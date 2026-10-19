package com.filecamera.apk;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.muxer.FragmentedMp4Muxer;
import androidx.media3.muxer.Muxer;

import com.google.common.collect.ImmutableList;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 录像封装：把视频/音频编码器的输出样本写进 Media3 的分片 MP4。
 *
 * <p>必须等所有轨道都 addTrack 之后才能写第一个样本（muxer 在第一次写入时生成 moov）。
 * 所以这里先缓存各轨样本，等格式都到齐再统一建 muxer、补写缓存，之后就是实时写入。
 *
 * <p>分片时长 1 秒：录制中进程被杀，已经落盘的分片仍然可播，启动时补入库即可。
 */
final class Camera2MuxerSink {

    private static final String TAG = "Camera2MuxerSink";

    static final int TRACK_AUDIO = 0;
    static final int TRACK_VIDEO = 1;

    private static final long FRAGMENT_DURATION_MS = 1000L;

    private final Object lock = new Object();
    private final FileOutputStream output;
    private int expectedTracks;
    private final int orientation;

    private final Format[] formats = new Format[2];
    private final boolean[] ready = new boolean[2];
    private final Muxer.TrackToken[] tokens = new Muxer.TrackToken[2];
    private final List<PendingSample> pending = new ArrayList<>();

    private FragmentedMp4Muxer muxer;
    private boolean opened;
    private boolean closed;
    private Throwable error;

    Camera2MuxerSink(FileOutputStream output, int orientation, int expectedTracks) {
        this.output = output;
        this.orientation = orientation;
        this.expectedTracks = expectedTracks;
    }

    /** 编码器报告输出格式（INFO_OUTPUT_FORMAT_CHANGED 时调用）。 */
    void onFormat(int track, MediaFormat mediaFormat) {
        synchronized (lock) {
            if (closed || ready[track]) return;
            try {
                Format format = buildFormat(track, mediaFormat);
                if (format == null) {
                    throw new IOException("无法构建轨道格式: " + track);
                }
                formats[track] = format;
                ready[track] = true;
                maybeOpenLocked();
            } catch (Throwable t) {
                Log.e(TAG, "准备轨道格式失败", t);
                error = t;
                closed = true;
            }
        }
    }

    /** 写入一个编码样本（内部会复制，调用方可以立即释放编码器 buffer）。 */
    void write(int track, ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (buffer == null || info == null || info.size <= 0) return;
        synchronized (lock) {
            if (closed) return;
            PendingSample sample = PendingSample.copyOf(track, buffer, info);
            if (!opened) {
                pending.add(sample);
                // 正常情况下两个编码器几十毫秒内都会给出格式，缓存不会超过几百帧
                if (pending.size() > 900) {
                    error = new IOException("等待轨道格式超时");
                    closed = true;
                }
                return;
            }
            writeLocked(sample);
        }
    }

    /** 音频轨初始化失败时调用：把期望轨道降为纯视频，避免一直等不到音频格式。 */
    void dropAudioTrack() {
        synchronized (lock) {
            if (closed || expectedTracks != 2 || ready[TRACK_AUDIO]) return;
            expectedTracks = 1;
            try {
                maybeOpenLocked();
            } catch (Throwable t) {
                Log.e(TAG, "降级为纯视频失败", t);
                error = t;
                closed = true;
            }
        }
    }

    /** 关闭 muxer 和文件，返回收尾过程中的错误（没有则 null）。 */
    Throwable close() {
        synchronized (lock) {
            if (closed && muxer == null && output == null) return error;
            closed = true;
            if (!opened && error == null) {
                error = new IOException("编码器没有输出可封装的样本");
            }
            if (muxer != null) {
                try {
                    muxer.close();
                } catch (Throwable t) {
                    if (error == null) error = t;
                }
                muxer = null;
            }
            try {
                output.close();
            } catch (Throwable t) {
                if (error == null) error = t;
            }
            pending.clear();
            return error;
        }
    }

    private void maybeOpenLocked() throws IOException {
        if (opened || closed) return;
        if (!ready[TRACK_VIDEO]) return;
        if (expectedTracks == 2 && !ready[TRACK_AUDIO]) return;

        muxer = new FragmentedMp4Muxer.Builder(output)
                .setFragmentDurationMs(FRAGMENT_DURATION_MS)
                .setSampleCopyEnabled(true)
                .build();
        if (orientation != 0) {
            muxer.addMetadataEntry(new Mp4OrientationData(orientation));
        }
        if (ready[TRACK_AUDIO]) {
            tokens[TRACK_AUDIO] = muxer.addTrack(formats[TRACK_AUDIO]);
        }
        if (ready[TRACK_VIDEO]) {
            tokens[TRACK_VIDEO] = muxer.addTrack(formats[TRACK_VIDEO]);
        }
        opened = true;

        // 缓存样本按时间排序后补写，保证音画起点一致
        pending.sort(Comparator.comparingLong(s -> s.info.presentationTimeUs));
        for (PendingSample sample : pending) {
            writeLocked(sample);
            if (closed) break;
        }
        pending.clear();
    }

    private void writeLocked(PendingSample sample) {
        if (closed || muxer == null || tokens[sample.track] == null) return;
        try {
            muxer.writeSampleData(tokens[sample.track], ByteBuffer.wrap(sample.data), sample.info);
        } catch (Throwable t) {
            Log.e(TAG, "写入样本失败", t);
            error = t;
            closed = true;
        }
    }

    private Format buildFormat(int track, MediaFormat mediaFormat) {
        if (track == TRACK_VIDEO) {
            int width = mediaFormat.containsKey(MediaFormat.KEY_WIDTH)
                    ? mediaFormat.getInteger(MediaFormat.KEY_WIDTH) : 0;
            int height = mediaFormat.containsKey(MediaFormat.KEY_HEIGHT)
                    ? mediaFormat.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            byte[] csd0 = getBytes(mediaFormat.getByteBuffer("csd-0"));
            byte[] csd1 = getBytes(mediaFormat.getByteBuffer("csd-1"));
            byte[][] spsPps = splitSpsPps(csd0, csd1);
            if (spsPps[0] == null || spsPps[1] == null) {
                throw new IllegalArgumentException("视频轨道缺少 SPS/PPS");
            }
            return new Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setWidth(width)
                    .setHeight(height)
                    .setFrameRate(30)
                    .setInitializationData(ImmutableList.of(spsPps[0], spsPps[1]))
                    .build();
        }
        if (track == TRACK_AUDIO) {
            int sampleRate = mediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = mediaFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            byte[] csd0 = getBytes(mediaFormat.getByteBuffer("csd-0"));
            if (csd0 == null || csd0.length == 0) {
                // AAC LC / 44.1kHz / mono 的 AudioSpecificConfig 兜底
                csd0 = new byte[]{0x12, 0x10};
            }
            return new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_AAC)
                    .setSampleRate(sampleRate)
                    .setChannelCount(channels)
                    .setInitializationData(ImmutableList.of(csd0))
                    .build();
        }
        return null;
    }

    // region --- Annex-B / SPS-PPS 工具 ---

    private static byte[] getBytes(ByteBuffer buffer) {
        if (buffer == null) return null;
        ByteBuffer copy = buffer.duplicate();
        byte[] data = new byte[copy.remaining()];
        copy.get(data);
        return data;
    }

    private static byte[][] splitSpsPps(byte[] csd0, byte[] csd1) {
        byte[] sps = null;
        byte[] pps = null;
        for (byte[] nal : splitAnnexB(csd0)) {
            int type = nalType(nal);
            if (type == 7) sps = nal;
            else if (type == 8) pps = nal;
        }
        if (pps == null) {
            for (byte[] nal : splitAnnexB(csd1)) {
                if (nalType(nal) == 8) {
                    pps = nal;
                    break;
                }
            }
        }
        if (sps == null) {
            for (byte[] nal : splitAnnexB(csd1)) {
                if (nalType(nal) == 7) {
                    sps = nal;
                    break;
                }
            }
        }
        return new byte[][]{sps, pps};
    }

    private static int nalType(byte[] nal) {
        if (nal == null) return -1;
        for (int i = 0; i + 3 < nal.length; i++) {
            if (nal[i] == 0 && nal[i + 1] == 0 && nal[i + 2] == 1) {
                int offset = i + 3;
                return offset < nal.length ? (nal[offset] & 0x1f) : -1;
            }
        }
        return -1;
    }

    private static List<byte[]> splitAnnexB(byte[] data) {
        List<byte[]> result = new ArrayList<>();
        if (data == null || data.length < 4) return result;
        int i = 0;
        while (i < data.length - 3) {
            int start = findStartCode(data, i);
            if (start < 0) break;
            int next = findStartCode(data, start + 4);
            int end = next < 0 ? data.length : next;
            if (end > start) result.add(Arrays.copyOfRange(data, start, end));
            i = end;
        }
        return result;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                if (i > 0 && data[i - 1] == 0) return i - 1;
                return i;
            }
        }
        return -1;
    }

    // endregion

    private static final class PendingSample {
        final int track;
        final byte[] data;
        final MediaCodec.BufferInfo info;

        PendingSample(int track, byte[] data, MediaCodec.BufferInfo info) {
            this.track = track;
            this.data = data;
            this.info = info;
        }

        static PendingSample copyOf(int track, ByteBuffer buffer, MediaCodec.BufferInfo info) {
            ByteBuffer copy = buffer.duplicate();
            copy.position(info.offset);
            copy.limit(info.offset + info.size);
            byte[] data = new byte[info.size];
            copy.get(data);
            MediaCodec.BufferInfo copiedInfo = new MediaCodec.BufferInfo();
            copiedInfo.set(0, info.size, info.presentationTimeUs, info.flags);
            return new PendingSample(track, data, copiedInfo);
        }
    }
}
