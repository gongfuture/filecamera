package com.filecamera.apk;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.OverlaySettings;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.TransformationException;
import androidx.media3.transformer.TransformationResult;
import androidx.media3.transformer.TransformationException;
import androidx.media3.transformer.TransformationResult;
import androidx.media3.transformer.Transformer;

import com.google.common.collect.ImmutableList;

import org.json.JSONObject;

import java.io.File;
import java.util.Collections;

/**
 * 烧录水印：把水印位图直接合成进视频画面，导出一个"带水印的副本"（原视频保留）。
 *
 * <p>用的是 Media3 Transformer：视频重编码 + 位图叠加层，水印层按视频原始分辨率生成，
 * 所以烧出来的画面与播放页看到的位置一致。
 */
public final class VideoWatermarkBurner {

    private static final String TAG = "VideoWatermarkBurner";

    public interface Callback {
        /** 0~100 */
        void onProgress(int percent);

        void onDone(File output);

        void onError(Throwable error);
    }

    private VideoWatermarkBurner() {
    }

    /**
     * @param input  原视频（app_data 里的干净视频）
     * @param output 目标文件（必须可写；一般直接给 app_data/&lt;新id&gt;.mp4）
     * @param wm     水印数据
     */
    /** 烧录句柄：用于中途取消（取消后调用方负责清理半成品文件）。 */
    public static final class Handle {
        private final Transformer transformer;

        Handle(Transformer transformer) {
            this.transformer = transformer;
        }

        public void cancel() {
            try {
                transformer.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    public static Handle burn(Context context, File input, File output, JSONObject wm, Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (input == null || !input.isFile()) {
            main.post(() -> callback.onError(new IllegalStateException("原视频不存在")));
            return null;
        }
        if (wm == null) {
            main.post(() -> callback.onError(new IllegalStateException("该视频没有水印数据")));
            return null;
        }

        int width;
        int height;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(input.getAbsolutePath());
            width = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            height = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rotation = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if ((rotation == 90 || rotation == 270) && width > 0 && height > 0) {
                int tmp = width;
                width = height;
                height = tmp;
            }
        } catch (Exception e) {
            Log.e(TAG, "读取视频尺寸失败", e);
            main.post(() -> callback.onError(e));
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        if (width <= 0 || height <= 0) {
            main.post(() -> callback.onError(new IllegalStateException("无法获取视频分辨率")));
            return null;
        }

        Bitmap overlayBitmap = WatermarkBitmapRenderer.renderFullFrameOverlay(width, height, wm);
        if (overlayBitmap == null) {
            main.post(() -> callback.onError(new IllegalStateException("生成水印层失败")));
            return null;
        }

        OverlaySettings settings = new OverlaySettings.Builder()
                .setScale(1f, 1f)
                .build();
        Effect overlayEffect = new OverlayEffect(ImmutableList.of(
                BitmapOverlay.createStaticBitmapOverlay(overlayBitmap, settings)));

        EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setEffects(new Effects(Collections.emptyList(), ImmutableList.of(overlayEffect)))
                .build();

        Transformer transformer = new Transformer.Builder(context).build();
        ProgressHolder progressHolder = new ProgressHolder();
        Runnable[] poller = new Runnable[1];

        transformer.addListener(new Transformer.Listener() {
            @Override
            public void onTransformationCompleted(MediaItem mediaItem, TransformationResult result) {
                main.removeCallbacks(poller[0]);
                recycleQuietly(overlayBitmap);
                main.post(() -> callback.onProgress(100));
                main.post(() -> callback.onDone(output));
            }

            @Override
            public void onTransformationError(MediaItem mediaItem, TransformationException exception) {
                main.removeCallbacks(poller[0]);
                recycleQuietly(overlayBitmap);
                Log.e(TAG, "烧录失败", exception);
                main.post(() -> callback.onError(exception));
            }
        });

        poller[0] = new Runnable() {
            @Override
            public void run() {
                int state = transformer.getProgress(progressHolder);
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    main.post(() -> callback.onProgress(Math.max(1, progressHolder.progress)));
                }
                if (state == Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY
                        || state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    main.postDelayed(this, 400);
                }
            }
        };

        try {
            transformer.start(item, output.getAbsolutePath());
            main.postDelayed(poller[0], 600);
            return new Handle(transformer);
        } catch (Exception e) {
            Log.e(TAG, "启动烧录失败", e);
            recycleQuietly(overlayBitmap);
            main.post(() -> callback.onError(e));
            return null;
        }
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void recycleQuietly(Bitmap bitmap) {
        try {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        } catch (Exception ignored) {
        }
    }
}
