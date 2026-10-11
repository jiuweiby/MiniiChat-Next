package com.rev.mcptool;

import android.util.Log;

import com.miniichatNext.carter.debug.DebugLog;
import com.miniichatNext.carter.debug.DebugLevel;

/**
 * Java 侧的日志门面。
 *
 * <p>背景：{@link DebugLog} 是 Kotlin object，Java 调用要写成
 * {@code DebugLog.INSTANCE.i("Tag", "msg")}，而且异常重载只接受
 * {@code (String, String, Throwable)} 一个签名，用起来别扭，
 * 结果整个 rev/mcptool 包长期只用空 catch 吞异常，出问题完全查不到。
 *
 * <p>本类把三件事补齐：
 * <ol>
 *   <li>给出简洁的 Java 静态方法签名 {@code RevLog.i(tag, msg)}</li>
 *   <li>提供 {@code format()} 与 {@code ca} 之类的字符串拼接辅助方法</li>
 *   <li>同时打到 logcat 与 {@link DebugLog}：
 *       前者方便用 adb 实时看，后者让日志进应用内预览页与导出 zip</li>
 * </ol>
 *
 * <p><b>为什么不直接传播异常</b>：本包被 MCP 工具调用与子进程驱动逻辑大量使用，
 * 这些位置抛异常会直接把一次工具调用打成 500。合理的做法是记录后继续，
 * 由调用方根据返回值判断成功与否。
 */
public final class RevLog {

    private RevLog() {
    }

    /** logcat 统一前缀，方便 adb logcat -s RevMCP:* 只看本模块 */
    private static final String LOGCAT_TAG = "RevMCP";

    public static void v(String tag, String msg) {
        Log.v(LOGCAT_TAG, "[" + tag + "] " + msg);
        DebugLog.INSTANCE.v(tag, msg);
    }

    public static void d(String tag, String msg) {
        Log.d(LOGCAT_TAG, "[" + tag + "] " + msg);
        DebugLog.INSTANCE.d(tag, msg);
    }

    public static void i(String tag, String msg) {
        Log.i(LOGCAT_TAG, "[" + tag + "] " + msg);
        DebugLog.INSTANCE.i(tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(LOGCAT_TAG, "[" + tag + "] " + msg);
        DebugLog.INSTANCE.w(tag, msg);
    }

    public static void w(String tag, String msg, Throwable tr) {
        Log.w(LOGCAT_TAG, "[" + tag + "] " + msg, tr);
        DebugLog.INSTANCE.w(tag, msg + "\n" + stackOf(tr));
    }

    public static void e(String tag, String msg) {
        Log.e(LOGCAT_TAG, "[" + tag + "] " + msg);
        DebugLog.INSTANCE.e(tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(LOGCAT_TAG, "[" + tag + "] " + msg, tr);
        DebugLog.INSTANCE.e(tag, msg, tr);
    }

    /**
     * 记录一次被吞掉的异常。
     *
     * <p>专门为「这里出错也不该中断流程」的位置准备。相比空 catch，
     * 至少能在预览页里看到哪个环节失败了、失败原因是什么。
     *
     * @param tag     调用点标识
     * @param what    正在做什么（用一句话描述当前动作）
     * @param tr      捕获到的异常，可为 null
     */
    public static void swallow(String tag, String what, Throwable tr) {
        String detail = tr == null
                ? "null"
                : (tr.getClass().getSimpleName() + ": " + tr.getMessage());
        Log.w(LOGCAT_TAG, "[" + tag + "] " + what + " 失败，已忽略 -> " + detail, tr);
        DebugLog.INSTANCE.w(tag, what + " 失败，已忽略 -> " + detail);
    }

    /**
     * 格式化拼接。
     *
     * <p>Java 侧的日志消息几乎全是字符串拼接，直接写 {@code "a=" + a + " b=" + b}
     * 在编译期就会生成 StringBuilder 链，读起来也乱。这里提供一个
     * 轻量的模板方法：用 {@code {}} 占位，按顺序替换。
     *
     * <pre>
     * RevLog.d("Blutter", RevLog.format("start ver={} timeout={}s", ver, timeout));
     * </pre>
     *
     * <p>刻意不引入 String.format：它要解析格式串、且 %s 遇到 null 会输出 "null"，
     * 在日志场景下反而不如直接拼 toString 直观。
     */
    public static String format(String pattern, Object... args) {
        if (pattern == null || args == null || args.length == 0) return pattern;
        StringBuilder sb = new StringBuilder(pattern.length() + args.length * 8);
        int argIndex = 0;
        int i = 0;
        int len = pattern.length();
        while (i < len) {
            int brace = pattern.indexOf("{}", i);
            if (brace < 0 || argIndex >= args.length) {
                sb.append(pattern, i, len);
                break;
            }
            sb.append(pattern, i, brace);
            sb.append(args[argIndex] == null ? "null" : args[argIndex].toString());
            argIndex++;
            i = brace + 2;
        }
        return sb.toString();
    }

    /**
     * 把异常栈转成字符串。
     *
     * <p>{@link DebugLog} 的 w 重载不带 Throwable，只能自己转。
     * 只保留前若干行：完整栈动辄几十行，写进日志会淹没上下文，
     * 而排查时真正有用的是最上面的调用链。
     */
    private static String stackOf(Throwable tr) {
        if (tr == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        tr.printStackTrace(new java.io.PrintWriter(sw));
        String full = sw.toString();
        String[] lines = full.split("\n");
        int keep = Math.min(lines.length, 12);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i].trim());
        }
        if (lines.length > keep) {
            sb.append("\n    ... 其余 ").append(lines.length - keep).append(" 行已省略");
        }
        return sb.toString();
    }

    /** 供上层判断当前日志等级，避免在低等级下做昂贵的字符串拼接 */
    public static boolean isDebugEnabled() {
        DebugLevel lvl = DebugLog.INSTANCE.getLevel();
        return lvl.ordinal() <= DebugLevel.DEBUG.ordinal();
    }

    /** 供上层判断是否需要记录 verbose 级日志 */
    public static boolean isVerboseEnabled() {
        return DebugLog.INSTANCE.getLevel() == DebugLevel.VERBOSE;
    }
}
