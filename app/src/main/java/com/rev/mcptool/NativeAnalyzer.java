package com.rev.mcptool;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 原生库（.so）分析器。
 *
 * **双层设计（真机反馈后确定）**：
 *   第 1 层 —— 纯 Java ELF 解析（{@link ElfReader}）：段表 / 动态符号 / 导出导入 /
 *              依赖库 / 字符串 / Dart 源路径与包路径。**必定成功、不会卡住**，
 *              信息量已足以支撑"定位 → 交给别的工具改"这条主流程。
 *   第 2 层 —— radare2 增强（可选）：文件信息、反汇编定位、aa 函数识别。
 *              手机上 r2 子进程可能挂住，所以它是**加法而非前提**：
 *              失败/超时只写一行说明，不影响第 1 层的结论。
 *
 * 纯 Java，零 android.* 依赖，桌面 JVM 可原样回归。
 */
public final class NativeAnalyzer {

    /** r2 执行器（手机端由 R2Bridge 实现，桌面端由测试用进程实现） */
    public interface Runner {
        String run(String target, String[] cmds, int timeoutSec) throws Exception;
    }

    /** 进度回调 */
    public interface Emit {
        void emit(String line);
    }

    private static final int MAX_ROWS = 25;
    private static final int MAX_REPORT = 90000;

    /** r2 增强分析的超时：刻意取短值，宁可放弃也不拖住 MCP 响应 */
    private static final int R2_BASE_TIMEOUT = 45;
    private static final int R2_DEEP_TIMEOUT = 150;

    /** 字符串扫描模式（一次遍历同时统计） */
    private static final String[] PATTERNS = {
            ".dart", "package:", "http", "dart:", "flutter", "frida"
    };
    private static final int STR_MIN_LEN = 6;
    private static final int STR_SAMPLE = 20;

    // 输出分段标记：不能用 @（r2 会当 foreach 运算符）
    private static final String M_II = "==REV_II==";
    private static final String M_IS = "==REV_IS==";
    private static final String M_IE = "==REV_IE==";
    private static final String M_IL = "==REV_IL==";
    private static final String M_ISY = "==REV_ISY==";
    private static final String M_AFLC = "==REV_AFLC==";
    private static final String M_AFL = "==REV_AFL==";

    private NativeAnalyzer() { }

    public static String analyzeSo(Runner runner, File so, boolean deep, Emit emit) throws Exception {
        String path = so.getAbsolutePath();
        StringBuilder sb = new StringBuilder();
        sb.append("■■ ").append(so.getName()).append("\n");
        sb.append("  路径 : ").append(path).append("\n");
        sb.append("  大小 : ").append(so.length())
          .append(" bytes (").append(ApkAnalyzer.fmtSize(so.length())).append(")\n");

        // ---------- 第 1 层：纯 Java ELF 解析（必定成功） ----------
        if (emit != null) emit.emit("  · ELF 结构解析 " + so.getName());
        boolean dartAot = false;
        try {
            ElfReader er = ElfReader.parse(so);
            appendElf(sb, er, so.getName());
            dartAot = er.isDartAot();
        } catch (Exception e) {
            sb.append("\n■ ELF 结构\n  !! 解析失败: ").append(e).append("\n");
        }

        // ---------- 第 2 层：radare2 增强（可选，短超时） ----------
        sb.append("\n■ radare2 增强分析（可选，失败不影响上面的结论）\n");
        if (runner == null) {
            sb.append("  (未启用：未勾选深度分析，或 radare2 运行时不可用)\n");
        } else {
            boolean ok = false;
            try {
                if (emit != null) emit.emit("  · r2 增强分析 " + so.getName() + "（超时 " + R2_BASE_TIMEOUT + "s）");
                long t0 = System.currentTimeMillis();
                String base = runner.run(path, baseCmds(), R2_BASE_TIMEOUT);
                // 空输出＝执行器层瞬态失败（进程未起来/输出未交接完），重试一次即可恢复；
                // 重试只在这条路径上发生，不影响"r2 坏了也不影响纯 Java 结论"的承诺。
                if (base == null || base.trim().isEmpty()) {
                    if (emit != null) emit.emit("  · r2 空输出，重试一次 " + so.getName());
                    base = runner.run(path, baseCmds(), R2_BASE_TIMEOUT);
                }
                long ms = System.currentTimeMillis() - t0;
                Map<String, String> sec = split(base);
                String info = get(sec, M_II);
                if (!looksLikeR2(info)) {
                    sb.append("  !! radare2 无有效输出（用时 ").append(ms).append("ms）。\n");
                    sb.append("     常见原因：手机上 r2 子进程无法执行（Android 10+ 对 app 私有目录\n");
                    sb.append("     的 exec 限制）或依赖库缺失。可调用 r2_doctor 工具自查。\n");
                    sb.append("     **上面的纯 Java ELF 结论不受影响，可直接使用。**\n");
                } else {
                    ok = true;
                    sb.append("  用时 ").append(ms).append("ms\n");
                    sb.append("  ◆ 文件信息（iI）: ").append(join(parseInfo(info),
                            "arch", "bits", "machine", "os", "pic", "stripped", "canary", "nx", "relro")).append("\n");
                    List<String> il = bareLines(get(sec, M_IL));
                    if (!il.isEmpty()) sb.append("  ◆ 依赖库（il）: ").append(joinList(il, 20)).append("\n");
                    List<String> is = table(get(sec, M_ISY));
                    sb.append("  ◆ 动态符号（isq）: ").append(is.size()).append(" 条\n");
                    List<String> ie = table(get(sec, M_IE));
                    if (!ie.isEmpty()) {
                        sb.append("  ◆ 入口点（ie）: ").append(ie.size()).append(" 个\n");
                        cap(sb, ie, "      ", 4);
                    }
                }
            } catch (Exception e) {
                sb.append("  !! radare2 调用失败: ").append(e).append("\n");
                sb.append("     **上面的纯 Java ELF 结论不受影响，可直接使用。**\n");
            }

            // 深度：函数识别（仅主库且 r2 可用时）
            if (deep && ok) {
                sb.append("\n  ◆ 深度函数分析（aa）\n");
                if (emit != null) emit.emit("  · r2 深度分析 " + so.getName() + "（可能数十秒）");
                long t0 = System.currentTimeMillis();
                try {
                    String deepOut = runner.run(path, deepCmds(), R2_DEEP_TIMEOUT);
                    Map<String, String> ds = split(deepOut);
                    long ms = System.currentTimeMillis() - t0;
                    sb.append("    函数总数 : ").append(firstNumber(get(ds, M_AFLC)))
                      .append("（用时 ").append(ms / 1000).append("s）\n");
                    List<String> fl = table(get(ds, M_AFL));
                    cap(sb, fl, "    ", MAX_ROWS);
                    if (dartAot && fl.size() <= 2) {
                        // 真机实测：libapp.so 上 aa 只给出 entry0 一个"函数"。
                        // 这不是 r2 出问题，而是 Dart AOT 的机器码布局让线性分析无从下手。
                        sb.append("    (Dart AOT 库 —— `aa` 只能识别到 entry0 级别的入口，属正常现象；\n");
                        sb.append("     函数边界要用 blutter 还原：blutter_run {\"so_path\":\"…/libapp.so\"})\n");
                    } else if (fl.isEmpty()) {
                        sb.append("    (aa 未识别出函数 —— 布局异常或库被强 strip；\n");
                        sb.append("     改用下面的字符串偏移 + r2_xrefs 定位)\n");
                    }
                } catch (Exception e) {
                    sb.append("    !! 深度分析失败: ").append(e).append("\n");
                }
            }
        }

        // ---------- 第 3 层：定位建议（按文件类型分流） ----------
        sb.append("\n■ 下一步定位建议\n");
        if (dartAot) {
            // Dart AOT 的正确主线是符号还原：r2 的线性分析对它基本无效
            // （实测 libapp.so 上 `aa` 只识别出 entry0 一个"函数"）。
            sb.append("  ★ 这是 Dart AOT 快照库 —— 先做符号还原，再谈代码定位\n");
            sb.append("  blutter_run {\"so_path\":\"").append(path).append("\"}\n");
            sb.append("      用内置预编译 blutter 还原函数名 / 类结构 / 伪代码，产物写进 analysis.log 可分页读；\n");
            sb.append("      版本按 Dart 版本自动挑选（可先跑 blutter_doctor 看内置清单）。\n");
            sb.append("  flutter_probe {\"apk_path\":\"<APK>\"}   取 Dart 版本 / engine id（若要手动对版本）\n");
            sb.append("\n  以下 r2 手段在 libapp.so 上只用于**字符串级**定位，不要期望反汇编出函数：\n");
        }
        sb.append("  r2_strings {\"path\":\"").append(path).append("\",\"filter\":\"关键字\"}   找字符串\n");
        sb.append("  r2_xrefs   {\"path\":\"").append(path).append("\",\"addr\":\"0x偏移\"}   从字符串偏移追到代码\n");
        sb.append("  r2_disasm  {\"path\":\"").append(path).append("\",\"addr\":\"0x偏移\",\"n\":64}   反汇编\n");
        if (!dartAot) {
            sb.append("  flutter_probe {\"apk_path\":\"<APK>\"}   取 Dart 版本与 blutter 版本选择\n");
        }
        sb.append("  说明: 上面列出的字符串都带文件偏移（0x…），拿它去 r2_xrefs 就能顺藤摸瓜；\n");
        if (dartAot) {
            sb.append("        Dart AOT 里 ObjectPool / 常量表引用也能这么追，但**函数边界要靠 blutter 产物**\n");
            sb.append("        （pp.txt 形如 [package:xxx/yyy.dart] Class::method (0xaddr) 的条目即地址↔源位置对应）。\n");
        } else {
            sb.append("        这是没有符号表（stripped）时最有效的路径。\n");
        }

        String out = sb.toString();
        if (out.length() > MAX_REPORT) {
            out = out.substring(0, MAX_REPORT) + "\n…（报告超长已截断，用 r2_* 工具做定向深挖）\n";
        }
        return out;
    }

    // ---------------- 第 1 层：ELF 报告 ----------------

    private static void appendElf(StringBuilder sb, ElfReader er, String fileName) {
        sb.append("\n■ ELF 结构（纯 Java 解析，不依赖 radare2）\n");
        if (er.unsupported != null && er.unsupported.length() > 0) {
            sb.append("  !! ").append(er.unsupported).append("\n");
            return;
        }
        sb.append("  类型     : ").append(er.typeName()).append("\n");
        sb.append("  类别     : ").append(er.elf64 ? "ELF64" : "ELF32")
          .append(er.little ? " 小端（LE）" : " 大端（BE）").append("\n");
        sb.append("  架构     : ").append(er.machineName()).append("\n");
        sb.append("  入口     : 0x").append(Long.toHexString(er.entry)).append("\n");
        if (er.soname != null && er.soname.length() > 0) {
            sb.append("  SONAME   : ").append(er.soname).append("\n");
        }
        sb.append("  段数     : ").append(er.sections.size()).append("\n");

        // 段表
        sb.append("\n  ◆ 段表\n");
        int n = 0;
        for (ElfReader.Section s : er.sections) {
            if (s.type == 0 || s.size == 0) continue;
            if (n++ >= MAX_ROWS) {
                sb.append("      …（其余 ").append(er.sections.size() - MAX_ROWS).append(" 段已省略）\n");
                break;
            }
            sb.append("      ").append(cell(s.name, 18))
              .append(cell("0x" + Long.toHexString(s.addr), 12))
              .append(cell(ApkAnalyzer.fmtSize(s.size), 10))
              .append(cell(ElfReader.sectionTypeName(s.type), 12))
              .append(perm(s.flags)).append("\n");
        }

        // 依赖库
        if (!er.needed.isEmpty()) {
            sb.append("\n  ◆ 依赖库（DT_NEEDED，").append(er.needed.size()).append(" 个）\n");
            sb.append("      ").append(joinList(er.needed, 24)).append("\n");
        }

        // 导出符号
        List<ElfReader.Sym> ex = er.exports();
        sb.append("\n  ◆ 导出符号（").append(ex.size()).append(" 个）\n");
        List<ElfReader.Sym> interesting = er.interestingExports(MAX_ROWS);
        if (interesting.isEmpty()) {
            sb.append("      (无 Dart/JNI/网络/反调试 相关的具名导出)\n");
        } else {
            sb.append("      与 Dart / JNI / 网络 / 反调试 相关的导出：\n");
            for (ElfReader.Sym s : interesting) {
                sb.append("      0x").append(Long.toHexString(s.value)).append("  ");
                sb.append(cell(s.name, 34));
                if (s.size > 0) sb.append("(").append(s.size).append(")");
                sb.append("\n");
            }
        }

        // 导入符号
        List<ElfReader.Sym> im = er.imports();
        sb.append("\n  ◆ 导入符号（UND，").append(im.size()).append(" 个）\n");
        if (!im.isEmpty()) sb.append("      ").append(joinSymNames(im, 24)).append("\n");

        // 字符串
        sb.append("\n  ◆ 字符串情报（单次扫描，"
                + StrMin() + " 字符以上；每项最多列 " + STR_SAMPLE + " 条）\n");
        Map<String, ElfReader.StrHit> hits = er.scanStrings(PATTERNS, STR_MIN_LEN, STR_SAMPLE);
        ElfReader.StrHit meta = hits.get("\u0000meta\u0000");
        sb.append("      字符串总数 : ").append(meta == null ? 0 : meta.total).append("\n\n");
        for (String p : PATTERNS) {
            ElfReader.StrHit h = hits.get(p);
            int cnt = h == null ? 0 : h.total;
            sb.append("      [").append(pad(p, 10)).append("] ").append(cnt).append(" 条\n");
            if (h != null) {
                for (String[] row : h.sample) {
                    sb.append("         ").append(pad(row[0], 10))
                      .append(shortStr(row[1], 110)).append("\n");
                }
            }
        }

        // Dart 专项：包路径 / 内部库路径 / 快照符号位置
        appendDart(sb, er, hits, fileName);
    }

    /**
     * Dart 专项情报 —— 这是 Flutter 逆向里最值钱的部分：
     *   · package:xxx/yyy.dart 直接给出"函数地址 ↔ Dart 源位置"的线索
     *   · dart:internal 说明该库来自 Dart 运行时内部
     *   · _kDartVmSnapshotData / _kDartIsolateSnapshotData 的文件偏移 =
     *     后续用 r2_xrefs 追快照结构的入口点
     * 对非 Flutter 库同样输出（计数为 0），这样"有没有 Dart 成分"一眼可判。
     */
    private static void appendDart(StringBuilder sb, ElfReader er,
                                   Map<String, ElfReader.StrHit> hits, String fileName) {
        sb.append("\n  ◆ Dart 包路径（package: / dart:）\n");
        ElfReader.StrHit pkg = hits.get("package:");
        ElfReader.StrHit dart = hits.get("dart:");
        ElfReader.StrHit dartExt = hits.get(".dart");
        sb.append("      package: 命中 ").append(pkg == null ? 0 : pkg.total)
          .append(" 条，dart: 命中 ").append(dart == null ? 0 : dart.total)
          .append(" 条，.dart 命中 ").append(dartExt == null ? 0 : dartExt.total).append(" 条\n");
        int shown = 0;
        if (pkg != null) {
            for (String[] row : pkg.sample) {
                sb.append("         ").append(pad(row[0], 10)).append(shortStr(row[1], 110)).append("\n");
                if (++shown >= MAX_ROWS) break;
            }
        }
        if (shown == 0 && dart != null) {
            for (String[] row : dart.sample) {
                sb.append("         ").append(pad(row[0], 10)).append(shortStr(row[1], 110)).append("\n");
                if (++shown >= MAX_ROWS) break;
            }
        }
        if (shown == 0) {
            sb.append("         (无 Dart 源路径 —— 非 Flutter 库，或该库已被 strip 到只剩机器码)\n");
        }

        // 快照符号：先看动态符号表，再退化为原始字节搜索（Dart AOT 通常已 strip）
        sb.append("\n  ◆ Dart 快照符号\n");
        int named = 0;
        for (ElfReader.Sym y : er.dynsyms) {
            if (y.name.indexOf("Dart") >= 0 || y.name.indexOf("Snapshot") >= 0) {
                sb.append("      符号 ").append(shortStr(y.name, 80))
                  .append("  @ 0x").append(Long.toHexString(y.value)).append("\n");
                named++;
                if (named >= 10) break;
            }
        }
        long[] hitsOff = er.findBytes(new String[]{
                "_kDartVmSnapshotData", "_kDartIsolateSnapshotData"});
        if (hitsOff.length == 0) {
            // 按文件角色给不同的解释 —— 否则对 libavcodec / libflutter 说"对 libapp.so 是正常的"会误导
            String n = fileName == null ? "" : fileName.toLowerCase();
            if (n.endsWith("libapp.so") || n.equals("app")) {
                sb.append("      (未发现 Dart 快照符号 —— 该 libapp.so 可能是非 AOT 构建"
                        + "（debug 走 kernel_blob.bin，无 AOT snapshot）)\n");
            } else if (n.endsWith("libflutter.so") || n.equals("flutter")) {
                sb.append("      (libflutter.so 不含 Dart 快照 —— 快照在 libapp.so 里；\n");
                sb.append("       Dart 版本/engine id 用 flutter_probe 从本文件读取)\n");
            } else {
                sb.append("      (非 Dart 库，无快照符号属正常；若目标是 Flutter 应用，"
                        + "看同批里的 libapp.so)\n");
            }
        } else {
            for (long off : hitsOff) {
                sb.append("      原始字节命中 @ 0x").append(Long.toHexString(off))
                  .append("（可用 r2_xrefs 追引用）\n");
            }
        }
    }

    private static String StrMin() { return String.valueOf(STR_MIN_LEN); }

    private static String perm(long flags) {
        StringBuilder sb = new StringBuilder();
        sb.append((flags & 0x1) != 0 ? 'R' : '-');
        sb.append((flags & 0x2) != 0 ? 'W' : '-');
        sb.append((flags & 0x4) != 0 ? 'X' : '-');
        return sb.toString();
    }

    private static String shortStr(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private static String joinSymNames(List<ElfReader.Sym> l, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i).name);
        }
        if (l.size() > max) sb.append(" … 等 ").append(l.size()).append(" 个");
        return sb.toString();
    }

    // ---------------- r2 命令集（刻意精简：字符串已由 Java 侧完成） ----------------

    private static String[] baseCmds() {
        List<String> c = new ArrayList<String>();
        add(c, M_II, "iI");
        add(c, M_IS, "iS");
        add(c, M_IE, "ie");
        add(c, M_IL, "il");
        add(c, M_ISY, "isq");
        return c.toArray(new String[0]);
    }

    private static String[] deepCmds() {
        List<String> c = new ArrayList<String>();
        c.add("aa");
        add(c, M_AFLC, "aflc");
        add(c, M_AFL, "afl");
        return c.toArray(new String[0]);
    }

    private static void add(List<String> c, String marker, String cmd) {
        c.add("echo " + marker);
        c.add(cmd);
    }

    // ---------------- 解析 ----------------

    static Map<String, String> split(String out) {
        Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        if (out == null) return m;
        String[] lines = out.split("\n", -1);
        String cur = null;
        StringBuilder buf = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("==REV_") && t.endsWith("==")) {
                if (cur != null) m.put(cur, buf.toString());
                cur = t;
                buf = new StringBuilder();
                continue;
            }
            if (cur != null) buf.append(line).append('\n');
        }
        if (cur != null) m.put(cur, buf.toString());
        return m;
    }

    private static String get(Map<String, String> sec, String marker) {
        String s = sec.get(marker);
        return s == null ? "" : s;
    }

    static Map<String, String> parseInfo(String s) {
        Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        for (String line : noise(s).split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String[] p = t.split("\\s+", 2);
            if (p.length == 2 && p[0].length() > 0) m.put(p[0], p[1].trim());
        }
        return m;
    }

    static List<String> table(String s) {
        List<String> out = new ArrayList<String>();
        for (String line : noise(s).split("\n")) {
            if (line.trim().isEmpty()) continue;
            out.add(line);
        }
        return out;
    }

    static List<String> bareLines(String s) {
        List<String> out = new ArrayList<String>();
        for (String line : noise(s).split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String noise(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("WARN:") || t.startsWith("INFO:") || t.startsWith("ERROR:")) continue;
            if (t.startsWith("nth ") || t.startsWith("paddr ") || t.startsWith("----")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    static String firstNumber(String s) {
        String last = null;
        for (String line : noise(s).split("\n")) {
            String t = line.trim();
            if (t.matches("\\d+")) last = t;
        }
        return last == null ? "?" : last;
    }

    private static String join(Map<String, String> m, String... keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            String v = m.get(k);
            if (v == null) continue;
            if (sb.length() > 0) sb.append("  ");
            sb.append(k).append('=').append(v);
        }
        return sb.length() == 0 ? "(无)" : sb.toString();
    }

    private static String joinList(List<String> l, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i));
        }
        if (l.size() > max) sb.append(" … 等 ").append(l.size()).append(" 个");
        return sb.toString();
    }

    private static void cap(StringBuilder sb, List<String> rows, String indent, int max) {
        int n = 0;
        for (String r : rows) {
            if (n++ >= max) {
                sb.append(indent).append("…（其余 ").append(rows.size() - max).append(" 行已省略）\n");
                break;
            }
            sb.append(indent).append(r.trim()).append("\n");
        }
    }

    static boolean looksLikeR2(String out) {
        if (out == null || out.length() == 0) return false;
        String low = out.toLowerCase(Locale.US);
        return low.contains("bintype");
    }

    private static String pad(String s, int w) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder(s);
        while (b.length() < w) b.append(' ');
        return b.toString();
    }

    /**
     * 表格单元格：左对齐补到至少 w 宽，并**保证尾部有一个分隔空格**。
     *
     * 原来直接用 pad() 拼列，段名一旦超过列宽就会和后一列连起来 ——
     * 真机日志里出现过 `.note.android.ident0x238`、`.note.gnu.build-id0x2d0`
     * 这种把名字和偏移糊成一坨的行（`.note.android.ident` 长 19 > 列宽 18）。
     */
    private static String cell(String s, int w) {
        String p = pad(s, w);
        return p.endsWith(" ") ? p : p + " ";
    }
}
