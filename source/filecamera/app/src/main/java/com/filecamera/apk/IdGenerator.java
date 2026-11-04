package com.filecamera.apk;

/**
 * 原生层的 id 生成器：各层各管各的——JS 层用 assets/js/android_part.js 里的 get_id_by_time()，
 * 原生层用这里的 get_id_by_time()，两套实现互不依赖。
 *
 * <p>之所以不做跨层统一：两层负责的 id 范围基本不重叠（WebView 侧主要生成目录/任务 id，
 * 原生侧主要生成文件/会话 id），各自本地生成更快，也少一层依赖。
 *
 * <p>规则：&lt;毫秒时间戳&gt;-&lt;同一毫秒内的序号&gt;。
 *
 * <p><b>线程安全</b>：{@code synchronized} 保证并发调用时 (时间戳, 序号) 组合唯一，
 * 所以导入可以放心用多个 worker 并行。另外这里额外防了「系统时钟被回拨」：
 * 只要时钟没有前进，就继续用上一个时间戳 + 递增序号，保证 id 单调且不会和历史 id 撞。
 */
public class IdGenerator {

    private static long lastTimestamp = 0;
    private static long sequence = 0;

    public static synchronized String get_id_by_time() {
        long currentTime = System.currentTimeMillis();
        if (currentTime <= lastTimestamp) {
            // 同一毫秒，或系统时钟被回拨：沿用上一个时间戳，序号继续加
            sequence++;
            return lastTimestamp + "-" + sequence;
        }
        lastTimestamp = currentTime;
        sequence = 0;
        return currentTime + "-" + sequence;
    }
}
