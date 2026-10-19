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

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 视频播放页：播放库里的视频。新录像的水印已经在录制时烧进画面；
 * 旧 .wmp4 仅作为普通视频播放（不再读取尾部数据）。
 *
 * <p>界面完全自定义（不使用 Media3 默认控制条）：顶部返回 + 文件名，中间播放/暂停，
 * 底部音量行 + 进度行；点画面显隐控制层，播放中自动隐藏。
 */
public class VideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayerActivity";
    public static final String EXTRA_FILE_ID = "com.filecamera.apk.extra.FILE_ID";
    public static final String EXTRA_FILE_NAME = "com.filecamera.apk.extra.FILE_NAME";
    public static final String EXTRA_REAL_FILE_NAME = "com.filecamera.apk.extra.REAL_FILE_NAME";

    /** 控制层自动隐藏时间 */
    private static final long CONTROLS_HIDE_DELAY_MS = 5000;

    private PlayerView playerView;
    /** 盖在视频上的透明点击层：只负责点画面显隐控制条 */
    private FrameLayout videoTouchLayer;
    private ExoPlayer player;

    // 自定义控制层
    private LinearLayout controls;
    private LinearLayout controlsBottom;
    private LinearLayout volumeRow;
    private ImageButton btnPlay;
    private ImageButton btnVolume;
    private ImageButton btnVolumeToggle;
    private File sourceVideoFile;
    private SeekBar volumeBar;
    private SeekBar seekBar;
    private TextView timePos;
    private TextView timeDur;
    private AudioManager audioManager;
    private boolean controlsVisible = true;
    private boolean draggingSeek = false;
    private boolean playing = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideControlsRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.isPlaying()) {
                setControlsVisible(false);
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
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
        videoTouchLayer = findViewById(R.id.watermark_overlay);
        controls = findViewById(R.id.controls);
        controlsBottom = findViewById(R.id.controls_bottom);
        volumeRow = findViewById(R.id.volume_row);
        btnPlay = findViewById(R.id.btn_play);
        btnVolume = findViewById(R.id.btn_volume);
        btnVolumeToggle = findViewById(R.id.btn_volume_toggle);
        volumeBar = findViewById(R.id.volume_bar);
        seekBar = findViewById(R.id.seek_bar);
        timePos = findViewById(R.id.time_pos);
        timeDur = findViewById(R.id.time_dur);

        String realFileName = getIntent().getStringExtra(EXTRA_REAL_FILE_NAME);
        String displayName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        TextView titleView = findViewById(R.id.title_view);
        titleView.setText(displayName == null ? "视频" : displayName);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        File videoFile = new File(new File(getFilesDir(), "app_data"), realFileName == null ? "" : realFileName);
        sourceVideoFile = videoFile;
        if (!videoFile.isFile()) {
            Toast.makeText(this, "视频文件不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 水印已在录制时烧进画面，播放器只负责播放

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
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                Log.e(TAG, "视频播放失败: " + error.getErrorCodeName(), error);
                Toast.makeText(VideoPlayerActivity.this,
                        "视频播放失败: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
                setControlsVisible(true);
            }

            @Override
            public void onPlaybackStateChanged(int state) {                if (state == androidx.media3.common.Player.STATE_ENDED) {
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
        });

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
        videoTouchLayer.setClickable(true);
        videoTouchLayer.setOnClickListener(toggle);
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
        };
        btnVolume.setOnClickListener(volumeToggle);
        btnVolumeToggle.setOnClickListener(volumeToggle);

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
    }

    private void scheduleHideControls() {
        uiHandler.removeCallbacks(hideControlsRunnable);
        if (player != null && player.isPlaying()) {
            uiHandler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS);
        }
    }

    // endregion

    // region --- 水印渲染 ---

    /**
     * 计算视频在 PlayerView 里的实际显示矩形（等比缩放 + 居中，扣掉黑边），
     * 让水印层紧贴画面；控制层可见时底部让出控制层高度，避免进度条压住水印。
     */
}
