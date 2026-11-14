package com.filecamera.apk;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
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
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
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
import java.io.InputStream;
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

    /** AE 预闪测光的最长等待：超时后按当前 AE 状态继续拍照，避免快门一直卡住。 */
    private static final long PRE_CAPTURE_TIMEOUT_MS = 2500L;
    /** 触发预闪后至少等待这么久再接受“测光完成”结果，避免把触发前的旧结果当成结论。 */
    private static final long PRE_CAPTURE_SETTLE_MS = 300L;
    /** 预览 AE 状态的有效期：超过这个时间没刷新就不敢据此跳过 DECIDE 预闪。 */
    private static final long PREVIEW_AE_FRESH_MS = 500L;

    /** 自动闪光第一遍：AUTO_FLASH 测光，只用来判断“需不需要闪光”。 */
    private static final int PRECAPTURE_PHASE_DECIDE = 0;
    /** 闪光流程第二遍：ALWAYS_FLASH 测光，测出闪光功率后强制闪光拍照。 */
    private static final int PRECAPTURE_PHASE_METER = 1;

    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_CHANNEL_COUNT = 1;
    private static final int AUDIO_BIT_RATE = 96_000;

    public interface Listener {
        void onCameraReady(int lensFacing, boolean hasFlash, float minZoom, float maxZoom);

        void onCameraError(String message);

        void onPhotoReady(File photoFile);

        /** 传感器开始曝光（与闪光同一时刻，用于播放快门声）。方法名保留，时机已前移到曝光开始。 */
        void onPhotoWriteStarted();

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
    /** 合并连续变焦请求：触摸事件频率可能高于相机 HAL 处理能力，只保留最后一次。 */
    private final Runnable zoomApplyRunnable = this::updateRepeatingRequest;

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
    private volatile boolean hasFlash = false;
    private boolean supportsZoomRatio = false;
    private float minZoom = 1f;
    private float maxZoom = 1f;
    private volatile float currentZoom = 1f;
    private volatile int flashMode = FLASH_OFF;

    // ---- 闪光拍照：AE 预闪测光状态（全部在 cameraHandler 线程读写）----
    /** 预闪测光进行中，期间不再接受新的拍照请求。 */
    private boolean precaptureRunning = false;
    private boolean precaptureSawRunningState = false;
    private long precaptureStartMs = 0;
    private File pendingCaptureFile;
    private int pendingCaptureRotation;
    /** 本遍预闪测光的目的：PRECAPTURE_PHASE_DECIDE / PRECAPTURE_PHASE_METER。 */
    private int precapturePhase = PRECAPTURE_PHASE_METER;
    /** JPEG 拍照请求已提交、还在等相机回图；期间关会话要明确告诉界面“这张没拍成”。 */
    private boolean stillCapturePending = false;
    private int lastAeState = -1;
    private long lastExposureTimeNs = 0;
    private int lastIso = 0;
    /** 预览持续 AE 的最新状态缓存：自动闪光时预览常驻 AUTO_FLASH，拍照直接读 FLASH_REQUIRED 判断。 */
    private int previewAeState = -1;
    private long previewExposureTimeNs = 0;
    private int previewIso = 0;
    private long previewAeStateMs = 0;
    /** 预览请求当前使用的 AE 模式：平时由 defaultPreviewAeMode() 决定；预闪测光窗口内临时切到 AUTO/ALWAYS_FLASH。 */
    private int previewAeMode = CaptureRequest.CONTROL_AE_MODE_ON;
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
                    CaptureRequest.Builder b = buildRepeatingPreviewRequest();
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

    /** 当前镜头是否有闪光灯（前置通常没有）；界面据此决定闪光灯按钮显隐。 */
    public boolean hasFlash() {
        return hasFlash;
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
        // 不要在每次 onScale 都直接 post 一次 setRepeatingRequest：
        // 双指缩放事件可能 120Hz，相机 HAL 处理不过来会积压，表现为变焦“慢半拍”。
        // 移除尚未执行的旧请求，只保留最新一次；执行时读取最新的 currentZoom。
        cameraHandler.removeCallbacks(zoomApplyRunnable);
        cameraHandler.post(zoomApplyRunnable);
    }

    public void setFlashMode(int mode) {
        flashMode = mode;
        // 对齐 CameraX：自动闪光时预览常驻 AUTO_FLASH，AE 会在预览帧里直接报 FLASH_REQUIRED，
        // 拍照时无需再为“要不要闪”单独跑一遍预闪。
        cameraHandler.post(() -> {
            previewAeMode = defaultPreviewAeMode();
            updateRepeatingRequest();
        });
    }

    /** 预览常态 AE 模式：自动闪光且有闪光灯时用 AUTO_FLASH，其余用普通 ON。 */
    private int defaultPreviewAeMode() {
        return hasFlash && flashMode == FLASH_AUTO
                ? CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                : CaptureRequest.CONTROL_AE_MODE_ON;
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
            // 预览固定使用 4:3 全画幅；拍照/录像仍按设置比例单独请求。
            previewSize = choosePreviewSize(map, 4f / 3f);
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
        if (precaptureRunning || stillCapturePending) {
            // 预闪测光/等相机回图期间关会话（切镜头/切模式/退出）时，
            // 明确告诉界面“这张没拍成”，否则快门会一直停在“处理中”。
            cancelPrecaptureState();
            stillCapturePending = false;
            pendingPhotoFile = null;
            postPhotoError("拍照已取消");
        }
        // 会话都要关了，预览 AE 模式无需恢复；新会话按当前闪光模式重建预览请求
        previewAeMode = defaultPreviewAeMode();
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

    /**
     * 预闪测光/等 JPEG 期间，任何预览请求（缩放、对焦、预览循环）都必须保持闪光 AE 模式，
     * 否则中间插进来的普通预览请求会把 AE 状态机从 AUTO_FLASH/ALWAYS_FLASH 拉回去，
     * 预闪测光就白做了。
     */
    private CaptureRequest.Builder buildRepeatingPreviewRequest() throws CameraAccessException {
        CaptureRequest.Builder builder = buildPreviewRequest();
        builder.set(CaptureRequest.CONTROL_AE_MODE, previewAeMode);
        return builder;
    }

    private void updateRepeatingRequest() {
        if (session == null || cameraDevice == null) return;
        try {
            // 预览结果里带回 AE 状态：自动闪光要靠它判断“预闪测光是否完成、是否需要闪光”
            session.setRepeatingRequest(buildRepeatingPreviewRequest().build(),
                    previewResultCallback, cameraHandler);
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
            CaptureRequest.Builder b = buildRepeatingPreviewRequest();
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

        // 相机画面在显示方向下是竖向的：短边为宽，长边为高。
        // TextureView 默认把 buffer 铺满 View；这里用矩阵做一次方向修正，
        // 只改变内容在 View 内的显示比例，View 本身仍是 match_parent，超出部分由父容器裁剪。
        int displayW = Math.min(pw, ph);
        int displayH = Math.max(pw, ph);
        float scale = Math.max(vw / (float) displayW, vh / (float) displayH);
        float scaleX = displayW * scale / vw;
        float scaleY = displayH * scale / vh;

        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, vw / 2f, vh / 2f);
        previewView.setTransform(matrix);
        previewView.setRotation(0f);
        previewView.setPivotX(vw / 2f);
        previewView.setPivotY(vh / 2f);
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

    /**
     * 发起一次拍照。
     *
     * <p>闪光灯不是“按下快门就直接闪”，而是先判断场景是否需要闪光，再决定走哪条流程：
     * <ul>
     *     <li>闪光灯关闭 / 无闪光灯：无闪光流程，直接拍；</li>
     *     <li>闪光灯常开：闪光流程（AE 预闪测光 → 按测光结果补光后拍）；</li>
     *     <li>自动闪光（对齐 CameraX）：预览常驻 AUTO_FLASH，拍照时直接读预览帧 AE 状态——
     *         FLASH_REQUIRED 则进入闪光流程（只跑一遍功率预闪），否则无闪光直接拍；
     *         预览状态过期时才退回 DECIDE 预闪做正式判断。</li>
     * </ul>
     * 预闪测光是避免极暗场景“闪光灯全功率输出 + 曝光仍按无闪光场景计算”导致整张
     * 照片过曝（白屏）的关键；这也是 Camera2 对有闪光灯的高质量拍照的明确要求。
     */
    private void captureStill(File file, int rotation) {
        if (session == null || photoReader == null || cameraDevice == null) {
            pendingPhotoFile = null;
            postPhotoError("相机尚未就绪");
            return;
        }
        if (precaptureRunning) {
            // 正常流程不会走到这里（Activity 在上一张处理完成前会禁用快门）
            pendingPhotoFile = null;
            postPhotoError("上一张照片还在处理");
            return;
        }
        if (!hasFlash || flashMode == FLASH_OFF) {
            Log.i(TAG, "拍照流程：无闪光（flashMode=" + flashModeName(flashMode)
                    + ", hasFlash=" + hasFlash + "）");
            submitStillCapture(file, rotation, CaptureRequest.CONTROL_AE_MODE_ON);
            return;
        }
        if (flashMode == FLASH_AUTO && isPreviewAeFresh()) {
            // 对齐 CameraX：预览常驻 AUTO_FLASH，要不要闪直接读预览帧的 AE 状态。
            if (previewAeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                Log.i(TAG, "自动闪光：预览 AE 报 FLASH_REQUIRED，进入闪光测光流程"
                        + "（exposureNs=" + previewExposureTimeNs + ", iso=" + previewIso + "）");
                startPrecapture(file, rotation, PRECAPTURE_PHASE_METER);
            } else {
                Log.i(TAG, "自动闪光：预览 AE 无需闪光，直接拍（aeState="
                        + aeStateName(previewAeState) + " exposureNs=" + previewExposureTimeNs
                        + ", iso=" + previewIso + "）");
                submitStillCapture(file, rotation, CaptureRequest.CONTROL_AE_MODE_ON);
            }
            return;
        }
        // 预览 AE 状态过期（刚开相机/预览卡顿）：自动闪光退回 DECIDE 预闪做正式判断。
        startPrecapture(file, rotation, flashMode == FLASH_ON
                ? PRECAPTURE_PHASE_METER : PRECAPTURE_PHASE_DECIDE);
    }

    /** 预览 AE 状态是否在有效期内（过期则不敢据此直接决定闪不闪）。 */
    private boolean isPreviewAeFresh() {
        return previewAeState >= 0
                && SystemClock.elapsedRealtime() - previewAeStateMs <= PREVIEW_AE_FRESH_MS;
    }

    /**
     * 触发 AE 预闪测光。
     *
     * <p>相机文档要求：带闪光灯的高质量拍照必须先触发一次 precapture metering，让 AE 通过
     * 预闪光测出场景亮度和最终闪光功率。跳过这一步直接 {@code AE_MODE_ON + FLASH_MODE_SINGLE}
     * 会让闪光灯按全功率闪，而曝光仍按无闪光场景计算，极暗场景就会得到一张全白照片。
     *
     * @param phase PRECAPTURE_PHASE_DECIDE=自动闪光先判断；PRECAPTURE_PHASE_METER=测闪光功率后强制闪
     */
    private void startPrecapture(File file, int rotation, int phase) {
        pendingCaptureFile = file;
        pendingCaptureRotation = rotation;
        precapturePhase = phase;
        precaptureRunning = true;
        precaptureSawRunningState = false;
        precaptureStartMs = SystemClock.elapsedRealtime();
        lastAeState = -1;
        lastExposureTimeNs = 0;
        lastIso = 0;

        final int aeMode = phase == PRECAPTURE_PHASE_METER
                ? CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                : CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH;
        try {
            // 预闪测光期间让预览请求也用同一个闪光 AE 模式：AE 状态机必须持续处于
            // AUTO_FLASH / ALWAYS_FLASH，才能在后续预览帧里把测光跑完。
            previewAeMode = aeMode;
            updateRepeatingRequest();

            CaptureRequest.Builder builder = buildPreviewRequest();
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // 预闪测光期间不触发对焦，避免对焦触发把 AE 触发往后拖
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            session.capture(builder.build(), precaptureResultCallback, cameraHandler);
            cameraHandler.postDelayed(precaptureTimeoutRunnable, PRE_CAPTURE_TIMEOUT_MS);
            Log.i(TAG, "闪光流程：开始 AE 预闪测光（"
                    + (phase == PRECAPTURE_PHASE_METER ? "闪光测光" : "自动判断") + "）");
        } catch (Exception e) {
            Log.e(TAG, "触发 AE 预闪测光失败，直接按当前模式拍照", e);
            cancelPrecaptureState();
            restorePreviewAeMode();
            submitStillCapture(file, rotation, phase == PRECAPTURE_PHASE_METER
                    ? CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    : CaptureRequest.CONTROL_AE_MODE_ON);
        }
    }

    /**
     * 处理预闪测光结果：等 AE 从 PRECAPTURE 走到 CONVERGED / FLASH_REQUIRED。
     *
     * @param fromTrigger true=这是带 START 触发的那一帧结果，它的最终状态可以直接作为结论；
     *                    false=后续预览帧结果，刚触发时可能还是触发前的旧状态，需要稳定窗口
     */
    private void handlePrecaptureResult(CaptureResult result, boolean fromTrigger) {
        if (!precaptureRunning || result == null) return;
        Integer state = result.get(CaptureResult.CONTROL_AE_STATE);
        if (state == null) return;
        lastAeState = state;
        Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (exposure != null) lastExposureTimeNs = exposure;
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (iso != null) lastIso = iso;

        long elapsed = SystemClock.elapsedRealtime() - precaptureStartMs;
        if (state == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
            precaptureSawRunningState = true;
            return;
        }
        boolean settled = state == CaptureResult.CONTROL_AE_STATE_CONVERGED
                || state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                || state == CaptureResult.CONTROL_AE_STATE_LOCKED;
        if (!settled) return;
        // 刚触发时可能先收到触发前就在管线里的旧结果（CONVERGED）；
        // 触发帧自己的结果可信，后续预览帧则至少等一个稳定窗口再下结论。
        if (!fromTrigger && !precaptureSawRunningState && elapsed < PRE_CAPTURE_SETTLE_MS) return;
        finishPrecapture();
    }

    /** 结束一遍预闪测光：自动判断 → 决定是否进入闪光流程；闪光测光 → 提交强制闪光拍照。 */
    private void finishPrecapture() {
        if (!precaptureRunning) return;
        precaptureRunning = false;
        cameraHandler.removeCallbacks(precaptureTimeoutRunnable);

        final int phase = precapturePhase;
        final File file = pendingCaptureFile;
        final int rotation = pendingCaptureRotation;
        pendingCaptureFile = null;

        if (phase == PRECAPTURE_PHASE_DECIDE) {
            final boolean useFlash = shouldAutoFlash();
            Log.i(TAG, "自动闪光判断：aeState=" + aeStateName(lastAeState)
                    + " exposureNs=" + lastExposureTimeNs
                    + " iso=" + lastIso
                    + " → " + (useFlash ? "需要闪光，进入闪光流程" : "不需要闪光，无闪光流程"));
            if (useFlash) {
                // 关键：AUTO_FLASH 只负责判断；真正拍照前必须再用 ALWAYS_FLASH 测一遍闪光功率。
                // 否则相机可能既不闪，又沿用 40ms/ISO 5万 这类无闪光曝光，得到一张黑照片。
                startPrecapture(file, rotation, PRECAPTURE_PHASE_METER);
            } else {
                restorePreviewAeMode();
                submitStillCapture(file, rotation, CaptureRequest.CONTROL_AE_MODE_ON);
            }
            return;
        }

        Log.i(TAG, "闪光流程：测光完成（aeState=" + aeStateName(lastAeState)
                + " exposureNs=" + lastExposureTimeNs
                + " iso=" + lastIso + "）→ ALWAYS_FLASH 拍照");
        submitStillCapture(file, rotation, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
    }

    /**
     * 自动闪光的判断：AE 明确要求闪光 → 闪光；否则用曝光参数兜底判断“极暗”。
     * 兜底只用于个别 HAL 不给 FLASH_REQUIRED 的情况，阈值刻意定得很暗，避免正常室内光也乱闪。
     */
    private boolean shouldAutoFlash() {
        return lastAeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || isSceneVeryDark();
    }

    private boolean isSceneVeryDark() {
        return isVeryDark(lastExposureTimeNs, lastIso);
    }

    private static boolean isVeryDark(long exposureTimeNs, int iso) {
        if (exposureTimeNs <= 0 && iso <= 0) return false;
        // 曝光 ≥ 1/10s，或 ≥ 1/20s 且 ISO ≥ 1600，或 ISO 已经拉到 3200：
        // 都是肉眼/相机都认为很暗的场景，用作 AE 没给 FLASH_REQUIRED 时的兜底。
        return exposureTimeNs >= 100_000_000L
                || (exposureTimeNs >= 50_000_000L && iso >= 1600)
                || iso >= 3200;
    }

    private void cancelPrecaptureState() {
        precaptureRunning = false;
        pendingCaptureFile = null;
        cameraHandler.removeCallbacks(precaptureTimeoutRunnable);
    }

    /** 拍照结束（成功/失败）后把预览 AE 恢复成常态模式（自动闪光时为 AUTO_FLASH，其余为 ON）。 */
    private void restorePreviewAeMode() {
        final int mode = defaultPreviewAeMode();
        if (previewAeMode == mode) return;
        previewAeMode = mode;
        updateRepeatingRequest();
    }

    private final Runnable precaptureTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!precaptureRunning) return;
            Log.w(TAG, "AE 预闪测光超时（" + PRE_CAPTURE_TIMEOUT_MS + "ms），按当前 AE 状态继续拍照");
            finishPrecapture();
        }
    };

    private final CameraCaptureSession.CaptureCallback precaptureResultCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession s,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    handlePrecaptureResult(result, true);
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession s,
                                            @NonNull CaptureRequest request,
                                            @NonNull CaptureFailure failure) {
                    if (!precaptureRunning) return;
                    Log.e(TAG, "AE 预闪测光请求失败: " + failure.getReason());
                    finishPrecapture();
                }
            };

    /**
     * 预览帧也会携带 AE 状态：预闪测光常常跨越好几帧，最终状态要看这些后续结果。
     * 非预闪期间把最新 AE 状态/曝光参数缓存下来，供自动闪光的快速通道判断。
     */
    private final CameraCaptureSession.CaptureCallback previewResultCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession s,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    if (!precaptureRunning) {
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState != null) {
                            previewAeState = aeState;
                            Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                            if (exposure != null) previewExposureTimeNs = exposure;
                            Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                            if (iso != null) previewIso = iso;
                            previewAeStateMs = SystemClock.elapsedRealtime();
                        }
                    }
                    handlePrecaptureResult(result, false);
                }
            };

    /** 真正提交 JPEG 拍照请求；aeMode 决定无闪光 / 自动闪光 / 常开闪光流程。 */
    private void submitStillCapture(File file, int rotation, int aeMode) {
        if (file == null) {
            pendingPhotoFile = null;
            restorePreviewAeMode();
            postPhotoError("拍照失败");
            return;
        }
        if (session == null || photoReader == null || cameraDevice == null) {
            pendingPhotoFile = null;
            restorePreviewAeMode();
            postPhotoError("相机尚未就绪");
            return;
        }
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(photoReader.getSurface());
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
            builder.set(CaptureRequest.JPEG_ORIENTATION, rotation);
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
            applyFlashForStill(builder, aeMode);
            applyZoom(builder);
            stillCapturePending = true;
            session.capture(builder.build(), stillCaptureCallback, cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "发起拍照失败", e);
            stillCapturePending = false;
            pendingPhotoFile = null;
            restorePreviewAeMode();
            postPhotoError("拍照失败");
        }
    }

    private final CameraCaptureSession.CaptureCallback stillCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession s,
                                             @NonNull CaptureRequest request,
                                             long timestamp, long frameNumber) {
                    // 传感器开始曝光的瞬间（闪光也在这一刻打闪）：在这里播快门声，
                    // 与闪光同步；既不会提前到预闪测光阶段，也不会滞后到 JPEG 写盘。
                    mainHandler.post(listener::onPhotoWriteStarted);
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession s,
                                            @NonNull CaptureRequest request,
                                            @NonNull CaptureFailure failure) {
                    Log.e(TAG, "拍照请求失败: " + failure.getReason());
                    stillCapturePending = false;
                    pendingPhotoFile = null;
                    restorePreviewAeMode();
                    postPhotoError("拍照失败");
                }
            };

    private void applyFlashForStill(CaptureRequest.Builder builder, int aeMode) {
        if (!hasFlash || aeMode == CaptureRequest.CONTROL_AE_MODE_ON) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
            return;
        }
        // 闪光灯交给 AE 控制：闪光流程固定用 ON_ALWAYS_FLASH，按上一遍闪光测光得到的
        // 曝光/闪光功率输出。注意不要再同时设置 FLASH_MODE_SINGLE——
        // 那会绕过 AE 强制全功率闪光，在极暗场景下正是“白屏过曝”的根因。
        builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
    }

    private void onPhotoAvailable(ImageReader reader) {
        Image image = null;
        File target = pendingPhotoFile;
        pendingPhotoFile = null;
        stillCapturePending = false;
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
            Log.d(TAG, "JPEG 开始落盘: " + target.getName());
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
            // 照片已经拿到，恢复普通预览 AE（闪光 AE 模式只在测光/拍照窗口内使用）
            restorePreviewAeMode();
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
            // GL 只补偿固定的传感器安装方向；设备横竖屏由 MP4 rotation 单独处理。
            int glCameraRotation = (360 - sensorOrientation) % 360;
            glRenderer = new Camera2GlRenderer(this::onGlError);
            Bitmap watermarkIcon = loadWatermarkIcon(initialWatermark);
            videoCameraSurface = glRenderer.start(videoSurface, cameraSize, usedSize,
                    glCameraRotation, displaySize, rotation, initialWatermark, watermarkIcon);

            captureSessionForRecording();
        } catch (Throwable e) {
            Log.e(TAG, "启动录像失败", e);
            failVideoStart(e);
        }
    }

    private Bitmap loadWatermarkIcon(JSONObject watermark) {
        if (watermark == null) return null;
        String ref = watermark.optString("icon_ref", "");
        if (ref.isEmpty()) return null;
        try {
            String assetPrefix = "file:///android_asset/";
            if (ref.startsWith(assetPrefix)) {
                try (InputStream input = context.getAssets().open(ref.substring(assetPrefix.length()))) {
                    return BitmapFactory.decodeStream(input);
                }
            }
            if (ref.startsWith("content://")) {
                try (InputStream input = context.getContentResolver().openInputStream(
                        android.net.Uri.parse(ref))) {
                    return BitmapFactory.decodeStream(input);
                }
            }
            String path = ref.startsWith("file://") ? ref.substring(7) : ref;
            return BitmapFactory.decodeFile(path);
        } catch (Exception e) {
            Log.w(TAG, "加载录像水印图标失败", e);
            return null;
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

    private static String flashModeName(int mode) {
        switch (mode) {
            case FLASH_ON: return "ON";
            case FLASH_AUTO: return "AUTO";
            default: return "OFF";
        }
    }

    private static String aeStateName(int state) {
        switch (state) {
            case CaptureResult.CONTROL_AE_STATE_INACTIVE: return "INACTIVE";
            case CaptureResult.CONTROL_AE_STATE_SEARCHING: return "SEARCHING";
            case CaptureResult.CONTROL_AE_STATE_CONVERGED: return "CONVERGED";
            case CaptureResult.CONTROL_AE_STATE_LOCKED: return "LOCKED";
            case CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED: return "FLASH_REQUIRED";
            case CaptureResult.CONTROL_AE_STATE_PRECAPTURE: return "PRECAPTURE";
            default: return "UNKNOWN(" + state + ")";
        }
    }

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
