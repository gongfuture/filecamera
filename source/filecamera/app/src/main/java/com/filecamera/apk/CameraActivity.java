package com.filecamera.apk;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.graphics.drawable.InsetDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ScaleGestureDetector;
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
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
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
import java.net.URLEncoder;
import java.lang.ref.WeakReference;
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
import android.os.Environment;
import com.google.android.material.tabs.TabLayout;

public class CameraActivity extends AppCompatActivity {

    // region --- 常量和成员变量 ---
    private static final String TAG = "CameraActivity";
    private static final int REQUEST_PERMISSIONS = 101;
    private static final long LOCATION_TIMEOUT_MS = 5000L;
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

            // 3. 检查天气
            if (hasWeatherItem) {
					GaodeApi.requestWeatherUpdateIfStale(CameraActivity.this, weatherAdcode, new GaodeApi.WeatherCallback() {
                    @Override
                    public void onWeatherUpdated(String weatherInfo) {
                        runOnUiThread(() -> {
                            updateDynamicItemsByType("weather", weatherInfo);
                        });
                    }
                });
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
    private TabLayout cameraModeTabLayout;
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

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                setResult(RESULT_CANCELED);
                finish(); // 关闭当前 Activity
            }
        });
        String configJson = getIntent().getStringExtra("watermark_config");
        if (configJson == null) configJson = "{}";

        roadDatabaseHelper = new RoadDatabaseHelper(this);

        initViews();
        initGestureDetectors();
        initLocationServices(); // 仅初始化服务，不请求更新
        initOrientationListener();
        parseConfig(configJson);
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
        if (cameraProvider != null) {
            cameraProvider.unbindAll(); // 作为最后保障
        }
    }
    // endregion

    // region --- 初始化方法 ---
    private void initViews() {
        previewView = findViewById(R.id.previewView);
        captureButton = findViewById(R.id.captureButton);
        flashButton = findViewById(R.id.flashButton);
        cameraModeTabLayout = findViewById(R.id.cameraModeTabLayout);
        watermarkContainer = findViewById(R.id.watermarkContainer);
        exitButton = findViewById(R.id.exitButton);
        captureButton.setOnClickListener(v -> takePhoto());
        flashButton.setOnClickListener(v -> cycleFlashMode());
        exitButton.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
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
     * 设置模式选择器 (普通/自拍)
     */
    private void setupCameraModeSelector() {
        cameraModeTabLayout.addTab(cameraModeTabLayout.newTab().setText("普通模式"));
        cameraModeTabLayout.addTab(cameraModeTabLayout.newTab().setText("自拍模式"));
        
        if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            cameraModeTabLayout.selectTab(cameraModeTabLayout.getTabAt(0));
        } else {
            cameraModeTabLayout.selectTab(cameraModeTabLayout.getTabAt(1));
        }
        
        cameraModeTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                if (position == 0) {
                    // 普通模式 - 后置
                    if (currentLensFacing != CameraSelector.LENS_FACING_BACK) {
                        switchCamera(CameraSelector.LENS_FACING_BACK);
                    }
                } else {
                    // 自拍模式 - 前置
                    if (currentLensFacing != CameraSelector.LENS_FACING_FRONT) {
                        switchCamera(CameraSelector.LENS_FACING_FRONT);
                    }
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void initGestureDetectors() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (camera == null) return false;
                float currentZoom = camera.getCameraInfo().getZoomState().getValue().getZoomRatio();
                float newZoom = currentZoom * detector.getScaleFactor();
                cameraControl.setZoomRatio(newZoom);
                return true;
            }
        });

        previewView.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                performFocusOnPoint(event.getX(), event.getY());
            }
            return true;
        });
    }

    /**
     * 仅初始化 LocationManager 和 Listener。
     * 真正的定位请求在 startLocationUpdates() 中。
     */
    private void initLocationServices() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                Log.d(TAG, "New location update received.");
                lat = location.getLatitude();
                lon = location.getLongitude();
                alt = location.getAltitude();
                locationTime = location.getTime();
                
                // [Refactored] 数据驱动更新
                // 1. 更新坐标
                updateDynamicItemsByType("camera_coord", getCurrentCoordText());
                
                // 2. 触发异步任务
                findRoadInfoInBackground(lon, lat); // 异步, 自己会更新 "road" 和 "pile"
                fetchAddressOnce(); // 异步, 自己会更新 "address"
            }
        };
    }

    /**
     * 开始请求位置更新 (onResume 和权限授予后调用)。
     */
    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "startLocationUpdates called without permissions.");
            return;
        }

        // 尝试使用最后一次已知位置
        Location lastKnownGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Location lastKnownNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
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
            
            // [Refactored] 数据驱动更新
            updateDynamicItemsByType("camera_coord", getCurrentCoordText());
            findRoadInfoInBackground(lon, lat);
            fetchAddressOnce();

        } else {
            Log.d(TAG, "No recent last known location found. Waiting for new updates.");
        }

        // 发起新的定位请求
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0.0f, locationListener);
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0.0f, locationListener);
        Log.d(TAG, "Location updates started.");
    }

    /**
     * 停止位置更新以节省电量 (onPause调用)。
     */
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

    /**
     * 切换前后摄像头
     */
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
            // [已修正]
            case ImageCapture.FLASH_MODE_ON:
                flashButton.setImageResource(R.drawable.ic_flash_on);
                imageCapture.setFlashMode(ImageCapture.FLASH_MODE_ON);
                break;
            // [已修正]
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

        // 1. 创建 ResolutionSelector (基于 targetAspectRatio)
        AspectRatioStrategy aspectRatioStrategy = new AspectRatioStrategy(
                targetAspectRatio,
                AspectRatioStrategy.FALLBACK_RULE_AUTO
        );
        ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspectRatioStrategy)
                .build();

        // 2. 应用于 Preview
        Preview preview = new Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(currentLensFacing)
                .build();

        try {
            // 检查摄像头是否可用
            if (!cameraProvider.hasCamera(cameraSelector)) {
                String cameraType = currentLensFacing == CameraSelector.LENS_FACING_FRONT ? "前置" : "后置";
                Log.e(TAG, "设备上没有可用的" + cameraType + "摄像头");
                Toast.makeText(this, "未找到" + cameraType + "摄像头", Toast.LENGTH_LONG).show();
                
                // 如果当前摄像头不可用，切换回另一个
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

        // 3. 应用于 ImageCapture
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
        if (imageCapture == null) return;
        setCaptureButtonEnabled(false);

        try {
            File photoFile = createImageFile();
            ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

            imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                    processAndizeImage(photoFile);
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
    // endregion

    // region --- 图像和文件处理 ---
    private void processAndizeImage(File photoFile) {
        try {
            processImageOverlays(photoFile.getAbsolutePath());
            Toast.makeText(CameraActivity.this, "照片已保存", Toast.LENGTH_SHORT).show();
            Uri savedUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
            Intent resultIntent = new Intent();
            resultIntent.setData(savedUri);
            setResult(RESULT_OK, resultIntent);
            finish();
        } catch (Exception e) {
            handlePhotoError("处理照片失败", e);
        }
    }

    private void processImageOverlays(String imagePath) throws IOException {
        Bitmap originalBitmap = BitmapFactory.decodeFile(imagePath);
        if (originalBitmap == null) throw new IOException("无法解码图片文件");

        // 1. 读取原始 EXIF
        ExifInterface oldExif = new ExifInterface(imagePath);
        int orientation = oldExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        
        // 2. 根据 EXIF 旋转 Bitmap
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

        // 3. 绘制水印和二维码
        if (showWatermark && watermarkConfig != null) {
            // [Refactored] 拍照时，我们从数据源(watermarkDataStore)重建一个临时的、
            // 已填充好所有最新数据的 View，然后抓取它的 Bitmap。
            Bitmap watermarkBitmap = createBitmapFromView(watermarkContainer);
            if (watermarkBitmap != null) {
                finalBitmap = addOverlayToBitmap(finalBitmap, watermarkBitmap, watermarkConfig.position, watermarkConfig.scale);
                watermarkBitmap.recycle();
            }
        }

        if (showQrCode && qrCodeConfig != null && isLocationValid()) {
            String qrContent = generateAmapUrl(lat, lon, "拍照位置");
            int baseQrSizeDp = 128;
            int finalQrSizePx = dpToPx((int)(baseQrSizeDp * qrCodeConfig.scale));
            Bitmap qrCodeBitmap = generateQrCodeBitmap(qrContent, finalQrSizePx, finalQrSizePx, qrCodeConfig.padding, qrCodeConfig.radius);
            if (qrCodeBitmap != null) {
                finalBitmap = addOverlayToBitmap(finalBitmap, qrCodeBitmap, qrCodeConfig.position, 1.0f);
                qrCodeBitmap.recycle();
            }
        }

        // 4. 覆盖保存图片 (EXIF 丢失)
        try (FileOutputStream out = new FileOutputStream(imagePath)) {
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        finalBitmap.recycle();

        // 5. 为新文件创建 EXIF 接口
        ExifInterface newExif = new ExifInterface(imagePath);
        
        // 6. 复制旧标签
        copyExifTags(oldExif, newExif); 

        // 7. 修正方向
        newExif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));

        // 8. 确保时间戳
        if (newExif.getAttribute(ExifInterface.TAG_DATETIME) == null) {
            String currentTimestamp = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault()).format(new Date());
            newExif.setAttribute(ExifInterface.TAG_DATETIME, currentTimestamp);
            newExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, currentTimestamp);
            newExif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, currentTimestamp);
        }
        
        // 9. 处理 GPS 信息
        
        // 9a. 检查 LocationManager 缓存
        if (isLocationValid()) {
            Log.d(TAG, "正在写入最新的 (LocationManager) GPS 信息到 EXIF");
            Location location = new Location("");
            location.setLatitude(lat);
            location.setLongitude(lon);
            location.setAltitude(alt);
            newExif.setGpsInfo(location); 
        } else {
            // 9b. GPS 无效, 打印提示
            Log.w(TAG, "最终未写入 GPS 信息。");
            Toast.makeText(this, "GPS信号弱，未写入位置信息", Toast.LENGTH_LONG).show();
        }

        // 10. 保存 EXIF
        newExif.saveAttributes();
    }

    /**
     * 复制常用 EXIF 标签
     */
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
        File picturesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (picturesDir == null) throw new IOException("无法获取外部私有存储目录。");
        File storageDir = new File(picturesDir, "photo_cache");
        if (storageDir.exists()) {
            File[] files = storageDir.listFiles();
            if (files != null) for (File f : files) f.delete();
        } else {
            if (!storageDir.mkdirs()) throw new IOException("无法创建照片缓存目录。");
        }
        String fileName = IdGenerator.getIdByTime() + ".jpg";
        return new File(storageDir, fileName);
    }

    private void handlePhotoError(String message, Exception e) {
        Log.e(TAG, message, e);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        setCaptureButtonEnabled(true);
        setResult(RESULT_CANCELED);
    }
    // endregion

    // region --- [Refactored] 水印数据驱动和UI构建 ---

    /**
     * [Refactored] 更新水印数据存储，并同步刷新UI。
     * 这是 *唯一* 的UI刷新入口。
     * @param itemId 水印项的唯一ID
     * @param newValue 要显示的新值
     */
    private void updateWatermarkItem(String itemId, String newValue) {
        if (itemId == null) return;
        if (newValue == null) newValue = "";

        // 1. 更新单一数据源
        String oldValue = watermarkDataStore.put(itemId, newValue);
        
        // 2. 如果值没有变化，则不刷新UI
        if (newValue.equals(oldValue)) {
            return;
        }

        // 3. 在UI线程上更新对应的 TextView
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final String finalValue = newValue;
            runOnUiThread(() -> updateTextView(itemId, finalValue));
        } else {
            updateTextView(itemId, newValue);
        }
    }

    /**
     * 辅助方法：在UI线程上实际更新TextView的文本
     */
    private void updateTextView(String itemId, String value) {
        WeakReference<TextView> ref = watermarkViewMap.get(itemId);
        if (ref != null) {
            TextView textView = ref.get();
            if (textView != null) {
                // 仅在文本不同时才设置
                if (!textView.getText().toString().equals(value)) {
                    textView.setText(value);
                }
            } else {
                watermarkViewMap.remove(itemId); // 清理过期的弱引用
            }
        }
    }

    /**
     * [Refactored] 辅助方法：根据 *类型* 更新所有匹配的UI项
     * @param typeToUpdate 如 "time", "road", "address"
     * @param newValue 要设置的新值
     */
    private void updateDynamicItemsByType(String typeToUpdate, String newValue) {
        if (watermarkConfig == null) return;

        for (Section s : new Section[]{watermarkConfig.header, watermarkConfig.body, watermarkConfig.foot}) {
            if (s == null) continue;
            
            // 检查 Title
            if (s.title != null && typeToUpdate.equals(s.title.type)) {
                updateWatermarkItem(s.title.id, newValue);
            }
            // 检查 Content
            if (s.content != null) {
                for (WatermarkItem item : s.content.values()) {
                    if (typeToUpdate.equals(item.type)) {
                        updateWatermarkItem(item.id, newValue);
                    }
                }
            }
        }
    }

    /**
     * [Refactored] 辅助方法：当GPS失效时，重置所有依赖GPS的UI项
     */
    private void resetGpsDependentData() {
        String coordText = "定位中...";
        String roadText = "暂无道路信息";
        String pileText = "暂无桩号";
        String addressText = "获取地址中...";
        
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

    /**
     * 辅助方法：检查 item 类型并调用更新
     */
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
        }
    }

    /**
     * [Refactored] 步骤1：填充 watermarkDataStore 的初始值
     */
    private void initializeDataStore() {
        watermarkDataStore.clear();
        if (watermarkConfig == null) return;

        List<Section> sections = new ArrayList<>();
        if (watermarkConfig.header != null) sections.add(watermarkConfig.header);
        if (watermarkConfig.body != null) sections.add(watermarkConfig.body);
        if (watermarkConfig.foot != null) sections.add(watermarkConfig.foot);

        for (Section section : sections) {
            if (!section.display) continue;
            
            // 1. 添加 Title (如果存在)
            if (section.title != null) {
                watermarkDataStore.put(section.title.id, getContentValue(section.title));
            }
            // 2. 添加 Content (如果存在)
            if (section.content != null) {
                for (WatermarkItem item : section.content.values()) {
                    // 这将预填充 'input' 类型的默认值, 'time' 的当前时间等。
                    watermarkDataStore.put(item.id, getContentValue(item));
                }
            }
        }
    }

    /**
     * [Refactored] 步骤2：构建/重建整个水印视图。
     * 仅在 onCreate 时调用一次。
     */
    private void setupWatermarkPreview() {
        if (!showWatermark || watermarkConfig == null) return;
        
        // 1. [New] 填充数据源
        initializeDataStore();

        // 2. [New] 清理旧的UI和视图映射
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

        // 3. 构建UI
        for (int i = 0; i < availableSections.size(); i++) {
            Section section = availableSections.get(i);
            boolean isFirst = (i == 0);
            boolean isLast = (i == availableSections.size() - 1);

            int[] padding;
            if (section == watermarkConfig.header) {
                padding = new int[]{dp(0), dp(0), dp(0), dp(0)};
            } else if (section == watermarkConfig.body) {
                padding = new int[]{dp(10), dp(4), dp(10), dp(4)};
            } else {
                padding = new int[]{dp(10), dp(4), dp(10), dp(4)};
            }
            
            // createSectionLayout 会自动调用 createHeaderView 和 createContentItemView
            // 这些子函数会负责从 watermarkDataStore 读取值, 并填充 watermarkViewMap
            ClippingFrameLayout sectionLayout = createSectionLayout(section, padding);
            
            if (sectionLayout != null) {
                float rTL = dpToPx((int) watermarkConfig.radius[0]);
                float rTR = dpToPx((int) watermarkConfig.radius[1]);
                float rBR = dpToPx((int) watermarkConfig.radius[2]);
                float rBL = dpToPx((int) watermarkConfig.radius[3]);

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

    /**
     * [Refactored] 步骤3：创建 Section 布局
     * (此函数在 setupWatermarkPreview 内部调用)
     */
    private ClippingFrameLayout createSectionLayout(Section section, int[] padding) {
        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(padding[0], padding[1], padding[2], padding[3]);

        boolean hasContent = false;
        if (section.icon != null || section.title != null) {
            // createHeaderView 会从 DataStore 读值, 并填充 ViewMap
            View headerView = createHeaderView(section.icon, section.title); 
            if (headerView != null) {
                contentLayout.addView(headerView);
                hasContent = true;
            }
        }
        for (WatermarkItem item : getSortedContentItems(section.content)) {
            // createContentItemView 会从 DataStore 读值, 并填充 ViewMap
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

    /**
     * [Refactored] 步骤4a：创建 Header 视图
     * (此函数在 createSectionLayout 内部调用)
     */
    private View createHeaderView(WatermarkItem icon, WatermarkItem title) {
        // 1. 根容器 (Flex-box)
        LinearLayout rootHeaderLayout = new LinearLayout(this);
        rootHeaderLayout.setOrientation(LinearLayout.HORIZONTAL);
        rootHeaderLayout.setGravity(Gravity.CENTER_VERTICAL);

        boolean hasTitle = (title != null && !title.value.isEmpty());
        boolean hasIcon = (icon != null && icon.display);

        // 2. 根据 icon width/height 调整布局
        if (hasIcon) {
            int iconWidth = icon.width;
            int iconHeight = icon.height;

            if (iconWidth == 0 && iconHeight == 0) {
                hasIcon = false;
            } else if (iconWidth == 0 && iconHeight != 0) {
                rootHeaderLayout.setMinimumHeight(dp(iconHeight));
                hasIcon = false;
            } else if (iconWidth != 0 && iconHeight == 0) {
                hasIcon = false;
            }
        }

        if (!hasTitle && !hasIcon) {
            return null; // 图标被禁用且无标题
        }

        // 3. 准备视图 (Icon 和 Title)
        View iconView = null;
        if (hasIcon) {
            iconView = createIconView(icon);
            if (iconView == null) hasIcon = false;
        }

        View titleContainer = null;
        if (hasTitle) {
            RelativeLayout innerTitleContainer = new RelativeLayout(this);
            int[] padding = new int[]{dp(8), dp(8), dp(8), dp(8)};
            innerTitleContainer.setPadding(padding[0], padding[1], padding[2], padding[3]);
            
            // [Refactored] 从 DataStore 获取初始文本, 并注册到 ViewMap
            String titleText = watermarkDataStore.get(title.id);
            if (titleText == null) titleText = title.value; // Fallback
            
            TextView titleView = createTextView(titleText, title.color, title.size, Typeface.BOLD);
            watermarkViewMap.put(title.id, new WeakReference<>(titleView)); // [New] 注册
            
            RelativeLayout.LayoutParams titleParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            );
            titleParams.addRule(RelativeLayout.CENTER_IN_PARENT);
            innerTitleContainer.addView(titleView, titleParams);
            titleContainer = innerTitleContainer;
        }
        
        // 4. 组装布局 (Icon + Title)
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

    /**
     * [Refactored] 步骤4b：创建 Content Item 视图
     * (此函数在 createSectionLayout 内部调用)
     */
    private View createContentItemView(WatermarkItem item) {
        // 1. [Refactored] 从 DataStore 获取初始文本
        String value = watermarkDataStore.get(item.id);
        if (value == null) {
            value = ""; // Fallback
        }

        // 2. 创建主布局
        LinearLayout itemLayout = new LinearLayout(this);
        itemLayout.setOrientation(LinearLayout.HORIZONTAL);
        itemLayout.setPadding(0, dpToPx(1), 0, dpToPx(1));
        
        // 3. 添加名称标签 (如果需要)
        if (item.nameDisplay && !item.name.isEmpty()) {
            itemLayout.addView(createTextView(item.name + ": ", item.color, item.size, Typeface.BOLD));
        }

        // 4. 创建 "值" TextView
        TextView valueView = createTextView(value, item.color, item.size, Typeface.NORMAL);

        // 5. [Refactored] 将 "值" TextView 注册到 ViewMap
        watermarkViewMap.put(item.id, new WeakReference<>(valueView));

        // 6. 添加 "值" 视图
        itemLayout.addView(valueView);

        return itemLayout;
    }

    private ImageView createIconView(WatermarkItem icon) {
        try {
            Bitmap bitmap = loadBitmap(icon.value);
            if (bitmap == null) return null;
            ImageView imageView = new ImageView(this);
            int width = dp(icon.width);
            int height = dp(icon.height);
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
        textView.setTextSize(size * watermarkConfig.scale);
        textView.setTypeface(Typeface.DEFAULT, typefaceStyle);
        textView.setMaxWidth(dpToPx((int)(MAX_WATERMARK_LINE_WIDTH_DP * watermarkConfig.scale)));
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
        
        // [Refactored] 从 DataStore 读取当前值
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
            
            // 2. [Refactored] 触发单一数据驱动更新
            updateWatermarkItem(item.id, newValue);
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    /**
     * 处理水印容器点击事件 (用于 "input" 字段)
     */
    private void handleWatermarkClick() {
        // 1. 收集所有 "input" 字段
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
            if ("input".equals(item.type)) {
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

            int margin = dpToPx(10);
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

    private Bitmap generateQrCodeBitmap(String content, int width, int height, float[] padding, float[] radius) {
        if (content.isEmpty()) return null;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix bitMatrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, height, hints);
            Bitmap qrBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < width; x++) for (int y = 0; y < height; y++)
                qrBitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.TRANSPARENT);
            int pL = dpToPx((int) padding[0]), pT = dpToPx((int) padding[1]);
            int pR = dpToPx((int) padding[2]), pB = dpToPx((int) padding[3]);
            int finalW = width + pL + pR, finalH = height + pT + pB;
            Bitmap finalBitmap = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(finalBitmap);
            Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(Color.WHITE);
            Path path = new Path();
            float[] radii = {dpToPx((int)radius[0]), dpToPx((int)radius[0]), dpToPx((int)radius[1]), dpToPx((int)radius[1]), dpToPx((int)radius[2]), dpToPx((int)radius[2]), dpToPx((int)radius[3]), dpToPx((int)radius[3])};
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
            
            // showWatermark 依赖于 section 是否存在，以及它们是否被设置为 display:true
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
        
        // 'display' 默认为 true
        if (sectionObj.has("display") && Boolean.FALSE.equals(sectionObj.opt("display"))) {
             section.display = false;
        }
        
        parseIntArray(sectionObj, "background", section.background);
        
        // [Refactored] 解析时分配 ID
        if (sectionObj.has("icon")) {
            section.icon = parseWatermarkItem(sectionObj.getJSONObject("icon"));
            section.icon.id = "_" + sectionName + "_icon"; // e.g., "_header_icon"
        }
        if (sectionObj.has("title")) {
            section.title = parseWatermarkItem(sectionObj.getJSONObject("title"));
            section.title.id = "_" + sectionName + "_title"; // e.g., "_header_title"
        }
        if (sectionObj.has("content")) {
            JSONObject contentObj = sectionObj.getJSONObject("content");
            Iterator<String> keys = contentObj.keys();
            while (keys.hasNext()) {
                String key = keys.next(); // JSON key (e.g., "item1") is the ID
                WatermarkItem item = parseWatermarkItem(contentObj.getJSONObject(key));
                item.id = key; // [New] 存储唯一ID
                section.content.put(key, item);
            }
        }
        return section;
    }

    private WatermarkItem parseWatermarkItem(JSONObject obj) {
        WatermarkItem item = new WatermarkItem();
        
        // 'display' 默认为 true (主要用于 icon)
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
                // 权限授予后，启动所有服务
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
        // 条件判断保持不变: 必须有地址项、尚未获取成功、GPS有效、且没有正在获取中
        if (hasAddressItem &&isActivityResumed&& isLocationValid() && !isFetchingAddress) {

            // 1. 设置状态标志，防止并发请求
            isFetchingAddress = true; 
            

            final double currentLon = lon;
            final double currentLat = lat;
            
            // 3. [重构] 调用新的异步方法，传入回调
            GaodeApi.getAddressFromGpsAsync(CameraActivity.this, currentLon, currentLat, new GaodeApi.AddressCallback() {
                
                /**
                 * 这个回调是在 GaodeApi 的后台线程中执行的！
                 * @param addressInfo 结果 (地址字符串或错误代码)
                 * @param success true 如果是有效地址
                 */
                @Override
                public void onAddressUpdated(String addressInfo, boolean success) {
                    
                    // 4. 更新状态变量 (这些是线程安全的)
                    isFetchingAddress = false;
                    currentAddress = addressInfo; // 无论成功与否，都用新结果更新
                    
                    // 5. [修复] 将UI更新操作“推送”回主线程(UI线程)
                    runOnUiThread(() -> {
                        updateDynamicItemsByType("address", currentAddress);
                    });
                }
            });
        }
    }

    private void checkForWeatherItem() {
        if (watermarkConfig == null) return;

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
        if (hasWeatherItem && weatherAdcode != null) {
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

    private int dp(int dp) {
        if (watermarkConfig == null) return dpToPx(dp);
        return Math.round(dp * watermarkConfig.scale * getResources().getDisplayMetrics().density);
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
        // "input" 和 "string" 类型的初始值就是它们在配置中的 "value"
        if ("input".equals(item.type) || "string".equals(item.type)) {
            return item.value;
        }
        
        // 动态类型，调用 TypeHandler 获取当前的初始状态
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
                RoadDatabaseHelper.RoadLocationMatch match = roadDatabaseHelper.findNearestRoadLocation(lng, lat);
                
                currentRoadMatch = match; // 存储新数据 (null 或 匹配结果)

                // [Refactored] 数据驱动更新
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


    /**
     * [Refactored] 简化回 createBitmapFromView
     * 因为 `updateWatermarkItem` 保证了 `watermarkContainer` 始终显示最新数据。
     */
    private Bitmap createBitmapFromView(View view) {
        if (view == null || view.getWidth() == 0 || view.getHeight() == 0 || watermarkConfig == null) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        
        // 应用全局圆角剪裁
        Path clipPath = new Path();
        float[] globalRadii = watermarkConfig.radius;
        float rTL = dpToPx((int) globalRadii[0]);
        float rTR = dpToPx((int) globalRadii[1]);
        float rBR = dpToPx((int) globalRadii[2]);
        float rBL = dpToPx((int) globalRadii[3]);
        float[] radii = {rTL, rTL, rTR, rTR, rBR, rBR, rBL, rBL};
        clipPath.addRoundRect(new RectF(0, 0, view.getWidth(), view.getHeight()), radii, Path.Direction.CW);
        canvas.clipPath(clipPath);
        
        // 关键: view.draw(canvas) 会绘制 view 的当前状态 (包括用户输入的 'input' 值)
        view.draw(canvas);
        return bitmap;
    }
    
    // [Helper for createBitmapFromView - 已删除，逻辑合并]
    // private void applyClippingAndDraw(Canvas canvas, View view) { ... }


    private Bitmap addOverlayToBitmap(Bitmap photo, Bitmap overlay, Position position, float scale) {
        Bitmap result = photo.copy(Bitmap.Config.ARGB_8888, true);
        if (result != photo) {
            photo.recycle();
        }
        Canvas canvas = new Canvas(result);
        float resolutionScale = calculateOverlayScale(result.getWidth(), result.getHeight());
        Bitmap scaledOverlay = overlay;
        if (Math.abs(resolutionScale - 1.0f) > 0.01) {
            scaledOverlay = Bitmap.createScaledBitmap(overlay,
                    (int) (overlay.getWidth() * resolutionScale),
                    (int) (overlay.getHeight() * resolutionScale), true);
        }
        float x = calculateOverlayX(result.getWidth(), scaledOverlay.getWidth(), position, resolutionScale);
        float y = calculateOverlayY(result.getHeight(), scaledOverlay.getHeight(), position, resolutionScale);
        canvas.drawBitmap(scaledOverlay, x, y, null);
        if (scaledOverlay != overlay) scaledOverlay.recycle();
        return result;
    }

    /**
     * 计算水印/二维码叠加层相对于照片的缩放比例
     */
    private float calculateOverlayScale(float photoWidth, float photoHeight) {
        int previewWidth = previewView.getWidth();
        int previewHeight = previewView.getHeight();

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

    private float calculateOverlayX(float photoWidth, float overlayWidth, Position pos, float scale) {
        float margin = dpToPx(10);
        if (pos.left != null) return pos.left * scale + margin;
        if (pos.right != null) return photoWidth - overlayWidth - (pos.right * scale) - margin;
        return margin;
    }

    private float calculateOverlayY(float photoHeight, float overlayHeight, Position pos, float scale) {
        float margin = dpToPx(10);
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

    private static class CameraConfig {
        String proportion = "16:9";
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
        boolean display = true; // 默认为 true
        int[] background = {0, 0, 0, 0};
        WatermarkItem icon, title;
        Map<String, WatermarkItem> content = new HashMap<>();
        int[] internalPadding = {0, 0, 0, 0};
    }

    private static class WatermarkItem {
        String id; // [New] 唯一标识符
        boolean display = true; // 默认为 true (用于 icon)
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
