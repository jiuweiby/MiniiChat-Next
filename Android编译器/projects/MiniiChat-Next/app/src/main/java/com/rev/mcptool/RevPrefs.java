package com.rev.mcptool;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 轻量设置持久化：端口、blutter 超时、分析设置等。
 */
public final class RevPrefs {
    private static final String FILE = "revmcp";
    private static final String KEY_PORT = "port";
    private static final String KEY_BLUTTER_TIMEOUT = "blutter_timeout";
    private static final String KEY_DEEP_ANALYSIS = "deep_analysis";
    private static final String KEY_FLUTTER_RESTORE = "flutter_restore";
    /** 用户在界面上手选的产物根目录（绝对路径）与其 SAF 树 URI */
    private static final String KEY_OUT_DIR = "out_dir_path";
    private static final String KEY_OUT_URI = "out_dir_uri";

    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;
    public static final int DEFAULT_BLUTTER_TIMEOUT = 420; // 秒

    private RevPrefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ===== 端口设置 =====

    public static boolean isValidPort(int p) {
        return p >= MIN_PORT && p <= MAX_PORT;
    }

    public static int getPort(Context ctx) {
        int p;
        try {
            p = sp(ctx).getInt(KEY_PORT, McpServer.DEFAULT_PORT);
        } catch (Exception e) {
            return McpServer.DEFAULT_PORT;
        }
        return isValidPort(p) ? p : McpServer.DEFAULT_PORT;
    }

    public static boolean setPort(Context ctx, int p) {
        if (!isValidPort(p)) return false;
        try {
            sp(ctx).edit().putInt(KEY_PORT, p).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isCustomPort(Context ctx) {
        return getPort(ctx) != McpServer.DEFAULT_PORT;
    }

    public static void resetPort(Context ctx) {
        try {
            sp(ctx).edit().remove(KEY_PORT).apply();
        } catch (Exception e) { /* ignore */ }
    }

    public static String endpointFor(Context ctx) {
        return "http://127.0.0.1:" + getPort(ctx) + McpServer.PATH_MCP;
    }

    // ===== Blutter 超时设置 =====

    public static int getBlutterTimeout(Context ctx) {
        try {
            return sp(ctx).getInt(KEY_BLUTTER_TIMEOUT, DEFAULT_BLUTTER_TIMEOUT);
        } catch (Exception e) {
            return DEFAULT_BLUTTER_TIMEOUT;
        }
    }

    public static void setBlutterTimeout(Context ctx, int seconds) {
        try {
            sp(ctx).edit().putInt(KEY_BLUTTER_TIMEOUT, seconds).apply();
        } catch (Exception e) { /* ignore */ }
    }

    // ===== 分析设置 =====

    public static boolean isDeepAnalysis(Context ctx) {
        try {
            return sp(ctx).getBoolean(KEY_DEEP_ANALYSIS, true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void setDeepAnalysis(Context ctx, boolean enabled) {
        try {
            sp(ctx).edit().putBoolean(KEY_DEEP_ANALYSIS, enabled).apply();
        } catch (Exception e) { /* ignore */ }
    }

    public static void resetAll(Context ctx) {
        try {
            sp(ctx).edit()
                    .remove(KEY_PORT)
                    .remove(KEY_BLUTTER_TIMEOUT)
                    .remove(KEY_DEEP_ANALYSIS)
                    .remove(KEY_FLUTTER_RESTORE)
                    .remove(KEY_OUT_DIR)
                    .remove(KEY_OUT_URI)
                    .apply();
        } catch (Exception e) { /* ignore */ }
    }

    // ===== 产物根目录（用户手选） =====

    /** 用户选定的产物根目录绝对路径；未选择返回 null */
    public static String getOutDirPath(Context ctx) {
        try {
            String p = sp(ctx).getString(KEY_OUT_DIR, "");
            if (p == null) return null;
            p = p.trim();
            return p.length() == 0 ? null : p;
        } catch (Exception e) {
            return null;
        }
    }

    /** 记录用户选定的产物根目录（path 传 null/空 = 清除选择） */
    public static boolean setOutDir(Context ctx, String path, String treeUri) {
        try {
            android.content.SharedPreferences.Editor e = sp(ctx).edit();
            if (path == null || path.trim().length() == 0) {
                e.remove(KEY_OUT_DIR).remove(KEY_OUT_URI);
            } else {
                e.putString(KEY_OUT_DIR, path.trim());
                e.putString(KEY_OUT_URI, treeUri == null ? "" : treeUri);
            }
            e.apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 用户选定目录对应的 SAF 树 URI 字符串（用于给目录选择器定位初始位置） */
    public static String getOutDirUri(Context ctx) {
        try {
            String u = sp(ctx).getString(KEY_OUT_URI, "");
            return u == null ? "" : u;
        } catch (Exception e) {
            return "";
        }
    }

    // ===== Flutter 符号还原设置 =====

    public static boolean isFlutterRestore(Context ctx) {
        try {
            return sp(ctx).getBoolean(KEY_FLUTTER_RESTORE, true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void setFlutterRestore(Context ctx, boolean enabled) {
        try {
            sp(ctx).edit().putBoolean(KEY_FLUTTER_RESTORE, enabled).apply();
        } catch (Exception e) { /* ignore */ }
    }
}
