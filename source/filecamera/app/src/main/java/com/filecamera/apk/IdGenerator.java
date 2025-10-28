package com.filecamera.apk;
public class IdGenerator {
    private static long lastTimestamp = 0;
    private static long sequence = 0;

    // 使用 static 关键字，这样就可以通过类名直接调用
    public static synchronized String getIdByTime() {
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
