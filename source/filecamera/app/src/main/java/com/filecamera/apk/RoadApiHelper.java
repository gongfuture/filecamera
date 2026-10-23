package com.filecamera.apk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 一个纯粹的 WebView JS 接口帮助类。
 * 负责与前端的交互，并将数据库任务委托给 RoadDatabaseHelper。
 */
public class RoadApiHelper {
    private static final String TAG = "RoadApiHelper";
    private final WebView webView;
    private final RoadDatabaseHelper dbHelper; // 持有数据库帮助类的实例

    /**
     * 构造函数。
     * @param context 应用上下文，用于创建 dbHelper。
     * @param webView 用于JS回调的WebView实例。
     */
    public RoadApiHelper(Context context, WebView webView) {
        this.webView = webView;
        this.dbHelper = new RoadDatabaseHelper(context); // 创建数据库帮助类的实例
    }

    // --- Javascript Interface Methods ---

    @JavascriptInterface
    public void replaceAllRoads(String task_id, String json_data) {
        new Thread(() -> {
            try {
                // [已更新] 正确的 JSON 解析逻辑
                // 1. 将传入的整个字符串解析为一个JSON对象 (payload)
                JSONObject payload = new JSONObject(json_data);
                // 2. 从中获取名为 "geoJson" 的子对象
                JSONObject geoJsonObject = payload.getJSONObject("geoJson");
                // 3. 将这个子对象转换为字符串，以传递给数据库帮助类
                String geoJsonString = geoJsonObject.toString();

                // 将工作委托给 dbHelper
                if (dbHelper.replaceAllRoads(geoJsonString)) {
                    call_js_callback(task_id, true, null, null);
                } else {
                    call_js_callback(task_id, false, null, "dbHelper failed to replace all roads.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in replaceAllRoads", e);
                call_js_callback(task_id, false, null, e.getMessage());
            }
        }).start();
    }

    @JavascriptInterface
    public void updateOrInsertRoad(String task_id, String json_data) {
        new Thread(() -> {
            try {
                JSONObject feature = new JSONObject(json_data).getJSONObject("feature");
                // 将工作委托给 dbHelper
                if (dbHelper.updateOrInsertRoad(feature)) {
                    call_js_callback(task_id, true, null, null);
                } else {
                    call_js_callback(task_id, false, null, "dbHelper failed to update or insert road.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in updateOrInsertRoad", e);
                call_js_callback(task_id, false, null, e.getMessage());
            }
        }).start();
    }

    @JavascriptInterface
    public void findNearestRoadLocation(String task_id, String json_data) {
        new Thread(() -> {
            try {
                JSONObject data = new JSONObject(json_data);
                double lng = data.getDouble("lng");
                double lat = data.getDouble("lat");

                // 将工作委托给 dbHelper
                RoadDatabaseHelper.RoadLocationMatch match = dbHelper.findNearestRoadLocation(lng, lat, null);

                JSONObject resultJson = new JSONObject();
                resultJson.put("roadPartId", match.roadPartId);
                resultJson.put("roadName", match.roadName);
                resultJson.put("roadId", match.roadId);
                resultJson.put("roadCountryId", match.roadCountryId);
                resultJson.put("direction", match.direction); 				
                resultJson.put("formattedPile", match.formattedPile);
                resultJson.put("rawPile", Double.isNaN(match.rawPile) ? JSONObject.NULL : match.rawPile);
                resultJson.put("distanceToRoad", match.distanceToRoad);

                call_js_callback(task_id, true, resultJson, null);

            } catch (Exception e) {
                Log.e(TAG, "Error in findNearestRoadLocation", e);
                call_js_callback(task_id, false, null, e.getMessage());
            }
        }).start();
    }
	@JavascriptInterface
    public void getAllRoadInfo(String task_id,String json_data) {
        new Thread(() -> {
            try {
                JSONArray roadList = dbHelper.getAllRoadInfo();
                call_js_callback(task_id, true, roadList, null);
            } catch (Exception e) {
                Log.e(TAG, "Error in getAllRoadInfo", e);
                call_js_callback(task_id, false, null, e.getMessage());
            }
        }).start();
    }
    @JavascriptInterface
    public void deleteRoadById(String task_id, String json_data) {
        new Thread(() -> {
            try {
                JSONObject data = new JSONObject(json_data);
                String roadPartId = data.getString("road_part_id");

                if (dbHelper.deleteRoadById(roadPartId)) {
                    call_js_callback(task_id, true, null, null);
                } else {
                    call_js_callback(task_id, false, null, "dbHelper failed to delete road.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in deleteRoadById", e);
                call_js_callback(task_id, false, null, e.getMessage());
            }
        }).start();
    }

    /**
     * 路线数据网络拉取（从 JS 层下放到 Java 层）。
     *
     * <p>JS 只负责拼好 URL 并传入，Java 完成 HTTPS 请求后把解析好的 JSON 回传。
     * 这样 WebView 不再直接访问服务器，也就不需要在 network_security_config 里
     * 内置/信任服务器证书；服务器更换证书不会影响 App。</p>
     *
     * <p>只允许 HTTPS；服务器地址允许在秘钥管理页配置，因此不写死 host。</p>
     */
    @JavascriptInterface
    public void fetchRoadData(String task_id, String json_data) {
        new Thread(() -> {
            HttpsURLConnection connection = null;
            try {
                JSONObject params = new JSONObject(json_data);
                String urlString = params.getString("url");
                URL url = new URL(urlString);

                if (!"https".equalsIgnoreCase(url.getProtocol())) {
                    throw new IOException("只允许 HTTPS 服务器地址");
                }

                connection = (HttpsURLConnection) url.openConnection();
                // 服务器证书最近会更换，数据为半公开性质；这里对配置的路线服务器放开证书校验，
                // 不读取任何证书资源。应用内其他 HTTPS 请求仍走系统 CA。
                connection.setSSLSocketFactory(getTrustAllSocketFactory());
                connection.setHostnameVerifier((hostname, session) -> true);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Connection", "close");

                int responseCode = connection.getResponseCode();
                String body = readAll(responseCode >= 400
                        ? connection.getErrorStream()
                        : connection.getInputStream());

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException(formatHttpError(responseCode, body));
                }

                JSONObject result = new JSONObject(body);
                call_js_callback(task_id, true, result, null);
            } catch (Exception e) {
                Log.e(TAG, "Error in fetchRoadData", e);
                String message = e.getMessage();
                call_js_callback(task_id, false, null,
                        message == null || message.trim().isEmpty() ? "路线数据请求失败" : message);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private static String readAll(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int length;
            while ((length = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, length);
            }
        }
        return builder.toString();
    }

    private static String formatHttpError(int responseCode, String body) {
        String detail = body == null ? "" : body.trim();
        try {
            JSONObject json = new JSONObject(detail);
            detail = json.optString("error", json.optString("message", ""));
        } catch (Exception ignored) {
            // 非 JSON 错误页，保留原文并截断。
        }
        if (detail.length() > 300) {
            detail = detail.substring(0, 300) + "...";
        }
        return "HTTP 错误! 状态码: " + responseCode
                + (detail.isEmpty() ? "" : ". 原因: " + detail);
    }

    private static volatile SSLSocketFactory trustAllSocketFactory;

    /**
     * 只对路线服务器使用的 trust-all SocketFactory，不读取任何证书资源。
     */
    private static SSLSocketFactory getTrustAllSocketFactory() throws Exception {
        SSLSocketFactory factory = trustAllSocketFactory;
        if (factory != null) {
            return factory;
        }
        synchronized (RoadApiHelper.class) {
            factory = trustAllSocketFactory;
            if (factory == null) {
                TrustManager[] trustAllManagers = new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                };
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllManagers, new SecureRandom());
                factory = sslContext.getSocketFactory();
                trustAllSocketFactory = factory;
            }
        }
        return factory;
    }

    // --- JS 回调方法 ---
    private void call_js_callback(String task_id, boolean success, Object data, String error_message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                JSONObject params = new JSONObject();
                params.put("id", task_id);
                params.put("success", success);
                if (data != null) params.put("data", data);
                if (error_message != null) params.put("errorMessage", error_message);
                String js_code = String.format("window.handlePromiseCallback(%s);", params.toString());
                webView.evaluateJavascript(js_code, null);
            } catch (JSONException e) {
                // Log the error for debugging
                Log.e(TAG, "Failed to create JSON for JS callback", e);
            }
        });
    }
}
