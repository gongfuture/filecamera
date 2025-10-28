package com.filecamera.apk;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "backserver.db";
    private static final int DATABASE_VERSION = 1;

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // 创建表的SQL语句 (V4版本)
        db.execSQL("CREATE TABLE IF NOT EXISTS dir (" +
                "id TEXT PRIMARY KEY," +
                "name TEXT NOT NULL," +
                "parent_dir_id TEXT," +
                "type TEXT DEFAULT 'dir'," +
                "dirsortmode INTEGER DEFAULT 2," +
                "filesortmode INTEGER DEFAULT 2," +
                "createtime INTEGER," +
                "seqnumber INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE IF NOT EXISTS file (" +
                "id TEXT PRIMARY KEY," +
                "name TEXT NOT NULL," +
				"parent_dir_id TEXT NOT NULL," +
                "type TEXT DEFAULT 'file'," +
                "real_file_name TEXT, " + // 重命名字段：存储真实文件名
                "real_icon_name TEXT, " + // 重命名字段：存储图标的真实文件名
                "createtime INTEGER, " +
                "seqnumber INTEGER DEFAULT 0, " +
                "size INTEGER, " +
                "extension TEXT)");

		db.execSQL("CREATE INDEX idx_dir_parent_dir_id ON dir (parent_dir_id)");
		db.execSQL("CREATE INDEX idx_file_parent_dir_id ON file (parent_dir_id)");
		db.execSQL("CREATE INDEX idx_file_seqnumber ON file (seqnumber)");
        // 插入根目录
        db.execSQL("INSERT OR IGNORE INTO dir (id, name, parent_dir_id, type, dirsortmode, filesortmode, createtime) " +
                "VALUES ('root', '', NULL, 'dir', 2, 2, " + System.currentTimeMillis() + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    // 通用数据库操作函数 - 类似Node.js的db_get
    public JSONObject db_get(String sql, JSONArray arr) throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        String[] selection_args = null;

        if (arr != null && arr.length() > 0) {
            selection_args = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                selection_args[i] = arr.optString(i);
            }
        }

        Cursor cursor = db.rawQuery(sql, selection_args);
        JSONObject result = null;

        if (cursor.moveToFirst()) {
            result = cursor_to_json(cursor);
        }

        cursor.close();
        return result;
    }

    // 通用数据库操作函数 - 类似Node.js的db_all
    public JSONArray db_all(String sql, JSONArray arr) throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        String[] selection_args = null;

        if (arr != null && arr.length() > 0) {
            selection_args = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                selection_args[i] = arr.optString(i);
            }
        }

        Cursor cursor = db.rawQuery(sql, selection_args);
        JSONArray results = new JSONArray();

        while (cursor.moveToNext()) {
            results.put(cursor_to_json(cursor));
        }

        cursor.close();
        return results;
    }

    // 通用数据库操作函数 - 类似Node.js的db_run
    public JSONObject db_run(String sql, JSONArray arr) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        String[] bind_args = null;

        if (arr != null && arr.length() > 0) {
            bind_args = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                // 处理 JSON null
                if (arr.isNull(i)) {
                    bind_args[i] = null;
                } else {
                    bind_args[i] = arr.optString(i);
                }
            }
        }

        SQLiteStatement statement = db.compileStatement(sql);

        if (bind_args != null) {
            for (int i = 0; i < bind_args.length; i++) {
                if (bind_args[i] == null) {
                    statement.bindNull(i + 1);
                } else {
                    statement.bindString(i + 1, bind_args[i]);
                }
            }
        }

        long result = statement.executeUpdateDelete();

        JSONObject return_obj = new JSONObject();
        return_obj.put("changes", result);
        return_obj.put("lastID", get_last_insert_row_id(db));

        statement.close();
        return return_obj;
    }

    // 工具函数：将Cursor转换为JSONObject
    private JSONObject cursor_to_json(Cursor cursor) throws JSONException {
        JSONObject json = new JSONObject();
        String[] column_names = cursor.getColumnNames();

        for (String column_name : column_names) {
            int column_index = cursor.getColumnIndex(column_name);

            switch (cursor.getType(column_index)) {
                case Cursor.FIELD_TYPE_NULL:
                    json.put(column_name, JSONObject.NULL);
                    break;
                case Cursor.FIELD_TYPE_INTEGER:
                    json.put(column_name, cursor.getLong(column_index));
                    break;
                case Cursor.FIELD_TYPE_FLOAT:
                    json.put(column_name, cursor.getDouble(column_index));
                    break;
                case Cursor.FIELD_TYPE_STRING:
                    json.put(column_name, cursor.getString(column_index));
                    break;
                case Cursor.FIELD_TYPE_BLOB:
                    json.put(column_name, cursor.getBlob(column_index));
                    break;
            }
        }

        return json;
    }

    // 获取最后插入的行ID
    private long get_last_insert_row_id(SQLiteDatabase db) {
        Cursor cursor = db.rawQuery("SELECT last_insert_rowid()", null);
        long last_id = -1;
        if (cursor.moveToFirst()) {
            last_id = cursor.getLong(0);
        }
        cursor.close();
        return last_id;
    }
}
