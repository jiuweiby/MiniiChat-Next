package com.rev.mcptool;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * blutter「快照版本哈希失配」的纯逻辑：从 blutter 运行输出里抠出
 * {@code expected 'X' found 'Y'}，并把二进制内嵌的 expected 哈希改成 App 实际哈希。
 *
 * <p>无 {@code android.*} 依赖，桌面 JVM 可直接回归（见 {@code tools/BlutterRelaxTest.java}）。
 * 真正的「放宽后执行」在 {@link BlutterRunner#tryRelax}（需要 Android 的 memfd 执行），
 * 这里只负责两件事：识别失配、算出补丁字节。
 */
public final class BlutterRelax {

    private BlutterRelax() { }

    /** 从 blutter 输出里抠出 "expected 'X' found 'Y'" 的快照版本哈希对；没有则返回 null */
    public static String[] parseSnapshotMismatch(String output) {
        if (output == null) return null;
        Matcher m = SNAP.matcher(output);
        return m.find() ? new String[]{m.group(1), m.group(2)} : null;
    }

    private static final Pattern SNAP =
            Pattern.compile("expected '([0-9a-fA-F]{32})' found '([0-9a-fA-F]{32})'");

    /**
     * 把二进制里内嵌的 expected 快照哈希改成 found。
     * 同时替换两种表示：ASCII 十六进制串（blutter 存常量与报错模板都用它）和原始 16 字节
     * （部分构建会把哈希当字节数组）。任一命中即视为可放宽。
     *
     * @return 改好的字节；没有任何替换时返回 null（放宽无意义）
     */
    public static byte[] relaxSnapshotHash(byte[] orig, String expected, String found) {
        if (orig == null || expected == null || found == null) return null;
        if (expected.length() != 32 || found.length() != 32) return null;
        byte[] expAsc = expected.getBytes(StandardCharsets.US_ASCII);
        byte[] fndAsc = found.getBytes(StandardCharsets.US_ASCII);
        byte[] expRaw, fndRaw;
        try { expRaw = hexToBytes(expected); fndRaw = hexToBytes(found); }
        catch (Exception e) { return null; }

        byte[] data = orig.clone();
        boolean changed = false;
        changed |= replaceAll(data, expAsc, fndAsc);
        changed |= replaceAll(data, expRaw, fndRaw);
        return changed ? data : null;
    }

    static boolean replaceAll(byte[] data, byte[] from, byte[] to) {
        if (from == null || to == null || from.length != to.length || from.length == 0) return false;
        boolean changed = false;
        int i = 0;
        while (i + from.length <= data.length) {
            boolean eq = true;
            for (int j = 0; j < from.length; j++) {
                if (data[i + j] != from[j]) { eq = false; break; }
            }
            if (eq) {
                System.arraycopy(to, 0, data, i, to.length);
                i += to.length;
                changed = true;
            } else {
                i++;
            }
        }
        return changed;
    }

    static byte[] hexToBytes(String hex) {
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
