package com.filecamera.apk;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 拍照会话（Capture Session）。
 *
 * <p>职责已经收窄成两件事：
 * <ol>
 *     <li><b>临时目录</b>：为 CameraX 提供本次拍摄的落盘位置（合成在临时目录内原地完成）；</li>
 *     <li><b>新增记录清单</b>：每张照片合成完后立刻由 {@link FileImporter} 入库，
 *         入库返回的库记录（id/name/real_file_name/...）追加到清单，退出时整份交给前端，
 *         前端据此刷新列表并加载缩略图。</li>
 * </ol>
 *
 * <p>注意：照片的"是否已入库"不再由这里判断——入库在拍照当时就已经完成（SQLite 是唯一事实来源），
 * 所以不存在"交付/确认/补交"这层状态；崩溃后由 {@link FileImporter#repairPendingImports()} 修复即可。
 */
public final class CameraSessionStore {

    /** Activity result 中携带"本次新增记录"数组的 key。 */
    public static final String EXTRA_SESSION_JSON = "com.filecamera.apk.extra.CAMERA_SESSION_JSON";

    private static final String TAG = "CameraSessionStore";

    /** 会话根目录（内部分区，与 app_data/icons 同一分区，搬移才能用原子 rename）。 */
    private static final String SESSION_ROOT_DIR = "photo_tmp";
    private static final String SESSION_DIR_PREFIX = "session_";
    private static final String MANIFEST_FILE_NAME = "session.json";
    /** 未被清理的会话目录保留时长，超时由 GC 回收。 */
    private static final long SESSION_TTL_MS = 24L * 60 * 60 * 1000;

    private static final Object LOCK = new Object();

    private static String currentSessionId = null;
    private static File currentSessionDir = null;
    private static long currentCreatedAt = 0;
    /** 本次会话新增的库记录（FileImporter 的返回值） */
    private static final JSONArray currentRecords = new JSONArray();

    private CameraSessionStore() {
    }

    // region --- 会话生命周期 ---

    /**
     * 开启（或延续）一次拍照会话。
     * 同一进程内若已存在未结束的会话则直接复用，避免 Activity 重建时丢失已拍的记录。
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
            String sessionId = SESSION_DIR_PREFIX + IdGenerator.get_id_by_time();
            File dir = new File(root, sessionId);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建拍照会话目录: " + dir.getAbsolutePath());
            }
            currentSessionId = sessionId;
            currentSessionDir = dir;
            currentCreatedAt = now;
            resetRecordsLocked();
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
        return new File(dir, IdGenerator.get_id_by_time() + ".jpg");
    }

    /** 记录一条已入库的文件记录（水印图 / 无水印原图各算一条）。 */
    public static void addRecord(Context context, JSONObject record) {
        if (record == null) return;
        synchronized (LOCK) {
            currentRecords.put(record);
            persistManifestLocked();
        }
    }

    /** 本次会话已入库的记录条数。 */
    public static int getRecordCount() {
        synchronized (LOCK) {
            return currentRecords.length();
        }
    }

    /** 结束内存中的会话状态（不删除目录；目录里的中转文件已入库，交给 GC/退出清理）。 */
    public static void closeSession() {
        synchronized (LOCK) {
            currentSessionId = null;
            currentSessionDir = null;
            currentCreatedAt = 0;
            resetRecordsLocked();
        }
    }

    /** 删除当前会话目录（本次没有任何照片、或正常退出后清理中转文件时调用）。 */
    public static void clearCurrent(Context context) {
        File dir;
        synchronized (LOCK) {
            dir = currentSessionDir;
            currentSessionId = null;
            currentSessionDir = null;
            currentCreatedAt = 0;
            resetRecordsLocked();
        }
        if (dir != null) {
            deleteRecursively(dir);
            Log.i(TAG, "拍照会话目录已清理: " + dir.getAbsolutePath());
        }
    }

    private static void resetRecordsLocked() {
        while (currentRecords.length() > 0) {
            currentRecords.remove(0);
        }
    }

    // endregion

    // region --- 交接给前端 ---

    /** 本次会话新增的库记录（前端据此刷新列表、加载缩略图）。 */
    public static JSONArray snapshotRecords() {
        synchronized (LOCK) {
            try {
                return new JSONArray(currentRecords.toString());
            } catch (JSONException e) {
                return new JSONArray();
            }
        }
    }

    /**
     * 把"本次新增的文件记录"放进 Activity result。
     * 前端只需要这些记录（不是文件内容），图片数据早已在 Java 层入库。
     */
    public static Intent buildResultIntent(Context context) {
        Intent intent = new Intent();
        intent.putExtra(EXTRA_SESSION_JSON, snapshotRecords().toString());
        return intent;
    }

    /** 从 Activity result 中解析记录数组；不是本会话的 result 返回空数组。 */
    public static JSONArray parseRecords(Intent data) {
        if (data == null) return new JSONArray();
        String raw = data.getStringExtra(EXTRA_SESSION_JSON);
        if (raw == null || raw.trim().isEmpty()) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            Log.e(TAG, "解析会话记录失败", e);
            return new JSONArray();
        }
    }

    // endregion

    // region --- 磁盘清单与回收 ---

    private static File getSessionRoot(Context context) {
        // 内部分区：与 files/app_data、files/icons 同分区，成品搬移是原子 rename（不是跨分区复制）
        return new File(context.getFilesDir(), SESSION_ROOT_DIR);
    }

    private static void persistManifestLocked() {
        if (currentSessionDir == null) return;
        try {
            JSONObject manifest = new JSONObject();
            manifest.put("session_id", currentSessionId == null ? "" : currentSessionId);
            manifest.put("created_at", currentCreatedAt);
            manifest.put("count", currentRecords.length());
            manifest.put("records", new JSONArray(currentRecords.toString()));
            File target = new File(currentSessionDir, MANIFEST_FILE_NAME);
            byte[] payload = manifest.toString().getBytes(StandardCharsets.UTF_8);
            File temp = new File(currentSessionDir, MANIFEST_FILE_NAME + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                out.write(payload);
                out.flush();
            }
            if (!temp.renameTo(target)) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                    out.write(payload);
                }
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "写入会话清单失败", e);
        }
    }

    private static void gcOldSessionsAsync(Context context) {
        final Context appContext = context.getApplicationContext();
        new Thread(() -> gcOldSessions(appContext)).start();
    }

    /**
     * 回收历史会话目录：
     * - 空目录（一张没拍/已全部入库）超过 1 小时即删；
     * - 其余超过 24 小时删除。
     * 这里只清理"拍摄中转文件"，不影响任何已入库的照片（照片在 app_data 里）。
     */
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
            File[] children = dir.listFiles();
            boolean onlyManifest = children == null || children.length == 0
                    || (children.length == 1 && MANIFEST_FILE_NAME.equals(children[0].getName()));
            long ttl = onlyManifest ? 60L * 60 * 1000 : SESSION_TTL_MS;
            if (now - dir.lastModified() < ttl) continue;
            if (deleteRecursively(dir)) {
                Log.i(TAG, "已回收拍照会话目录: " + dir.getAbsolutePath());
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

    // endregion
}
