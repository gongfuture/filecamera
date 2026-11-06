package com.filecamera.apk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.os.Build;
import android.provider.MediaStore;

import android.database.Cursor;
import android.content.ContentUris;
import android.annotation.SuppressLint;
import android.graphics.Canvas;
import java.util.Deque;
import java.util.ArrayDeque;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Stack;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import android.util.Pair;
import java.util.Queue;
import java.util.LinkedList;
import java.util.Iterator;
import android.media.MediaScannerConnection;
import android.webkit.MimeTypeMap;
import java.util.List;
import java.util.HashMap;
import androidx.exifinterface.media.ExifInterface;

public class BackServer {
    private WebView webView;
    private Context context;
    private DatabaseHelper db_helper;

    // GPS 缓存（由 MainActivity 定期更新）
    private volatile double gpsLat = 0;
    private volatile double gpsLng = 0;
    private volatile long gpsTime = 0;
    private static final long GPS_TIMEOUT_MS = 20000;

    /** 删除/覆盖时每批处理多少个文件：任务与删行同事务提交，崩溃最多回放这一批。 */
    private static final int FILE_BATCH_SIZE = 100;

    public BackServer(WebView webView, Context context) {
        this.webView = webView;
        this.context = context;
        this.db_helper = DatabaseHelper.getInstance(context);

        // 初始化时创建必要的目录
        initDirectories();
    }

    /**
     * 由 MainActivity 在每次 GPS 更新时调用，缓存最新位置供 fetchAdcodeByGps 使用。
     */
    public void updateGpsLocation(double lat, double lng) {
        this.gpsLat = lat;
        this.gpsLng = lng;
        this.gpsTime = System.currentTimeMillis();
    }
	/** 复制视频/大文件的临时目录：和 app_data 同分区，写完 rename 进目标目录；启动时清空。 */
	private File createCopyTempDirectory() {
		File dir = new File(context.getFilesDir(), "copy_tmp");
		if (dir.isDirectory() || dir.mkdirs()) return dir;
		return null;
	}

	private File createDataDirectory() {
		try {
			File data_dir = new File(context.getFilesDir(), "app_data");

			if (!data_dir.exists()) {
				if (!data_dir.mkdirs()) {
					showToast("无法创建图标目录: " + data_dir.getAbsolutePath());
					return null;
				}
			}

			return data_dir;
		} catch (Exception e) {
			showToast("创建图标目录失败: " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}
	// 创建图标存储目录（WebView可访问）
    private File createIconDirectory() {
        try {
            // 使用应用内部存储的icons目录
            File icon_dir = new File(context.getFilesDir(), "icons");

            if (!icon_dir.exists()) {
                if (!icon_dir.mkdirs()) {
                    showToast("无法创建图标目录: " + icon_dir.getAbsolutePath());
                    return null;
                }
            }

            return icon_dir;
        } catch (Exception e) {
            showToast("创建图标目录失败: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    // 初始化目录
    private void initDirectories() {
        // 创建图标存储目录
        File icon_dir = createIconDirectory();
        if (icon_dir == null) {
            showToast("图标目录创建失败");
        }

        // 测试数据目录创建
        File data_dir = createDataDirectory();
        if (data_dir == null) {
            showToast("数据目录创建失败");
        }
    }
    private void call_js_callback(String task_id, boolean success, Object data, String error_message) {
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
    /**
     * 「导入文件 / 导入文件夹」：读取 content URI 的内容并入库。
     * 入库逻辑（查重重命名、seqnumber、缩略图、落 app_data、写 file 表）统一由 FileImporter 负责，
     * 与相机拍摄走同一条路径（含 pending_file 两阶段提交与崩溃修复）。
     */
    @JavascriptInterface
    public void upload_file(String task_id, String json_data) {
        new Thread(() -> {
            try {
                JSONObject data = new JSONObject(json_data);
                String content_uri = data.getString("contentUri");
                String dir_id = data.getString("dirId");
                String file_name = data.getString("fileName");

                JSONObject result_data = new FileImporter(context)
                        .importFromContentUri(content_uri, dir_id, file_name);
                call_js_callback(task_id, true, result_data, null);
            } catch (Exception e) {
                showToast("上传文件时发生错误: " + e.getMessage());
                call_js_callback(task_id, false, null, e.getMessage());
            }
        }).start();
    }
	@JavascriptInterface
	public void getFileData(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject data = new JSONObject(json_data);
				JSONArray parentpath_arr = data.getJSONArray("parentpath_arr");
				String fileName = data.getString("fileName");
				String table = data.getString("table");

				if (!table.equals("file") && !table.equals("icon")) {
					call_js_callback(task_id, false, null, "无效的表名");
					return;
				}

                // 通过路径找到父目录ID
                String parent_dir_id = findDirIdByPath(parentpath_arr);

                if(parent_dir_id == null){
                    call_js_callback(task_id, false, null, "路径不存在");
                    return;
                }

				// 从file表获取文件ID
				JSONArray file_query_arr = new JSONArray().put(parent_dir_id).put(fileName);
				JSONObject file_info = db_helper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?", file_query_arr);

				if (file_info == null) {
					call_js_callback(task_id, false, null, "文件不存在");
					return;
				}

                String file_id = file_info.getString("id");

                // 根据请求的table类型，决定查询哪个字段
                String column_to_select = table.equals("file") ? "real_file_name" : "real_icon_name";
				String sql_query = "SELECT " + column_to_select + " FROM file WHERE id = ?";

				JSONArray data_query_arr = new JSONArray().put(file_id);
				JSONObject file_data_record = db_helper.db_get(sql_query, data_query_arr);

				if (file_data_record == null) {
					call_js_callback(task_id, false, null, "文件记录不存在");
					return;
				}

				String actual_filename = file_data_record.optString(column_to_select, null);
				if (actual_filename == null || actual_filename.isEmpty()) {
					call_js_callback(task_id, false, null, table.equals("file") ? "文件名为空" : "该文件没有图标");
					return;
				}

				File target_dir = table.equals("file") ? createDataDirectory() : createIconDirectory();

				if (target_dir == null) {
					call_js_callback(task_id, false, null, "无法访问文件目录");
					return;
				}

				File target_file = new File(target_dir, actual_filename);
				if (!target_file.exists()) {
					call_js_callback(task_id, false, null, "文件不存在: " + actual_filename);
					return;
				}

				byte[] file_bytes = readFileToBytes(target_file);
				if (file_bytes == null) {
					call_js_callback(task_id, false, null, "读取文件失败");
					return;
				}

				String base64_data = Base64.encodeToString(file_bytes, Base64.NO_WRAP);

				String mime_type = getMimeType(actual_filename);
				String result_data = "data:" + mime_type + ";base64," + base64_data;

				call_js_callback(task_id, true, result_data, null);

			} catch (Exception e) {
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}
	// 读取文件到字节数组
	private byte[] readFileToBytes(File file) {
		try (FileInputStream fis = new FileInputStream(file);
			 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

			byte[] buffer = new byte[8192];
			int bytes_read;
			while ((bytes_read = fis.read(buffer)) != -1) {
				baos.write(buffer, 0, bytes_read);
			}
			return baos.toByteArray();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	// 根据文件扩展名获取MIME类型
	private String getMimeType(String filename) {
		String extension = "";
		int dot_index = filename.lastIndexOf('.');
		if (dot_index > 0 && dot_index < filename.length() - 1) {
			extension = filename.substring(dot_index + 1).toLowerCase();
		}

		switch (extension) {
			case "jpg":
			case "jpeg":
				return "image/jpeg";
			case "png":
				return "image/png";
			case "gif":
				return "image/gif";
			case "bmp":
				return "image/bmp";
			case "webp":
				return "image/webp";
			case "svg":
				return "image/svg+xml";
			case "pdf":
				return "application/pdf";
			case "txt":
				return "text/plain";
			case "json":
				return "application/json";
			case "mp4":
				return "video/mp4";
			case "mp3":
				return "audio/mpeg";
			default:
				return "application/octet-stream";
		}
	}
	@JavascriptInterface
	public void get_index_by_id(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject data = new JSONObject(json_data);
				String id = data.getString("id");

				// 1. 获取目录本身的信息
				String dirInfoQuery = "SELECT * FROM dir WHERE id = ?";
				JSONObject dir_info = db_helper.db_get(dirInfoQuery, new JSONArray().put(id));

				if (dir_info == null) {
					call_js_callback(task_id, false, null, "文件夹不存在");
					return;
				}

				// 2. 从目录信息中获取文件和子目录的排序模式
				int dirSortMode = dir_info.optInt("dirsortmode", 2); // 默认为2 (名称降序)
				int fileSortMode = dir_info.optInt("filesortmode", 2); // 默认为2 (名称降序)

				// 3. 使用新的辅助函数生成排序子句
				String dirOrderBy = getOrderByClause("dir", dirSortMode);
				String fileOrderBy = getOrderByClause("file", fileSortMode);

				// 4. 查询该目录下的所有子文件 (带排序)
				String fileQuery = "SELECT * FROM file WHERE parent_dir_id = ? ORDER BY " + fileOrderBy;
				JSONArray file_index_arr = db_helper.db_all(fileQuery, new JSONArray().put(id));

				// 4.1 一致性自检：主图已不在磁盘上的行不可能被正常打开（否则图片页会白屏/卡住），
				//     这里直接把这类"坏记录"连同缩略图一起清掉，再返回干净列表。
				file_index_arr = dropRecordsWithMissingFile(file_index_arr);

				// 5. 查询该目录下的所有子目录 (带排序)
				String dirQuery = "SELECT id, name, type, createtime, seqnumber FROM dir WHERE parent_dir_id = ? ORDER BY " + dirOrderBy;
				JSONArray dir_index_arr = db_helper.db_all(dirQuery, new JSONArray().put(id));

				// --- 代码修改开始 ---
				// 5.1 检查请求中是否需要查询封面
				boolean shouldQueryCover = data.optBoolean("cover");

				// 如果请求中 cover 属性为 true，则用一条 SQL 给所有子目录补封面
				if (shouldQueryCover) {
					fillDirCovers(dir_index_arr, id);
				}
				// --- 代码修改结束 ---

				// 6. 构建返回结果
				JSONObject result = new JSONObject();
				result.put("file_index_arr", file_index_arr); // 此数组已在后端排好序
				result.put("dir_index_arr", dir_index_arr);   // 此数组已在后端排好序 (并可能增加了 cover 属性)
				result.put("dir_info", dir_info);

				call_js_callback(task_id, true, result, null);

			} catch (Exception e) {
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}

	/** 进度遮罩宿主：BackServer 由 MainActivity 构造，context 就是它；找不到就退化为不显示进度。 */
	private MainActivity getOverlayHost() {
		return (context instanceof MainActivity) ? (MainActivity) context : null;
	}

	/**
	 * 一次性给子目录列表补封面：一条 SQL 查出当前目录下所有子目录的封面。
	 *
	 * <p>原来是 for 循环里"每个子目录查一次"，N 个子目录就是 N 条 SQL、N 次语句编译和 N 个游标。
	 * 子目录集合本身就能用 {@code dir.parent_dir_id = 当前目录} 表达，所以直接 JOIN dir 表即可，
	 * 不需要把 id 拼成 IN (?,?,...)：既避开了 SQLite 的 SQLITE_MAX_VARIABLE_NUMBER 上限
	 * （Android 10/11 只有 999），也省掉了分批拼参数。
	 *
	 * <p>两个刻意的细节：
	 * <ul>
	 *   <li>CROSS JOIN + INDEXED BY 把执行计划钉死成 dir 驱动、file 走 (parent_dir_id) 索引。
	 *       没有 ANALYZE 统计信息时，SQLite 也可能反过来扫 idx_file_seqnumber 上所有
	 *       seqnumber=1 的行，结果正确但耗时随全库这类行的数量线性增长。</li>
	 *   <li>过滤 real_icon_name 为 NULL/空的行。旧实现在这种情况下会通过
	 *       {@code JSONObject.NULL.toString()} 取出字符串 "null" 塞给前端，
	 *       封面变成 {@code <img src=".../icons/null">} 的碎图。</li>
	 * </ul>
	 */
	private void fillDirCovers(JSONArray dir_index_arr, String parent_dir_id) throws JSONException {
		if (dir_index_arr == null || dir_index_arr.length() == 0) return;

		String cover_query =
				"SELECT f.parent_dir_id AS dir_id, f.real_icon_name AS cover_name " +
				"FROM dir d CROSS JOIN file f INDEXED BY idx_file_parent_dir_id " +
				"WHERE d.parent_dir_id = ? AND f.parent_dir_id = d.id " +
				"AND f.seqnumber = 1 " +
				"AND f.real_icon_name IS NOT NULL AND f.real_icon_name <> '' " +
				"AND f.extension IN ('jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg') " +
				"GROUP BY f.parent_dir_id";

		JSONArray cover_rows = db_helper.db_all(cover_query, new JSONArray().put(parent_dir_id));
		if (cover_rows == null || cover_rows.length() == 0) return;

		HashMap<String, String> cover_by_dir = new HashMap<>();
		for (int i = 0; i < cover_rows.length(); i++) {
			JSONObject row = cover_rows.optJSONObject(i);
			if (row == null || row.isNull("dir_id") || row.isNull("cover_name")) continue;
			cover_by_dir.put(row.optString("dir_id", ""), row.optString("cover_name", ""));
		}

		for (int i = 0; i < dir_index_arr.length(); i++) {
			JSONObject sub_dir = dir_index_arr.optJSONObject(i);
			if (sub_dir == null) continue;
			String cover = cover_by_dir.get(sub_dir.optString("id", ""));
			if (cover != null && !cover.isEmpty()) {
				sub_dir.put("cover", cover);
			}
		}
	}

	private String getOrderByClause(String type, int mode) {
		String orderBy;

		switch (mode) {
			case 1: // 按名称升序
				orderBy = "name ASC";
				break;
			case 3: // 按序号升序 (增加name作为第二排序，保证顺序稳定)
				orderBy = "seqnumber ASC, name ASC";
				break;
			case 4: // 按序号降序
				orderBy = "seqnumber DESC, name DESC";
				break;
			case 5: // 按创建时间升序
				orderBy = "createtime ASC";
				break;
			case 6: // 按创建时间降序
				orderBy = "createtime DESC";
				break;
			case 7: // 按扩展名/名称升序
				if ("file".equals(type)) {
					orderBy = "extension ASC, name ASC";
				} else { // 文件夹没有扩展名，按名称排序
					orderBy = "name ASC";
				}
				break;
			case 8: // 按扩展名/名称降序
				if ("file".equals(type)) {
					orderBy = "extension DESC, name DESC";
				} else { // 文件夹没有扩展名，按名称排序
					orderBy = "name DESC";
				}
				break;
			case 2: // 按名称降序 (默认)
			default:
				orderBy = "name DESC";
				break;
		}
		return orderBy;
	}
	/**
	 * 一致性自检：把"数据库里有、磁盘上主图已不存在"的记录清掉（连带缩略图），
	 * 避免列表里出现点开就白屏/卡住的坏记录。只检查当前目录下的行，开销可忽略。
	 */
	private JSONArray dropRecordsWithMissingFile(JSONArray rows) {
		if (rows == null || rows.length() == 0) return rows;
		File appDataDir = new File(context.getFilesDir(), "app_data");
		File iconDir = new File(context.getFilesDir(), "icons");
		JSONArray cleaned = new JSONArray();
		List<String> dead_ids = new ArrayList<>();
		for (int i = 0; i < rows.length(); i++) {
			JSONObject row = rows.optJSONObject(i);
			if (row == null) continue;
			String realFileName = row.optString("real_file_name", "");
			if (realFileName.isEmpty() || new File(appDataDir, realFileName).isFile()) {
				cleaned.put(row);
				continue;
			}
			String fileId = row.optString("id", "");
			String realIconName = row.isNull("real_icon_name") ? null : row.optString("real_icon_name", null);
			if (!fileId.isEmpty()) dead_ids.add(fileId);
			try {
				if (realIconName != null && !realIconName.isEmpty()) {
					File icon = new File(iconDir, realIconName);
					if (icon.isFile()) {
						//noinspection ResultOfMethodCallIgnored
						icon.delete();
					}
				}
				android.util.Log.w("BackServer", "主图缺失，已清理库记录: " + fileId + " / " + realFileName);
			} catch (Exception e) {
				android.util.Log.e("BackServer", "清理缺失文件的库记录失败: " + fileId, e);
			}
		}
		// 坏记录通常只是一两条；真出现成百上千条时，逐条 DELETE 会把打开文件夹拖慢，统一分批删。
		deleteFileRowsByIds(dead_ids);
		return cleaned;
	}

	/** 按 id 批量删除 file 行：每批 100 个占位符，远低于 SQLite 的参数上限。 */
	private void deleteFileRowsByIds(List<String> fileIds) {
		if (fileIds == null || fileIds.isEmpty()) return;
		int batch_size = 100;
		for (int i = 0; i < fileIds.size(); i += batch_size) {
			int end_index = Math.min(i + batch_size, fileIds.size());
			List<String> batch = fileIds.subList(i, end_index);

			StringBuilder placeholders = new StringBuilder();
			JSONArray params = new JSONArray();
			for (int j = 0; j < batch.size(); j++) {
				if (j > 0) placeholders.append(",");
				placeholders.append("?");
				params.put(batch.get(j));
			}

			try {
				db_helper.db_run("DELETE FROM file WHERE id IN (" + placeholders + ")", params);
			} catch (Exception e) {
				android.util.Log.e("BackServer", "批量清理缺失文件的库记录失败", e);
			}
		}
	}

	@JavascriptInterface
	public void createFolder(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject data = new JSONObject(json_data);
				String name = data.getString("name");
				String parentId = data.getString("parentId");

				// 检查父目录是否存在
				if (getDataById(parentId, "dir") == null) {
                    call_js_callback(task_id, false, null, "父文件夹不存在");
                    return;
                }

                // 检查同名文件夹是否存在
                JSONArray checkParams = new JSONArray().put(parentId).put(name);
                if(db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", checkParams) != null) {
                    call_js_callback(task_id, false, null, "文件夹已经存在");
                    return;
                }

				long create_time = System.currentTimeMillis();
				String dir_id = IdGenerator.get_id_by_time();
				long seq_number = FileImporter.getSeqNumberFromString(name);

				JSONArray create_params = new JSONArray();
				create_params.put(dir_id);
				create_params.put(name);
				create_params.put(parentId);
				create_params.put("dir");
				create_params.put(create_time);
				create_params.put(2); // filesortmode
				create_params.put(2); // dirsortmode
				create_params.put(seq_number);

                // 简化操作：直接插入新目录，无需更新父目录
				JSONObject create_result = db_helper.db_run(
					"INSERT INTO dir (id, name, parent_dir_id, type, createtime, filesortmode, dirsortmode, seqnumber) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
					create_params
				);

				if (create_result.optLong("changes", 0) == 0) {
					call_js_callback(task_id, false, null, "创建文件夹失败");
					return;
				}

				// 返回新创建的文件夹信息
				JSONObject result = new JSONObject();
				result.put("id", dir_id);
				result.put("name", name);
				result.put("type", "dir");
				result.put("createtime", create_time);
				result.put("seqnumber", seq_number);

				call_js_callback(task_id, true, result, null);

			} catch (Exception e) {
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}
	@JavascriptInterface
	public void delete_file_by_id(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject data = new JSONObject(json_data);
				String file_id = data.getString("file_id");

				if (file_id == null || file_id.isEmpty()) {
					call_js_callback(task_id, false, null, "文件ID不能为空");
					return;
				}
                // 简化操作：不再需要更新父目录的快照
				// 直接批量删除文件即可
                List<String> file_id_to_delete = new ArrayList<>();
                file_id_to_delete.add(file_id);
                deleteFilesNow(file_id_to_delete);

                call_js_callback(task_id, true, null, null);

			} catch (Exception e) {
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}
	@JavascriptInterface
	public void delete_items(String task_id, String json_data) {
		new Thread(() -> {
			MainActivity overlay = getOverlayHost();
			try {
				JSONObject data = new JSONObject(json_data);
				JSONArray items = data.getJSONArray("items");
				String parentId = data.getString("parentId");

				// 检查父目录是否存在
				if (getDataById(parentId, "dir") == null) {
                    call_js_callback(task_id, false, null, "父文件夹不存在");
                    return;
                }

				// 进度只走原生遮罩，H5 不再叠一层 loading
				final int totalItems = items.length();
				if (overlay != null) overlay.showProgress("正在删除…", "正在统计要删除的内容");

                List<String> all_file_ids = new ArrayList<>();
                List<String> all_dir_ids = new ArrayList<>();

                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    String name = item.getString("name");
                    String type = item.getString("type");

                    if (type.equals("dir")) {
                        // 查找目录ID
                        JSONArray findDirParams = new JSONArray().put(parentId).put(name);
                        JSONObject dirInfo = db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", findDirParams);
                        if (dirInfo != null) {
                            String dirId = dirInfo.getString("id");
                            CollectResult result = collectDirectoryIds(dirId);
                            all_file_ids.addAll(result.fileIds);
                            all_dir_ids.addAll(result.dirIds);
                        }
                    } else if (type.equals("file")) {
                        // 查找文件ID
                        JSONArray findFileParams = new JSONArray().put(parentId).put(name);
                        JSONObject fileInfo = db_helper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?", findFileParams);
                        if(fileInfo != null) {
                            all_file_ids.add(fileInfo.getString("id"));
                        }
                    }
                    if (overlay != null) overlay.updateProgress("正在统计", i + 1, totalItems, name);
                }

                // 统计阶段按"选中项"计数，删除阶段才是真正的耗时部分：
                // 文件要逐个删物理文件+图标，只选一个文件夹时旧的按"选中项"计数会瞬间 1/1，
                // 之后遮罩一直是转圈的不确定态。这里改成按"文件数 + 目录数"计，逐项 tick。
                final int totalUnits = all_file_ids.size() + all_dir_ids.size();
                if (overlay != null) {
                    overlay.showProgress("正在删除…",
                            "共 " + all_file_ids.size() + " 个文件、" + all_dir_ids.size() + " 个文件夹");
                }
                BatchProgress progress = new BatchProgress(overlay, "正在删除", totalUnits, 0);

                // 每 100 个文件一个事务：日志 + 删行一起提交，提交后才删物理文件；
                // 中途被杀最多留下这一批的任务，启动时由 repairPendingTasks 幂等重放。
                for (int i = 0; i < all_file_ids.size(); i += FILE_BATCH_SIZE) {
                    deleteFileBatch(all_file_ids.subList(i,
                            Math.min(i + FILE_BATCH_SIZE, all_file_ids.size())), progress);
                }
                deleteDirectoryRows(all_dir_ids, progress);

                if (overlay != null) overlay.finishProgress("删除完成", "正在刷新列表…");
                call_js_callback(task_id, true, null, null);

			} catch (Exception e) {
				if (overlay != null) overlay.hideProgress();
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}

	// 收集结果类
	private static class CollectResult {
		public List<String> fileIds;
		public List<String> dirIds;

		public CollectResult() {
			this.fileIds = new ArrayList<>();
			this.dirIds = new ArrayList<>();
		}
	}

	// [重构] 使用SQL查询递归收集所有待删除的ID
	private CollectResult collectDirectoryIds(String dir_id) throws JSONException {
		CollectResult result = new CollectResult();
		Queue<String> dir_queue = new LinkedList<>();
		dir_queue.offer(dir_id);

		while (!dir_queue.isEmpty()) {
			String current_dir_id = dir_queue.poll();
			result.dirIds.add(current_dir_id); // 收集当前目录ID

			// 收集当前目录下的所有文件ID
            JSONArray files = db_helper.db_all("SELECT id FROM file WHERE parent_dir_id = ?", new JSONArray().put(current_dir_id));
            for(int i = 0; i < files.length(); i++) {
                result.fileIds.add(files.getJSONObject(i).getString("id"));
            }

			// 将子目录加入队列
            JSONArray dirs = db_helper.db_all("SELECT id FROM dir WHERE parent_dir_id = ?", new JSONArray().put(current_dir_id));
            for(int i = 0; i < dirs.length(); i++) {
                dir_queue.offer(dirs.getJSONObject(i).getString("id"));
            }
		}

		return result;
	}

	/** 一条待删文件的临时信息（任务已提交，等物理文件删完再销账）。 */
	private static final class PendingDelete {
		final String taskId;
		final String realFile;
		final String realIcon;
		final String displayName;

		PendingDelete(String taskId, String realFile, String realIcon, String displayName) {
			this.taskId = taskId;
			this.realFile = realFile;
			this.realIcon = realIcon;
			this.displayName = displayName;
		}
	}

	/**
	 * 删除一批文件：先在一个事务里写 pending_file(op=delete) + 删 file 行，提交后才删物理文件，
	 * 最后销账。中途被杀时，任务行会让下次启动把没删完的物理文件补删掉。
	 */
	private void deleteFileBatch(List<String> file_ids, BatchProgress progress) throws JSONException {
		if (file_ids == null || file_ids.isEmpty()) return;

		File dataDir = createDataDirectory();
		File iconDir = createIconDirectory();

		StringBuilder placeholders = new StringBuilder();
		JSONArray params = new JSONArray();
		for (int i = 0; i < file_ids.size(); i++) {
			if (i > 0) placeholders.append(",");
			placeholders.append("?");
			params.put(file_ids.get(i));
		}
		String where = placeholders.toString();

		// 行删掉之后就查不到物理文件名了，先取出来并写进待执行表
		JSONArray rows = db_helper.db_all(
				"SELECT id, name, parent_dir_id, real_file_name, real_icon_name FROM file WHERE id IN (" + where + ")",
				params);
		List<PendingDelete> pending = new ArrayList<>(rows.length());

		db_helper.db_run("BEGIN TRANSACTION", new JSONArray());
		try {
			for (int i = 0; i < rows.length(); i++) {
				JSONObject row = rows.getJSONObject(i);
				String fileId = row.getString("id");
				String realFile = readColumn(row, "real_file_name");
				String realIcon = readColumn(row, "real_icon_name");
				String displayName = readColumn(row, "name");
				String parentId = readColumn(row, "parent_dir_id");
				FileImporter.recordPendingDelete(db_helper, fileId, displayName,
						parentId == null ? "root" : parentId, realFile, realIcon);
				pending.add(new PendingDelete(fileId, realFile, realIcon, displayName));
			}
			db_helper.db_run("DELETE FROM file WHERE id IN (" + where + ")", params);
			db_helper.db_run("COMMIT", new JSONArray());
		} catch (Exception e) {
			db_helper.db_run("ROLLBACK", new JSONArray());
			throw e;
		}

		// 提交成功后再删文件；删不掉的保留任务，下次启动重试
		List<String> cleared = new ArrayList<>(pending.size());
		for (PendingDelete item : pending) {
			boolean ok = deletePhysicalFile(dataDir, item.realFile)
					& deletePhysicalFile(iconDir, item.realIcon);
			if (ok) cleared.add(item.taskId);
			if (progress != null) progress.tick(item.displayName);
		}
		// 极端情况下行在收集后被并发删掉：补齐 tick，避免收尾差一截
		for (int i = rows.length(); i < file_ids.size(); i++) {
			if (progress != null) progress.tick(null);
		}
		if (!cleared.isEmpty()) {
			db_helper.db_run("BEGIN TRANSACTION", new JSONArray());
			try {
				FileImporter.clearPending(db_helper, cleared);
				db_helper.db_run("COMMIT", new JSONArray());
			} catch (Exception e) {
				db_helper.db_run("ROLLBACK", new JSONArray());
				android.util.Log.e("DeleteTask", "清理待执行任务失败，留待启动恢复", e);
			}
		}
	}

	/** 读可空列：缺失/JSON null 都返回 null。 */
	private static String readColumn(JSONObject row, String column) {
		return row.isNull(column) ? null : row.optString(column, null);
	}

	/** @return true 表示文件不存在或已删掉；删不掉返回 false，保留日志下次重试。 */
	private boolean deletePhysicalFile(File dir, String name) {
		if (dir == null || name == null || name.isEmpty()) return true;
		File file = new File(dir, name);
		if (!file.exists()) return true;
		if (file.delete()) return true;
		android.util.Log.w("DeleteWarning", "删除物理文件失败: " + file.getAbsolutePath());
		return false;
	}

	/** 覆盖同名文件等单点场景：按批走同一套"日志 → 删行 → 删文件 → 销账"。 */
	private void deleteFilesNow(List<String> file_ids) throws JSONException {
		for (int i = 0; i < file_ids.size(); i += FILE_BATCH_SIZE) {
			deleteFileBatch(file_ids.subList(i, Math.min(i + FILE_BATCH_SIZE, file_ids.size())), null);
		}
	}

	private void deleteDirectoryRows(List<String> dir_ids, BatchProgress progress) throws JSONException {
		if (dir_ids == null || dir_ids.isEmpty()) return;

		int batch_size = 100;

		for (int i = 0; i < dir_ids.size(); i += batch_size) {
			int end_index = Math.min(i + batch_size, dir_ids.size());
			List<String> batch = dir_ids.subList(i, end_index);

			StringBuilder placeholders = new StringBuilder();
			JSONArray params = new JSONArray();

			for (int j = 0; j < batch.size(); j++) {
				if (j > 0) placeholders.append(",");
				placeholders.append("?");
				params.put(batch.get(j));
			}

			db_helper.db_run("DELETE FROM dir WHERE id IN (" + placeholders + ")", params);

			// 目录只是删行，通常瞬间完成；但总数里算了它们，这里同步补齐，避免收尾停在 99%
			if (progress != null) {
				for (int j = 0; j < batch.size(); j++) progress.tick(null);
			}
		}
	}

	@JavascriptInterface
	public void rename_item(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject data = new JSONObject(json_data);
				String oldName = data.getString("oldName");
				String newName = data.getString("newName");
				String type = data.getString("type");
				String parentId = data.getString("parentId");

				if (type.equals("dir")) {
					// 检查新名称是否已存在
					JSONArray checkParams = new JSONArray().put(parentId).put(newName);
					if (db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", checkParams) != null) {
						call_js_callback(task_id, false, null, "文件夹中有相同名字的文件夹");
						return;
					}

					// 查找要重命名的文件夹
					JSONArray findParams = new JSONArray().put(parentId).put(oldName);
					JSONObject dirToRename = db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", findParams);
					if (dirToRename == null) {
						call_js_callback(task_id, false, null, "原文件夹不存在");
						return;
					}

					String dirId = dirToRename.getString("id");
					long new_seq_number = FileImporter.getSeqNumberFromString(newName);

					// 执行重命名
					JSONArray updateParams = new JSONArray().put(newName).put(new_seq_number).put(dirId);
					db_helper.db_run("UPDATE dir SET name = ?, seqnumber = ? WHERE id = ?", updateParams);

				} else if (type.equals("file")) {
					// 检查新名称是否已存在
					JSONArray checkParams = new JSONArray().put(parentId).put(newName);
					if(db_helper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?", checkParams) != null) {
						call_js_callback(task_id, false, null, "文件夹中有相同名字的文件");
						return;
					}

					// 查找要重命名的文件
					JSONArray findParams = new JSONArray().put(parentId).put(oldName);
					JSONObject fileToRename = db_helper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?", findParams);
					if (fileToRename == null) {
						call_js_callback(task_id, false, null, "原文件不存在");
						return;
					}
					String fileId = fileToRename.getString("id");

					// ===== 修复部分：正确解析扩展名和序号 =====
					// 解析扩展名
					String extension = "";
					int lastDotIndex = newName.lastIndexOf('.');
					if (lastDotIndex > 0 && lastDotIndex < newName.length() - 1) {
						extension = newName.substring(lastDotIndex + 1);
					}
					String name_part = lastDotIndex > 0 ? newName.substring(0, lastDotIndex) : newName;
					long seq_number = FileImporter.getSeqNumberFromString(name_part);
					JSONArray updateFileParams = new JSONArray()
						.put(newName)
						.put(extension)
						.put(seq_number)
						.put(fileId);
					db_helper.db_run("UPDATE file SET name = ?, extension = ?, seqnumber = ? WHERE id = ?", updateFileParams);

				} else {
					call_js_callback(task_id, false, null, "无效的类型");
					return;
				}

				call_js_callback(task_id, true, null, null);

			} catch (Exception e) {
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}

    /**
     * [重构] 辅助函数：通过关系查询根据路径数组获取目录。
     * @param path_array 路径数组，例如 ["", "folderA", "folderB"]
     * @return 找到的目录的JSONObject，如果路径不存在则返回null
     */
    private JSONObject getDirByPath(JSONArray path_array) throws JSONException {
        String id = "root";
        for (int i = 1; i < path_array.length(); i++) {
            String path_segment = path_array.optString(i, "");
            if (path_segment.isEmpty()) continue;

            JSONArray query_params = new JSONArray().put(id).put(path_segment);
            JSONObject child_dir = db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", query_params);

            if (child_dir == null) return null; // 路径中某一段不存在
            id = child_dir.getString("id");
        }

        // 返回最终找到的目录的完整信息
        return getDataById(id, "dir");
    }


	/** 单项操作结果：批量循环里用，避免每个条目都往 JS 回一次回调。 */
	private static final class ItemResult {
		static final int CODE_OK = 0;
		static final int CODE_NEED_CONFIRM = 1;
		static final int CODE_ERROR = 2;
		static final ItemResult OK = new ItemResult(CODE_OK, null);

		final int code;
		final String message;

		ItemResult(int code, String message) {
			this.code = code;
			this.message = message;
		}

		static ItemResult needConfirm(String message) {
			return new ItemResult(CODE_NEED_CONFIRM, message);
		}

		static ItemResult error(String message) {
			return new ItemResult(CODE_ERROR, message);
		}
	}

	private static JSONArray sliceFrom(JSONArray arr, int from) throws JSONException {
		JSONArray out = new JSONArray();
		for (int i = Math.max(0, from); i < arr.length(); i++) {
			out.put(arr.get(i));
		}
		return out;
	}

	/** 批量操作的进度计数器：只往原生遮罩上报，不经过 JS。 */
	private static final class BatchProgress {
		private final MainActivity overlay;
		private final String title;
		private final int total;
		private int done;

		BatchProgress(MainActivity overlay, String title, int total, int done) {
			this.overlay = overlay;
			this.title = title;
			this.total = Math.max(1, total);
			this.done = done;
		}

		/** 推进若干单元（目录整棵移动时一次补齐，和复制/合并的逐项推进保持同一套计数）。 */
		void advance(int units, String name) {
			if (units <= 0) return;
			done += units;
			if (overlay != null) overlay.updateProgress(title, done, total, name);
		}

		void tick(String name) {
			advance(1, name);
		}

		int done() {
			return done;
		}
	}

	/** 统计一个目录的工作量：目录自身 + 所有子孙目录 + 所有子孙文件（移动合并与复制共用同一套计数）。 */
	private int countTreeUnits(String parentDirId, String dirName) throws JSONException {
		JSONObject dir = db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?",
				new JSONArray().put(parentDirId).put(dirName));
		if (dir == null) return 1; // 源目录不存在时按 1 个单元算，交给 copyDirectory/moveDirectory 报错

		int units = 1;
		Queue<String> queue = new LinkedList<>();
		queue.offer(dir.getString("id"));
		while (!queue.isEmpty()) {
			String currentId = queue.poll();
			JSONArray files = db_helper.db_all("SELECT id FROM file WHERE parent_dir_id = ?", new JSONArray().put(currentId));
			units += files.length();
			JSONArray subDirs = db_helper.db_all("SELECT id FROM dir WHERE parent_dir_id = ?", new JSONArray().put(currentId));
			for (int i = 0; i < subDirs.length(); i++) {
				units += 1;
				queue.offer(subDirs.getJSONObject(i).getString("id"));
			}
		}
		return units;
	}

	/**
	 * 批量移动：整批只调用一次，进度全部在 Java 侧刷原生遮罩。
	 *
	 * <p>冲突协议：遇到同名文件/目录时立即停下，把 needConfirm + 剩余项 + 已完成数回给 JS；
	 * JS 弹确认框后带 forceFirst=true 重新调用，剩下的条目里只有第一条按"覆盖/合并"处理。
	 */
	@JavascriptInterface
	public void move_items(String task_id, String json_data) {
		new Thread(() -> runBatchOps(task_id, json_data, true)).start();
	}

	/** 批量复制：与 move_items 同一套协议，进度同样只在 Java 侧更新。 */
	@JavascriptInterface
	public void copy_items(String task_id, String json_data) {
		new Thread(() -> runBatchOps(task_id, json_data, false)).start();
	}

	private void runBatchOps(String task_id, String json_data, boolean is_move) {
		MainActivity overlay = getOverlayHost();
		String action = is_move ? "移动" : "复制";
		String title = "正在" + action;
		try {
			JSONObject data = new JSONObject(json_data);
			JSONArray items = data.getJSONArray("items");
			JSONArray sourcePath = data.getJSONArray(is_move ? "sourcePath" : "sourceArray");
			JSONArray targetPath = data.getJSONArray(is_move ? "targetPath" : "targetArray");
			int done = data.optInt("done", 0);
			int total = data.optInt("total", 0);
			boolean forceFirst = data.optBoolean("forceFirst", false);

			JSONObject source_dir = getDirByPath(sourcePath);
			JSONObject target_dir = getDirByPath(targetPath);
			if (source_dir == null || target_dir == null) {
				call_js_callback(task_id, false, null, is_move ? "源路径或目标路径不存在" : "源目录或目标目录不存在");
				return;
			}
			String sourceDirId = source_dir.getString("id");
			String targetDirId = target_dir.getString("id");

			if (is_move && sourceDirId.equals(targetDirId)) {
				call_js_callback(task_id, true, null, null); // 移动到相同位置，视为成功
				return;
			}

			if (overlay != null) overlay.showProgress(title + "…", "");

			// 首次调用按"文件/目录节点数"算总工作量（移动合并、复制文件夹内部都能逐项推进）。
			// 每项的 units 会随 items 一起回传给 JS，续跑/跳过时不必重新遍历目录树。
			if (total <= 0) {
				if (overlay != null) overlay.showProgress(title + "…", "正在统计要" + action + "的内容");
				for (int i = 0; i < items.length(); i++) {
					JSONObject item = items.getJSONObject(i);
					int units = "file".equals(item.getString("type"))
							? 1
							: countTreeUnits(sourceDirId, item.getString("name"));
					item.put("units", units);
					total += units;
				}
			}
			BatchProgress progress = new BatchProgress(overlay, title, total, done);

			for (int i = 0; i < items.length(); i++) {
				JSONObject item = items.getJSONObject(i);
				String name = item.getString("name");
				String type = item.getString("type");
				boolean force = forceFirst && i == 0;
				int itemUnits = item.optInt("units", 0);
				if (itemUnits <= 0) itemUnits = "file".equals(type) ? 1 : countTreeUnits(sourceDirId, name);
				int before = progress.done();

				ItemResult result;
				if (is_move) {
					result = "file".equals(type)
							? moveFile(name, sourceDirId, targetDirId, force, progress)
							: moveDirectory(name, sourceDirId, targetDirId, force, itemUnits, progress);
				} else if ("file".equals(type)) {
					result = copyFile(name, sourceDirId, targetDirId, force);
					if (result.code == ItemResult.CODE_OK) progress.advance(1, name);
				} else {
					result = copyDirectory(name, sourceDirId, targetDirId, force, progress);
				}

				if (result.code != ItemResult.CODE_OK) {
					if (overlay != null) overlay.hideProgress();
					// itemDone 是这一项已经推进的单元数；JS 跳过它时补上剩下的部分，进度才能收在 100%。
					JSONObject response = new JSONObject()
							.put("remaining", sliceFrom(items, i))
							.put("done", progress.done())
							.put("total", total)
							.put("itemUnits", itemUnits)
							.put("itemDone", progress.done() - before)
							.put("message", result.message);
					if (result.code == ItemResult.CODE_NEED_CONFIRM) {
						response.put("needConfirm", true);
					}
					call_js_callback(task_id, false, response, result.message);
					return;
				}
			}

			if (overlay != null) overlay.finishProgress(action + "完成", "正在刷新列表…");
			call_js_callback(task_id, true, null, null);

		} catch (Exception e) {
			if (overlay != null) overlay.hideProgress();
			call_js_callback(task_id, false, null, e.getMessage());
		}
	}

    private ItemResult moveFile(String file_name, String source_dir_id, String target_dir_id, boolean force_overwrite, BatchProgress progress) throws JSONException {
        // 查找源文件
        JSONArray findSourceParams = new JSONArray().put(source_dir_id).put(file_name);
        JSONObject sourceFile = db_helper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?", findSourceParams);
        if (sourceFile == null) {
            return ItemResult.error("源文件不存在");
        }
        String fileId = sourceFile.getString("id");

        // 检查目标位置是否存在同名文件
        JSONArray findTargetParams = new JSONArray().put(target_dir_id).put(file_name);
        JSONObject targetFile = db_helper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?", findTargetParams);

        if (targetFile != null) {
            if (!force_overwrite) {
                return ItemResult.needConfirm("目标位置已存在同名文件，是否覆盖？");
            } else {
                // 强制覆盖，删除目标位置的旧文件
                String oldFileId = targetFile.getString("id");
                deleteFilesNow(Collections.singletonList(oldFileId));
            }
        }

        // 移动文件：只需更新其 parent_dir_id
        JSONArray updateParams = new JSONArray().put(target_dir_id).put(fileId);
        db_helper.db_run("UPDATE file SET parent_dir_id = ? WHERE id = ?", updateParams);
        if (progress != null) progress.advance(1, file_name);
        return ItemResult.OK;
    }

    private ItemResult moveDirectory(String dir_name, String source_dir_id, String target_dir_id, boolean force_overwrite, int units, BatchProgress progress) throws JSONException {
        // 查找源目录
        JSONArray findSourceParams = new JSONArray().put(source_dir_id).put(dir_name);
        JSONObject sourceDir = db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", findSourceParams);
        if (sourceDir == null) {
            return ItemResult.error("源目录不存在");
        }
        String dirId = sourceDir.getString("id");

        // 防止将父目录移动到子目录中
        if (isAncestor(dirId, target_dir_id)) {
            return ItemResult.error("不能将目录移动到其自身或子目录中");
        }

        // 检查目标位置是否存在同名目录
        JSONArray findTargetParams = new JSONArray().put(target_dir_id).put(dir_name);
        JSONObject targetDir = db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", findTargetParams);

        if (targetDir == null) { // 目标位置无同名目录，直接移动
            db_helper.db_run("UPDATE dir SET parent_dir_id = ? WHERE id = ?", new JSONArray().put(target_dir_id).put(dirId));
            if (progress != null) progress.advance(units, dir_name);
            return ItemResult.OK;
        }

        if (!force_overwrite) {
            return ItemResult.needConfirm("目标位置已存在同名目录，是否合并（同名文件将被覆盖）？");
        }

        // 合并：整棵树的文件逐个搬，进度按节点推进
        int start = progress != null ? progress.done() : 0;
        mergeDirectories(dirId, targetDir.getString("id"), dir_name, progress);
        db_helper.db_run("DELETE FROM dir WHERE id = ?", new JSONArray().put(dirId));
        // 整棵移动的子目录只推进了自身 1 个单元，末尾补齐剩余额度，保证 done 与 total 对齐
        if (progress != null) progress.advance(units - (progress.done() - start), dir_name);
        return ItemResult.OK;
    }

    /** 合并目录：source 的文件/子孙目录搬进 target（同名文件覆盖、同名子目录递归合并），逐项推进进度。 */
    private void mergeDirectories(String source_dir_id, String target_dir_id, String display_name, BatchProgress progress) throws JSONException {
        if (progress != null) progress.tick(display_name);

        // 1. 文件：同名覆盖，其余直接改父目录
        JSONArray sourceFiles = db_helper.db_all("SELECT id, name FROM file WHERE parent_dir_id = ?", new JSONArray().put(source_dir_id));
        for (int i = 0; i < sourceFiles.length(); i++) {
            JSONObject sourceFile = sourceFiles.getJSONObject(i);
            String fileName = sourceFile.getString("name");
            String fileId = sourceFile.getString("id");

            JSONObject targetFile = db_helper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?", new JSONArray().put(target_dir_id).put(fileName));
            if (targetFile != null) {
                deleteFilesNow(Collections.singletonList(targetFile.getString("id")));
            }
            db_helper.db_run("UPDATE file SET parent_dir_id = ? WHERE id = ?", new JSONArray().put(target_dir_id).put(fileId));
            if (progress != null) progress.tick(fileName);
        }

        // 2. 子目录：同名递归合并后删源目录，其余整棵移动
        JSONArray sourceSubDirs = db_helper.db_all("SELECT id, name FROM dir WHERE parent_dir_id = ?", new JSONArray().put(source_dir_id));
        for (int i = 0; i < sourceSubDirs.length(); i++) {
            JSONObject sourceSubDir = sourceSubDirs.getJSONObject(i);
            String subDirName = sourceSubDir.getString("name");
            String sourceSubDirId = sourceSubDir.getString("id");

            JSONObject targetSubDir = db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", new JSONArray().put(target_dir_id).put(subDirName));
            if (targetSubDir != null) {
                mergeDirectories(sourceSubDirId, targetSubDir.getString("id"), subDirName, progress);
                db_helper.db_run("DELETE FROM dir WHERE id = ?", new JSONArray().put(sourceSubDirId));
            } else {
                db_helper.db_run("UPDATE dir SET parent_dir_id = ? WHERE id = ?", new JSONArray().put(target_dir_id).put(sourceSubDirId));
                if (progress != null) progress.tick(subDirName);
            }
        }
    }

    private ItemResult copyFile(String file_name, String source_dir_id, String target_dir_id, boolean force_overwrite) throws Exception {
        // 查找源文件
        JSONObject source_file_db = db_helper.db_get("SELECT * FROM file WHERE parent_dir_id = ? AND name = ?", new JSONArray().put(source_dir_id).put(file_name));
        if (source_file_db == null) {
            return ItemResult.error("源文件不存在");
        }

        // 检查目标位置是否存在同名文件
        JSONObject target_file_exist = db_helper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?", new JSONArray().put(target_dir_id).put(file_name));
        if (target_file_exist != null && !force_overwrite) {
            return ItemResult.needConfirm("目标位置已存在同名文件，是否覆盖？");
        }

        // 先把"要写一个新文件"注册成待执行任务，再动磁盘；中途被杀由启动修复兜底
        String new_file_id = IdGenerator.get_id_by_time();
        String source_real_name = source_file_db.getString("real_file_name");
        String extension = source_file_db.optString("extension", "");
        String new_real_name = new_file_id + (extension.isEmpty() ? "" : "." + extension);
        String source_real_icon_name = readColumn(source_file_db, "real_icon_name");
        String new_real_icon_name = (source_real_icon_name == null || source_real_icon_name.isEmpty())
                ? null : new_file_id + "_icon.png";
        long expected_size = source_file_db.optLong("size", 0);
        if (expected_size <= 0) {
            File dataDir = createDataDirectory();
            if (dataDir != null) expected_size = new File(dataDir, source_real_name).length();
        }
        String tmp_path = new File(new File(context.getFilesDir(), "copy_tmp"), new_real_name).getAbsolutePath();
        String old_target_id = target_file_exist == null ? null : target_file_exist.getString("id");

        db_helper.db_run("BEGIN TRANSACTION", new JSONArray());
        try {
            FileImporter.recordPendingCopy(db_helper, new_file_id, file_name, target_dir_id,
                    new_real_name, new_real_icon_name, expected_size, extension,
                    source_file_db.getLong("seqnumber"), tmp_path, old_target_id);
            db_helper.db_run("COMMIT", new JSONArray());
        } catch (Exception e) {
            db_helper.db_run("ROLLBACK", new JSONArray());
            throw e;
        }

        // 复制物理文件
        if (!copyPhysicalFile(source_real_name, new_real_name, false)) {
            clearPendingQuietly(new_file_id);
            return ItemResult.error("物理文件复制失败");
        }

        // 复制图标（失败就把任务里的图标名清空，不能让行指向不存在的图标文件）
        if (new_real_icon_name != null && !copyPhysicalFile(source_real_icon_name, new_real_icon_name, true)) {
            new_real_icon_name = null;
            db_helper.db_run("UPDATE pending_file SET real_icon_name = NULL WHERE id = ?",
                    new JSONArray().put(new_file_id));
        }

        // 新文件完整落盘后再删旧目标：复制失败（如磁盘满）时旧文件还在
        if (target_file_exist != null) {
            deleteFilesNow(Collections.singletonList(target_file_exist.getString("id")));
        }

        // 事务提交：pending → file，同时销掉任务；要么"行在、任务清"，要么回滚后由启动修复
        FileImporter.promote(db_helper, new_file_id);

        return ItemResult.OK;
    }

    /** 失败路径清理任务：清不掉也没关系，启动修复会兜底。 */
    private void clearPendingQuietly(String task_id) {
        try {
            db_helper.db_run("BEGIN TRANSACTION", new JSONArray());
            try {
                FileImporter.clearPending(db_helper, Collections.singletonList(task_id));
                db_helper.db_run("COMMIT", new JSONArray());
            } catch (Exception e) {
                db_helper.db_run("ROLLBACK", new JSONArray());
                throw e;
            }
        } catch (Exception e) {
            android.util.Log.e("FileTask", "清理复制任务失败: " + task_id, e);
        }
    }

    private ItemResult copyDirectory(String dir_name, String source_dir_id, String target_dir_id, boolean force_overwrite, BatchProgress progress) throws Exception {
        // 查找源目录
        JSONObject source_dir = db_helper.db_get("SELECT * FROM dir WHERE parent_dir_id = ? AND name = ?", new JSONArray().put(source_dir_id).put(dir_name));
        if (source_dir == null) {
            return ItemResult.error("源目录不存在");
        }
        String source_root_id = source_dir.getString("id");

        // 检查目标位置是否存在同名目录
        JSONObject target_dir_exist = db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", new JSONArray().put(target_dir_id).put(dir_name));
        if (target_dir_exist != null && !force_overwrite) {
            return ItemResult.needConfirm("目标位置已存在同名目录，是否合并（同名文件将被覆盖）？");
        }

        // 防止将父目录复制到子目录中
        if (isAncestor(source_root_id, target_dir_id)) {
            return ItemResult.error("不能将目录复制到其自身或子目录中");
        }

        // 使用队列进行广度优先复制
        Queue<Pair<String, String>> copy_queue = new LinkedList<>();

        // 如果目标位置存在，则使用其ID作为复制根，否则创建新目录
        String target_root_id;
        if(target_dir_exist != null) { // 合并模式
            target_root_id = target_dir_exist.getString("id");
        } else { // 新建模式
            target_root_id = IdGenerator.get_id_by_time();
            JSONArray create_params = new JSONArray()
                .put(target_root_id)
                .put(dir_name)
                .put(target_dir_id)
                .put("dir")
                .put(System.currentTimeMillis())
                .put(source_dir.getInt("filesortmode"))
                .put(source_dir.getInt("dirsortmode"))
                .put(source_dir.getLong("seqnumber"));
            db_helper.db_run("INSERT INTO dir (id, name, parent_dir_id, type, createtime, filesortmode, dirsortmode, seqnumber) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", create_params);
        }

        copy_queue.offer(new Pair<>(source_root_id, target_root_id));
        if (progress != null) progress.tick(dir_name); // 根目录本身算一个单元

        int failed = 0;
        String firstFailed = null;
        while (!copy_queue.isEmpty()) {
            Pair<String, String> current = copy_queue.poll();
            String current_source_id = current.first;
            String current_target_parent_id = current.second;

            // 复制当前目录下的所有文件
            JSONArray files_to_copy = db_helper.db_all("SELECT name FROM file WHERE parent_dir_id = ?", new JSONArray().put(current_source_id));
            for(int i=0; i < files_to_copy.length(); i++) {
                String file_name_to_copy = files_to_copy.getJSONObject(i).getString("name");
                // 单个文件失败不能让整棵目录"看起来复制成功"：记下来，结束时整体报错
                if (copyFile(file_name_to_copy, current_source_id, current_target_parent_id, force_overwrite).code != ItemResult.CODE_OK) {
                    failed++;
                    if (firstFailed == null) firstFailed = file_name_to_copy;
                }
                if (progress != null) progress.tick(file_name_to_copy);
            }

            // 复制当前目录下的所有子目录
            JSONArray dirs_to_copy = db_helper.db_all("SELECT * FROM dir WHERE parent_dir_id = ?", new JSONArray().put(current_source_id));
            for(int i=0; i < dirs_to_copy.length(); i++) {
                JSONObject sub_dir_to_copy = dirs_to_copy.getJSONObject(i);
                String sub_dir_name = sub_dir_to_copy.getString("name");
                String old_sub_dir_id = sub_dir_to_copy.getString("id");

                // 检查子目录在目标位置是否存在
                JSONObject sub_dir_exist_in_target = db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", new JSONArray().put(current_target_parent_id).put(sub_dir_name));
                String new_sub_dir_id;
                if(sub_dir_exist_in_target != null) { // 已存在，使用现有ID
                    new_sub_dir_id = sub_dir_exist_in_target.getString("id");
                } else { // 不存在，创建新目录
                    new_sub_dir_id = IdGenerator.get_id_by_time();
                    JSONArray create_sub_params = new JSONArray()
                        .put(new_sub_dir_id)
                        .put(sub_dir_name)
                        .put(current_target_parent_id)
                        .put("dir")
                        .put(System.currentTimeMillis())
                        .put(sub_dir_to_copy.getInt("filesortmode"))
                        .put(sub_dir_to_copy.getInt("dirsortmode"))
                        .put(sub_dir_to_copy.getLong("seqnumber"));
                    db_helper.db_run("INSERT INTO dir (id, name, parent_dir_id, type, createtime, filesortmode, dirsortmode, seqnumber) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", create_sub_params);
                }
                copy_queue.offer(new Pair<>(old_sub_dir_id, new_sub_dir_id));
                if (progress != null) progress.tick(sub_dir_name);
            }
        }
        if (failed > 0) {
            return ItemResult.error(failed + " 个文件复制失败（如 " + firstFailed + "）");
        }
        return ItemResult.OK;
    }

	/**
	 * 物理文件复制：64KB 缓冲流式写，内存占用与文件大小无关（复制几 GB 视频也不会 OOM）。
	 * 先写 .part 临时文件、fsync 后再 rename，避免目标位置留下半截文件。
	 */
	private boolean copyPhysicalFile(String source_name, String target_name, boolean is_icon) {
		File source_dir = is_icon ? createIconDirectory() : createDataDirectory();
		File target_dir = is_icon ? createIconDirectory() : createDataDirectory();
		if (source_dir == null || target_dir == null) return false;

		File source_file = new File(source_dir, source_name);
		if (!source_file.isFile()) return false;

		File target_file = new File(target_dir, target_name);
		File temp_dir = createCopyTempDirectory();
		if (temp_dir == null) return false;
		File temp_file = new File(temp_dir, target_name);
		try (InputStream in = new FileInputStream(source_file);
			 FileOutputStream out = new FileOutputStream(temp_file)) {
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
			}
			out.flush();
			out.getFD().sync();
		} catch (IOException e) {
			android.util.Log.e("CopyPhysicalFile", "复制失败: " + source_name, e);
			//noinspection ResultOfMethodCallIgnored
			temp_file.delete();
			return false;
		}

		if (temp_file.renameTo(target_file)) return true;
		//noinspection ResultOfMethodCallIgnored
		temp_file.delete();
		android.util.Log.e("CopyPhysicalFile", "重命名失败: " + target_name);
		return false;
	}

	// [重构] 辅助函数 findDirIdByPath，用于替换 getIdByArr
	private String findDirIdByPath(JSONArray path_array) throws JSONException {
        JSONObject dir = getDirByPath(path_array);
        return dir != null ? dir.getString("id") : null;
    }

	private JSONObject getDataById(String id, String table) throws JSONException {
		JSONArray params = new JSONArray().put(id);
		return db_helper.db_get("SELECT * FROM " + table + " WHERE id = ?", params);
	}
	private boolean isAncestor(String potentialAncestorId, String descendantId) throws JSONException {
		String currentId = descendantId;
		if (potentialAncestorId.equals(currentId)) return true;

		while (currentId != null && !currentId.equals("root")) {
			JSONObject currentDir = db_helper.db_get("SELECT parent_dir_id FROM dir WHERE id = ?", new JSONArray().put(currentId));
			if (currentDir == null) break;
			currentId = currentDir.optString("parent_dir_id", null);
			if (potentialAncestorId.equals(currentId)) {
				return true;
			}
		}
		return false;
	}

    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        });
    }

	@JavascriptInterface
	public void get_data_by_id(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject data = new JSONObject(json_data);
				String id = data.getString("id");
				String table = data.getString("table");

				if (!table.equals("dir") && !table.equals("file")) {
					call_js_callback(task_id, false, null, "无效的表名");
					return;
				}
				JSONObject result = getDataById(id, table);
				call_js_callback(task_id, true, result, null);

			} catch (Exception e) {
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}

	@JavascriptInterface
	public void get_data_by_arr(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject data = new JSONObject(json_data);
				JSONArray pathArray = data.getJSONArray("pathArray");
				String table = data.getString("table");
				if (!table.equals("dir") && !table.equals("file")) {
					call_js_callback(task_id, false, null, "无效的表名");
					return;
				}

                if (table.equals("dir")) {
                    JSONObject result = getDirByPath(pathArray);
                    call_js_callback(task_id, true, result, null);
                } else {
                    String fileName = pathArray.getString(pathArray.length() - 1);
                    JSONArray dirPath = new JSONArray();
                    for(int i=0; i < pathArray.length() - 1; i++) {
                        dirPath.put(pathArray.get(i));
                    }
                    String dirId = findDirIdByPath(dirPath);
                    if(dirId == null) {
                        call_js_callback(task_id, false, null, "路径不存在");
                        return;
                    }

                    JSONObject file_info = db_helper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?", new JSONArray().put(dirId).put(fileName));
                    if(file_info == null) {
                        call_js_callback(task_id, false, null, "文件不存在");
                        return;
                    }
                    JSONObject result = getDataById(file_info.getString("id"), table);
                    call_js_callback(task_id, true, result, null);
                }
			} catch (Exception e) {
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}

    private String save_icon_to_file(String file_id, byte[] icon_data) {
        try {
            File icon_dir = new File(context.getFilesDir(), "icons");
            File icon_file = new File(icon_dir, file_id + "_icon.png");

            try (FileOutputStream fos = new FileOutputStream(icon_file)) {
                fos.write(icon_data);
                fos.flush();
            }
            return "file://" + icon_file.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
	@JavascriptInterface
	public void create_dir_to_path(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject data = new JSONObject(json_data);
				JSONArray parentarr = data.getJSONArray("parentarr");
				String now_id = data.getString("now_id");

				String current_parent_id = now_id;

				for (int i = 0; i < parentarr.length(); i++) {
					String dir_name = parentarr.getString(i);
					if (dir_name == null || dir_name.trim().isEmpty()) {
						continue;
					}

                    JSONArray findParams = new JSONArray().put(current_parent_id).put(dir_name);
                    JSONObject existing_dir = db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", findParams);

					if (existing_dir != null) {
						current_parent_id = existing_dir.getString("id");
					} else {
						// 目录不存在，创建新目录
						long create_time = System.currentTimeMillis();
						String new_dir_id = IdGenerator.get_id_by_time();
						long seq_number = FileImporter.getSeqNumberFromString(dir_name);

						JSONArray create_dir_params = new JSONArray()
                            .put(new_dir_id)
                            .put(dir_name)
                            .put(current_parent_id)
                            .put("dir")
                            .put(create_time)
                            .put(2) // filesortmode
                            .put(2) // dirsortmode
                            .put(seq_number);

						db_helper.db_run(
							"INSERT INTO dir (id, name, parent_dir_id, type, createtime, filesortmode, dirsortmode, seqnumber) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
							create_dir_params
						);
						current_parent_id = new_dir_id;
					}
				}

				JSONObject result = new JSONObject().put("dir_id", current_parent_id);
				call_js_callback(task_id, true, result, null);

			} catch (Exception e) {
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}

	//---------------------------------导出方法 (已重构)---------------------------------------
	@JavascriptInterface
	public void export_items(String task_id, String json_data) {
		new Thread(() -> {
			MainActivity overlay = getOverlayHost();
			try {
				JSONObject data = new JSONObject(json_data);
				JSONArray ids = data.getJSONArray("ids");

				String targetDirPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/filecamera/";
				File exportDir = new File(targetDirPath);

				if (overlay != null) overlay.showProgress("正在导出…", "正在准备导出目录");
				if (!ensureDirectoryExists(exportDir)) {
					throw new IOException("无法创建导出目录: " + targetDirPath);
				}
				checkAndCreateDirectoryStructure(exportDir);

				// 导出文件夹实际是一条 zip：按"选中项"计数时只选一个文件夹就是 1/1，
				// 之后压缩几百上千个文件的过程没有任何进度（旧实现还把明细发给了已废弃的 H5 遮罩）。
				// 先数一遍要写出的文件，再在压缩/拷贝每个文件后 tick，遮罩上才有真正的进度。
				int totalUnits = 0;
				if (overlay != null) overlay.showProgress("正在导出…", "正在统计要导出的文件");
				for (int i = 0; i < ids.length(); i++) {
					JSONObject dirData = getDataById(ids.getString(i), "dir");
					totalUnits += dirData != null ? countFilesInDirectory(dirData.getString("id")) : 1;
				}
				if (overlay != null) overlay.showProgress("正在导出…", "共 " + totalUnits + " 个文件");
				BatchProgress progress = new BatchProgress(overlay, "正在导出", totalUnits, 0);

                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.getString(i);

                    JSONObject dirData = getDataById(id, "dir");
                    if (dirData != null) {
                        String dirName = dirData.getString("name");
                        String zipFileName = dirName + ".zip";
                        String finalZipFileName = getFinalZipFileName(exportDir, zipFileName);

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            createZipWithMediaStore(dirData, finalZipFileName, dirName, progress);
                        } else {
                            createZipWithFile(dirData, targetDirPath, finalZipFileName, dirName, progress);
                        }
                    } else {
                        JSONObject fileData = getDataById(id, "file");
                        if (fileData != null) {
                            moveFileToTarget(fileData, exportDir);
                            progress.tick(fileData.isNull("name") ? null : fileData.getString("name"));
                        } else {
                            android.util.Log.w("Export", "ID为 " + id + " 的项目未找到，已跳过。");
                            // 统计时按 1 算过，这里也要补一次，否则收尾永远差 1 项
                            progress.tick(null);
                        }
                    }
                }
				if (overlay != null) {
					overlay.finishProgress("导出完成", "已保存到 Download/filecamera/");
					overlay.hideProgressAfter(1500L);
				}
				call_js_callback(task_id, true, "已导出到 " + targetDirPath, null);

			} catch (Exception e) {
				android.util.Log.e("ExportError", "导出项目时发生错误", e);
				if (overlay != null) overlay.hideProgress();
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}

	private boolean ensureDirectoryExists(File directory) {
		if (directory == null) return false;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ContentResolver resolver = context.getContentResolver();
			String downloadsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
			String targetPath = directory.getAbsolutePath();
			String relativePath = "";

			if (targetPath.startsWith(downloadsPath)) {
				String subPath = targetPath.substring(downloadsPath.length());
				if (subPath.startsWith(File.separator)) subPath = subPath.substring(1);
				relativePath = Environment.DIRECTORY_DOWNLOADS + File.separator + subPath + File.separator;
			} else {
				return false;
			}

			ContentValues values = new ContentValues();
			values.put(MediaStore.Downloads.DISPLAY_NAME, ".placeholder_" + System.currentTimeMillis());
			values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
			values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);

			Uri placeholderUri = null;
			try {
				placeholderUri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
				if (placeholderUri != null) {
					resolver.delete(placeholderUri, null, null);
					return true;
				}
				return false;
			} catch (Exception e) {
				return directory.exists() && directory.isDirectory();
			}
		} else {
			return directory.exists() ? directory.isDirectory() : directory.mkdirs();
		}
	}


	private void checkAndCreateDirectoryStructure(File exportDir) {
		try {
			if (!ensureDirectoryExists(exportDir)) {
				android.util.Log.e("Export", "无法确保导出目录存在: " + exportDir.getAbsolutePath());
			}
		} catch (Exception e) {
			android.util.Log.e("Export", "检查目录结构失败: " + e.getMessage(), e);
		}
	}

	private String getFinalZipFileName(File exportDir, String originalFileName) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ContentResolver resolver = context.getContentResolver();
			Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
			String relativePath = Environment.DIRECTORY_DOWNLOADS + File.separator + "filecamera" + File.separator;
			String selection = MediaStore.Downloads.RELATIVE_PATH + " = ? AND " + MediaStore.Downloads.DISPLAY_NAME + " = ?";

			try (Cursor cursor = resolver.query(collection, new String[]{MediaStore.Downloads._ID}, selection, new String[]{relativePath, originalFileName}, null)) {
				if (cursor == null || cursor.getCount() == 0) return originalFileName;
			}

			String nameWithoutExt;
			String ext = "";
			int dotIndex = originalFileName.lastIndexOf('.');

			if (dotIndex > 0 && dotIndex < originalFileName.length() - 1) {
				nameWithoutExt = originalFileName.substring(0, dotIndex);
				ext = originalFileName.substring(dotIndex);
			} else {
				nameWithoutExt = originalFileName;
			}

			int count = 1;
			String finalFileName;
			do {
				finalFileName = nameWithoutExt + " (" + count + ")" + ext;
				try (Cursor cursor = resolver.query(collection, new String[]{MediaStore.Downloads._ID}, selection, new String[]{relativePath, finalFileName}, null)) {
					if (cursor == null || cursor.getCount() == 0) return finalFileName;
				}
				count++;
			} while (true);

		} else {
			File file = new File(exportDir, originalFileName);
			if (!file.exists()) return originalFileName;

            String nameWithoutExt;
			String ext = "";
			int dotIndex = originalFileName.lastIndexOf('.');
			if (dotIndex > 0 && dotIndex < originalFileName.length() - 1) {
				nameWithoutExt = originalFileName.substring(0, dotIndex);
				ext = originalFileName.substring(dotIndex);
			} else {
				nameWithoutExt = originalFileName;
			}

			int count = 1;
			String finalFileName;
			do {
				finalFileName = nameWithoutExt + " (" + count + ")" + ext;
				file = new File(exportDir, finalFileName);
				count++;
			} while (file.exists());
			return finalFileName;
		}
	}
	private void moveFileToTarget(JSONObject fileData, File exportDir) throws Exception {
		String originalFilename = fileData.getString("name");
		String storedFilename = fileData.getString("real_file_name");

		File sourceFile = new File(createDataDirectory(), storedFilename);
		if (!sourceFile.exists()) return;

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ContentResolver resolver = context.getContentResolver();
			String relativePath = Environment.DIRECTORY_DOWNLOADS + File.separator + "filecamera" + File.separator;

			File destFilePlaceholder = handleNameConflict(exportDir, originalFilename, "");
			String finalFileName = destFilePlaceholder.getName();

			ContentValues values = new ContentValues();
			values.put(MediaStore.Downloads.DISPLAY_NAME, finalFileName);
			String extension = MimeTypeMap.getFileExtensionFromUrl(finalFileName.toLowerCase());
			String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
			values.put(MediaStore.Downloads.MIME_TYPE, mimeType != null ? mimeType : "application/octet-stream");
			values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
			values.put(MediaStore.Downloads.IS_PENDING, 1);

			Uri destUri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
			if (destUri == null) throw new IOException("无法为文件创建 MediaStore 条目: " + finalFileName);

			try (InputStream in = new FileInputStream(sourceFile); OutputStream out = resolver.openOutputStream(destUri)) {
				if (out == null) throw new IOException("无法打开 MediaStore URI 的输出流。");
				byte[] buffer = new byte[8192];
				int bytesRead;
				while ((bytesRead = in.read(buffer)) != -1) {
					out.write(buffer, 0, bytesRead);
				}
			}

			values.clear();
			values.put(MediaStore.Downloads.IS_PENDING, 0);
			resolver.update(destUri, values, null, null);
		} else {
			if (!ensureDirectoryExists(exportDir)) throw new IOException("无法创建导出目录: " + exportDir.getAbsolutePath());

            File destFile = handleNameConflict(exportDir, originalFilename, "");

			try (FileInputStream fis = new FileInputStream(sourceFile); FileOutputStream fos = new FileOutputStream(destFile)) {
				byte[] buffer = new byte[8192];
				int bytesRead;
				while ((bytesRead = fis.read(buffer)) != -1) {
					fos.write(buffer, 0, bytesRead);
				}
			}
			MediaScannerConnection.scanFile(context, new String[]{destFile.getAbsolutePath()}, null, null);
		}
	}

	private void createZipWithMediaStore(JSONObject dirData, String zipFileName, String dirName, BatchProgress progress) throws Exception {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ContentValues values = new ContentValues();
			values.put(MediaStore.Downloads.DISPLAY_NAME, zipFileName);
			values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
			String relativePath = Environment.DIRECTORY_DOWNLOADS + File.separator + "filecamera" + File.separator;
			values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
			values.put(MediaStore.Downloads.IS_PENDING, 1);

			ContentResolver resolver = context.getContentResolver();
			Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
			Uri zipUri = resolver.insert(collection, values);

			if (zipUri != null) {
				try (OutputStream outputStream = resolver.openOutputStream(zipUri);
					 ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(outputStream))) {
					writeDirectoryToZip(zos, dirData, SecurityUtils.safePathComponent(dirData.getString("name")) + "/", progress);
				}
				try{
					values.clear();
					values.put(MediaStore.Downloads.IS_PENDING, 0);
					resolver.update(zipUri, values, null, null);
				}catch (Exception e) {
					android.util.Log.w("ZIP_CREATE", "手动更新IS_PENDING失败: " + e.getMessage());
				}
			} else {
				throw new IOException("无法通过MediaStore创建ZIP文件: " + zipFileName);
			}
		}
	}
	private void createZipWithFile(JSONObject dirData, String targetDirPath, String zipFileName, String dirName, BatchProgress progress) throws Exception {
		File targetDir = new File(targetDirPath);
		if (!ensureDirectoryExists(targetDir)) throw new IOException("无法创建目标目录: " + targetDirPath);

		File zipFile = new File(targetDir, zipFileName);

		try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
			writeDirectoryToZip(zos, dirData, SecurityUtils.safePathComponent(dirData.getString("name")) + "/", progress);
		}

		MediaScannerConnection.scanFile(context, new String[]{zipFile.getAbsolutePath()}, null, null);
	}

	// [重构] 将目录写入ZIP流，使用SQL查询
	private void writeDirectoryToZip(ZipOutputStream zos, JSONObject dirData, String pathInZip, BatchProgress progress) throws Exception {
		String rootDirId = dirData.getString("id");
		File dataDir = createDataDirectory();

		Queue<Pair<String, String>> queue = new LinkedList<>();
		queue.add(new Pair<>(rootDirId, pathInZip));

		while (!queue.isEmpty()) {
			Pair<String, String> current = queue.poll();
			String currentId = current.first;
			String currentPathInZip = current.second;

			zos.putNextEntry(new ZipEntry(currentPathInZip));
			zos.closeEntry();

			// 处理文件
            JSONArray files = db_helper.db_all("SELECT * FROM file WHERE parent_dir_id = ?", new JSONArray().put(currentId));
            for (int i = 0; i < files.length(); i++) {
                JSONObject fileInfo = files.getJSONObject(i);
                String fileName = SecurityUtils.safePathComponent(fileInfo.getString("name"));
                String storedFilename = fileInfo.getString("real_file_name");
                File sourceFile = dataDir == null ? null : new File(dataDir, storedFilename);

                if (sourceFile != null && sourceFile.isFile()) {
                    zos.putNextEntry(new ZipEntry(currentPathInZip + fileName));
                    // 流式写：导出文件夹里的视频时，内存占用与单个文件大小无关
                    try (InputStream in = new FileInputStream(sourceFile)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            zos.write(buffer, 0, read);
                        }
                    }
                    zos.closeEntry();
                }
                // 即使物理文件缺失也补一次，保证 done 与统计时的 total 一致
                if (progress != null) progress.tick(fileName);
            }

			// 处理子目录
            JSONArray subDirs = db_helper.db_all("SELECT id, name FROM dir WHERE parent_dir_id = ?", new JSONArray().put(currentId));
            for(int i = 0; i < subDirs.length(); i++) {
                JSONObject subDirInfo = subDirs.getJSONObject(i);
                String subDirId = subDirInfo.getString("id");
                String subDirName = SecurityUtils.safePathComponent(subDirInfo.getString("name"));
                queue.add(new Pair<>(subDirId, currentPathInZip + subDirName + "/"));
            }
		}
	}
	private File handleNameConflict(File directory, String originalName, String extension) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			String finalName = getFinalZipFileName(directory, originalName + extension);
			return new File(directory, finalName);
		} else {
			File finalFile = new File(directory, originalName + extension);
			if (!finalFile.exists()) return finalFile;

			int count = 1;
			String nameWithoutExt = originalName;
			String ext = extension;

			if (extension.isEmpty()) {
				int dotIndex = originalName.lastIndexOf('.');
				if (dotIndex > 0 && dotIndex < originalName.length() - 1) {
					nameWithoutExt = originalName.substring(0, dotIndex);
					ext = originalName.substring(dotIndex);
				}
			}

			do {
				finalFile = new File(directory, nameWithoutExt + " (" + count + ")" + ext);
				count++;
			} while (finalFile.exists());

			return finalFile;
		}
	}

    // [重构] 使用SQL查询统计文件总数
	private int countFilesInDirectory(String rootDirId) throws Exception {
        CollectResult result = collectDirectoryIds(rootDirId);
        return result.fileIds.size();
	}

	@JavascriptInterface
	public void update_sort_mode(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject data = new JSONObject(json_data);
				String dir_id = data.getString("dir_id");
				int dir_sort_mode = data.getInt("dir_sort_mode");
				int file_sort_mode = data.getInt("file_sort_mode");
				boolean apply_to_all = data.getBoolean("apply_to_all");
				boolean update_dir_sort = data.optBoolean("update_dir_sort", true);
				boolean update_file_sort = data.optBoolean("update_file_sort", true);

				if (!update_dir_sort && !update_file_sort) {
					call_js_callback(task_id, true, null, null);
					return;
				}

				if (apply_to_all) {
					updateSortModeRecursive(dir_id, dir_sort_mode, file_sort_mode, update_dir_sort, update_file_sort);
				} else {
					updateSingleDirectory(dir_id, dir_sort_mode, file_sort_mode, update_dir_sort, update_file_sort);
				}

				call_js_callback(task_id, true, null, null);

			} catch (Exception e) {
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}

	// [重构] 递归更新所有子目录的排序模式，使用SQL查询
	private void updateSortModeRecursive(String root_dir_id, int dir_sort_mode, int file_sort_mode,
									   boolean update_dir_sort, boolean update_file_sort) throws JSONException {

		Queue<String> dir_id_queue = new LinkedList<>();
		dir_id_queue.offer(root_dir_id);

		while (!dir_id_queue.isEmpty()) {
			String current_dir_id = dir_id_queue.poll();

			// 更新当前目录
			updateSingleDirectory(current_dir_id, dir_sort_mode, file_sort_mode, update_dir_sort, update_file_sort);

			// 将所有子目录加入队列
            JSONArray subDirs = db_helper.db_all("SELECT id FROM dir WHERE parent_dir_id = ?", new JSONArray().put(current_dir_id));
            for(int i = 0; i < subDirs.length(); i++) {
                dir_id_queue.offer(subDirs.getJSONObject(i).getString("id"));
            }
		}
	}

	private void updateSingleDirectory(String dir_id, int dir_sort_mode, int file_sort_mode,
									 boolean update_dir_sort, boolean update_file_sort) throws JSONException {

		boolean need_update = false;
		List<Object> paramsList = new ArrayList<>();
		StringBuilder sql = new StringBuilder("UPDATE dir SET ");

		if (update_dir_sort) {
			sql.append("dirsortmode = ?");
            paramsList.add(dir_sort_mode);
			need_update = true;
		}

		if (update_file_sort) {
			if (need_update) sql.append(", ");
			sql.append("filesortmode = ?");
            paramsList.add(file_sort_mode);
			need_update = true;
		}

		if (need_update) {
			sql.append(" WHERE id = ?");
            paramsList.add(dir_id);

            JSONArray params = new JSONArray(paramsList);
			db_helper.db_run(sql.toString(), params);
		}
	}
	
	@JavascriptInterface
	public void saveIconToCache(String task_id, String json_data) {
		new Thread(() -> {
			File privateDir = null;
			try {
				privateDir = context.getFilesDir();
				if (privateDir == null) {
					throw new IOException("context.getFilesDir() 返回 null");
				}

				File targetSubDir = new File(privateDir, "watermark/icon");
				if (!targetSubDir.exists()) {
					if (!targetSubDir.mkdirs()) {
						throw new IOException("无法创建目标目录: " + targetSubDir.getAbsolutePath());
					}
				}

				JSONObject params = new JSONObject(json_data);
				String sourceUriString = params.optString("sourceUri", null);
				boolean isTemporary = params.optBoolean("temporary", false); // 是否临时保存

				if (sourceUriString == null || sourceUriString.isEmpty()) {
					call_js_callback(task_id, false, null, "sourceUri 不能为空");
					return;
				}

				Uri sourceUri = Uri.parse(sourceUriString);
				Bitmap bitmap = null;

				// 根据 temporary 标志决定文件名
				String fileName = isTemporary ? "temp_icon.png" : "cache_icon.png";
				File targetFile = new File(targetSubDir, fileName);

				try (InputStream inputStream = context.getContentResolver().openInputStream(sourceUri);
					 FileOutputStream outputStream = new FileOutputStream(targetFile)) {

					if (inputStream == null) {
						throw new IOException("无法打开源URI的输入流: " + sourceUriString);
					}

					bitmap = BitmapFactory.decodeStream(inputStream);
					if (bitmap == null) {
						throw new IOException("无法将输入流解码为图片");
					}

					bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
					outputStream.flush();

				} finally {
					if (bitmap != null && !bitmap.isRecycled()) {
						bitmap.recycle();
					}
				}

				// 返回结果
				Uri targetUri = Uri.fromFile(new File(targetSubDir, "cache_icon.png")); // 最终目标路径
				Uri tempUri = isTemporary ? Uri.fromFile(targetFile) : null;
				
				JSONObject resultData = new JSONObject();
				resultData.put("uri", targetUri.toString());
				if (isTemporary) {
					resultData.put("tempUri", tempUri.toString());
				}
				call_js_callback(task_id, true, resultData, null);

			} catch (Exception e) {
				android.util.Log.e("SaveIconError", "保存图标失败: " + e.toString(), e);
				call_js_callback(task_id, false, null, "保存图标失败: " + e.getMessage());
			}
		}).start();
	}

	// 新增：提交图标变更（将临时文件移动到正式位置）
	@JavascriptInterface
	public void commitIconChange(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject params = new JSONObject(json_data);
				String tempUriString = params.optString("tempUri", null);
				
				if (tempUriString != null) {
					File tempFile = new File(Uri.parse(tempUriString).getPath());
					File targetFile = new File(context.getFilesDir(), "watermark/icon/cache_icon.png");
					
					if (tempFile.exists()) {
						// 删除旧的正式文件（如果存在）
						if (targetFile.exists()) {
							targetFile.delete();
						}
						// 重命名临时文件为正式文件
						if (!tempFile.renameTo(targetFile)) {
							throw new IOException("无法移动临时文件到正式位置");
						}
					}
				}
				call_js_callback(task_id, true, null, null);
				
			} catch (Exception e) {
				android.util.Log.e("CommitIconError", "提交图标失败: " + e.toString(), e);
				call_js_callback(task_id, false, null, "提交图标失败: " + e.getMessage());
			}
		}).start();
	}

	// 新增：清理临时图标文件
	@JavascriptInterface
	public void cleanupTempIcon(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject params = new JSONObject(json_data);
				String tempUriString = params.optString("tempUri", null);
				
				if (tempUriString != null) {
					File tempFile = new File(Uri.parse(tempUriString).getPath());
					if (tempFile.exists()) {
						tempFile.delete();
					}
				}
				
				call_js_callback(task_id, true, null, null);
				
			} catch (Exception e) {
				android.util.Log.e("CleanupIconError", "清理临时文件失败: " + e.toString(), e);
				call_js_callback(task_id, false, null, "清理失败: " + e.getMessage());
			}
		}).start();
	}



	@JavascriptInterface
	public void fetchAdcodeByGps(String task_id, String json_data) {
		new Thread(() -> {
			try {
				if (gpsTime == 0 || (System.currentTimeMillis() - gpsTime) > GPS_TIMEOUT_MS) {
					call_js_callback(task_id, false, null, "GPS未就绪，请稍后重试");
					return;
				}
				String result = GaodeApi.resolveAdcodeFromGps(context, gpsLng, gpsLat);
				if (result == null || result.equals(GaodeApi.ERROR_FETCH_FAILED)
					|| result.equals(GaodeApi.ERROR_NO_KEY)
					|| result.equals(GaodeApi.ERROR_INVALID_KEY)) {
					call_js_callback(task_id, false, null, result != null ? result : "获取失败");
				} else {
					JSONObject resultData = new JSONObject();
					resultData.put("adcode", result);
					call_js_callback(task_id, true, resultData, null);
				}
			} catch (Exception e) {
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}
}
