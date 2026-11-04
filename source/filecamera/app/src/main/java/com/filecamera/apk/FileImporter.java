package com.filecamera.apk;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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

    /**
     * 「查重命名 + 写 pending_file」的临界区锁：多 worker 并行导入时保证同目录下
     * 不会有两份文件同时拿到同一个名字（pending 行也算占用，见 {@link #nameTaken}）。
     */
    private static final Object NAME_LOCK = new Object();

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
        return doImport(IdGenerator.get_id_by_time(), sourceFile, dirId, displayName);
    }

    /**
     * Web 路径：从 content URI 读出内容后入库（「菜单 → 导入文件 / 导入文件夹」使用）。
     *
     * <p>id 在这里就先定下来，草稿文件直接用「最终 id + 扩展名」命名，
     * 这样一次导入只消耗一个 id，后面的 rename 只是把同一个文件从草稿目录挪到 app_data。
     */
    public JSONObject importFromContentUri(String contentUri, String dirId, String displayName) throws Exception {
        String fileId = IdGenerator.get_id_by_time();
        String extension = extensionOf(displayName);
        File temp = new File(tempDirectory(),
                extension.isEmpty() ? fileId + ".tmp" : fileId + "." + extension);
        // 流式落盘：不再把整份文件读进 byte[]。批量导入大图时，
        // 「整文件 byte[] + ByteArrayOutputStream 翻倍扩容」是内存峰值最高、最先 OOM 的地方。
        if (!copyUriToFile(contentUri, temp)) {
            deleteQuietly(temp);
            throw new IOException("读取文件内容失败");
        }
        try {
            return doImport(fileId, temp, dirId, displayName);
        } finally {
            deleteQuietly(temp);
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
            // 录像的 pending 行由 scanPendingVideoDirs() 专门处理（要探测可播、生成首帧），
            // 不能当成照片直接搬进 app_data。
            if (isVideoPendingRow(row)) continue;
            if (repairOne(row)) repaired++;
        }
        if (repaired > 0) {
            Log.i(TAG, "已修复 " + repaired + " 条未完成的入库记录");
        }
        return repaired;
    }

    // endregion

    // region --- 入库主流程 ---

    private JSONObject doImport(String fileId, File sourceFile, String dirId, String displayName) throws Exception {
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

        // 查重命名 + 写 pending 必须原子：多 worker 并行导入时，同目录的两个文件
        // 不能各自查到"没人占用"而拿到同一个名字（pending 里的行也算占用，见 dedupeName）。
        String finalName;
        String extension;
        String namePart;
        String realFileName;
        boolean isImage;
        String realIconName;
        long createTime;
        long seqNumber;
        long size = sourceFile.length();
        synchronized (NAME_LOCK) {
            finalName = dedupeName(targetDirId, displayName);
            extension = extensionOf(finalName);
            namePart = baseNameOf(finalName);
            realFileName = extension.isEmpty() ? fileId : fileId + "." + extension;
            isImage = isImageFile(finalName);
            realIconName = isImage ? fileId + "_icon.png" : "";
            createTime = System.currentTimeMillis();
            seqNumber = getSeqNumberFromString(namePart);

            // ① 未就绪记录：对外不可见，崩溃后由 repairPendingImports 处理
            dbHelper.db_run("INSERT INTO pending_file (" + COLUMNS + ",tmp_photo_path,tmp_icon_path) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    new JSONArray()
                            .put(fileId).put(finalName).put(targetDirId).put("file")
                            .put(realFileName).put(realIconName).put(createTime).put(seqNumber)
                            .put(size).put(extension).put(sourceFile.getAbsolutePath()).put(""));
        }

        File dataDir = dataDirectory();
        File iconDir = iconDirectory();
        File targetFile = new File(dataDir, realFileName);
        File iconFile = isImage ? new File(iconDir, realIconName) : null;
        final boolean image = isImage;

        boolean promoted = false;
        try {
            // ② 主图落位（同分区 rename；跨分区自动退化为复制）
            if (!dataDir.isDirectory() && !dataDir.mkdirs()) {
                throw new IOException("无法创建数据目录");
            }
            // 大容量导入最容易踩的坑：内部存储被塞满后所有写入都会失败，
            // 系统侧的 WebView/SQLite 也会跟着出问题，所以在落盘前就拦住，留出安全余量。
            if (!hasEnoughSpace(dataDir, size)) {
                throw new IOException("存储空间不足，剩余 " + (dataDir.getUsableSpace() / (1024 * 1024)) + "MB");
            }
            syncFile(sourceFile);
            if (!moveFile(sourceFile, targetFile) || !targetFile.isFile()) {
                throw new IOException("照片写入失败");
            }

            // ③ 主图已就位 → 直接按目标文件夹里的主图生成缩略图（失败不阻塞入库，前端有默认图标兜底）
            String iconWritten = null;
            if (image) {
                if (!iconDir.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    iconDir.mkdirs();
                }
                byte[] iconBytes = iconMake(targetFile, finalName);
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

    /** 是否是录像的 pending 行（录制中直接写 .part，由 scanPendingVideoDirs 收尾）。 */
    private static boolean isVideoPendingRow(JSONObject row) {
        String tmp = row.optString("tmp_photo_path", "");
        return tmp.endsWith(".part") || tmp.contains("video_pending");
    }

    /** @return true 表示这条记录被修复/清理掉了 */
    private boolean repairOne(JSONObject row) {
        if (isVideoPendingRow(row)) return false;
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

            // 缩略图缺失或损坏：重新生成；生成失败就把 real_icon_name 置空（前端回退默认图标）
            if (isImageFile(displayName) && !isIconUsable(iconFile)) {
                String iconName = (iconFile == null) ? (id + "_icon.png") : iconFile.getName();
                File iconDir = iconDirectory();
                if (!iconDir.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    iconDir.mkdirs();
                }
                byte[] iconBytes = iconMake(targetFile, displayName);
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

    /**
     * 缩略图是否可用：文件存在、非空、而且能解出宽高。
     *
     * <p>只判 {@code isFile()} 不够——进程被杀在 {@code saveFile} 中间时，会留下 0 字节
     * 或者半截的 PNG，那时 isFile() 为真但图是坏的，前端只会一直显示默认图标。
     * 这里用 {@code inJustDecodeBounds} 读一下头，几十微秒，不会真的解码像素。
     */
    private static boolean isIconUsable(File iconFile) {
        if (iconFile == null || !iconFile.isFile() || iconFile.length() <= 0) return false;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(iconFile.getAbsolutePath(), bounds);
            return bounds.outWidth > 0 && bounds.outHeight > 0;
        } catch (Throwable e) {
            // 损坏的图、极端情况下解码头就 OOM，都按"不可用"处理，让调用方重建
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

    // region --- 视频录像入库（边录边烧，中断由修复器兜底）---

    /** 一次录像的入库票据：开录前就把文件名/待入表定下来，录完再提交。 */
    public static final class VideoTicket {
        public final String fileId;
        public final String realFileName;
        public File targetFile;
        public final String displayName;
        public final String parentDirId;

        VideoTicket(String fileId, String realFileName, File targetFile, String displayName, String parentDirId) {
            this.fileId = fileId;
            this.realFileName = realFileName;
            this.targetFile = targetFile;
            this.displayName = displayName;
            this.parentDirId = parentDirId;
        }
    }

    /**
     * 开始录像前的准备：定下最终文件名，录到 video_pending/&lt;id&gt;/&lt;id&gt;.mp4.part，
     * 同时写好 pending_file（未就绪，列表不可见）。
     * 中断/被杀时：文件与两条记录都在，启动修复会探测能否播放，能播就补提交，不能播就清理。
     */
    public VideoTicket beginVideo(String dirId, String displayName) throws Exception {
        String targetDirId = (dirId == null || dirId.trim().isEmpty()) ? "root" : dirId;
        if (dbHelper.db_get("SELECT id FROM dir WHERE id = ?", new JSONArray().put(targetDirId)) == null) {
            throw new Exception("文件夹不存在");
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = IdGenerator.get_id_by_time() + ".mp4";
        }
        String finalName = dedupeName(targetDirId, displayName);
        if (extensionOf(finalName).isEmpty()) finalName = finalName + ".mp4";
        String extension = extensionOf(finalName);
        String fileId = IdGenerator.get_id_by_time();
        String realFileName = fileId + "." + extension;
        String realIconName = fileId + "_icon.png";
        // 录制期间只写临时目录（草稿纸），收尾成功才搬进 app_data
        File tmpDir = videoPendingDir(fileId);
        File part = new File(tmpDir, realFileName + ".part");

        dbHelper.db_run("INSERT INTO pending_file (" + COLUMNS + ",tmp_photo_path,tmp_icon_path) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                new JSONArray()
                        .put(fileId).put(finalName).put(targetDirId).put("file")
                        .put(realFileName).put(realIconName).put(System.currentTimeMillis())
                        .put(getSeqNumberFromString(baseNameOf(finalName))).put(0L).put(extension)
                        .put(part.getAbsolutePath()).put(""));
        return new VideoTicket(fileId, realFileName, part, finalName, targetDirId);
    }

    /**
     * 录像收尾之后提交入库：探测可播放 → 生成首帧缩略图（画面里已经带水印）→
     * 搬进 app_data → 事务把 pending 行搬进 file 表。
     */
    public JSONObject finishVideo(VideoTicket ticket) throws Exception {
        if (ticket == null || !ticket.targetFile.isFile() || ticket.targetFile.length() <= 0) {
            throw new IOException("录像文件不可用");
        }
        if (!isPlayableVideo(ticket.targetFile)) {
            throw new IOException("录像文件无法播放（可能未正常结束）");
        }
        long size = ticket.targetFile.length();
        // 草稿 → 正式目录（同分区 rename，原子）
        File dataDir = dataDirectory();
        if (!dataDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dataDir.mkdirs();
        }
        File finalFile = new File(dataDir, ticket.realFileName);
        if (finalFile.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            finalFile.delete();
        }
        if (!moveFile(ticket.targetFile, finalFile)) {
            throw new IOException("录像文件搬入正式目录失败");
        }
        ticket.targetFile = finalFile;

        // 缩略图（首帧）；画面里已经烧好水印，失败不阻塞入库
        String iconWritten = null;
        File iconDir = iconDirectory();
        if (!iconDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            iconDir.mkdirs();
        }
        byte[] thumb = videoThumbnail(ticket.targetFile);
        String iconName = ticket.fileId + "_icon.png";
        if (thumb != null && saveFile(thumb, new File(iconDir, iconName))) {
            iconWritten = iconName;
        }

        dbHelper.db_run("UPDATE pending_file SET size = ?, real_icon_name = ? WHERE id = ?",
                new JSONArray().put(size).put(iconWritten == null ? JSONObject.NULL : iconWritten).put(ticket.fileId));
        size = ticket.targetFile.length();
        promote(ticket.fileId);
        deleteVideoPendingDir(ticket.fileId);
        return buildRecord(ticket.fileId, ticket.displayName, ticket.parentDirId, ticket.realFileName,
                iconWritten, System.currentTimeMillis(), getSeqNumberFromString(baseNameOf(ticket.displayName)),
                size, extensionOf(ticket.displayName));
    }

    /** 录像失败/取消：删待入表、半成品文件与缩略图。 */
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

    /** 探测文件是否是可播放的视频。分片 MP4 的 mvhd 时长可能为 0，不能只看 duration。 */
    static boolean isPlayableVideo(File file) {
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int videoTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    break;
                }
            }
            if (videoTrack < 0) return false;
            extractor.selectTrack(videoTrack);
            long sampleSize = extractor.getSampleSize();
            if (sampleSize <= 0) return false;
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(
                    (int) Math.min(sampleSize, 32L * 1024 * 1024));
            int read = extractor.readSampleData(buffer, 0);
            return read > 0;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                extractor.release();
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
     * 视频缩略图：首帧画面里已经烧好水印，缩放后直接合成播放三角，存 PNG。
     */
    private byte[] videoThumbnail(File videoFile) {
        Bitmap frame = videoFirstFrame(videoFile);
        if (frame == null) return null;
        byte[] bytes = scaleAndCompress(frame);
        if (!frame.isRecycled()) frame.recycle();
        return bytes;
    }

    /** 等比缩到长边 256，并在中心合成明显的半透明播放三角，再压成 PNG。 */
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
            Bitmap annotated = scaled.copy(Bitmap.Config.ARGB_8888, true);
            if (annotated != null) {
                drawPlayTriangle(annotated);
                if (scaled != source) scaled.recycle();
                scaled = annotated;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (scaled != null && scaled != source && !scaled.isRecycled()) scaled.recycle();
        }
    }

    /** 在缩略图中心画半透明白色播放三角，带深色描边保证亮/暗画面都明显。 */
    private static void drawPlayTriangle(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float shortSide = Math.min(width, height);
        float triangleHeight = shortSide * 0.44f;
        float triangleWidth = triangleHeight * 0.82f;
        float cx = width / 2f;
        float cy = height / 2f;
        float left = cx - triangleWidth * 0.38f;

        Path path = new Path();
        path.moveTo(left, cy - triangleHeight / 2f);
        path.lineTo(left, cy + triangleHeight / 2f);
        path.lineTo(left + triangleWidth, cy);
        path.close();

        Canvas canvas = new Canvas(bitmap);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xCCFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, fill);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(0x66000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(2f, shortSide * 0.014f));
        canvas.drawPath(path, stroke);
    }

    // endregion

    // region --- 持久待入库目录（video_pending/<id>/）---

    /** 待入库目录根：录制中写这里，入库成功后整目录删除。 */
    private File videoPendingRoot() {
        File dir = new File(context.getFilesDir(), "video_pending");
        if (!dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** 某次录制的待入库目录 files/video_pending/&lt;id&gt;/ */
    public File videoPendingDir(String fileId) {
        File dir = new File(videoPendingRoot(), fileId);
        if (!dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** 入库成功后删除该次录制的待入库目录。 */
    public void deleteVideoPendingDir(String fileId) {
        if (fileId == null) return;
        File dir = new File(videoPendingRoot(), fileId);
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                //noinspection ResultOfMethodCallIgnored
                child.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    /** 启动扫描待入库目录：已入库就删、可播就补入库、不可播就丢弃。 */
    public int scanPendingVideoDirs() {
        File root = new File(context.getFilesDir(), "video_pending");
        File[] dirs = root.listFiles();
        if (dirs == null) return 0;
        int recovered = 0;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            String fileId = dir.getName();
            try {
                if (dbHelper.db_get("SELECT id FROM file WHERE id = ?", new JSONArray().put(fileId)) != null) {
                    deleteVideoPendingDir(fileId);       // 已入库，只是目录没删
                    continue;
                }
                File videoFile = null;
                File[] children = dir.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (child.getName().endsWith(".part")) {
                            videoFile = child;
                            break;
                        }
                    }
                }
                if (videoFile == null || !videoFile.isFile() || !isPlayableVideo(videoFile)) {
                    Log.w(TAG, "待入库录像不可用，丢弃: " + fileId);
                    dbHelper.db_run("DELETE FROM pending_file WHERE id = ?", new JSONArray().put(fileId));
                    deleteVideoPendingDir(fileId);
                    continue;
                }
                // 可播：补做收尾入库
                JSONObject pendingRow = dbHelper.db_get(
                        "SELECT parent_dir_id, name, extension FROM pending_file WHERE id = ?",
                        new JSONArray().put(fileId));
                if (pendingRow == null) {
                    deleteVideoPendingDir(fileId);
                    continue;
                }
                String parentDirId = pendingRow.optString("parent_dir_id", "root");
                String extension = pendingRow.optString("extension", "mp4");
                String realFileName = fileId + "." + extension;
                File dataDir = dataDirectory();
                if (!dataDir.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    dataDir.mkdirs();
                }
                File finalFile = new File(dataDir, realFileName);
                if (finalFile.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    finalFile.delete();
                }
                if (!moveFile(videoFile, finalFile)) {
                    Log.e(TAG, "待入库录像搬移失败: " + fileId);
                    continue;
                }
                long size = finalFile.length();
                String iconWritten = null;
                byte[] thumb = videoThumbnail(finalFile);
                File iconDir = iconDirectory();
                if (!iconDir.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    iconDir.mkdirs();
                }
                String iconName = fileId + "_icon.png";
                if (thumb != null && saveFile(thumb, new File(iconDir, iconName))) {
                    iconWritten = iconName;
                }
                dbHelper.db_run("UPDATE pending_file SET size = ?, real_icon_name = ? WHERE id = ?",
                        new JSONArray().put(size).put(iconWritten == null ? JSONObject.NULL : iconWritten).put(fileId));
                promote(fileId);
                deleteVideoPendingDir(fileId);
                recovered++;
                Log.i(TAG, "已从待入库目录恢复录像: " + fileId);
            } catch (Exception e) {
                Log.e(TAG, "恢复待入库录像失败: " + dir.getName(), e);
            }
        }
        return recovered;
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

    /**
     * 清理导入中转目录（启动时调用）。
     *
     * <p>进程被杀在「content URI → 临时文件」这段拷贝里时，还没有 pending_file 行，
     * 这些 .tmp 不会被任何流程引用或复用，只会白占空间（大图能占几十 MB，反复强杀会累积）。
     *
     * <p><b>必须在 {@link #repairPendingImports()} 之后调用</b>：修复时要靠 pending 行里的
     * tmp_photo_path 把主图补回来，先删了就等于把唯一的副本删掉。
     *
     * @return 删除的临时文件数
     */
    public int cleanImportTempDir() {
        File dir = new File(context.getFilesDir(), "import_tmp");
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int removed = 0;
        for (File child : children) {
            if (child.isFile() && child.delete()) {
                removed++;
            }
        }
        if (removed > 0) {
            Log.i(TAG, "已清理导入临时文件: " + removed + " 个");
        }
        return removed;
    }

    /** 内部存储安全余量：可用空间低于它就不再继续导入，避免把系统盘写满后整机异常。 */
    public static final long MIN_FREE_BYTES = 64L * 1024 * 1024;

    /** 是否还有空间放下 size 字节并保留安全余量。 */
    static boolean hasEnoughSpace(File dir, long size) {
        try {
            long usable = dir.getUsableSpace();
            // 拿不到值（返回 0）时不拦，交给真实写入去报错
            return usable <= 0 || usable > size + MIN_FREE_BYTES;
        } catch (Exception e) {
            return true;
        }
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

    /**
     * 把 content URI 的内容流式写进临时文件（固定 64KB 缓冲，内存占用与文件大小无关）。
     * 写完后 fsync，保证后续 rename 进 app_data 的是完整内容。
     */
    private boolean copyUriToFile(String contentUri, File target) {
        Uri uri = Uri.parse(contentUri);
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) return false;
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                total += read;
            }
            output.flush();
            output.getFD().sync();
            return total > 0;
        } catch (Exception e) {
            Log.e(TAG, "读取 content URI 失败: " + contentUri, e);
            return false;
        }
    }

    private String dedupeName(String dirId, String name) throws JSONException {
        String candidate = name;
        String base = baseNameOf(name);
        String extension = extensionOf(name);
        int counter = 1;
        while (nameTaken(dirId, candidate)) {
            candidate = base + "(" + counter + ")" + (extension.isEmpty() ? "" : "." + extension);
            counter++;
        }
        return candidate;
    }

    /**
     * 名字是否已被占用。并行导入时 file 表里看不到那些"还在导"的文件，
     * 所以 pending_file 也要一起查，否则两个 worker 会给两份文件挑到同一个新名字。
     */
    private boolean nameTaken(String dirId, String candidate) throws JSONException {
        JSONArray args = new JSONArray().put(dirId).put(candidate);
        if (dbHelper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?", args) != null) {
            return true;
        }
        return dbHelper.db_get("SELECT id FROM pending_file WHERE parent_dir_id = ? AND name = ?", args) != null;
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
     *
     * <p>直接按文件路径解码（两遍：先读边界、再降采样），不再先把整份图片读成 byte[]：
     * 大图批量导入时，那一步的 byte[] + ByteArrayOutputStream 扩容才是真正的内存杀手。
     * 任何 Throwable（含 OutOfMemoryError）都只让这一张缩略图失败，绝不能让导入线程/进程挂掉。
     */
    static byte[] iconMake(File file, String filename) {
        if (file == null || !file.isFile() || !isImageFile(filename)) return null;
        Bitmap decoded = null;
        Bitmap oriented = null;
        Bitmap scaled = null;
        try {
            // 先只解码边界获取尺寸，再按比例降采样解码，避免全尺寸解码（如 50MP 照片约 200MB）把内存挤爆
            BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
            boundsOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), boundsOptions);
            int srcWidth = boundsOptions.outWidth;
            int srcHeight = boundsOptions.outHeight;
            if (srcWidth <= 0 || srcHeight <= 0) return null;

            BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
            decodeOptions.inSampleSize = Math.max(1, Math.min(srcWidth, srcHeight) / 512);
            decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), decodeOptions);
            if (decoded == null) return null;

            // 相机直出的原图像素未旋转，方向记录在 EXIF 中，不处理会导致缩略图方向错误
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try {
                orientation = new ExifInterface(file.getAbsolutePath())
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (Throwable ignored) {
            }
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
                default: break;
            }
            oriented = decoded;
            if (!matrix.isIdentity()) {
                oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
                if (oriented != decoded) {
                    decoded.recycle();
                    decoded = null;
                }
            }

            int width = oriented.getWidth();
            int height = oriented.getHeight();
            if (width <= 0 || height <= 0) return null;
            float aspect = (float) width / (float) height;
            int newWidth, newHeight;
            if (width >= height) {
                newWidth = 256;
                newHeight = Math.max(1, Math.round(256 / aspect));
            } else {
                newHeight = 256;
                newWidth = Math.max(1, Math.round(256 * aspect));
            }
            scaled = Bitmap.createScaledBitmap(oriented, newWidth, newHeight, true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, output);
            return output.toByteArray();
        } catch (Throwable e) {
            // 超大图/损坏图可能抛 OutOfMemoryError（属于 Error，不是 Exception），必须在这里兜住
            Log.w(TAG, "生成缩略图失败: " + filename, e);
            return null;
        } finally {
            if (scaled != null && scaled != oriented && !scaled.isRecycled()) scaled.recycle();
            if (oriented != null && !oriented.isRecycled()) oriented.recycle();
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
        }
    }

    // endregion
}
