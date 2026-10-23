package com.filecamera.apk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * 应用配置默认值。
 *
 * <p>配置分两层：</p>
 * <ul>
 *     <li>config/default_config.json：真默认值，两个版本完全一致；</li>
 *     <li>config/custom_config.json：定制覆盖，可选。开源版为空 {} 或不存在。</li>
 * </ul>
 *
 * <p>合并规则：custom 覆盖 default；对象递归合并，数组/字符串直接覆盖；
 * custom 里没写的 key 继承 default，写了空字符串表示显式覆盖为空。</p>
 *
 * <p>前端通过 FilePicker.readConfig 的 serverUrl/serverKey/gaodeWebKey/defaultConfig
 * 取得合并后的默认值；Java 侧 WatermarkConfigManager、GaodeApi 也读同一份合并结果。</p>
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
     * 读取真默认值，并在存在定制配置时做深合并。结果缓存。
     */
    public static synchronized JSONObject get(Context context) {
        if (cached != null) {
            return cached;
        }

        JSONObject result = new JSONObject();
        if (context != null) {
            try {
                result = loadAsset(context, ASSET_DEFAULT);
            } catch (Exception e) {
                Log.e(TAG, "读取 " + ASSET_DEFAULT + " 失败", e);
            }

            try {
                JSONObject custom = loadAsset(context, ASSET_CUSTOM);
                deepMerge(result, custom);
            } catch (FileNotFoundException e) {
                // 定制覆盖是可选的：不存在时直接使用真默认值。
                Log.d(TAG, "未找到 " + ASSET_CUSTOM + "，使用真默认值");
            } catch (Exception e) {
                // 定制文件为空或格式错误时也回退真默认值，不影响 App 启动。
                Log.w(TAG, "读取 " + ASSET_CUSTOM + " 失败，使用真默认值", e);
            }
        }

        cached = result;
        return cached;
    }

    /**
     * 首次启动/偏好缺失时，把合并后的默认配置写入 SharedPreferences。
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
     * 水印/相机/二维码的合并后默认配置，前后端共用。
     */
    public static String getDefaultConfig(Context context) {
        JSONObject defaultConfig = get(context).optJSONObject("default_config");
        return defaultConfig == null ? "{}" : defaultConfig.toString();
    }

    private static JSONObject loadAsset(Context context, String assetName) throws Exception {
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
            return new JSONObject(builder.toString());
        }
    }

    /**
     * 把 override 深合并到 base。对象递归合并，其余类型直接覆盖；
     * null 视为未配置，保留 base 的值。
     */
    private static void deepMerge(JSONObject base, JSONObject override) {
        Iterator<String> keys = override.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object overrideValue = override.opt(key);
            if (overrideValue == null || overrideValue == JSONObject.NULL) {
                continue;
            }

            Object baseValue = base.opt(key);
            if (baseValue instanceof JSONObject && overrideValue instanceof JSONObject) {
                deepMerge((JSONObject) baseValue, (JSONObject) overrideValue);
            } else {
                try {
                    base.put(key, overrideValue);
                } catch (Exception e) {
                    Log.w(TAG, "合并配置项失败: " + key, e);
                }
            }
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
