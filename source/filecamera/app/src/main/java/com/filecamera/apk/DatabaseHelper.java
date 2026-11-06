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
    // v2：新增 pending_file（入库预备表，对现有表零侵入）
    // v5：补齐 parent_dir_id / seqnumber 索引（老版本升级上来的库可能没有；
    //     封面查询用 INDEXED BY 固定执行计划，要求索引确实存在）
    // v6：过渡版本（未发布）：新增过独立的 pending_file_op，已废弃
    // v7：pending_file 升级为统一待执行任务表（op = import/copy/delete），启动时自愈
    // v8：copy 任务记录被覆盖的旧行 id（old_file_id），崩溃恢复时先补删旧目标
    private static final int DATABASE_VERSION = 8;

    private static volatile DatabaseHelper instance;

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    /** 全局唯一的数据库连接，避免相机入库与 Web 桥各开一个连接互相锁 */
    public static DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            synchronized (DatabaseHelper.class) {
                if (instance == null) {
                    instance = new DatabaseHelper(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /**
     * 待执行任务表：应用启动时靠它修复/自愈中断的操作。
     *
     * <p>op = import：新文件即将入库（id 是新文件 id，tmp_photo_path 是临时文件）；
     * op = copy：新文件即将复制出来（id 是新文件 id，real_file_name 是目标物理名）；
     * op = delete：旧文件即将删除（id 是旧文件 id，real_file_name/real_icon_name 是要删的文件）。
     * 正常收尾立刻删行；进程被杀留下的行由启动修复统一处理。
     */
    private static final String CREATE_PENDING_FILE =
            "CREATE TABLE IF NOT EXISTS pending_file (" +
                    "id TEXT PRIMARY KEY," +
                    "name TEXT NOT NULL," +
                    "parent_dir_id TEXT NOT NULL," +
                    "type TEXT DEFAULT 'file'," +
                    "real_file_name TEXT," +
                    "real_icon_name TEXT," +
                    "createtime INTEGER," +
                    "seqnumber INTEGER DEFAULT 0," +
                    "size INTEGER," +
                    "extension TEXT," +
                    "tmp_photo_path TEXT," +
                    "tmp_icon_path TEXT," +
                    "old_file_id TEXT," +
                    "op TEXT NOT NULL DEFAULT 'import')";

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
        // 入库预备表（v2 新增）
        db.execSQL(CREATE_PENDING_FILE);
        // 插入根目录
        db.execSQL("INSERT OR IGNORE INTO dir (id, name, parent_dir_id, type, dirsortmode, filesortmode, createtime) " +
                "VALUES ('root', '', NULL, 'dir', 2, 2, " + System.currentTimeMillis() + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 只做增量建表/加列，不改老表结构，升级不会与现有数据冲突
        if (oldVersion < 2) {
            db.execSQL(CREATE_PENDING_FILE);
        }
        if (oldVersion < 5) {
            // 早期版本的 onCreate 未必建过这些索引，这里统一补上（已存在则是空操作）。
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_dir_parent_dir_id ON dir (parent_dir_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_file_parent_dir_id ON file (parent_dir_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_file_seqnumber ON file (seqnumber)");
        }
        if (oldVersion < 7) {
            // v5 的 pending_file 没有 op；v6 是未发布的过渡版本（独立 pending_file_op），一并兼容
            if (!hasColumn(db, "pending_file", "op")) {
                db.execSQL("ALTER TABLE pending_file ADD COLUMN op TEXT NOT NULL DEFAULT 'import'");
            }
            db.execSQL("DROP TABLE IF EXISTS pending_file_op");
        }
        if (oldVersion < 8) {
            if (!hasColumn(db, "pending_file", "old_file_id")) {
                db.execSQL("ALTER TABLE pending_file ADD COLUMN old_file_id TEXT");
            }
        }
    }

    /** 表里是否已有某列（增量迁移用）。 */
    private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(cursor.getColumnIndex("name")))) {
                    return true;
                }
            }
        }
        return false;
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
