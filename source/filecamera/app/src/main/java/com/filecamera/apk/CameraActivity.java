package com.filecamera.apk;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.LiveData;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class CameraActivity extends AppCompatActivity {

    // region --- 常量和成员变量 ---
    private static final String TAG = "CameraActivity";
    
    private static final int REQUEST_PERMISSIONS = 101;
    private static final long LOCATION_TIMEOUT_MS = 5000L;
    // GPS 优先策略：GPS 定位在此时间窗口内仍有效时，忽略网络定位结果
    private static final long GPS_PRIORITY_TIMEOUT_MS = 15000L;
    private static final int MAX_WATERMARK_LINE_WIDTH_DP = 260;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };
    private volatile boolean isActivityResumed = false;
    // 视图控件
    private PreviewView previewView;
    private ImageButton captureButton;
    private LinearLayout watermarkContainer;
    private ImageButton flashButton;
    private ImageButton exitButton;
    // 变焦按钮（位于两个模式标签中间，显示当前倍数，点击弹出倍数选择框）
    private TextView zoomButton;
    private final List<Float> availableZoomRatios = new ArrayList<>();
    // 当前正在观察的 ZoomState LiveData，切换摄像头时需移除旧观察者
    private LiveData<ZoomState> observedZoomState;
    // 预设变焦倍数，仅展示设备支持范围内的档位
    private static final float[] ZOOM_PRESETS = {0.6f, 1.0f, 2.0f, 3.0f, 5.0f};
    // 拍照提示音
    private MediaActionSound mediaActionSound;

    // region --- 连续拍照会话 ---
    /** 打开相机时由前端传入的目标目录 id（照片直接入库到这个目录） */
    public static final String EXTRA_TARGET_DIR_ID = "com.filecamera.apk.extra.TARGET_DIR_ID";
    /** 入库器：合成完成后立刻把照片写进库（SQLite 是唯一事实来源） */
    private FileImporter fileImporter;
    private String targetDirId = "root";
    // 是否有照片正在处理：期间禁用快门与退出，保证「已拍的照片一定不丢」
    // （照片处理是同步的，这个标记同时挡住「点快门连击」和「处理中退出」两件事）
    private volatile boolean isCaptureProcessing = false;
    // 处理过程中用户已要求退出：等当前照片保存完成后自动带结果退出
    private boolean pendingFinishAfterCapture = false;
    // 本次会话是否已提示过 GPS 无效（避免每拍一张都弹一次挡住快门）
    private boolean gpsWarningShown = false;
    // endregion

    // region --- 录像（视频不烧水印：水印数据随视频入库，播放/导出时再叠）---
    private TextView modeTabVideo;
    private TextView recordingTimer;
    private VideoCapture<Recorder> videoCapture;
    private Recording currentRecording;
    private volatile boolean isRecording = false;
    private boolean isVideoMode = false;
    private long recordStartMs = 0;
    private FileImporter.VideoTicket videoTicket;
    /** 本次录像的水印数据（首行全量 + 变化回填） */
    private JSONObject videoWatermarkJson;
    /** 上一次的水印字段快照，用于只记录变化 */
    private final java.util.Map<String, String> lastWatermarkValues = new java.util.HashMap<>();
    private String lastWatermarkAnchor = "";
    private final Handler recordTimerHandler = new Handler(Looper.getMainLooper());
    private final Runnable recordTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording || recordingTimer == null) return;
            long ms = System.currentTimeMillis() - recordStartMs;
            recordingTimer.setText(String.format(Locale.CHINA, "● %02d:%02d", ms / 60000, (ms / 1000) % 60));
            recordTimerHandler.postDelayed(this, 500);
        }
    };
    // endregion

    // CameraX 组件
    private ImageCapture imageCapture;
    private Camera camera;
    private CameraControl cameraControl;
    private ProcessCameraProvider cameraProvider;

    // 手势和方向
    private ScaleGestureDetector scaleGestureDetector;
    private OrientationEventListener orientationEventListener;
    private int currentRotation = Surface.ROTATION_0;
    private static final int ORIENTATION_HYSTERESIS = 20;

    // 定位服务
    private LocationManager locationManager;
    private LocationListener locationListener;
    private double lat = 0, lon = 0, alt = 0;
    private long locationTime = 0;
    // 最近一次 GPS 定位的时间，用于 GPS 优先策略（网络定位仅在 GPS 超时时使用）
    private long gpsLocationTime = 0;

    // 外部API数据
    private boolean hasWeatherItem = false;
    private String weatherAdcode = null;
    private boolean hasAddressItem = false;
    private volatile String currentAddress = "获取地址中..."; // [Data]
    private volatile boolean isFetchingAddress = false;

    // 道路匹配
    private static final long MATCH_INTERVAL_MS = 5000;
    private volatile boolean isMatchingRoad = false;
    private long lastMatchRequestTime = 0;
    private RoadDatabaseHelper roadDatabaseHelper;
    private volatile RoadDatabaseHelper.RoadLocationMatch currentRoadMatch = null; // [Data]
    private int currentFlashMode = ImageCapture.FLASH_MODE_OFF; // 闪光灯状态

    // UI更新循环
    private final Handler uiUpdateHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            // 1. 定时更新 "time" 类型的字段
            updateDynamicItemsByType("time", getCurrentTimeText());
            
            // 2. 检查GPS是否过期
            if (!isLocationValid()) {
                // GPS过期，重置所有依赖GPS的数据和UI
                resetGpsDependentData();
            }

            // 3. 检查天气（手动/自动统一入口：仅adcode来源不同——配置读取 vs GPS实时解析）
            if (hasWeatherItem) {
                boolean isAutoAdcode = weatherAdcode == null || weatherAdcode.trim().isEmpty();
                // 自动模式需等GPS就绪才能解析adcode；手动模式不依赖GPS
                if (!isAutoAdcode || isLocationValid()) {
                    GaodeApi.requestWeatherUpdateIfStale(CameraActivity.this, weatherAdcode, lon, lat, new GaodeApi.WeatherCallback() {
                        @Override
                        public void onWeatherUpdated(String weatherInfo) {
                            runOnUiThread(() -> {
                                updateDynamicItemsByType("weather", weatherInfo);
                            });
                        }
                    });
                }
                // 自动模式GPS未就绪时保持当前显示（"获取中..."），等待下一秒轮询
            }
            
            // 4. 录像中：把"变化的水印字段/位置"追加成 delta 记录
            if (isRecording) {
                recordWatermarkDelta();
            }

            uiUpdateHandler.postDelayed(this, 1000); // 1秒更新一次
        }
    };

    // 配置与状态
    private CameraConfig cameraConfig;
    private WatermarkConfig watermarkConfig;
    private QrCodeConfig qrCodeConfig;
    private boolean showWatermark = false;
    private boolean showQrCode = false;
    private int targetAspectRatio = AspectRatio.RATIO_16_9;
    // 模式选择（普通/自拍）两个标签
    private TextView modeTabPhoto;
    private ImageButton switchCameraButton;
    private int currentLensFacing = CameraSelector.LENS_FACING_BACK; // 当前摄像头方向

    // [Refactored] 水印数据和视图的单一来源
    // 1. 数据存储 (单一数据源)
    private final Map<String, String> watermarkDataStore = new java.util.concurrent.ConcurrentHashMap<>();
    // 2. 视图映射 (ID -> TextView)
    private final Map<String, WeakReference<TextView>> watermarkViewMap = new HashMap<>();
    private int lastWatermarkWidth = 0;
    private int lastWatermarkHeight = 0;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener watermarkLayoutListener;
    
    // [Refactored] 类型处理器 (仅用于获取 *初始* 值)
    private final Map<String, TypeHandler> typeHandlers = new HashMap<String, TypeHandler>() {{
        // 静态类型：返回配置中的 value
        put("string", item -> item.value);
        put("input", item -> item.value);
        // [新增] input_auto_save 初始值处理与 input 相同
        put("input_auto_save", item -> item.value); 
        
        // 动态类型：返回当前的默认/初始值
        put("road", item -> getCurrentRoadText());
        put("camera_coord", item -> getCurrentCoordText());
        put("camera_pile", item -> getCurrentPileText());
        put("time", item -> getCurrentTimeText());
        put("weather", item -> getCurrentWeatherText());
        put("address", item -> getCurrentAddressText());
    }};
    // endregion

    // region --- Activity 生命周期 ---
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        // 相机会话可能持续较久（连续拍摄），拍摄期间保持屏幕常亮（与主界面 wakelock 同一套机制）
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 0. 前端传入的目标目录（照片直接入库到这个目录）
        String extraDirId = getIntent().getStringExtra(EXTRA_TARGET_DIR_ID);
        if (extraDirId != null && !extraDirId.trim().isEmpty()) {
            targetDirId = extraDirId;
        }
        fileImporter = new FileImporter(this);

        // 1. 处理返回键逻辑：与右上角退出按钮一致——退出即交付本次会话已拍照片
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                requestExit();
            }
        });
        
        // [MODIFIED] 使用 WatermarkConfigManager 读取配置
        String configJson = WatermarkConfigManager.getInstance(this).getConfig();

        // -------------------------------------------------------------
        
        roadDatabaseHelper = new RoadDatabaseHelper(this);

        initViews();
        initGestureDetectors();
        initLocationServices(); // 仅初始化服务，不请求更新
        initOrientationListener();

        // 预加载拍照提示音，避免拍照时首次加载产生延迟
        mediaActionSound = new MediaActionSound();
        mediaActionSound.load(MediaActionSound.SHUTTER_CLICK);
        
        // 解析读取到的配置
        parseConfig(configJson);

        // 开启本次拍照会话：本次拍摄的所有照片都累积在同一个会话目录里，退出时统一交付给前端
        try {
            CameraSessionStore.beginSession(this);
        } catch (IOException e) {
            Log.e(TAG, "开启拍照会话失败", e);
        }
        
        checkForWeatherItem();
        checkForAddressItem();

        GaodeApi.resetApiCallFailedFlag();
        loadInitialCaches();
        if (showWatermark) {
            watermarkContainer.setVisibility(View.VISIBLE);
            // [Refactored] 仅在 onCreate 时构建一次UI
            setupWatermarkPreview(); 
        }

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityResumed = false;
        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }
        stopLocationUpdates(); // 停止位置更新
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable); // 停止UI更新循环
        if (showWatermark && watermarkLayoutListener != null) {
            watermarkContainer.getViewTreeObserver().removeOnGlobalLayoutListener(watermarkLayoutListener);
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityResumed = true;

        // 重新加载配置，确保 adcode 等设置变更后能及时生效
        try {
            String latestConfig = WatermarkConfigManager.getInstance(this).getConfig();
            JSONObject root = new JSONObject(latestConfig);
            if (root.has("watermark")) {
                parseWatermark(root.getJSONObject("watermark"));
                checkForWeatherItem();
                checkForAddressItem();
                if (hasWeatherItem) {
                    GaodeApi.loadWeatherFromCache(this, weatherAdcode);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onResume 重新加载配置失败", e);
        }

        // [Refactored] 不再重建UI，而是重置数据
        if (!isLocationValid()) {
            currentAddress = "获取地址中...";
            locationTime = 0;
            currentRoadMatch = null;

            if (showWatermark) {
                // 仅重置依赖GPS的数据和UI，而不是重建所有
                resetGpsDependentData(); 
            }
        }
        if (showWatermark && watermarkLayoutListener != null) {
            // 重置上一次的尺寸，以便在 onResume 时强制刷新一次位置
            lastWatermarkWidth = 0;
            lastWatermarkHeight = 0;
            watermarkContainer.getViewTreeObserver().addOnGlobalLayoutListener(watermarkLayoutListener);
        }        
        if (allPermissionsGranted()) {
            startCamera();
            startLocationUpdates(); // 开始位置更新
            uiUpdateHandler.post(uiUpdateRunnable); // 启动UI更新循环
        }
        if (orientationEventListener != null && orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 录像中直接销毁：先停录（Finalize 会把已录部分入库），再解绑相机
        if (isRecording) {
            stopRecording();
        }
        recordTimerHandler.removeCallbacksAndMessages(null);
        if (cameraProvider != null) {
            cameraProvider.unbindAll(); // 作为最后保障
        }
        if (mediaActionSound != null) {
            mediaActionSound.release();
            mediaActionSound = null;
        }
    }
    // endregion

    // region --- 初始化方法 ---
    private void initViews() {
        previewView = findViewById(R.id.previewView);
        captureButton = findViewById(R.id.captureButton);
        flashButton = findViewById(R.id.flashButton);
        modeTabPhoto = findViewById(R.id.modeTabPhoto);
        switchCameraButton = findViewById(R.id.switchCameraButton);
        if (switchCameraButton != null) {
            switchCameraButton.setOnClickListener(v -> toggleLensFacing());
        }
        modeTabVideo = findViewById(R.id.modeTabVideo);
        recordingTimer = findViewById(R.id.recordingTimer);
        if (modeTabVideo != null) {
            modeTabVideo.setOnClickListener(v -> switchVideoMode(true));
        }
        watermarkContainer = findViewById(R.id.watermarkContainer);
        exitButton = findViewById(R.id.exitButton);
        zoomButton = findViewById(R.id.zoomButton);
        zoomButton.setOnClickListener(v -> showZoomRatioDialog());
        captureButton.setOnClickListener(v -> takePhoto());
        flashButton.setOnClickListener(v -> cycleFlashMode());
        exitButton.setOnClickListener(v -> requestExit());
        setupCameraModeSelector();
        watermarkContainer.setOnClickListener(v -> handleWatermarkClick());
        
        watermarkLayoutListener = new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (watermarkContainer == null) return;

                int newWidth = watermarkContainer.getWidth();
                int newHeight = watermarkContainer.getHeight();

                // 检查：尺寸必须有效(>0) 且 必须与上一次的尺寸不同
                if (newWidth > 0 && newHeight > 0 && 
                    (newWidth != lastWatermarkWidth || newHeight != lastWatermarkHeight)) {
                    
                    // 1. 立即存储新的尺寸，防止下一次重复触发
                    lastWatermarkWidth = newWidth;
                    lastWatermarkHeight = newHeight;
                    positionWatermarkContainer();
                }
            }
        };
    }
    
    /**
    /**
     * 模式选择：照相模式 / 录像模式（前/后摄像头由底部切换按钮负责）
     */
    private void setupCameraModeSelector() {
        if (modeTabPhoto != null) {
            modeTabPhoto.setOnClickListener(v -> switchVideoMode(false));
        }
        if (modeTabVideo != null) {
            modeTabVideo.setOnClickListener(v -> switchVideoMode(true));
        }
        updateModeTabHighlight();
    }

    /** 前/后摄像头切换（自拍 / 普通）。 */
    private void toggleLensFacing() {
        if (isRecording) {
            Toast.makeText(this, "正在录像，请先停止", Toast.LENGTH_SHORT).show();
            return;
        }
        switchCamera(currentLensFacing == CameraSelector.LENS_FACING_BACK
                ? CameraSelector.LENS_FACING_FRONT
                : CameraSelector.LENS_FACING_BACK);
    }
    /** 切换 拍照/录像 模式：重新绑定 use case，并更新标签高亮。 */
    private void switchVideoMode(boolean videoMode) {
        if (isVideoMode == videoMode) return;
        if (isRecording) {
            Toast.makeText(this, "正在录像，请先停止", Toast.LENGTH_SHORT).show();
            return;
        }
        isVideoMode = videoMode;
        setCaptureButtonAlphaForRecording(false);   // 切模式时同步快门外观（录像=红色内圆）
        updateModeTabHighlight();
        bindCameraUseCases();
    }

    /** 照相/录像标签高亮 + 切换按钮文案显示当前镜头。 */
    private void updateModeTabHighlight() {
        int selectedColor = ContextCompat.getColor(this, R.color.icon_gold);
        boolean isBack = currentLensFacing == CameraSelector.LENS_FACING_BACK;

        if (modeTabPhoto != null) {
            modeTabPhoto.setTextColor(isVideoMode ? Color.WHITE : selectedColor);
            modeTabPhoto.setTypeface(null, isVideoMode ? Typeface.NORMAL : Typeface.BOLD);
        }
        if (modeTabVideo != null) {
            modeTabVideo.setTextColor(isVideoMode ? Color.parseColor("#FF5252") : Color.WHITE);
            modeTabVideo.setTypeface(null, isVideoMode ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (switchCameraButton != null) {
            // 前置(自拍)时图标金色，后置时白色，用来表示当前镜头
            switchCameraButton.setColorFilter(isBack ? Color.WHITE : ContextCompat.getColor(this, R.color.icon_gold));
        }
    }

    private void initGestureDetectors() {
        final float[] downPos = new float[2];
        final boolean[] pinchScaled = {false};
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (camera == null) return false;
                float currentZoom = camera.getCameraInfo().getZoomState().getValue().getZoomRatio();
                float newZoom = currentZoom * detector.getScaleFactor();
                cameraControl.setZoomRatio(newZoom);
                pinchScaled[0] = true;
                return true;
            }
        });

        previewView.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pinchScaled[0] = false;
                    downPos[0] = event.getX();
                    downPos[1] = event.getY();
                    break;
                case MotionEvent.ACTION_UP:
                    // 抬手时才对焦：避免双指缩放的第一根手指落下就触发对焦，干扰缩放流畅度
                    if (!pinchScaled[0]) {
                        float dx = event.getX() - downPos[0];
                        float dy = event.getY() - downPos[1];
                        if (dx * dx + dy * dy < touchSlop * touchSlop) {
                            performFocusOnPoint(downPos[0], downPos[1]);
                        }
                    }
                    break;
            }
            return true;
        });
    }

    private void initLocationServices() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                Log.d(TAG, "New location update received.");
                if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
                    gpsLocationTime = location.getTime();
                } else if (gpsLocationTime != 0
                        && System.currentTimeMillis() - gpsLocationTime < GPS_PRIORITY_TIMEOUT_MS) {
                    // GPS 定位仍然有效，优先使用 GPS，忽略网络定位结果
                    Log.d(TAG, "Ignoring network location, GPS fix is still fresh.");
                    return;
                }
                lat = location.getLatitude();
                lon = location.getLongitude();
                alt = location.getAltitude();
                locationTime = location.getTime();
                
                // [Refactored] 数据驱动更新
                updateDynamicItemsByType("camera_coord", getCurrentCoordText());
                
                findRoadInfoInBackground(lon, lat); 
                fetchAddressOnce(); 
            }
        };
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "startLocationUpdates called without permissions.");
            return;
        }

        try {
            // 部分设备（如无 GMS 的安卓系统）不存在 network 定位提供者，需先检测再使用，否则会崩溃
            boolean hasGpsProvider = locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER);
            boolean hasNetworkProvider = locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER);

            Location lastKnownGps = hasGpsProvider ? locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) : null;
            Location lastKnownNetwork = hasNetworkProvider ? locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) : null;
            Location bestLastKnownLocation = null;
            if (lastKnownGps != null && lastKnownNetwork != null) {
                bestLastKnownLocation = lastKnownGps.getTime() > lastKnownNetwork.getTime() ? lastKnownGps : lastKnownNetwork;
            } else {
                bestLastKnownLocation = (lastKnownGps != null) ? lastKnownGps : lastKnownNetwork;
            }

            if (bestLastKnownLocation != null && (System.currentTimeMillis() - bestLastKnownLocation.getTime()) < LOCATION_TIMEOUT_MS) {
                Log.d(TAG, "Using a recent last known location for initial match.");
                lat = bestLastKnownLocation.getLatitude();
                lon = bestLastKnownLocation.getLongitude();
                alt = bestLastKnownLocation.getAltitude();
                locationTime = bestLastKnownLocation.getTime();
                if (LocationManager.GPS_PROVIDER.equals(bestLastKnownLocation.getProvider())) {
                    gpsLocationTime = bestLastKnownLocation.getTime();
                }

                updateDynamicItemsByType("camera_coord", getCurrentCoordText());
                findRoadInfoInBackground(lon, lat);
                fetchAddressOnce();

            } else {
                Log.d(TAG, "No recent last known location found. Waiting for new updates.");
            }

            if (hasGpsProvider) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0.0f, locationListener);
            }
            if (hasNetworkProvider) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0.0f, locationListener);
            }
            Log.d(TAG, "Location updates started.");
        } catch (IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "Location updates failed to start (provider may not exist on this device).", e);
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
            Log.d(TAG, "Location updates stopped.");
        }
    }

    private void initOrientationListener() {
        orientationEventListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;

                int rotation;
                if (currentRotation == Surface.ROTATION_0) {
                    if (orientation > (45 + ORIENTATION_HYSTERESIS) && orientation < (135 - ORIENTATION_HYSTERESIS)) {
                        rotation = Surface.ROTATION_270;
                    } 
                    else if (orientation > (225 + ORIENTATION_HYSTERESIS) && orientation < (315 - ORIENTATION_HYSTERESIS)) {
                        rotation = Surface.ROTATION_90;
                    } 
                    else {
                        rotation = Surface.ROTATION_0;
                    }
                } 
                else if (currentRotation == Surface.ROTATION_270) {
                    if (orientation < (45 - ORIENTATION_HYSTERESIS) || orientation > (135 + ORIENTATION_HYSTERESIS)) {
                        if (orientation >= 225 && orientation < 315) {
                            rotation = Surface.ROTATION_90;
                        } else {
                            rotation = Surface.ROTATION_0;
                        }
                    }
                    else {
                        rotation = Surface.ROTATION_270;
                    }
                } 
                else if (currentRotation == Surface.ROTATION_90) {
                    if (orientation < (225 - ORIENTATION_HYSTERESIS) || orientation > (315 + ORIENTATION_HYSTERESIS)) {
                        if (orientation >= 45 && orientation < 135) {
                            rotation = Surface.ROTATION_270;
                        } else {
                            rotation = Surface.ROTATION_0;
                        }
                    }
                    else {
                        rotation = Surface.ROTATION_90;
                    }
                }
                else {
                    if (orientation >= 45 && orientation < 135) {
                        rotation = Surface.ROTATION_270;
                    } else if (orientation >= 225 && orientation < 315) {
                        rotation = Surface.ROTATION_90;
                    } else {
                        rotation = Surface.ROTATION_0;
                    }
                }
                if (rotation != currentRotation) {
                    currentRotation = rotation;
                    updateUiRotation(currentRotation);
                    if (imageCapture != null) {
                        imageCapture.setTargetRotation(currentRotation);
                    }
                }
            }
        };
    }
    // endregion

    // region --- 相机核心逻辑 ---
    private void cycleFlashMode() {
        switch (currentFlashMode) {
            case ImageCapture.FLASH_MODE_OFF:
                currentFlashMode = ImageCapture.FLASH_MODE_ON;
                break;
            case ImageCapture.FLASH_MODE_ON:
                currentFlashMode = ImageCapture.FLASH_MODE_AUTO;
                break;
            case ImageCapture.FLASH_MODE_AUTO:
                currentFlashMode = ImageCapture.FLASH_MODE_OFF;
                break;
        }
        updateFlashMode();
    }

    private void switchCamera(int targetLensFacing) {
        currentLensFacing = targetLensFacing;
        currentFlashMode = ImageCapture.FLASH_MODE_OFF; // 重置闪光灯
        bindCameraUseCases(); // 重新绑定
    }

    private void updateFlashMode() {
        if (imageCapture == null || camera == null) return;

        switch (currentFlashMode) {
            case ImageCapture.FLASH_MODE_OFF:
                flashButton.setImageResource(R.drawable.ic_flash_off);
                imageCapture.setFlashMode(ImageCapture.FLASH_MODE_OFF);
                camera.getCameraControl().enableTorch(false);
                break;
            case ImageCapture.FLASH_MODE_ON:
                flashButton.setImageResource(R.drawable.ic_flash_on);
                imageCapture.setFlashMode(ImageCapture.FLASH_MODE_ON);
                break;
            case ImageCapture.FLASH_MODE_AUTO:
                flashButton.setImageResource(R.drawable.ic_flash_auto);
                imageCapture.setFlashMode(ImageCapture.FLASH_MODE_AUTO);
                camera.getCameraControl().enableTorch(false);
                break;
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "相机初始化失败", e);
                Toast.makeText(this, "相机初始化失败", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) {
            Log.e(TAG, "Camera Provider is not available.");
            return;
        }

        AspectRatioStrategy aspectRatioStrategy = new AspectRatioStrategy(
                targetAspectRatio,
                AspectRatioStrategy.FALLBACK_RULE_AUTO
        );
        ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspectRatioStrategy)
                .build();

        Preview preview = new Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(currentLensFacing)
                .build();

        try {
            if (!cameraProvider.hasCamera(cameraSelector)) {
                String cameraType = currentLensFacing == CameraSelector.LENS_FACING_FRONT ? "前置" : "后置";
                Log.e(TAG, "设备上没有可用的" + cameraType + "摄像头");
                Toast.makeText(this, "未找到" + cameraType + "摄像头", Toast.LENGTH_LONG).show();
                
                if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
                    currentLensFacing = CameraSelector.LENS_FACING_BACK;
                } else {
                    currentLensFacing = CameraSelector.LENS_FACING_FRONT;
                }
                return;
            }
        } catch (androidx.camera.core.CameraInfoUnavailableException e) {
            Log.e(TAG, "无法检查相机可用性", e);
            Toast.makeText(this, "无法获取相机信息", Toast.LENGTH_SHORT).show();
            return;
        }

        // 录像模式：VideoCapture + Recorder（画面上不烧水印，水印数据随视频入库）
        if (isVideoMode) {
            // 画质从高到低退让，避免个别机型只支持低分辨率视频时直接绑定失败
            QualitySelector qualitySelector = QualitySelector.fromOrderedList(
                    java.util.Arrays.asList(Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD));
            Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build();
            videoCapture = VideoCapture.withOutput(recorder);
            imageCapture = null;
            try {
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture);
                cameraControl = camera.getCameraControl();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
                setupZoomButton();
                updateModeTabHighlight();
                flashButton.setVisibility(View.GONE);
            } catch (Exception e) {
                Log.e(TAG, "录像用例绑定失败", e);
                videoCapture = null;
                isVideoMode = false;
                updateModeTabHighlight();
                Toast.makeText(this, "本机不支持录像，已切回拍照模式", Toast.LENGTH_LONG).show();
                // 失败后回到拍照绑定
                bindCameraUseCases();
            }
            return;
        }

        imageCapture = new ImageCapture.Builder()
                .setResolutionSelector(resolutionSelector)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(currentRotation)
                .build();

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
            cameraControl = camera.getCameraControl();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
            setupZoomButton();
            updateModeTabHighlight();
            
            if (camera.getCameraInfo().hasFlashUnit()) {
                flashButton.setVisibility(View.VISIBLE);
                updateFlashMode();
            } else {
                flashButton.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            Log.e(TAG, "相机绑定失败", e);
            Toast.makeText(this, "相机绑定失败，请重启应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void takePhoto() {
        // 录像模式：快门 = 开始/停止录像
        if (isVideoMode) {
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
            return;
        }
        if (imageCapture == null) return;
        // 上一张还在保存时忽略重复点击，避免两张照片的处理任务互相踩踏
        if (isCaptureProcessing) return;
        isCaptureProcessing = true;
        setCaptureButtonEnabled(false);

        // 根据设置播放拍照提示音，便于用户确认拍摄已触发
        if (cameraConfig != null && cameraConfig.shutter_sound && mediaActionSound != null) {
            mediaActionSound.play(MediaActionSound.SHUTTER_CLICK);
        }

        try {
            File photoFile = createImageFile();
            ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

            imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                    // 在快门瞬间抓取水印位图与 GPS，保证「所见即所得」（之后 GPS/时间继续变化也不影响这一张）
                    CapturedState state = captureStateSnapshot();
                    processCapturedImage(photoFile, state);
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    handlePhotoError("拍照失败", exception);
                }
            });
        } catch (IOException e) {
            handlePhotoError("创建文件失败", e);
        }
    }

    // region --- 录像：开始 / 停止 / 入库 ---

    /** 开始录像：先定名字 + 写待入表 + 写水印首行，再直接录进正式目录。 */
    private void startRecording() {
        if (isRecording) return;
        if (videoCapture == null) {
            Toast.makeText(this, "本机不支持录像", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            long baseTime = System.currentTimeMillis();
            videoWatermarkJson = buildVideoWatermarkJson(baseTime);
            videoTicket = fileImporter.beginVideo(targetDirId, IdGenerator.get_id_by_time() + "." + WatermarkFileCodec.EXTENSION, videoWatermarkJson);
            lastWatermarkValues.clear();
            JSONObject initialValues = snapshotWatermarkValues();
            java.util.Iterator<String> valueKeys = initialValues.keys();
            while (valueKeys.hasNext()) {
                String key = valueKeys.next();
                lastWatermarkValues.put(key, initialValues.optString(key, ""));
            }
            lastWatermarkAnchor = currentAnchor();

            FileOutputOptions options = new FileOutputOptions.Builder(videoTicket.targetFile).build();
            isRecording = true;
            recordStartMs = baseTime;
            if (recordingTimer != null) {
                recordingTimer.setVisibility(View.VISIBLE);
                recordingTimer.setText("● 00:00");
            }
            recordTimerHandler.post(recordTimerRunnable);
            setCaptureButtonAlphaForRecording(true);

            currentRecording = videoCapture.getOutput()
                    .prepareRecording(this, options)
                    .start(ContextCompat.getMainExecutor(this), event -> onVideoRecordEvent(event));
        } catch (Exception e) {
            isRecording = false;
            if (recordingTimer != null) recordingTimer.setVisibility(View.GONE);
            recordTimerHandler.removeCallbacks(recordTimerRunnable);
            setCaptureButtonAlphaForRecording(false);
            if (videoTicket != null) {
                fileImporter.abortVideo(videoTicket);
                videoTicket = null;
            }
            handlePhotoError("开始录像失败", e);
        }
    }

    /** 停止录像（Finalize 回调里再做入库）。 */
    private void stopRecording() {
        if (currentRecording == null) {
            isRecording = false;
            return;
        }
        try {
            currentRecording.stop();
        } catch (Exception e) {
            Log.e(TAG, "停止录像失败", e);
        }
        currentRecording = null;
    }

    private void onVideoRecordEvent(VideoRecordEvent event) {
        if (!(event instanceof VideoRecordEvent.Finalize)) return;
        VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
        boolean failed = fin.hasError();
        isRecording = false;
        recordTimerHandler.removeCallbacks(recordTimerRunnable);
        if (recordingTimer != null) recordingTimer.setVisibility(View.GONE);
        setCaptureButtonAlphaForRecording(false);

        if (failed) {
            Log.e(TAG, "录像结束但有错误: " + fin.getError());
            if (videoTicket != null) {
                fileImporter.abortVideo(videoTicket);
                videoTicket = null;
            }
            Toast.makeText(this, "录像失败", Toast.LENGTH_SHORT).show();
            if (pendingFinishAfterCapture) {
                pendingFinishAfterCapture = false;
                finishWithSession();
            }
            return;
        }

        // 录像文件已 finalize → 入库（算哈希、缩略图、提交）
        final FileImporter.VideoTicket ticket = videoTicket;
        videoTicket = null;
        if (ticket == null) return;
        try {
            JSONObject record = fileImporter.finishVideo(ticket);
            CameraSessionStore.addRecord(this, record);
            onCaptureSaved(false);
        } catch (Throwable e) {
            fileImporter.abortVideo(ticket);
            handlePhotoError("录像保存失败", e);
        }
    }

    private void setCaptureButtonAlphaForRecording(boolean recording) {
        if (captureButton == null) return;
        captureButton.setAlpha(1.0f);
        if (recording) {
            // 录像中：白底红描边（按下即停止）
            captureButton.setBackgroundResource(R.drawable.capture_button_recording);
            captureButton.setScaleX(0.82f);
            captureButton.setScaleY(0.82f);
        } else {
            // 未录像：按当前模式还原（录像模式红色内圆，拍照模式白色内圆）
            captureButton.setBackgroundResource(isVideoMode
                    ? R.drawable.capture_button_video
                    : R.drawable.capture_button);
            captureButton.setScaleX(1.0f);
            captureButton.setScaleY(1.0f);
    }
    }

    /** 水印数据首行（全量）：配置里的 header/body/foot/position/scale + 图标引用 + 首段字段值。 */
    private JSONObject buildVideoWatermarkJson(long baseTimeMs) {
        JSONObject wm = new JSONObject();
        try {
            wm.put("ver", 1);
            wm.put("base_time_ms", baseTimeMs);
            JSONObject root = new JSONObject(WatermarkConfigManager.getInstance(this).getConfig());
            JSONObject config = root.optJSONObject("watermark");
            if (config != null) {
                for (String key : new String[]{"scale", "radius", "position", "header", "body", "foot"}) {
                    if (config.has(key)) wm.put(key, config.get(key));
                }
                // 图标只存引用（图标本体仍放在应用私有目录，不进数据库）
                JSONObject header = config.optJSONObject("header");
                JSONObject icon = header == null ? null : header.optJSONObject("icon");
                if (icon != null && icon.has("value")) {
                    wm.put("icon_ref", icon.optString("value", ""));
                }
            }
            wm.put("pos", currentAnchor());

            JSONArray segments = new JSONArray();
            JSONObject first = new JSONObject();
            first.put("from_ms", 0);
            first.put("values", snapshotWatermarkValues());
            segments.put(first);
            wm.put("segments", segments);
        } catch (Exception e) {
            Log.e(TAG, "构建视频水印数据失败", e);
        }
        return wm;
    }

    /** 当前所有水印字段值（time 除外，时间由播放位置实时换算）。 */
    private JSONObject snapshotWatermarkValues() {
        JSONObject values = new JSONObject();
        try {
            for (Map.Entry<String, String> entry : watermarkDataStore.entrySet()) {
                if ("time".equals(entry.getKey())) continue;
                values.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
        } catch (Exception e) {
            Log.e(TAG, "快照水印字段失败", e);
        }
        return values;
    }

    /** 当前水印锚点：left_top / left_bottom / right_top / right_bottom。 */
    private String currentAnchor() {
        Position pos = watermarkConfig == null ? null : watermarkConfig.position;
        boolean right = pos != null && pos.right != null;
        boolean top = pos != null && pos.top != null;
        return (right ? "right" : "left") + "_" + (top ? "top" : "bottom");
    }

    /** 每秒把"变化的水印字段/位置"追加成一条 delta 记录（首行已是全量）。 */
    private void recordWatermarkDelta() {
        if (!isRecording || videoWatermarkJson == null) return;
        try {
            JSONArray segments = videoWatermarkJson.optJSONArray("segments");
            if (segments == null) return;
            long elapsed = Math.max(0, System.currentTimeMillis() - recordStartMs);

            JSONObject valuesDelta = new JSONObject();
            JSONObject current = snapshotWatermarkValues();
            java.util.Iterator<String> keys = current.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = current.optString(key, "");
                String old = lastWatermarkValues.get(key);
                if (old == null || !old.equals(value)) {
                    valuesDelta.put(key, value);
                    lastWatermarkValues.put(key, value);
                }
            }

            String anchor = currentAnchor();
            boolean anchorChanged = !anchor.equals(lastWatermarkAnchor);

            if (valuesDelta.length() > 0 || anchorChanged) {
                JSONObject seg = new JSONObject();
                seg.put("from_ms", elapsed);
                if (valuesDelta.length() > 0) seg.put("values", valuesDelta);
                if (anchorChanged) {
                    seg.put("pos", anchor);
                    lastWatermarkAnchor = anchor;
                }
                segments.put(seg);
            }
        } catch (Exception e) {
            Log.e(TAG, "记录水印变化失败", e);
        }
    }

    // endregion

    /**
     * 拍下一张照片瞬间的状态快照：水印位图 + GPS + 配置 + 预览尺寸。
     * 处理时只读这份快照，处理期间即使配置被重新加载、预览尺寸变化，也不会影响这一张的一致性。
     * 水印位图必须在此刻（UI 线程）从水印 View 抓取，因为 View 只能在自己的 UI 线程上绘制。
     */
    private CapturedState captureStateSnapshot() {
        CapturedState state = new CapturedState();
        state.gpsValid = isLocationValid();
        state.lat = lat;
        state.lon = lon;
        state.alt = alt;
        state.showWatermark = showWatermark;
        state.showQrCode = showQrCode;
        state.watermarkConfig = watermarkConfig;
        state.qrCodeConfig = qrCodeConfig;
        state.previewWidth = previewView != null ? previewView.getWidth() : 0;
        state.previewHeight = previewView != null ? previewView.getHeight() : 0;
        state.watermarkDensity = watermarkDensity();
        if (state.showWatermark && state.watermarkConfig != null) {
            state.watermarkOverlay = createBitmapFromView(watermarkContainer);
        }
        return state;
    }

    private void performFocusOnPoint(float x, float y) {
        if (cameraControl == null) return;
        try {
            MeteringPoint meteringPoint = previewView.getMeteringPointFactory().createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(meteringPoint, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build();
            cameraControl.startFocusAndMetering(action);
            showFocusIndicator(x, y);
        } catch (Exception e) {
            Log.e(TAG, "聚焦失败", e);
        }
    }

    /**
     * 根据当前摄像头支持的变焦范围，计算可选倍数档位并更新模式栏右侧的倍数按钮
     * （按钮实时显示当前倍数，点击弹出档位选择框；切换前后摄像头后需重新调用，无可用档位时隐藏按钮）
     */
    private void setupZoomButton() {
        availableZoomRatios.clear();
        if (camera == null) {
            zoomButton.setVisibility(View.GONE);
            return;
        }

        ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();
        if (zoomState == null) {
            zoomButton.setVisibility(View.GONE);
            return;
        }
        float minZoom = zoomState.getMinZoomRatio();
        float maxZoom = zoomState.getMaxZoomRatio();

        for (float preset : ZOOM_PRESETS) {
            if (preset >= minZoom - 0.01f && preset <= maxZoom + 0.01f) {
                availableZoomRatios.add(preset);
            }
        }
        // 1x 必须存在，作为基准档位
        if (!availableZoomRatios.contains(1.0f)) {
            availableZoomRatios.add(1.0f);
            Collections.sort(availableZoomRatios);
        }
        if (availableZoomRatios.size() < 2) {
            zoomButton.setVisibility(View.GONE);
            return;
        }
        zoomButton.setVisibility(View.VISIBLE);

        // 实时显示当前变焦倍数（捏合缩放、档位选择都会触发更新）
        LiveData<ZoomState> liveData = camera.getCameraInfo().getZoomState();
        if (observedZoomState != null) {
            observedZoomState.removeObservers(this);
        }
        observedZoomState = liveData;
        liveData.observe(this, state -> {
            if (state != null) {
                zoomButton.setText(formatZoomText(state.getZoomRatio()));
            }
        });
    }

    /**
     * 弹出倍数选择模态框，最接近当前倍数的一项默认选中，点击即精确切换
     */
    private void showZoomRatioDialog() {
        if (camera == null || availableZoomRatios.isEmpty()) return;

        String[] labels = new String[availableZoomRatios.size()];
        for (int i = 0; i < availableZoomRatios.size(); i++) {
            labels[i] = formatZoomText(availableZoomRatios.get(i));
        }

        float currentZoom = 1.0f;
        ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();
        if (zoomState != null) {
            currentZoom = zoomState.getZoomRatio();
        }
        int checkedIndex = 0;
        float minDiff = Float.MAX_VALUE;
        for (int i = 0; i < availableZoomRatios.size(); i++) {
            float diff = Math.abs(availableZoomRatios.get(i) - currentZoom);
            if (diff < minDiff) {
                minDiff = diff;
                checkedIndex = i;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("选择变焦倍数")
                .setSingleChoiceItems(labels, checkedIndex, (dialog, which) -> {
                    if (cameraControl != null) {
                        cameraControl.setZoomRatio(availableZoomRatios.get(which));
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 倍数显示格式化：1.0 -> "1x"，0.6 -> "0.6x"，1.25 -> "1.3x"
     */
    private String formatZoomText(float ratio) {
        return String.format(Locale.getDefault(), "%.1fx", ratio).replace(".0x", "x");
    }
    // endregion

    // region --- 图像和文件处理 ---
    /**
     * 处理一张刚拍摄的照片（同步，在 UI 线程上跑完）：
     * 备份原图（可选）→ 旋转/水印/EXIF → 同步相册（可选）→ 记入拍照会话。
     * 处理结束后相机保持打开，用户可以继续拍摄；照片信息在退出时统一交给前端。
     *
     * <p>这里刻意采用同步处理（与改造前一致）：处理期间界面会短暂停顿，
     * 但「拍一张→存一张→恢复快门」是一串顺行流程，不会与下一张或退出动作交叉，时序最简单。
     */
    private void processCapturedImage(final File photoFile, final CapturedState state) {
        final boolean syncAlbum = cameraConfig != null && cameraConfig.sync_album;
        final boolean saveOriginal = cameraConfig != null && cameraConfig.save_original;

        File originalFile = null;
        try {
            // 备份无水印原图：先复制原始捕获，再走与水印图完全相同的处理管线（旋转/EXIF/GPS），只是不叠加任何叠加层
            if (saveOriginal) {
                originalFile = copyOriginalImage(photoFile);
                processImageOverlays(originalFile.getAbsolutePath(), false, state);
            }
            processImageOverlays(photoFile.getAbsolutePath(), true, state);

            if (syncAlbum) {
                try {
                    copyToDcim(photoFile, false);
                    // 开启原图保存时，相册中会同时出现水印图和无水印原图两张照片
                    if (originalFile != null) {
                        copyToDcim(originalFile, false);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "同步到相册失败", e);
                    Toast.makeText(CameraActivity.this, "同步相册失败", Toast.LENGTH_SHORT).show();
                }
            }

            // 合成完成 → 立刻在 Java 层入库（FileImporter 负责查重命名/缩略图/落盘/写库），
            // 入库成功才把记录放进本次会话清单；照片文件此时已被移入 app_data。
            JSONObject watermarkedRecord = fileImporter.importLocalFile(photoFile, targetDirId, photoFile.getName());
            CameraSessionStore.addRecord(this, watermarkedRecord);
            if (originalFile != null) {
                JSONObject originalRecord = fileImporter.importLocalFile(originalFile, targetDirId, originalFile.getName());
                CameraSessionStore.addRecord(this, originalRecord);
            }
        } catch (Throwable e) {
            // 连 OutOfMemoryError 也要接住，否则快门会一直停在「处理中」再也点不动
            handlePhotoError("照片保存失败", e);
            return;
        } finally {
            if (state.watermarkOverlay != null && !state.watermarkOverlay.isRecycled()) {
                state.watermarkOverlay.recycle();
                state.watermarkOverlay = null;
            }
        }
        onCaptureSaved(syncAlbum);
    }

    /**
     * 一张照片处理完成：恢复快门，必要时执行被推迟的退出。
     * 保存反馈放在屏幕上部（见 {@link #showTopToast}），不遮挡底部操作区。
     */
    private void onCaptureSaved(boolean syncedToAlbum) {
        isCaptureProcessing = false;
        setCaptureButtonEnabled(true);
        showTopToast(syncedToAlbum ? "照片已保存并同步相册" : "照片已保存");
        if (pendingFinishAfterCapture) {
            pendingFinishAfterCapture = false;
            finishWithSession();
        }
    }

    /**
     * 屏幕上部居中显示的轻提示。
     * 用自定义视图而不是普通文本 Toast：普通 Toast 在部分系统版本上会被强制到底部，
     * 那正好盖住快门/闪光灯/退出所在的操作区；自定义视图的 {setGravity} 才稳定生效。
     */
    private void showTopToast(String message) {
        try {
            Toast toast = new Toast(this);
            TextView view = new TextView(this);
            view.setText(message);
            view.setTextColor(Color.WHITE);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xCC000000);
            background.setCornerRadius(dpToPx(18));
            view.setBackground(background);
            view.setPadding(dpToPx(16), dpToPx(9), dpToPx(16), dpToPx(9));
            toast.setView(view);
            toast.setDuration(Toast.LENGTH_SHORT);
            // 顶部居中：避开底部 120dp 的操作区，也避开左下水印预览
            toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dpToPx(72));
            toast.show();
        } catch (Exception e) {
            Log.w(TAG, "显示提示失败", e);
        }
    }

    // region --- 会话退出：把本次新增图片信息交接给前端 ---

    /** 退出入口（返回键 / 右上角关闭按钮）。正在保存/正在录像时延迟到完成后再退出，避免丢数据。 */
    private void requestExit() {
        if (isRecording) {
            // 先停止录像，Finalize 回调里入库后再退出
            pendingFinishAfterCapture = true;
            stopRecording();
            return;
        }
        if (isCaptureProcessing) {
            pendingFinishAfterCapture = true;
            return;
        }
        finishWithSession();
    }

    /**
     * 结束本次拍照会话：
     * 有照片 → 以 RESULT_OK 回传「本次新增的库记录」（照片早已入库，前端只负责刷新列表）；
     * 无照片 → RESULT_CANCELED（等同于用户取消）。
     */
    private void finishWithSession() {
        int count = CameraSessionStore.getRecordCount();
        if (count <= 0) {
            CameraSessionStore.clearCurrent(this);
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        setResult(RESULT_OK, CameraSessionStore.buildResultIntent(this));
        // 重置内存会话状态；中转目录只剩余空目录/已入库文件，交由 GC 回收
        CameraSessionStore.closeSession();
        finish();
    }
    // endregion

    /**
     * 将刚拍摄、尚未叠加水印的原始照片复制为同目录下的 "*_原图.jpg" 文件，
     * 随后由 processImageOverlays 统一完成旋转和 EXIF 处理
     */
    private File copyOriginalImage(File photoFile) throws IOException {
        String name = photoFile.getName();
        int dotIndex = name.lastIndexOf('.');
        String originalName = (dotIndex > 0 ? name.substring(0, dotIndex) : name) + "_原图.jpg";
        File originalFile = new File(photoFile.getParentFile(), originalName);
        try (java.io.InputStream in = new java.io.FileInputStream(photoFile);
             FileOutputStream out = new FileOutputStream(originalFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
        return originalFile;
    }

    private void copyToDcim(File sourceFile) throws IOException {
        copyToDcim(sourceFile, true);
    }

    private void copyToDcim(File sourceFile, boolean showToast) throws IOException {
        ContentValues values = new ContentValues();
        String fileName = sourceFile.getName();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + File.separator + "Camera");
        values.put(MediaStore.Images.Media.IS_PENDING, 1); 

        ContentResolver resolver = getContentResolver();
        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        
        Uri itemUri = null;
        try {
            itemUri = resolver.insert(collection, values);
            if (itemUri == null) {
                throw new IOException("无法创建 MediaStore 记录");
            }

            try (java.io.OutputStream out = resolver.openOutputStream(itemUri);
                 java.io.InputStream in = new java.io.FileInputStream(sourceFile)) {
                
                if (out == null) {
                    throw new IOException("无法打开 MediaStore Uri 的输出流");
                }

                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }

            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(itemUri, values, null, null);
            Log.d(TAG, "照片已成功同步到相册: " + itemUri);
            if (showToast) {
                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "照片已保存并同步到相册", Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            Log.e(TAG, "同步到相册失败", e);
            if (itemUri != null) {
                resolver.delete(itemUri, null, null);
            }
            throw new IOException("同步到相册失败: " + e.getMessage(), e);
        }
    }
    /**
     * 图片处理统一管线：按 EXIF 旋转像素、可选叠加水印/二维码、重写 EXIF（含 GPS）。
     * 本方法在 UI 线程同步执行（处理期间界面会短暂停顿），绘制水印位图的工作由调用方在快门瞬间完成。
     *
     * @param applyOverlays true=水印图（叠加水印和二维码）；false=无水印原图（其余处理完全一致）
     * @param state         拍摄瞬间的快照（水印位图、GPS），保证同一张照片所用数据一致
     */
    private void processImageOverlays(String imagePath, boolean applyOverlays, CapturedState state) throws IOException {
        Bitmap originalBitmap = BitmapFactory.decodeFile(imagePath);
        if (originalBitmap == null) throw new IOException("无法解码图片文件");

        ExifInterface oldExif = new ExifInterface(imagePath);
        int orientation = oldExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
        }
        Bitmap rotatedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.getWidth(), originalBitmap.getHeight(), matrix, true);
        if (rotatedBitmap != originalBitmap) {
            originalBitmap.recycle();
        }

        Bitmap finalBitmap = rotatedBitmap.copy(Bitmap.Config.ARGB_8888, true);
        if (finalBitmap != rotatedBitmap) {
            rotatedBitmap.recycle();
        }

        if (applyOverlays && state.showWatermark && state.watermarkConfig != null && state.watermarkOverlay != null) {
            finalBitmap = addOverlayToBitmap(finalBitmap, state.watermarkOverlay,
                    state.watermarkConfig.position, state.watermarkConfig.scale, state);
        }

        if (applyOverlays && state.showQrCode && state.qrCodeConfig != null && state.gpsValid) {
            String qrContent = generateAmapUrl(state.lat, state.lon, "拍照位置");
            int baseQrSizeDp = 128;
            int finalQrSizePx = Math.round(baseQrSizeDp * state.qrCodeConfig.scale * state.watermarkDensity);
            Bitmap qrCodeBitmap = generateQrCodeBitmap(qrContent, finalQrSizePx, finalQrSizePx, state.qrCodeConfig.padding, state.qrCodeConfig.radius, state.watermarkDensity);
            if (qrCodeBitmap != null) {
                finalBitmap = addOverlayToBitmap(finalBitmap, qrCodeBitmap, state.qrCodeConfig.position, 1.0f, state);
                qrCodeBitmap.recycle();
            }
        }

        try (FileOutputStream out = new FileOutputStream(imagePath)) {
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        finalBitmap.recycle();

        ExifInterface newExif = new ExifInterface(imagePath);

        copyExifTags(oldExif, newExif);

        newExif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));

        if (newExif.getAttribute(ExifInterface.TAG_DATETIME) == null) {
            String currentTimestamp = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault()).format(new Date());
            newExif.setAttribute(ExifInterface.TAG_DATETIME, currentTimestamp);
            newExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, currentTimestamp);
            newExif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, currentTimestamp);
        }

        if (state.gpsValid) {
            Log.d(TAG, "正在写入拍摄瞬间的 GPS 信息到 EXIF");
            Location location = new Location("");
            location.setLatitude(state.lat);
            location.setLongitude(state.lon);
            location.setAltitude(state.alt);
            newExif.setGpsInfo(location);
        } else {
            Log.w(TAG, "最终未写入 GPS 信息。");
            if (applyOverlays && !gpsWarningShown) {
                // 每个会话只提示一次（连续拍照时不反复弹窗挡住快门），原图不重复提示
                gpsWarningShown = true;
                showTopToast("GPS信号弱，未写入位置信息");
            }
        }

        newExif.saveAttributes();
    }

    private void copyExifTags(ExifInterface oldExif, ExifInterface newExif) {
        String[] tagsToCopy = {
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_ISO_SPEED,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        };

        for (String tag : tagsToCopy) {
            String value = oldExif.getAttribute(tag);
            if (value != null) {
                newExif.setAttribute(tag, value);
            }
        }
    }

    private File createImageFile() throws IOException {
        // 会话目录中的新文件；不再像旧实现那样清空目录，否则会删掉本次会话里已经拍好的照片
        return CameraSessionStore.createPhotoFile(this);
    }

    private void handlePhotoError(String message, Throwable e) {
        Log.e(TAG, message, e);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        isCaptureProcessing = false;
        setCaptureButtonEnabled(true);
        // 单张失败不再直接退出相机：用户的其它已拍照片还在会话里，可以继续拍或直接退出交付
        if (pendingFinishAfterCapture) {
            pendingFinishAfterCapture = false;
            finishWithSession();
        }
    }
    // endregion

    // region --- [Refactored] 水印数据驱动和UI构建 ---

    private void updateWatermarkItem(String itemId, String newValue) {
        if (itemId == null) return;
        if (newValue == null) newValue = "";

        String oldValue = watermarkDataStore.put(itemId, newValue);
        
        if (newValue.equals(oldValue)) {
            return;
        }

        if (Looper.myLooper() != Looper.getMainLooper()) {
            final String finalValue = newValue;
            runOnUiThread(() -> updateTextView(itemId, finalValue));
        } else {
            updateTextView(itemId, newValue);
        }
    }

    private void updateTextView(String itemId, String value) {
        WeakReference<TextView> ref = watermarkViewMap.get(itemId);
        if (ref != null) {
            TextView textView = ref.get();
            if (textView != null) {
                if (!textView.getText().toString().equals(value)) {
                    textView.setText(value);
                }
            } else {
                watermarkViewMap.remove(itemId);
            }
        }
    }

    private void updateDynamicItemsByType(String typeToUpdate, String newValue) {
        if (watermarkConfig == null) return;

        for (Section s : new Section[]{watermarkConfig.header, watermarkConfig.body, watermarkConfig.foot}) {
            if (s == null) continue;
            
            if (s.title != null && typeToUpdate.equals(s.title.type)) {
                updateWatermarkItem(s.title.id, newValue);
            }
            if (s.content != null) {
                for (WatermarkItem item : s.content.values()) {
                    if (typeToUpdate.equals(item.type)) {
                        updateWatermarkItem(item.id, newValue);
                    }
                }
            }
        }
    }

    private void resetGpsDependentData() {
        String coordText = "定位中...";
        String roadText = "暂无道路信息";
        String pileText = "暂无桩号";
        String addressText = "获取地址中...";
        
        // 同步重置内存中的数据，避免下次 onResume 时恢复旧值
        currentAddress = addressText;
        currentRoadMatch = null;
        
        if (watermarkConfig == null) return;

        for (Section s : new Section[]{watermarkConfig.header, watermarkConfig.body, watermarkConfig.foot}) {
            if (s == null) continue;

            if (s.title != null) {
                resetItemIfGpsDependent(s.title, coordText, roadText, pileText, addressText);
            }
            if (s.content != null) {
                for (WatermarkItem item : s.content.values()) {
                    resetItemIfGpsDependent(item, coordText, roadText, pileText, addressText);
                }
            }
        }
    }

    private void resetItemIfGpsDependent(WatermarkItem item, String coord, String road, String pile, String address) {
        switch (item.type) {
            case "camera_coord":
                updateWatermarkItem(item.id, coord);
                break;
            case "road":
                updateWatermarkItem(item.id, road);
                break;
            case "camera_pile":
                updateWatermarkItem(item.id, pile);
                break;
            case "address":
                updateWatermarkItem(item.id, address);
                break;
        }
    }

    private void initializeDataStore() {
        watermarkDataStore.clear();
        if (watermarkConfig == null) return;

        List<Section> sections = new ArrayList<>();
        if (watermarkConfig.header != null) sections.add(watermarkConfig.header);
        if (watermarkConfig.body != null) sections.add(watermarkConfig.body);
        if (watermarkConfig.foot != null) sections.add(watermarkConfig.foot);

        for (Section section : sections) {
            if (!section.display) continue;
            
            if (section.title != null) {
                watermarkDataStore.put(section.title.id, getContentValue(section.title));
            }
            if (section.content != null) {
                for (WatermarkItem item : section.content.values()) {
                    watermarkDataStore.put(item.id, getContentValue(item));
                }
            }
        }
    }

    private void setupWatermarkPreview() {
        if (!showWatermark || watermarkConfig == null) return;
        
        initializeDataStore();

        watermarkContainer.removeAllViews();
        watermarkViewMap.clear();

        List<Section> availableSections = new ArrayList<>();
        
        if (watermarkConfig.header != null && watermarkConfig.header.display) {
            availableSections.add(watermarkConfig.header);
        }
        if (watermarkConfig.body != null && watermarkConfig.body.display) {
            availableSections.add(watermarkConfig.body);
        }
        if (watermarkConfig.foot != null && watermarkConfig.foot.display) {
            availableSections.add(watermarkConfig.foot);
        }

        for (int i = 0; i < availableSections.size(); i++) {
            Section section = availableSections.get(i);
            boolean isFirst = (i == 0);
            boolean isLast = (i == availableSections.size() - 1);

            int[] padding;
            if (section == watermarkConfig.header) {
                padding = new int[]{wmDp(0), wmDp(0), wmDp(0), wmDp(0)};
            } else if (section == watermarkConfig.body) {
                padding = new int[]{wmDp(10), wmDp(4), wmDp(10), wmDp(4)};
            } else {
                padding = new int[]{wmDp(10), wmDp(4), wmDp(10), wmDp(4)};
            }
            
            ClippingFrameLayout sectionLayout = createSectionLayout(section, padding);
            
            if (sectionLayout != null) {
                float rTL = wmPx(watermarkConfig.radius[0]);
                float rTR = wmPx(watermarkConfig.radius[1]);
                float rBR = wmPx(watermarkConfig.radius[2]);
                float rBL = wmPx(watermarkConfig.radius[3]);

                float topLeftRadius = isFirst ? rTL : 0;
                float topRightRadius = isFirst ? rTR : 0;
                float bottomRightRadius = isLast ? rBR : 0;
                float bottomLeftRadius = isLast ? rBL : 0;

                float[] radii = new float[]{
                        topLeftRadius, topLeftRadius, topRightRadius, topRightRadius,
                        bottomRightRadius, bottomRightRadius, bottomLeftRadius, bottomLeftRadius
                };
                sectionLayout.setCornerRadiiPx(radii);

                if (sectionLayout.getChildCount() > 0) {
                    View child = sectionLayout.getChildAt(0);
                    if (child.getBackground() instanceof GradientDrawable) {
                        GradientDrawable background = (GradientDrawable) child.getBackground();
                        background.setCornerRadii(radii);
                    }
                }
                watermarkContainer.addView(sectionLayout);
            }
        }
        positionWatermarkContainer();
    }

    private ClippingFrameLayout createSectionLayout(Section section, int[] padding) {
        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(padding[0], padding[1], padding[2], padding[3]);

        boolean hasContent = false;
        if (section.icon != null || section.title != null) {
            View headerView = createHeaderView(section.icon, section.title); 
            if (headerView != null) {
                contentLayout.addView(headerView);
                hasContent = true;
            }
        }
        for (WatermarkItem item : getSortedContentItems(section.content)) {
            View itemView = createContentItemView(item); 
            if (itemView != null) {
                contentLayout.addView(itemView);
                hasContent = true;
            }
        }

        if (!hasContent) {
            return null;
        }

        ClippingFrameLayout clippingContainer = new ClippingFrameLayout(this);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(section.background[3], section.background[0], section.background[1], section.background[2]));
        contentLayout.setBackground(background);
        clippingContainer.addView(contentLayout);
        return clippingContainer;
    }

    private View createHeaderView(WatermarkItem icon, WatermarkItem title) {
        LinearLayout rootHeaderLayout = new LinearLayout(this);
        rootHeaderLayout.setOrientation(LinearLayout.HORIZONTAL);
        rootHeaderLayout.setGravity(Gravity.CENTER_VERTICAL);

        boolean hasTitle = (title != null && !title.value.isEmpty());
        boolean hasIcon = (icon != null && icon.display);

        if (hasIcon) {
            int iconWidth = icon.width;
            int iconHeight = icon.height;

            if (iconWidth == 0 && iconHeight == 0) {
                hasIcon = false;
            } else if (iconWidth == 0 && iconHeight != 0) {
                rootHeaderLayout.setMinimumHeight(wmDp(iconHeight));
                hasIcon = false;
            } else if (iconWidth != 0 && iconHeight == 0) {
                hasIcon = false;
            }
        }

        if (!hasTitle && !hasIcon) {
            return null;
        }

        View iconView = null;
        if (hasIcon) {
            iconView = createIconView(icon);
            if (iconView == null) hasIcon = false;
        }

        View titleContainer = null;
        if (hasTitle) {
            RelativeLayout innerTitleContainer = new RelativeLayout(this);
            int[] padding = new int[]{wmDp(8), wmDp(8), wmDp(8), wmDp(8)};
            innerTitleContainer.setPadding(padding[0], padding[1], padding[2], padding[3]);
            
            String titleText = watermarkDataStore.get(title.id);
            if (titleText == null) titleText = title.value; // Fallback
            
            TextView titleView = createTextView(titleText, title.color, title.size, Typeface.BOLD);
            watermarkViewMap.put(title.id, new WeakReference<>(titleView));
            
            RelativeLayout.LayoutParams titleParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            );
            titleParams.addRule(RelativeLayout.CENTER_IN_PARENT);
            innerTitleContainer.addView(titleView, titleParams);
            titleContainer = innerTitleContainer;
        }
        
        String iconPos = (hasIcon && icon.position != null) ? icon.position : "left";

        if (hasIcon && hasTitle) {
            if ("right".equals(iconPos)) {
                LinearLayout.LayoutParams titleContainerParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                rootHeaderLayout.addView(titleContainer, titleContainerParams);
                rootHeaderLayout.addView(iconView);
            } else { // 默认 left
                rootHeaderLayout.addView(iconView);
                LinearLayout.LayoutParams titleContainerParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                rootHeaderLayout.addView(titleContainer, titleContainerParams);
            }
        } else if (hasTitle) {
            LinearLayout.LayoutParams titleContainerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rootHeaderLayout.addView(titleContainer, titleContainerParams);
        } else if (hasIcon) {
            if ("right".equals(iconPos)) {
                View spacer = new View(this);
                LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, 0, 1.0f);
                rootHeaderLayout.addView(spacer, spacerParams);
            }
            rootHeaderLayout.addView(iconView);
        }

        return rootHeaderLayout;
    }

    private View createContentItemView(WatermarkItem item) {
        String value = watermarkDataStore.get(item.id);
        if (value == null) {
            value = "";
        }

        LinearLayout itemLayout = new LinearLayout(this);
        itemLayout.setOrientation(LinearLayout.HORIZONTAL);
        itemLayout.setPadding(0, wmPx(1), 0, wmPx(1));
        
        if (item.nameDisplay && !item.name.isEmpty()) {
            itemLayout.addView(createTextView(item.name + ": ", item.color, item.size, Typeface.BOLD));
        }

        TextView valueView = createTextView(value, item.color, item.size, Typeface.NORMAL);

        watermarkViewMap.put(item.id, new WeakReference<>(valueView));

        itemLayout.addView(valueView);

        return itemLayout;
    }

    private ImageView createIconView(WatermarkItem icon) {
        try {
            Bitmap bitmap = loadBitmap(icon.value);
            if (bitmap == null) return null;
            ImageView imageView = new ImageView(this);
            int width = wmDp(icon.width);
            int height = wmDp(icon.height);
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
            imageView.setImageBitmap(scaledBitmap);
            if (bitmap != scaledBitmap) bitmap.recycle();
            return imageView;
        } catch (Exception e) {
            Log.e(TAG, "创建图标失败", e);
            return null;
        }
    }

    private TextView createTextView(String text, int color, float size, int typefaceStyle) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextColor(color);
        // 使用固定 px 字号，避免系统字体缩放（老年模式 fontScale）影响烧进照片的文字大小
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, wmDp(size));
        textView.setTypeface(Typeface.DEFAULT, typefaceStyle);
        textView.setMaxWidth(wmPx(MAX_WATERMARK_LINE_WIDTH_DP * watermarkConfig.scale));
        textView.setFontFeatureSettings("tnum");
        return textView;
    }

    /**
     * 显示 "input" 类型的编辑对话框
     * @param item 正在编辑的 WatermarkItem 对象 (用于读写 value)
     */
    private void showInputDialog(WatermarkItem item) {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(item.name);

        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        
        input.setText(watermarkDataStore.get(item.id)); 
        input.setSelection(input.getText().length());

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        int margin = dpToPx(20);
        params.setMargins(margin, dpToPx(10), margin, dpToPx(10));
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String newValue = input.getText().toString();
            
            // 1. 更新 WatermarkItem 内存中的值 (用于持久化或下次打开)
            item.value = newValue; 
            
            // 2. 触发单一数据驱动更新 (更新 UI)
            updateWatermarkItem(item.id, newValue);

            // [新增] 3. 如果是 input_auto_save 类型，执行持久化保存
            if ("input_auto_save".equals(item.type)) {
                saveConfigForAutoSaveItem(item.id, newValue);
            }
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());
        builder.show();
    }
    
    // [MODIFIED] 使用 WatermarkConfigManager 进行保存
    private void saveConfigForAutoSaveItem(String itemId, String newValue) {
        new Thread(() -> {
            // 获取最新配置（可能是缓存的）
            String configJson = WatermarkConfigManager.getInstance(this).getConfig();
            
            if (configJson == null) return; 
            
            try {
                JSONObject root = new JSONObject(configJson);
                JSONObject watermark = root.optJSONObject("watermark");
                if (watermark == null) return;
                
                boolean updated = false;
                String[] sections = {"header", "body", "foot"};
                
                // 遍历所有 section 寻找匹配的 item ID
                for (String sectionName : sections) {
                    JSONObject section = watermark.optJSONObject(sectionName);
                    if (section == null) continue;
                    
                    JSONObject content = section.optJSONObject("content");
                    if (content != null && content.has(itemId)) {
                        JSONObject itemObj = content.getJSONObject(itemId);
                        itemObj.put("value", newValue);
                        updated = true;
                        // 找到并更新后跳出循环
                        break; 
                    }
                }
                
                if (updated) {
                    // 使用 Manager 保存更新后的配置
                    WatermarkConfigManager.getInstance(this).saveConfig(root.toString());
                }
                
            } catch (JSONException e) {
                Log.e(TAG, "保存 input_auto_save 配置失败", e);
            }
        }).start();
    }

    /**
     * 处理水印容器点击事件 (用于 "input" 字段)
     */
    private void handleWatermarkClick() {
        // 1. 收集所有 "input" 和 "input_auto_save" 字段
        List<WatermarkItem> inputItems = collectInputItems();

        // 2. 没有 input 字段则不操作
        if (inputItems.isEmpty()) {
            return;
        }

        // 3. 优化: 只有一个 input 字段，直接显示对话框
        if (inputItems.size() == 1) {
            showInputDialog(inputItems.get(0));
            return;
        }

        // 4. 多个 input 字段，显示选择列表
        String[] itemNames = new String[inputItems.size()];
        for (int i = 0; i < inputItems.size(); i++) {
            itemNames[i] = inputItems.get(i).name;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择要输入的字段");
        builder.setItems(itemNames, (dialog, which) -> {
            // 5. 获取选择项
            WatermarkItem selectedItem = inputItems.get(which);
            // 6. 显示该项的输入对话框
            showInputDialog(selectedItem);
        });
        
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    /**
     * 从配置中收集所有 "input" 类型的 WatermarkItem
     */
    private List<WatermarkItem> collectInputItems() {
        List<WatermarkItem> items = new ArrayList<>();
        if (watermarkConfig == null) {
            return items;
        }

        addInputItemsFromSection(items, watermarkConfig.header);
        addInputItemsFromSection(items, watermarkConfig.body);
        addInputItemsFromSection(items, watermarkConfig.foot);

        // 按 'index' 排序
        Collections.sort(items, Comparator.comparingInt(o -> o.index));
        return items;
    }

    /**
     * 辅助方法: 从 Section 提取 "input" 字段
     */
    private void addInputItemsFromSection(List<WatermarkItem> list, Section section) {
        if (section == null || section.content == null) {
            return;
        }
        for (WatermarkItem item : section.content.values()) {
            // [修改] 支持 input 和 input_auto_save 类型
            if ("input".equals(item.type) || "input_auto_save".equals(item.type)) {
                list.add(item);
            }
        }
    }
    // endregion

    // region --- UI 定位与旋转 ---
    private void updateUiRotation(int rotation) {
        float targetRotation;
        switch (rotation) {
            case Surface.ROTATION_90: targetRotation = 90; break;
            case Surface.ROTATION_270: targetRotation = -90; break;
            default: targetRotation = 0; break;
        }
        AccelerateDecelerateInterpolator interpolator = new AccelerateDecelerateInterpolator();
        watermarkContainer.animate().rotation(targetRotation).setInterpolator(interpolator).setDuration(300).start();
        captureButton.animate().rotation(targetRotation).setInterpolator(interpolator).setDuration(300).start();
        flashButton.animate().rotation(targetRotation).setInterpolator(interpolator).setDuration(300).start();
        exitButton.animate().rotation(targetRotation).setInterpolator(interpolator).setDuration(300).start();
        positionWatermarkContainer();
    }

    private void positionWatermarkContainer() {
        if (watermarkConfig == null) return;
        watermarkContainer.post(() -> {
            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) watermarkContainer.getLayoutParams();
            if(params == null) params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

            params.removeRule(RelativeLayout.ALIGN_PARENT_LEFT);
            params.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            params.removeRule(RelativeLayout.ALIGN_PARENT_TOP);
            params.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            params.leftMargin = params.topMargin = params.rightMargin = params.bottomMargin = 0;

            int margin = wmPx(10);
            int width = watermarkContainer.getWidth();
            int height = watermarkContainer.getHeight();
            Position pos = watermarkConfig.position;

            setHorizontalPosition(params, pos, margin, width, height, currentRotation);
            setVerticalPosition(params, pos, margin, width, height, currentRotation);
            watermarkContainer.setLayoutParams(params);
        });
    }

    private void setHorizontalPosition(RelativeLayout.LayoutParams params, Position pos, int margin, int w, int h, int rotation) {
        int offset = (w - h) / 2;
        switch (rotation) {
            case Surface.ROTATION_90:
                if (pos.left != null) {
                    params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                    params.topMargin = offset + Math.round(pos.left * watermarkConfig.scale) + margin;
                } else if (pos.right != null) {
                    params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                    params.bottomMargin = offset - Math.round(pos.right * watermarkConfig.scale) + margin;
                }
                break;
            case Surface.ROTATION_270:
                if (pos.left != null) {
                    params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                    params.bottomMargin = offset - Math.round(pos.left * watermarkConfig.scale) + margin;
                } else if (pos.right != null) {
                    params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                    params.topMargin = offset + Math.round(pos.right * watermarkConfig.scale) + margin;
                }
                break;
            default:
                if (pos.left != null) {
                    params.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
                    params.leftMargin = Math.round(pos.left * watermarkConfig.scale) + margin;
                } else if (pos.right != null) {
                    params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                    params.rightMargin = Math.round(pos.right * watermarkConfig.scale) + margin;
                }
                break;
        }
    }

    private void setVerticalPosition(RelativeLayout.LayoutParams params, Position pos, int margin, int w, int h, int rotation) {
        int offset = (h - w) / 2;
        switch (rotation) {
            case Surface.ROTATION_90:
                if (pos.top != null) {
                    params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                    params.rightMargin = offset - Math.round(pos.top * watermarkConfig.scale) + margin;
                } else if (pos.bottom != null) {
                    params.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
                    params.leftMargin = offset + Math.round(pos.bottom * watermarkConfig.scale) + margin;
                }
                break;
            case Surface.ROTATION_270:
                if (pos.top != null) {
                    params.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
                    params.leftMargin = offset + Math.round(pos.top * watermarkConfig.scale) + margin;
                } else if (pos.bottom != null) {
                    params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                    params.rightMargin = offset - Math.round(pos.bottom * watermarkConfig.scale) + margin;
                }
                break;
            default:
                if (pos.top != null) {
                    params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                    params.topMargin = Math.round(pos.top * watermarkConfig.scale) + margin;
                } else if (pos.bottom != null) {
                    params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                    params.bottomMargin = Math.round(pos.bottom * watermarkConfig.scale) + margin;
                }
                break;
        }
    }

    private void showFocusIndicator(float x, float y) {
        removeFocusIndicator();
        ImageView indicator = new ImageView(this);
        indicator.setTag("focus_indicator");
        indicator.setBackgroundResource(R.drawable.focus_indicator_crosshair);
        int size = dpToPx(80);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(size, size);
        params.leftMargin = (int) (x - size / 2f);
        params.topMargin = (int) (y - size / 2f);
        ((ViewGroup) previewView.getParent()).addView(indicator, params);
        indicator.setAlpha(0f);
        indicator.setScaleX(1.5f);
        indicator.setScaleY(1.5f);
        indicator.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .withEndAction(() -> indicator.animate().alpha(0f).setStartDelay(1000).setDuration(800)
                        .withEndAction(this::removeFocusIndicator).start())
                .start();
    }

    private void removeFocusIndicator() {
        ViewGroup parent = (ViewGroup) previewView.getParent();
        View indicator = parent.findViewWithTag("focus_indicator");
        if (indicator != null) {
            indicator.animate().cancel();
            parent.removeView(indicator);
        }
    }
    // endregion

    // region --- 二维码生成 ---
    private String generateAmapUrl(double dlat, double dlon, String dname) {
        try {
            return "https://uri.amap.com/marker?position=" + dlon + "," + dlat + "&name=" + URLEncoder.encode(dname, "UTF-8") + "&coordinate=wgs84&callnative=1";
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    /** 生成二维码叠加层（含内边距与圆角）。density 由调用方传入，不依赖当前 View 尺寸。 */
    private Bitmap generateQrCodeBitmap(String content, int width, int height, float[] padding, float[] radius, float density) {
        if (content.isEmpty()) return null;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix bitMatrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, height, hints);
            Bitmap qrBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < width; x++) for (int y = 0; y < height; y++)
                qrBitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.TRANSPARENT);
            int pL = Math.round(padding[0] * density), pT = Math.round(padding[1] * density);
            int pR = Math.round(padding[2] * density), pB = Math.round(padding[3] * density);
            int finalW = width + pL + pR, finalH = height + pT + pB;
            Bitmap finalBitmap = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(finalBitmap);
            Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(Color.WHITE);
            Path path = new Path();
            float r0 = radius[0] * density, r1 = radius[1] * density, r2 = radius[2] * density, r3 = radius[3] * density;
            float[] radii = {r0, r0, r1, r1, r2, r2, r3, r3};
            path.addRoundRect(new RectF(0, 0, finalW, finalH), radii, Path.Direction.CW);
            canvas.drawPath(path, bgPaint);
            canvas.drawBitmap(qrBitmap, pL, pT, null);
            qrBitmap.recycle();
            return finalBitmap;
        } catch (WriterException e) {
            return null;
        }
    }
    // endregion

    // region --- 配置解析 ---
    private void parseConfig(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (root.has("camera")) parseCamera(root.getJSONObject("camera"));
            if (root.has("watermark")) parseWatermark(root.getJSONObject("watermark"));
            if (root.has("qrcode")) parseQrCode(root.getJSONObject("qrcode"));
        } catch (JSONException e) {
            Log.e(TAG, "解析主配置失败", e);
            showWatermark = false;
            showQrCode = false;
        }
    }

    private void parseCamera(JSONObject json) {
        try {
            cameraConfig = new CameraConfig();
            cameraConfig.proportion = json.optString("proportion", "16:9");
            cameraConfig.sync_album = json.optBoolean("sync_album", false);
            // 旧配置无此字段时默认关闭提示音、不保存原图
            // 拍照声音默认开启；旧配置里没有该字段时也按开启处理
            cameraConfig.shutter_sound = json.optBoolean("shutter_sound", true);
            cameraConfig.save_original = json.optBoolean("save_original", false);
            if ("4:3".equals(cameraConfig.proportion)) {
                targetAspectRatio = AspectRatio.RATIO_4_3;
            } else {
                targetAspectRatio = AspectRatio.RATIO_16_9;
            }
        } catch (Exception e) {
            Log.e(TAG, "解析相机配置失败", e);
            targetAspectRatio = AspectRatio.RATIO_16_9;
        }
    }

    private void parseWatermark(JSONObject json) {
        try {
            watermarkConfig = new WatermarkConfig();
            watermarkConfig.enable = json.optBoolean("enable", false);
            if (!watermarkConfig.enable) {
                showWatermark = false; return;
            }
            watermarkConfig.scale = (float) json.optDouble("scale", 1.0);
            parseFloatArray(json, "radius", watermarkConfig.radius);
            if (json.has("position")) parsePosition(json.getJSONObject("position"), watermarkConfig.position);
            watermarkConfig.header = parseSection(json, "header");
            watermarkConfig.body = parseSection(json, "body");
            watermarkConfig.foot = parseSection(json, "foot");
            
            showWatermark = (watermarkConfig.header != null && watermarkConfig.header.display) ||
                            (watermarkConfig.body != null && watermarkConfig.body.display) ||
                            (watermarkConfig.foot != null && watermarkConfig.foot.display);

        } catch (JSONException e) {
            Log.e(TAG, "解析水印配置失败", e);
            showWatermark = false;
        }
    }

    private void parseQrCode(JSONObject json) {
        try {
            qrCodeConfig = new QrCodeConfig();
            qrCodeConfig.enable = json.optBoolean("enable", false);
            if (!qrCodeConfig.enable) {
                showQrCode = false; return;
            }
            qrCodeConfig.scale = (float) json.optDouble("scale", 1.0);
            if (json.has("position")) parsePosition(json.getJSONObject("position"), qrCodeConfig.position);
            if (json.has("radius")) parseFloatArray(json, "radius", qrCodeConfig.radius);
            if (json.has("padding")) parseFloatArray(json, "padding", qrCodeConfig.padding);
            showQrCode = true;
        } catch (JSONException e) {
            Log.e(TAG, "解析二维码配置失败", e);
            showQrCode = false;
        }
    }

    private Section parseSection(JSONObject config, String sectionName) throws JSONException {
        if (!config.has(sectionName)) return null;
        JSONObject sectionObj = config.getJSONObject(sectionName);
        Section section = new Section();
        
        if (sectionObj.has("display") && Boolean.FALSE.equals(sectionObj.opt("display"))) {
             section.display = false;
        }
        
        parseIntArray(sectionObj, "background", section.background);
        
        if (sectionObj.has("icon")) {
            section.icon = parseWatermarkItem(sectionObj.getJSONObject("icon"));
            section.icon.id = "_" + sectionName + "_icon"; 
        }
        if (sectionObj.has("title")) {
            section.title = parseWatermarkItem(sectionObj.getJSONObject("title"));
            section.title.id = "_" + sectionName + "_title"; 
        }
        if (sectionObj.has("content")) {
            JSONObject contentObj = sectionObj.getJSONObject("content");
            Iterator<String> keys = contentObj.keys();
            while (keys.hasNext()) {
                String key = keys.next(); 
                WatermarkItem item = parseWatermarkItem(contentObj.getJSONObject(key));
                item.id = key; 
                section.content.put(key, item);
            }
        }
        return section;
    }

    private WatermarkItem parseWatermarkItem(JSONObject obj) {
        WatermarkItem item = new WatermarkItem();
        
        if (obj.has("display") && Boolean.FALSE.equals(obj.opt("display"))) {
            item.display = false;
        }
        
        item.name = obj.optString("name", "");
        item.type = obj.optString("type", "string");
        item.value = obj.optString("value", ""); // "input" 类型的默认值
        item.position = obj.optString("position", "left");
        item.nameDisplay = obj.optBoolean("name_display", false);
        item.index = obj.optInt("index", 0);
        try {
            item.color = Color.parseColor(obj.optString("color", "#FFFFFF"));
        } catch (Exception e) {
            item.color = Color.WHITE;
        }
        item.size = (float) obj.optDouble("size", 16);
        item.width = obj.optInt("width", 24);
        item.height = obj.optInt("height", 24);
        return item;
    }

    private void parsePosition(JSONObject json, Position position) throws JSONException {
        if (json.has("left")) position.left = (float) json.getDouble("left");
        if (json.has("top")) position.top = (float) json.getDouble("top");
        if (json.has("right")) position.right = (float) json.getDouble("right");
        if (json.has("bottom")) position.bottom = (float) json.getDouble("bottom");
    }

    private void parseFloatArray(JSONObject obj, String key, float[] array) throws JSONException {
        if (!obj.has(key)) return;
        JSONArray jsonArray = obj.getJSONArray(key);
        for (int i = 0; i < Math.min(array.length, jsonArray.length()); i++) {
            array[i] = (float) jsonArray.getDouble(i);
        }
    }

    private void parseIntArray(JSONObject obj, String key, int[] array) throws JSONException {
        if (!obj.has(key)) return;
        JSONArray jsonArray = obj.getJSONArray(key);
        for (int i = 0; i < Math.min(array.length, jsonArray.length()); i++) {
            array[i] = jsonArray.getInt(i);
        }
    }
    // endregion

    // region --- 权限管理 ---
    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
                startLocationUpdates();
                uiUpdateHandler.post(uiUpdateRunnable);
            } else {
                Toast.makeText(this, "需要授予相机和位置权限才能使用", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
    // endregion

    // region --- 外部数据处理 ---

    private void checkForAddressItem() {
        if (watermarkConfig == null) return;

        // 先重置再扫描：配置中移除地址项后（onResume重新解析），标志位能正确归零
        this.hasAddressItem = false;

        List<Section> sections = new ArrayList<>();
        if (watermarkConfig.header != null) sections.add(watermarkConfig.header);
        if (watermarkConfig.body != null) sections.add(watermarkConfig.body);
        if (watermarkConfig.foot != null) sections.add(watermarkConfig.foot);

        for (Section section : sections) {
            if (section.content != null) {
                for (WatermarkItem item : section.content.values()) {
                    if ("address".equals(item.type)) {
                        this.hasAddressItem = true;
                        return;
                    }
                }
            }
        }
    }

    private void fetchAddressOnce() {
        if (hasAddressItem &&isActivityResumed&& isLocationValid() && !isFetchingAddress) {

            isFetchingAddress = true; 
            
            final double currentLon = lon;
            final double currentLat = lat;
            
            GaodeApi.getAddressFromGpsAsync(CameraActivity.this, currentLon, currentLat, new GaodeApi.AddressCallback() {
                
                @Override
                public void onAddressUpdated(String addressInfo, boolean success) {
                    isFetchingAddress = false;
                    currentAddress = addressInfo;
                    runOnUiThread(() -> {
                        updateDynamicItemsByType("address", currentAddress);
                    });
                }
            });
        }
    }

    private void checkForWeatherItem() {
        if (watermarkConfig == null) return;

        // 先重置再扫描：配置中移除天气项后（onResume重新解析），标志位能正确归零
        this.hasWeatherItem = false;
        this.weatherAdcode = null;

        List<Section> sections = new ArrayList<>();
        if (watermarkConfig.header != null) sections.add(watermarkConfig.header);
        if (watermarkConfig.body != null) sections.add(watermarkConfig.body);
        if (watermarkConfig.foot != null) sections.add(watermarkConfig.foot);

        for (Section section : sections) {
            if (section.content != null) {
                for (WatermarkItem item : section.content.values()) {
                    if ("weather".equals(item.type)) {
                        this.hasWeatherItem = true;
                        this.weatherAdcode = item.value;
                        return;
                    }
                }
            }
        }
    }

    private void loadInitialCaches() {
        if (hasWeatherItem) {
            // adcode为空（自动模式）时此处无法预加载：需先由GPS解析出adcode才能匹配持久化缓存，
            // 由后续轮询的自动流程统一处理（解析后比对缓存，一致且未过期则复用，否则拉取）
            GaodeApi.loadWeatherFromCache(this, weatherAdcode);
        }
        if (hasAddressItem) {
            GaodeApi.loadAddressFromCache(this);
        }
    }
    // endregion

    // region --- 工具方法和数据获取 ---
    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /**
     * 水印渲染专用的密度基准：以预览宽度对应 360dp 设计宽度进行换算。
     * 不使用系统 DisplayMetrics.density，也不受系统 fontScale 影响，
     * 避免老年模式（字体/显示大小缩放）导致烧进照片的水印尺寸异常。
     */
    private float watermarkDensity() {
        int previewWidth = previewView != null ? previewView.getWidth() : 0;
        if (previewWidth <= 0) {
            previewWidth = getResources().getDisplayMetrics().widthPixels;
        }
        return previewWidth > 0 ? previewWidth / 360f : 3.0f;
    }

    /** 水印渲染专用 dp→px（含 watermarkConfig.scale），替代原 dp()。 */
    private int wmDp(float dp) {
        float scale = watermarkConfig != null ? watermarkConfig.scale : 1.0f;
        return Math.round(dp * scale * watermarkDensity());
    }

    /** 水印渲染专用 dp→px（不含 watermarkConfig.scale），替代原 dpToPx()。 */
    private int wmPx(float dp) {
        return Math.round(dp * watermarkDensity());
    }

    private List<WatermarkItem> getSortedContentItems(Map<String, WatermarkItem> content) {
        List<WatermarkItem> items = new ArrayList<>(content.values());
        Collections.sort(items, Comparator.comparingInt(o -> o.index));
        return items;
    }

    /**
     * [Refactored] 获取 item 的 *初始* 值
     */
    private String getContentValue(WatermarkItem item) {
        // [修改] 包含 input_auto_save
        if ("input".equals(item.type) || "input_auto_save".equals(item.type) || "string".equals(item.type)) {
            return item.value;
        }
        
        TypeHandler handler = typeHandlers.get(item.type);
        return handler != null ? handler.getValue(item) : item.value;
    }

    private String getCurrentAddressText() {
        if (!isLocationValid()) {
            return "获取地址中...";
        }
        return currentAddress;
    }

    private String getCurrentWeatherText() {
        // adcode为空时为自动模式：天气由GPS解析的adcode拉取，统一返回内存缓存
        // （初始为"获取中..."，失败时为错误提示），保证预览与拍照出图一致
        return GaodeApi.getCachedWeatherInfo();
    }

    private String getCurrentCoordText() {
        if (isLocationValid()) {
            return String.format(Locale.getDefault(), "%.6f, %.6f", lon, lat);
        }
        return "定位中...";
    }

    private String getCurrentRoadText() {
        if (isLocationValid()) {
            if (currentRoadMatch != null && !"暂无".equals(currentRoadMatch.roadName)) {
                String roadName = currentRoadMatch.roadName;
                String roadCountryId = currentRoadMatch.roadCountryId;
                if (roadCountryId != null && !roadCountryId.isEmpty()&&!"暂无".equals(roadCountryId)) {
                    return roadCountryId + "-" + roadName;
                }
                return roadName;
            }
            return "暂无道路信息";
        }
        return "暂无道路信息";
    }

    private String getCurrentPileText() {
        if (isLocationValid()) {
            if (currentRoadMatch != null) {
                return currentRoadMatch.formattedPile;
            }
            return "暂无桩号";
        }
        return "暂无桩号";
    }

    private void findRoadInfoInBackground(double lng, double lat) {
        if (isMatchingRoad) {
            return;
        }
        if (System.currentTimeMillis() - lastMatchRequestTime < MATCH_INTERVAL_MS) {
            return;
        }
        isMatchingRoad = true;
        lastMatchRequestTime = System.currentTimeMillis();

        new Thread(() -> {
            try {
                if (roadDatabaseHelper == null) return;
                String lastRoadPartId = currentRoadMatch != null ? currentRoadMatch.roadPartId : null;
                RoadDatabaseHelper.RoadLocationMatch match = roadDatabaseHelper.findNearestRoadLocation(lng, lat, lastRoadPartId);
                
                currentRoadMatch = match; 

                updateDynamicItemsByType("road", getCurrentRoadText());
                updateDynamicItemsByType("camera_pile", getCurrentPileText());
            } finally {
                isMatchingRoad = false;
            }
        }).start();
    }

    private String getCurrentTimeText() {
        return new SimpleDateFormat("yyyy-MM-dd E HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private boolean isLocationValid() {
        return locationTime != 0 && (System.currentTimeMillis() - locationTime <= LOCATION_TIMEOUT_MS);
    }

    private Bitmap loadBitmap(String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            if (path.startsWith("file:///android_asset/")) {
                return BitmapFactory.decodeStream(getAssets().open(path.substring(22)));
            } else if (path.startsWith("content://")) {
                return BitmapFactory.decodeStream(getContentResolver().openInputStream(Uri.parse(path)));
            } else if (path.startsWith("file://")) {
                return BitmapFactory.decodeFile(Uri.parse(path).getPath());
            } else {
                return BitmapFactory.decodeFile(path);
            }
        } catch (Exception e) {
            Log.e(TAG, "加载图片失败: " + path, e);
            return null;
        }
    }


    private Bitmap createBitmapFromView(View view) {
        if (view == null || view.getWidth() == 0 || view.getHeight() == 0 || watermarkConfig == null) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        
        Path clipPath = new Path();
        float[] globalRadii = watermarkConfig.radius;
        float rTL = wmPx(globalRadii[0]);
        float rTR = wmPx(globalRadii[1]);
        float rBR = wmPx(globalRadii[2]);
        float rBL = wmPx(globalRadii[3]);
        float[] radii = {rTL, rTL, rTR, rTR, rBR, rBR, rBL, rBL};
        clipPath.addRoundRect(new RectF(0, 0, view.getWidth(), view.getHeight()), radii, Path.Direction.CW);
        canvas.clipPath(clipPath);
        
        view.draw(canvas);
        return bitmap;
    }

    /**
     * 把叠加层（水印/二维码）画到照片上。
     * 预览尺寸与密度都取自拍摄瞬间的快照（state），因此本方法不依赖当前 View 尺寸。
     */
    private Bitmap addOverlayToBitmap(Bitmap photo, Bitmap overlay, Position position, float scale, CapturedState state) {
        Bitmap result = photo.copy(Bitmap.Config.ARGB_8888, true);
        if (result != photo) {
            photo.recycle();
        }
        Canvas canvas = new Canvas(result);
        float resolutionScale = calculateOverlayScale(result.getWidth(), result.getHeight(), state.previewWidth, state.previewHeight);
        Bitmap scaledOverlay = overlay;
        if (Math.abs(resolutionScale - 1.0f) > 0.01) {
            scaledOverlay = Bitmap.createScaledBitmap(overlay,
                    (int) (overlay.getWidth() * resolutionScale),
                    (int) (overlay.getHeight() * resolutionScale), true);
        }
        float x = calculateOverlayX(result.getWidth(), scaledOverlay.getWidth(), position, resolutionScale, state.watermarkDensity);
        float y = calculateOverlayY(result.getHeight(), scaledOverlay.getHeight(), position, resolutionScale, state.watermarkDensity);
        canvas.drawBitmap(scaledOverlay, x, y, null);
        if (scaledOverlay != overlay) scaledOverlay.recycle();
        return result;
    }

    private float calculateOverlayScale(float photoWidth, float photoHeight, int previewWidth, int previewHeight) {
        if (previewWidth == 0 || previewHeight == 0) {
            return Math.min(photoWidth / 1080f, photoHeight / 1920f);
        }
        float photoLongerSide = Math.max(photoWidth, photoHeight);
        float previewLongerSide = Math.max(previewWidth, previewHeight);
        if (previewLongerSide > 0) {
            return photoLongerSide / previewLongerSide;
        } else {
            return 1.0f;
        }
    }

    private float calculateOverlayX(float photoWidth, float overlayWidth, Position pos, float scale, float density) {
        float margin = Math.round(10 * density);
        if (pos.left != null) return pos.left * scale + margin;
        if (pos.right != null) return photoWidth - overlayWidth - (pos.right * scale) - margin;
        return margin;
    }

    private float calculateOverlayY(float photoHeight, float overlayHeight, Position pos, float scale, float density) {
        float margin = Math.round(10 * density);
        if (pos.top != null) return pos.top * scale + margin;
        if (pos.bottom != null) return photoHeight - overlayHeight - (pos.bottom * scale) - margin;
        return margin;
    }

    private void setCaptureButtonEnabled(boolean enabled) {
        captureButton.setEnabled(enabled);
        captureButton.setAlpha(enabled ? 1.0f : 0.5f);
    }
    // endregion

    // region --- 内部类和接口 ---
    public static class ClippingFrameLayout extends FrameLayout {
        private Path clipPath;
        private float[] cornerRadii;
        private final RectF rectF = new RectF();

        public ClippingFrameLayout(@NonNull Context context) { super(context); init(); }
        public ClippingFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
        public ClippingFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

        private void init() {
            clipPath = new Path();
            setWillNotDraw(false);
        }

        public void setCornerRadiiPx(float[] radii) {
            this.cornerRadii = radii;
            updateClipPath();
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            rectF.set(0, 0, w, h);
            updateClipPath();
        }

        private void updateClipPath() {
            if (cornerRadii != null && rectF.width() > 0 && rectF.height() > 0) {
                clipPath.reset();
                clipPath.addRoundRect(rectF, cornerRadii, Path.Direction.CW);
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            canvas.save();
            if (cornerRadii != null && !clipPath.isEmpty()) {
                canvas.clipPath(clipPath);
            }
            super.dispatchDraw(canvas);
            canvas.restore();
        }
    }

    /**
     * 一次快门的「拍摄瞬间状态」：水印位图 + GPS + 相关配置 + 预览尺寸/密度。
     * 照片处理时只读这份快照，避免处理期间时间/位置/配置/尺寸变化带来不一致。
     */
    private static class CapturedState {
        Bitmap watermarkOverlay = null;
        boolean gpsValid = false;
        double lat = 0, lon = 0, alt = 0;
        boolean showWatermark = false;
        boolean showQrCode = false;
        WatermarkConfig watermarkConfig = null;
        QrCodeConfig qrCodeConfig = null;
        int previewWidth = 0;
        int previewHeight = 0;
        float watermarkDensity = 3.0f;
    }

    private static class CameraConfig {
        String proportion = "16:9";
        boolean sync_album = false;
        boolean shutter_sound = true;
        boolean save_original = false;
    }

    private static class WatermarkConfig {
        boolean enable = false;
        float scale = 1.0f;
        float[] radius = {8, 8, 8, 8};
        Position position = new Position();
        Section header, body, foot;
    }

    private static class QrCodeConfig {
        boolean enable = false;
        float scale = 1.0f;
        Position position = new Position();
        float[] radius = {4, 4, 4, 4};
        float[] padding = {4, 4, 4, 4};
    }

    private static class Position {
        Float left, top, right, bottom;
    }

    private static class Section {
        boolean display = true;
        int[] background = {0, 0, 0, 0};
        WatermarkItem icon, title;
        Map<String, WatermarkItem> content = new HashMap<>();
        int[] internalPadding = {0, 0, 0, 0};
    }

    private static class WatermarkItem {
        String id;
        boolean display = true; 
        String name = "", type = "string", value = "", position = "left";
        boolean nameDisplay = false;
        int index = 0, color = Color.WHITE, width = 24, height = 24;
        float size = 16;
    }

    private interface TypeHandler {
        String getValue(WatermarkItem item);
    }
    // endregion
}
