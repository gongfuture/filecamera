package com.filecamera.apk; // 请确保包名与您的项目一致

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 地图、坐标转换与天气相关的工具类。
 * 包含：API Key管理、网络状态检查、天气信息获取与缓存、地址信息获取与缓存、坐标转换。
 * [重构] 移除了所有UI回调和Handler，此类现在只负责数据处理和缓存管理。
 * [修改 v2] 细化了失败返回类型，区分：无Key、Key错误、获取失败。
 * [修改 v3] 为天气(Weather)和地址(Address)添加了异步回调(Push)模式。
 *
 * @author Wandergis (Original JS Author)
 * @author Gemini (Java Translator & Refactor)
 * Created on 2025/10/13
 * LastModified on 2025/10/28
 */
public final class GaodeApi {

    private static final String TAG = "GaodeApi";

    // --- 构造函数私有化，防止实例化工具类 ---
    private GaodeApi() {}

    // region --- Public Error Constants ---

    /**
     * 公共错误常量：表示 API Key 尚未配置
     */
    public static final String ERROR_NO_KEY = "Key未配置";
    /**
     * 公共错误常量：表示 API Key 无效 (INVALID_USER_KEY)
     */
    public static final String ERROR_INVALID_KEY = "Key不正确";
    /**
     * 公共错误常量：表示因网络、超时或服务器内部错误导致的获取失败
     */
    public static final String ERROR_FETCH_FAILED = "获取失败";

    // endregion

    // region --- API Key Management ---

    private static final String PREFS_NAME = "watermark_settings";
    private static final String KEY_GAODE_WEB_API = "gaodeWebKey";

    public static void saveGaodeApiKey(Context context, String apiKey) {
        if (context == null || apiKey == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_GAODE_WEB_API, apiKey).apply();
    }

    public static String getGaodeApiKey(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_GAODE_WEB_API, null);
    }

    // endregion

    // region --- Weather Caching & Fetching ---

    private static final long WEATHER_CACHE_EXPIRATION_MS = 60 * 60 * 1000;
    private static final String WEATHER_PREFS_NAME = "weather_cache";
    private static final String PREF_KEY_WEATHER_INFO = "weather_info";
    private static final String PREF_KEY_WEATHER_TIMESTAMP = "weather_timestamp";
    private static final String PREF_KEY_WEATHER_ADCODE = "weather_adcode";

    // 初始状态
    private static String cachedWeatherInfo = "获取中...";
    private static long weatherCacheTimestamp = 0;
    private static volatile boolean isFetchingWeather = false;
    private static boolean weatherApiCallFailedThisSession = false;

    public static void resetApiCallFailedFlag() {
        weatherApiCallFailedThisSession = false;
    }

    public static String getCachedWeatherInfo() {
        return cachedWeatherInfo;
    }

    public static void loadWeatherFromCache(Context context, String currentAdcode) {
        if (context == null || currentAdcode == null || currentAdcode.trim().isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(WEATHER_PREFS_NAME, Context.MODE_PRIVATE);
        String cachedAdcode = prefs.getString(PREF_KEY_WEATHER_ADCODE, null);
        if (!currentAdcode.trim().equals(cachedAdcode)) {
            Log.d(TAG, "天气adcode已更改，忽略持久化缓存。");
            return;
        }
        long timestamp = prefs.getLong(PREF_KEY_WEATHER_TIMESTAMP, 0);
        if (System.currentTimeMillis() - timestamp < WEATHER_CACHE_EXPIRATION_MS) {
            String info = prefs.getString(PREF_KEY_WEATHER_INFO, null);
            if (info != null) {
                cachedWeatherInfo = info;
                weatherCacheTimestamp = timestamp;
                Log.d(TAG, "从持久化缓存加载天气: " + info);
            }
        }
    }

    /**
     * [新增] 用于天气更新的异步回调接口。
     */
    public interface WeatherCallback {
        /**
         * 当天气信息更新时在后台线程中调用。
         * @param weatherInfo 最新的天气信息，或错误代码 (如 ERROR_NO_KEY)
         */
        void onWeatherUpdated(String weatherInfo);
    }

    public static void requestWeatherUpdateIfStale(Context context, final String adcode, final WeatherCallback callback) {
        
        // [FIX] 唯一保留在外部的检查：防止重复启动线程
        // 如果一个获取任务已在运行中，则直接返回，
        // 那个正在运行的任务会负责在完成后触发回调。
        if (isFetchingWeather) {
            return;
        }
        isFetchingWeather = true; // 立即设置标志

        new Thread(() -> {
            String finalWeatherInfo = null;
            try {
                // --- [FIX] 将所有逻辑检查移入线程内部 ---

                // [FIXED BUG 1] 检查会话失败标志
                if (weatherApiCallFailedThisSession) {
                    finalWeatherInfo = cachedWeatherInfo; // 使用缓存中已有的错误信息
                    return; // 跳转到 finally
                }

                // [FIXED BUG 2] 检查 adcode
                if (adcode == null || adcode.trim().isEmpty()) {
                    cachedWeatherInfo = "adcode未配置";
                    finalWeatherInfo = "adcode未配置";
                    return; // 跳转到 finally
                }

                // [FIXED BUG 3] 检查缓存是否有效
                long now = System.currentTimeMillis();
                boolean isCacheValid = (now - weatherCacheTimestamp < WEATHER_CACHE_EXPIRATION_MS);
                if (isCacheValid) {
                    // 缓存有效，无需联网，直接使用缓存数据回调
                    finalWeatherInfo = cachedWeatherInfo;
                    return; // 跳转到 finally
                }

                // --- 缓存无效，执行网络请求 ---
                
                // 检查 API Key
                String apiKey = getGaodeApiKey(context);
                if (apiKey == null || apiKey.trim().isEmpty()) {
                    cachedWeatherInfo = ERROR_NO_KEY;
                    finalWeatherInfo = ERROR_NO_KEY;
                    weatherApiCallFailedThisSession = true;
                    Log.w(TAG, "天气更新失败: " + ERROR_NO_KEY);
                    return; // 跳转到 finally
                }

                // 执行网络请求
                String newWeatherInfo = fetchWeatherInfo(context, apiKey, adcode);

                if (newWeatherInfo != null) {
                    // 检查是否是Key错误
                    if (newWeatherInfo.equals(ERROR_INVALID_KEY)) {
                        cachedWeatherInfo = ERROR_INVALID_KEY;
                        finalWeatherInfo = ERROR_INVALID_KEY;
                        weatherApiCallFailedThisSession = true;
                        Log.e(TAG, "天气更新失败: " + ERROR_INVALID_KEY);
                    } else {
                        // 成功获取
                        long newTimestamp = System.currentTimeMillis();
                        cachedWeatherInfo = newWeatherInfo;
                        finalWeatherInfo = newWeatherInfo;
                        weatherCacheTimestamp = newTimestamp;
                        SharedPreferences prefs = context.getSharedPreferences(WEATHER_PREFS_NAME, Context.MODE_PRIVATE);
                        prefs.edit()
                                .putString(PREF_KEY_WEATHER_INFO, newWeatherInfo)
                                .putLong(PREF_KEY_WEATHER_TIMESTAMP, newTimestamp)
                                .putString(PREF_KEY_WEATHER_ADCODE, adcode)
                                .apply();
                        Log.d(TAG, "天气获取并缓存成功。");
                    }
                } else {
                    // fetchWeatherInfo返回null，代表通用获取失败
                    cachedWeatherInfo = ERROR_FETCH_FAILED;
                    finalWeatherInfo = ERROR_FETCH_FAILED;
                    weatherApiCallFailedThisSession = true;
                    Log.e(TAG, "天气更新失败: " + ERROR_FETCH_FAILED);
                }
            } finally {
                isFetchingWeather = false; // 释放锁
                
                // [FIX] 现在，此代码块在所有情况下都会被执行
                if (callback != null && finalWeatherInfo != null) {
                    callback.onWeatherUpdated(finalWeatherInfo);
                } else if (callback != null) {
                    // 增加一个保险回调，防止 finalWeatherInfo 意外为 null
                    callback.onWeatherUpdated(ERROR_FETCH_FAILED);
                    Log.e(TAG, "Weather callback triggered with fallback error; finalWeatherInfo was null.");
                }
            }
        }).start();
	}
    // endregion

    // region --- Address Caching & Fetching ---

    private static final String ADDRESS_PREFS_NAME = "address_cache";
    private static final String PREF_KEY_ADDRESS_JSON = "address_json";
    private static final String PREF_KEY_ADDRESS_LAT = "address_lat";
    private static final String PREF_KEY_ADDRESS_LNG = "address_lng";
    private static final double CACHE_DISTANCE_THRESHOLD_METERS = 100.0; // 缓存有效距离：100米

    private static JSONObject cachedAddressJson = null;
    private static double cachedAddressLat = 0.0;
    private static double cachedAddressLng = 0.0;

    /**
     * [新增] 从持久化存储加载地址数据到内存缓存。
     * 建议在应用或Activity启动时调用一次。
     * @param context 应用上下文
     */
    public static void loadAddressFromCache(Context context) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(ADDRESS_PREFS_NAME, Context.MODE_PRIVATE);
        String jsonString = prefs.getString(PREF_KEY_ADDRESS_JSON, null);
        if (jsonString != null) {
            try {
                cachedAddressJson = new JSONObject(jsonString);
                cachedAddressLat = Double.longBitsToDouble(prefs.getLong(PREF_KEY_ADDRESS_LAT, 0));
                cachedAddressLng = Double.longBitsToDouble(prefs.getLong(PREF_KEY_ADDRESS_LNG, 0));
                Log.d(TAG, "从持久化缓存加载地址成功。");
            } catch (JSONException e) {
                Log.e(TAG, "无法从SharedPreferences解析缓存的地址JSON", e);
                cachedAddressJson = null; // 解析失败则清空内存缓存
            }
        }
    }

    /**
     * [新增] 用于地址更新的异步回调接口。
     */
    public interface AddressCallback {
        /**
         * 当地址信息更新时在后台线程中调用。
         * @param addressInfo 最新的地址信息，或错误代码 (如 ERROR_NO_KEY)
         * @param success true 如果成功获取地址, false 如果是错误代码
         */
        void onAddressUpdated(String addressInfo, boolean success);
    }

    /**
     * [新增] [异步请求] 通过GPS(WGS84)坐标获取格式化的地址字符串。
     * <p><b>重要：</b>此方法会立即返回。它将在后台线程中运行
     * {@link #getAddressFromGps(Context, double, double)} 的所有逻辑（包括缓存检查），
     * 并通过回调返回结果。
     *
     * @param context  上下文环境
     * @param gpsLng   GPS经度 (WGS84)
     * @param gpsLat   GPS纬度 (WGS84)
     * @param callback 回调函数
     */
    public static void getAddressFromGpsAsync(Context context, double gpsLng, double gpsLat, AddressCallback callback) {

        // 启动后台线程来执行同步方法
        new Thread(() -> {
            // getAddressFromGps 是一个同步的、阻塞的方法，它内部已经处理了缓存检查
            String addressResult = getAddressFromGps(context, gpsLng, gpsLat);

            // --- 处理结果并回调 ---
            boolean success;
            if (addressResult == null ||
                addressResult.equals(ERROR_NO_KEY) ||
                addressResult.equals(ERROR_INVALID_KEY) ||
                addressResult.equals(ERROR_FETCH_FAILED))
            {
                success = false;
            } else {
                success = true;
            }

            // 在后台线程中执行回调
            if (callback != null) {
                callback.onAddressUpdated(addressResult, success);
            }

        }).start();
    }

    // endregion

    // region --- GaoDe Web API Calls ---

    /**
     * [修改]
     * 1. 移除了'apiKey == null'的检查（由调用者处理）。
     * 2. 当API返回错误时，会检查是否为'INVALID_USER_KEY'。
     * @return 成功时返回天气字符串, Key无效时返回 ERROR_INVALID_KEY, 其他失败(网络/HTTP/JSON)返回 null。
     */
    public static String fetchWeatherInfo(Context context, String apiKey, String adcode) {
        if (!isNetworkAvailable(context)) {
            Log.e(TAG, "fetchWeatherInfo: Network is not available.");
            return null;
        }
        // [修改] 移除了 apiKey 和 adcode 的检查，假设调用者已处理
        // (apiKey 检查在 requestWeatherUpdateIfStale 中已新增)
        if (adcode == null || adcode.trim().isEmpty()) {
             Log.e(TAG, "fetchWeatherInfo: adcode is null or empty.");
             return null;
        }

        HttpURLConnection connection = null;
        try {
            String urlString = "https://restapi.amap.com/v3/weather/weatherInfo?city=" + adcode + "&key=" + apiKey + "&extensions=base&output=JSON";
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.connect();
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String response = readStream(connection.getInputStream());
                JSONObject jsonResponse = new JSONObject(response);
                if ("1".equals(jsonResponse.optString("status")) && jsonResponse.has("lives")) {
                    JSONArray lives = jsonResponse.getJSONArray("lives");
                    if (lives.length() > 0) {
                        return parseWeatherData(lives.getJSONObject(0)); // 成功
                    }
                } else {
                    // [修改] 检查是否为Key错误
                    String info = jsonResponse.optString("info");
                    Log.e(TAG, "Gaode weather API error: " + info);
                    if ("INVALID_USER_KEY".equals(info)) {
                        return ERROR_INVALID_KEY; // 返回Key错误
                    }
                }
            } else {
                Log.e(TAG, "HTTP error code: " + connection.getResponseCode());
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to fetch weather data", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null; // 代表通用获取失败 (ERROR_FETCH_FAILED)
    }

    /**
     * [修改]
     * 1. 当API返回 status != "1" 时，不再返回 null，而是返回包含错误信息的 JSONObject。
     * 2. 只有在网络/HTTP/JSON解析层面失败时才返回 null。
     * @return 成功或API级错误时返回JSONObject，网络/解析失败时返回 null。
     */
    public static JSONObject getAddressFromCoordinates(Context context, String apiKey, double lng, double lat) {
        if (!isNetworkAvailable(context)) {
            Log.e(TAG, "getAddressFromCoordinates: Network is not available.");
            return null;
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            Log.e(TAG, "getAddressFromCoordinates: apiKey is null or empty.");
            return null; // 理论上不应发生，因为 getAddressFromGps 已检查
        }
        HttpURLConnection connection = null;
        try {
            String urlString = String.format("https://restapi.amap.com/v3/geocode/regeo?key=%s&location=%f,%f&output=json", apiKey, lng, lat);
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.connect();
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String response = readStream(connection.getInputStream());
                JSONObject jsonObject = new JSONObject(response);
                
                // [修改] 无论 status 是 "1" 还是 "0"，都返回 jsonObject
                // 让调用者 (getAddressFromGps) 来解析 status 和 info
                if (!"1".equals(jsonObject.optString("status"))) {
                    Log.e(TAG, "Gaode regeo API error: " + jsonObject.optString("info"));
                }
                return jsonObject; 
                
            } else {
                Log.e(TAG, "HTTP error code: " + connection.getResponseCode());
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to get address from coordinates", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null; // 代表网络/HTTP/JSON解析失败
    }

    /**
     * [重构] [同步请求] 通过GPS(WGS84)坐标获取格式化的地址字符串，并集成缓存管理。
     * <p><b>重要：</b>此方法会执行同步的网络请求（如果缓存未命中），必须在后台线程中调用。
     *
     * @param context 上下文环境
     * @param gpsLng  GPS经度 (WGS84)
     * @param gpsLat  GPS纬度 (WGS84)
     * @return 成功时返回格式化的地址字符串。
     * 失败时返回 {@link #ERROR_NO_KEY}, {@link #ERROR_INVALID_KEY}, 或 {@link #ERROR_FETCH_FAILED}。
     */
    public static String getAddressFromGps(Context context, double gpsLng, double gpsLat) {
        // --- 1. 检查缓存 ---
        if (cachedAddressJson != null) {
            double distance = haversineDistance(gpsLat, gpsLng, cachedAddressLat, cachedAddressLng);
            if (distance < CACHE_DISTANCE_THRESHOLD_METERS) {
                Log.d(TAG, String.format("地址缓存命中，距离: %.2f米", distance));
                return parseAddressFromJson(cachedAddressJson); // 从缓存JSON中解析地址
            }
        }
        // --- 2. 缓存未命中或失效，从网络获取 ---
        Log.d(TAG, "地址缓存未命中或距离过远，从网络获取...");
        String apiKey = getGaodeApiKey(context);
        
        // [修改] 检查Key是否配置
        if (apiKey == null || apiKey.trim().isEmpty()) {
            Log.e(TAG, "getAddressFromGps: " + ERROR_NO_KEY);
            return ERROR_NO_KEY; // 返回 "Key未配置"
        }

        double[] gcj02Coords = wgs84togcj02(gpsLng, gpsLat);
        JSONObject newAddressJson = getAddressFromCoordinates(context, apiKey, gcj02Coords[0], gcj02Coords[1]);

        // --- 3. 处理网络请求结果 ---
        if (newAddressJson != null) { 
            // [修改] 检查 status 字段
            if ("1".equals(newAddressJson.optString("status"))) {
                // --- 3a. API调用成功 ---
                Log.d(TAG, "网络获取地址成功，更新缓存。");
                // 更新内存缓存
                cachedAddressJson = newAddressJson;
                cachedAddressLat = gpsLat;
                cachedAddressLng = gpsLng;

                // 更新持久化缓存
                SharedPreferences prefs = context.getSharedPreferences(ADDRESS_PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit()
                    .putString(PREF_KEY_ADDRESS_JSON, newAddressJson.toString())
                    .putLong(PREF_KEY_ADDRESS_LAT, Double.doubleToRawLongBits(gpsLat))
                    .putLong(PREF_KEY_ADDRESS_LNG, Double.doubleToRawLongBits(gpsLng))
                    .apply();

                return parseAddressFromJson(newAddressJson);
            } else {
                // --- 3b. API调用失败 (例如, Key错误) ---
                String info = newAddressJson.optString("info");
                Log.e(TAG, "Gaode API returned an error: " + info);
                if ("INVALID_USER_KEY".equals(info)) {
                    return ERROR_INVALID_KEY; // 返回 "Key不正确"
                }
                return ERROR_FETCH_FAILED; // 返回 "获取失败" (API层面)
            }
        } else { 
            // --- 3c. API调用失败 (例如, 网络错误) ---
            Log.e(TAG, "网络获取地址失败 (null response)，不更新缓存。");
            return ERROR_FETCH_FAILED; // 返回 "获取失败" (网络层面)
        }
    }

    // endregion

    // region --- Coordinate Transformation (WGS84 to GCJ-02) ---

    private static final double PI = 3.1415926535897932384626;
    private static final double a = 6378245.0;
    private static final double ee = 0.00669342162296594323;

    public static double[] wgs84togcj02(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{round(lng), round(lat)};
        }
        double dlat = transformLat(lng - 105.0, lat - 35.0);
        double dlng = transformLng(lng - 105.0, lat - 35.0);
        double radlat = lat / 180.0 * PI;
        double magic = Math.sin(radlat);
        magic = 1 - ee * magic * magic;
        double sqrtmagic = Math.sqrt(magic);
        dlat = (dlat * 180.0) / ((a * (1 - ee)) / (magic * sqrtmagic) * PI);
        dlng = (dlng * 180.0) / (a / sqrtmagic * Math.cos(radlat) * PI);
        double mglat = lat + dlat;
        double mglng = lng + dlng;
        return new double[]{round(mglng), round(mglat)};
    }

    // endregion

    // region --- Private Helper Methods ---

    /**
     * [新增] 使用Haversine公式计算两个GPS坐标之间的距离。
     * @param lat1 点1的纬度
     * @param lon1 点1的经度
     * @param lat2 点2的纬度
     * @param lon2 点2的经度
     * @return 两点之间的距离（单位：米）
     */
    private static double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final double EARTH_RADIUS_METERS = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    /**
     * [新增] 从逆地理编码的JSON响应中解析出格式化的地址字符串。
     * @param responseJson 高德API返回的完整JSONObject
     * @return 格式化的地址字符串，如果解析失败则返回 null。
     */
    private static String parseAddressFromJson(JSONObject responseJson) {
        if (responseJson == null) {
            return null;
        }
        JSONObject regeocode = responseJson.optJSONObject("regeocode");
        if (regeocode != null) {
            String formattedAddress = regeocode.optString("formatted_address", null);
            if (formattedAddress != null && !formattedAddress.isEmpty()) {
                return formattedAddress;
            }
        }
        Log.e(TAG, "parseAddressFromJson: 未在JSON中找到有效地址。");
        // [修改] 确保在API成功但地址为空时返回一个非null的错误
        return ERROR_FETCH_FAILED; 
    }

    private static boolean isNetworkAvailable(Context context) {
        if (context == null) {
            return false;
        }
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            return false;
        }
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static String parseWeatherData(JSONObject data) {
        String city = data.optString("city", "").replace("市", "");
        String weather = data.optString("weather", "");
        String temperature = data.optString("temperature", "");
        String windDirection = data.optString("winddirection", "");

        StringBuilder sb = new StringBuilder();
        sb.append(city).append(" ").append(weather);
        if (!temperature.isEmpty()) {
            sb.append(" ").append(temperature).append("℃");
        }
        if (!windDirection.isEmpty()) {
            sb.append(" ").append(windDirection).append("风");
        }
        return sb.toString();
    }

    private static String readStream(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    private static double transformLat(double lng, double lat) {
        double ret = -100.0 + 2.0 * lng + 3.0 * lat + 0.2 * lat * lat + 0.1 * lng * lat + 0.2 * Math.sqrt(Math.abs(lng));
        ret += (20.0 * Math.sin(6.0 * lng * PI) + 20.0 * Math.sin(2.0 * lng * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(lat * PI) + 40.0 * Math.sin(lat / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(lat / 12.0 * PI) + 320 * Math.sin(lat * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double lng, double lat) {
        double ret = 300.0 + lng + 2.0 * lat + 0.1 * lng * lng + 0.1 * lng * lat + 0.1 * Math.sqrt(Math.abs(lng));
        ret += (20.0 * Math.sin(6.0 * lng * PI) + 20.0 * Math.sin(2.0 * lng * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(lng * PI) + 40.0 * Math.sin(lng / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(lng / 12.0 * PI) + 300.0 * Math.sin(lng / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    private static boolean outOfChina(double lng, double lat) {
        return !(lng > 73.66 && lng < 135.05 && lat > 3.86 && lat < 53.55);
    }

    private static double round(double value) {
        return new BigDecimal(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    // endregion
}
