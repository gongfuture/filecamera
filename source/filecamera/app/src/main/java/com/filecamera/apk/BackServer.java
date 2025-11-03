package com.filecamera.apk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.util.Stack;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import android.util.Pair;
import java.util.Queue;
import java.util.LinkedList;
import java.util.Iterator;
import android.media.MediaScannerConnection;
import android.webkit.MimeTypeMap;
import android.app.Activity;
import java.util.List;

public class BackServer {
    private WebView webView;
    private Context context;
    private DatabaseHelper db_helper;

    public BackServer(WebView webView, Context context) {
        this.webView = webView;
        this.context = context;
        this.db_helper = new DatabaseHelper(context);

        // 初始化时创建必要的目录
        initDirectories();
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
    @JavascriptInterface
	public void upload_file(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject data = new JSONObject(json_data);
				String content_uri = data.getString("contentUri");
				String dir_id = data.getString("dirId");
				String file_name = data.getString("fileName");

				String file_id = IdGenerator.getIdByTime();

				// 从content URI复制文件数据
				byte[] file_data = copy_file_from_uri(content_uri);

				// 检查目录是否存在
				JSONArray check_arr = new JSONArray().put(dir_id);
				JSONObject dir = db_helper.db_get("SELECT id FROM dir WHERE id = ?", check_arr);

				if (dir == null) {
					call_js_callback(task_id, false, null, "文件夹不存在");
					return;
				}

				// 解析文件名获取扩展名和序号
				String[] extension_arr = file_name.split("\\.");
				String extension = extension_arr.length > 1 ? extension_arr[extension_arr.length - 1] : "";

				String name_part = (extension_arr.length > 1) ? file_name.substring(0, file_name.lastIndexOf('.')) : file_name;
				long seq_number = getSeqNumberFromString(name_part);
				long create_time = System.currentTimeMillis();
                long file_size = file_data.length;

				// 创建存储目录
				File data_dir = createDataDirectory();
				File icon_dir = createIconDirectory();

				if (data_dir == null || icon_dir == null) {
					call_js_callback(task_id, false, null, "无法创建数据或图标存储目录");
					return;
				}

				// 生成文件名
				String real_file_name = file_id + (extension.isEmpty() ? "" : "." + extension);
				String real_icon_name = file_id + "_icon.png";

				// 保存原文件到data目录
				File target_file = new File(data_dir, real_file_name);
				if (!saveFile(file_data, target_file)) {
					call_js_callback(task_id, false, null, "文件保存失败");
					return;
				}
				// 如果是图片文件，生成并保存图标
				if (is_image_file(file_name)) {
					byte[] icon_data = icon_make(file_data, file_name);
					if (icon_data != null) {
						File icon_file = new File(icon_dir, real_icon_name);

						if (!saveFile(icon_data, icon_file)) {
							real_icon_name = null;
						}
					}
				}else{
					real_icon_name = null;
				}
				// 在数据库中保存文件信息
				JSONArray file_arr = new JSONArray();
				file_arr.put(file_id);
				file_arr.put(file_name);
				file_arr.put(dir_id);
				file_arr.put("file");
				file_arr.put(real_file_name); // real_file_name字段保存实际文件名
                file_arr.put(create_time);
                file_arr.put(seq_number);
                file_arr.put(file_size);
                file_arr.put(extension);
				file_arr.put(real_icon_name);
				db_helper.db_run(
					"INSERT INTO file (id, name, parent_dir_id, type, real_file_name, createtime, seqnumber, size, extension, real_icon_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
					file_arr
				);



				// 创建要返回给前端的文件信息对象
				JSONObject result_data = new JSONObject();
				result_data.put("type", "file");
				result_data.put("id", file_id);
				result_data.put("name", file_name);
				result_data.put("size", file_size);
				result_data.put("createtime", create_time);
				result_data.put("extension", extension);
				result_data.put("real_file_name", real_file_name);
				result_data.put("real_icon_name", real_icon_name);
				result_data.put("seqnumber", seq_number);
                result_data.put("parent_dir_id", dir_id);

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

				// 5. 查询该目录下的所有子目录 (带排序)
				String dirQuery = "SELECT id, name, type, createtime, seqnumber FROM dir WHERE parent_dir_id = ? ORDER BY " + dirOrderBy;
				JSONArray dir_index_arr = db_helper.db_all(dirQuery, new JSONArray().put(id));

				// --- 代码修改开始 ---
				// 5.1 检查请求中是否需要查询封面
				boolean shouldQueryCover = data.optBoolean("cover");

				// 如果请求中 cover 属性为 true, 则为每个子目录查找封面
				if (shouldQueryCover) {
					for (int i = 0; i < dir_index_arr.length(); i++) {
						JSONObject sub_dir = dir_index_arr.getJSONObject(i);
						String sub_dir_id = sub_dir.getString("id");

						// 查询条件: seqnumber=1 并且是图片格式
						String cover_query = "SELECT real_icon_name FROM file " +
											 "WHERE parent_dir_id = ? AND seqnumber = 1 " +
											 "AND extension IN ('jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg') " +
											 "LIMIT 1";

						JSONArray cover_params = new JSONArray().put(sub_dir_id);
						JSONObject cover_file = db_helper.db_get(cover_query, cover_params);

						// 如果找到了符合条件的封面文件，则将其 real_icon_name 添加到 sub_dir 对象中
						if (cover_file != null && cover_file.has("real_icon_name")) {
							sub_dir.put("cover", cover_file.getString("real_icon_name"));
						}
					}
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
				String dir_id = IdGenerator.getIdByTime();
				long seq_number = getSeqNumberFromString(name);

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
                batchDeleteFiles(file_id_to_delete);

                call_js_callback(task_id, true, null, null);

			} catch (Exception e) {
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}
	@JavascriptInterface
	public void delete_items(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject data = new JSONObject(json_data);
				JSONArray items = data.getJSONArray("items");
				String parentId = data.getString("parentId");

				// 检查父目录是否存在
				if (getDataById(parentId, "dir") == null) {
                    call_js_callback(task_id, false, null, "父文件夹不存在");
                    return;
                }

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
                }

                db_helper.db_run("BEGIN TRANSACTION", new JSONArray());
                try {
                    if (!all_file_ids.isEmpty()) {
                        batchDeleteFiles(all_file_ids);
                    }

                    if (!all_dir_ids.isEmpty()) {
                        batchDeleteDirectories(all_dir_ids);
                    }
                    db_helper.db_run("COMMIT", new JSONArray());
                    call_js_callback(task_id, true, null, null);
                } catch (Exception e) {
                    db_helper.db_run("ROLLBACK", new JSONArray());
                    throw e;
                }

			} catch (Exception e) {
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

	private void batchDeleteFiles(List<String> file_ids) {
		if (file_ids == null || file_ids.isEmpty()) {
			return;
		}
		for (String fileId : file_ids) {
			deleteRealFileAndIcon(fileId);
		}

		try {
			int batch_size = 100;
			for (int i = 0; i < file_ids.size(); i += batch_size) {
				int end_index = Math.min(i + batch_size, file_ids.size());
				List<String> batch = file_ids.subList(i, end_index);

				StringBuilder placeholders = new StringBuilder();
				JSONArray params = new JSONArray();
				for (int j = 0; j < batch.size(); j++) {
					if (j > 0) placeholders.append(",");
					placeholders.append("?");
					params.put(batch.get(j));
				}

				db_helper.db_run("DELETE FROM file WHERE id IN (" + placeholders + ")", params);
			}
		} catch (Exception e) {
			android.util.Log.e("DeleteDBError", "批量删除数据库记录时出错", e);
		}
	}
	private void deleteRealFileAndIcon(String file_id) {
		JSONArray params = new JSONArray().put(file_id);

		try {
			// 从file表中同时获取主文件名和图标文件名
			JSONObject file_record = db_helper.db_get("SELECT real_file_name, real_icon_name FROM file WHERE id = ?", params);
			if (file_record != null) {
				// 删除主文件
				if (file_record.has("real_file_name") && !file_record.isNull("real_file_name")) {
					String real_file_name = file_record.getString("real_file_name");
					File data_dir = createDataDirectory();
					if (data_dir != null) {
						File file_to_delete = new File(data_dir, real_file_name);
						if (file_to_delete.exists()) {
							if (!file_to_delete.delete()) {
								android.util.Log.w("DeleteWarning", "删除物理文件失败: " + file_to_delete.getAbsolutePath());
							}
						}
					}
				}
				// 删除图标文件
				if (file_record.has("real_icon_name") && !file_record.isNull("real_icon_name")) {
					String real_icon_name = file_record.getString("real_icon_name");
					File icon_dir = createIconDirectory();
					if (icon_dir != null) {
						File icon_to_delete = new File(icon_dir, real_icon_name);
						if (icon_to_delete.exists()) {
							if (!icon_to_delete.delete()) {
								android.util.Log.w("DeleteWarning", "删除物理图标失败: " + icon_to_delete.getAbsolutePath());
							}
						}
					}
				}
			}
		} catch (Exception e) {
			android.util.Log.e("DeleteError", "删除物理文件或图标时发生异常, ID: " + file_id, e);
		}
	}
	private void batchDeleteDirectories(List<String> dir_ids) throws JSONException {
		if (dir_ids.isEmpty()) return;

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
					long new_seq_number = getSeqNumberFromString(newName);

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
					long seq_number = getSeqNumberFromString(name_part);
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


	@JavascriptInterface
	public void move_item(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject data = new JSONObject(json_data);
				JSONObject item = data.getJSONObject("item");
				JSONArray sourcePath = data.getJSONArray("sourcePath");
				JSONArray targetPath = data.getJSONArray("targetPath");
				boolean forceOverwrite = data.optBoolean("forceOverwrite", false);

				String itemName = item.getString("name");
				String itemType = item.getString("type");

				JSONObject source_dir = getDirByPath(sourcePath);
				JSONObject target_dir = getDirByPath(targetPath);

				if (source_dir == null || target_dir == null) {
					call_js_callback(task_id, false, null, "源路径或目标路径不存在");
					return;
				}

                String sourceDirId = source_dir.getString("id");
                String targetDirId = target_dir.getString("id");

                if(sourceDirId.equals(targetDirId)) {
                    call_js_callback(task_id, true, null, null); // 移动到相同位置，操作成功
                    return;
                }

                if (itemType.equals("file")) {
                    moveFile(task_id, itemName, sourceDirId, targetDirId, forceOverwrite);
                } else if (itemType.equals("dir")) {
                    moveDirectory(task_id, itemName, sourceDirId, targetDirId, forceOverwrite);
                } else {
                    call_js_callback(task_id, false, null, "未知的项目类型");
                }

			} catch (Exception e) {
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}

    private void moveFile(String task_id, String file_name, String source_dir_id, String target_dir_id, boolean force_overwrite) throws JSONException {
        // 查找源文件
        JSONArray findSourceParams = new JSONArray().put(source_dir_id).put(file_name);
        JSONObject sourceFile = db_helper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?", findSourceParams);
        if (sourceFile == null) {
            call_js_callback(task_id, false, null, "源文件不存在");
            return;
        }
        String fileId = sourceFile.getString("id");

        // 检查目标位置是否存在同名文件
        JSONArray findTargetParams = new JSONArray().put(target_dir_id).put(file_name);
        JSONObject targetFile = db_helper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?", findTargetParams);

        if (targetFile != null) {
            if (!force_overwrite) {
                // 需要前端确认覆盖
                JSONObject response = new JSONObject().put("needConfirm", true).put("message", "目标位置已存在同名文件，是否覆盖？");
                call_js_callback(task_id, false, response, null);
                return;
            } else {
                // 强制覆盖，删除目标位置的旧文件
                String oldFileId = targetFile.getString("id");
                batchDeleteFiles(new ArrayList<String>() {{ add(oldFileId); }});
            }
        }

        // 移动文件：只需更新其 parent_dir_id
        JSONArray updateParams = new JSONArray().put(target_dir_id).put(fileId);
        db_helper.db_run("UPDATE file SET parent_dir_id = ? WHERE id = ?", updateParams);
        call_js_callback(task_id, true, null, null);
    }

    private void moveDirectory(String task_id, String dir_name, String source_dir_id, String target_dir_id, boolean force_overwrite) throws JSONException {
        // 查找源目录
        JSONArray findSourceParams = new JSONArray().put(source_dir_id).put(dir_name);
        JSONObject sourceDir = db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", findSourceParams);
        if (sourceDir == null) {
            call_js_callback(task_id, false, null, "源目录不存在");
            return;
        }
        String dirId = sourceDir.getString("id");

        // 防止将父目录移动到子目录中
        if (isAncestor(dirId, target_dir_id)) {
            call_js_callback(task_id, false, null, "不能将目录移动到其自身或子目录中");
            return;
        }

        // 检查目标位置是否存在同名目录
        JSONArray findTargetParams = new JSONArray().put(target_dir_id).put(dir_name);
        JSONObject targetDir = db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", findTargetParams);

        if (targetDir != null) { // 存在同名目录，需要合并
            if(!force_overwrite){
                JSONObject response = new JSONObject().put("needConfirm", true).put("message", "目标位置已存在同名目录，是否合并？");
                call_js_callback(task_id, false, response, null);
                return;
            }
            String targetSubDirId = targetDir.getString("id");
            mergeDirectories(dirId, targetSubDirId, force_overwrite); // 递归合并
            // 合并后，源目录本身被删除
            db_helper.db_run("DELETE FROM dir WHERE id = ?", new JSONArray().put(dirId));

        } else { // 目标位置无同名目录，直接移动
            JSONArray updateParams = new JSONArray().put(target_dir_id).put(dirId);
            db_helper.db_run("UPDATE dir SET parent_dir_id = ? WHERE id = ?", updateParams);
        }
        call_js_callback(task_id, true, null, null);
    }

    private void mergeDirectories(String source_dir_id, String target_dir_id, boolean force_overwrite) throws JSONException {
        // 1. 移动源目录下的所有文件到目标目录
        JSONArray sourceFiles = db_helper.db_all("SELECT id, name FROM file WHERE parent_dir_id = ?", new JSONArray().put(source_dir_id));
        for (int i = 0; i < sourceFiles.length(); i++) {
            JSONObject sourceFile = sourceFiles.getJSONObject(i);
            String fileName = sourceFile.getString("name");
            String fileId = sourceFile.getString("id");

            JSONObject targetFile = db_helper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?", new JSONArray().put(target_dir_id).put(fileName));
            if(targetFile != null) { // 文件冲突
                if(force_overwrite) {
                    batchDeleteFiles(new ArrayList<String>() {{ add(targetFile.getString("id")); }});
                    // 删除后，移动源文件
                    db_helper.db_run("UPDATE file SET parent_dir_id = ? WHERE id = ?", new JSONArray().put(target_dir_id).put(fileId));
                }
                // 如果不覆盖，则忽略此文件
            } else {
                // 没有冲突，直接移动
                db_helper.db_run("UPDATE file SET parent_dir_id = ? WHERE id = ?", new JSONArray().put(target_dir_id).put(fileId));
            }
        }

        // 2. 递归处理源目录下的所有子目录
        JSONArray sourceSubDirs = db_helper.db_all("SELECT id, name FROM dir WHERE parent_dir_id = ?", new JSONArray().put(source_dir_id));
        for(int i = 0; i < sourceSubDirs.length(); i++) {
            JSONObject sourceSubDir = sourceSubDirs.getJSONObject(i);
            String subDirName = sourceSubDir.getString("name");
            String sourceSubDirId = sourceSubDir.getString("id");

            JSONObject targetSubDir = db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", new JSONArray().put(target_dir_id).put(subDirName));
            if(targetSubDir != null) { // 子目录也冲突，递归合并
                mergeDirectories(sourceSubDirId, targetSubDir.getString("id"), force_overwrite);
                // 合并后删除源子目录
                db_helper.db_run("DELETE FROM dir WHERE id = ?", new JSONArray().put(sourceSubDirId));
            } else { // 子目录不冲突，直接移动
                db_helper.db_run("UPDATE dir SET parent_dir_id = ? WHERE id = ?", new JSONArray().put(target_dir_id).put(sourceSubDirId));
            }
        }
    }

	@JavascriptInterface
	public void copy_item(String task_id, String json_data) {
		new Thread(() -> {
			try {
				JSONObject data = new JSONObject(json_data);
				String name = data.getString("name");
				String type = data.getString("type");
				JSONArray sourceArray = data.getJSONArray("sourceArray");
				JSONArray targetArray = data.getJSONArray("targetArray");
				boolean forceOverwrite = data.optBoolean("forceOverwrite", false);

				JSONObject source_dir = getDirByPath(sourceArray);
				JSONObject target_dir = getDirByPath(targetArray);

				if (source_dir == null || target_dir == null) {
					call_js_callback(task_id, false, null, "源目录或目标目录不存在");
					return;
				}

                String sourceDirId = source_dir.getString("id");
                String targetDirId = target_dir.getString("id");

				if (type.equals("file")) {
                    copyFile(task_id, name, sourceDirId, targetDirId, forceOverwrite);
				} else if (type.equals("dir")) {
                    copyDirectory(task_id, name, sourceDirId, targetDirId, forceOverwrite);
				} else {
					call_js_callback(task_id, false, null, "未知的项目类型");
				}
			} catch (Exception e) {
				android.util.Log.e("CopyItemError", "复制操作顶层异常", e);
				call_js_callback(task_id, false, null, e.getMessage());
			}
		}).start();
	}

    private void copyFile(String task_id, String file_name, String source_dir_id, String target_dir_id, boolean force_overwrite) throws Exception {
        // 查找源文件
        JSONObject source_file_db = db_helper.db_get("SELECT * FROM file WHERE parent_dir_id = ? AND name = ?", new JSONArray().put(source_dir_id).put(file_name));
        if (source_file_db == null) {
            if (task_id != null) call_js_callback(task_id, false, null, "源文件不存在");
            return;
        }

        // 检查目标位置是否存在同名文件
        JSONObject target_file_exist = db_helper.db_get("SELECT id FROM file WHERE parent_dir_id = ? AND name = ?", new JSONArray().put(target_dir_id).put(file_name));
        if (target_file_exist != null) {
            if (!force_overwrite) {
                if (task_id != null) {
                    JSONObject response = new JSONObject().put("needConfirm", true).put("message", "目标位置已存在同名文件，是否覆盖？");
                    call_js_callback(task_id, false, response, null);
                }
                return;
            } else {
                // 覆盖：删除旧文件
                batchDeleteFiles(new ArrayList<String>() {{ add(target_file_exist.getString("id")); }});
            }
        }

        // 开始复制
        String new_file_id = IdGenerator.getIdByTime();

        // 复制物理文件
        String source_real_name = source_file_db.getString("real_file_name");
        String extension = source_file_db.optString("extension", "");
        String new_real_name = new_file_id + (extension.isEmpty() ? "" : "." + extension);
        if (!copyPhysicalFile(source_real_name, new_real_name, false)) {
            if (task_id != null) call_js_callback(task_id, false, null, "物理文件复制失败");
            return;
        }

        // 复制图标（如果存在）
        String new_real_icon_name = null;
        if (source_file_db.has("real_icon_name") && !source_file_db.isNull("real_icon_name")) {
            String source_real_icon_name = source_file_db.getString("real_icon_name");
            if(source_real_icon_name != null && !source_real_icon_name.isEmpty()){
                new_real_icon_name = new_file_id + "_icon.png";
                copyPhysicalFile(source_real_icon_name, new_real_icon_name, true);
            }
        }

        // 插入新的文件数据库记录
        JSONArray insert_file_params = new JSONArray()
            .put(new_file_id)
            .put(file_name)
            .put(target_dir_id)
            .put("file")
            .put(new_real_name)
            .put(new_real_icon_name) // 添加real_icon_name
            .put(System.currentTimeMillis())
            .put(source_file_db.getLong("seqnumber"))
            .put(source_file_db.getLong("size"))
            .put(extension);
        db_helper.db_run("INSERT INTO file (id, name, parent_dir_id, type, real_file_name, real_icon_name, createtime, seqnumber, size, extension) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", insert_file_params);

        if (task_id != null) {
            JSONObject new_file_info = db_helper.db_get("SELECT * from file where id = ?", new JSONArray().put(new_file_id));
            call_js_callback(task_id, true, new_file_info, null);
        }
    }

    private void copyDirectory(String task_id, String dir_name, String source_dir_id, String target_dir_id, boolean force_overwrite) throws Exception {
        // 查找源目录
        JSONObject source_dir = db_helper.db_get("SELECT * FROM dir WHERE parent_dir_id = ? AND name = ?", new JSONArray().put(source_dir_id).put(dir_name));
        if (source_dir == null) {
            call_js_callback(task_id, false, null, "源目录不存在");
            return;
        }
        String source_root_id = source_dir.getString("id");

        // 检查目标位置是否存在同名目录
        JSONObject target_dir_exist = db_helper.db_get("SELECT id FROM dir WHERE parent_dir_id = ? AND name = ?", new JSONArray().put(target_dir_id).put(dir_name));
        if (target_dir_exist != null && !force_overwrite) {
            JSONObject response = new JSONObject().put("needConfirm", true).put("message", "目标位置已存在同名目录，是否合并？");
            call_js_callback(task_id, false, response, null);
            return;
        }

        // 防止将父目录复制到子目录中
        if (isAncestor(source_root_id, target_dir_id)) {
            call_js_callback(task_id, false, null, "不能将目录复制到其自身或子目录中");
            return;
        }

        // 使用队列进行广度优先复制
        Queue<Pair<String, String>> copy_queue = new LinkedList<>();

        // 如果目标位置存在，则使用其ID作为复制根，否则创建新目录
        String target_root_id;
        if(target_dir_exist != null) { // 合并模式
            target_root_id = target_dir_exist.getString("id");
        } else { // 新建模式
            target_root_id = IdGenerator.getIdByTime();
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

        while (!copy_queue.isEmpty()) {
            Pair<String, String> current = copy_queue.poll();
            String current_source_id = current.first;
            String current_target_parent_id = current.second;

            // 复制当前目录下的所有文件
            JSONArray files_to_copy = db_helper.db_all("SELECT name FROM file WHERE parent_dir_id = ?", new JSONArray().put(current_source_id));
            for(int i=0; i < files_to_copy.length(); i++) {
                String file_name_to_copy = files_to_copy.getJSONObject(i).getString("name");
                // 调用文件复制，传入null task_id，因为它在循环内部
                copyFile(null, file_name_to_copy, current_source_id, current_target_parent_id, force_overwrite);
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
                    new_sub_dir_id = IdGenerator.getIdByTime();
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
            }
        }
        call_js_callback(task_id, true, null, null);
    }

	private boolean copyPhysicalFile(String source_name, String target_name, boolean is_icon) {
		try {
            File source_dir = is_icon ? createIconDirectory() : createDataDirectory();
            File target_dir = is_icon ? createIconDirectory() : createDataDirectory();
			if (source_dir == null || target_dir == null) return false;

			File source_file = new File(source_dir, source_name);
			if (!source_file.exists()) return false;

			byte[] file_data = readFileToBytes(source_file);
			if (file_data == null) {
				android.util.Log.e("CopyPhysicalFile", "读取源文件失败: " + source_name);
				return false;
			}

            File target_file = new File(target_dir, target_name);
			return saveFile(file_data, target_file);

		} catch (Exception e) {
			android.util.Log.e("CopyPhysicalFile", "物理文件复制时发生异常", e);
			return false;
		}
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
						String new_dir_id = IdGenerator.getIdByTime();
						long seq_number = getSeqNumberFromString(dir_name);

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
			try {
				JSONObject data = new JSONObject(json_data);
				JSONArray ids = data.getJSONArray("ids");
				int totalItems = ids.length();

				String targetDirPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/filecamera/";
				File exportDir = new File(targetDirPath);

				if (!ensureDirectoryExists(exportDir)) {
					throw new IOException("无法创建导出目录: " + targetDirPath);
				}
				checkAndCreateDirectoryStructure(exportDir);
				showToast("正在导出中......");
                for (int i = 0; i < totalItems; i++) {
                    String id = ids.getString(i);
                    final int currentProgress = i + 1;

                    JSONObject dirData = getDataById(id, "dir");
                    if (dirData != null) {
                        String dirName = dirData.getString("name");
                        String zipFileName = dirName + ".zip";
                        String finalZipFileName = getFinalZipFileName(exportDir, zipFileName);

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            createZipWithMediaStore(dirData, finalZipFileName, dirName);
                        } else {
                            createZipWithFile(dirData, targetDirPath, finalZipFileName, dirName);
                        }
                    } else {
                        JSONObject fileData = getDataById(id, "file");
                        if (fileData != null) {
                            moveFileToTarget(fileData, exportDir);
                        } else {
                            android.util.Log.w("Export", "ID为 " + id + " 的项目未找到，已跳过。");
                        }
                    }
                }
				showToast("导出完成");
				call_js_callback(task_id, true, "已导出到 " + targetDirPath, null);

			} catch (Exception e) {
				android.util.Log.e("ExportError", "导出项目时发生错误", e);
				showToast("导出失败: " + e.getMessage());
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
	private void deleteDirectoryContents(File directory) {
		if (directory == null || !directory.exists() || !directory.isDirectory()) return;
		try {
			Deque<File> traversalDeque = new ArrayDeque<>();
			Deque<File> dirsToDelete = new ArrayDeque<>();
			traversalDeque.push(directory);
			while (!traversalDeque.isEmpty()) {
				File current = traversalDeque.pop();
				File[] files = current.listFiles();
				if (files != null) {
					for (File file : files) {
						if (file.isDirectory()) {
							traversalDeque.push(file);
							dirsToDelete.push(file);
						} else {
							if (!file.delete()) {
								android.util.Log.w("Export", "无法删除文件: " + file.getAbsolutePath());
							}
						}
					}
				}
			}
			while (!dirsToDelete.isEmpty()) {
				File dirToDelete = dirsToDelete.pop();
				if (!dirToDelete.delete()) {
					android.util.Log.w("Export", "无法删除目录: " + dirToDelete.getAbsolutePath());
				}
			}
		} catch (Exception e) {
			android.util.Log.e("Export", "删除目录内容时发生异常: " + directory.getAbsolutePath(), e);
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

	private void createZipWithMediaStore(JSONObject dirData, String zipFileName, String dirName) throws Exception {
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
					writeDirectoryToZip(zos, dirData, dirData.getString("name") + "/");
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
	private void createZipWithFile(JSONObject dirData, String targetDirPath, String zipFileName, String dirName) throws Exception {
		File targetDir = new File(targetDirPath);
		if (!ensureDirectoryExists(targetDir)) throw new IOException("无法创建目标目录: " + targetDirPath);

		File zipFile = new File(targetDir, zipFileName);

		try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
			writeDirectoryToZip(zos, dirData, dirData.getString("name") + "/");
		}

		MediaScannerConnection.scanFile(context, new String[]{zipFile.getAbsolutePath()}, null, null);
	}

	// [重构] 将目录写入ZIP流，使用SQL查询
	private void writeDirectoryToZip(ZipOutputStream zos, JSONObject dirData, String pathInZip) throws Exception {
		String rootDirId = dirData.getString("id");
		final int totalFiles = countFilesInDirectory(rootDirId);
		int filesZipped = 0;

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
                String fileName = fileInfo.getString("name");
                String storedFilename = fileInfo.getString("real_file_name");
                File sourceFile = new File(createDataDirectory(), storedFilename);

                if (sourceFile.exists()) {
                    byte[] fileBytes = readFileToBytes(sourceFile);
                    if (fileBytes != null) {
                        ZipEntry fileEntry = new ZipEntry(currentPathInZip + fileName);
                        zos.putNextEntry(fileEntry);
                        zos.write(fileBytes);
                        zos.closeEntry();
                        filesZipped++;
                        updateLoadingMessage("正在压缩: " + filesZipped + "/" + totalFiles);
                    }
                }
            }

			// 处理子目录
            JSONArray subDirs = db_helper.db_all("SELECT id, name FROM dir WHERE parent_dir_id = ?", new JSONArray().put(currentId));
            for(int i = 0; i < subDirs.length(); i++) {
                JSONObject subDirInfo = subDirs.getJSONObject(i);
                String subDirId = subDirInfo.getString("id");
                String subDirName = subDirInfo.getString("name");
                queue.add(new Pair<>(subDirId, currentPathInZip + subDirName + "/"));
            }
		}
	}
	private void updateLoadingMessage(final String message) {
		Activity activity = (Activity) context;
		activity.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				String escapedMessage = message.replace("\\", "\\\\").replace("'", "\\'");
				String jsCall = "javascript:window.updata_load_info('" + escapedMessage + "');";
				webView.evaluateJavascript(jsCall, null);
			}
		});
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

    // 工具方法
	private boolean saveFile(byte[] data, File targetFile) {
		File parentDir = targetFile.getParentFile();
		if (parentDir != null && !parentDir.exists()) {
			if (!parentDir.mkdirs()) return false;
		}
		try (FileOutputStream fos = new FileOutputStream(targetFile)) {
			fos.write(data);
			return true;
		} catch (IOException e) {
			return false;
		}
	}
	private byte[] copy_file_from_uri(String content_uri) throws IOException {
        Uri uri = Uri.parse(content_uri);
        try (InputStream input_stream = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output_stream = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int bytes_read;
            while ((bytes_read = input_stream.read(buffer)) != -1) {
                output_stream.write(buffer, 0, bytes_read);
            }
            return output_stream.toByteArray();
        }
    }

    private boolean is_image_file(String filename) {
        return filename.toLowerCase().matches(".*\\.(jpg|jpeg|png|gif|bmp|webp|svg)$");
    }

	private byte[] icon_make(byte[] file_data, String filename) {
		try {
			if (!is_image_file(filename)) {
				return null;
			}

			Bitmap original_bitmap = BitmapFactory.decodeByteArray(file_data, 0, file_data.length);
			if (original_bitmap == null) {
				return null;
			}

			int original_width = original_bitmap.getWidth();
			int original_height = original_bitmap.getHeight();
			float aspect_ratio = (float) original_width / (float) original_height;

			int new_width;
			int new_height;
			if (original_width >= original_height) {
				new_width = 256;
				new_height = Math.round(256 / aspect_ratio);
			} else {
				new_height = 256;
				new_width = Math.round(256 * aspect_ratio);
			}

			Bitmap scaled_bitmap = Bitmap.createScaledBitmap(original_bitmap, new_width, new_height, true);

			ByteArrayOutputStream output_stream = new ByteArrayOutputStream();
			scaled_bitmap.compress(Bitmap.CompressFormat.PNG, 100, output_stream);
			
			original_bitmap.recycle();
			scaled_bitmap.recycle();

			return output_stream.toByteArray();
		} catch (Exception e) {
			return null;
		}
	}

    private long getSeqNumberFromString(String input) {
        if (input == null || input.isEmpty()) return 0L;

        StringBuilder numberBuilder = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (Character.isDigit(c)) {
                numberBuilder.append(c);
            }
        }
        String numberResult = numberBuilder.toString();
        if (numberResult.length() > 18) {
            numberResult = numberResult.substring(numberResult.length() - 18);
        }

        if (numberResult.isEmpty()) return 0L;

        try {
            return Long.parseLong(numberResult);
        } catch (NumberFormatException e) {
            return 0L;
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



}
