package com.filecamera.apk;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 拍照会话（Capture Session）存储与交接层。
 *
 * <p>架构说明：相机不再「拍一张就退出并回传一张」，而是：
 * <ol>
 *     <li>进入相机时通过 {@link #beginSession(Context)} 开启一次会话，本次会话拍摄的所有照片都落在
 *         会话专属目录 {@code .../Pictures/photo_cache/session_xxx/} 下（互不覆盖、互不删除）；</li>
 *     <li>每拍完一张，{@link #addCapture(Context, File, File)} 把「新增图片信息」（文件名 / 路径 / content URI /
 *         大小 / 时间，以及可选的无水印原图）追加进会话清单，并实时落盘为 {@code session.json}；</li>
 *     <li>用户手动退出相机时，{@link #buildResultIntent(Context)} 把整份会话清单随 Activity result 回传，
 *         前端拿到清单后再自行「拉取」图片数据（逐张 content URI 上传入库）；</li>
 *     <li>前端导入完成后可调用 {@link #clearCurrent(Context)} 清理会话目录；未清理的会话由
 *         {@link #gcOldSessions(Context)} 按 24 小时 TTL 兜底回收。</li>
 * </ol>
 *
 * <p>清单同时落盘，因此即便 Activity result 丢失（进程被回收后重建等极端情况），
 * 前端仍可通过 {@link #readPendingSession(Context)} 找回本次新增图片的信息。
 */
public final class CameraSessionStore {

    /** Activity result 中携带会话清单的 key。 */
    public static final String EXTRA_SESSION_JSON = "com.filecamera.apk.extra.CAMERA_SESSION_JSON";

    private static final String TAG = "CameraSessionStore";

    /** 会话根目录（位于 getExternalFilesDir(Pictures) 下，已被 FileProvider 的 my_images 路径覆盖）。 */
    private static final String SESSION_ROOT_DIR = "photo_cache";
    private static final String SESSION_DIR_PREFIX = "session_";
    private static final String MANIFEST_FILE_NAME = "session.json";
    /** 未被前端确认清理的会话目录保留时长，超时由 GC 回收。 */
    private static final long SESSION_TTL_MS = 24L * 60 * 60 * 1000;

    private static final Object LOCK = new Object();

    // 当前会话（进程内单例状态，Activity 重建时可延续；进程被杀后由磁盘清单兜底）
    private static String currentSessionId = null;
    private static File currentSessionDir = null;
    private static long currentCreatedAt = 0;
    private static final JSONArray currentCaptures = new JSONArray();

    private CameraSessionStore() {
    }

    // region --- 会话生命周期 ---

    /**
     * 开启（或延续）一次拍照会话。
     * 同一进程内若已存在未结束的会话则直接复用，避免 Activity 重建时丢失已拍照片。
     */
    public static File beginSession(Context context) throws IOException {
        synchronized (LOCK) {
            if (currentSessionDir != null && currentSessionDir.isDirectory()) {
                return currentSessionDir;
            }
            File root = getSessionRoot(context);
            if (!root.exists() && !root.mkdirs()) {
                throw new IOException("无法创建拍照会话目录: " + root.getAbsolutePath());
            }
            long now = System.currentTimeMillis();
            String sessionId = SESSION_DIR_PREFIX + now + "_" + Math.abs(IdGenerator.getIdByTime().hashCode());
            File dir = new File(root, sessionId);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建拍照会话目录: " + dir.getAbsolutePath());
            }
            currentSessionId = sessionId;
            currentSessionDir = dir;
            currentCreatedAt = now;
            resetCapturesLocked();
            persistManifestLocked();
            Log.i(TAG, "拍照会话已开启: " + dir.getAbsolutePath());
            gcOldSessionsAsync(context);
            return dir;
        }
    }

    /** 在会话目录中为下一张照片创建占位文件（每次调用都是新文件名，不会删除已拍照片）。 */
    public static File createPhotoFile(Context context) throws IOException {
        File dir;
        synchronized (LOCK) {
            dir = currentSessionDir;
        }
        if (dir == null) {
            dir = beginSession(context);
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建拍照会话目录: " + dir.getAbsolutePath());
        }
        return new File(dir, IdGenerator.getIdByTime() + ".jpg");
    }

    /**
     * 记录一张已处理完成的新照片。
     *
     * @param photoFile    水印图文件
     * @param originalFile 无水印原图文件，可为 null
     */
    public static void addCapture(Context context, File photoFile, File originalFile) {
        if (photoFile == null || !photoFile.isFile()) return;
        synchronized (LOCK) {
            try {
                JSONObject capture = new JSONObject();
                capture.put("index", currentCaptures.length());
                capture.put("time", System.currentTimeMillis());
                capture.put("name", photoFile.getName());
                capture.put("path", photoFile.getAbsolutePath());
                capture.put("uri", fileToUri(context, photoFile));
                capture.put("size", photoFile.length());
                if (originalFile != null && originalFile.isFile()) {
                    JSONObject original = new JSONObject();
                    original.put("name", originalFile.getName());
                    original.put("path", originalFile.getAbsolutePath());
                    original.put("uri", fileToUri(context, originalFile));
                    original.put("size", originalFile.length());
                    capture.put("original", original);
                }
                currentCaptures.put(capture);
                persistManifestLocked();
            } catch (JSONException e) {
                Log.e(TAG, "记录新照片失败", e);
            }
        }
    }

    /** 当前会话已保存的照片张数。 */
    public static int getCaptureCount() {
        synchronized (LOCK) {
            return currentCaptures.length();
        }
    }

    /**
     * 结束内存中的会话状态（不删除文件）。
     * 照片已在 {@link #buildResultIntent(Context)} 里交给前端，目录需保留到前端拉取完成。
     */
    public static void closeSession() {
        synchronized (LOCK) {
            currentSessionId = null;
            currentSessionDir = null;
            currentCreatedAt = 0;
            resetCapturesLocked();
        }
    }

    /** 清空并删除当前会话（前端确认导入完成后调用，或本次会话没有任何照片时调用）。 */
    public static void clearCurrent(Context context) {
        File dir;
        synchronized (LOCK) {
            dir = currentSessionDir;
            currentSessionId = null;
            currentSessionDir = null;
            currentCreatedAt = 0;
            resetCapturesLocked();
        }
        if (dir != null) {
            deleteRecursively(dir);
            Log.i(TAG, "拍照会话目录已清理: " + dir.getAbsolutePath());
        }
    }

    /**
     * 删除指定会话的目录（前端确认图片已全部拉取/入库完成后调用）。
     * 正在拍摄中的会话不会被删除。
     */
    public static void discardSession(Context context, String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) return;
        // 防目录穿越
        if (sessionId.contains("/") || sessionId.contains("\\") || sessionId.contains("..")) return;
        File dir = new File(getSessionRoot(context), sessionId);
        synchronized (LOCK) {
            if (currentSessionDir != null && currentSessionDir.equals(dir)) {
                return;
            }
        }
        if (deleteRecursively(dir)) {
            Log.i(TAG, "拍照会话目录已删除: " + dir.getAbsolutePath());
        }
    }

    private static void resetCapturesLocked() {
        while (currentCaptures.length() > 0) {
            currentCaptures.remove(0);
        }
    }

    // endregion

    // region --- 交接给前端 ---

    /** 生成完整的会话清单（新增图片信息），供前端按需拉取。 */
    public static JSONObject snapshot(Context context) {
        synchronized (LOCK) {
            JSONObject session = new JSONObject();
            try {
                session.put("session_id", currentSessionId == null ? "" : currentSessionId);
                session.put("created_at", currentCreatedAt);
                session.put("count", currentCaptures.length());
                session.put("captures", new JSONArray(currentCaptures.toString()));
            } catch (JSONException e) {
                Log.e(TAG, "构建会话清单失败", e);
            }
            return session;
        }
    }

    /** 把会话清单放进 Activity result，同时用 ClipData 携带所有图片 URI 以兼容旧的读取方式。 */
    public static Intent buildResultIntent(Context context) {
        JSONObject session = snapshot(context);
        // 标记为「已交付」：若后续 result 丢失，兜底恢复逻辑不会把同一批照片重复交给前端
        markDeliveredLocked(session);
        Intent intent = new Intent();
        intent.putExtra(EXTRA_SESSION_JSON, session.toString());
        try {
            JSONArray captures = session.optJSONArray("captures");
            Uri first = null;
            android.content.ClipData clipData = null;
            if (captures != null) {
                for (int i = 0; i < captures.length(); i++) {
                    JSONObject capture = captures.optJSONObject(i);
                    if (capture == null) continue;
                    Uri uri = Uri.parse(capture.optString("uri", ""));
                    if (uri == null || uri.toString().isEmpty()) continue;
                    if (first == null) {
                        first = uri;
                        clipData = android.content.ClipData.newRawUri("photos", uri);
                    } else {
                        clipData.addItem(new android.content.ClipData.Item(uri));
                    }
                    JSONObject original = capture.optJSONObject("original");
                    if (original != null) {
                        String originalUri = original.optString("uri", "");
                        if (!originalUri.isEmpty()) {
                            clipData.addItem(new android.content.ClipData.Item(Uri.parse(originalUri)));
                        }
                    }
                }
            }
            if (first != null) {
                intent.setData(first);
                intent.setClipData(clipData);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (Exception e) {
            Log.e(TAG, "构建会话 result 失败", e);
        }
        return intent;
    }

    /** 从 Activity result 中解析会话清单，非本会话的 result 返回 null。 */
    public static JSONObject parseSession(Intent data) {
        if (data == null) return null;
        String raw = data.getStringExtra(EXTRA_SESSION_JSON);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            Log.e(TAG, "解析会话清单失败", e);
            return null;
        }
    }

    /**
     * 把会话清单拍平为前端既有的文件对象数组：[{name, uri, type, capture_index}]。
     * 顺序与拍照顺序一致，开启「保存无水印原图」时每张照片后紧跟其原图（与旧的 ClipData 顺序一致）。
     */
    public static JSONArray toFileObjects(JSONObject session) {
        JSONArray result = new JSONArray();
        if (session == null) return result;
        String sessionId = session.optString("session_id", "");
        JSONArray captures = session.optJSONArray("captures");
        if (captures == null) return result;
        for (int i = 0; i < captures.length(); i++) {
            JSONObject capture = captures.optJSONObject(i);
            if (capture == null) continue;
            try {
                JSONObject file = new JSONObject();
                file.put("name", capture.optString("name", ""));
                file.put("uri", capture.optString("uri", ""));
                file.put("type", "file");
                file.put("capture_index", capture.optInt("index", i));
                file.put("size", capture.optLong("size", 0));
                file.put("session_id", sessionId);
                result.put(file);

                JSONObject original = capture.optJSONObject("original");
                if (original != null) {
                    JSONObject originalFile = new JSONObject();
                    originalFile.put("name", original.optString("name", ""));
                    originalFile.put("uri", original.optString("uri", ""));
                    originalFile.put("type", "file");
                    originalFile.put("capture_index", capture.optInt("index", i));
                    originalFile.put("size", original.optLong("size", 0));
                    originalFile.put("is_original", true);
                    originalFile.put("session_id", sessionId);
                    result.put(originalFile);
                }
            } catch (JSONException e) {
                Log.e(TAG, "构建文件对象失败", e);
            }
        }
        return result;
    }

    /**
     * 兜底读取磁盘上最近一次「尚未交付」的会话清单，并标记为已交付。
     * 用于两种场景：
     * 1) Activity result 丢失（进程重建等）；
     * 2) 拍照过程中进程被杀/崩溃，下次启动时由 MainActivity 主动补交给前端。
     * 已交付过的会话不会被再次返回，避免同一批照片被重复导入。
     */
    public static JSONObject readPendingSession(Context context) {
        File root = getSessionRoot(context);
        File[] dirs = root.listFiles();
        if (dirs == null) return null;
        File latest = null;
        long latestTime = -1;
        for (File dir : dirs) {
            if (!dir.isDirectory() || !dir.getName().startsWith(SESSION_DIR_PREFIX)) continue;
            if (dir.lastModified() > latestTime) {
                latestTime = dir.lastModified();
                latest = dir;
            }
        }
        if (latest == null) return null;
        JSONObject manifest = readManifest(latest);
        if (manifest == null) return null;
        if (manifest.optInt("count", 0) <= 0) return null;
        if (manifest.optBoolean("delivered", false)) return null;
        markSessionDelivered(latest, manifest);
        return manifest;
    }

    // endregion

    // region --- 磁盘清单与回收 ---

    private static File getSessionRoot(Context context) {
        File picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (picturesDir == null) {
            picturesDir = new File(context.getFilesDir(), "photo_cache");
        }
        return new File(picturesDir, SESSION_ROOT_DIR);
    }

    private static void persistManifestLocked() {
        if (currentSessionDir == null) return;
        try {
            JSONObject manifest = new JSONObject();
            manifest.put("session_id", currentSessionId == null ? "" : currentSessionId);
            manifest.put("created_at", currentCreatedAt);
            manifest.put("count", currentCaptures.length());
            manifest.put("captures", new JSONArray(currentCaptures.toString()));
            writeManifest(new File(currentSessionDir, MANIFEST_FILE_NAME), manifest);
        } catch (Exception e) {
            Log.e(TAG, "写入会话清单失败", e);
        }
    }

    /** 交付时在磁盘清单上打「已交付」标记，防止兜底恢复时重复交付。 */
    private static void markDeliveredLocked(JSONObject session) {
        File dir;
        synchronized (LOCK) {
            dir = currentSessionDir;
        }
        if (dir == null) return;
        markSessionDelivered(dir, session);
    }

    /** 给指定会话目录的清单打「已交付」标记（同时更新传入的清单对象）。 */
    private static void markSessionDelivered(File sessionDir, JSONObject session) {
        if (sessionDir == null || session == null) return;
        try {
            session.put("delivered", true);
            writeManifest(new File(sessionDir, MANIFEST_FILE_NAME), session);
        } catch (Exception e) {
            Log.e(TAG, "标记会话已交付失败", e);
        }
    }

    private static void writeManifest(File target, JSONObject manifest) throws IOException {
        byte[] payload = manifest.toString().getBytes(StandardCharsets.UTF_8);
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(payload);
            out.flush();
        }
        if (!temp.renameTo(target)) {
            // 极少数文件系统不支持覆盖式 rename，退化为直接写
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(payload);
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static JSONObject readManifest(File sessionDir) {
        if (sessionDir == null) return null;
        File manifest = new File(sessionDir, MANIFEST_FILE_NAME);
        if (!manifest.isFile()) return null;
        try (InputStream in = new FileInputStream(manifest)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "读取会话清单失败", e);
            return null;
        }
    }

    private static void gcOldSessionsAsync(Context context) {
        final Context appContext = context.getApplicationContext();
        new Thread(() -> gcOldSessions(appContext)).start();
    }

    /** 回收超过 TTL 的历史会话目录（当前会话除外）。前端导入完成后会主动清理，此处仅兜底。 */
    public static void gcOldSessions(Context context) {
        File root = getSessionRoot(context);
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        long now = System.currentTimeMillis();
        for (File dir : dirs) {
            if (!dir.isDirectory() || !dir.getName().startsWith(SESSION_DIR_PREFIX)) continue;
            synchronized (LOCK) {
                if (currentSessionDir != null && currentSessionDir.equals(dir)) continue;
            }
            if (now - dir.lastModified() < SESSION_TTL_MS) continue;
            if (deleteRecursively(dir)) {
                Log.i(TAG, "已回收过期拍照会话目录: " + dir.getAbsolutePath());
            }
        }
    }

    private static boolean deleteRecursively(File target) {
        if (target == null || !target.exists()) return true;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return target.delete();
    }

    private static String fileToUri(Context context, File file) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        return uri.toString();
    }

    // endregion
}
