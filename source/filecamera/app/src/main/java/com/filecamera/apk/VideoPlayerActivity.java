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
    /** 视频画面在 PlayerView 里的实际显示区域（扣掉黑边），水印层紧贴它 */
    private int pictureWidth = 0;
    private int pictureHeight = 0;
    private String appliedAnchor = "";

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

            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
                // 视频尺寸（或旋转）变化后，重算画面矩形并重排水印
                updateOverlayToPicture();
            }
        });
        // 播放器/窗口尺寸变化（含设备旋转、控制器显隐）时也重排
        playerView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateOverlayToPicture());
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
     * 计算视频在 PlayerView 里的实际显示矩形（等比缩放 + 居中，扣掉黑边），
     * 让水印层紧贴画面；随后按"画面尺寸"重排水印。
     * 设备怎么摆放都不影响水印与画面的相对关系——这里只做坐标换算，不读设备方向。
     */
    private void updateOverlayToPicture() {
        int pw = playerView.getWidth();
        int ph = playerView.getHeight();
        if (pw <= 0 || ph <= 0) return;

        int vw = 0;
        int vh = 0;
        if (player != null) {
            androidx.media3.common.VideoSize size = player.getVideoSize();
            vw = size.width;
            vh = size.height;
        }
        if (vw <= 0 || vh <= 0) {
            // 视频尺寸还没出来：先铺满，等 onVideoSizeChanged 再校正
            vw = pw;
            vh = ph;
        }

        float fit = Math.min((float) pw / vw, (float) ph / vh);
        int w = Math.max(1, Math.round(vw * fit));
        int h = Math.max(1, Math.round(vh * fit));
        if (w == pictureWidth && h == pictureHeight) return;
        pictureWidth = w;
        pictureHeight = h;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlay.getLayoutParams();
        lp.width = w;
        lp.height = h;
        lp.leftMargin = (pw - w) / 2;
        lp.topMargin = (ph - h) / 2;
        lp.gravity = Gravity.TOP | Gravity.START;
        overlay.setLayoutParams(lp);

        if (watermark != null) {
            buildWatermarkViews(watermark);
        }
    }

    /**
     * 按水印数据构建视图。**绘制方式与相机端保持一致**：
     * 整块水印只裁剪一次圆角（四角半径来自配置 radius），header/body/foot 之间是直边；
     * 各分区只负责自己的背景色和内边距。
     * 密度基准用"画面宽度 / 360"，所以水印大小/边距都跟着画面走，不跟屏幕走。
     */
    private void buildWatermarkViews(JSONObject wm) {
        overlay.removeAllViews();
        if (pictureWidth <= 0 || pictureHeight <= 0) return;
        float density = pictureWidth / DESIGN_WIDTH;
        float scale = (float) wm.optDouble("scale", 0.75);

        // 与相机一致：整块水印统一裁一次圆角（复用相机那边的 ClippingFrameLayout）
        CameraActivity.ClippingFrameLayout clip = new CameraActivity.ClippingFrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        clip.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        // 四角半径：配置里是 [左上, 右上, 右下, 左下]，同一个值给 X/Y
        JSONArray radiusArr = wm.optJSONArray("radius");
        float rtl = (float) (radiusArr != null ? radiusArr.optDouble(0, 8) : 8) * density;
        float rtr = (float) (radiusArr != null ? radiusArr.optDouble(1, 8) : 8) * density;
        float rbr = (float) (radiusArr != null ? radiusArr.optDouble(2, 8) : 8) * density;
        float rbl = (float) (radiusArr != null ? radiusArr.optDouble(3, 8) : 8) * density;
        clip.setCornerRadiiPx(new float[]{rtl, rtl, rtr, rtr, rbr, rbr, rbl, rbl});

        // 摆放：锚点由数据决定（左上/左下/右上/右下），边距按画面宽度换算
        appliedAnchor = currentAnchorFromData();
        boolean right = appliedAnchor.startsWith("right");
        boolean topAnchor = appliedAnchor.endsWith("top");

        JSONObject pos = wm.optJSONObject("position");
        int margin = Math.round(10 * density);
        int offsetX = margin;
        int offsetY = margin;
        if (pos != null) {
            offsetX = Math.round((float) pos.optDouble(right ? "right" : "left", 10) * density * scale) + margin;
            offsetY = Math.round((float) pos.optDouble(topAnchor ? "top" : "bottom", 10) * density * scale) + margin;
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = (right ? Gravity.END : Gravity.START) | (topAnchor ? Gravity.TOP : Gravity.BOTTOM);
        if (right) {
            lp.rightMargin = offsetX;
        } else {
            lp.leftMargin = offsetX;
        }
        if (topAnchor) {
            lp.topMargin = offsetY;
        } else {
            lp.bottomMargin = offsetY;
        }
        overlay.addView(clip, lp);
    }

    /** 当前生效的锚点：取当前数据段的 pos，回退到顶层 pos，再回退到配置 position。 */
    private String currentAnchorFromData() {
        JSONObject seg = currentSegment();
        if (seg != null) {
            String p = seg.optString("pos", "");
            if (!p.isEmpty()) return p;
        }
        if (watermark != null) {
            String p = watermark.optString("pos", "");
            if (!p.isEmpty()) return p;
            JSONObject pos = watermark.optJSONObject("position");
            if (pos != null) {
                boolean right = pos.has("right");
                boolean top = pos.has("top");
                return (right ? "right" : "left") + "_" + (top ? "top" : "bottom");
            }
        }
        return "left_bottom";
    }

    /** 构建一个水印分区（header/body/foot）：只管背景色与内边距，圆角由外层统一裁剪。 */
    private LinearLayout buildSection(JSONObject section, float density, float scale, boolean isHeader) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // 与相机一致：分区背景是纯色（不做圆角），整块圆角由 ClippingFrameLayout 负责
        box.setBackgroundColor(readColor(section, "background", isHeader ? 0xFF1B4493 : 0xAAF8FAFC));

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
        // 锚点变化（数据里 pos 段切换）→ 按新锚点重排水印
        if (pictureWidth > 0) {
            String anchor = currentAnchorFromData();
            if (!anchor.equals(appliedAnchor)) {
                buildWatermarkViews(watermark);
                return;
            }
        }
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
