package com.filecamera.apk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 水印配置管理器 (单例模式)
 * 负责统一管理水印配置的读取、写入和缓存
 */
public class WatermarkConfigManager {

    private static final String TAG = "WatermarkConfigManager";
    private static final String PREFERENCES_NAME = AppConfig.PREFS_NAME;
    private static final String WATERMARK_CONFIG_KEY = AppConfig.KEY_WATERMARK_CONFIG;
    /** 拍照声音默认值从「关」改为「开」的一次性迁移标记 */
    private static final String KEY_SOUND_DEFAULT_APPLIED = "shutter_sound_default_on_applied";

    private static WatermarkConfigManager instance;
    private final Context appContext;
    private final SharedPreferences sharedPreferences;
    
    // 内存缓存，保证读取速度
    private String cachedConfig = null;

    private WatermarkConfigManager(Context context) {
        // 使用 Application Context 防止内存泄漏
        this.appContext = context.getApplicationContext();
        this.sharedPreferences = appContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized WatermarkConfigManager getInstance(Context context) {
        if (instance == null) {
            instance = new WatermarkConfigManager(context);
        }
        return instance;
    }

    /**
     * 获取水印配置
     * 优先从内存缓存读取，如果为空则从 SP 读取，如果还为空则返回默认配置
     */
    public synchronized String getConfig() {
        if (cachedConfig != null && !cachedConfig.isEmpty()) {
            return cachedConfig;
        }

        String savedConfig = sharedPreferences.getString(WATERMARK_CONFIG_KEY, null);
        // 水印配置是整份 JSON，null/空字符串都按未加载处理；
        // 标量设置（服务器、高德 key）才严格区分 null 和 ""。
        if (savedConfig != null && !savedConfig.isEmpty()) {
            try {
                // 校验 JSON 格式是否有效
                new JSONObject(savedConfig);
                this.cachedConfig = migrateShutterSoundDefault(savedConfig);
                return this.cachedConfig;
            } catch (JSONException e) {
                Log.w(TAG, "本地存储的配置格式无效，将使用默认配置");
            }
        }

        // 如果没有配置或配置无效，生成默认配置
        String defaultConfig = getDefaultWatermarkConfig();
        // 自动保存默认配置到本地，以便下次读取
        saveConfig(defaultConfig); 
        return defaultConfig;
    }

    /**
     * 一次性迁移：早期版本「拍照声音」默认关闭，配置里会存下 "shutter_sound": false。
     * 新版本默认开启，这里对老配置纠正一次；之后用户在设置里的开关会被正常尊重（迁移只跑一次）。
     */
    private String migrateShutterSoundDefault(String json) {
        if (sharedPreferences.getBoolean(KEY_SOUND_DEFAULT_APPLIED, false)) {
            return json;
        }
        sharedPreferences.edit().putBoolean(KEY_SOUND_DEFAULT_APPLIED, true).apply();
        try {
            JSONObject root = new JSONObject(json);
            JSONObject camera = root.optJSONObject("camera");
            if (camera == null) {
                return json;
            }
            camera.put("shutter_sound", true);
            String migrated = root.toString();
            saveConfig(migrated);
            Log.i(TAG, "已将拍照声音默认值迁移为开启");
            return migrated;
        } catch (JSONException e) {
            Log.w(TAG, "拍照声音默认值迁移失败", e);
            return json;
        }
    }

    /**
     * 保存水印配置
     * 同步更新内存缓存和 SharedPreferences
     */
    public synchronized boolean saveConfig(String jsonConfig) {
        if (jsonConfig == null || jsonConfig.isEmpty()) {
            return false;
        }
        try {
            // 校验格式
            new JSONObject(jsonConfig);
            
            // 1. 更新内存缓存
            this.cachedConfig = jsonConfig;
            
            // 2. 持久化存储
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(WATERMARK_CONFIG_KEY, jsonConfig);
            return editor.commit(); // 使用 commit 确保同步写入成功
        } catch (JSONException e) {
            Log.e(TAG, "尝试保存无效的 JSON 配置", e);
            return false;
        }
    }

    /**
     * 获取默认的水印配置 JSON 字符串（与前端共用当前选中的默认配置）。
     */
    private String getDefaultWatermarkConfig() {
        return AppConfig.getDefaultConfig(appContext);
    }
}
