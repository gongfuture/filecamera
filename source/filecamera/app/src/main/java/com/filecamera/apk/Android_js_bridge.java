package com.filecamera.apk;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;
import android.view.WindowManager; 
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Android_js_bridge {

    // Activity的引用，类型改为AppCompatActivity以支持registerForActivityResult
    private AppCompatActivity activity;
    private WebView webView;
    private final Object lock = new Object();
    private String currentFolderPickerId = null;

    // 将ActivityResultLauncher作为成员变量，由本类自行管理
    private final ActivityResultLauncher<Uri> folderPickerLauncher;

    // 构造函数，接收AppCompatActivity实例
    public Android_js_bridge(AppCompatActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;

        // 在构造函数中注册ActivityResultLauncher
        this.folderPickerLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    // 这个回调会在用户选择文件夹或取消后执行
                    String pickerId = getCurrentFolderPickerId();
                    if (pickerId == null) {
                        return; // 防止意外的回调
                    }

                    if (uri != null) {
                        // 用户成功选择了一个文件夹 (对应旧的 RESULT_OK)
                        handleFolderPickerResultForJsBridge(uri);
                    } else {
                        // 用户取消了选择 (对应旧的 RESULT_CANCELED)
                        // 按照旧逻辑，取消时 success 为 true，data 为 null
                        call_js_callback(pickerId, true, null, null);
                        clearCurrentFolderPickerId();
                    }
                }
        );
    }

    // JavaScript接口方法
    @JavascriptInterface
    public void downloadFile(String url, String fileName, String parentpath, String datatype,String showContent, String id) {
        downloadBlob(url, fileName, parentpath, datatype, showContent, id);
    }

    @JavascriptInterface
    public void startOutWithZip(String id) {
        try {
            outWithZip(id);
        } catch (Exception e) {
            call_js_callback(id, false, null, "压缩操作失败: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void exitapp() {
        activity.runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle("确认退出");
            builder.setMessage("您确定要退出应用吗？");
            builder.setPositiveButton("是", (dialog, which) -> activity.finish());
            builder.setNegativeButton("否", (dialog, which) -> dialog.dismiss());
            builder.show();
        });
    }

    @JavascriptInterface
    public void clearCacheDirectory(String id) {
        clearzip(id);
    }

    @JavascriptInterface
    public void getDirUrl(String id) {
        openFolderPicker(id);
    }

    @JavascriptInterface
    public void fileProcessed() {
        synchronized (lock) {
            lock.notify();
        }
    }

	@JavascriptInterface
		public void startwakelock() {
		// 必须在 UI 线程上修改窗口
		activity.runOnUiThread(() -> {
			activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		});
	}

	@JavascriptInterface
	public void endwakelock() {
		// 必须在 UI 线程上修改窗口
		activity.runOnUiThread(() -> {
			activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		});
	}

    // 统一的JSON回调方法
    public void call_js_callback(String task_id, boolean success, Object data, String error_message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
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
                e.printStackTrace();
            }
        });
    }

    private void downloadBlob(String base64String, String fileName, String parentpath, String datatype,String showContent, String id){
        new Thread(() -> {
            try {
                byte[] decodedBytes;
                if ("file".equals(datatype)) {
                    decodedBytes = Base64.decode(base64String, Base64.DEFAULT);
                } else {
                    decodedBytes = new byte[0];
                }

                ContentValues values = new ContentValues();
                String customFolder = "file_camera/cache";
                String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + customFolder + parentpath;
                ContentResolver resolver = activity.getContentResolver();

                if("file".equals(datatype)){
                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);

                    String selection = MediaStore.Downloads.DISPLAY_NAME + " = ? AND " + MediaStore.Downloads.RELATIVE_PATH + " = ?";
                    String[] selectionArgs = new String[] { fileName, relativePath + "/" }; // 精确匹配

                    // 先删除可能的旧记录
                    resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, selection, selectionArgs);

                    // 插入新的文件记录
                    Uri fileUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (fileUri != null) {
                        try (OutputStream outputStream = resolver.openOutputStream(fileUri)) {
                            if (outputStream != null) {
                                outputStream.write(decodedBytes);
                                outputStream.flush();
                            } else {
                                throw new IOException("无法打开输出流");
                            }
                        }
                    } else {
                        throw new IOException("创建文件失败 (MediaStore aPI 返回 null Uri)");
                    }
                } else if ("dir".equals(datatype)) {
                    // 创建文件夹通常是通过创建 .nomedia 文件来暗示
                    values.put(MediaStore.Downloads.DISPLAY_NAME, ".nomedia");
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                }

                call_js_callback(id, true, "下载完成", null);
                if (showContent != null && !showContent.isEmpty()) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, showContent, Toast.LENGTH_SHORT).show());
                }

            } catch (Exception e) {
                call_js_callback(id, false, null, "下载出现错误: " + e.getMessage());
                activity.runOnUiThread(() -> Toast.makeText(activity, "下载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void outWithZip(String id) {
        new Thread(() -> {
            try {
                String cacheDirPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/file_camera/cache/";
                String targetDirPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/file_camera/";
                File cacheDir = new File(cacheDirPath);

                if (!cacheDir.exists() || !cacheDir.isDirectory()) {
                    call_js_callback(id, false, null, "源文件夹不存在");
                    activity.runOnUiThread(() -> Toast.makeText(activity, "源文件夹不存在", Toast.LENGTH_SHORT).show());
                    return;
                }

                moveFiles(cacheDir, new File(targetDirPath));

                File[] cacheFiles = cacheDir.listFiles();
                if (cacheFiles != null) {
                    for (File folder : cacheFiles) {
                        if (folder.isDirectory()) {
                            String zipFileName = folder.getName() + ".zip";
                            createZipWithMediaStore(folder, zipFileName);
                        }
                    }
                }

                deleteDirectories(cacheDir);
                call_js_callback(id, true, "已导出到 " + targetDirPath, null);

                activity.runOnUiThread(() -> Toast.makeText(activity, "文件导出完成！", Toast.LENGTH_SHORT).show());

            } catch (Exception e) {
                call_js_callback(id, false, null, "导出过程出现异常: " + e.getMessage());
                activity.runOnUiThread(() -> Toast.makeText(activity, "压缩失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void createZipWithMediaStore(File folder, String zipFileName) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, zipFileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/file_camera/");

        ContentResolver resolver = activity.getContentResolver();
        String selection = MediaStore.Downloads.DISPLAY_NAME + " = ? AND " + MediaStore.Downloads.RELATIVE_PATH + " = ?";
        String[] selectionArgs = new String[] { zipFileName, Environment.DIRECTORY_DOWNLOADS + "/file_camera/" };

        resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, selection, selectionArgs);

        Uri zipUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

        if (zipUri != null) {
            try (OutputStream outputStream = resolver.openOutputStream(zipUri);
                 ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(outputStream))) {
                addFolderToZip(zos, folder);
            }
        } else {
            throw new IOException("创建ZIP文件失败 (MediaStore aPI 返回 null Uri)");
        }
    }

    private void moveFiles(File sourceDir, File targetDir) {
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        File[] sourceFiles = sourceDir.listFiles();
        if (sourceFiles != null) {
            for (File file : sourceFiles) {
                if (file.isFile()) {
                    try {
                        Files.move(file.toPath(), new File(targetDir, file.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void addFolderToZip(ZipOutputStream zos, File rootFolder) throws IOException {
        Deque<File> folderStack = new ArrayDeque<>();
        folderStack.push(rootFolder);
        while (!folderStack.isEmpty()) {
            File currentFolder = folderStack.pop();
            String currentPathInZip = rootFolder.equals(currentFolder) ? "" : currentFolder.getAbsolutePath().substring(rootFolder.getAbsolutePath().length() + 1).replace(File.separator, "/");
            File[] files = currentFolder.listFiles();
            if (files != null) {
                for (File file : files) {
                    String zipEntryName = currentPathInZip.isEmpty() ? file.getName() : currentPathInZip + "/" + file.getName();
                    if (file.isDirectory()) {
                        folderStack.push(file);
                    } else {
                        try (FileInputStream fis = new FileInputStream(file)) {
                            ZipEntry zipEntry = new ZipEntry(zipEntryName);
                            zos.putNextEntry(zipEntry);
                            byte[] buffer = new byte[4096];
                            int bytesRead;
                            while ((bytesRead = fis.read(buffer)) > 0) {
                                zos.write(buffer, 0, bytesRead);
                            }
                            zos.closeEntry();
                        }
                        file.delete();
                    }
                }
                if (files.length == 0 && !currentPathInZip.isEmpty()) {
                    ZipEntry zipEntry = new ZipEntry(currentPathInZip + "/");
                    zos.putNextEntry(zipEntry);
                    zos.closeEntry();
                }
            }
        }
    }

    private boolean deleteDirectories(File directory) {
        if (directory == null || !directory.exists()) return true;
        if (!directory.isDirectory()) return directory.delete();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteDirectories(file);
            }
        }
        return directory.delete();
    }

    private void clearzip(String id){
        new Thread(() -> {
            try {
                File cacheDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "/file_camera/cache/");
                if (deleteDirectories(cacheDir)) {
                    call_js_callback(id, true, "缓存目录已清空", null);
                } else {
                    call_js_callback(id, false, null, "清空缓存目录时出错");
                }
            } catch (Exception e) {
                call_js_callback(id, false, null, "清空缓存目录时发生异常: " + e.getMessage());
            }
        }).start();
    }

    // 使用新的ActivityResultLauncher API打开文件夹选择器
    private void openFolderPicker(String id) {
        currentFolderPickerId = id;
        // 直接启动在本类中注册的launcher
        folderPickerLauncher.launch(null);
    }

    public String getCurrentFolderPickerId() {
        return currentFolderPickerId;
    }

    public void clearCurrentFolderPickerId() {
        this.currentFolderPickerId = null;
    }

    public void handleFolderPickerResultForJsBridge(Uri folderUri) {
        String pickerId = getCurrentFolderPickerId();
        if (pickerId == null) {
            return;
        }
        try {
            DocumentFile pickedFolder = DocumentFile.fromTreeUri(activity, folderUri);
            if (pickedFolder == null) {
                throw new Exception("无法访问所选文件夹。");
            }

            Uri[] all_uri = traverseFolder(pickedFolder);
            List<String> relativePaths = new ArrayList<>();
            String rootDocumentId = DocumentsContract.getTreeDocumentId(pickedFolder.getUri());
            String dir_name = pickedFolder.getName();

            for (Uri fileUri : all_uri) {
                String fileDocumentId = DocumentsContract.getDocumentId(fileUri);
                // 确保我们只处理根目录下的路径
                if (fileDocumentId.startsWith(rootDocumentId + ":")) {
                    String relativePath = fileDocumentId.substring((rootDocumentId + ":").length());
                    relativePaths.add(dir_name + "/" + relativePath);
                }
            }

            JSONArray jsonArray = new JSONArray(relativePaths);
            String jsonString = jsonArray.toString();
            byte[] jsonString_data = jsonString.getBytes("UTF-8");
            String base64String = Base64.encodeToString(jsonString_data, Base64.NO_WRAP);

            call_js_callback(pickerId, true, base64String, null);

        } catch (Exception e) {
            call_js_callback(pickerId, false, null, "文件路径获取错误：" + e.getMessage());
        } finally {
            // 无论成功还是失败，都清除ID
            clearCurrentFolderPickerId();
        }
    }

    public Uri[] getUrisFromFolder(Uri folderUri) {
        if (folderUri == null) return new Uri[0];
        DocumentFile pickedFolder = DocumentFile.fromTreeUri(activity, folderUri);
        if (pickedFolder == null || !pickedFolder.isDirectory()) return new Uri[0];
        return traverseFolder(pickedFolder);
    }

    private Uri[] traverseFolder(DocumentFile rootFolder) {
        List<Uri> uriList = new ArrayList<>();
        Deque<DocumentFile> stack = new ArrayDeque<>();
        stack.push(rootFolder);
        while (!stack.isEmpty()) {
            DocumentFile currentFolder = stack.pop();
            DocumentFile[] folderFiles = currentFolder.listFiles();
            if (folderFiles != null) {
                for (DocumentFile file : folderFiles) {
                    if (file.isFile()) {
                        uriList.add(file.getUri());
                    } else if (file.isDirectory()) {
                        stack.push(file);
                    }
                }
            }
        }
        return uriList.toArray(new Uri[0]);
    }

    public void onSaveInstanceState(Bundle outState) {
        if (currentFolderPickerId != null) {
            outState.putString("currentFolderPickerId_js_bridge", currentFolderPickerId);
        }
    }

    public void onRestoreInstanceState(Bundle savedInstanceState) {
        currentFolderPickerId = savedInstanceState.getString("currentFolderPickerId_js_bridge");
    }
}
