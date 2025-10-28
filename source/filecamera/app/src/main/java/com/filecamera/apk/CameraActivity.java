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

    // 外部API数据相关成员变量
    private boolean hasWeatherItem = false;
    private String weatherAdcode = null;
    private boolean hasAddressItem = false;
    private volatile String currentAddress = "获取地址中...";
    private volatile boolean isFetchingAddress = false;
    private volatile boolean hasAddressBeenFetched = false;

    private static final long MATCH_INTERVAL_MS = 5000;
    private volatile boolean isMatchingRoad = false;
    private long lastMatchRequestTime = 0;

    private RoadDatabaseHelper roadDatabaseHelper;
    private volatile RoadDatabaseHelper.RoadLocationMatch currentRoadMatch = null;
	private int currentFlashMode = ImageCapture.FLASH_MODE_OFF; // 闪光灯状态

    // UI更新循环
    private final Handler uiUpdateHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (hasWeatherItem) {
                GaodeApi.requestWeatherUpdateIfStale(CameraActivity.this, weatherAdcode);
            }
            updateWatermarkPreview();
            uiUpdateHandler.postDelayed(this, 5000); // 5秒检查一次
        }
    };

    // 配置与状态
    private CameraConfig cameraConfig;
    private WatermarkConfig watermarkConfig;
    private QrCodeConfig qrCodeConfig;
    private boolean showWatermark = false;
    private boolean showQrCode = false;
    private int targetAspectRatio = AspectRatio.RATIO_16_9; // 此变量保留，用于配置ResolutionSelector

    // 类型处理器
    private final Map<String, TypeHandler> typeHandlers = new HashMap<String, TypeHandler>() {{
        put("string", item -> item.value);
        put("road", item -> getCurrentRoadText());
        put("camera_coord", item -> getCurrentCoordText());
        put("camera_pile", item -> getCurrentPileText());
        put("time", item -> getCurrentTimeText());
		put("weather", item -> getCurrentWeatherText());
        put("address", item -> getCurrentAddressText());
        put("input", item -> item.value); // input 类型处理器, 返回当前内存中的值
    }};
    // endregion

    // region --- Activity 生命周期 ---
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true /* enabled by default */) {
			@Override
			public void handleOnBackPressed() {
				setResult(RESULT_CANCELED);
				// 调用 finish() 来关闭当前 Activity
				finish();
			}
		});
        String configJson = getIntent().getStringExtra("watermark_config");
        if (configJson == null) configJson = "{}";

        roadDatabaseHelper = new RoadDatabaseHelper(this);

        initViews();
        initGestureDetectors();
        initLocationServices(); // 此处只做初始化，不请求更新
        initOrientationListener();
        parseConfig(configJson);
        checkForWeatherItem();
        checkForAddressItem();

        GaodeApi.resetApiCallFailedFlag();
        loadInitialCaches();
        if (showWatermark) {
            watermarkContainer.setVisibility(View.VISIBLE);
            setupWatermarkPreview();
        }

		if (!allPermissionsGranted()) {
			ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS);
		}
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 只有在权限被授予后，才在 onStart 中启动相机和定位
        if (allPermissionsGranted()) {
            startCamera();
            startLocationUpdates(); // 在Activity可见时开始位置更新
            uiUpdateHandler.post(uiUpdateRunnable); // 在此启动UI更新循环
        }
        if (orientationEventListener != null && orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Activity不可见时，停止所有更新以节省资源
        stopLocationUpdates(); // 停止位置更新
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable); // 停止UI更新循环

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        // 当Activity不可见时，重置地址获取标志位
        // 这样用户从息屏或其他应用返回时，就可以重新获取一次地址
        hasAddressBeenFetched = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // onResume中不再需要处理定位和相机，这些已移至onStart
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清理逻辑已移至 onStop，onDestroy 只做最后的回收
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
		watermarkContainer = findViewById(R.id.watermarkContainer);
		exitButton = findViewById(R.id.exitButton);
		captureButton.setOnClickListener(v -> takePhoto());
		flashButton.setOnClickListener(v -> cycleFlashMode());
		exitButton.setOnClickListener(v -> {
			setResult(RESULT_CANCELED);
			finish();
		});

        // [核心修改] 为整个水印容器设置一个点击监听器
        // 它将处理所有 'input' 类型的字段
        watermarkContainer.setOnClickListener(v -> handleWatermarkClick());
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
     * 该方法现在只负责初始化LocationManager和LocationListener实例。
     * 真正的定位请求被移到了 startLocationUpdates() 方法中。
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
                findRoadInfoInBackground(lon, lat);
                fetchAddressOnce();
                if (showWatermark) updateWatermarkPreview();
            }
        };
    }

    /**
     * 开始请求位置更新。
     * 这个方法应该在 onStart() 和权限被授予后调用。
     */
    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "startLocationUpdates called without permissions.");
            return;
        }

        // 尝试使用最后一次已知位置进行快速初始化
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
            findRoadInfoInBackground(lon, lat);
            fetchAddressOnce();
            if (showWatermark) updateWatermarkPreview();
        } else {
            Log.d(TAG, "No recent last known location found. Waiting for new updates.");
        }

        // 发起新的定位请求
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0.0f, locationListener);
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0.0f, locationListener);
        Log.d(TAG, "Location updates started.");
    }

    /**
     * 停止位置更新以节省电量。
     * 这个方法应该在 onStop() 中调用。
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

                int rotation; // 这是新计算出的目标旋转

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
				camera.getCameraControl().enableTorch(true);
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

        // 1. 根据 targetAspectRatio (例如 AspectRatio.RATIO_16_9) 创建 ResolutionSelector
        // AspectRatioStrategy 会确保选择的分辨率符合此宽高比
        AspectRatioStrategy aspectRatioStrategy = new AspectRatioStrategy(
                targetAspectRatio,
                AspectRatioStrategy.FALLBACK_RULE_AUTO // 自动选择最佳分辨率
        );

        ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspectRatioStrategy)
                .build();

        // 2. 将 ResolutionSelector 应用于 Preview
		Preview preview = new Preview.Builder()
                .setResolutionSelector(resolutionSelector) // (新 API)
				.build();

		CameraSelector cameraSelector = new CameraSelector.Builder()
				.requireLensFacing(CameraSelector.LENS_FACING_BACK)
				.build();

		try {
			if (!cameraProvider.hasCamera(cameraSelector)) {
				Log.e(TAG, "设备上没有可用的后置摄像头");
				Toast.makeText(this, "未找到后置摄像头", Toast.LENGTH_LONG).show();
				return;
			}
		} catch (androidx.camera.core.CameraInfoUnavailableException e) {
			Log.e(TAG, "无法检查相机可用性", e);
			Toast.makeText(this, "无法获取相机信息", Toast.LENGTH_SHORT).show();
			return;
		}

        // 3. 将相同的 ResolutionSelector 应用于 ImageCapture
		imageCapture = new ImageCapture.Builder()
                .setResolutionSelector(resolutionSelector) // (新 API)
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
            writeLocationToExif(photoFile.getAbsolutePath());
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

		ExifInterface exif = new ExifInterface(imagePath);
		int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
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

		if (showWatermark && watermarkConfig != null) {
            // [核心] createBitmapFromView 会绘制水印容器的 *当前* 视觉状态。
            // 此时 input 字段的 TextView 已经（通过 updateWatermarkPreview）被设置为
            // 用户输入的新值。
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

		try (FileOutputStream out = new FileOutputStream(imagePath)) {
			finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
		}
		finalBitmap.recycle();

		ExifInterface newExif = new ExifInterface(imagePath);
		newExif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
		newExif.saveAttributes();
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

    private void writeLocationToExif(String photoPath) {
        if (!isLocationValid()) {
            Log.w(TAG, "位置信息无效或超时，跳过写入EXIF");
            Toast.makeText(this, "GPS信号弱，未写入位置信息", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            ExifInterface exif = new ExifInterface(photoPath);
            Location location = new Location("");
            location.setLatitude(lat);
            location.setLongitude(lon);
            location.setAltitude(alt);
            exif.setGpsInfo(location);
            exif.saveAttributes();
            Log.d(TAG, "GPS信息已成功写入EXIF");
        } catch (IOException e) {
            Log.e(TAG, "写入EXIF信息失败", e);
        }
    }

    private void handlePhotoError(String message, Exception e) {
        Log.e(TAG, message, e);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        setCaptureButtonEnabled(true);
        setResult(RESULT_CANCELED);
    }

    private void setupWatermarkPreview() {
        if (!showWatermark || watermarkConfig == null) return;
        watermarkContainer.removeAllViews();
        watermarkContainer.setOrientation(LinearLayout.VERTICAL);

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
                padding = new int[]{dp(0), dp(0), dp(0), dp(0)};
            } else if (section == watermarkConfig.body) {
                padding = new int[]{dp(10), dp(4), dp(10), dp(4)};
            } else {
                padding = new int[]{dp(10), dp(4), dp(10), dp(4)};
            }
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

    private ClippingFrameLayout createSectionLayout(Section section, int[] padding) {
        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(padding[0], padding[1], padding[2], padding[3]);

        boolean hasContent = false;
        if (section.icon != null || section.title != null) {
            // createHeaderView 现在会创建您描述的 flex 布局
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
        // 根容器, 对应 <div style="display:flex;">
        LinearLayout rootHeaderLayout = new LinearLayout(this);
        rootHeaderLayout.setOrientation(LinearLayout.HORIZONTAL);
        rootHeaderLayout.setGravity(Gravity.CENTER_VERTICAL); // 确保图标和标题容器垂直居中对齐

        boolean hasTitle = (title != null && !title.value.isEmpty());
        boolean hasIcon = (icon != null && icon.display);

        // [新优化] 根据 icon.width 和 icon.height 调整 hasIcon 状态和布局
        if (hasIcon) {
            int iconWidth = icon.width;
            int iconHeight = icon.height;

            if (iconWidth == 0 && iconHeight == 0) {
                // 1. 宽和高都为0, 则不启用图标
                hasIcon = false;
            } else if (iconWidth == 0 && iconHeight != 0) {
                // 2. 宽为0, 高不为0, 设置页眉高度, 且不显示图标
                rootHeaderLayout.setMinimumHeight(dp(iconHeight));
                hasIcon = false; // 不创建 iconView
            } else if (iconWidth != 0 && iconHeight == 0) {
                // 3. 宽不为0, 高为0 (逻辑上等同于不显示, 避免 createScaledBitmap 崩溃)
                hasIcon = false;
            }
            // 4. (隐式) 宽和高都不为0, hasIcon 保持 true, 正常创建
        }


        if (!hasTitle && !hasIcon) {
            return null; // 如果图标被禁用且没有标题, 则返回 null
        }

        // --- 准备视图 ---
        View iconView = null;
        if (hasIcon) { // hasIcon 在此已确保 width 和 height 均不为 0
            iconView = createIconView(icon);
            if (iconView == null) hasIcon = false; // 创建失败 (例如 loadBitmap 失败)
        }

        View titleContainer = null;
        if (hasTitle) {
            RelativeLayout innerTitleContainer = new RelativeLayout(this);
			int[] padding = new int[]{dp(8), dp(8), dp(8), dp(8)};
            innerTitleContainer.setPadding(padding[0], padding[1], padding[2], padding[3]);

            TextView titleView = createTextView(title.value, title.color, title.size, Typeface.BOLD);
            RelativeLayout.LayoutParams titleParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            );
            titleParams.addRule(RelativeLayout.CENTER_IN_PARENT);
            innerTitleContainer.addView(titleView, titleParams);
            titleContainer = innerTitleContainer;
        }
        
        // --- 组装布局 ---
        String iconPos = (hasIcon && icon.position != null) ? icon.position : "left";

        if (hasIcon && hasTitle) {
            if ("right".equals(iconPos)) {
                // 标题容器在左, 占据剩余空间
                LinearLayout.LayoutParams titleContainerParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                rootHeaderLayout.addView(titleContainer, titleContainerParams);
                // 图标在右
                rootHeaderLayout.addView(iconView);
            } else { // 默认 left
                // 图标在左
                rootHeaderLayout.addView(iconView);
                // 标题容器在右, 占据剩余空间
                LinearLayout.LayoutParams titleContainerParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                rootHeaderLayout.addView(titleContainer, titleContainerParams);
            }
        } else if (hasTitle) {
            // 只有标题, 容器占满全部空间
            LinearLayout.LayoutParams titleContainerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rootHeaderLayout.addView(titleContainer, titleContainerParams);
        } else if (hasIcon) {
            // 只有图标
            if ("right".equals(iconPos)) {
                // 用一个空的、带权重的View把图标推到右边
                View spacer = new View(this);
                LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, 0, 1.0f);
                rootHeaderLayout.addView(spacer, spacerParams);
            }
            // 默认就是 left, 所以直接添加即可
            rootHeaderLayout.addView(iconView);
        }

        return rootHeaderLayout;
    }
    private View createContentItemView(WatermarkItem item) {
        // 1. 获取要显示的值
        String value = getContentValue(item);
        if (value == null) {
            value = ""; // 确保安全
        }

        // 2. 创建主布局 (所有类型共用)
        LinearLayout itemLayout = new LinearLayout(this);
        itemLayout.setOrientation(LinearLayout.HORIZONTAL);
        itemLayout.setPadding(0, dpToPx(1), 0, dpToPx(1));
        
        // [已移除] itemLayout.setGravity(Gravity.CENTER_VERTICAL); 
        // 移除这一行后, 布局将默认顶部对齐, 解决多行文本的问题。

        // 3. 添加名称标签 (如果需要)
        if (item.nameDisplay && !item.name.isEmpty()) {
            itemLayout.addView(createTextView(item.name + ": ", item.color, item.size, Typeface.BOLD));
        }

        // 4. 创建 "值" TextView
        TextView valueView = createTextView(value, item.color, item.size, Typeface.NORMAL);

        // 5. (无特殊处理)

        // 6. 将 "值" TextView 添加到主布局
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
        return textView;
    }

    private void updateWatermarkPreview() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            setupWatermarkPreview();
        } else {
            runOnUiThread(this::setupWatermarkPreview);
        }
    }

    /**
     * [修改] 显示用于 "input" 类型的编辑对话框
     * @param item 正在编辑的 WatermarkItem 对象 (用于读写 value)
     */
    private void showInputDialog(WatermarkItem item) {
        // 检查 Activity 是否即将销毁，防止 crash
        if (isFinishing() || isDestroyed()) {
            return;
        }

        // 1. 创建 AlertDialog 构建器
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(item.name);

        // 2. 创建 EditText 输入框
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setText(item.value); // 设置当前值 (来自JSON的默认值或上次输入的值)
        input.setSelection(item.value.length()); // 将光标移动到文本末尾

        // 3. 为输入框创建一个带边距的容器，使对话框看起来更舒服
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

        // 4. 设置 "确定" 和 "取消" 按钮
        builder.setPositiveButton("确定", (dialog, which) -> {
            String newValue = input.getText().toString();
            
            // [关键步骤 1] 更新 WatermarkItem 对象在内存中的值
            item.value = newValue; 
            
            // [关键步骤 2] 触发水印UI刷新以显示新值
            updateWatermarkPreview();
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());

        // 5. 显示对话框
        builder.show();
    }

    /**
     * [新方法] 处理水印容器的点击事件
     */
    private void handleWatermarkClick() {
        // 1. 收集所有可用的 "input" 字段
        List<WatermarkItem> inputItems = collectInputItems();

        // 2. 如果没有 input 字段，则不执行任何操作
        if (inputItems.isEmpty()) {
            return;
        }

        // 3. [优化] 如果只有一个 input 字段，直接显示该字段的输入对话框
        if (inputItems.size() == 1) {
            showInputDialog(inputItems.get(0));
            return;
        }

        // 4. [核心逻辑] 如果有多个 input 字段，显示一个选择列表
        String[] itemNames = new String[inputItems.size()];
        for (int i = 0; i < inputItems.size(); i++) {
            itemNames[i] = inputItems.get(i).name; // 使用字段的 'name' 作为列表项
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择要输入的字段");
        builder.setItems(itemNames, (dialog, which) -> {
            // 5. 用户从列表中选择一项后，获取对应的 WatermarkItem
            WatermarkItem selectedItem = inputItems.get(which);
            // 6. 显示该项的输入对话框
            showInputDialog(selectedItem);
        });
        
        // "取消" 按钮，关闭列表，即“重置选择结果”
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    /**
     * [新方法] 从配置中收集所有 "input" 类型的 WatermarkItem
     * @return 一个包含所有 'input' 字段的列表
     */
    private List<WatermarkItem> collectInputItems() {
        List<WatermarkItem> items = new ArrayList<>();
        if (watermarkConfig == null) {
            return items;
        }

        // 遍历所有 section (header, body, foot)
        addInputItemsFromSection(items, watermarkConfig.header);
        addInputItemsFromSection(items, watermarkConfig.body);
        addInputItemsFromSection(items, watermarkConfig.foot);

        // 按照 'index' 排序，确保列表顺序与UI显示顺序一致
        Collections.sort(items, Comparator.comparingInt(o -> o.index));
        return items;
    }

    /**
     * [新方法] 辅助方法，用于从单个 Section 中提取 "input" 字段
     * @param list 要添加到的目标列表
     * @param section 要搜索的 Section
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
        
        // [修改 3/6] 解析 'display' 属性
        // section.display 默认为 true。只有当 'display' 显式为 false 时才覆盖为 false。
        // null, "true", true, 或不存在 均视为 true。
        if (sectionObj.has("display") && Boolean.FALSE.equals(sectionObj.opt("display"))) {
             section.display = false;
        }
        
        parseIntArray(sectionObj, "background", section.background);
        if (sectionObj.has("icon")) section.icon = parseWatermarkItem(sectionObj.getJSONObject("icon"));
        if (sectionObj.has("title")) section.title = parseWatermarkItem(sectionObj.getJSONObject("title"));
        if (sectionObj.has("content")) {
            JSONObject contentObj = sectionObj.getJSONObject("content");
            Iterator<String> keys = contentObj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                section.content.put(key, parseWatermarkItem(contentObj.getJSONObject(key)));
            }
        }
        return section;
    }

    private WatermarkItem parseWatermarkItem(JSONObject obj) {
        WatermarkItem item = new WatermarkItem();
        
        // [修改 4/6] 解析 'display' 属性 (主要用于 icon)
        // item.display 默认为 true。
        if (obj.has("display") && Boolean.FALSE.equals(obj.opt("display"))) {
            item.display = false;
        }
        
        item.name = obj.optString("name", "");
        item.type = obj.optString("type", "string");
        item.value = obj.optString("value", ""); // 对于 "input" 类型, 这是默认值
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
                // 权限授予后，需要启动所有服务，就像在 onStart 中一样
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
        if (hasAddressItem && !hasAddressBeenFetched && isLocationValid() && !isFetchingAddress) {
            hasAddressBeenFetched = true;
            isFetchingAddress = true;

            currentAddress = "获取地址中...";
            updateWatermarkPreview(); // 立即显示"获取中..."

            final double currentLon = lon;
            final double currentLat = lat;
            new Thread(() -> {
                try {
                    String address = GaodeApi.getAddressFromGps(CameraActivity.this, currentLon, currentLat);
                    if (address.equals(GaodeApi.ERROR_NO_KEY) ||address.equals(GaodeApi.ERROR_INVALID_KEY) ||address.equals(GaodeApi.ERROR_FETCH_FAILED)){
                        currentAddress = address;
                    }else if (address == null || address.isEmpty()){
                        currentAddress = "获取地址失败"; // 通用失败
                    }else {
                        currentAddress = address; // 成功获取
                    }
                } finally {
                    isFetchingAddress = false;
                    updateWatermarkPreview(); 
                }
            }).start();
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

	private String getContentValue(WatermarkItem item) {
		TypeHandler handler = typeHandlers.get(item.type);
        // 对于 "input" 类型, handler 会返回 item.value (无论是默认值还是用户输入的新值)
		return handler != null ? handler.getValue(item) : item.value;
	}

    private String getCurrentAddressText() {
        if (!isLocationValid()) {
            return "定位中...";
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
				if (match != null) {
                    currentRoadMatch = match;
                } else {
                    currentRoadMatch = null;
                }
				updateWatermarkPreview();
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
		float rTL = dpToPx((int) globalRadii[0]);
		float rTR = dpToPx((int) globalRadii[1]);
		float rBR = dpToPx((int) globalRadii[2]);
		float rBL = dpToPx((int) globalRadii[3]);
		float[] radii = {rTL, rTL, rTR, rTR, rBR, rBR, rBL, rBL};
		clipPath.addRoundRect(new RectF(0, 0, view.getWidth(), view.getHeight()), radii, Path.Direction.CW);
		canvas.clipPath(clipPath);
        // 关键: view.draw(canvas) 会绘制 view 的当前状态,
        // 包括已经被用户输入更新过的 TextView (input 类型)
		view.draw(canvas);
		return bitmap;
	}

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
        boolean display = true; // [修改 1/6] 默认为 true
        int[] background = {0, 0, 0, 0};
        WatermarkItem icon, title;
        Map<String, WatermarkItem> content = new HashMap<>();
		int[] internalPadding = {0, 0, 0, 0};
    }

    private static class WatermarkItem {
        boolean display = true; // [修改 2/6] 默认为 true (用于 icon)
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