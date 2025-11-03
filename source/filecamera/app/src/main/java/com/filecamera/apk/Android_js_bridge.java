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
// [新增] 导入 MimeTypeMap
import android.webkit.MimeTypeMap;

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
// [移除] 移除不再需要的 import
// import java.nio.file.Files;
// import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.charset.StandardCharsets; // [新增] 导入 Charset

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
            // [修改] 调用重构后的 outWithZip
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
        // [修改] 调用重构后的 clearzip
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

    // [重构] 采用 "混合存储" 逻辑
    private void downloadBlob(String base64String, String fileName, String parentpath ,String datatype){
        new Thread(() -> {
            try {
                // [修改] 将 Base64 解码移到顶部，确保两个分支都能访问 decodedBytes
                byte[] decodedBytes = decodeBase64(base64String); 

                if ("file".equals(datatype)) {
                    // --- 1. "file" 类型: 直接写入公共 "Downloads" 目录 ---
                    // 注意: MainActivity.this.getContentResolver() 或 activity.getContentResolver()
                    ContentResolver resolver = getContentResolver(); // 或者 activity.getContentResolver();
                    ContentValues values = new ContentValues();
                    String customFolder = "road_manage"; // 你指定的公共下载子文件夹
                    String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + customFolder + (parentpath == null ? "" : parentpath);

                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    
                    // 自动检测 MimeType
                    String mimeType = "application/octet-stream";
                    String extension = MimeTypeMap.getFileExtensionFromUrl(fileName);
                    if (extension != null) {
                        String mimeFromExtension = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
                        if (mimeFromExtension != null) {
                            mimeType = mimeFromExtension;
                        }
                    }
                    values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
                    // 标记为下载完成
                    values.put(MediaStore.Downloads.IS_PENDING, 0);

                    // 检查并删除已存在的文件，确保覆盖
                    Uri existingUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    String selection = MediaStore.Downloads.RELATIVE_PATH + " = ? AND " + MediaStore.Downloads.DISPLAY_NAME + " = ?";
                    String[] selectionArgs = new String[]{relativePath + "/", fileName}; // MediaStore 路径需要以 / 结尾
                    
                    try (Cursor cursor = resolver.query(existingUri, new String[]{MediaStore.Downloads._ID}, selection, selectionArgs, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            long id = cursor.getLong(0);
                            Uri deleteUri = ContentUris.withAppendedId(existingUri, id);
                            try {
                                resolver.delete(deleteUri, null, null);
                            } catch (Exception e) {
                                // 即使删除失败（例如文件被锁定），也尝试继续写入，可能会覆盖
                                Log.w("DownloadBlob", "Failed to delete existing file, attempting overwrite.", e);
                            }
                        }
                    }

                    // 插入新文件
                    Uri fileUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (fileUri == null) {
						throw new IOException("MediaStore 无法创建文件");
					}

                    // 写入数据
                    try (OutputStream os = resolver.openOutputStream(fileUri)) {
                        if (os == null) {
							throw new IOException("无法打开输出流");
						}
                        os.write(decodedBytes);
                    }
                    // 注意: MainActivity.this.runOnUiThread()
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "文件已保存到 " + relativePath, Toast.LENGTH_SHORT).show());

                } else if ("dir".equals(datatype)) {
                    File storageDir = new File(getExternalFilesDir(null), "road_manage/cache" + (parentpath == null ? "" : parentpath));
                    
                    if (!storageDir.exists()) {
                        if (!storageDir.mkdirs()) {
                            throw new IOException("无法创建私有缓存目录: " + storageDir.getAbsolutePath());
                        }
                    }

                    File targetFile = new File(storageDir, fileName); // [新增] 创建目标文件
                    
                    // 写入数据到私有文件
                    try (FileOutputStream fos = new FileOutputStream(targetFile)) { // [新增]
                        fos.write(decodedBytes); // [新增]
                    }

                } else {
                    // 如果 datatype 既不是 "file" 也不是 "dir"，则抛出错误
                    throw new IllegalArgumentException("不支持的 datatype: " + (datatype == null ? "null" : datatype));
                }

                // 通知 JS 成功 (仅在所有操作成功后触发一次)
                // 注意: MainActivity.this.runOnUiThread() 和 webView
                runOnUiThread(() -> webView.evaluateJavascript("window.onDownloadComplete()", null));

            } catch (Exception e) {
                // 注意: MainActivity.this.runOnUiThread() 和 webView
                Log.e("DownloadBlobError", "downloadBlob 出错", e);
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    String errorMessage = (e.getMessage() != null ? e.getMessage().replace("'", "\\'") : "Unknown error");
                    webView.evaluateJavascript("window.onDownloadError('" + errorMessage + "')", null);
                });
            }
		}).start();
	}
    // [重构] 从 "私有" 压缩到 "公共"
    private void outWithZip(String id) {
        new Thread(() -> {
            try {
                // [修改] 源路径: 私有缓存
                File cacheDir = new File(activity.getExternalFilesDir(null), "file_camera/cache");
                // [修改] 目标路径 (公共)
                String targetPublicRelativePath = Environment.DIRECTORY_DOWNLOADS + "/file_camera";

                if (!cacheDir.exists() || !cacheDir.isDirectory()) {
                    call_js_callback(id, false, null, "源文件夹 (App 缓存) 不存在");
                    activity.runOnUiThread(() -> Toast.makeText(activity, "源文件夹不存在", Toast.LENGTH_SHORT).show());
                    return;
                }

                // [移除] moveFiles(...) - 不再需要

                File[] cacheFiles = cacheDir.listFiles();
                if (cacheFiles == null || cacheFiles.length == 0) {
                     call_js_callback(id, true, "缓存目录为空，无需导出", null);
                     activity.runOnUiThread(() -> Toast.makeText(activity, "缓存目录为空", Toast.LENGTH_SHORT).show());
                     return;
                }

                for (File folder : cacheFiles) {
                    if (folder.isDirectory()) {
                        String zipFileName = folder.getName() + ".zip";
                        // [修改] 传入公共目标路径
                        createZipWithMediaStore(folder, zipFileName, targetPublicRelativePath);
                    }
                }

                deleteDirectories(cacheDir); // 清理私有缓存
                
                // [修改] 更新 Toast 消息
                call_js_callback(id, true, "已导出到 " + targetPublicRelativePath, null);
                activity.runOnUiThread(() -> Toast.makeText(activity, "文件导出完成！", Toast.LENGTH_SHORT).show());

            } catch (Exception e) {
                call_js_callback(id, false, null, "导出过程出现异常: " + e.getMessage());
                activity.runOnUiThread(() -> Toast.makeText(activity, "压缩失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // [修改] 签名增加了 targetPublicRelativePath
    private void createZipWithMediaStore(File folder, String zipFileName, String targetPublicRelativePath) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, zipFileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        // [修改] 使用传入的参数
        values.put(MediaStore.Downloads.RELATIVE_PATH, targetPublicRelativePath);
        values.put(MediaStore.Downloads.IS_PENDING, 1); // 标记为正在写入

        ContentResolver resolver = activity.getContentResolver();
        String selection = MediaStore.Downloads.DISPLAY_NAME + " = ? AND " + MediaStore.Downloads.RELATIVE_PATH + " = ?";
        // [修改] 使用传入的参数 (并确保路径以 / 结尾)
        String[] selectionArgs = new String[] { zipFileName, targetPublicRelativePath + "/" };

        // [修改] 改进的删除逻辑
        try (Cursor cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Downloads._ID}, selection, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long fileId = cursor.getLong(0);
                Uri deleteUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, fileId);
                resolver.delete(deleteUri, null, null);
            }
        }

        Uri zipUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

        if (zipUri != null) {
            try (OutputStream outputStream = resolver.openOutputStream(zipUri);
                 ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(outputStream))) {
                
                // [修改] 确保调用的是 *新* 替换的 addFolderToZip
                addFolderToZip(zos, folder);
            }

            // [新增] 写入完成后，更新 IS_PENDING 状态
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(zipUri, values, null, null);

        } else {
            throw new IOException("创建ZIP文件失败 (MediaStore API 返回 null Uri)");
        }
    }

    // [替换] 使用上一个项目中更健壮的 addFolderToZip 版本
    private void addFolderToZip(ZipOutputStream zos, File rootFolder) throws IOException {
		Deque<File> folderStack = new ArrayDeque<>();
		folderStack.push(rootFolder);
        // [修改] 基础路径应为 rootFolder 本身，以便 Zip 包内的路径是相对的
		String rootPath = rootFolder.getPath();

		while (!folderStack.isEmpty()) {
			File currentFolder = folderStack.pop();
			File[] files = currentFolder.listFiles();
			if (files == null) continue;

            // [修改] 计算相对路径
            String relativePath = currentFolder.getPath().substring(rootPath.length()).replace(File.separator, "/");
            // 移除开头的 "/"
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }

            // [修改] 确保在文件列表之前添加目录条目 (处理空文件夹)
            if (!relativePath.isEmpty() && files.length == 0) {
                zos.putNextEntry(new ZipEntry(relativePath + "/"));
                zos.closeEntry();
            }

			for (File file : files) {
                // [修改] 计算条目路径
                String entryPath = file.getPath().substring(rootPath.length()).replace(File.separator, "/");
                if (entryPath.startsWith("/")) {
                    entryPath = entryPath.substring(1);
                }

				if (file.isDirectory()) {
                    // [修改] 显式添加目录条目
                    zos.putNextEntry(new ZipEntry(entryPath + "/"));
                    zos.closeEntry();
					folderStack.push(file);
				} else {
					boolean isfiledeleted = false;
					try (FileInputStream fis = new FileInputStream(file)) {
						ZipEntry zipEntry = new ZipEntry(entryPath);
						zos.putNextEntry(zipEntry);
						byte[] buffer = new byte[8192]; // [修改] 增加缓冲区
						int bytesRead;
						while ((bytesRead = fis.read(buffer)) != -1) { // [修改] 修正循环
							zos.write(buffer, 0, bytesRead);
						}
						zos.closeEntry();
						isfiledeleted = true;
					}
					if (isfiledeleted) {
						if (!file.delete()) {
							// Log.e("DeleteFileError", "Failed to delete file: " + file.getAbsolutePath());
						}
					}
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

    // [重构] clearzip 指向私有缓存
    private void clearzip(String id){
        new Thread(() -> {
            try {
                // [修改] 指向私有缓存
                File cacheDir = new File(activity.getExternalFilesDir(null), "file_camera/cache");
                if (!cacheDir.exists() || !cacheDir.isDirectory()) {
                    call_js_callback(id, true, "缓存目录已是空的", null);
                    return;
                }
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
            // [新增] 持久化权限
            final int takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            activity.getContentResolver().takePersistableUriPermission(folderUri, takeFlags);
            
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
                // [修改] 修复相对路径逻辑 (原版逻辑是对的，但要确保健壮)
                if (fileDocumentId != null && fileDocumentId.startsWith(rootDocumentId)) {
                    String relativePath = fileDocumentId.substring(rootDocumentId.length());
                    if (relativePath.startsWith(":")) {
                        relativePath = relativePath.substring(1);
                    }
                    relativePaths.add(dir_name + "/" + relativePath);
                }
            }

            JSONArray jsonArray = new JSONArray(relativePaths);
            String jsonString = jsonArray.toString();
            // [修改] 明确使用 UTF-8
            byte[] jsonString_data = jsonString.getBytes(StandardCharsets.UTF_8);
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
        if (rootFolder != null) {
            stack.push(rootFolder);
        }
        while (!stack.isEmpty()) {
            DocumentFile currentFolder = stack.pop();
            if (currentFolder == null) continue;
            DocumentFile[] folderFiles = currentFolder.listFiles();
            if (folderFiles != null) {
                for (DocumentFile file : folderFiles) {
                    if (file == null) continue;
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
    private byte[] decodeBase64(String base64Data) {
        if (base64Data == null) {
            return new byte[0];
        }
        String base64String;
        if (base64Data.contains(",")) {
            base64String = base64Data.substring(base64Data.indexOf(',') + 1);
        } else {
            base64String = base64Data;
        }
        return Base64.decode(base64String, Base64.DEFAULT);
    }
}