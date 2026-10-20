package com.filecamera.apk;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Camera2 控制器：预览 / 拍照 / 点击对焦 / 变焦 / 闪光 / 边录边烧 MP4。
 *
 * <p>录像链路：
 * <pre>
 *   相机 → SurfaceTexture → Camera2GlRenderer（旋转 + 叠水印）→ MediaCodec 输入 Surface
 *        → H.264/AAC 样本 → Camera2MuxerSink（FragmentedMp4Muxer，1 秒分片）→ .mp4.part
 * </pre>
 * 水印在录制时就已经烧进画面，输出就是普通 .mp4。
 * 分片封装保证录制中进程被杀时，已落盘的分片仍可播，启动扫描补入库即可。
 *
 * <p>线程模型：相机回调在 {@link #cameraHandler}；编码器排空在两个独立线程；
 * GL 在 {@link Camera2GlRenderer} 自己的线程；界面回调统一 post 到主线程。
 */
public class Camera2Controller implements TextureView.SurfaceTextureListener {

    private static final String TAG = "Camera2Controller";

    public static final int LENS_FACING_FRONT = CameraCharacteristics.LENS_FACING_FRONT;
    public static final int LENS_FACING_BACK = CameraCharacteristics.LENS_FACING_BACK;

    public static final int FLASH_OFF = 0;
    public static final int FLASH_ON = 1;
    public static final int FLASH_AUTO = 2;

    private static final long STOP_TIMEOUT_MS = 6000L;
    private static final long ENCODER_DEQUEUE_TIMEOUT_US = 10_000L;
    private static final int VIDEO_FRAME_RATE = 30;
    private static final long MAX_PHOTO_PIXELS = 12_000_000L;

    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_CHANNEL_COUNT = 1;
    private static final int AUDIO_BIT_RATE = 96_000;

    public interface Listener {
        void onCameraReady(int lensFacing, boolean hasFlash, float minZoom, float maxZoom);

        void onCameraError(String message);

        void onPhotoReady(File photoFile);

        void onPhotoError(String message);

        /** 录像收尾完成（主线程回调）；error 非空时 file 可能不可用。 */
        void onRecordingFinished(File file, int rotation, Throwable error);
    }

    private final Context context;
    private final TextureView previewView;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread cameraThread;
    private final Handler cameraHandler;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private SurfaceTexture surfaceTexture;
    private Surface previewSurface;
    private ImageReader photoReader;
    private CameraCharacteristics characteristics;
    private String cameraId;

    private volatile boolean started = false;
    private volatile boolean surfaceReady = false;
    private volatile boolean opening = false;
    private volatile int lensFacing = LENS_FACING_BACK;
    private volatile boolean videoMode = false;

    private Size previewSize;
    private Size photoSize;
    private Size videoCameraSize;
    private int sensorOrientation = 90;
    private Rect activeArray;
    private boolean hasFlash = false;
    private boolean supportsZoomRatio = false;
    private float minZoom = 1f;
    private float maxZoom = 1f;
    private volatile float currentZoom = 1f;
    private int flashMode = FLASH_OFF;
    /** Activity 设置里的拍照比例（16:9 / 4:3）。 */
    private volatile float targetAspectRatio = 16f / 9f;
    /** 由 Activity 的方向监听器提供（Surface.ROTATION_*），用于照片/视频方向。 */
    private volatile int targetRotation = Surface.ROTATION_0;

    private MeteringRectangle focusRegion;
    private MeteringRectangle meteringRegion;
    private final Runnable clearFocusRunnable;

    private volatile File pendingPhotoFile;

    // ---- 录像状态 ----
    private final AtomicBoolean recordingCallbackSent = new AtomicBoolean(false);
    private final AtomicInteger activeWriters = new AtomicInteger();
    private volatile boolean recording = false;
    private volatile boolean stopping = false;
    private volatile boolean stopRequested = false;
    private volatile boolean pendingClose = false;
    private volatile File videoFile;
    private volatile int videoRotation;
    private volatile Throwable recordingError;
    private volatile JSONObject liveWatermark;
    private volatile long recordingStartUs;
    /** 视频/音频共用的时间基准：相机 PTS 域（可能与 System.nanoTime 的 MONOTONIC 域不同）。 */
    private volatile boolean videoClockReady;
    private volatile long videoBasePtsUs;
    private volatile long cameraClockOffsetUs;
    private long stopDeadlineMs = 0;

    private MediaCodec videoEncoder;
    private Surface videoSurface;
    private Surface videoCameraSurface;
    private Camera2GlRenderer glRenderer;
    private Camera2MuxerSink muxerSink;

    private MediaCodec audioEncoder;
    private AudioRecord audioRecord;
    private Thread videoThread;
    private Thread audioThread;
    private boolean audioEnabled;
    private volatile boolean audioStopRequested;

    public Camera2Controller(Context context, TextureView previewView, Listener listener) {
        this.context = context.getApplicationContext();
        this.previewView = previewView;
        this.listener = listener;
        this.cameraThread = new HandlerThread("Camera2Controller");
        this.cameraThread.start();
        this.cameraHandler = new Handler(cameraThread.getLooper());
        this.clearFocusRunnable = () -> {
            focusRegion = null;
            meteringRegion = null;
            if (session != null) {
                try {
                    CaptureRequest.Builder b = buildPreviewRequest();
                    b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                    session.capture(b.build(), null, cameraHandler);
                } catch (Exception e) {
                    Log.w(TAG, "取消对焦失败", e);
                }
                updateRepeatingRequest();
            }
        };
        previewView.setSurfaceTextureListener(this);
        if (previewView.isAvailable()) {
            surfaceTexture = previewView.getSurfaceTexture();
            surfaceReady = true;
        }
    }

    // region --- 生命周期 ---

    public void start() {
        started = true;
        // 权限弹窗/首帧布局期间可能错过 onSurfaceTextureAvailable，
        // 这里主动补一次 surface 状态，避免 start 后一直等待不到预览。
        if (!surfaceReady && previewView.isAvailable()) {
            surfaceTexture = previewView.getSurfaceTexture();
            surfaceReady = surfaceTexture != null;
        }
        cameraHandler.post(this::openCameraIfReady);
    }

    public void stop() {
        started = false;
        if (recording) {
            pendingClose = true;
            stopRecording();
            return;
        }
        cameraHandler.post(this::closeCameraInternal);
    }

    public void release() {
        stop();
        cameraHandler.postDelayed(() -> {
            try {
                cameraThread.quitSafely();
            } catch (Exception ignored) {
            }
        }, 1500);
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
        surfaceTexture = texture;
        surfaceReady = true;
        cameraHandler.post(() -> {
            if (!started) return;
            // 权限弹窗/页面恢复可能导致 TextureView 的 Surface 被重建，
            // 旧 session 仍绑定在失效的 Surface 上，表现为预览黑屏。
            // 此时需要关掉旧相机，用新的 Surface 重新开会话。
            if (cameraDevice != null && !recording) {
                closeCameraInternal();
            }
            openCameraIfReady();
        });
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
        mainHandler.post(this::applyPreviewTransform);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
        surfaceReady = false;
        surfaceTexture = null;
        cameraHandler.post(this::closeCameraInternal);
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
    }

    // endregion

    // region --- 对外状态 / 设置 ---

    public int getLensFacing() {
        return lensFacing;
    }

    public int getSensorOrientation() {
        return sensorOrientation;
    }

    public float getMinZoom() {
        return minZoom;
    }

    public float getMaxZoom() {
        return maxZoom;
    }

    public float getZoomRatio() {
        return currentZoom;
    }

    public boolean isVideoSupported() {
        return videoCameraSize != null;
    }

    public boolean isRecording() {
        return recording;
    }

    public void setVideoMode(boolean video) {
        if (recording || videoMode == video) return;
        videoMode = video;
        cameraHandler.post(() -> {
            if (cameraDevice != null) createSession();
        });
    }

    public void switchCamera(int targetLensFacing) {
        if (recording) return;
        if (targetLensFacing == lensFacing) return;
        lensFacing = targetLensFacing;
        currentZoom = 1f;
        cameraHandler.post(() -> {
            closeCameraInternal();
            openCameraIfReady();
        });
    }

    public void setZoomRatio(float ratio) {
        float clamped = Math.max(minZoom, Math.min(maxZoom, ratio));
        currentZoom = clamped;
        cameraHandler.post(this::updateRepeatingRequest);
    }

    public void setFlashMode(int mode) {
        flashMode = mode;
    }

    /** 设置拍照比例后重建会话（16:9 / 4:3）。 */
    public void setTargetAspectRatio(float ratio) {
        if (ratio <= 0 || Math.abs(targetAspectRatio - ratio) < 0.01f) return;
        targetAspectRatio = ratio;
        cameraHandler.post(() -> {
            if (cameraDevice != null && !recording) {
                closeCameraInternal();
                openCameraIfReady();
            }
        });
    }

    /** Activity 方向监听器更新物理方向时调用（Surface.ROTATION_*）。 */
    public void setTargetRotation(int rotation) {
        targetRotation = rotation;
    }

    /** 照片/视频应当写入的旋转角度（0/90/180/270）。 */
    public int getJpegRotation() {
        return computeJpegRotation();
    }

    /** 预览画面相对传感器的旋转角度（跟随屏幕方向；本页锁竖屏时恒为 sensorOrientation）。 */
    public int getPreviewRotation() {
        return previewRotationDegrees();
    }

    public void takePhoto(File file) {
        pendingPhotoFile = file;
        cameraHandler.post(() -> captureStill(file, computeJpegRotation()));
    }

    public void focusAt(float viewX, float viewY) {
        cameraHandler.post(() -> focusInternal(viewX, viewY));
    }

    /** 录像中实时更新水印（每秒一次即可）。 */
    public void updateLiveWatermark(JSONObject wm) {
        liveWatermark = wm;
        Camera2GlRenderer renderer = glRenderer;
        if (renderer != null) renderer.updateWatermark(wm);
    }

    // endregion

    // region --- 打开 / 关闭相机 ---

    private void openCameraIfReady() {
        if (!started || !surfaceReady || cameraDevice != null || opening) return;
        if (surfaceTexture == null) return;
        opening = true;
        try {
            if (cameraManager == null) {
                cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            }
            cameraId = findCameraId(lensFacing);
            if (cameraId == null) {
                opening = false;
                postCameraError("未找到摄像头");
                return;
            }
            characteristics = cameraManager.getCameraCharacteristics(cameraId);
            sensorOrientation = intValue(characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION), 90);
            activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            hasFlash = Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));

            List<CaptureRequest.Key<?>> requestKeys = characteristics.getAvailableCaptureRequestKeys();
            supportsZoomRatio = Build.VERSION.SDK_INT >= 30
                    && requestKeys != null
                    && requestKeys.contains(CaptureRequest.CONTROL_ZOOM_RATIO);
            readZoomRange();
            currentZoom = Math.max(minZoom, Math.min(maxZoom, currentZoom));

            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                opening = false;
                postCameraError("无法读取相机配置");
                return;
            }
            previewSize = choosePreviewSize(map, targetAspectRatio);
            photoSize = choosePhotoSize(map, targetAspectRatio);
            // 录像输入按设置比例取相机画幅：4:3 或 16:9
            videoCameraSize = targetAspectRatio > 1.5f
                    ? choosePreviewSize(map, 16f / 9f)
                    : chooseFourThreeSize(map);
            if (previewSize == null) {
                opening = false;
                postCameraError("相机没有可用预览尺寸");
                return;
            }

            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(surfaceTexture);
            mainHandler.post(this::applyPreviewTransform);

            cameraManager.openCamera(cameraId, deviceStateCallback, cameraHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            opening = false;
            Log.e(TAG, "打开相机失败", e);
            postCameraError("打开相机失败");
        }
    }

    private final CameraDevice.StateCallback deviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            opening = false;
            cameraDevice = camera;
            createSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            opening = false;
            if (cameraDevice == camera) cameraDevice = null;
            closeSessionAndReader();
            camera.close();
            postCameraError("相机已断开");
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            opening = false;
            if (cameraDevice == camera) cameraDevice = null;
            closeSessionAndReader();
            camera.close();
            Log.e(TAG, "相机错误: " + error);
            postCameraError("相机发生错误");
        }
    };

    private String findCameraId(int targetLens) throws CameraAccessException {
        String fallback = null;
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == targetLens) {
                Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                if (level != null && level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                    if (fallback == null) fallback = id;
                } else {
                    return id;
                }
            }
        }
        return fallback;
    }

    private void closeSessionAndReader() {
        if (session != null) {
            try {
                session.stopRepeating();
            } catch (Exception ignored) {
            }
            try {
                session.close();
            } catch (Exception ignored) {
            }
            session = null;
        }
        if (photoReader != null) {
            try {
                photoReader.close();
            } catch (Exception ignored) {
            }
            photoReader = null;
        }
    }

    private void closeCameraInternal() {
        if (recording) {
            pendingClose = true;
            return;
        }
        closeSessionAndReader();
        if (cameraDevice != null) {
            try {
                cameraDevice.close();
            } catch (Exception ignored) {
            }
            cameraDevice = null;
        }
        if (previewSurface != null) {
            try {
                previewSurface.release();
            } catch (Exception ignored) {
            }
            previewSurface = null;
        }
        opening = false;
    }

    // endregion

    // region --- 会话创建 / 请求 ---

    private void createSession() {
        if (cameraDevice == null || previewSurface == null) return;
        if (recording) return;
        closeSessionAndReader();
        try {
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            if (!videoMode && photoSize != null) {
                photoReader = ImageReader.newInstance(
                        photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 2);
                photoReader.setOnImageAvailableListener(this::onPhotoAvailable, cameraHandler);
                surfaces.add(photoReader.getSurface());
            }
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraSession) {
                    session = cameraSession;
                    updateRepeatingRequest();
                    postCameraReady();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraSession) {
                    Log.e(TAG, "创建相机会话失败");
                    postCameraError("创建相机会话失败");
                }
            }, cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "创建相机会话异常", e);
            postCameraError("创建相机会话异常");
        }
    }

    private CaptureRequest.Builder buildPreviewRequest() throws CameraAccessException {
        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(previewSurface);
        if (recording && videoCameraSurface != null) {
            builder.addTarget(videoCameraSurface);
        }
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AF_MODE,
                videoMode ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        applyZoom(builder);
        if (focusRegion != null) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusRegion});
        }
        if (meteringRegion != null) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{meteringRegion});
        }
        return builder;
    }

    private void updateRepeatingRequest() {
        if (session == null || cameraDevice == null) return;
        try {
            session.setRepeatingRequest(buildPreviewRequest().build(), null, cameraHandler);
        } catch (Exception e) {
            Log.w(TAG, "设置预览请求失败", e);
        }
    }

    private void applyZoom(CaptureRequest.Builder builder) {
        if (supportsZoomRatio) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom);
        } else {
            Rect crop = cropRegionForZoom(currentZoom);
            if (crop != null) builder.set(CaptureRequest.SCALER_CROP_REGION, crop);
        }
    }

    private Rect cropRegionForZoom(float zoom) {
        if (activeArray == null) return null;
        float z = Math.max(1f, zoom);
        int w = Math.max(1, Math.round(activeArray.width() / z));
        int h = Math.max(1, Math.round(activeArray.height() / z));
        int cx = activeArray.centerX();
        int cy = activeArray.centerY();
        int left = Math.max(activeArray.left, cx - w / 2);
        int top = Math.max(activeArray.top, cy - h / 2);
        int right = Math.min(activeArray.right, left + w);
        int bottom = Math.min(activeArray.bottom, top + h);
        return new Rect(left, top, right, bottom);
    }

    private void readZoomRange() {
        minZoom = 1f;
        maxZoom = 1f;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Range<Float> range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (range != null) {
                    minZoom = range.getLower();
                    maxZoom = range.getUpper();
                }
            }
            if (maxZoom <= minZoom) {
                Float max = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                maxZoom = max == null ? 1f : max;
                minZoom = 1f;
            }
        } catch (Exception e) {
            Log.w(TAG, "读取变焦范围失败", e);
            minZoom = 1f;
            maxZoom = 1f;
        }
    }

    private void focusInternal(float viewX, float viewY) {
        if (session == null || activeArray == null || previewSize == null) return;
        int vw = previewView.getWidth();
        int vh = previewView.getHeight();
        if (vw <= 0 || vh <= 0) return;

        float[] normalized = viewToPreviewNormalized(viewX, viewY, vw, vh);
        float nx = normalized[0];
        float ny = normalized[1];
        Rect crop = cropRegionForZoom(currentZoom);
        if (crop == null) crop = activeArray;
        int px = crop.left + Math.round(nx * crop.width());
        int py = crop.top + Math.round(ny * crop.height());
        int size = Math.max(40, Math.min(activeArray.width(), activeArray.height()) / 10);
        int half = size / 2;
        Rect r = new Rect(
                clamp(px - half, activeArray.left, activeArray.right - 1),
                clamp(py - half, activeArray.top, activeArray.bottom - 1),
                clamp(px + half, activeArray.left + 1, activeArray.right),
                clamp(py + half, activeArray.top + 1, activeArray.bottom));
        focusRegion = new MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX);
        meteringRegion = focusRegion;

        try {
            CaptureRequest.Builder b = buildPreviewRequest();
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(b.build(), null, cameraHandler);
            updateRepeatingRequest();
            cameraHandler.removeCallbacks(clearFocusRunnable);
            cameraHandler.postDelayed(clearFocusRunnable, 3000);
        } catch (Exception e) {
            Log.w(TAG, "点击对焦失败", e);
        }
    }

    private float[] viewToPreviewNormalized(float x, float y, int vw, int vh) {
        int layoutW = previewView.getWidth();
        int layoutH = previewView.getHeight();
        if (layoutW <= 0 || layoutH <= 0) return new float[]{0.5f, 0.5f};
        float nx = clamp(x / layoutW, 0f, 1f);
        float ny = clamp(y / layoutH, 0f, 1f);
        if (lensFacing == LENS_FACING_FRONT) nx = 1f - nx;
        return new float[]{nx, ny};
    }

    // endregion

    // region --- 预览变换 ---

    private int previewRotationDegrees() {
        int displayDegrees = 0;
        try {
            if (previewView.getDisplay() != null) {
                switch (previewView.getDisplay().getRotation()) {
                    case Surface.ROTATION_90: displayDegrees = 90; break;
                    case Surface.ROTATION_180: displayDegrees = 180; break;
                    case Surface.ROTATION_270: displayDegrees = 270; break;
                    default: displayDegrees = 0; break;
                }
            }
        } catch (Exception ignored) {
        }
        return (sensorOrientation - displayDegrees + 360) % 360;
    }

    private void applyPreviewTransform() {
        if (previewSize == null) return;
        View parent = (View) previewView.getParent();
        if (parent == null) return;
        int vw = parent.getWidth();
        int vh = parent.getHeight();
        if (vw <= 0 || vh <= 0) return;

        int pw = previewSize.getWidth();
        int ph = previewSize.getHeight();

        // 只做等比 cover，保持相机画面比例：
        // 容器高宽比更大 → 高度对齐；否则宽度对齐。超出容器部分由父布局裁剪。
        float containerRatio = vh / (float) vw;
        float pictureRatio = ph / (float) pw;
        float scale;
        if (containerRatio > pictureRatio) {
            scale = vh / (float) ph;
        } else {
            scale = vw / (float) pw;
        }
        int layoutW = Math.round(pw * scale);
        int layoutH = Math.round(ph * scale);

        ViewGroup.LayoutParams lp = previewView.getLayoutParams();
        if (lp instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams rlp = (RelativeLayout.LayoutParams) lp;
            if (rlp.width != layoutW || rlp.height != layoutH
                    || rlp.getRules()[RelativeLayout.CENTER_IN_PARENT] == 0) {
                rlp.width = layoutW;
                rlp.height = layoutH;
                rlp.addRule(RelativeLayout.CENTER_IN_PARENT);
                previewView.setLayoutParams(rlp);
            }
        }
        previewView.setTransform(null);
        previewView.setRotation(0f);
        // 镜像必须绕 View 中心翻转；pivot 为 0 时负 scaleX 会把整个预览翻到容器外，
        // 前置摄像头就会表现为黑屏。刚布局完 getWidth() 可能还是 0，用 layoutW/H 兜底。
        float pivotX = previewView.getWidth() > 0 ? previewView.getWidth() / 2f : layoutW / 2f;
        float pivotY = previewView.getHeight() > 0 ? previewView.getHeight() / 2f : layoutH / 2f;
        previewView.setPivotX(pivotX);
        previewView.setPivotY(pivotY);
        // 前置只做一次左右翻转；设备前置输入本身已镜像，这里去掉原来的再次镜像。
        previewView.setScaleX(1f);
        previewView.setScaleY(1f);
    }

    // endregion

    // region --- 拍照 ---

    private int computeJpegRotation() {
        int degrees;
        switch (targetRotation) {
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
            default: degrees = 0; break;
        }
        if (lensFacing == LENS_FACING_FRONT) {
            return (sensorOrientation + degrees) % 360;
        }
        return (sensorOrientation - degrees + 360) % 360;
    }

    private void captureStill(File file, int rotation) {
        if (session == null || photoReader == null || cameraDevice == null) {
            pendingPhotoFile = null;
            postPhotoError("相机尚未就绪");
            return;
        }
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(photoReader.getSurface());
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            builder.set(CaptureRequest.JPEG_ORIENTATION, rotation);
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
            applyFlashForStill(builder);
            applyZoom(builder);
            session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession s, @NonNull CaptureRequest request,
                                            @NonNull CaptureFailure failure) {
                    Log.e(TAG, "拍照请求失败: " + failure.getReason());
                    pendingPhotoFile = null;
                    postPhotoError("拍照失败");
                }
            }, cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "发起拍照失败", e);
            pendingPhotoFile = null;
            postPhotoError("拍照失败");
        }
    }

    private void applyFlashForStill(CaptureRequest.Builder builder) {
        if (!hasFlash) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
            return;
        }
        switch (flashMode) {
            case FLASH_ON:
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE);
                break;
            case FLASH_AUTO:
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE);
                break;
            default:
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
                break;
        }
    }

    private void onPhotoAvailable(ImageReader reader) {
        Image image = null;
        File target = pendingPhotoFile;
        pendingPhotoFile = null;
        try {
            image = reader.acquireNextImage();
            if (image == null) {
                postPhotoError("拍照失败");
                return;
            }
            if (target == null) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("无法创建照片目录");
            }
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(data);
                out.flush();
                out.getFD().sync();
            }
            mainHandler.post(() -> listener.onPhotoReady(target));
        } catch (Exception e) {
            Log.e(TAG, "保存照片失败", e);
            if (target != null) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            postPhotoError("照片保存失败");
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // endregion

    // region --- 录像 ---

    public void startVideoRecording(File file, int rotation, JSONObject initialWatermark) {
        if (recording) return;
        if (videoCameraSize == null) {
            mainHandler.post(() -> listener.onRecordingFinished(null, rotation,
                    new IOException("本机不支持录像")));
            return;
        }
        recording = true;
        stopping = false;
        stopRequested = false;
        pendingClose = false;
        recordingCallbackSent.set(false);
        recordingError = null;
        audioStopRequested = false;
        audioEnabled = false;
        videoFile = file;
        videoRotation = rotation;
        liveWatermark = initialWatermark;
        videoClockReady = false;
        videoBasePtsUs = 0;
        cameraClockOffsetUs = 0;
        cameraHandler.post(() -> startVideoInternal(file, rotation, initialWatermark));
    }

    public void stopRecording() {
        if (!recording || stopping) return;
        stopping = true;
        stopRequested = true;
        cameraHandler.post(this::signalEosIfReady);
    }

    private void startVideoInternal(File file, int rotation, JSONObject initialWatermark) {
        if (cameraDevice == null || previewSurface == null) {
            failVideoStart(new IOException("相机尚未就绪"));
            return;
        }
        try {
            closeSessionAndReader();

            // 纯相机 → 编码器：输出尺寸直接用相机输出尺寸，不做任何方向/比例处理。
            Size cameraSize = videoCameraSize != null ? videoCameraSize : previewSize;
            Size usedSize = cameraSize;
            if (selectVideoEncoder(usedSize) == null) {
                usedSize = new Size(usedSize.getWidth() / 2, usedSize.getHeight() / 2);
            }
            if (selectVideoEncoder(usedSize) == null) {
                usedSize = new Size(usedSize.getWidth() / 2, usedSize.getHeight() / 2);
            }
            if (selectVideoEncoder(usedSize) == null) {
                throw new IOException("编码器不支持录像尺寸");
            }
            // 像素保持相机原始方向，方向写进 MP4 的 tkhd 旋转元数据。
            int mp4Rotation = rotation;

            MediaCodecInfo codecInfo = selectVideoEncoder(usedSize);
            if (codecInfo == null) throw new IOException("没有可用的 H.264 编码器");
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, usedSize.getWidth(), usedSize.getHeight());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(usedSize));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            videoEncoder = MediaCodec.createByCodecName(codecInfo.getName());
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            videoSurface = videoEncoder.createInputSurface();
            videoEncoder.start();

            FileOutputStream stream = new FileOutputStream(file);
            audioEnabled = initAudio();
            muxerSink = new Camera2MuxerSink(stream, mp4Rotation,
                    audioEnabled ? 2 : 1);

            recordingStartUs = System.nanoTime() / 1000;
            Log.i(TAG, "录像开始: startUs=" + recordingStartUs + " size=" + usedSize
                    + " mp4Rotation=" + mp4Rotation + " audio=" + audioEnabled);

            // GL 只做同尺寸合成：相机 → GL（叠水印）→ 编码器，尺寸/方向元数据都不动。
            boolean portrait = rotation == 90 || rotation == 270;
            Size displaySize = portrait
                    ? new Size(cameraSize.getHeight(), cameraSize.getWidth())
                    : cameraSize;
            // 相机纹理裸采样在横屏时是 180°，竖屏时是 0°；四方向按此分情况。
            int glCameraRotation = (rotation == 90 || rotation == 270) ? 0 : 180;
            glRenderer = new Camera2GlRenderer(this::onGlError);
            videoCameraSurface = glRenderer.start(videoSurface, cameraSize, usedSize,
                    glCameraRotation, displaySize, rotation, initialWatermark);

            captureSessionForRecording();
        } catch (Throwable e) {
            Log.e(TAG, "启动录像失败", e);
            failVideoStart(e);
        }
    }

    private void captureSessionForRecording() {
        try {
            List<Surface> surfaces = Arrays.asList(previewSurface, videoCameraSurface);
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraSession) {
                    session = cameraSession;
                    updateRepeatingRequest();
                    activeWriters.set(audioEnabled ? 2 : 1);
                    startVideoThread();
                    if (audioEnabled) startAudioThread();
                    if (stopRequested) signalEosIfReady();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraSession) {
                    failVideoStart(new IOException("创建录像会话失败"));
                }
            }, cameraHandler);
        } catch (Throwable e) {
            failVideoStart(e);
        }
    }

    private void signalEosIfReady() {
        if (videoEncoder == null) return;
        stopDeadlineMs = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MS;
        audioStopRequested = true;
        try {
            if (session != null) {
                session.stopRepeating();
                session.close();
                session = null;
            }
        } catch (Exception ignored) {
        }
        try {
            videoEncoder.signalEndOfInputStream();
        } catch (Exception e) {
            Log.w(TAG, "通知视频编码器结束失败", e);
            stopDeadlineMs = SystemClock.elapsedRealtime();
        }
    }

    private void startVideoThread() {
        if (videoThread != null) return;
        videoThread = new Thread(this::videoEncodeLoop, "Camera2VideoEncoder");
        videoThread.start();
    }

    private void videoEncodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        Throwable error = null;
        boolean firstVideoSampleLogged = false;
        try {
            while (true) {
                if (stopping && stopDeadlineMs > 0
                        && SystemClock.elapsedRealtime() > stopDeadlineMs) {
                    Log.w(TAG, "视频编码器 EOS 超时，按已有分片收尾");
                    break;
                }
                int index = videoEncoder.dequeueOutputBuffer(info, ENCODER_DEQUEUE_TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerSink.onFormat(Camera2MuxerSink.TRACK_VIDEO, videoEncoder.getOutputFormat());
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!stopping) continue;
                } else if (index >= 0) {
                    ByteBuffer buffer = videoEncoder.getOutputBuffer(index);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (buffer != null && info.size > 0 && !config) {
                        if (!videoClockReady) {
                            videoBasePtsUs = info.presentationTimeUs;
                            cameraClockOffsetUs = info.presentationTimeUs - System.nanoTime() / 1000;
                            videoClockReady = true;
                            Log.i(TAG, "视频时间基准: basePts=" + videoBasePtsUs
                                    + " clockOffsetUs=" + cameraClockOffsetUs);
                        }
                        info.presentationTimeUs = Math.max(0, info.presentationTimeUs - videoBasePtsUs);
                        if (!firstVideoSampleLogged) {
                            firstVideoSampleLogged = true;
                            Log.i(TAG, "首个视频样本 pts=" + info.presentationTimeUs
                                    + " basePts=" + videoBasePtsUs);
                        }
                        muxerSink.write(Camera2MuxerSink.TRACK_VIDEO, buffer, info);
                    }
                    videoEncoder.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            error = t;
            Log.e(TAG, "视频编码失败", t);
        } finally {
            closeVideoResources();
        }
        if (error != null) recordingError = error;
        onWriterFinished();
    }

    private void startAudioThread() {
        if (audioThread != null) return;
        audioThread = new Thread(this::audioEncodeLoop, "Camera2AudioEncoder");
        audioThread.start();
    }

    private void audioEncodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        Throwable error = null;
        byte[] pcm = new byte[4096];
        long frames = 0;
        long lastPtsUs = 0;
        boolean eosQueued = false;
        boolean eos = false;
        boolean audioFormatReported = false;
        boolean firstAudioSampleLogged = false;
        long audioStartOffsetUs = 0;
        try {
            // 相机时间戳和 System.nanoTime 可能不在同一个时钟域（深度睡眠后差异很大），
            // 所以先等第一帧视频建立基准，再用同一个“相机时钟”给音频打时间戳。
            long waitUntil = SystemClock.elapsedRealtime() + 3000;
            while (!videoClockReady && recording && !stopRequested
                    && SystemClock.elapsedRealtime() < waitUntil) {
                Thread.sleep(5);
            }
            if (!videoClockReady) {
                videoBasePtsUs = System.nanoTime() / 1000;
                cameraClockOffsetUs = 0;
                videoClockReady = true;
            }
            audioRecord.startRecording();
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException("AudioRecord 未能开始录音");
            }
            audioStartOffsetUs = Math.max(0,
                    (System.nanoTime() / 1000 + cameraClockOffsetUs) - videoBasePtsUs);
            Log.i(TAG, "音频线程启动 offsetUs=" + audioStartOffsetUs
                    + " basePtsUs=" + videoBasePtsUs);
            while (true) {
                if (stopping && stopDeadlineMs > 0
                        && SystemClock.elapsedRealtime() > stopDeadlineMs + 2000) {
                    Log.w(TAG, "音频编码器 EOS 超时");
                    break;
                }
                if ((stopRequested || audioStopRequested) && !eosQueued) {
                    try {
                        audioRecord.stop();
                    } catch (Exception ignored) {
                    }
                    eosQueued = queueAudioEos(lastPtsUs);
                }

                if (!eosQueued) {
                    int read = audioRecord.read(pcm, 0, pcm.length);
                    if (read > 0) {
                        int input = audioEncoder.dequeueInputBuffer(ENCODER_DEQUEUE_TIMEOUT_US);
                        if (input >= 0) {
                            ByteBuffer ib = audioEncoder.getInputBuffer(input);
                            if (ib != null) {
                                ib.clear();
                                ib.put(pcm, 0, read);
                                lastPtsUs = audioStartOffsetUs
                                        + frames * 1_000_000L / AUDIO_SAMPLE_RATE;
                                audioEncoder.queueInputBuffer(input, 0, read, lastPtsUs, 0);
                                frames += read / 2;
                            }
                        }
                    } else if (read == AudioRecord.ERROR_INVALID_OPERATION
                            || read == AudioRecord.ERROR_BAD_VALUE) {
                        throw new IOException("AudioRecord 读取失败: " + read);
                    }
                }

                while (true) {
                    int out = audioEncoder.dequeueOutputBuffer(info, 0);
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxerSink.onFormat(Camera2MuxerSink.TRACK_AUDIO,
                                audioEncoder.getOutputFormat());
                        audioFormatReported = true;
                        continue;
                    }
                    if (out < 0) continue;
                    ByteBuffer buffer = audioEncoder.getOutputBuffer(out);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (buffer != null && info.size > 0 && !config) {
                        if (!firstAudioSampleLogged) {
                            firstAudioSampleLogged = true;
                            Log.i(TAG, "首个音频样本 pts=" + info.presentationTimeUs
                                    + " offsetUs=" + audioStartOffsetUs);
                        }
                        muxerSink.write(Camera2MuxerSink.TRACK_AUDIO, buffer, info);
                    }
                    audioEncoder.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        eos = true;
                        break;
                    }
                }
                if (eos) break;
            }
        } catch (Throwable t) {
            error = t;
            Log.e(TAG, "音频编码失败", t);
            if (muxerSink != null) muxerSink.dropAudioTrack();
        } finally {
            closeAudioResources();
            if (!audioFormatReported && muxerSink != null) {
                muxerSink.dropAudioTrack();
            }
        }
        if (error != null && recordingError == null) recordingError = error;
        onWriterFinished();
    }

    private boolean queueAudioEos(long ptsUs) {
        try {
            int input = audioEncoder.dequeueInputBuffer(ENCODER_DEQUEUE_TIMEOUT_US);
            if (input < 0) return false;
            audioEncoder.queueInputBuffer(input, 0, 0, ptsUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "音频 EOS 入队失败", e);
            return true;
        }
    }

    private void onWriterFinished() {
        if (activeWriters.decrementAndGet() != 0) return;
        Throwable error = recordingError;
        closeVideoResources();
        closeAudioResources();
        if (muxerSink != null) {
            Throwable sinkError = muxerSink.close();
            if (error == null) error = sinkError;
            muxerSink = null;
        }
        if (error == null && (videoFile == null || !videoFile.isFile() || videoFile.length() <= 0)) {
            error = new IOException("录像文件为空");
        }
        finishRecording(error);
    }

    private void onGlError(Throwable error) {
        Log.e(TAG, "GL 合成失败", error);
        if (recordingError == null) recordingError = error;
        stopRequested = true;
        audioStopRequested = true;
        cameraHandler.post(this::signalEosIfReady);
    }

    private void finishRecording(@Nullable Throwable error) {
        if (!recordingCallbackSent.compareAndSet(false, true)) return;
        final File file = videoFile;
        final int rotation = videoRotation;
        cameraHandler.post(() -> {
            recording = false;
            stopping = false;
            stopRequested = false;
            videoThread = null;
            audioThread = null;
            liveWatermark = null;
            if (pendingClose) {
                pendingClose = false;
                closeCameraInternal();
            } else if (started && cameraDevice != null) {
                createSession();
            }
        });
        mainHandler.post(() -> listener.onRecordingFinished(file, rotation, error));
    }

    private void failVideoStart(Throwable error) {
        Log.e(TAG, "录像启动失败", error);
        closeVideoResources();
        closeAudioResources();
        if (muxerSink != null) {
            muxerSink.close();
            muxerSink = null;
        }
        if (!recordingCallbackSent.compareAndSet(false, true)) return;
        final File file = videoFile;
        final int rotation = videoRotation;
        cameraHandler.post(() -> {
            recording = false;
            stopping = false;
            stopRequested = false;
            if (pendingClose) {
                pendingClose = false;
                closeCameraInternal();
            } else if (started && cameraDevice != null) {
                createSession();
            }
        });
        mainHandler.post(() -> listener.onRecordingFinished(file, rotation, error));
    }

    private void closeVideoResources() {
        if (glRenderer != null) {
            glRenderer.stop();
            glRenderer = null;
        }
        videoCameraSurface = null;
        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
            } catch (Exception ignored) {
            }
            try {
                videoEncoder.release();
            } catch (Exception ignored) {
            }
            videoEncoder = null;
        }
        if (videoSurface != null) {
            try {
                videoSurface.release();
            } catch (Exception ignored) {
            }
            videoSurface = null;
        }
    }

    private void closeAudioResources() {
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }
        if (audioEncoder != null) {
            try {
                audioEncoder.stop();
            } catch (Exception ignored) {
            }
            try {
                audioEncoder.release();
            } catch (Exception ignored) {
            }
            audioEncoder = null;
        }
    }

    private boolean initAudio() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "没有录音权限，本次录像不含音频");
            return false;
        }
        try {
            int minBuffer = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(16 * 1024, minBuffer > 0 ? minBuffer * 2 : 0);
            try {
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER,
                        AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            } catch (Exception e) {
                Log.w(TAG, "CAMCORDER 音源不可用，改用 MIC", e);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            }
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("AudioRecord 初始化失败");
            }

            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "初始化音频失败，本次录像不含音频", t);
            closeAudioResources();
            return false;
        }
    }

    private MediaCodecInfo selectVideoEncoder(Size size) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) continue;
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    if (caps == null) continue;
                    MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                    if (videoCaps != null && videoCaps.isSizeSupported(size.getWidth(), size.getHeight())) {
                        return info;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "查找编码器失败", e);
        }
        return null;
    }

    private boolean encoderSupports(Size size) {
        return selectVideoEncoder(size) != null;
    }

    private static int bitrateFor(Size size) {
        long pixels = (long) size.getWidth() * size.getHeight();
        return (int) Math.max(4_000_000L, Math.min(12_000_000L, pixels * 5));
    }

    // endregion

    // region --- 尺寸选择 ---

    /** 最大的 4:3 相机输出，尽量保留 CMOS 原生画幅。 */
    private Size chooseFourThreeSize(StreamConfigurationMap map) {
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null) return choosePreviewSize(map, 4f / 3f);
        Size best = null;
        for (Size size : sizes) {
            int w = Math.max(size.getWidth(), size.getHeight());
            int h = Math.min(size.getWidth(), size.getHeight());
            if (w > 1920 || h > 1440) continue;
            if (Math.abs((float) w / h - 4f / 3f) > 0.03f) continue;
            if (best == null || w * h > best.getWidth() * best.getHeight()) {
                best = new Size(w, h);
            }
        }
        return best != null ? best : choosePreviewSize(map, 4f / 3f);
    }

    private Size choosePreviewSize(StreamConfigurationMap map, float targetAspect) {
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null) return null;
        Size best = null;
        float bestDiff = Float.MAX_VALUE;
        for (Size size : sizes) {
            int w = Math.max(size.getWidth(), size.getHeight());
            int h = Math.min(size.getWidth(), size.getHeight());
            if (w > 1920 || h > 1080) continue;
            float diff = Math.abs((float) w / h - targetAspect);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = new Size(w, h);
            }
        }
        if (best == null) {
            for (Size size : sizes) {
                int w = Math.max(size.getWidth(), size.getHeight());
                int h = Math.min(size.getWidth(), size.getHeight());
                if (w <= 1920 && h <= 1080 && (best == null || w * h < best.getWidth() * best.getHeight())) {
                    best = new Size(w, h);
                }
            }
        }
        return best;
    }

    private Size choosePhotoSize(StreamConfigurationMap map, float targetAspect) {
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null) return choosePreviewSize(map, targetAspect);
        Size best = null;
        float bestScore = Float.MAX_VALUE;
        for (Size size : sizes) {
            int w = Math.max(size.getWidth(), size.getHeight());
            int h = Math.min(size.getWidth(), size.getHeight());
            if ((long) w * h > MAX_PHOTO_PIXELS) continue;
            float aspectDiff = Math.abs((float) w / h - targetAspect);
            float score = aspectDiff * 100f - (w * h) / (float) MAX_PHOTO_PIXELS;
            if (score < bestScore) {
                bestScore = score;
                best = new Size(w, h);
            }
        }
        return best != null ? best : choosePreviewSize(map, targetAspect);
    }

    // endregion

    // region --- 工具 ---

    private void postCameraReady() {
        final int lens = lensFacing;
        final boolean flash = hasFlash;
        final float min = minZoom;
        final float max = maxZoom;
        mainHandler.post(() -> listener.onCameraReady(lens, flash, min, max));
    }

    private void postCameraError(String message) {
        mainHandler.post(() -> listener.onCameraError(message));
    }

    private void postPhotoError(String message) {
        mainHandler.post(() -> listener.onPhotoError(message));
    }

    private static int intValue(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // endregion
}
