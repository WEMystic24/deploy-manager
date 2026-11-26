package com.example.deploy.util;

/**
 * shell 命令拼接的小工具，主要用于路径转义，防止空格 / 特殊字符导致命令被截断。
 */
public final class ShellUtil {

    private ShellUtil() {
    }

    /** 用单引号包裹参数，内部单引号做转义 */
    public static String quote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().length() == 0;
    }

    public static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    public static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /** 截断过长输出，避免前端展示爆炸 */
    public static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "...(已截断)";
    }
}
