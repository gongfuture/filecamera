package com.filecamera.apk;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * 旧版 .wmp4 文件的“水印数据区”只读兼容。
 *
 * <p>新录像已经是边录边烧、直接输出标准 .mp4，不再写尾部水印数据；
 * 这里保留读取能力，只是为了让历史 .wmp4 记录仍然能叠印播放。
 */
public final class WatermarkFileCodec {

    private static final String TAG = "WatermarkFileCodec";
    private static final byte[] MAGIC = new byte[]{'W', 'M', 'P', '4'};
    private static final int TAIL_SIZE = 8; // 4 字节长度 + 4 字节魔术字

    private WatermarkFileCodec() {
    }

    public static boolean isWatermarkVideo(File file) {
        return file != null && file.isFile()
                && file.getName().toLowerCase().endsWith(".wmp4");
    }

    /** 读取尾部水印 JSON；没有/损坏返回 null。 */
    public static String readTrailer(File file) {
        if (file == null || !file.isFile()) return null;
        long size = file.length();
        if (size <= TAIL_SIZE) return null;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            // 从文件尾部往前搜魔术字：不假设它紧贴 EOF（录制/系统可能在校验后追加尾包）
            int window = (int) Math.min(8 * 1024, size);
            long windowStart = size - window;
            byte[] buf = new byte[window];
            raf.seek(windowStart);
            raf.readFully(buf);
            int magicPos = -1;
            for (int i = buf.length - MAGIC.length; i >= 0; i--) {
                boolean hit = true;
                for (int k = 0; k < MAGIC.length; k++) {
                    if (buf[i + k] != MAGIC[k]) {
                        hit = false;
                        break;
                    }
                }
                if (hit) {
                    magicPos = i;
                    break;
                }
            }
            if (magicPos < 4) return null;
            int length = bytesToInt(buf, magicPos - 4);
            if (length <= 0) return null;
            long payloadStart = windowStart + magicPos - 4 - length;
            if (payloadStart < 0) return null;
            raf.seek(payloadStart);
            byte[] payload = new byte[length];
            raf.readFully(payload);
            return new String(payload, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "读取水印数据失败", e);
            return null;
        }
    }

    /** 本文件"水印区之前"的数据长度（用于算/校验内容哈希）。 */
    public static long contentLength(File file) {
        long size = file.length();
        String json = readTrailer(file);
        if (json == null) return size;
        byte[] payload;
        try {
            payload = json.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return size;
        }
        return Math.max(0, size - TAIL_SIZE - payload.length);
    }

    /** 计算文件前 contentLength 字节的 SHA-256（不含水印区）。 */
    public static String contentHash(File file) {
        long limit = contentLength(file);
        if (limit <= 0) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            long remaining = limit;
            while (remaining > 0) {
                int read = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (read <= 0) break;
                digest.update(buf, 0, read);
                remaining -= read;
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "计算内容哈希失败", e);
            return "";
        }
    }

    private static int bytesToInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}
