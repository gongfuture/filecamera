package com.filecamera.apk;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 把水印数据（JSON）画到 Bitmap 上。
 *
 * <p>用途：录像边录边烧时，把当前水印画成与编码画面同尺寸的透明位图，
 * 交给 Camera2GlRenderer 合成到每一帧。
 *
 * <p>绘制规则与相机端/播放端保持一致：整块只圆角一次（半径来自配置 radius），
 * header/body/foot 之间是直边；密度基准 = 画面宽度 / 360；锚点来自数据的 pos。
 */
public final class WatermarkBitmapRenderer {

    private static final float DESIGN_WIDTH = 360f;

    private WatermarkBitmapRenderer() {
    }

    /** 合成上下文：第一段字段值 + 基准时间（首帧等于播放位置 0）。 */
    private static final class Ctx {
        JSONObject values;
        long baseTimeMs;
    }

    /**
     * 在给定画面上合成水印，返回新 Bitmap（不修改传入的 frame）。
     */
    public static Bitmap composeOnto(Bitmap frame, JSONObject wm) {
        if (frame == null || wm == null) return frame;
        Bitmap output;
        try {
            output = frame.copy(Bitmap.Config.ARGB_8888, true);
        } catch (Exception e) {
            return frame;
        }
        if (output == null) return frame;
        Canvas canvas = new Canvas(output);
        drawWatermark(canvas, output.getWidth(), output.getHeight(), wm);
        return output;
    }

    /**
     * 生成"整帧透明水印层"：尺寸与视频帧一致，水印画在数据指定的位置。
     * 烧录时把它作为位图叠加层合成到每一帧上。
     */
    public static Bitmap renderFullFrameOverlay(int width, int height, JSONObject wm) {
        if (width <= 0 || height <= 0) return null;
        Bitmap overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(overlay);
        drawWatermark(canvas, width, height, wm);
        return overlay;
    }
    /** 把水印按画面宽高画到已有画布上（画面尺寸用于换算密度与定位）。 */
    public static void drawWatermark(Canvas canvas, int pictureWidth, int pictureHeight, JSONObject wm) {
        if (canvas == null || wm == null || pictureWidth <= 0 || pictureHeight <= 0) return;
        float density = pictureWidth / DESIGN_WIDTH;
        float scale = (float) wm.optDouble("scale", 0.75);

        Ctx ctx = new Ctx();
        ctx.baseTimeMs = wm.optLong("base_time_ms", 0);
        JSONArray segments = wm.optJSONArray("segments");
        if (segments != null && segments.length() > 0) {
            JSONObject first = segments.optJSONObject(0);
            if (first != null) ctx.values = first.optJSONObject("values");
        }

        List<Section> sections = new ArrayList<>();
        Section header = buildHeader(wm.optJSONObject("header"), density, scale);
        if (header != null) sections.add(header);
        Section body = buildContentSection(wm.optJSONObject("body"), density, scale, ctx);
        if (body != null) sections.add(body);
        Section foot = buildContentSection(wm.optJSONObject("foot"), density, scale, ctx);
        if (foot != null) sections.add(foot);
        if (sections.isEmpty()) return;

        // 统一块宽：所有分区同宽，整块只圆角一次
        float blockWidth = 0;
        for (Section s : sections) {
            blockWidth = Math.max(blockWidth, s.measureWidth());
        }
        blockWidth = Math.max(blockWidth, 1);

        JSONArray radiusArr = wm.optJSONArray("radius");
        float rtl = (float) (radiusArr != null ? radiusArr.optDouble(0, 8) : 8) * density;
        float rtr = (float) (radiusArr != null ? radiusArr.optDouble(1, 8) : 8) * density;
        float rbr = (float) (radiusArr != null ? radiusArr.optDouble(2, 8) : 8) * density;
        float rbl = (float) (radiusArr != null ? radiusArr.optDouble(3, 8) : 8) * density;

        float totalHeight = 0;
        for (Section s : sections) {
            totalHeight += s.measuredHeight;
        }

        String anchor = currentAnchor(wm);
        boolean right = anchor.startsWith("right");
        boolean topAnchor = anchor.endsWith("top");
        JSONObject pos = wm.optJSONObject("position");
        float margin = 10 * density;
        float offsetX = margin;
        float offsetY = margin;
        if (pos != null) {
            offsetX = (float) pos.optDouble(right ? "right" : "left", 10) * density * scale + margin;
            offsetY = (float) pos.optDouble(topAnchor ? "top" : "bottom", 10) * density * scale + margin;
        }
        float left = right ? pictureWidth - blockWidth - offsetX : offsetX;
        float top = topAnchor ? offsetY : pictureHeight - totalHeight - offsetY;
        if (left < 0) left = 0;
        if (top < 0) top = 0;

        RectF block = new RectF(left, top, left + blockWidth, top + totalHeight);
        Path clip = new Path();
        clip.addRoundRect(block, new float[]{rtl, rtl, rtr, rtr, rbr, rbr, rbl, rbl}, Path.Direction.CW);

        int save = canvas.save();
        canvas.clipPath(clip);
        float y = top;
        for (Section s : sections) {
            s.draw(canvas, left, y, blockWidth);
            y += s.measuredHeight;
        }
        canvas.restoreToCount(save);
    }

    // region --- 分区模型 ---

    private static final class Row {
        String text;
        float size;
        int color;
        boolean bold;
        boolean center;
    }

    private static final class Section {
        int background;
        float padH;
        float padV;
        final List<Row> rows = new ArrayList<>();
        float measuredHeight;
        float maxTextWidth;

        float measureWidth() {
            return maxTextWidth + padH * 2;
        }

        void measure() {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            maxTextWidth = 0;
            float lineSpacing = 2;
            float total = 0;
            for (Row row : rows) {
                paint.setTextSize(row.size);
                paint.setTypeface(row.bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                maxTextWidth = Math.max(maxTextWidth, paint.measureText(row.text));
                Paint.FontMetrics fm = paint.getFontMetrics();
                total += (fm.descent - fm.ascent) + lineSpacing;
            }
            measuredHeight = total + padV * 2;
        }

        void draw(Canvas canvas, float left, float top, float width) {
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(background);
            canvas.drawRect(left, top, left + width, top + measuredHeight, bg);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            float lineSpacing = 2;
            float y = top + padV;
            for (Row row : rows) {
                paint.setTextSize(row.size);
                paint.setColor(row.color);
                paint.setTypeface(row.bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                Paint.FontMetrics fm = paint.getFontMetrics();
                float baseline = y - fm.ascent;
                if (row.center) {
                    paint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText(row.text, left + width / 2f, baseline, paint);
                    paint.setTextAlign(Paint.Align.LEFT);
                } else {
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(row.text, left + padH, baseline, paint);
                }
                y += (fm.descent - fm.ascent) + lineSpacing;
            }
        }
    }

    private static Section buildHeader(JSONObject header, float density, float scale) {
        if (header == null) return null;
        Section s = new Section();
        s.background = readColor(header, "background", 0xFF1B4493);
        s.padH = 10 * density;
        s.padV = 4 * density;
        JSONObject title = header.optJSONObject("title");
        String text = title != null ? title.optString("value", header.optString("title", "")) : "";
        if (!text.isEmpty()) {
            Row row = new Row();
            row.text = text;
            row.size = (float) (title != null ? title.optDouble("size", 20) : 20) * density * scale;
            row.color = readTextColor(title, Color.WHITE);
            row.bold = true;
            row.center = true;
            s.rows.add(row);
        }
        s.measure();
        return s;
    }

    private static Section buildContentSection(JSONObject section, float density, float scale, Ctx ctx) {
        if (section == null) return null;
        Section s = new Section();
        s.background = readColor(section, "background", 0xAAF8FAFC);
        s.padH = 10 * density;
        s.padV = 4 * density;

        JSONObject content = section.optJSONObject("content");
        if (content != null) {
            List<JSONObject> items = new ArrayList<>();
            java.util.Iterator<String> keys = content.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = content.optJSONObject(key);
                if (item == null) continue;
                try {
                    item.put("_key", key);
                } catch (Exception ignored) {
                }
                items.add(item);
            }
            items.sort((a, b) -> Integer.compare(a.optInt("index", 0), b.optInt("index", 0)));
            for (JSONObject item : items) {
                Row row = buildRow(item, density, scale, section, ctx);
                if (row != null) s.rows.add(row);
            }
        } else {
            JSONArray items = section.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    Row row = buildRow(items.optJSONObject(i), density, scale, section, ctx);
                    if (row != null) s.rows.add(row);
                }
            }
        }
        if (s.rows.isEmpty()) return null;
        s.measure();
        return s;
    }

    private static Row buildRow(JSONObject item, float density, float scale, JSONObject section, Ctx ctx) {
        if (item == null) return null;
        boolean display = !item.has("display") || item.optBoolean("display", true);
        if (!display) return null;
        String key = item.optString("_key", "");
        String name = item.optString("name", "");
        String type = item.optString("type", "string");
        boolean nameDisplay = item.optBoolean("name_display", true);

        String value;
        if ("time".equals(type)) {
            long t = ctx.baseTimeMs > 0 ? ctx.baseTimeMs : System.currentTimeMillis();
            value = new SimpleDateFormat("yyyy-MM-dd EEE HH:mm:ss", Locale.CHINA).format(new Date(t));
        } else if (ctx.values != null && ctx.values.has(key)) {
            value = ctx.values.optString(key, "");
        } else {
            value = "";
        }
        String prefix = nameDisplay && !name.isEmpty() ? name + "：" : "";

        Row row = new Row();
        row.text = prefix + value;
        row.size = (float) item.optDouble("size", 16) * density * scale;
        row.color = readTextColor(item, readTextColor(section, 0xFF2C3E50));
        row.bold = false;
        row.center = false;
        return row;
    }

    private static String currentAnchor(JSONObject wm) {
        String p = wm.optString("pos", "");
        if (!p.isEmpty()) return p;
        JSONObject pos = wm.optJSONObject("position");
        if (pos != null) {
            boolean right = pos.has("right");
            boolean top = pos.has("top");
            return (right ? "right" : "left") + "_" + (top ? "top" : "bottom");
        }
        return "left_bottom";
    }

    private static int readColor(JSONObject obj, String key, int def) {
        if (obj == null) return def;
        JSONArray arr = obj.optJSONArray(key);
        if (arr != null && arr.length() >= 3) {
            int a = arr.length() >= 4 ? arr.optInt(3, 255) : 255;
            return Color.argb(a, arr.optInt(0, 0), arr.optInt(1, 0), arr.optInt(2, 0));
        }
        return def;
    }

    private static int readTextColor(JSONObject obj, int def) {
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
