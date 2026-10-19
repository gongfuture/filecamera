package com.filecamera.apk;

/**
 * 全应用统一的 id 生成器：与前端 assets/js/android_part.js 里的 get_id_by_time() 规则一致——
 * &lt;毫秒时间戳&gt;-&lt;同一毫秒内的序号&gt;，保证 WebView 与原生两侧生成的 id 不会撞车。
 * 原生侧任何需要新 id 的地方（文件、目录、录像、拍照、会话目录、任务 id）都只走这一个入口。
 */
public class IdGenerator {

    private static long lastTimestamp = 0;
    private static long sequence = 0;

    /** 与 JS get_id_by_time() 完全一致的实现。 */
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

    /** 兼容旧调用：统一转给 get_id_by_time()。 */
    @Deprecated
    public static String getIdByTime() {
        return get_id_by_time();
    }
}
