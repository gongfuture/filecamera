package com.filecamera.apk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 水印配置管理器 (单例模式)
 * 负责统一管理水印配置的读取、写入和缓存
 */
public class WatermarkConfigManager {

    private static final String TAG = "WatermarkConfigManager";
    private static final String PREFERENCES_NAME = "watermark_settings";
    private static final String WATERMARK_CONFIG_KEY = "watermark_config_json";

    private static WatermarkConfigManager instance;
    private final SharedPreferences sharedPreferences;
    
    // 内存缓存，保证读取速度
    private String cachedConfig = null;

    private WatermarkConfigManager(Context context) {
        // 使用 Application Context 防止内存泄漏
        this.sharedPreferences = context.getApplicationContext()
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
        if (savedConfig != null && !savedConfig.isEmpty()) {
            try {
                // 校验 JSON 格式是否有效
                new JSONObject(savedConfig);
                this.cachedConfig = savedConfig;
                return savedConfig;
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
     * 获取默认的水印配置 JSON 字符串
     */
    private String getDefaultWatermarkConfig() {
        try {
            JSONObject rootConfig = new JSONObject();

            // --- 相机配置 ---
            JSONObject camera = new JSONObject()
                    .put("proportion", "16:9")
                    .put("sync_album", false);
            rootConfig.put("camera", camera);

            // --- 水印配置 ---
            JSONObject watermark = new JSONObject()
                    .put("enable", true)
                    .put("scale", 0.75)
                    .put("padding", new JSONArray("[16, 16, 16, 16]"))
                    .put("radius", new JSONArray("[8, 8, 8, 8]"))
                    .put("position", new JSONObject().put("left", 10).put("bottom", 10));

            // 页眉
            JSONObject header = new JSONObject()
                    .put("display", true)
                    .put("background", new JSONArray("[27, 68, 147, 255]"))
                    .put("icon", new JSONObject()
                            .put("display", true)
                            .put("value", "file:///android_asset/icon.png")
                            .put("width", 48)
                            .put("height", 48)
                            .put("position", "left"))
                    .put("title", new JSONObject()
                            .put("value", "巡查记录")
                            .put("color", "#FFFFFF")
                            .put("size", 20));

            // 主体
            JSONObject body = new JSONObject()
                    .put("display", true)
                    .put("background", new JSONArray("[248, 250, 252, 170]"))
                    .put("content", new JSONObject()
                            .put("road_name", new JSONObject().put("name", "路线名称").put("type", "road").put("name_display", true).put("index", 1).put("color", "#2C3E50").put("size", 16))
                            .put("pile", new JSONObject().put("name", "桩号").put("type", "camera_pile").put("name_display", true).put("index", 2).put("color", "#2C3E50").put("size", 16))
                            .put("coord", new JSONObject().put("name", "坐标").put("type", "camera_coord").put("name_display", true).put("index", 3).put("color", "#2C3E50").put("size", 16))
                            .put("time", new JSONObject().put("name", "时间").put("type", "time").put("name_display", true).put("index", 4).put("color", "#2C3E50").put("size", 16))
                    );

            // 页脚
            JSONObject foot = new JSONObject()
                    .put("display", true)
                    .put("background", new JSONArray("[27, 68, 147, 255]"))
                    .put("content", new JSONObject()
                            .put("unit", new JSONObject().put("name", "责任单位").put("type", "string").put("name_display", true).put("value", "").put("index", 1).put("color", "#FFFFFF").put("size", 12))
                    );

            watermark.put("header", header)
                    .put("body", body)
                    .put("foot", foot);

            rootConfig.put("watermark", watermark);

            // --- 二维码配置 ---
            JSONObject qrcode = new JSONObject()
                    .put("enable", true)
                    .put("scale", 0.75)
                    .put("padding", new JSONArray("[4, 4, 4, 4]"))
                    .put("radius", new JSONArray("[8, 8, 8, 8]"))
                    .put("position", new JSONObject().put("right", 10).put("top", 10));

            rootConfig.put("qrcode", qrcode);

            return rootConfig.toString();
        } catch (JSONException e) {
            e.printStackTrace();
            return "{}";
        }
    }
}
