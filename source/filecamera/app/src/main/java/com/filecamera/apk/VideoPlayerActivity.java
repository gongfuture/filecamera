package com.filecamera.apk;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 视频播放页：播放库里的视频，并按 file_watermark 里记录的水印数据在画面上叠加水印。
 *
 * <p>设计要点：
 * <ul>
 *     <li>播放器文件本身不带水印（干净视频，任何播放器都能播）；水印在播放时叠加，类似字幕。</li>
 *     <li>水印数据存在 SQLite（file_watermark 表），同时记录视频内容的 SHA-256 指纹：
 *         播放前重算指纹，和记录不一致就不叠加水印，避免"把别的水印数据嫁接到别的视频上"。</li>
 *     <li>水印内容里的"时间"用「基准时间 + 播放位置」实时计算，所以能跟着视频跳动；
 *         其余字段（坐标/路名/桩号/单位等）按 segments 里记录的 from_ms 分段切换。</li>
 * </ul>
 */
public class VideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayerActivity";
    public static final String EXTRA_FILE_ID = "com.filecamera.apk.extra.FILE_ID";
    public static final String EXTRA_FILE_NAME = "com.filecamera.apk.extra.FILE_NAME";
    public static final String EXTRA_REAL_FILE_NAME = "com.filecamera.apk.extra.REAL_FILE_NAME";

    /** 水印渲染的设计宽度（与相机端一致：360dp 基准） */
    private static final float DESIGN_WIDTH = 360f;

    private PlayerView playerView;
    private FrameLayout overlay;
    private ExoPlayer player;

    private JSONObject watermark;            // 水印数据（file_watermark.data）
    private JSONArray segments = new JSONArray();
    private TextView timeView;               // 每秒刷新的时间行
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private boolean playing = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateDynamicWatermark();
            if (playing) {
                tickHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

        playerView = findViewById(R.id.player_view);
        overlay = findViewById(R.id.watermark_overlay);

        String fileId = getIntent().getStringExtra(EXTRA_FILE_ID);
        String realFileName = getIntent().getStringExtra(EXTRA_REAL_FILE_NAME);
        String displayName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        setTitle(displayName == null ? "视频" : displayName);

        File videoFile = new File(new File(getFilesDir(), "app_data"), realFileName == null ? "" : realFileName);
        if (!videoFile.isFile()) {
            Toast.makeText(this, "视频文件不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 1) 读水印数据并做防篡改校验（指纹不一致就不叠印）
        watermark = loadWatermark(fileId);
        if (watermark != null) {
            String expect = watermark.optString("_content_hash", "");
            String actual = sha256(videoFile);
            if (expect.isEmpty() || !expect.equalsIgnoreCase(actual)) {
                Log.w(TAG, "水印数据与视频指纹不匹配，跳过叠印");
                watermark = null;
                Toast.makeText(this, "该视频的水印信息与视频不匹配，无法显示水印", Toast.LENGTH_LONG).show();
            }
        }
        if (watermark != null) {
            segments = watermark.optJSONArray("segments") == null ? new JSONArray() : watermark.optJSONArray("segments");
            buildWatermarkViews(watermark);
        }

        // 2) 播放
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)));
        player.prepare();
        player.addListener(new androidx.media3.common.Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                playing = isPlaying;
                tickHandler.removeCallbacks(tick);
                if (playing) {
                    tickHandler.post(tick);
                }
            }
        });
        player.play();
    }

    @Override
    protected void onDestroy() {
        tickHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    // region --- 水印数据 ---

    /** 读取 file_watermark；返回的 JSON 里额外带上 _content_hash 便于校验。 */
    private JSONObject loadWatermark(String fileId) {
        if (fileId == null || fileId.isEmpty()) return null;
        try {
            JSONArray row = DatabaseHelper.getInstance(this).db_all(
                    "SELECT data, content_hash FROM file_watermark WHERE file_id = ?",
                    new JSONArray().put(fileId));
            if (row.length() == 0) return null;
            JSONObject r = row.getJSONObject(0);
            String data = r.isNull("data") ? null : r.optString("data", null);
            if (data == null || data.isEmpty()) return null;
            JSONObject json = new JSONObject(data);
            json.put("_content_hash", r.optString("content_hash", ""));
            return json;
        } catch (Exception e) {
            Log.e(TAG, "读取水印数据失败", e);
            return null;
        }
    }

    private static String sha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) > 0) {
                digest.update(buf, 0, read);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "计算视频指纹失败", e);
            return "";
        }
    }

    // endregion

    // region --- 水印渲染 ---

    /**
     * 按水印数据构建视图。结构与相机端一致：
     * header（蓝底 + 图标 + 标题）/ body（半透明白底 + "名称：值" 行）/ foot（蓝底 + 行）。
     * 尺寸以「播放区宽度 / 360」为密度基准，和相机端 360dp 设计宽度对齐。
     */
    private void buildWatermarkViews(JSONObject wm) {
        overlay.removeAllViews();
        int baseWidth = getResources().getDisplayMetrics().widthPixels;
        float density = baseWidth / DESIGN_WIDTH;
        float scale = (float) wm.optDouble("scale", 0.75);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        JSONObject header = wm.optJSONObject("header");
        if (header != null) {
            root.addView(buildSection(header, density, scale, true));
        }
        JSONObject body = wm.optJSONObject("body");
        if (body != null) {
            root.addView(buildSection(body, density, scale, false));
        }
        JSONObject foot = wm.optJSONObject("foot");
        if (foot != null) {
            root.addView(buildSection(foot, density, scale, false));
        }

        // 位置：以 360dp 设计宽度为基准，换算成像素边距
        JSONObject pos = wm.optJSONObject("position");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = Math.round(10 * density);
        lp.gravity = Gravity.START | Gravity.BOTTOM;
        lp.leftMargin = margin;
        lp.bottomMargin = margin;
        if (pos != null) {
            if (pos.has("left")) {
                lp.gravity = Gravity.START | lp.gravity;
                lp.leftMargin = Math.round((float) pos.optDouble("left", 10) * density * scale) + margin;
            } else if (pos.has("right")) {
                lp.gravity = Gravity.END | lp.gravity;
                lp.rightMargin = Math.round((float) pos.optDouble("right", 10) * density * scale) + margin;
            }
            if (pos.has("top")) {
                lp.gravity = (lp.gravity & Gravity.END) | (lp.gravity & Gravity.START) | Gravity.TOP;
                lp.topMargin = Math.round((float) pos.optDouble("top", 10) * density * scale) + margin;
                lp.bottomMargin = 0;
            } else if (pos.has("bottom")) {
                lp.gravity = (lp.gravity & (Gravity.END | Gravity.START)) | Gravity.BOTTOM;
                lp.bottomMargin = Math.round((float) pos.optDouble("bottom", 10) * density * scale) + margin;
                lp.topMargin = 0;
            }
        }
        overlay.addView(root, lp);
    }

    /** 构建一个水印分区（header/body/foot）。 */
    private LinearLayout buildSection(JSONObject section, float density, float scale, boolean isHeader) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(readColor(section, "background", isHeader ? 0xFF1B4493 : 0xAAF8FAFC));
        float radius = Math.round(8 * density);
        bg.setCornerRadius(radius);
        box.setBackground(bg);

        int padH = Math.round(10 * density);
        int padV = Math.round(4 * density);
        box.setPadding(padH, padV, padH, padV);

        if (isHeader) {
            JSONObject title = section.optJSONObject("title");
            String text = title != null ? title.optString("value", section.optString("title", "")) : "";
            if (!text.isEmpty()) {
                TextView tv = new TextView(this);
                tv.setText(text);
                tv.setTextColor(readTextColor(title, 0xFFFFFFFF));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.round((title != null ? title.optDouble("size", 20) : 20) * density * scale));
                tv.setGravity(Gravity.CENTER);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                box.addView(tv);
            }
            return box;
        }

        JSONObject content = section.optJSONObject("content");
        if (content == null) {
            // foot 可能用 items 数组简写
            JSONArray items = section.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    box.addView(buildRow(item, density, scale, section));
                }
            }
            return box;
        }

        // 按 index 排序后逐行渲染
        java.util.List<JSONObject> list = new java.util.ArrayList<>();
        java.util.Iterator<String> keys = content.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject item = content.optJSONObject(k);
            if (item != null) {
                try {
                    item.put("_key", k);
                } catch (JSONException ignored) {
                }
                list.add(item);
            }
        }
        list.sort((a, b) -> Integer.compare(a.optInt("index", 0), b.optInt("index", 0)));
        for (JSONObject item : list) {
            boolean display = !item.has("display") || item.optBoolean("display", true);
            if (!display) continue;
            box.addView(buildRow(item, density, scale, section));
        }
        return box;
    }

    /** 一行："名称：值"；时间类型的那一行会把 TextView 记下来供每秒刷新。 */
    private TextView buildRow(JSONObject item, float density, float scale, JSONObject section) {
        String key = item.optString("_key", "");
        String name = item.optString("name", "");
        String type = item.optString("type", "string");
        boolean nameDisplay = item.optBoolean("name_display", true);

        TextView tv = new TextView(this);
        tv.setTextColor(readTextColor(item, readTextColor(section, 0xFF2C3E50)));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                Math.round(item.optDouble("size", 16) * density * scale));
        tv.setSingleLine(true);
        tv.setTag(key);
        if ("time".equals(type)) {
            timeView = tv;
            timeView.setTag("time");
        }
        String prefix = nameDisplay && !name.isEmpty() ? name + "：" : "";
        tv.setText(prefix + currentValue(type, key));
        return tv;
    }

    /** 取当前播放位置对应的字段值。 */
    private String currentValue(String type, String key) {
        if ("time".equals(type)) {
            return formatTime(currentPlayPositionMs());
        }
        JSONObject seg = currentSegment();
        if (seg != null) {
            JSONObject values = seg.optJSONObject("values");
            if (values != null && values.has(key)) {
                return values.optString(key, "");
            }
        }
        // 回退：水印数据里直接给的值
        return "";
    }

    private JSONObject currentSegment() {
        long pos = currentPlayPositionMs();
        JSONObject result = null;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject seg = segments.optJSONObject(i);
            if (seg == null) continue;
            if (seg.optLong("from_ms", 0) <= pos) {
                result = seg;
            } else {
                break;
            }
        }
        return result;
    }

    private long currentPlayPositionMs() {
        if (player == null) return 0;
        try {
            return Math.max(0, player.getCurrentPosition());
        } catch (Exception e) {
            return 0;
        }
    }

    private String formatTime(long positionMs) {
        long base = watermark == null ? 0 : watermark.optLong("base_time_ms", 0);
        long t = base > 0 ? base + positionMs : System.currentTimeMillis();
        return new SimpleDateFormat("yyyy-MM-dd EEE HH:mm:ss", Locale.CHINA).format(new Date(t));
    }

    /** 每秒刷新：时间行 + 分段字段（坐标/路名等变化时自动切换）。 */
    private void updateDynamicWatermark() {
        if (watermark == null) return;
        if (timeView != null) {
            JSONObject seg = currentSegment();
            String prefix = "";
            // 从时间行的文本里保留"名称："前缀
            String old = timeView.getText() == null ? "" : timeView.getText().toString();
            int idx = old.indexOf('：');
            if (idx > 0) {
                prefix = old.substring(0, idx + 1);
            }
            timeView.setText(prefix + formatTime(currentPlayPositionMs()));
        }
        // 其它字段：分段切换时重绘（简单起见，值变化才重设文本）
        refreshRowsText();
    }

    private void refreshRowsText() {
        if (overlay.getChildCount() == 0) return;
        View root = overlay.getChildAt(0);
        if (!(root instanceof LinearLayout)) return;
        JSONObject seg = currentSegment();
        if (seg == null) return;
        JSONObject values = seg.optJSONObject("values");
        if (values == null) return;
        LinearLayout ll = (LinearLayout) root;
        for (int i = 0; i < ll.getChildCount(); i++) {
            View section = ll.getChildAt(i);
            if (!(section instanceof LinearLayout)) continue;
            LinearLayout sl = (LinearLayout) section;
            for (int j = 0; j < sl.getChildCount(); j++) {
                View row = sl.getChildAt(j);
                if (!(row instanceof TextView)) continue;
                TextView tv = (TextView) row;
                Object tag = tv.getTag();
                if (tag == null || "time".equals(tag)) continue;
                String key = tag.toString();
                if (!values.has(key)) continue;
                String old = tv.getText() == null ? "" : tv.getText().toString();
                int idx = old.indexOf('：');
                String prefix = idx > 0 ? old.substring(0, idx + 1) : "";
                tv.setText(prefix + values.optString(key, ""));
            }
        }
    }

    private int readColor(JSONObject obj, String key, int def) {
        if (obj == null) return def;
        JSONArray arr = obj.optJSONArray(key);
        if (arr != null && arr.length() >= 3) {
            int a = arr.length() >= 4 ? arr.optInt(3, 255) : 255;
            return Color.argb(a, arr.optInt(0, 0), arr.optInt(1, 0), arr.optInt(2, 0));
        }
        return def;
    }

    private int readTextColor(JSONObject obj, int def) {
        if (obj == null) return def;
        String s = obj.optString("color", "");
        if (s != null && s.startsWith("#")) {
            try {
                return Color.parseColor(s);
            } catch (Exception ignored) {
            }
        }
        return def;
    }

    // endregion
}
