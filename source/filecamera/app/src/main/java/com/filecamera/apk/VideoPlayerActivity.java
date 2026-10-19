package com.filecamera.apk;

import android.content.Context;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.app.AlertDialog;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
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
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 视频播放页：播放库里的干净视频，并按 file_watermark 里记录的水印数据在画面上叠加水印。
 *
 * <p>界面完全自定义（不使用 Media3 默认控制条）：顶部返回 + 文件名，中间播放/暂停，
 * 底部音量行 + 进度行；点画面显隐控制层，播放中自动隐藏。
 *
 * <p>水印层紧贴视频画面矩形：按画面坐标定位，与设备横竖屏无关；水印的锚点/位置全部来自数据。
 */
public class VideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayerActivity";
    public static final String EXTRA_FILE_ID = "com.filecamera.apk.extra.FILE_ID";
    public static final String EXTRA_FILE_NAME = "com.filecamera.apk.extra.FILE_NAME";
    public static final String EXTRA_REAL_FILE_NAME = "com.filecamera.apk.extra.REAL_FILE_NAME";

    /** 水印渲染的设计宽度（与相机端一致：360dp 基准） */
    private static final float DESIGN_WIDTH = 360f;
    /** 控制层自动隐藏时间 */
    private static final long CONTROLS_HIDE_DELAY_MS = 5000;

    private PlayerView playerView;
    private FrameLayout overlay;
    private ExoPlayer player;

    // 自定义控制层
    private LinearLayout controls;
    private LinearLayout controlsBottom;
    private LinearLayout volumeRow;
    private ImageButton btnPlay;
    private ImageButton btnVolume;
    private ImageButton btnVolumeToggle;    private TextView btnBurn;
    private LinearLayout burnMask;
    private ProgressBar burnBar;
    private TextView burnText;
    private TextView burnStop;
    private File sourceVideoFile;
    private String sourceFileId;
    private String sourceDisplayName;
    private boolean burning = false;
    private VideoWatermarkBurner.Handle burnHandle;
    private SeekBar volumeBar;
    private SeekBar seekBar;
    private TextView timePos;
    private TextView timeDur;
    private AudioManager audioManager;
    private boolean controlsVisible = true;
    private boolean draggingSeek = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideControlsRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.isPlaying()) {
                setControlsVisible(false);
            }
        }
    };

    // 水印
    private JSONObject watermark;
    private JSONArray segments = new JSONArray();
    private TextView timeView;
    private boolean playing = false;
    private int pictureWidth = 0;
    private int pictureHeight = 0;
    private String appliedAnchor = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateDynamicWatermark();
            updateProgressUi();
            if (playing) {
                uiHandler.postDelayed(this, 500);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = findViewById(R.id.player_view);
        overlay = findViewById(R.id.watermark_overlay);
        controls = findViewById(R.id.controls);
        controlsBottom = findViewById(R.id.controls_bottom);
        volumeRow = findViewById(R.id.volume_row);
        btnPlay = findViewById(R.id.btn_play);
        btnVolume = findViewById(R.id.btn_volume);
        btnVolumeToggle = findViewById(R.id.btn_volume_toggle);        btnBurn = findViewById(R.id.btn_burn);
        burnMask = findViewById(R.id.burn_mask);
        burnBar = findViewById(R.id.burn_bar);
        burnText = findViewById(R.id.burn_text);
        burnStop = findViewById(R.id.burn_stop);
        volumeBar = findViewById(R.id.volume_bar);
        seekBar = findViewById(R.id.seek_bar);
        timePos = findViewById(R.id.time_pos);
        timeDur = findViewById(R.id.time_dur);

        String fileId = getIntent().getStringExtra(EXTRA_FILE_ID);
        String realFileName = getIntent().getStringExtra(EXTRA_REAL_FILE_NAME);
        String displayName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        TextView titleView = findViewById(R.id.title_view);
        titleView.setText(displayName == null ? "视频" : displayName);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        File videoFile = new File(new File(getFilesDir(), "app_data"), realFileName == null ? "" : realFileName);
        sourceVideoFile = videoFile;
        sourceFileId = fileId;
        sourceDisplayName = displayName;
        if (!videoFile.isFile()) {
            Toast.makeText(this, "视频文件不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 1) 读水印数据并做防篡改校验（指纹不一致就不叠印）
        watermark = loadWatermark(fileId, videoFile);
        if (watermark != null) {
            String expect = watermark.optString("_content_hash", "");
            String actual = WatermarkFileCodec.isWatermarkVideo(videoFile)
                    ? WatermarkFileCodec.contentHash(videoFile)
                    : sha256(videoFile);
            if (expect.isEmpty() || !expect.equalsIgnoreCase(actual)) {
                Log.w(TAG, "水印数据与视频指纹不匹配，跳过叠印");
                watermark = null;
                Toast.makeText(this, "该视频的水印信息与视频不匹配，无法显示水印", Toast.LENGTH_LONG).show();
            }
        }
        if (watermark != null) {
            segments = watermark.optJSONArray("segments") == null ? new JSONArray() : watermark.optJSONArray("segments");
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
                updatePlayPauseIcon();
                if (!isPlaying) {
                    // 暂停/播放结束：控制层与播放按钮必须常驻（不再自动隐藏）
                    setControlsVisible(true);
                }
                uiHandler.removeCallbacks(tick);
                if (playing) {
                    uiHandler.post(tick);
                    scheduleHideControls();
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    // 容器的 duration 可能比真实可播内容略长（录制结束时写入的时长偏大），
                    // 播放结束就把进度条顶到最右、时间显示为总时长，避免"没到顶就停了"的观感
                    draggingSeek = false;
                    long dur = Math.max(0, player == null ? 0 : player.getDuration());
                    if (seekBar != null) {
                        seekBar.setProgress((int) Math.min(Integer.MAX_VALUE, dur));
                    }
                    if (timePos != null) timePos.setText(formatDuration(dur));
                    updatePlayPauseIcon();
                }
            }

            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
                postOverlayUpdate();
            }
        });
        playerView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateOverlayToPicture());

        bindControls();
        player.play();
        setControlsVisible(true);
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    // region --- 自定义控制层 ---

    private void bindControls() {
        btnPlay.setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying()) {
                player.pause();
            } else {
                // 播放结束后再点播放：必须先回到开头，否则 play() 是空操作
                if (player.getPlaybackState() == androidx.media3.common.Player.STATE_ENDED) {
                    player.seekTo(0);
                }
                player.play();
            }
            updatePlayPauseIcon();
            scheduleHideControls();
        });

        // 点画面：显隐控制层（水印层/播放器都挂上，保证黑边区域点也能响应）
        // 点画面：播放中则暂停（暂停键随之出现），暂停中则继续播放；同时保证控制条可见
        View.OnClickListener toggle = v -> {
            if (player == null) return;
            if (player.isPlaying()) {
                player.pause();
            } else {
                if (player.getPlaybackState() == androidx.media3.common.Player.STATE_ENDED) {
                    player.seekTo(0);
                }
                player.play();
            }
            setControlsVisible(true);
        };
        playerView.setOnClickListener(toggle);
        overlay.setClickable(true);
        overlay.setOnClickListener(toggle);
        // PlayerView 会吞掉触摸，画面黑边区域靠这一层接收点击
        View tapCatcher = findViewById(R.id.tap_catcher);
        if (tapCatcher != null) {
            tapCatcher.setClickable(true);
            tapCatcher.setOnClickListener(toggle);
        }

        // 点控制层空白处：收起（音量行展开时先收起音量行）
        controls.setOnClickListener(v -> {
            if (volumeRow.getVisibility() == View.VISIBLE) {
                volumeRow.setVisibility(View.GONE);
                postOverlayUpdate();
                return;
            }
            setControlsVisible(false);
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    player.seekTo(progress);
                    timePos.setText(formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                draggingSeek = true;
                uiHandler.removeCallbacks(hideControlsRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                draggingSeek = false;
                scheduleHideControls();
            }
        });

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            volumeBar.setMax(max);
            volumeBar.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        }
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || audioManager == null) return;
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                updateVolumeIcon();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                uiHandler.removeCallbacks(hideControlsRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                scheduleHideControls();
            }
        });

        View.OnClickListener volumeToggle = v -> {
            boolean show = volumeRow.getVisibility() != View.VISIBLE;
            volumeRow.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                updateVolumeIcon();
                if (audioManager != null) {
                    volumeBar.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
                }
            }
            scheduleHideControls();
            postOverlayUpdate();
        };
        btnVolume.setOnClickListener(volumeToggle);
        btnVolumeToggle.setOnClickListener(volumeToggle);

        if (btnBurn != null) {
            btnBurn.setOnClickListener(v -> {
                scheduleHideControls();
                startBurnFlow();
            });
        }
        updatePlayPauseIcon();
        updateVolumeIcon();
    }

    private void updatePlayPauseIcon() {
        if (btnPlay == null) return;
        boolean isPlaying = player != null && player.isPlaying();
        btnPlay.setImageResource(isPlaying ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        btnPlay.setVisibility(isPlaying ? View.GONE : View.VISIBLE);
        // 播放键与控制层解耦：播放中隐藏，暂停/结束时显示
    }

    private void updateVolumeIcon() {
        int progress = volumeBar == null ? 1 : volumeBar.getProgress();
        int res = progress == 0 ? R.drawable.ic_player_volume_off : R.drawable.ic_player_volume;
        if (btnVolume != null) btnVolume.setImageResource(res);
        if (btnVolumeToggle != null) btnVolumeToggle.setImageResource(res);
    }

    /** 进度条与时间文案：拖动进度时不覆盖用户的位置。 */
    private void updateProgressUi() {
        if (player == null) return;
        long duration = Math.max(0, player.getDuration());
        long position = Math.max(0, player.getCurrentPosition());
        if (seekBar != null) {
            seekBar.setMax((int) Math.min(Integer.MAX_VALUE, duration));
            if (!draggingSeek) {
                seekBar.setProgress((int) Math.min(Integer.MAX_VALUE, position));
                if (timePos != null) timePos.setText(formatDuration(position));
            }
        }
        if (timeDur != null) timeDur.setText(formatDuration(duration));
        if (btnBurn != null) {
            btnBurn.setOnClickListener(v -> {
                scheduleHideControls();
                startBurnFlow();
            });
        }
        updatePlayPauseIcon();
        updateVolumeIcon();
    }

    private static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.CHINA, "%02d:%02d", minutes, seconds);
    }

    /** 显示/隐藏控制层；播放中显示后自动隐藏。 */
    void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        if (controls != null) {
            controls.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        updatePlayPauseIcon();
        uiHandler.removeCallbacks(hideControlsRunnable);
        if (!visible && volumeRow != null) {
            volumeRow.setVisibility(View.GONE);
        } else if (player != null && player.isPlaying()) {
            scheduleHideControls();
        }
        // 控制层显隐会影响水印可用的摆放高度
        postOverlayUpdate();
    }

   /** 控件尺寸要等本次布局完成才准确，post 一下再按新的控制区高度重排水印。 */
    private void postOverlayUpdate() {
        if (playerView == null) return;
        playerView.post(this::updateOverlayToPicture);
    }
    private void scheduleHideControls() {
        uiHandler.removeCallbacks(hideControlsRunnable);
        if (player != null && player.isPlaying()) {
            uiHandler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS);
        }
    }

    // endregion

    // region --- 烧录水印（导出带印副本） ---

    /** 点烧录按钮：先弹确认框，确认后才开始。 */
    private void startBurnFlow() {
        if (burning) return;
        if (watermark == null) {
            Toast.makeText(this, "该视频没有水印数据，无法烧录", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("烧录水印")
                .setMessage("将把水印直接合成进画面，生成一个带水印的副本（原视频保留）。是否开始烧录？")
                .setPositiveButton("开始烧录", (dialog, which) -> doBurn())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 真正开始烧录：新记录先占位（pending），烧到它的最终路径，成功后提交入库。 */
    private void doBurn() {
        if (burning || sourceVideoFile == null || watermark == null) return;
        String dirId = queryDirId(sourceFileId);
        String baseName = sourceDisplayName == null || sourceDisplayName.isEmpty() ? "video" : sourceDisplayName;
        int dot = baseName.lastIndexOf('.');
        String outName = (dot > 0 ? baseName.substring(0, dot) : baseName) + "_带水印.mp4";

        final FileImporter importer = new FileImporter(this);
        // 先烧到临时文件；只有烧录成功后才走入库流程把它搬进 app_data
        final File tempFile = importer.newBurnTempFile("burn.mp4");
        final String targetDirId = dirId;
        final String targetName = outName;

        burning = true;
        if (burnStop != null) {
            burnStop.setOnClickListener(v -> {
                if (burnHandle != null) {
                    burnHandle.cancel();
                    Toast.makeText(this, "已停止烧录，正在清理…", Toast.LENGTH_SHORT).show();
                }
            });
        }
        burnMask.setVisibility(View.VISIBLE);
        burnBar.setProgress(0);
        burnText.setText("正在烧录水印… 0%");

        burnHandle = VideoWatermarkBurner.burn(this, sourceVideoFile, tempFile, watermark,
                new VideoWatermarkBurner.Callback() {
                    @Override
                    public void onProgress(int percent) {
                        burnBar.setProgress(percent);
                        burnText.setText("正在烧录水印… " + percent + "%");
                    }

                    @Override
                    public void onDone(File output) {
                        try {
                            importer.importBurnedVideo(tempFile, targetDirId, targetName);   // 烧录成功才入库（从临时文件搬走）
                            burning = false;
                            burnHandle = null;
                            burnMask.setVisibility(View.GONE);
                            Toast.makeText(VideoPlayerActivity.this, "烧录完成，已保存到当前文件夹", Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Log.e(TAG, "烧录入库失败", e);
                            if (tempFile != null && tempFile.isFile()) {
                                //noinspection ResultOfMethodCallIgnored
                                tempFile.delete();
                            }
                            burning = false;
                            burnMask.setVisibility(View.GONE);
                            Toast.makeText(VideoPlayerActivity.this, "烧录完成但保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        // 中途停止/失败：直接删掉临时文件，绝不留半成品
                        if (tempFile != null && tempFile.isFile()) {
                            //noinspection ResultOfMethodCallIgnored
                            tempFile.delete();
                        }
                        burning = false;
                        burnHandle = null;
                        burnMask.setVisibility(View.GONE);
                        String msg = error == null ? "未知错误" : error.getMessage();
                        Toast.makeText(VideoPlayerActivity.this, "烧录失败：" + msg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    /** 取这条视频所在的文件夹，烧录结果放回同一个目录。 */
    private String queryDirId(String fileId) {
        try {
            JSONArray rows = DatabaseHelper.getInstance(this).db_all(
                    "SELECT parent_dir_id FROM file WHERE id = ?",
                    new JSONArray().put(fileId == null ? "" : fileId));
            if (rows.length() > 0) return rows.getJSONObject(0).optString("parent_dir_id", "root");
        } catch (Exception ignored) {
        }
        return "root";
    }

    @Override
    public void onBackPressed() {
        if (burning) {
            Toast.makeText(this, "正在烧录，请稍候…", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    // endregion

    // region --- 水印数据 ---

    /** 读取 file_watermark；返回的 JSON 里额外带上 _content_hash 便于校验。 */
    private JSONObject loadWatermark(String fileId, File videoFile) {
        // .wmp4：水印数据只存在文件尾部（数据库不保存），直接读文件
        if (WatermarkFileCodec.isWatermarkVideo(videoFile)) {
            try {
                String json = WatermarkFileCodec.readTrailer(videoFile);
                if (json != null && !json.isEmpty()) {
                    JSONObject wm = new JSONObject(json);
                    wm.put("_content_hash", wm.optString("content_hash", ""));
                    return wm;
                }
            } catch (Exception e) {
                Log.e(TAG, "读取 .wmp4 水印数据失败", e);
            }
            return null;
        }
        // 非 .wmp4（例如烧录出的普通 mp4）：文件里没有水印数据，不叠印
        return null;
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
     * 让水印层紧贴画面；控制层可见时底部让出控制层高度，避免进度条压住水印。
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
        int fullH = Math.max(1, Math.round(vh * fit));

        // 水印层只按视频画面定位：控制层是独立图层，显/隐都不会改变水印的位置和尺寸
        int h = fullH;
        if (w == pictureWidth && h == pictureHeight) return;
        pictureWidth = w;
        pictureHeight = h;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlay.getLayoutParams();
        lp.width = w;
        lp.height = h;
        lp.leftMargin = (pw - w) / 2;
        lp.topMargin = (ph - fullH) / 2;
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

        JSONArray radiusArr = wm.optJSONArray("radius");
        float rtl = (float) (radiusArr != null ? radiusArr.optDouble(0, 8) : 8) * density;
        float rtr = (float) (radiusArr != null ? radiusArr.optDouble(1, 8) : 8) * density;
        float rbr = (float) (radiusArr != null ? radiusArr.optDouble(2, 8) : 8) * density;
        float rbl = (float) (radiusArr != null ? radiusArr.optDouble(3, 8) : 8) * density;
        clip.setCornerRadiiPx(new float[]{rtl, rtl, rtr, rtr, rbr, rbr, rbl, rbl});

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

    /** 构建一个水印分区（header/body/foot）：只管背景色与内边距，圆角由外层统一裁剪。 */
    private LinearLayout buildSection(JSONObject section, float density, float scale, boolean isHeader) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
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
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        Math.round((title != null ? title.optDouble("size", 20) : 20) * density * scale));
                tv.setGravity(Gravity.CENTER);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                box.addView(tv);
            }
            return box;
        }

        JSONObject content = section.optJSONObject("content");
        if (content == null) {
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

    /** 一行："名称：值"；时间类型的那一行会把 TextView 记下来供实时刷新。 */
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
            tv.setTag("time");
        }
        String prefix = nameDisplay && !name.isEmpty() ? name + "：" : "";
        tv.setText(prefix + currentValue(type, key));
        return tv;
    }

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

    /** 每 0.5 秒刷新：时间行 + 锚点变化重排 + 分段字段。 */
    private void updateDynamicWatermark() {
        if (watermark == null) return;
        if (pictureWidth > 0) {
            String anchor = currentAnchorFromData();
            if (!anchor.equals(appliedAnchor)) {
                buildWatermarkViews(watermark);
                return;
            }
        }
        if (timeView != null) {
            String old = timeView.getText() == null ? "" : timeView.getText().toString();
            int idx = old.indexOf('：');
            String prefix = idx > 0 ? old.substring(0, idx + 1) : "";
            timeView.setText(prefix + formatTime(currentPlayPositionMs()));
        }
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
