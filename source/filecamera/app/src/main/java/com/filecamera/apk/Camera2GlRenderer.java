package com.filecamera.apk;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把相机帧 + 水印位图用 OpenGL 合成到 MediaCodec 的输入 Surface 上。
 *
 * <p>相机输出（横向）→ OES 纹理 → 按 rotation 旋转到最终方向 → 画到编码器 Surface；
 * 水印是一张与编码画面同尺寸的透明位图，叠在相机帧上面。这样编码出来的 MP4 本身就带水印，
 * 不再需要 .wmp4 尾部数据和播放时叠印。
 *
 * <p>所有 GL 操作都在独立线程；相机 SurfaceTexture 的 onFrameAvailable 会 post 到该线程合并渲染。
 */
final class Camera2GlRenderer {

    private static final String TAG = "Camera2GlRenderer";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final long START_TIMEOUT_MS = 4000;

    private static final float[] FULL_QUAD = {
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
    };
    private static final float[] CAMERA_TEX_COORDS = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
    };
    // Bitmap 第 0 行在纹理顶部；GL 的 v=0 在底部，所以这里上下翻转
    private static final float[] OVERLAY_TEX_COORDS = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };

    interface Callback {
        void onError(Throwable error);
    }

    private final Callback callback;
    private HandlerThread thread;
    private Handler handler;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;

    private int cameraProgram;
    private int overlayProgram;
    private int cameraTextureId;
    private int overlayTextureId;
    private SurfaceTexture cameraTexture;
    private Surface cameraSurface;

    private int cameraPosLoc, cameraTexLoc, cameraMvpLoc, cameraSamplerLoc;
    private int overlayPosLoc, overlayTexLoc, overlayMvpLoc, overlaySamplerLoc;

    private FloatBuffer quadBuffer;
    private FloatBuffer cameraTexBuffer;
    private FloatBuffer overlayTexBuffer;

    private final float[] texMatrix = new float[16];
    private final float[] mvpMatrix = new float[16];
    private final float[] identity = new float[16];

    private Size encoderSize;
    private Size overlayDisplaySize;
    private int overlayRotation;
    private Bitmap overlayBitmap;
    private Bitmap overlayDisplayBitmap;
    private Canvas overlayCanvas;
    private boolean hasOverlay;

    private volatile boolean running;
    private final AtomicBoolean framePending = new AtomicBoolean(false);
    private final AtomicBoolean errorReported = new AtomicBoolean(false);

    Camera2GlRenderer(Callback callback) {
        this.callback = callback;
    }

    /**
     * 启动 GL，返回给相机会话用的 Surface（相机帧从它进来）。
     *
     * @param encoderSurface  MediaCodec.createInputSurface()
     * @param cameraSize      相机输出尺寸（横向）
     * @param encoderSize     编码尺寸（竖屏或横屏）
     * @param cameraRotation  相机帧旋转角度（0/90/180/270）
     * @param displaySize     水印的“正立”尺寸；通常等于 encoderSize，回退时可能是交换后的尺寸
     * @param overlayRotation 水印位图旋转到编码画面的角度（0/90/180/270）
     */
    Surface start(Surface encoderSurface, Size cameraSize, Size encoderSize,
                  int cameraRotation, Size displaySize, int overlayRotation,
                  JSONObject initialWatermark) throws Exception {
        this.encoderSize = encoderSize;
        this.overlayDisplaySize = displaySize;
        this.overlayRotation = overlayRotation;

        thread = new HandlerThread("Camera2Gl");
        thread.start();
        handler = new Handler(thread.getLooper());

        final CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];
        handler.post(() -> {
            try {
                initGl(encoderSurface, cameraSize, cameraRotation, initialWatermark);
                running = true;
            } catch (Throwable t) {
                failure[0] = t;
                releaseGl();
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            stop();
            throw new IOException("GL 初始化超时");
        }
        if (failure[0] != null) {
            stop();
            throw new IOException("GL 初始化失败", failure[0]);
        }
        if (cameraSurface == null) {
            stop();
            throw new IOException("GL 相机 Surface 不可用");
        }
        return cameraSurface;
    }

    /** 更新水印（可在任意线程调用）。wm 为空表示不叠水印。 */
    void updateWatermark(JSONObject wm) {
        Handler h = handler;
        if (h == null) return;
        h.post(() -> {
            if (!running) return;
            try {
                drawOverlay(wm);
            } catch (Throwable t) {
                notifyError(t);
            }
        });
    }

    void stop() {
        running = false;
        Handler h = handler;
        if (h != null) {
            final CountDownLatch latch = new CountDownLatch(1);
            h.post(() -> {
                try {
                    releaseGl();
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        handler = null;
        cameraSurface = null;
    }

    // region --- GL 初始化 / 释放 ---

    private void initGl(Surface encoderSurface, Size cameraSize, int cameraRotation,
                        JSONObject initialWatermark) throws Exception {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new IOException("eglGetDisplay 失败");

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new IOException("eglInitialize 失败");
        }

        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0) {
            throw new IOException("没有可用的 EGLConfig");
        }
        eglConfig = configs[0];

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw new IOException("eglCreateContext 失败");

        int[] surfaceAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface,
                surfaceAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw new IOException("eglCreateWindowSurface 失败");

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new IOException("eglMakeCurrent 失败");
        }

        cameraProgram = buildProgram(VERTEX_SHADER, CAMERA_FRAGMENT_SHADER);
        overlayProgram = buildProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER);

        cameraPosLoc = GLES20.glGetAttribLocation(cameraProgram, "aPosition");
        cameraTexLoc = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord");
        cameraMvpLoc = GLES20.glGetUniformLocation(cameraProgram, "uMvp");
        cameraSamplerLoc = GLES20.glGetUniformLocation(cameraProgram, "sTexture");

        overlayPosLoc = GLES20.glGetAttribLocation(overlayProgram, "aPosition");
        overlayTexLoc = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord");
        overlayMvpLoc = GLES20.glGetUniformLocation(overlayProgram, "uMvp");
        overlaySamplerLoc = GLES20.glGetUniformLocation(overlayProgram, "sTexture");

        quadBuffer = floatBuffer(FULL_QUAD);
        cameraTexBuffer = floatBuffer(CAMERA_TEX_COORDS);
        overlayTexBuffer = floatBuffer(OVERLAY_TEX_COORDS);

        Matrix.setIdentityM(identity, 0);
        Matrix.setIdentityM(mvpMatrix, 0);
        // Camera2 的 SurfaceTexture 是传感器方向；Matrix.rotateM 正角度是逆时针，
        // 这里取反，才能得到与预览一致的顺时针旋转。
        Matrix.rotateM(mvpMatrix, 0, (360 - cameraRotation) % 360, 0f, 0f, 1f);

        cameraTextureId = createOesTexture();
        cameraTexture = new SurfaceTexture(cameraTextureId);
        cameraTexture.setDefaultBufferSize(cameraSize.getWidth(), cameraSize.getHeight());
        cameraSurface = new Surface(cameraTexture);
        cameraTexture.setOnFrameAvailableListener(st -> {
            if (running && framePending.compareAndSet(false, true)) {
                Handler h = handler;
                if (h != null) h.post(this::drawFrame);
            }
        }, handler);

        overlayTextureId = create2dTexture();
        overlayBitmap = Bitmap.createBitmap(encoderSize.getWidth(), encoderSize.getHeight(),
                Bitmap.Config.ARGB_8888);
        overlayCanvas = new Canvas(overlayBitmap);
        if (overlayRotation != 0) {
            overlayDisplayBitmap = Bitmap.createBitmap(overlayDisplaySize.getWidth(),
                    overlayDisplaySize.getHeight(), Bitmap.Config.ARGB_8888);
        }
        drawOverlay(initialWatermark);

        GLES20.glViewport(0, 0, encoderSize.getWidth(), encoderSize.getHeight());
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        checkGlError("initGl");
    }

    private void releaseGl() {
        running = false;
        try {
            if (cameraTexture != null) {
                cameraTexture.setOnFrameAvailableListener(null);
                cameraTexture.release();
                cameraTexture = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (cameraSurface != null) {
                cameraSurface.release();
                cameraSurface = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (overlayBitmap != null) {
                overlayBitmap.recycle();
                overlayBitmap = null;
            }
            if (overlayDisplayBitmap != null) {
                overlayDisplayBitmap.recycle();
                overlayDisplayBitmap = null;
            }
        } catch (Exception ignored) {
        }
        if (cameraProgram != 0) {
            GLES20.glDeleteProgram(cameraProgram);
            cameraProgram = 0;
        }
        if (overlayProgram != 0) {
            GLES20.glDeleteProgram(overlayProgram);
            overlayProgram = 0;
        }
        if (cameraTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{cameraTextureId}, 0);
            cameraTextureId = 0;
        }
        if (overlayTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{overlayTextureId}, 0);
            overlayTextureId = 0;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
    }

    // endregion

    // region --- 逐帧渲染 ---

    private void drawFrame() {
        framePending.set(false);
        if (!running || cameraTexture == null || eglSurface == EGL14.EGL_NO_SURFACE) return;
        try {
            cameraTexture.updateTexImage();
            cameraTexture.getTransformMatrix(texMatrix);
            long timestampNs = cameraTexture.getTimestamp();
            if (timestampNs <= 0) timestampNs = System.nanoTime();

            GLES20.glViewport(0, 0, encoderSize.getWidth(), encoderSize.getHeight());
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            drawCamera();
            if (hasOverlay) drawOverlayQuad();

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs);
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                throw new IOException("eglSwapBuffers 失败: " + EGL14.eglGetError());
            }
        } catch (Throwable t) {
            notifyError(t);
        }
    }

    private void drawCamera() {
        GLES20.glUseProgram(cameraProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(cameraSamplerLoc, 0);
        GLES20.glUniformMatrix4fv(cameraMvpLoc, 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(cameraTexLoc, 1, false, texMatrix, 0);

        GLES20.glEnableVertexAttribArray(cameraPosLoc);
        GLES20.glVertexAttribPointer(cameraPosLoc, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
        GLES20.glEnableVertexAttribArray(cameraTexLoc);
        GLES20.glVertexAttribPointer(cameraTexLoc, 2, GLES20.GL_FLOAT, false, 0, cameraTexBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(cameraPosLoc);
        GLES20.glDisableVertexAttribArray(cameraTexLoc);
    }

    private void drawOverlayQuad() {
        GLES20.glUseProgram(overlayProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
        GLES20.glUniform1i(overlaySamplerLoc, 0);
        GLES20.glUniformMatrix4fv(overlayMvpLoc, 1, false, identity, 0);

        GLES20.glEnableVertexAttribArray(overlayPosLoc);
        GLES20.glVertexAttribPointer(overlayPosLoc, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
        GLES20.glEnableVertexAttribArray(overlayTexLoc);
        GLES20.glVertexAttribPointer(overlayTexLoc, 2, GLES20.GL_FLOAT, false, 0, overlayTexBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(overlayPosLoc);
        GLES20.glDisableVertexAttribArray(overlayTexLoc);
    }

    /** 把水印画进与编码画面同尺寸的透明位图并上传纹理。必须在 GL 线程调用。 */
    private void drawOverlay(JSONObject wm) {
        if (wm == null || overlayBitmap == null) {
            hasOverlay = false;
            return;
        }
        try {
            overlayBitmap.eraseColor(Color.TRANSPARENT);
            if (overlayRotation == 0) {
                WatermarkBitmapRenderer.drawWatermark(overlayCanvas,
                        overlayBitmap.getWidth(), overlayBitmap.getHeight(), wm);
            } else {
                overlayDisplayBitmap.eraseColor(Color.TRANSPARENT);
                Canvas displayCanvas = new Canvas(overlayDisplayBitmap);
                WatermarkBitmapRenderer.drawWatermark(displayCanvas,
                        overlayDisplayBitmap.getWidth(), overlayDisplayBitmap.getHeight(), wm);
                Canvas canvas = overlayCanvas;
                int sw = overlayBitmap.getWidth();
                int sh = overlayBitmap.getHeight();
                int save = canvas.save();
                if (overlayRotation == 90) {
                    // 正立竖图 → 横向编码帧：旋转 -90°，播放时由 tkhd 旋转 +90° 还原
                    canvas.translate(0, sh);
                    canvas.rotate(-90);
                } else if (overlayRotation == 270) {
                    canvas.translate(sw, 0);
                    canvas.rotate(90);
                } else {
                    canvas.rotate(overlayRotation, sw / 2f, sh / 2f);
                }
                canvas.drawBitmap(overlayDisplayBitmap, 0, 0, null);
                canvas.restoreToCount(save);
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0);
            checkGlError("uploadOverlay");
            hasOverlay = true;
        } catch (Throwable t) {
            hasOverlay = false;
            throw t;
        }
    }

    // endregion

    // region --- 工具 ---

    private void notifyError(Throwable t) {
        if (errorReported.compareAndSet(false, true)) {
            Log.e(TAG, "GL 渲染失败", t);
            callback.onError(t);
        }
    }

    private static FloatBuffer floatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    private static int createOesTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("createOesTexture");
        return textures[0];
    }

    private static int create2dTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("create2dTexture");
        return textures[0];
    }

    private static int buildProgram(String vertexSource, String fragmentSource) throws IOException {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IOException("着色器链接失败: " + log);
        }
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        return program;
    }

    private static int compileShader(int type, String source) throws IOException {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IOException("着色器编译失败: " + log);
        }
        return shader;
    }

    private static void checkGlError(String op) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.w(TAG, op + " glError=" + error);
        }
    }

    private static final String VERTEX_SHADER =
            "uniform mat4 uMvp;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = uMvp * aPosition;\n" +
            "  vTexCoord = (uTexMatrix * aTexCoord).xy;\n" +
            "}\n";

    private static final String CAMERA_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
            "}\n";

    private static final String OVERLAY_VERTEX_SHADER =
            "uniform mat4 uMvp;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = uMvp * aPosition;\n" +
            "  vTexCoord = aTexCoord.xy;\n" +
            "}\n";

    private static final String OVERLAY_FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform sampler2D sTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
            "}\n";

    // endregion
}
