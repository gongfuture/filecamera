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
