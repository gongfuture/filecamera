package com.filecamera.apk;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.exifinterface.media.ExifInterface;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import android.webkit.MimeTypeMap; // 确保在文件顶部导入了这个类
import java.util.HashSet;       // 确保导入

import android.provider.Settings;
import androidx.appcompat.app.AlertDialog;
public class MainActivity extends AppCompatActivity {

    private String indexurl = "https://appassets.androidplatform.net/assets/index.html";
    private String open_in_app_url_head = "file:///";

    // WebView 和相关组件
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private String cameraPhotoPath;

    // 定位服务
    private LocationManager locationManager;
    private LocationListener locationListener;
    private double lat, lon, alt;
    private long locationTime;

    // 系统服务和 JS 桥接
    private Android_js_bridge jsBridge;
    private FilePickerBridge filePickerBridge;

    // 后端和数据库
    private BackServer backServer;
    private DatabaseHelper dbHelper;
    private RoadDatabaseHelper roadDatabaseHelper;
    private RoadApiHelper roadApiHelper;

    // --- 现代化的 ActivityResult Launcher ---

    // 用于在应用启动时请求多个权限的 Launcher。
	private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
			registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
				// 收集未授予的权限
				List<String> deniedPermissions = new ArrayList<>();
				
				for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
					if (!entry.getValue()) {
						deniedPermissions.add(entry.getKey());
					}
				}
				
				if (!deniedPermissions.isEmpty()) {
					// 将权限名称转换为更友好的描述
					StringBuilder message = new StringBuilder("需要以下权限以保证应用正常运行：\n\n");
					
					for (String permission : deniedPermissions) {
						String friendlyName = getPermissionFriendlyName(permission);
						message.append("• ").append(friendlyName).append("\n");
					}
					
					message.append("\n请在设置中授予这些权限。");
					
					// 显示详细的权限提示对话框
					new AlertDialog.Builder(this)
							.setTitle("权限请求")
							.setMessage(message.toString())
							.setPositiveButton("去设置", (dialog, which) -> {
								// 打开应用设置页面
								Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
								Uri uri = Uri.fromParts("package", getPackageName(), null);
								intent.setData(uri);
								startActivity(intent);
							})
							.setNegativeButton("取消", null)
							.show();
				}
			});

	/**
	 * 将系统权限名称转换为用户友好的描述
	 */
	private String getPermissionFriendlyName(String permission) {
		switch (permission) {
			case Manifest.permission.CAMERA:
				return "相机权限 - 用于拍照和录像";
			case Manifest.permission.WRITE_EXTERNAL_STORAGE:
				return "存储权限 - 用于保存照片和文件";
			case Manifest.permission.READ_EXTERNAL_STORAGE:
				return "读取存储权限 - 用于访问照片和文件";
			case Manifest.permission.ACCESS_FINE_LOCATION:
				return "精确位置权限 - 用于地理标记";
			case Manifest.permission.ACCESS_COARSE_LOCATION:
				return "大致位置权限 - 用于地理标记";
			case Manifest.permission.RECORD_AUDIO:
				return "麦克风权限 - 用于录制音频";
			case Manifest.permission.READ_PHONE_STATE:
				return "电话状态权限 - 用于设备识别";
			// 添加其他您的应用需要的权限
			default:
				// 如果没有匹配的，返回权限的最后一部分
				String[] parts = permission.split("\\.");
				return parts[parts.length - 1].replace("_", " ");
		}
	}

    // 用于处理 WebView 文件/相机选择器结果的 Launcher。
    private final ActivityResultLauncher<Intent> webViewFileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::handleWebViewFileResult);

    // 用于处理 WebView 文件夹选择器结果的 Launcher。
    private final ActivityResultLauncher<Intent> webViewFolderPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::handleWebViewFolderResult);


    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // App 初始化序列
        checkAndRequestPermissions();
        initLocationServices();
        initWebView();
        initDatabaseAndBackend();
        setupWebViewClients();

        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState);
        }

        webView.loadUrl(indexurl);
    }

    /**
     * 初始化定位服务以获取 GPS 数据。
     */
    private void initLocationServices() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                lat = location.getLatitude();
                lon = location.getLongitude();
                alt = location.getAltitude();
                locationTime = location.getTime();
            }
        };
    }

    /**
     * 配置 WebView、其设置以及 JS 接口。
     */
    private void initWebView() {
        webView = findViewById(R.id.webview);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);

        jsBridge = new Android_js_bridge(this, webView);
        filePickerBridge = new FilePickerBridge(this, webView);

        String desktopUserAgent = "Mozilla/5.0 (Linux; Android 10; Pixel 4 XL Build/QD1A.190805.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36";
        webSettings.setUserAgentString(desktopUserAgent);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
    }

    /**
     * 设置数据库助手和后端服务的 JS 接口。
     */
    private void initDatabaseAndBackend() {
        dbHelper = new DatabaseHelper(this);
        roadDatabaseHelper = new RoadDatabaseHelper(this) ;
        backServer = new BackServer(webView, this);
        roadApiHelper = new RoadApiHelper(this, webView);
        webView.addJavascriptInterface(jsBridge, "Android");
        webView.addJavascriptInterface(filePickerBridge, "FilePicker");
        webView.addJavascriptInterface(backServer, "backServer");
        webView.addJavascriptInterface(roadApiHelper, "roadApi");
        initializeDatabaseData();
    }

    /**
     * 检查数据库中是否存在根目录，如果不存在则创建它。
     */
    private void initializeDatabaseData() {
        new Thread(() -> {
            try {
                JSONArray arr = new JSONArray();
                arr.put("root");
                JSONObject rootDir = dbHelper.db_get("SELECT * FROM dir WHERE id = ?", arr);
                if (rootDir == null) {
                    createRootDirectory();
                }
            } catch (Exception e) {
                Log.e("MainActivity", "数据库初始化失败: " + e.getMessage(), e);
            }
        }).start();
    }

    /**
     * 在数据库中创建初始的“根”目录和“默认”目录。
     */
    private void createRootDirectory() {
        try {
            JSONArray rootArr = new JSONArray();
            rootArr.put("root").put("根目录").put(JSONObject.NULL).put("dir").put("{}").put("{}").put(2).put(2).put(System.currentTimeMillis()).put(0);
            dbHelper.db_run("INSERT OR IGNORE INTO dir (id, name, parent_dir_id, type, file_obj, dir_obj, dirsortmode, filesortmode, createtime, seqnumber) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rootArr);

            String defaultId = IdGenerator.getIdByTime();
            JSONArray defaultArr = new JSONArray();
            defaultArr.put(defaultId).put("默认文件夹").put("root").put("dir").put("{}").put("{}").put(2).put(2).put(System.currentTimeMillis()).put(1);
            dbHelper.db_run("INSERT OR IGNORE INTO dir (id, name, parent_dir_id, type, file_obj, dir_obj, dirsortmode, filesortmode, createtime, seqnumber) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", defaultArr);

            Log.i("MainActivity", "数据库初始化完成");
        } catch (Exception e) {
            Log.e("MainActivity", "创建根目录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为 WebView 设置 WebViewClient 和 WebChromeClient。
     */
    private void setupWebViewClients() {
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .setHttpAllowed(true)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/icons/", new WebViewAssetLoader.InternalStoragePathHandler(this, new File(getFilesDir(), "icons")))
                .addPathHandler("/app_data/", new WebViewAssetLoader.InternalStoragePathHandler(this, new File(getFilesDir(), "app_data")))
				.addPathHandler("/watermark_icons/", new WebViewAssetLoader.InternalStoragePathHandler(this, new File(getFilesDir(), "watermark/icon")))
                .build();
        webView.setWebViewClient(new WebViewClient() {
            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith(open_in_app_url_head)) {
                    // 在 WebView 内部加载本地文件 URL
                    return false;
                }
                // 对于所有其他 URL，在外部浏览器中打开
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webView.evaluateJavascript("if(typeof onAndroidReady === 'function') { onAndroidReady(); }", null);
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (!url.startsWith("data:")) {
                String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                startDownload(url, filename);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                // 取消任何之前的（可能未处理的）文件选择请求
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                // 相机权限在应用启动时请求。如果此时权限仍未被授予，
                // 我们将优雅地取消文件选择操作，而不是再次请求权限。
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    showToast("需要相机权限才能拍照");
                    filePathCallback.onReceiveValue(null);
                    return true; // 表明我们已经处理了这次请求
                }

                String[] acceptTypes = fileChooserParams.getAcceptTypes();
                boolean isMultiple = (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);

                if(getchoosemode(acceptTypes, ".for_webview_camera")){
                    openCamera();
                } else if (getchoosemode(acceptTypes, ".for_webview_dir")) {
                    Intent contentSelectionIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    webViewFolderPickerLauncher.launch(contentSelectionIntent);
                } else {
                    openFileChooser(isMultiple, fileChooserParams.getAcceptTypes());
                }
                return true;
            }
        });
    }

    /**
     * 在配置发生变化（如屏幕旋转）后恢复实例状态。
     */
    private void restoreInstanceState(Bundle savedInstanceState) {
        cameraPhotoPath = savedInstanceState.getString("cameraPhotoPath");
        if (filePickerBridge != null) {
            filePickerBridge.onRestoreInstanceState(savedInstanceState);
        }
        if (jsBridge != null) {
            jsBridge.onRestoreInstanceState(savedInstanceState);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 拦截返回按钮事件并将其转发给 JavaScript 处理
            webView.evaluateJavascript("window.phoneback()", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
        }
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        if (dbHelper != null) {
            dbHelper.close();
        }
    }

    /**
     * 使用现代化的 Launcher API 检查并请求必要的运行时权限。
     */
    private void checkAndRequestPermissions() {
        String[] REQUIRED_PERMISSIONS = {
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
        requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS);
    }

    /**
     * 辅助方法，用于检查文件选择器的 acceptTypes 数组中是否存在特定字符串。
     */
    private boolean getchoosemode(String[] acceptTypes, String targetType) {
        if (acceptTypes != null && targetType != null) {
            for (String type : acceptTypes) {
                if (targetType.equals(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 打开一个文件选择器，其中包含拍摄新照片的选项。
     */
    private void openFileChooser(boolean isMultiple, String[] acceptTypes) {
        // --- 1. 创建拍照意图 (不变) ---
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        File photoFile = null;
        try {
            photoFile = createImageFile();
        } catch (IOException ex) {
            showToast("无法创建图片文件");
        }
        if (photoFile != null) {
            cameraPhotoPath = photoFile.getAbsolutePath();
            Uri photoURI = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
        } else {
            takePictureIntent = null;
        }

        // --- 2. 创建文件选择意图 (核心修改部分) ---
        Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
        contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
        contentSelectionIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, isMultiple);

        // --- 2.1 智能处理 acceptTypes ---
        HashSet<String> mimeTypesSet = new HashSet<>();
        if (acceptTypes != null && acceptTypes.length > 0 && !(acceptTypes.length == 1 && (acceptTypes[0] == null || acceptTypes[0].trim().isEmpty()))) {
            for (String type : acceptTypes) {
                if (type == null || type.trim().isEmpty()) continue;

                if (type.startsWith(".")) { // 处理后缀名
                    String extension = type.substring(1).toLowerCase();
                    String mimeFromExtension = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                    if (mimeFromExtension != null) {
                        mimeTypesSet.add(mimeFromExtension);
                    }
                    // 对已知特殊后缀做兼容处理
                    if ("geojson".equalsIgnoreCase(extension)) {
                        mimeTypesSet.add("application/geo+json");
                        mimeTypesSet.add("application/octet-stream");
                    } else if (mimeFromExtension == null) { // 对其他未知后缀
                        mimeTypesSet.add("application/octet-stream");
                    }
                } else { // 处理MIME类型
                    mimeTypesSet.add(type);
                }
            }
        }
        
        // ★★★ 关键的兼容性优化 ★★★
        String[] finalMimeTypes = mimeTypesSet.toArray(new String[0]);

        if (finalMimeTypes.length == 1) {
            // 如果只有一种MIME类型，直接使用 setType，这是最直接、最兼容的方式。
            contentSelectionIntent.setType(finalMimeTypes[0]);
        } else if (finalMimeTypes.length > 1) {
            // 如果有多种MIME类型，才使用 setType("*/*") + EXTRA_MIME_TYPES 的组合。
            contentSelectionIntent.setType("*/*");
            contentSelectionIntent.putExtra(Intent.EXTRA_MIME_TYPES, finalMimeTypes);
        } else {
            // 如果没有指定任何类型，或者处理后为空，则允许所有文件。
            contentSelectionIntent.setType("*/*");
        }
        
        // 打印日志用于调试，确认最终传递给Intent的MIME类型是什么
        Log.d("FileChooser", "Final MIME Types: " + java.util.Arrays.toString(finalMimeTypes));
        if(finalMimeTypes.length > 1){
            Log.d("FileChooser", "Using EXTRA_MIME_TYPES method.");
        } else if (finalMimeTypes.length == 1) {
            Log.d("FileChooser", "Using setType() method with: " + finalMimeTypes[0]);
        } else {
            Log.d("FileChooser", "No MIME types specified, defaulting to */*.");
        }

        // --- 3. 创建并启动最终的选择器 (不变) ---
        Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
        chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
        chooserIntent.putExtra(Intent.EXTRA_TITLE, "选择文件");
        if (takePictureIntent != null) {
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{takePictureIntent});
        }
        webViewFileChooserLauncher.launch(chooserIntent);
    }
    /**
     * 直接打开相机来拍照。
     */
    private void openCamera() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        File photoFile;
        try {
            photoFile = createImageFile();
        } catch (IOException ex) {
            showToast("无法创建图片文件");
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
            }
            return;
        }
        cameraPhotoPath = photoFile.getAbsolutePath();
        Uri photoURI = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
        // 使用与文件选择器相同的 Launcher
        webViewFileChooserLauncher.launch(takePictureIntent);
    }

    /**
     * 在应用的外部图片目录中创建一个临时的图片文件。
     */
    private File createImageFile() throws IOException {
        String uniqueId = IdGenerator.getIdByTime();
        String imageFileName = uniqueId + ".jpg";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return new File(storageDir, imageFileName);
    }

    /**
     * 使用 Android 的 DownloadManager 启动文件下载。
     */
    private void startDownload(String url, String fileName) {
        try {
            Uri uri = Uri.parse(url);
            android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(uri);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) {
                request.addRequestHeader("Cookie", cookies);
            }
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            android.app.DownloadManager downloadManager = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            downloadManager.enqueue(request);
            showToast("下载已开始");
        } catch (Exception e) {
            showToast("下载出现错误");
        }
    }

    /**
     * 用于处理 WebView 文件/相机选择器结果的回调方法。
     */
    private void handleWebViewFileResult(ActivityResult result) {
        if (filePathCallback == null) return;

        Uri[] results = null;
        if (result.getResultCode() == Activity.RESULT_OK) {
            Intent data = result.getData();
            // 情况1：来自文件选择器的结果（单个或多个文件）
            if (data != null && (data.getData() != null || data.getClipData() != null)) {
                if (data.getClipData() != null) {
                    int itemCount = data.getClipData().getItemCount();
                    results = new Uri[itemCount];
                    for (int i = 0; i < itemCount; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            // 情况2：来自相机的结果
            else if (cameraPhotoPath != null) {
                writeLocationToExif(cameraPhotoPath);
                results = new Uri[]{getCameraPhotoUri()};
            }
        }

        filePathCallback.onReceiveValue(results);
        // 清理状态变量
        filePathCallback = null;
        cameraPhotoPath = null;
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    /**
     * 用于处理 WebView 文件夹选择器结果的回调方法。
     */
    private void handleWebViewFolderResult(ActivityResult result) {
        if (filePathCallback == null) return;

        Uri[] results = null;
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && result.getData().getData() != null) {
            try {
                Uri folderUri = result.getData().getData();
                results = jsBridge.getUrisFromFolder(folderUri);
                DocumentFile pickedFolder = DocumentFile.fromTreeUri(this, folderUri);
                if (pickedFolder != null) {
                    List<String> relativePaths = new ArrayList<>();
                    String rootDocumentId = DocumentsContract.getTreeDocumentId(pickedFolder.getUri());
                    String dir_name = pickedFolder.getName();

                    for (Uri fileUri : results) {
                        String fileDocumentId = DocumentsContract.getDocumentId(fileUri);
                        String relativePath = "";
                         if (fileDocumentId.startsWith(rootDocumentId + ":")) {
                            relativePath = fileDocumentId.substring((rootDocumentId + ":").length());
                        } else if (fileDocumentId.startsWith(rootDocumentId)) {
                             relativePath = fileDocumentId.substring(rootDocumentId.length());
                             if (relativePath.startsWith("/")) {
                                 relativePath = relativePath.substring(1);
                             }
                         }
                        relativePaths.add(dir_name + "/" + relativePath);
                    }
                    JSONArray jsonArray = new JSONArray(relativePaths);
                    String tojs = "window.getphonepath(" + jsonArray.toString() + ")";
                    webView.evaluateJavascript(tojs, null);
                }
            } catch (Exception e) {
                webView.evaluateJavascript("window.getFolderFilesError('文件路径获取错误：'" + e.getMessage() + ")", null);
            }
        } else {
            // 用户取消了文件夹选择
            webView.evaluateJavascript("window.getphonepath('')", null);
        }

        filePathCallback.onReceiveValue(results);
        // 清理状态变量
        filePathCallback = null;
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (cameraPhotoPath != null) {
            outState.putString("cameraPhotoPath", cameraPhotoPath);
        }
        if (filePickerBridge != null) {
            filePickerBridge.onSaveInstanceState(outState);
        }
        if (jsBridge != null) {
            jsBridge.onSaveInstanceState(outState);
        }
    }

    private Uri getCameraPhotoUri() {
        File file = new File(cameraPhotoPath);
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
    }

    /**
     * 将最后一次获取到的 GPS 坐标写入指定照片的 EXIF 数据中。
     */
    private void writeLocationToExif(String photoPath) {
        if (locationTime == 0 || (System.currentTimeMillis() - locationTime) > 20000) {
            showToast("GPS数据无效或已过期");
            return;
        }
        try {
            ExifInterface exif = new ExifInterface(photoPath);
            exif.setGpsInfo(new Location("gps"));
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, convertToDMS(lat));
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, lat >= 0 ? "N" : "S");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, convertToDMS(lon));
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, lon >= 0 ? "E" : "W");
            if (alt != 0) {
                 exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, String.valueOf(Math.abs(alt)));
                 exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, alt < 0 ? "1" : "0");
            }
            exif.saveAttributes();
        } catch (IOException e) {
            showToast("EXIF写入失败: " + e.getMessage());
        }
    }

    /**
     * 将十进制坐标转换为 EXIF 所需的度/分/秒（DMS）格式。
     */
    private String convertToDMS(double coordinate) {
        coordinate = Math.abs(coordinate);
        int degrees = (int) coordinate;
        coordinate = (coordinate - degrees) * 60;
        int minutes = (int) coordinate;
        coordinate = (coordinate - minutes) * 60;
        double seconds = coordinate;
        return degrees + "/1," + minutes + "/1," + (int) (seconds * 1000) + "/1000";
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        CookieManager.getInstance().flush();
    }

    // 公共的 getter 方法，以便其他类可以访问这些助手对象
    public DatabaseHelper getDatabaseHelper() { return dbHelper; }
    public BackServer getBackServer() { return backServer; }
}
