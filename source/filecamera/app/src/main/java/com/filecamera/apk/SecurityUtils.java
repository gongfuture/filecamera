package com.filecamera.apk;

import java.io.File;
import java.io.IOException;

/**
 * 安全相关的共用校验。
 *
 * <p>文件路径只应落在调用方指定的根目录内。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 判断 file 是否位于 root 目录内部（包含 root 本身），使用 canonical path 防穿越。 */
    public static boolean isUnderDirectory(File file, File root) {
        if (file == null || root == null) return false;
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 把任意字符串变成安全的单级路径组件：
     * 去掉路径分隔符和控制字符，处理 "." / ".."。
     * 正常的文件名/目录名不受影响。
     */
    public static String safePathComponent(String name) {
        if (name == null) return "_";
        String cleaned = name.replace('/', '_').replace('\\', '_').replace("\u0000", "_");
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
            return "_";
        }
        return cleaned;
    }
}
