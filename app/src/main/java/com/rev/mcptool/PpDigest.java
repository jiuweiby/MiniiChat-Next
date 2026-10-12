package com.rev.mcptool;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * blutter 主产物 `pp.txt` 的**结构化摘要**。
 *
 * 为什么需要它：`pp.txt` 是对象池（Object Pool）的逐槽 dump，一份真实样本有
 * 2.9 MB / 63,181 行，纯文本丢给用户等于没给 —— 全篇形如
 *
 * <pre>
 * pool heap offset: 0xd00080
 * [pp+0x10]    Stub: Subtype7TestCache (0x4c2448)
 * [pp+0x58]    String: "file:///Users/sy/Desktop/git/group_2/hgdj/hgdj-android-client/..."
 * [pp+0xb0]    Field &lt;VideoPlayerPlatform._token@637265862&gt;: static late final (offset: 0x9a8)
 * [pp+0x148]   Closure: (int) =&gt; VideoPlayerInstanceApi from Function '...': static. (0x7a14d9fa24)
 * [pp+0x549f8] AnonymousClosure: (0x980560), in [package:hgdj/.../search_video_empty_view.dart] ...
 * </pre>
 *
 * 但其中最有价值的部分是**形如 {@code [package:xxx/yyy.dart] Class::method (0xaddr)} 的条目** ——
 * 它直接给出「函数地址 ↔ Dart 源位置」的对应关系，等于 blutter 帮你生成的符号表。
 * 有了它，再在 r2 里按地址定位，就能把 `sub_xxxx` 还原成语义化函数名。
 *
 * 本类把这些挖出来，并给出按对象类型 / 按 Dart 包的统计，让摘要自带结论。
 *
 * 纯 Java，零 {@code android.*} 依赖，桌面可直接回归（见 tools/PpDigestTest.java）。
 */
public final class PpDigest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** 单个 symbol 列表上限：超大 pp.txt 下防止内存失控（总数仍照实数计） */
    private static final int MAX_SYMBOLS = 20000;

    /**
     * 对外交付的产物名。
     *
     * <p>blutter 自己那份对象池 dump 固定叫 {@code pp.txt}（那是它的接口，改不了）；
     * 但**我们交给用户的解析结果**不该沿用这个看不出内容的工程文件名 ——
     * 真正有价值的是「地址 ↔ Dart 源位置」的符号索引，索性按内容命名：
     * <ul>
     *   <li>{@link #TSV_NAME} —— 机器可读的符号索引（一行一条，便于 grep / 喂给 AI）</li>
     *   <li>{@link #TXT_NAME} —— 人可读的摘要（类型统计 / 按包分布 / 业务包符号）</li>
     * </ul>
     */
    public static final String TSV_NAME = "dart_symbols.tsv";
    public static final String TXT_NAME = "dart_symbols.txt";

    public static final class Sym {
        public String addr = "";        // "0x7a14d9fa24"
        public String pkgPath = "";     // "package:hgdj/hj_page/xxx.dart"
        public String name = "";        // "OnlinePayPage::build"（尽力而为）
        public String off = "";         // "0x148"（对象池槽位）

        public String toString() {
            return addr + "  " + pkgPath + (name.length() > 0 ? "  " + name : "");
        }
    }

    public static final class Digest {
        public long lines;
        public long recognized;
        public long unrecognized;
        public long bytes;
        /** 结构行：缩进续行（成员行）+ 闭合花括号，不是解析失败，单独计 */
        public long continuations;
        /** 对象类型 → 条数（按条数降序） */
        public final Map<String, Integer> kinds = new LinkedHashMap<String, Integer>();
        /** Dart 包名（package: 后第一段）→ 条数 */
        public final Map<String, Integer> packages = new LinkedHashMap<String, Integer>();
        /** 全部带地址的符号条目（上限 MAX_SYMBOLS） */
        public final List<Sym> symbols = new ArrayList<Sym>();
        /** 命中"出现最多的那个包"的符号（一般是业务包） */
        public final List<Sym> appSymbols = new ArrayList<Sym>();
        public String topPackage = "";
        public String header = "";       // "pool heap offset: 0x…" 这类文件头行
        public boolean looksLikePp;      // 是否真的像 pp.txt
    }

    private PpDigest() { }

    // ---------------------------------------------------------------- 解析

    public static Digest parse(File pp) {
        Digest d = new Digest();
        if (pp == null || !pp.isFile()) return d;
        d.bytes = pp.length();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(pp), UTF8), 1 << 16);
            String line;
            while ((line = br.readLine()) != null) {
                d.lines++;
                String t = line.trim();
                if (t.length() == 0) continue;
                if (t.startsWith("pool heap offset")) {
                    if (d.header.length() == 0) d.header = t;
                    d.looksLikePp = true;
                    continue;
                }
                int close = t.indexOf("] ");
                if (!t.startsWith("[pp+") || close < 0) {
                    // 缩进开头的是上一条目的成员行，'}' 是对象 dump 的闭合花括号 ——
                    // 真实样本里二者占了六成（此刻日记 59,971 / 98,844 行），
                    // 若算进「未识别」会让摘要看起来像大面积解析失败，必须分开统计。
                    char c0 = line.charAt(0);
                    if (c0 == ' ' || c0 == '\t' || c0 == '}') d.continuations++;
                    else d.unrecognized++;
                    continue;
                }
                d.recognized++;
                d.looksLikePp = true;
                String off = t.substring(4, close);          // "0x148"
                String rest = t.substring(close + 2);

                String kind = kindOf(rest);
                if (kind != null) bump(d.kinds, kind);

                Sym s = symbolOf(off, t);
                if (s != null) {
                    if (d.symbols.size() < MAX_SYMBOLS) d.symbols.add(s);
                    String pkg = pkgName(s.pkgPath);
                    if (pkg.length() > 0) bump(d.packages, pkg);
                } else {
                    // 没有 package: 的行也可能带包名信息（罕见），不计入包统计
                }
            }
        } catch (Exception e) {
            d.unrecognized++;
        } finally {
            AnalysisLog.close(br);
        }
        d.topPackage = topAppKey(d.packages);
        if (d.topPackage.length() > 0) {
            for (Sym s : d.symbols) {
                if (d.topPackage.equals(pkgName(s.pkgPath))) d.appSymbols.add(s);
            }
        }
        return d;
    }

    /** 取 "[pp+0x…] " 后面到第一个 ':' 之前、再取首个空白词 —— 得到对象类型名 */
    private static String kindOf(String rest) {
        int colon = rest.indexOf(':');
        String k = (colon > 0 ? rest.substring(0, colon) : rest).trim();
        int sp = k.indexOf(' ');
        if (sp > 0) k = k.substring(0, sp);
        if (k.length() == 0 || k.length() > 24) return null;
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') && c != '_') return null;
        }
        return k;
    }

    /**
     * 从一行里抽出「地址 ↔ Dart 源位置」。
     *
     * 形如：{@code ... in [package:hgdj/hj_page/x.dart] OnlinePayPage::build (0x7a14d9fa24)}
     * 或 {@code [package:hgdj/.../y.dart] SomeClass::method (0x…) }
     * 地址取行内**最后一个** `(0x…`（前面的可能是字段 offset），名字取 `]` 到 `(0x` 之间的部分。
     */
    private static Sym symbolOf(String off, String line) {
        int pb = line.indexOf("[package:");
        if (pb < 0) return null;
        int pe = line.indexOf(']', pb);
        if (pe < 0) return null;
        // pb 指向 '['，故路径从 pb + "[package:".length() 开始（少带这个 '[' 会让结果变成 ":hgdj/…"）
        String path = line.substring(pb + "[package:".length(), pe).trim();
        if (path.length() == 0) return null;

        Sym s = new Sym();
        s.off = off;
        s.pkgPath = "package:" + path;

        int ap = line.lastIndexOf("(0x");
        if (ap >= 0) {
            int ae = line.indexOf(')', ap);
            if (ae < 0 || ae > ap + 24) ae = Math.min(line.length(), ap + 20);
            s.addr = line.substring(ap + 1, ae);
        }
        String tail = line.substring(pe + 1).trim();
        int cut = tail.indexOf("(0x");
        if (cut > 0) tail = tail.substring(0, cut).trim();
        // 去掉行尾的省略号/标点
        while (tail.length() > 0 && ",.;".indexOf(tail.charAt(tail.length() - 1)) >= 0) {
            tail = tail.substring(0, tail.length() - 1).trim();
        }
        if (tail.length() > 120) tail = tail.substring(0, 120) + "…";
        s.name = tail;
        return s;
    }

    private static String pkgName(String pkgPath) {
        if (pkgPath == null) return "";
        String p = pkgPath.startsWith("package:") ? pkgPath.substring(8) : pkgPath;
        int sl = p.indexOf('/');
        return sl > 0 ? p.substring(0, sl) : p;
    }

    private static void bump(Map<String, Integer> m, String k) {
        Integer c = m.get(k);
        m.put(k, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
    }

    private static String topKey(Map<String, Integer> m) {
        String best = "";
        int bestN = -1;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getValue().intValue() > bestN) { bestN = e.getValue().intValue(); best = e.getKey(); }
        }
        return best;
    }

    /**
     * 「业务包」= 条目最多的<b>非框架</b>包。
     *
     * <p>为什么不能直接取条目最多的包：真实样本里 {@code flutter} 框架自身的符号往往比 App
     * 还多（此刻日记 2.6.6：flutter 1563 条 vs mdiary2 730 条），按条目数排序会把框架当成业务包，
     * 于是「业务包符号」全是 {@code package:flutter/src/...} 的框架函数 —— 对逆向毫无价值，
     * 用户真正要看的是 App 自己的 {@code package:mdiary2/...}。
     *
     * <p>框架/SDK 包（dart:*、flutter、sky_engine 等）一律跳过；取剩下的第一名。
     * 若全是框架包（极端情况），退化为 {@link #topKey}。
     */
    private static String topAppKey(Map<String, Integer> m) {
        String best = "";
        int bestN = -1;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (isFramework(e.getKey())) continue;
            if (e.getValue().intValue() > bestN) { bestN = e.getValue().intValue(); best = e.getKey(); }
        }
        return best.length() > 0 ? best : topKey(m);
    }

    /** 框架 / SDK 自带的包，不算「业务代码」 */
    private static boolean isFramework(String pkg) {
        if (pkg == null || pkg.length() == 0) return true;
        if (pkg.equals("dart") || pkg.equals("flutter") || pkg.equals("sky_engine")) return true;
        if (pkg.equals("vector_math") || pkg.equals("typed_data") || pkg.equals("ffi")) return true;
        if (pkg.equals("_fe_analyzer_shared") || pkg.equals("_js_interop_checks")) return true;
        // 下划线开头的都是 SDK 内部实现（如 _engine、_http）
        return pkg.charAt(0) == '_';
    }

    private static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> l = new ArrayList<Map.Entry<String, Integer>>(m.entrySet());
        Collections.sort(l, new Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                if (a.getValue().intValue() != b.getValue().intValue()) {
                    return b.getValue().intValue() - a.getValue().intValue();
                }
                return a.getKey().compareTo(b.getKey());
            }
        });
        return l;
    }

    // ---------------------------------------------------------------- 导出

    /**
     * 导出**机器可读**的符号索引（TSV，UTF-8），一行一条：
     * {@code 地址 \t Dart 源位置 \t 方法名 \t 对象池槽位}。
     *
     * <p>这是本工具对外最有价值的产物：AI 客户端可以直接 grep / 分页读，
     * 拿到地址就能回 r2 / IDA 把 {@code sub_xxxx} 命名成 {@code Class::method}。
     *
     * @return 写入条数；出错返回 -1
     */
    public static int writeTsv(Digest d, File out, String source) {
        if (d == null || out == null) return -1;
        Writer w = null;
        try {
            File p = out.getParentFile();
            if (p != null && !p.isDirectory()) p.mkdirs();
            w = new OutputStreamWriter(new FileOutputStream(out), UTF8);
            w.write("# dart_symbols.tsv — Dart AOT 符号索引（blutter 对象池 dump 的结构化提取）\n");
            w.write("# 来源    : " + (source == null ? "libapp.so" : source) + "\n");
            w.write("# 条目    : " + d.symbols.size()
                    + (d.symbols.size() >= MAX_SYMBOLS ? "（已达收录上限）" : "") + "\n");
            w.write("# 业务包  : " + (d.topPackage.length() == 0 ? "(未识别)" : d.topPackage)
                    + "，规模 " + fmt(d.lines) + " 行 / " + fmt(d.recognized) + " 条\n");
            w.write("# 列      : 地址 \\t Dart 源位置 \\t 方法 \\t 对象池槽位\n");
            for (Sym s : d.symbols) {
                w.write(s.addr);
                w.write('\t');
                w.write(s.pkgPath);
                w.write('\t');
                w.write(s.name);
                w.write('\t');
                w.write(s.off);
                w.write('\n');
            }
            return d.symbols.size();
        } catch (Exception e) {
            return -1;
        } finally {
            AnalysisLog.close(w);
        }
    }

    /** 导出人可读摘要（就是 {@link #report} 的内容）。 */
    public static boolean writeDigest(Digest d, File out, int maxKinds, int maxPkgs, int maxSyms) {
        Writer w = null;
        try {
            File p = out.getParentFile();
            if (p != null && !p.isDirectory()) p.mkdirs();
            w = new OutputStreamWriter(new FileOutputStream(out), UTF8);
            w.write(report(d, maxKinds, maxPkgs, maxSyms));
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            AnalysisLog.close(w);
        }
    }

    // ---------------------------------------------------------------- 报告

    /**
     * @param d         解析结果
     * @param maxKinds  最多列几种对象类型（并列时按名字补全）
     * @param maxPkgs   最多列几个 Dart 包
     * @param maxSyms   最多列几条业务包符号
     */
    public static String report(Digest d, int maxKinds, int maxPkgs, int maxSyms) {
        StringBuilder sb = new StringBuilder();
        if (d == null || d.lines == 0) return "      (pp.txt 为空或读不到)\n";
        sb.append("  ◆ 结构化摘要（blutter 对象池 dump 的统计与符号索引）\n");
        if (!d.looksLikePp) {
            sb.append("      ⚠ 内容不像 blutter 的 pp.txt（没有 [pp+0x…] 条目）——\n");
            sb.append("        可能 blutter 版本与目标 Dart 不匹配，或产物被截断。\n");
            sb.append("        行数 ").append(fmt(d.lines)).append("，未识别 ").append(fmt(d.unrecognized)).append("\n");
            return sb.toString();
        }
        if (d.header.length() > 0) sb.append("      文件头 : ").append(d.header).append("\n");
        sb.append("      规模   : ").append(fmt(d.lines)).append(" 行 / ")
          .append(fmt(d.bytes / 1024)).append(" KB；识别 ")
          .append(fmt(d.recognized)).append(" 条，续行 ")
          .append(fmt(d.continuations)).append(" 条，未识别 ")
          .append(fmt(d.unrecognized)).append(" 条\n");

        sb.append("\n      ◆ 对象类型（这就是「函数地址 ↔ Dart 源位置」的载体）\n");
        List<Map.Entry<String, Integer>> ks = sorted(d.kinds);
        int kn = Math.min(ks.size(), maxKinds);
        int perLine = 0;
        for (int i = 0; i < kn; i++) {
            Map.Entry<String, Integer> e = ks.get(i);
            sb.append(perLine == 0 ? "      " : "  ")
              .append(padLeft(e.getKey(), 20)).append(" ")
              .append(padLeft(fmt(e.getValue().intValue()), 7));
            perLine++;
            if (perLine == 3) { sb.append("\n"); perLine = 0; }
        }
        if (perLine != 0) sb.append("\n");
        if (ks.size() > kn) sb.append("      …（其余 ").append(ks.size() - kn).append(" 种已省略）\n");

        sb.append("\n      ◆ 符号条目 : ").append(fmt(d.symbols.size()));
        if (d.symbols.size() >= MAX_SYMBOLS) sb.append("（已达收录上限）");
        sb.append(" 条带 (0x地址) —— 拿去 r2 就能按地址还原函数名\n");

        if (!d.packages.isEmpty()) {
            sb.append("\n      ◆ 按 Dart 包统计（前 ").append(Math.min(d.packages.size(), maxPkgs)).append("）\n");
            List<Map.Entry<String, Integer>> ps = sorted(d.packages);
            for (int i = 0; i < ps.size() && i < maxPkgs; i++) {
                Map.Entry<String, Integer> e = ps.get(i);
                sb.append("      ").append(padLeft(fmt(e.getValue().intValue()), 8)).append("  ")
                  .append(e.getKey());
                if (e.getKey().equals(d.topPackage)) sb.append("   ← 业务包（非框架，条目最多）");
                else if (isFramework(e.getKey())) sb.append("   (框架)");
                sb.append("\n");
            }
        }

        if (!d.appSymbols.isEmpty()) {
            sb.append("\n      ◆ 业务包 ").append(d.topPackage).append(" 的符号（前 ")
              .append(Math.min(d.appSymbols.size(), maxSyms)).append(" / ")
              .append(d.appSymbols.size()).append(" 条）\n");
            for (int i = 0; i < d.appSymbols.size() && i < maxSyms; i++) {
                Sym s = d.appSymbols.get(i);
                sb.append("      ").append(padLeft(s.addr.length() == 0 ? "(无)" : s.addr, 15))
                  .append("  ").append(s.pkgPath);
                if (s.name.length() > 0) sb.append("  ").append(s.name);
                sb.append("\n");
            }
            if (d.appSymbols.size() > maxSyms) {
                sb.append("      …（其余 ").append(d.appSymbols.size() - maxSyms)
                  .append(" 条在完整 pp.txt 里，用 read_log 分页读）\n");
            }
        }
        return sb.toString();
    }

    /** 供只想要一行结论的地方用 */
    public static String oneLine(Digest d) {
        if (d == null || d.lines == 0) return "(pp.txt 为空)";
        if (!d.looksLikePp) return "不像 pp.txt（" + fmt(d.lines) + " 行）";
        return fmt(d.lines) + " 行 / 识别 " + fmt(d.recognized) + " 条 / 符号 "
                + fmt(d.symbols.size()) + " 条 / 业务包 " + d.topPackage;
    }

    private static String fmt(long n) {
        return String.format("%,d", Long.valueOf(n));
    }

    private static String padLeft(String s, int w) {
        StringBuilder b = new StringBuilder();
        for (int i = s.length(); i < w; i++) b.append(' ');
        b.append(s);
        return b.toString();
    }
}
