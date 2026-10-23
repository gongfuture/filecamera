package com.filecamera.apk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 应用配置默认值。
 *
 * <p>默认配置是整份二选一，互斥、不合并：</p>
 * <ul>
 *     <li>存在 config/custom_config.json：只读定制默认值；</li>
 *     <li>不存在：只读 config/default_config.json 真默认值。</li>
 * </ul>
 *
 * <p>定制文件必须是完整配置，不会从真默认值里补缺失字段；
 * 定制文件存在但损坏时直接报错，不会把字段回退到真默认值。</p>
 *
 * <p>前端通过 FilePicker.readConfig 的 serverUrl/serverKey/gaodeWebKey/defaultConfig
 * 取得当前选中的默认值；Java 侧 WatermarkConfigManager、GaodeApi 也读同一份配置。</p>
 */
public final class AppConfig {

    private static final String TAG = "AppConfig";
    private static final String ASSET_DEFAULT = "config/default_config.json";
    private static final String ASSET_CUSTOM = "config/custom_config.json";

    // 统一常量，避免各桥接类各写一份偏好文件名/key。
    public static final String PREFS_NAME = "watermark_settings";
    public static final String KEY_WATERMARK_CONFIG = "watermark_config_json";
    public static final String KEY_SERVER_URL = "serverUrl";
    public static final String KEY_SERVER_KEY = "serverKey";
    public static final String KEY_GAODE_WEB_KEY = "gaodeWebKey";

    private static JSONObject cached;

    private AppConfig() {
    }

    /**
     * 有 custom_config.json 就整份读 custom，否则整份读 default。
     * 不做字段合并，结果缓存。
     */
    public static synchronized JSONObject get(Context context) {
        if (cached != null) {
            return cached;
        }

        JSONObject result = new JSONObject();
        if (context != null) {
            String assetName = hasAsset(context, ASSET_CUSTOM) ? ASSET_CUSTOM : ASSET_DEFAULT;
            try {
                result = loadAsset(context, assetName);
                Log.d(TAG, "使用配置: " + assetName);
            } catch (Exception e) {
                // custom 存在但损坏时直接报错，不把字段回退到 default。
                Log.e(TAG, "读取 " + assetName + " 失败", e);
            }
        }

        cached = result;
        return cached;
    }

    /**
     * 首次启动/偏好缺失时，把当前选中的默认配置写入 SharedPreferences。
     *
     * <p>只补缺失项，不覆盖用户已保存的值；空字符串视为用户已保存，不再回填。
     * 水印配置为空字符串/无效时由 WatermarkConfigManager 负责重新生成。</p>
     */
    public static void ensureDefaults(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = appContext.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
        JSONObject config = get(context);

        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;

        // 标量配置严格区分 null 和 ""：
        // - key 不存在（未加载）→ 写入默认值；
        // - key 已存在，即使是 "" → 视为用户显式清空，不覆盖。
        if (!preferences.contains(KEY_SERVER_URL)) {
            editor.putString(KEY_SERVER_URL, optString(config, "server", "url"));
            changed = true;
        }
        if (!preferences.contains(KEY_SERVER_KEY)) {
            editor.putString(KEY_SERVER_KEY, optString(config, "server", "key"));
            changed = true;
        }
        if (!preferences.contains(KEY_GAODE_WEB_KEY)) {
            editor.putString(KEY_GAODE_WEB_KEY, optString(config, "gaode", "web_key"));
            changed = true;
        }

        // 水印配置是一整份 JSON，"" 不是合法配置，按未加载/无效处理，回退默认值。
        String watermarkConfig = preferences.getString(KEY_WATERMARK_CONFIG, null);
        if (watermarkConfig == null || watermarkConfig.trim().isEmpty()) {
            editor.putString(KEY_WATERMARK_CONFIG, getDefaultConfig(context));
            changed = true;
        }

        if (changed && !editor.commit()) {
            Log.w(TAG, "写入默认配置到 SharedPreferences 失败");
        }
    }

    /**
     * 返回配置项默认值：serverUrl / serverKey / gaodeWebKey / defaultConfig。
     * 未知 key 返回 null，表示不参与默认值回填。
     */
    public static String getDefaultValue(Context context, String key) {
        JSONObject config = get(context);
        switch (key) {
            case "serverUrl":
                return optString(config, "server", "url");
            case "serverKey":
                return optString(config, "server", "key");
            case "gaodeWebKey":
                return optString(config, "gaode", "web_key");
            case "defaultConfig":
                return getDefaultConfig(context);
            default:
                return null;
        }
    }

    public static String getGaodeWebKey(Context context) {
        return optString(get(context), "gaode", "web_key");
    }

    /**
     * 水印/相机/二维码的当前默认配置（真默认或定制默认），前后端共用。
     */
    public static String getDefaultConfig(Context context) {
        JSONObject defaultConfig = get(context).optJSONObject("default_config");
        return defaultConfig == null ? "{}" : defaultConfig.toString();
    }

    /**
     * 判断 Assets 中是否存在指定文件。
     */
    private static boolean hasAsset(Context context, String assetName) {
        try (InputStream ignored = context.getApplicationContext()
                .getAssets().open(assetName)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONObject loadAsset(Context context, String assetName) throws Exception {
        return new JSONObject(readAssetText(context, assetName));
    }

    private static String readAssetText(Context context, String assetName) throws Exception {
        try (InputStream input = context.getApplicationContext()
                .getAssets().open(assetName);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[4096];
            int length;
            while ((length = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, length);
            }
            return builder.toString();
        }
    }

    private static String optString(JSONObject root, String section, String key) {
        JSONObject object = root.optJSONObject(section);
        if (object == null) {
            return "";
        }
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        return String.valueOf(value);
    }
}
