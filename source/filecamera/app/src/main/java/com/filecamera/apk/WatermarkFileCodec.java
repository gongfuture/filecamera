package com.filecamera.apk;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * .wmp4 专用视频文件的"水印数据区"读写。
 *
 * <p>文件结构（标准 MP4 + 尾部追加，不影响任何播放器解析前面的 moov/mdat）：
 * <pre>
 * [ 标准 MP4 ][ 水印 JSON 字节 ][ 4 字节 JSON 长度 ][ 4 字节魔术字 'WMP4' ]
 * </pre>
 *
 * <p>水印数据只存在文件里，数据库不再保存；JSON 内含 content_hash（尾部之前全部字节的
 * SHA-256），播放前重算比对，可发现"把别的水印数据塞进别的视频"这类篡改。
 */
public final class WatermarkFileCodec {

    private static final String TAG = "WatermarkFileCodec";
    private static final byte[] MAGIC = new byte[]{'W', 'M', 'P', '4'};
    private static final int TAIL_SIZE = 8; // 4 字节长度 + 4 字节魔术字

    /** 专用视频后缀 */
    public static final String EXTENSION = "wmp4";

    private WatermarkFileCodec() {
    }

    public static boolean isWatermarkVideo(File file) {
        return file != null && file.isFile()
                && file.getName().toLowerCase().endsWith("." + EXTENSION);
    }

    /** 把水印 JSON 追加到文件尾部；返回写入后的文件大小。 */
    public static long writeTrailer(File file, String json) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("文件不存在");
        byte[] payload = (json == null ? "{}" : json).getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(payload);
            out.write(intToBytes(payload.length));
            out.write(MAGIC);
            out.flush();
            out.getFD().sync();
        }
        return file.length();
    }

    /** 读取尾部水印 JSON；没有/损坏返回 null。 */
    public static String readTrailer(File file) {
        if (file == null || !file.isFile()) return null;
        long size = file.length();
        if (size <= TAIL_SIZE) return null;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            raf.seek(size - TAIL_SIZE);
            byte[] tail = new byte[TAIL_SIZE];
            raf.readFully(tail);
            for (int i = 0; i < MAGIC.length; i++) {
                if (tail[4 + i] != MAGIC[i]) return null;   // 不是 .wmp4 结构
            }
            int length = bytesToInt(tail, 0);
            if (length <= 0 || length > size - TAIL_SIZE) return null;
            long payloadStart = size - TAIL_SIZE - length;
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

    /** 给 JSON 补上 content_hash（写入前调用）。 */
    public static String withContentHash(File file, String json) {
        String hash = contentHash(file);
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json == null ? "{}" : json);
            obj.put("content_hash", hash);
            return obj.toString();
        } catch (Exception e) {
            return json;
        }
    }

    private static byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private static int bytesToInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}
