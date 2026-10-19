package com.filecamera.apk;

/**
 * 原生层的 id 生成器：各层各管各的——JS 层用 assets/js/android_part.js 里的 get_id_by_time()，
 * 原生层用这里的 get_id_by_time()，两套实现互不依赖。
 *
 * <p>之所以不做跨层统一：两层负责的 id 范围基本不重叠（WebView 侧主要生成目录/任务 id，
 * 原生侧主要生成文件/会话 id），各自本地生成更快，也少一层依赖。
 *
 * <p>规则：&lt;毫秒时间戳&gt;-&lt;同一毫秒内的序号&gt;。
 */
public class IdGenerator {

    private static long lastTimestamp = 0;
    private static long sequence = 0;

    public static synchronized String get_id_by_time() {
        long currentTime = System.currentTimeMillis();
        if (currentTime == lastTimestamp) {
            sequence++;
        } else {
            lastTimestamp = currentTime;
            sequence = 0;
        }
        return currentTime + "-" + sequence;
    }
}
