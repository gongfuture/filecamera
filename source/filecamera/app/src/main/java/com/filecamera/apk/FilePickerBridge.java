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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class FilePickerBridge {

    // region --- 常量和成员变量 ---
    private static final String TAG = "FilePickerBridge";
    private static final String PREFERENCES_NAME = "watermark_settings";
    
    // [MODIFIED] 去掉了 WATERMARK_CONFIG_KEY 和 cachedWatermarkConfig，由 Manager 管理

    private final AppCompatActivity activity;
    private final WebView webView;
    private final SharedPreferences sharedPreferences;
    
    private String cameraPhotoPath;
    private Uri cameraPhotoUri;
    private String pendingTaskId;
    /** 打开相机时前端指定的目标目录（照片由 Java 层直接入库到这个目录） */
    private String pendingTargetDirId = "root";

    private LocationManager locationManager;
    private LocationListener locationListener;
    private double lat, lon, alt;
    private long locationTime;

    private final ActivityResultLauncher<Intent> filePickerLauncher;
    private final ActivityResultLauncher<Uri> folderPickerLauncher;
    private final ActivityResultLauncher<Uri> cameraLauncher;
    private final ActivityResultLauncher<Intent> customCameraLauncher;
    private final ActivityResultLauncher<Intent> createDocumentLauncher;
    // 设置包导出：暂存待写入的zip数据与回调任务id
    private byte[] pendingZipBytes = null;
    private String pendingZipTaskId = null;
    // endregion

    // region --- 构造与生命周期 ---
    public FilePickerBridge(AppCompatActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        // 这里保留 sharedPreferences 仅仅是为了兼容 readConfig/writeConfig 通用键值对方法
        // 水印相关的全部走 WatermarkConfigManager
        this.sharedPreferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        initLocationServices();

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
        this.createDocumentLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::handleCreateDocumentResult
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
    
    // region --- 回调函数 ---
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

    // region --- JS 接口 ---
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
                // 相机需要知道目标目录：照片由 Java 层直接入库到这个目录
                this.pendingTargetDirId = params.optString("dirId", "root");
                if (this.pendingTargetDirId == null || this.pendingTargetDirId.trim().isEmpty()) {
                    this.pendingTargetDirId = "root";
                }
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

    /**
     * 打开视频播放页（新录像的水印已烧进画面，播放器不再读尾部数据）。
     * 参数: { id: 文件id, name: 显示名, real_file_name: app_data 里的真实文件名 }
     */
    /**
     * 统一导入入口（文件 / 文件夹）：原生侧弹系统选择器并走入库流程，
     * 参数 { dirId: 当前文件夹id, mode: "file"|"dir" }；取消也会回调，避免前端死锁。
     */
    @JavascriptInterface
    public void importPath(String task_id, String json_data) {
        try {
            JSONObject params = new JSONObject(json_data);
            String dirId = params.optString("dirId", params.optString("dir_id", "root"));
            String mode = params.optString("mode", "file");
            if (!(activity instanceof MainActivity)) {
                call_js_callback(task_id, false, null, "导入不可用");
                return;
            }
            ((MainActivity) activity).beginImport(mode, dirId, payload -> {
                try {
                    if (payload != null && payload.optBoolean("canceled", false)) {
                        call_js_callback(task_id, true, payload.put("canceled", true), null);
                    } else {
                        call_js_callback(task_id, true, payload, null);
                    }
                } catch (Exception e) {
                    call_js_callback(task_id, false, null, "导入回调失败: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error in importPath()", e);
            call_js_callback(task_id, false, null, "导入失败: " + e.getMessage());
        }
    }
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

    // [MODIFIED] 使用 Manager 保存配置
    @JavascriptInterface
    public void writeWatermarkConfig(String task_id, String json_data) {
        new Thread(() -> {
            try {
                JSONObject params = new JSONObject(json_data);
                JSONObject configObject = params.getJSONObject("config");
                String configData = configObject.toString();

                boolean success = WatermarkConfigManager.getInstance(activity).saveConfig(configData);

                if (success) {
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

    // [MODIFIED] 使用 Manager 读取配置
    @JavascriptInterface
    public void readWatermarkConfig(String task_id, String json_data) {
        new Thread(() -> {
            try {
                String configDataString = WatermarkConfigManager.getInstance(activity).getConfig();
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

                boolean gaodeKeyChanged = false;
                for (int i = 0; i < obj_arr.length(); i++) {
                    JSONObject item = obj_arr.getJSONObject(i);
                    String key = item.getString("key");
                    String value = item.getString("value");
                    editor.putString(key, value);
                    if (GaodeApi.KEY_GAODE_WEB_API.equals(key)) {
                        gaodeKeyChanged = true;
                    }
                }

                boolean success = editor.commit();

                if (success) {
                    // [新增] 高德密钥保存后立即生效：解除天气会话失败锁并使缓存失效，无需重启应用
                    if (gaodeKeyChanged) {
                        GaodeApi.onGaodeApiKeyChanged();
                    }
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

    // region --- 设置包导出/导入 (zip: settings.json + icon.png) ---

    private static final String SETTINGS_ZIP_JSON_ENTRY = "settings.json";
    private static final String SETTINGS_ZIP_ICON_ENTRY = "icon.png";
    private static final String WATERMARK_ICON_RELATIVE_PATH = "watermark/icon/cache_icon.png";

    /**
     * [新增] 导出设置包：将当前水印配置与自定义图标打包为zip，通过系统"保存到"界面交给用户。
     * 参数: { config: JSONObject, iconTempUri: String|null } - config为设置页当前的完整配置
     * （与 writeWatermarkConfig 相同结构）；iconTempUri为未保存的暂存图标路径，存在时优先打包。
     */
    @JavascriptInterface
    public void exportSettingsZip(String task_id, String json_data) {
        new Thread(() -> {
            try {
                // [FIX] 在途保护：上一次导出尚未完成（等待用户选择保存位置）时拒绝重复发起
                if (pendingZipTaskId != null) {
                    call_js_callback(task_id, false, null, "上一个导出操作正在进行中");
                    return;
                }
                JSONObject params = new JSONObject(json_data);
                String configData = params.getJSONObject("config").toString();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                    zos.putNextEntry(new ZipEntry(SETTINGS_ZIP_JSON_ENTRY));
                    zos.write(configData.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();

                    // 图标选择：优先未保存的暂存图标(iconTempUri)，其次已提交的自定义图标，
                    // 保证包内图标与设置页当前所见一致；都没有则zip中只有settings.json
                    String iconTempUri = params.optString("iconTempUri", null);
                    File iconFile = null;
                    if (iconTempUri != null && !iconTempUri.isEmpty()) {
                        String iconPath = Uri.parse(iconTempUri).getPath();
                        File iconRoot = new File(activity.getFilesDir(), "watermark/icon");
                        File tempFile = iconPath == null ? null : new File(iconPath);
                        // 只允许读取水印图标目录内的暂存文件，防止任意文件被打包
                        if (SecurityUtils.isUnderDirectory(tempFile, iconRoot) && tempFile.isFile()) {
                            iconFile = tempFile;
                        }
                    }
                    if (iconFile == null) {
                        File committedFile = new File(activity.getFilesDir(), WATERMARK_ICON_RELATIVE_PATH);
                        if (committedFile.exists()) {
                            iconFile = committedFile;
                        }
                    }
                    if (iconFile != null) {
                        zos.putNextEntry(new ZipEntry(SETTINGS_ZIP_ICON_ENTRY));
                        try (InputStream fis = new FileInputStream(iconFile)) {
                            zos.write(readAllBytes(fis));
                        }
                        zos.closeEntry();
                    }
                }

                pendingZipBytes = baos.toByteArray();
                pendingZipTaskId = task_id;

                String fileName = "watermark_settings_"
                        + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".zip";
                activity.runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/zip");
                    intent.putExtra(Intent.EXTRA_TITLE, fileName);
                    createDocumentLauncher.launch(intent);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error in exportSettingsZip()", e);
                pendingZipBytes = null;
                pendingZipTaskId = null;
                call_js_callback(task_id, false, null, "导出失败: " + e.getMessage());
            }
        }).start();
    }

    /**
     * [新增] 导入设置包：读取zip中的settings.json并应用；若包含icon.png则同时替换自定义图标。
     * 参数: { uri: String } - 用户选择的zip文件Uri。
     * 成功时回调返回已应用的配置JSONObject，供设置页直接刷新。
     */
    @JavascriptInterface
    public void importSettingsZip(String task_id, String json_data) {
        new Thread(() -> {
            try {
                JSONObject params = new JSONObject(json_data);
                Uri uri = Uri.parse(params.getString("uri"));

                String settingsJson = null;
                byte[] iconBytes = null;
                try (InputStream is = activity.getContentResolver().openInputStream(uri);
                     ZipInputStream zis = new ZipInputStream(is)) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (SETTINGS_ZIP_JSON_ENTRY.equals(entry.getName())) {
                            settingsJson = new String(readAllBytes(zis), StandardCharsets.UTF_8);
                        } else if (SETTINGS_ZIP_ICON_ENTRY.equals(entry.getName())) {
                            iconBytes = readAllBytes(zis);
                        }
                        zis.closeEntry();
                    }
                }

                if (settingsJson == null) {
                    call_js_callback(task_id, false, null, "zip包中未找到 " + SETTINGS_ZIP_JSON_ENTRY);
                    return;
                }
                JSONObject config = new JSONObject(settingsJson);
                if (!config.has("watermark")) {
                    call_js_callback(task_id, false, null, "settings.json 格式不正确（缺少 watermark 配置）");
                    return;
                }

                // zip包含图标时：写入本地图标目录，并把配置中的图标路径改写为本机路径
                if (iconBytes != null) {
                    File iconFile = new File(activity.getFilesDir(), WATERMARK_ICON_RELATIVE_PATH);
                    File parent = iconFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (OutputStream fos = new FileOutputStream(iconFile)) {
                        fos.write(iconBytes);
                    }
                    JSONObject header = config.getJSONObject("watermark").optJSONObject("header");
                    JSONObject icon = (header != null) ? header.optJSONObject("icon") : null;
                    if (icon != null) {
                        icon.put("value", Uri.fromFile(iconFile).toString());
                    }
                }

                boolean success = WatermarkConfigManager.getInstance(activity).saveConfig(config.toString());
                if (success) {
                    call_js_callback(task_id, true, config, null);
                } else {
                    call_js_callback(task_id, false, null, "配置保存失败");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in importSettingsZip()", e);
                call_js_callback(task_id, false, null, "导入失败: " + e.getMessage());
            }
        }).start();
    }

    /** "创建文档"结果：把内存中的zip字节写入用户选择的位置 */
    private void handleCreateDocumentResult(ActivityResult result) {
        final String currentTaskId = pendingZipTaskId;
        final byte[] zipBytes = pendingZipBytes;
        pendingZipTaskId = null;
        pendingZipBytes = null;
        if (currentTaskId == null) return;

        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call_js_callback(currentTaskId, true, null, "用户取消操作");
            return;
        }
        final Uri targetUri = result.getData().getData();
        new Thread(() -> {
            try (OutputStream os = activity.getContentResolver().openOutputStream(targetUri, "wt")) {
                if (os == null) throw new IOException("无法打开目标文件");
                os.write(zipBytes);
                call_js_callback(currentTaskId, true, new JSONObject(), null);
            } catch (Exception e) {
                Log.e(TAG, "Error writing exported zip", e);
                call_js_callback(currentTaskId, false, null, "保存zip失败: " + e.getMessage());
            }
        }).start();
    }

    /** 读取流中全部字节（ZipInputStream 读取到当前条目末尾即返回-1，可安全使用） */
    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    // endregion

    // region --- 结果处理器 ---
    private void handleActivityResult(ActivityResult result, String type) {
        stopLocationUpdates();
        String current_task_id = this.pendingTaskId;
        this.pendingTaskId = null;

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
        this.pendingTaskId = null;

        if (current_task_id == null) return;
        
        try {
            if (!success) {
                call_js_callback(current_task_id, true, null, "用户取消操作");
                return;
            }
            
            JSONArray results = new JSONArray();
            if (cameraPhotoPath != null) {
                writeLocationToExif(cameraPhotoPath);
                // 使用 cameraPhotoUri
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

    // region --- Activity 启动器 ---
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
        folderPickerLauncher.launch(null);
    }

    private void startCamera() {
        if (activity.getPackageManager().resolveActivity(new Intent(MediaStore.ACTION_IMAGE_CAPTURE), PackageManager.MATCH_DEFAULT_ONLY) == null) {
            call_js_callback(pendingTaskId, false, null, "相机不可用");
            pendingTaskId = null;
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
            pendingTaskId = null;
        }
    }

    private void startCustomCamera() {
        // 启动相机，并把目标目录带进去：照片由 Java 层直接入库到该目录
        Intent intent = new Intent(activity, CameraActivity.class);
        intent.putExtra(CameraActivity.EXTRA_TARGET_DIR_ID, pendingTargetDirId);
        customCameraLauncher.launch(intent);
    }
    // endregion

    // region --- 结果解析器 ---
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

    /**
     * 相机结果：本次会话新增的「库记录」数组。
     * 照片在拍照时就已由 Java 层入库（FileImporter），这里只把记录交给前端刷新列表，
     * 前端用记录里的 real_icon_name 加载缩略图，不再需要拉取任何图片数据。
     */
    private JSONArray handleCustomCameraResult(Intent data) throws JSONException {
        JSONArray records = CameraSessionStore.parseRecords(data);
        if (records.length() > 0) {
            return records;
        }

        // 兼容旧格式（单张照片 + 可选原图，通过 ClipData 返回）
        JSONArray results = new JSONArray();
        if (data != null) {
            if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (uri != null) {
                        results.put(createFileObject(uri));
                    }
                }
            } else if (data.getData() != null) {
                results.put(createFileObject(data.getData()));
            }
        }
        return results;
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
                // 部分设备无 GPS 模块或无 GMS（无 network 提供者），需先检测提供者是否存在，否则会崩溃
                if (locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0.0f, locationListener);
                } else if (locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                    // 设备无 GPS 模块时回退到网络定位
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0.0f, locationListener);
                }
            }
        } catch (IllegalArgumentException | SecurityException e) {
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
        String fileName = IdGenerator.get_id_by_time() + ".jpg";
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
