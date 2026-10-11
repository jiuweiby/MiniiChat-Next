package com.rev.mcptool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「目标 Dart 版本 → 该用哪个内置 blutter 二进制」的选型逻辑。
 *
 * 命名规则抄自 Blutter GUI：libblutter_&lt;major&gt;_&lt;minor&gt;[_&lt;tag&gt;].so
 * （其 Kotlin 侧的正则就是 {@code libblutter_(\d+)_(\d+)(?:_(\w+))?\.so}）。
 *
 * 官方 blutter 自己用完整版本号命名（{@code blutter_3.10.1_android_arm64}），
 * 但手机上不可能为每个 patch 版本各带一个二进制；按 major.minor 归并是可行的折中 ——
 * 同一 minor 内的 patch 一般不改变 snapshot 布局。
 *
 * 这里只做「排序出候选」，不赌单一答案：同 minor 存在多个 tag（如 3_0_90 / 3_0_aa）时，
 * 调用方按顺序逐个试跑，谁真的产出结果就用谁。
 *
 * 无 {@code android.*} 依赖，桌面 JVM 可直接回归（见 {@code tools/BlutterPickTest.java}）。
 */
public final class BlutterPick {

    /** 与 Blutter GUI 完全一致的命名正则 */
    private static final Pattern NAME =
            Pattern.compile("^libblutter_(\\d+)_(\\d+)(?:_(\\w+))?\\.so$");

    public static final class Ver {
        public final String name;
        public final int major;
        public final int minor;
        public final String tag;   // 无 tag 时为 null
        /** 从二进制里读到的真实 Dart 版本（懒填充，可能为 null） */
        public String realVersion;

        Ver(String name, int major, int minor, String tag) {
            this.name = name;
            this.major = major;
            this.minor = minor;
            this.tag = tag;
        }

        public boolean exact(int maj, int min) { return major == maj && minor == min; }

        @Override public String toString() {
            return name + " → " + major + "." + minor + (tag == null ? "" : "（tag=" + tag + "）");
        }
    }

    private BlutterPick() { }

    /** "3.10.1" → {3,10}；无法解析返回 null。容忍 "3.4.2-0.1.pre" 这类后缀 */
    public static int[] majorMinor(String dartVersion) {
        if (dartVersion == null) return null;
        String[] parts = dartVersion.trim().split("\\.");
        if (parts.length < 2) return null;
        int maj = leadingInt(parts[0]);
        int min = leadingInt(parts[1]);
        if (maj < 0 || min < 0) return null;
        return new int[]{maj, min};
    }

    /** 从文件名解析出版本；不符合命名规则返回 null */
    public static Ver parse(String fileName) {
        Matcher m = NAME.matcher(fileName);
        if (!m.matches()) return null;
        return new Ver(fileName, intOf(m.group(1)), intOf(m.group(2)), m.group(3));
    }

    /** 把一串文件名里符合规则的全部解析出来（按 major.minor 降序） */
    public static List<Ver> available(List<String> fileNames) {
        List<Ver> out = new ArrayList<Ver>();
        if (fileNames == null) return out;
        for (String n : fileNames) {
            Ver v = parse(n);
            if (v != null) out.add(v);
        }
        Collections.sort(out, new Comparator<Ver>() {
            public int compare(Ver a, Ver b) {
                if (a.major != b.major) return b.major - a.major;
                if (a.minor != b.minor) return b.minor - a.minor;
                String ta = a.tag == null ? "" : a.tag;
                String tb = b.tag == null ? "" : b.tag;
                return ta.compareTo(tb);
            }
        });
        return out;
    }

    /**
     * 候选顺序（越靠前越可能合适），**只在目标 major 之内**：
     *   ① 精确 major.minor，且不带 tag（最贴近参考实现的默认件）
     *   ② 精确 major.minor 的其他 tag（如 3_0_90 / 3_0_aa）
     *   ③ 同 major 下 major.minor 低于目标，从高到低
     *
     * 不做跨 major 兜底：Dart 的 snapshot 布局在大版本之间会变，
     * 拿 3.x 的 blutter 去解 4.x/2.x 的快照必然失败，给出这种候选只会误导。
     * 因此目标 major 没有对应二进制时返回空，由报告说明「内置清单里没有」。
     */
    public static List<Ver> candidates(List<String> fileNames, String dartVersion) {
        int[] want = majorMinor(dartVersion);
        List<Ver> avail = available(fileNames);
        List<Ver> out = new ArrayList<Ver>();
        if (want == null) return out;

        for (Ver v : avail) if (v.exact(want[0], want[1]) && v.tag == null) out.add(v);
        for (Ver v : avail) if (v.exact(want[0], want[1]) && v.tag != null) out.add(v);
        for (Ver v : avail) if (v.major == want[0] && v.minor < want[1]) out.add(v);
        return out;
    }

    /** 选中的那一个（可能为 null） */
    public static Ver pick(List<String> fileNames, String dartVersion) {
        List<Ver> c = candidates(fileNames, dartVersion);
        return c.isEmpty() ? null : c.get(0);
    }

    /** 供报告使用的可读清单 */
    public static String describe(List<String> fileNames) {
        List<Ver> avail = available(fileNames);
        if (avail.isEmpty()) return "(未内置任何 blutter 二进制)";
        StringBuilder sb = new StringBuilder();
        sb.append(avail.size()).append(" 个：");
        for (int i = 0; i < avail.size(); i++) {
            sb.append(i == 0 ? " " : ", ");
            sb.append(avail.get(i).major).append('.').append(avail.get(i).minor);
            if (avail.get(i).tag != null) sb.append('-').append(avail.get(i).tag);
        }
        return sb.toString();
    }

    // ---------------- 小工具 ----------------

    private static int intOf(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    private static int leadingInt(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c); else break;
        }
        return b.length() == 0 ? -1 : intOf(b.toString());
    }

    /** "3.10.1" → 1；解析不出返回 -1 */
    public static int patchOf(String version) {
        if (version == null) return -1;
        String[] parts = version.trim().split("\\.");
        if (parts.length < 3) return -1;
        return leadingInt(parts[2]);
    }

    /**
     * 读 blutter 二进制**内嵌的** Dart 版本。
     *
     * blutter 编译时把 Dart SDK 的源码路径编进了二进制，形如
     * {@code /tmp/blutter-termux/dartsdk/v3.10.0/runtime/vm/...}。
     * 这个串给出的是构建该二进制时实际使用的 Dart 版本 ——
     * 实测它和文件名并不总是一致（libblutter_3_6.so 实为 3.6.1、libblutter_3_0_90.so 实为 3.0.3），
     * 所以按文件名的 major.minor 粗筛之后，用这个值做精排才靠得住。
     *
     * @return 形如 "3.10.0"；读不到返回 null
     */
    public static String embeddedDartVersion(java.io.File bin) {
        if (bin == null || !bin.isFile()) return null;
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(bin, "r");
            byte[] buf = new byte[1 << 16];
            long pos = 0;
            long len = raf.length();
            final byte[] NEEDLE = "/dartsdk/v".getBytes("US-ASCII");
            int carry = 0;
            while (pos < len) {
                raf.seek(pos);
                int n = raf.read(buf, carry, buf.length - carry);
                if (n <= 0) break;
                int total = carry + n;
                int idx = indexOf(buf, total, NEEDLE);
                if (idx >= 0) {
                    int p = idx + NEEDLE.length;
                    StringBuilder b = new StringBuilder();
                    while (p < total && b.length() < 24) {
                        char c = (char) (buf[p] & 0xff);
                        if ((c >= '0' && c <= '9') || c == '.') b.append(c); else break;
                        p++;
                    }
                    // 版本串被块边界截断时不完整 —— 只在结尾是数字且跨度 <24 时接受
                    if (b.length() >= 5 && !(p >= total && b.charAt(b.length() - 1) != '.')) {
                        return b.toString();
                    }
                }
                pos += n;
                carry = 0;
            }
        } catch (Exception e) {
            return null;
        } finally {
            AnalysisLog.close(raf);
        }
        return null;
    }

    private static int indexOf(byte[] hay, int len, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= len; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
