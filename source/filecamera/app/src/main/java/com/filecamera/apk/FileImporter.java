package com.filecamera.apk;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 文件入库器：全应用唯一的「把文件变成库记录」实现（相机拍摄、「导入文件/文件夹」共用）。
 *
 * <p><b>为什么不是"直接 insert 一行"</b>：文件和 SQLite 是两个资源，没有跨资源事务。
 * 这里用「预备表 + 事务提交」把可见性做成原子的：
 * <pre>
 *   ① INSERT INTO pending_file      // 未就绪：对外完全不可见（现有查询都不看这张表）
 *   ② 主图落位（同分区 rename 原子）  // 失败可回滚
 *   ③ 主图就位后，**直接在目标文件夹按主图生成缩略图**
 *      → 由此得到一条强不变量：「缩略图存在 ⇒ 主图一定已经就位」，
 *        修复时不必考虑"缩略图搬了、主图没搬"这种中间态，逻辑最简单
 *   ④ 事务：INSERT INTO file SELECT ... FROM pending_file ; DELETE FROM pending_file  // 可见性 0/1 切换
 * </pre>
 * 任何一步崩掉，用户都不会看到"半张照片"：要么查不到，要么完整。
 *
 * <p><b>启动修复</b>（{@link #repairPendingImports()}）：扫 pending_file，
 * 主图缺失就丢弃这条（连同缩略图/临时文件），缩略图缺失就重新生成，都齐了就直接补提交。
 */
public class FileImporter {

    private static final String TAG = "FileImporter";
    private static final String DATA_DIR_NAME = "app_data";
    private static final String ICON_DIR_NAME = "icons";
    private static final String COLUMNS =
            "id,name,parent_dir_id,type,real_file_name,real_icon_name,createtime,seqnumber,size,extension";

    private final Context context;
    private final DatabaseHelper dbHelper;

    public FileImporter(Context context) {
        this.context = context.getApplicationContext();
        this.dbHelper = DatabaseHelper.getInstance(this.context);
    }

    // region --- 对外入口 ---

    /**
     * 相机路径：源文件已在应用内部分区（合成完成的成品），入库时被"移动"进 app_data。
     *
     * @param sourceFile  合成完成的照片文件
     * @param dirId       目标目录 id（为空则落到 root）
     * @param displayName 期望的显示文件名
     * @return 与 backServer.upload_file 完全一致的记录对象
     */
    public JSONObject importLocalFile(File sourceFile, String dirId, String displayName) throws Exception {
        return doImport(sourceFile, dirId, displayName);
    }

    /**
     * Web 路径：从 content URI 读出内容后入库（「菜单 → 导入文件 / 导入文件夹」使用）。
     */
    public JSONObject importFromContentUri(String contentUri, String dirId, String displayName) throws Exception {
        byte[] bytes = copyFileFromUri(contentUri);
        if (bytes == null || bytes.length == 0) {
            throw new IOException("读取文件内容失败");
        }
        File temp = new File(tempDirectory(), IdGenerator.get_id_by_time() + ".tmp");
        if (!saveFile(bytes, temp)) {
            throw new IOException("临时文件写入失败");
        }
        try {
            return doImport(temp, dirId, displayName);
        } finally {
            if (temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    /**
     * 启动时修复未完成的入库（在后台线程调用）。
     *
     * @return 修复（补提交或清理）的记录条数
     */
    public int repairPendingImports() {
        JSONArray rows;
        try {
            rows = dbHelper.db_all("SELECT * FROM pending_file", new JSONArray());
        } catch (Exception e) {
            Log.e(TAG, "读取 pending_file 失败", e);
            return 0;
        }
        int repaired = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            if (repairOne(row)) repaired++;
        }
        if (repaired > 0) {
            Log.i(TAG, "已修复 " + repaired + " 条未完成的入库记录");
        }
        return repaired;
    }

    // endregion

    // region --- 入库主流程 ---

    private JSONObject doImport(File sourceFile, String dirId, String displayName) throws Exception {
        if (sourceFile == null || !sourceFile.isFile() || sourceFile.length() <= 0) {
            throw new IOException("源文件不可用");
        }
        String targetDirId = (dirId == null || dirId.trim().isEmpty()) ? "root" : dirId;
        if (dbHelper.db_get("SELECT id FROM dir WHERE id = ?", new JSONArray().put(targetDirId)) == null) {
            throw new Exception("文件夹不存在");
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = sourceFile.getName();
        }

        String finalName = dedupeName(targetDirId, displayName);
        String extension = extensionOf(finalName);
        String namePart = baseNameOf(finalName);
        String fileId = IdGenerator.get_id_by_time();
        String realFileName = extension.isEmpty() ? fileId : fileId + "." + extension;
        boolean isImage = isImageFile(finalName);
        String realIconName = isImage ? fileId + "_icon.png" : "";
        long size = sourceFile.length();
        long createTime = System.currentTimeMillis();
        long seqNumber = getSeqNumberFromString(namePart);

        File dataDir = dataDirectory();
        File iconDir = iconDirectory();
        File targetFile = new File(dataDir, realFileName);
        File iconFile = isImage ? new File(iconDir, realIconName) : null;

        // ① 未就绪记录：对外不可见，崩溃后由 repairPendingImports 处理
        dbHelper.db_run("INSERT INTO pending_file (" + COLUMNS + ",tmp_photo_path,tmp_icon_path) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                new JSONArray()
                        .put(fileId).put(finalName).put(targetDirId).put("file")
                        .put(realFileName).put(realIconName).put(createTime).put(seqNumber)
                        .put(size).put(extension).put(sourceFile.getAbsolutePath()).put(""));

        boolean promoted = false;
        try {
            // ② 主图落位（同分区 rename；跨分区自动退化为复制）
            if (!dataDir.isDirectory() && !dataDir.mkdirs()) {
                throw new IOException("无法创建数据目录");
            }
            syncFile(sourceFile);
            if (!moveFile(sourceFile, targetFile) || !targetFile.isFile()) {
                throw new IOException("照片写入失败");
            }

            // ③ 主图已就位 → 直接按目标文件夹里的主图生成缩略图（失败不阻塞入库，前端有默认图标兜底）
            String iconWritten = null;
            if (isImage) {
                if (!iconDir.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    iconDir.mkdirs();
                }
                byte[] iconBytes = iconMake(readAll(targetFile), finalName);
                if (iconBytes != null && saveFile(iconBytes, iconFile)) {
                    iconWritten = realIconName;
                }
            }
            dbHelper.db_run("UPDATE pending_file SET real_icon_name = ?, tmp_photo_path = ? WHERE id = ?",
                    new JSONArray()
                            .put(iconWritten == null ? JSONObject.NULL : iconWritten)
                            .put("")
                            .put(fileId));

            // ④ 事务提交：pending → file，可见性在此原子切换
            promote(fileId);
            promoted = true;
            return buildRecord(fileId, finalName, targetDirId, realFileName, iconWritten,
                    createTime, seqNumber, size, extension);
        } finally {
            if (!promoted) {
                rollback(fileId, sourceFile, targetFile, iconFile);
            }
        }
    }

    /** 事务提交：把 pending_file 里的一行搬进 file 表（INSERT 与 DELETE 同一事务）。 */
    private void promote(String fileId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR REPLACE INTO file (" + COLUMNS + ") SELECT " + COLUMNS
                    + " FROM pending_file WHERE id = ?", new Object[]{fileId});
            db.execSQL("DELETE FROM pending_file WHERE id = ?", new Object[]{fileId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 失败回滚：删掉未就绪记录，把已经移动的主图退回源位置（保留给重试/清理），清掉缩略图。 */
    private void rollback(String fileId, File sourceFile, File targetFile, File iconFile) {
        try {
            dbHelper.db_run("DELETE FROM pending_file WHERE id = ?", new JSONArray().put(fileId));
        } catch (Exception e) {
            Log.e(TAG, "回滚 pending_file 失败", e);
        }
        try {
            if (targetFile != null && targetFile.isFile()) {
                if (sourceFile != null && !sourceFile.exists()) {
                    moveFile(targetFile, sourceFile);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    targetFile.delete();
                }
            }
            if (iconFile != null && iconFile.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                iconFile.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "回滚文件失败", e);
        }
    }

    private JSONObject buildRecord(String fileId, String name, String parentDirId, String realFileName,
                                  String realIconName, long createTime, long seqNumber, long size,
                                  String extension) throws JSONException {
        JSONObject record = new JSONObject();
        record.put("type", "file");
        record.put("id", fileId);
        record.put("name", name);
        record.put("size", size);
        record.put("createtime", createTime);
        record.put("extension", extension);
        record.put("real_file_name", realFileName);
        record.put("real_icon_name", realIconName == null ? JSONObject.NULL : realIconName);
        record.put("seqnumber", seqNumber);
        record.put("parent_dir_id", parentDirId);
        return record;
    }

    // endregion

    // region --- 启动修复 ---

    /** @return true 表示这条记录被修复/清理掉了 */
    private boolean repairOne(JSONObject row) {
        String id = row.optString("id");
        String realFileName = row.optString("real_file_name");
        if (id.isEmpty() || realFileName.isEmpty()) {
            return false;
        }
        String parentDirId = row.optString("parent_dir_id");
        String displayName = row.optString("name", realFileName);
        String realIconName = row.isNull("real_icon_name") ? null : row.optString("real_icon_name", null);
        if (realIconName != null && realIconName.isEmpty()) realIconName = null;

        File targetFile = new File(dataDirectory(), realFileName);
        File iconFile = realIconName == null ? null : new File(iconDirectory(), realIconName);

        try {
            // 目标目录已经被删掉 → 这条记录永远不可见，连同文件一起清掉
            if (dbHelper.db_get("SELECT id FROM dir WHERE id = ?", new JSONArray().put(parentDirId)) == null) {
                discard(id, targetFile, iconFile, row);
                return true;
            }

            // 主图缺失：先尝试从临时文件补位
            if (!targetFile.isFile()) {
                File tmp = new File(row.optString("tmp_photo_path", ""));
                if (tmp.isFile() && moveFile(tmp, targetFile) && targetFile.isFile()) {
                    Log.i(TAG, "已从临时文件补齐主图: " + id);
                } else {
                    // 主图彻底找不回 → 这条记录不可能完整显示，丢弃
                    Log.w(TAG, "主图缺失，丢弃未就绪记录: " + id);
                    discard(id, targetFile, iconFile, row);
                    return true;
                }
            }

            // 缩略图缺失：重新生成；生成失败就把 real_icon_name 置空（前端回退默认图标）
            if (isImageFile(displayName) && (iconFile == null || !iconFile.isFile())) {
                String iconName = (iconFile == null) ? (id + "_icon.png") : iconFile.getName();
                File iconDir = iconDirectory();
                if (!iconDir.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    iconDir.mkdirs();
                }
                byte[] iconBytes = iconMake(readAll(targetFile), displayName);
                String written = null;
                if (iconBytes != null && saveFile(iconBytes, new File(iconDir, iconName))) {
                    written = iconName;
                }
                dbHelper.db_run("UPDATE pending_file SET real_icon_name = ? WHERE id = ?",
                        new JSONArray().put(written == null ? JSONObject.NULL : written).put(id));
            }

            // 都齐了 → 补提交（事务），可见性在这里切换
            promote(id);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "修复未就绪记录失败: " + id, e);
            return false;
        }
    }

    /** 丢弃一条未就绪记录及其文件（主图 / 缩略图 / 临时文件）。 */
    private void discard(String id, File targetFile, File iconFile, JSONObject row) {
        try {
            dbHelper.db_run("DELETE FROM pending_file WHERE id = ?", new JSONArray().put(id));
        } catch (Exception e) {
            Log.e(TAG, "删除 pending_file 失败", e);
        }
        deleteQuietly(targetFile);
        deleteQuietly(iconFile);
        deleteQuietly(new File(row.optString("tmp_photo_path", "")));
    }

    // endregion

    // region --- 视频录像入库（边录边写正式目录，中断由修复器兜底）---

    /** 一次录像的入库票据：开录前就把文件名/待入表定下来，录完再提交。 */
    public static final class VideoTicket {
        public final String fileId;
        public final String realFileName;
        public final File targetFile;
        public final String displayName;
        /** 本次录像的水印数据（只写进 .wmp4 文件尾部，不存数据库） */
        public final JSONObject watermark;
        public final String parentDirId;

        VideoTicket(String fileId, String realFileName, File targetFile, String displayName, String parentDirId, JSONObject watermark) {
            this.fileId = fileId;
            this.realFileName = realFileName;
            this.targetFile = targetFile;
            this.displayName = displayName;
            this.watermark = watermark;
            this.parentDirId = parentDirId;
        }
    }

    /**
     * 开始录像前的准备：定下最终文件名，**直接录到正式目录** app_data/&lt;id&gt;.mp4，
     * 同时写好 pending_file（未就绪，列表不可见）和 file_watermark（水印数据，content_hash 先留空）。
     * 中断/被杀时：文件与两条记录都在，启动修复会探测能否播放，能播就补提交，不能播就清理。
     *
     * @param watermarkJson 本次录像的水印数据（base_time_ms + 首行全量 + 后续变化由调用方追加）
     */
    public VideoTicket beginVideo(String dirId, String displayName, JSONObject watermarkJson) throws Exception {
        String targetDirId = (dirId == null || dirId.trim().isEmpty()) ? "root" : dirId;
        if (dbHelper.db_get("SELECT id FROM dir WHERE id = ?", new JSONArray().put(targetDirId)) == null) {
            throw new Exception("文件夹不存在");
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = IdGenerator.get_id_by_time() + ".mp4";
        }
        String finalName = dedupeName(targetDirId, displayName);
        if (extensionOf(finalName).isEmpty()) finalName = finalName + "." + WatermarkFileCodec.EXTENSION;
        String extension = extensionOf(finalName);
        String fileId = IdGenerator.get_id_by_time();
        String realFileName = fileId + "." + extension;
        String realIconName = fileId + "_icon.png";
        File dataDir = dataDirectory();
        if (!dataDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dataDir.mkdirs();
        }
        File target = new File(dataDir, realFileName);

        dbHelper.db_run("INSERT INTO pending_file (" + COLUMNS + ",tmp_photo_path,tmp_icon_path) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                new JSONArray()
                        .put(fileId).put(finalName).put(targetDirId).put("file")
                        .put(realFileName).put(realIconName).put(System.currentTimeMillis())
                        .put(getSeqNumberFromString(baseNameOf(finalName))).put(0L).put(extension)
                        .put("").put(""));
        // .wmp4 专用格式：水印数据只写进文件尾部，数据库不保存任何水印信息
        return new VideoTicket(fileId, realFileName, target, finalName, targetDirId, watermarkJson);
    }

    /**
     * 录像 finalize 之后提交入库：探测可播放 → 算视频内容哈希（防篡改）→ 生成首帧缩略图 →
     * 更新 pending/watermark → 事务把 pending 行搬进 file 表。
     */
    public JSONObject finishVideo(VideoTicket ticket) throws Exception {
        if (ticket == null || !ticket.targetFile.isFile() || ticket.targetFile.length() <= 0) {
            throw new IOException("录像文件不可用");
        }
        if (!isPlayableVideo(ticket.targetFile)) {
            throw new IOException("录像文件无法播放（可能未正常结束）");
        }
        long size = ticket.targetFile.length();
        String hash = sha256(ticket.targetFile);

        // 缩略图（首帧）；失败不阻塞入库
        String iconWritten = null;
        File iconDir = iconDirectory();
        if (!iconDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            iconDir.mkdirs();
        }
        byte[] thumb = watermarkThumbnail(ticket.watermark, ticket.targetFile);
        String iconName = ticket.fileId + "_icon.png";
        if (thumb != null && saveFile(thumb, new File(iconDir, iconName))) {
            iconWritten = iconName;
        }

        dbHelper.db_run("UPDATE pending_file SET size = ?, real_icon_name = ? WHERE id = ?",
                new JSONArray().put(size).put(iconWritten == null ? JSONObject.NULL : iconWritten).put(ticket.fileId));
        // 按关键帧重排时间轴 → 把水印 JSON 写进 .wmp4 文件尾部（数据库不再保存水印信息）
        try {
            String wmJson = rebuildWatermarkTimeline(ticket.watermark, ticket.targetFile);
            if (wmJson != null) {
                WatermarkFileCodec.writeTrailer(ticket.targetFile, WatermarkFileCodec.withContentHash(ticket.targetFile, wmJson));
            }
        } catch (Exception e) {
            Log.e(TAG, "写入 .wmp4 水印数据失败", e);
        }
        size = ticket.targetFile.length();

        promote(ticket.fileId);
        return buildRecord(ticket.fileId, ticket.displayName, ticket.parentDirId, ticket.realFileName,
                iconWritten, System.currentTimeMillis(), getSeqNumberFromString(baseNameOf(ticket.displayName)),
                size, extensionOf(ticket.displayName));
    }

    /** 录像失败/取消：删待入表、水印数据、半成品文件与缩略图。 */
    public void abortVideo(VideoTicket ticket) {
        if (ticket == null) return;
        try {
            dbHelper.db_run("DELETE FROM pending_file WHERE id = ?", new JSONArray().put(ticket.fileId));
        } catch (Exception e) {
            Log.e(TAG, "回滚录像记录失败", e);
        }
        if (ticket.targetFile.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            ticket.targetFile.delete();
        }
        deleteQuietly(new File(iconDirectory(), ticket.fileId + "_icon.png"));
    }

    /** 探测文件是否是可播放的视频（未 finalize 的 MP4 会打不开）。 */
    static boolean isPlayableVideo(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration != null && Long.parseLong(duration) > 0;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** 视频首帧（失败返回 null）。 */
    static Bitmap videoFirstFrame(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 视频缩略图：**首帧先把水印合成上去**，再缩到 256px 存 PNG。
     * 合成失败/没有水印数据时退回无水印首帧，不阻塞入库。
     */
    private byte[] watermarkThumbnail(JSONObject wm, File videoFile) {
        Bitmap frame = videoFirstFrame(videoFile);
        if (frame == null) return null;
        Bitmap composed = frame;
        try {
            if (wm != null) {
                composed = WatermarkBitmapRenderer.composeOnto(frame, wm);
            }
        } catch (Exception e) {
            Log.e(TAG, "首帧合成水印失败", e);
            composed = frame;
        }
        byte[] bytes = scaleAndCompress(composed);
        if (composed != frame && composed != null) composed.recycle();
        if (frame != null && !frame.isRecycled()) frame.recycle();
        return bytes;
    }

    /** 等比缩到长边 256 后压成 PNG。 */
    private static byte[] scaleAndCompress(Bitmap source) {
        if (source == null) return null;
        int w = source.getWidth();
        int h = source.getHeight();
        if (w <= 0 || h <= 0) return null;
        float aspect = (float) w / (float) h;
        int nw, nh;
        if (w >= h) {
            nw = 256;
            nh = Math.round(256 / aspect);
        } else {
            nh = 256;
            nw = Math.round(256 * aspect);
        }
        Bitmap scaled = null;
        try {
            scaled = Bitmap.createScaledBitmap(source, nw, nh, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (scaled != null && scaled != source && !scaled.isRecycled()) scaled.recycle();
        }
    }

    /**
     * 把录制期的水印时间轴重排为「只在关键帧上存完整快照」，帧间不留任何数据。
     *
     * <p>和字幕一个道理：播放/拖动到哪个位置，就用"上一个关键帧"那条完整快照；
     * 这样跳转播放永远有权威基准，不用从 0 逐条累加，也不会因为时间戳漂移而错位。
     */
    private String rebuildWatermarkTimeline(JSONObject wm, File videoFile) throws Exception {
        if (wm == null) return null;
        if (wm.optBoolean("burned", false)) return null;
        JSONArray oldSegments = wm.optJSONArray("segments");
        if (oldSegments == null || oldSegments.length() == 0) return null;

        long[] keyFrames = readKeyFrameTimesMs(videoFile);
        if (keyFrames == null || keyFrames.length == 0) return null;

        // 原始采样记录按时间排序（录制时是"首条全量 + 后续只记变化"）
        java.util.List<JSONObject> records = new java.util.ArrayList<>();
        for (int i = 0; i < oldSegments.length(); i++) {
            JSONObject seg = oldSegments.optJSONObject(i);
            if (seg != null) records.add(seg);
        }
        records.sort((a, b) -> Long.compare(a.optLong("from_ms", 0), b.optLong("from_ms", 0)));

        // 每个关键帧一条完整快照（把该时刻之前的变化折叠进来）
        JSONArray rebuilt = new JSONArray();
        java.util.LinkedHashMap<String, String> state = new java.util.LinkedHashMap<>();
        String pos = wm.optString("pos", "");
        int cursor = 0;
        for (long keyTime : keyFrames) {
            while (cursor < records.size() && records.get(cursor).optLong("from_ms", 0) <= keyTime) {
                JSONObject seg = records.get(cursor);
                JSONObject values = seg.optJSONObject("values");
                if (values != null) {
                    java.util.Iterator<String> keys = values.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        state.put(k, values.optString(k, ""));
                    }
                }
                if (seg.has("pos")) pos = seg.optString("pos", pos);
                cursor++;
            }
            JSONObject key = new JSONObject();
            key.put("from_ms", keyTime);
            JSONObject snapshot = new JSONObject();
            for (java.util.Map.Entry<String, String> e : state.entrySet()) {
                snapshot.put(e.getKey(), e.getValue());
            }
            key.put("values", snapshot);
            if (!pos.isEmpty()) key.put("pos", pos);
            rebuilt.put(key);
        }

        wm.put("segments", rebuilt);
        JSONArray kf = new JSONArray();
        for (long keyTime : keyFrames) kf.put(keyTime);
        wm.put("keyframes", kf);
        wm.put("timeline", "keyframe");

        return wm.toString();
    }

    /** 读视频的关键帧时间（毫秒）。 */
    static long[] readKeyFrameTimesMs(File videoFile) {
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        try {
            extractor.setDataSource(videoFile.getAbsolutePath());
            int videoTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    break;
                }
            }
            if (videoTrack < 0) return null;
            extractor.selectTrack(videoTrack);
            java.util.List<Long> times = new java.util.ArrayList<>();
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(64 * 1024);
            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                long ptsUs = extractor.getSampleTime();
                if ((extractor.getSampleFlags() & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    times.add(Math.max(0, ptsUs / 1000));
                }
                if (!extractor.advance()) break;
            }
            long[] result = new long[times.size()];
            for (int i = 0; i < times.size(); i++) result[i] = times.get(i);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "读取关键帧失败", e);
            return null;
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** 文件内容 SHA-256（播放前用它校验水印数据是否与视频匹配）。 */
    static String sha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) > 0) {
                digest.update(buf, 0, read);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "计算文件哈希失败", e);
            return "";
        }
    }

    // endregion

    // region --- 烧录临时文件 + 烧录产物入库 ---

    /** 烧录临时目录（与 app_data 同一分区，入库时可原子 rename）。 */
    private File burnTempDir() {
        File dir = new File(context.getFilesDir(), "burn_tmp");
        if (!dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** 烧录输出先落到临时目录，入库时才搬进 app_data。 */
    public File newBurnTempFile(String baseName) {
        String name = (baseName == null || baseName.isEmpty()) ? "burn" : baseName;
        return new File(burnTempDir(), IdGenerator.get_id_by_time() + "-" + name);
    }

    /** 启动时清理烧录临时目录：闪存占用不堆积。 */
    public void cleanBurnTemp() {
        File dir = new File(context.getFilesDir(), "burn_tmp");
        if (!dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        int removed = 0;
        for (File child : children) {
            if (child.delete()) removed++;
        }
        if (removed > 0) Log.i(TAG, "启动清理烧录临时文件: " + removed + " 个");
    }

    /**
     * 烧录完成后入库：与拍照/录像同一条链路（pending → 搬文件 → 缩略图 → 事务提交）。
     * 烧录产物画面里已经带水印，所以不写任何水印数据；首帧缩略图直接取（不再合成）。
     */
    public JSONObject importBurnedVideo(File tempFile, String dirId, String displayName) throws Exception {
        if (tempFile == null || !tempFile.isFile() || tempFile.length() <= 0) {
            throw new IOException("烧录产物不可用");
        }
        if (!isPlayableVideo(tempFile)) {
            throw new IOException("烧录产物无法播放");
        }
        String targetDirId = (dirId == null || dirId.trim().isEmpty()) ? "root" : dirId;
        if (dbHelper.db_get("SELECT id FROM dir WHERE id = ?", new JSONArray().put(targetDirId)) == null) {
            throw new Exception("文件夹不存在");
        }
        String finalName = dedupeName(targetDirId, displayName);
        String extension = extensionOf(finalName);
        if (extension.isEmpty()) {
            extension = "mp4";
            finalName = finalName + ".mp4";
        }
        String fileId = IdGenerator.get_id_by_time();
        String realFileName = fileId + "." + extension;
        File dataDir = dataDirectory();
        if (!dataDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dataDir.mkdirs();
        }
        File target = new File(dataDir, realFileName);

        dbHelper.db_run("INSERT INTO pending_file (" + COLUMNS + ",tmp_photo_path,tmp_icon_path) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                new JSONArray()
                        .put(fileId).put(finalName).put(targetDirId).put("file")
                        .put(realFileName).put(fileId + "_icon.png").put(System.currentTimeMillis())
                        .put(getSeqNumberFromString(baseNameOf(finalName))).put(0L).put(extension)
                        .put("").put(""));

        if (!moveFile(tempFile, target)) {
            dbHelper.db_run("DELETE FROM pending_file WHERE id = ?", new JSONArray().put(fileId));
            throw new IOException("烧录产物搬移失败");
        }

        long size = target.length();
        byte[] thumb = scaleAndCompress(videoFirstFrame(target));
        String iconName = fileId + "_icon.png";
        String iconWritten = null;
        File iconDir = iconDirectory();
        if (!iconDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            iconDir.mkdirs();
        }
        if (thumb != null && saveFile(thumb, new File(iconDir, iconName))) {
            iconWritten = iconName;
        }

        dbHelper.db_run("UPDATE pending_file SET size = ?, real_icon_name = ? WHERE id = ?",
                new JSONArray().put(size).put(iconWritten == null ? JSONObject.NULL : iconWritten).put(fileId));
        promote(fileId);
        return buildRecord(fileId, finalName, targetDirId, realFileName, iconWritten,
                System.currentTimeMillis(), getSeqNumberFromString(baseNameOf(finalName)),
                size, extension);
    }

    // endregion
    // region --- 文件/命名工具 ---

    private File dataDirectory() {
        return new File(context.getFilesDir(), DATA_DIR_NAME);
    }

    private File iconDirectory() {
        return new File(context.getFilesDir(), ICON_DIR_NAME);
    }

    /** 导入中转目录（与 app_data 同分区，rename 才能原子） */
    private File tempDirectory() {
        File dir = new File(context.getFilesDir(), "import_tmp");
        if (!dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** 同分区用 rename（原子），跨分区退化为复制+删除。 */
    static boolean moveFile(File source, File target) {
        if (source == null || target == null) return false;
        if (target.isFile() && !target.delete()) return false;
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        if (source.renameTo(target)) return true;
        // 跨分区：复制后删源
        try (InputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            Log.e(TAG, "移动文件失败: " + source + " -> " + target, e);
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        source.delete();
        return target.isFile();
    }

    /** 把还没刷盘的文件内容强制落盘，避免"库里有记录但文件是空的"这种坏状态。 */
    private static void syncFile(File file) {
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.getFD().sync();
        } catch (Exception ignored) {
        }
    }

    private static void deleteQuietly(File file) {
        try {
            if (file != null && file.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Exception ignored) {
        }
    }

    static byte[] readAll(File file) {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] copyFileFromUri(String contentUri) throws IOException {
        Uri uri = Uri.parse(contentUri);
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("无法打开文件");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private String dedupeName(String dirId, String name) throws JSONException {
        String candidate = name;
        String base = baseNameOf(name);
        String extension = extensionOf(name);
        int counter = 1;
        while (dbHelper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?",
                new JSONArray().put(dirId).put(candidate)) != null) {
            candidate = base + "(" + counter + ")" + (extension.isEmpty() ? "" : "." + extension);
            counter++;
        }
        return candidate;
    }

    static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return (dot > 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "";
    }

    static String baseNameOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static boolean isImageFile(String filename) {
        return filename != null && filename.toLowerCase().matches(".*\\.(jpg|jpeg|png|gif|bmp|webp|svg)$");
    }

    static boolean saveFile(byte[] data, File targetFile) {
        if (data == null) return false;
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        try (FileOutputStream out = new FileOutputStream(targetFile)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
            return true;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            targetFile.delete();
            return false;
        }
    }

    static long getSeqNumberFromString(String input) {
        if (input == null || input.isEmpty()) return 0L;
        StringBuilder numbers = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (Character.isDigit(c)) numbers.append(c);
        }
        String digits = numbers.toString();
        if (digits.length() > 18) {
            digits = digits.substring(digits.length() - 18);
        }
        if (digits.isEmpty()) return 0L;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 生成 256px 缩略图（按 EXIF 方向旋转）。失败返回 null，由调用方决定是否阻塞入库。
     */
    static byte[] iconMake(byte[] fileData, String filename) {
        if (fileData == null || !isImageFile(filename)) return null;
        try {
            // 先只解码边界获取尺寸，再按比例降采样解码，避免全尺寸解码（如 50MP 照片约 200MB）把内存挤爆
            BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
            boundsOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(fileData, 0, fileData.length, boundsOptions);
            int srcWidth = boundsOptions.outWidth;
            int srcHeight = boundsOptions.outHeight;
            if (srcWidth <= 0 || srcHeight <= 0) return null;

            BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
            decodeOptions.inSampleSize = Math.max(1, Math.min(srcWidth, srcHeight) / 512);
            Bitmap decoded = BitmapFactory.decodeByteArray(fileData, 0, fileData.length, decodeOptions);
            if (decoded == null) return null;

            // 相机直出的原图像素未旋转，方向记录在 EXIF 中，不处理会导致缩略图方向错误
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try (ByteArrayInputStream exifStream = new ByteArrayInputStream(fileData)) {
                orientation = new ExifInterface(exifStream)
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (Exception ignored) {
            }
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
                default: break;
            }
            Bitmap oriented = decoded;
            if (!matrix.isIdentity()) {
                oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
                if (oriented != decoded) decoded.recycle();
            }

            int width = oriented.getWidth();
            int height = oriented.getHeight();
            float aspect = (float) width / (float) height;
            int newWidth, newHeight;
            if (width >= height) {
                newWidth = 256;
                newHeight = Math.round(256 / aspect);
            } else {
                newHeight = 256;
                newWidth = Math.round(256 * aspect);
            }
            Bitmap scaled = Bitmap.createScaledBitmap(oriented, newWidth, newHeight, true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, output);
            oriented.recycle();
            scaled.recycle();
            return output.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // endregion
}
