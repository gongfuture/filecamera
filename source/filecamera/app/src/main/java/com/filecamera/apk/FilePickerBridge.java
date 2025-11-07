package com.filecamera.apk;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class FilePickerBridge {

    // region --- 常量和成员变量 ---
    private static final String TAG = "FilePickerBridge";
    private static final String PREFERENCES_NAME = "watermark_settings";
    private static final String WATERMARK_CONFIG_KEY = "watermark_config_json";

    private final AppCompatActivity activity; // [MODIFIED] 需要 AppCompatActivity 来注册 Launcher
    private final WebView webView;
    private final SharedPreferences sharedPreferences;
    private String cachedWatermarkConfig = null;
    private String cameraPhotoPath;
    private Uri cameraPhotoUri; // [ADDED] 用于相机 Launcher 回调

    private String pendingTaskId;

    private LocationManager locationManager;
    private LocationListener locationListener;
    private double lat, lon, alt;
    private long locationTime;

    // [ADDED] ActivityResultLaunchers
    private final ActivityResultLauncher<Intent> filePickerLauncher;
    private final ActivityResultLauncher<Uri> folderPickerLauncher;
    private final ActivityResultLauncher<Uri> cameraLauncher;
    private final ActivityResultLauncher<Intent> customCameraLauncher;
    // endregion

    // region --- 构造与生命周期 ---
    public FilePickerBridge(AppCompatActivity activity, WebView webView) { // [MODIFIED] 构造函数参数
        this.activity = activity;
        this.webView = webView;
        this.sharedPreferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        initLocationServices();

        // [ADDED] 在构造函数中注册所有的 Launchers
        this.filePickerLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleActivityResult(result, "file")
        );
        this.folderPickerLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> handleUriResult(uri, "folder")
        );
        this.cameraLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> handleCameraResult(success)
        );
        this.customCameraLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleActivityResult(result, "custom_camera")
        );
    }

    public void onSaveInstanceState(Bundle outState) {
        outState.putString("pendingTaskId", pendingTaskId);
        outState.putString("cameraPhotoPath_bridge", cameraPhotoPath);
        if (cameraPhotoUri != null) {
            outState.putString("cameraPhotoUri_bridge", cameraPhotoUri.toString());
        }
    }

    public void onRestoreInstanceState(Bundle savedInstanceState) {
        pendingTaskId = savedInstanceState.getString("pendingTaskId");
        cameraPhotoPath = savedInstanceState.getString("cameraPhotoPath_bridge");
        String uriString = savedInstanceState.getString("cameraPhotoUri_bridge");
        if (uriString != null) {
            cameraPhotoUri = Uri.parse(uriString);
        }
    }
    // endregion
    
    // region --- [标准改造] 统一回调函数 ---
    private void call_js_callback(String task_id, boolean success, Object data, String error_message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (task_id == null || task_id.isEmpty()) {
                    Log.w(TAG, "call_js_callback invoked with empty task_id.");
                    return;
                }
                JSONObject params = new JSONObject();
                params.put("id", task_id);
                params.put("success", success);
                if (data != null) {
                    params.put("data", data);
                }
                if (error_message != null) {
                    params.put("errorMessage", error_message);
                }
                String js_code = String.format("window.handlePromiseCallback(%s);", params.toString());
                webView.evaluateJavascript(js_code, null);
            } catch (JSONException e) {
                Log.e(TAG, "Failed to create JSON for JS callback", e);
            }
        });
    }
    // endregion

    // region --- [标准改造] 公共 API (JavaScript 接口) ---
    @JavascriptInterface
    public void open(String task_id, String json_data) {
        new Thread(() -> {
            try {
                if (pendingTaskId != null) {
                    call_js_callback(task_id, false, null, "另一个选择操作正在进行中");
                    return;
                }

                JSONObject params = new JSONObject(json_data);
                String type = params.optString("type", "file");
                this.pendingTaskId = task_id;

                activity.runOnUiThread(() -> {
                    switch (type.toLowerCase()) {
                        case "camera":
                            startCamera();
                            break;
                        case "customcamera":
                            startCustomCamera();
                            break;
                        case "dir":
                            startFolderPicker();
                            break;
                        default:
                            boolean multiple = params.optBoolean("multiple", false);
                            JSONArray acceptTypes = params.optJSONArray("acceptTypes");
                            startFilePicker(multiple, acceptTypes);
                            break;
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error in open() preparation", e);
                call_js_callback(task_id, false, null, "参数格式错误或准备失败: " + e.getMessage());
                this.pendingTaskId = null;
            }
        }).start();
    }
    // ... (其他 JavascriptInterface 方法保持不变)
    @JavascriptInterface
    public void shareFile(String task_id, String json_data) {
        new Thread(() -> {
            try {
                JSONObject params = new JSONObject(json_data);
                String targetApp = params.optString("targetApp", "all");
                boolean multiple = params.optBoolean("multiple", false);

                JSONArray filesArray = params.optJSONArray("files");
                if (filesArray == null || filesArray.length() == 0) {
                    call_js_callback(task_id, false, null, "没有提供文件");
                    return;
                }

                ArrayList<Uri> uris = new ArrayList<>();
                ArrayList<String> names = new ArrayList<>();
                for (int i = 0; i < filesArray.length(); i++) {
                    JSONObject fileObj = filesArray.getJSONObject(i);
                    uris.add(Uri.parse(fileObj.getString("uri")));
                    names.add(fileObj.optString("name", "文件" + (i + 1)));
                }

                if (uris.isEmpty()) {
                    call_js_callback(task_id, false, null, "没有找到有效文件");
                    return;
                }

                boolean success;
                String errorMessage = null;

                if (multiple) {
                    success = shareMultiple(uris, names, targetApp);
                } else {
                    success = shareSingle(uris.get(0), names.get(0), targetApp);
                }

                if (!success) {
                    errorMessage = "启动分享失败";
                }

                call_js_callback(task_id, success, new JSONObject(), errorMessage);

            } catch (Exception e) {
                Log.e(TAG, "Error in shareFile()", e);
                call_js_callback(task_id, false, null, e.getMessage());
            }
        }).start();
    }

    @JavascriptInterface
    public void writeWatermarkConfig(String task_id, String json_data) {
        new Thread(() -> {
            try {
                JSONObject params = new JSONObject(json_data);
                JSONObject configObject = params.getJSONObject("config");
                String configData = configObject.toString();

                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(WATERMARK_CONFIG_KEY, configData);
                boolean success = editor.commit();

                if (success) {
                    this.cachedWatermarkConfig = configData;
                    call_js_callback(task_id, true, new JSONObject(), null);
                } else {
                    call_js_callback(task_id, false, null, "保存配置失败");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in writeWatermarkConfig()", e);
                call_js_callback(task_id, false, null, e.getMessage());
            }
        }).start();
    }

    @JavascriptInterface
    public void readWatermarkConfig(String task_id, String json_data) {
        new Thread(() -> {
            try {
                String configDataString = createWatermarkConfig();
                JSONObject configObject = new JSONObject(configDataString);
                call_js_callback(task_id, true, configObject, null);
            } catch (Exception e) {
                Log.e(TAG, "Error in readWatermarkConfig()", e);
                call_js_callback(task_id, false, null, e.getMessage());
            }
        }).start();
    }
    @JavascriptInterface
    public void writeConfig(String task_id, String json_data) {
        new Thread(() -> {
            try {
                JSONObject params = new JSONObject(json_data);
                JSONArray obj_arr = params.getJSONArray("obj_arr");

                SharedPreferences.Editor editor = sharedPreferences.edit();

                // 遍历数组，将所有键值对放入 editor
                for (int i = 0; i < obj_arr.length(); i++) {
                    JSONObject item = obj_arr.getJSONObject(i);
                    String key = item.getString("key");
                    String value = item.getString("value");
                    editor.putString(key, value);
                }

                // 一次性提交所有更改
                boolean success = editor.commit();

                if (success) {
                    call_js_callback(task_id, true, new JSONObject(), null);
                } else {
                    call_js_callback(task_id, false, null, "写入配置失败");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in writeConfig()", e);
                call_js_callback(task_id, false, null, "写入配置时出错: " + e.getMessage());
            }
        }).start();
    }
    @JavascriptInterface
    public void readConfig(String task_id, String json_data) {
        new Thread(() -> {
            try {
                JSONObject params = new JSONObject(json_data);
                JSONArray key_arr = params.getJSONArray("key_arr");

                JSONObject result = new JSONObject();

                // 遍历数组，从 SharedPreferences 中读取每一个键的值
                for (int i = 0; i < key_arr.length(); i++) {
                    String key = key_arr.getString(i);
                    String value = sharedPreferences.getString(key, null);
                    result.put(key, value);
                }
                call_js_callback(task_id, true, result, null);

            } catch (Exception e) {
                Log.e(TAG, "Error in readConfig()", e);
                call_js_callback(task_id, false, null, "读取配置时出错: " + e.getMessage());
            }
        }).start();
    }
    // endregion

    // region --- [REMOVED] 核心回调 (onActivityResult) ---
    // public boolean onActivityResult(...) { ... } // 这个方法已完全删除
    // endregion

    // region --- [ADDED] 新的结果处理器 ---
    private void handleActivityResult(ActivityResult result, String type) {
        stopLocationUpdates();
        String current_task_id = this.pendingTaskId;
        this.pendingTaskId = null; // 清理

        if (current_task_id == null) return;

        try {
            if (result.getResultCode() != Activity.RESULT_OK) {
                call_js_callback(current_task_id, true, null, "用户取消操作");
                return;
            }

            Intent data = result.getData();
            JSONArray results = new JSONArray();

            if ("file".equals(type)) {
                results = handleFileResult(data);
            } else if ("custom_camera".equals(type)) {
                results = handleCustomCameraResult(data);
            }

            if (results.length() > 0) {
                call_js_callback(current_task_id, true, results, null);
            } else {
                call_js_callback(current_task_id, false, null, "没有选择任何项目");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing activity result for " + type, e);
            call_js_callback(current_task_id, false, null, "处理结果时出错: " + e.getMessage());
        }
    }

    private void handleUriResult(Uri uri, String type) {
        stopLocationUpdates();
        String current_task_id = this.pendingTaskId;
        this.pendingTaskId = null;

        if (current_task_id == null) return;

        try {
            if (uri == null) {
                call_js_callback(current_task_id, true, null, "用户取消操作");
                return;
            }

            JSONArray results = new JSONArray();
            if ("folder".equals(type)) {
                // 构建一个与旧 handleFolderResult 兼容的 Intent
                Intent data = new Intent();
                data.setData(uri);
                results = handleFolderResult(data);
            }

            if (results.length() > 0) {
                call_js_callback(current_task_id, true, results, null);
            } else {
                call_js_callback(current_task_id, false, null, "没有选择任何项目");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing uri result for " + type, e);
            call_js_callback(current_task_id, false, null, "处理结果时出错: " + e.getMessage());
        }
    }

    private void handleCameraResult(boolean success) {
        stopLocationUpdates();
        String current_task_id = this.pendingTaskId;
        this.pendingTaskId = null; // 清理

        if (current_task_id == null) return;
        
        try {
            if (!success) {
                call_js_callback(current_task_id, true, null, "用户取消操作");
                return;
            }
            
            JSONArray results = new JSONArray();
            if (cameraPhotoPath != null) {
                writeLocationToExif(cameraPhotoPath);
                File photoFile = new File(cameraPhotoPath);
                // 使用 cameraPhotoUri, 它是由 FileProvider 生成的 content:// uri
                results.put(createFileObject(cameraPhotoUri));
            }

            if (results.length() > 0) {
                call_js_callback(current_task_id, true, results, null);
            } else {
                call_js_callback(current_task_id, false, null, "拍照失败或未找到照片");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing camera result", e);
            call_js_callback(current_task_id, false, null, "处理相机结果时出错: " + e.getMessage());
        } finally {
            this.cameraPhotoPath = null;
            this.cameraPhotoUri = null;
        }
    }
    // endregion

    // region --- Activity 启动器 (已改造) ---
    private void startFilePicker(boolean multiple, JSONArray acceptTypes) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        String mimeType = "*/*";
        if (acceptTypes != null && acceptTypes.length() > 0) {
            try {
                String firstType = acceptTypes.getString(0);
                if (firstType.contains("/")) {
                    mimeType = firstType;
                }
            } catch (JSONException ignored) {
            }
        }
        intent.setType(mimeType);
        filePickerLauncher.launch(Intent.createChooser(intent, "选择文件"));
    }

    private void startFolderPicker() {
        // ACTION_OPEN_DOCUMENT_TREE 不需要 chooser
        folderPickerLauncher.launch(null);
    }

    private void startCamera() {
        if (activity.getPackageManager().resolveActivity(new Intent(MediaStore.ACTION_IMAGE_CAPTURE), PackageManager.MATCH_DEFAULT_ONLY) == null) {
            call_js_callback(pendingTaskId, false, null, "相机不可用");
            pendingTaskId = null; // 清理
            return;
        }
        startLocationUpdates();
        try {
            File photoFile = createImageFile();
            cameraPhotoPath = photoFile.getAbsolutePath();
            cameraPhotoUri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", photoFile);
            cameraLauncher.launch(cameraPhotoUri);
        } catch (IOException e) {
            call_js_callback(pendingTaskId, false, null, "创建图片文件失败: " + e.getMessage());
            pendingTaskId = null; // 清理
        }
    }

    private void startCustomCamera() {
        String watermarkConfigString = createWatermarkConfig();
        Intent intent = new Intent(activity, CameraActivity.class);
        intent.putExtra("watermark_config", watermarkConfigString);
        customCameraLauncher.launch(intent);
    }
    // endregion

    // region --- 结果解析器 (无变动) ---
    private JSONArray handleFileResult(Intent data) throws JSONException {
        JSONArray results = new JSONArray();
        if (data != null) {
            if (data.getClipData() != null) { // 多选
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    results.put(createFileObject(data.getClipData().getItemAt(i).getUri()));
                }
            } else if (data.getData() != null) { // 单选
                results.put(createFileObject(data.getData()));
            }
        }
        return results;
    }

    private JSONArray handleFolderResult(Intent data) throws JSONException {
        JSONArray results = new JSONArray();
        if (data != null && data.getData() != null) {
            Uri folderUri = data.getData();
            DocumentFile folder = DocumentFile.fromTreeUri(activity, folderUri);
            if (folder != null) {
                String folderName = folder.getName();
                String rootDocumentId = DocumentsContract.getTreeDocumentId(folder.getUri());
                List<FileInfo> items = getAllItemsInFolder(folder);
                for (FileInfo item : items) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", getFileName(item.uri));
                    obj.put("uri", item.uri.toString());
                    obj.put("relativePath", getRelativePath(item.uri, rootDocumentId, folderName));
                    obj.put("type", item.isDirectory ? "dir" : "file");
                    results.put(obj);
                }
            }
        }
        return results;
    }

    // [REMOVED] handleCameraResult 已被新的基于 boolean 的方法取代
    /*
    private JSONArray handleCameraResult() throws JSONException { ... }
    */

    private JSONArray handleCustomCameraResult(Intent data) throws JSONException {
        JSONArray results = new JSONArray();
        if (data != null && data.getData() != null) {
            results.put(createFileObject(data.getData()));
        }
        return results;
    }
    // endregion
    
    // ... 此处省略了 水印配置、GPS定位、文件处理与分享辅助等所有未改动的代码 ...
    // ... 您可以将原始文件中的这些部分直接复制到这里 ...
    // region --- 水印配置模块 ---
    private String createWatermarkConfig() {
        if (this.cachedWatermarkConfig != null && !this.cachedWatermarkConfig.isEmpty()) {
            return this.cachedWatermarkConfig;
        }
        String savedConfig = sharedPreferences.getString(WATERMARK_CONFIG_KEY, null);
        String finalConfig;
        if (savedConfig != null && !savedConfig.isEmpty()) {
            try {
                new JSONObject(savedConfig);
                finalConfig = savedConfig;
            } catch (JSONException e) {
                Log.w(TAG, "保存的水印配置格式无效，使用默认配置");
                finalConfig = getDefaultWatermarkConfig();
            }
        } else {
            finalConfig = getDefaultWatermarkConfig();
        }
        this.cachedWatermarkConfig = finalConfig;
        return finalConfig;
    }
    private String getDefaultWatermarkConfig() {
        try {
            JSONObject rootConfig = new JSONObject();

            // --- 相机配置 (新增) ---
            JSONObject camera = new JSONObject()
                    .put("proportion", "16:9")
					.put("sync_album", false);
            rootConfig.put("camera", camera);

            // --- 水印配置 ---
            JSONObject watermark = new JSONObject()
                    .put("enable", true)
                    .put("scale", 0.75)
                    .put("padding", new JSONArray("[16, 16, 16, 16]"))
                    .put("radius", new JSONArray("[8, 8, 8, 8]"))
                    .put("position", new JSONObject().put("left", 10).put("bottom", 10));

            // 页眉
            JSONObject header = new JSONObject()
                    .put("display", true) // <-- 修改：新增
                    .put("background", new JSONArray("[27, 68, 147, 255]"))
                    .put("icon", new JSONObject()
                            .put("display", true) // <-- 修改：新增
                            .put("value", "file:///android_asset/icon.png")
                            .put("width", 48)
                            .put("height", 48)
                            .put("position", "left"))
                    .put("title", new JSONObject()
                            .put("value", "巡查记录")
                            .put("color", "#FFFFFF")
                            .put("size", 20));

            // 主体
            JSONObject body = new JSONObject()
                    .put("display", true) // <-- 修改：新增
                    .put("background", new JSONArray("[248, 250, 252, 170]"))
                    .put("content", new JSONObject()
                            .put("road_name", new JSONObject().put("name", "路线名称").put("type", "road").put("name_display", true).put("index", 1).put("color", "#2C3E50").put("size", 16))
                            .put("pile", new JSONObject().put("name", "桩号").put("type", "camera_pile").put("name_display", true).put("index", 2).put("color", "#2C3E50").put("size", 16))
                            .put("coord", new JSONObject().put("name", "坐标").put("type", "camera_coord").put("name_display", true).put("index", 3).put("color", "#2C3E50").put("size", 16))
                            .put("time", new JSONObject().put("name", "时间").put("type", "time").put("name_display", true).put("index", 4).put("color", "#2C3E50").put("size", 16))
                            //.put("weather", new JSONObject().put("name", "天气").put("type", "weather").put("name_display", true).put("value", "").put("index", 5).put("color", "#2C3E50").put("size", 16))
                    );

            // 页脚
            JSONObject foot = new JSONObject()
                    .put("display", true) // <-- 修改：新增
                    .put("background", new JSONArray("[27, 68, 147, 255]"))
                    .put("content", new JSONObject()
                            .put("unit", new JSONObject().put("name", "责任单位").put("type", "string").put("name_display", true).put("value", "").put("index", 1).put("color", "#FFFFFF").put("size", 12))
                    );

            watermark.put("header", header)
                    .put("body", body)
                    .put("foot", foot);

            rootConfig.put("watermark", watermark);

            // --- 二维码配置 ---
            JSONObject qrcode = new JSONObject()
                    .put("enable", true)
                    .put("scale", 0.75)
                    .put("padding", new JSONArray("[4, 4, 4, 4]"))
                    .put("radius", new JSONArray("[8, 8, 8, 8]"))
                    .put("position", new JSONObject().put("right", 10).put("top", 10));

            rootConfig.put("qrcode", qrcode);

            return rootConfig.toString();
        } catch (JSONException e) {
            e.printStackTrace();
            return "{}"; // 发生错误时返回空JSON对象
        }
    }
	
	// endregion

    // region --- GPS 定位模块 ---
    private void initLocationServices() {
        locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
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

    private void startLocationUpdates() {
        try {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0.0f, locationListener);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "请求位置更新失败", e);
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    private void writeLocationToExif(String photoPath) {
        if (hasGPSData(photoPath)) {
            return;
        }
        if (locationTime == 0) {
            showToast("未获得GPS数据");
            return;
        }
        if (System.currentTimeMillis() - locationTime > 20000) {
            showToast("GPS数据已过期");
            return;
        }
        try {
            ExifInterface exif = new ExifInterface(photoPath);
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, convertDecimalToDMS(lat));
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, lat > 0 ? "N" : "S");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, convertDecimalToDMS(lon));
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, lon > 0 ? "E" : "W");
            if (alt != 0) {
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, String.valueOf(Math.abs(alt)));
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, alt > 0 ? "0" : "1");
            }
            exif.saveAttributes();
        } catch (IOException e) {
            showToast("EXIF写入失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean hasGPSData(String imagePath) {
        try {
            ExifInterface exif = new ExifInterface(imagePath);
            String latitude = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE);
            return latitude != null && !latitude.isEmpty();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private String convertDecimalToDMS(double coordinate) {
        coordinate = Math.abs(coordinate);
        int degrees = (int) coordinate;
        coordinate = (coordinate - degrees) * 60;
        int minutes = (int) coordinate;
        coordinate = (coordinate - minutes) * 60;
        int seconds = (int) (coordinate * 1000);
        return degrees + "/1," + minutes + "/1," + seconds + "/1000";
    }
    // endregion

    // region --- 文件处理与分享辅助 ---
    private boolean shareSingle(Uri uri, String name, String targetApp) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(getMimeType(name));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, name);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if ("wechat".equals(targetApp) && isAppInstalled("com.tencent.mm")) {
                intent.setPackage("com.tencent.mm");
            } else if ("qq".equals(targetApp) && isAppInstalled("com.tencent.mobileqq")) {
                intent.setPackage("com.tencent.mobileqq");
            }
            activity.startActivity(Intent.createChooser(intent, "分享文件"));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Share single failed", e);
            return false;
        }
    }

    private boolean shareMultiple(ArrayList<Uri> uris, ArrayList<String> names, String targetApp) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.setType("*/*");
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            intent.putExtra(Intent.EXTRA_SUBJECT, String.join(", ", names));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if ("wechat".equals(targetApp) && isAppInstalled("com.tencent.mm")) {
                intent.setPackage("com.tencent.mm");
            } else if ("qq".equals(targetApp) && isAppInstalled("com.tencent.mobileqq")) {
                intent.setPackage("com.tencent.mobileqq");
            }
            activity.startActivity(Intent.createChooser(intent, "分享多个文件"));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Share multiple failed", e);
            return false;
        }
    }

    private boolean isAppInstalled(String packageName) {
        try {
            activity.getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String getMimeType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "*/*";
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            String extension = fileName.substring(lastDot + 1).toLowerCase();
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mimeType != null) {
                return mimeType;
            }
        }
        return "*/*";
    }

    private File createImageFile() throws IOException {
        File picturesDir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (picturesDir == null) {
            throw new IOException("无法获取外部私有存储目录。");
        }
        File storageDir = new File(picturesDir, "photo_cache");
        if (storageDir.exists() && storageDir.isDirectory()) {
            File[] files = storageDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.delete()) {
                        Log.w(TAG, "无法删除缓存文件: " + f.getAbsolutePath());
                    }
                }
            }
        }
        if (!storageDir.exists()) {
            if (!storageDir.mkdirs()) {
                throw new IOException("无法创建照片缓存目录: " + storageDir.getAbsolutePath());
            }
        }
        String fileName = IdGenerator.getIdByTime() + ".jpg";
        return new File(storageDir, fileName);
    }

    private JSONObject createFileObject(Uri uri) throws JSONException {
        return new JSONObject()
                .put("name", getFileName(uri))
                .put("uri", uri.toString())
                .put("type", "file");
    }

    private String getFileName(Uri uri) {
        String fileName = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex);
                    }
                }
            }
        }
        if (fileName == null) {
            fileName = uri.getLastPathSegment();
        }
        return fileName;
    }

    private List<FileInfo> getAllItemsInFolder(DocumentFile rootFolder) {
        List<FileInfo> items = new ArrayList<>();
        Deque<DocumentFile> stack = new ArrayDeque<>();
        stack.push(rootFolder);
        while (!stack.isEmpty()) {
            DocumentFile current = stack.pop();
            DocumentFile[] children = current.listFiles();
            if (children != null) {
                for (DocumentFile child : children) {
                    items.add(new FileInfo(child.getUri(), child.isDirectory()));
                    if (child.isDirectory()) {
                        stack.push(child);
                    }
                }
            }
        }
        return items;
    }

    private String getRelativePath(Uri uri, String rootDocumentId, String folderName) {
        try {
            String documentId = DocumentsContract.getDocumentId(uri);
            if (documentId.startsWith(rootDocumentId)) {
                String relativePath = documentId.substring(rootDocumentId.length());
                if (relativePath.startsWith("/")) {
                    relativePath = relativePath.substring(1);
                }
                return folderName + "/" + relativePath;
            }
        } catch (Exception ignored) {
        }
        return folderName + "/" + getFileName(uri);
    }
    // endregion
    private void showToast(String message) {
        activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
    }

    private static class FileInfo {
        public Uri uri;
        public boolean isDirectory;

        public FileInfo(Uri uri, boolean isDirectory) {
            this.uri = uri;
            this.isDirectory = isDirectory;
        }
    }
    
}
