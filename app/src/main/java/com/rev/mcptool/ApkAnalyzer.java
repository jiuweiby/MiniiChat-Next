package com.rev.mcptool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * APK / .so 全景分析（**纯 Java，零 android.* 依赖**，可在桌面 JVM 原样验证）。
 *
 * 这是界面上「选择文件 → 分析」的执行体：输入一个本地文件路径，
 * 输出一份结构化报告 —— 既能直接给人看，也能整篇落进 analysis.log
 * 供 AI 客户端用 read_log 分页读取，再据此调用其他工具改包。
 *
 * 只读保证：不改动输入文件；为探测 Flutter 而做的临时提取目录在分析结束后删除。
 */
public final class ApkAnalyzer {

    /** 列表类输出的上限，避免报告过长（超出会提示剩余数量） */
    private static final int MAX_LIST = 40;

    public static String analyze(String filePath) {
        StringBuilder sb = new StringBuilder();
        File f = new File(filePath);

        sb.append("■ 目标文件\n");
        sb.append("  路径    : ").append(f.getAbsolutePath()).append("\n");
        sb.append("  名称    : ").append(f.getName()).append("\n");
        if (!f.isFile()) {
            sb.append("  !! 文件不存在或不是普通文件\n");
            return sb.toString();
        }
        sb.append("  大小    : ").append(f.length()).append(" bytes (").append(fmtSize(f.length())).append(")\n");
        try {
            sb.append("  SHA-256 : ").append(sha256(f)).append("\n");
        } catch (Exception e) {
            sb.append("  SHA-256 : (计算失败: ").append(e).append(")\n");
        }
        sb.append("\n");

        byte[] head = readHead(f, 4);
        if (isZip(head)) {
            analyzeApk(f, sb);
        } else if (isElf(head)) {
            analyzeElf(f, sb);
        } else {
            sb.append("■ 类型\n  未识别的格式（既不是 ZIP/APK，也不是 ELF）。\n")
              .append("  前 4 字节: ").append(hex(head)).append("\n");
        }
        return sb.toString();
    }

    // ---------------- APK ----------------

    private static void analyzeApk(File apk, StringBuilder sb) {
        // 1) 条目扫描
        int total = 0, stored = 0, deflated = 0;
        long uncompressed = 0;
        Map<String, int[]> byTop = new LinkedHashMap<String, int[]>();
        List<String[]> nativeLibs = new ArrayList<String[]>();
        String flutterEntry = null, appEntry = null;

        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new FileInputStream(apk));
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (!e.isDirectory()) {
                    total++;
                    if (e.getMethod() == ZipEntry.STORED) stored++; else deflated++;
                    if (e.getSize() > 0) uncompressed += e.getSize();

                    String top = name.indexOf('/') >= 0 ? name.substring(0, name.indexOf('/')) : "(root)";
                    int[] c = byTop.get(top);
                    if (c == null) { c = new int[1]; byTop.put(top, c); }
                    c[0]++;

                    if (name.startsWith("lib/") && name.endsWith(".so")) {
                        String abi = name.split("/").length > 2 ? name.split("/")[1] : "?";
                        nativeLibs.add(new String[]{abi, name, String.valueOf(e.getSize())});
                        boolean arm64 = name.contains("arm64-v8a");
                        if (name.endsWith("/libflutter.so") && (flutterEntry == null || arm64))
                            flutterEntry = name;
                        if (name.endsWith("/libapp.so") && (appEntry == null || arm64))
                            appEntry = name;
                    }
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            sb.append("■ 结构\n  !! 读取 ZIP 结构失败: ").append(e).append("\n");
        } finally {
            AnalysisLog.close(zis);
        }

        sb.append("■ 条目结构\n");
        sb.append("  条目总数 : ").append(total)
          .append("（STORED ").append(stored).append(" / DEFLATE ").append(deflated).append("）\n");
        sb.append("  解压合计 : ").append(uncompressed).append(" bytes (").append(fmtSize(uncompressed)).append(")\n");
        sb.append("  按目录分类:\n");
        int shown = 0;
        for (Map.Entry<String, int[]> en : byTop.entrySet()) {
            if (shown++ >= MAX_LIST) {
                sb.append("    … 其余 ").append(byTop.size() - MAX_LIST).append(" 个目录已省略\n");
                break;
            }
            sb.append("    ").append(pad(en.getKey(), 20)).append(en.getValue()[0]).append(" 个\n");
        }
        sb.append("\n");

        // 2) 签名
        sb.append("■ 签名校验（内置 apksig）\n");
        String v = ApkPack.verifyApk(apk.getAbsolutePath());
        sb.append(indent(v));
        sb.append("\n");

        // 3) AndroidManifest
        sb.append("■ AndroidManifest.xml\n");
        try {
            byte[] axml = ApkPack.readEntry(apk.getAbsolutePath(), "AndroidManifest.xml");
            String xml = AxmReader.decodeForOutput(axml);
            sb.append(summarizeManifest(xml));
        } catch (Exception e) {
            sb.append("  !! 解码失败: ").append(e).append("\n");
        }
        sb.append("\n");

        // 4) 原生库
        sb.append("■ 原生库（lib/<abi>/*.so）\n");
        if (nativeLibs.isEmpty()) {
            sb.append("  无\n");
        } else {
            Map<String, List<String[]>> byAbi = new LinkedHashMap<String, List<String[]>>();
            for (String[] r : nativeLibs) {
                List<String[]> l = byAbi.get(r[0]);
                if (l == null) { l = new ArrayList<String[]>(); byAbi.put(r[0], l); }
                l.add(r);
            }
            for (Map.Entry<String, List<String[]>> en : byAbi.entrySet()) {
                sb.append("  ").append(en.getKey()).append("（").append(en.getValue().size()).append(" 个）\n");
                int c = 0;
                for (String[] r : en.getValue()) {
                    if (c++ >= MAX_LIST) {
                        sb.append("    … 还有 ").append(en.getValue().size() - MAX_LIST).append(" 个\n");
                        break;
                    }
                    sb.append("    ").append(pad(basename(r[1]), 28)).append(fmtSize(parseLong(r[2], 0))).append("\n");
                }
            }
        }
        sb.append("\n");

        // 5) Flutter 探测
        sb.append("■ Flutter 特征\n");
        if (flutterEntry == null && appEntry == null) {
            sb.append("  未发现 libflutter.so / libapp.so —— 该 APK 不是 Flutter 应用（或原生库已剥离）。\n");
        } else {
            sb.append("  发现: ")
              .append(flutterEntry == null ? "-" : flutterEntry).append("\n        ")
              .append(appEntry == null ? "-" : appEntry).append("\n");
            File tmp = new File(apk.getParentFile(), apk.getName() + ".probe_tmp");
            try {
                File lf = extract(apk, flutterEntry, tmp);
                File la = extract(apk, appEntry, tmp);
                DartInfo di = DartInfo.analyze(la, lf);
                sb.append(indent(di.describe() == null ? "(无结论)" : di.describe()));
                // Dart AOT 的下一步只有一个方向：符号还原。r2 对 libapp.so 的线性分析
                // 只能给出 entry0 一个入口（真机实测），函数边界必须靠 blutter 的产物。
                if (la != null && di.dartVersion != null) {
                    String p0 = apk.getAbsolutePath();
                    sb.append("\n  → 符号还原 : 这是 Dart AOT 包，函数名/类结构/伪代码要靠 blutter 还原\n");
                    sb.append("    blutter_run {\"apk_path\":\"").append(p0).append("\"}\n");
                    sb.append("    版本按 Dart ").append(di.dartVersion)
                      .append(" 自动挑选（blutter_doctor 可看内置清单）\n");
                }
            } catch (Exception e) {
                sb.append("  !! 探测失败: ").append(e).append("\n");
            } finally {
                deleteTree(tmp);
            }
        }
        sb.append("\n");

        // 6) 下一步
        sb.append("■ 建议的后续分析（用本 MCP 的只读工具，结果同样落日志）\n");
        String p = apk.getAbsolutePath();
        sb.append("  r2_info        {\"path\":\"").append(p).append("\"}   概览\n");
        if (appEntry != null) {
            sb.append("  unpack_apk_libs{\"apk_path\":\"").append(p)
              .append("\",\"out_dir\":\"<可写目录>\"}   解包 .so 后再 r2_functions/r2_strings 分析\n");
            sb.append("  extract_entry  {\"apk_path\":\"").append(p)
              .append("\",\"entry\":\"").append(appEntry).append("\",\"out_dir\":\"<可写目录>\"}\n");
        }
        sb.append("  list_apk_entries {\"apk_path\":\"").append(p).append("\"}   条目明细（支持名称过滤）\n");
        if (appEntry != null) {
            sb.append("  blutter_run    {\"apk_path\":\"").append(p)
              .append("\"}   Dart AOT 符号还原（Flutter 包的核心一步，先跑这个）\n");
            sb.append("  blutter_doctor {}   看内置 blutter 版本清单与依赖是否齐备\n");
        }
    }

    /** 从解码后的 manifest 文本里挑关键字段，避免整篇 XML 淹没日志 */
    private static String summarizeManifest(String xml) {
        StringBuilder sb = new StringBuilder();
        if (xml == null || xml.length() == 0) return "  (空)\n";

        attr(sb, xml, "包名        ", "package");
        attr(sb, xml, "versionName ", "versionName");
        attr(sb, xml, "versionCode ", "versionCode");
        attr(sb, xml, "minSdk      ", "minSdkVersion");
        attr(sb, xml, "targetSdk   ", "targetSdkVersion");
        attr(sb, xml, "compileSdk  ", "compileSdkVersion");
        attr(sb, xml, "extractNativeLibs", "extractNativeLibs");
        // <application> 的属性只在该标签段内取，否则 firstValue 会抓到前面 uses-permission 的 android:name
        String appTag = firstTag(xml, "application");
        if (appTag != null && appTag.startsWith("<application")) {
            String[] keys = {"android:label", "android:debuggable", "usesCleartextTraffic",
                             "android:networkSecurityConfig"};
            for (String k : keys) {
                String v = firstValue(appTag, k);
                if (v != null && v.length() > 0) {
                    sb.append("  ").append(pad(k, 20)).append(v).append("\n");
                }
            }
        }

        int perms = countOf(xml, "uses-permission");
        sb.append("  权限数量    : ").append(perms).append("\n");
        List<String> pl = grepValues(xml, "uses-permission", "android:name");
        int c = 0;
        for (String s : pl) {
            if (c++ >= MAX_LIST) { sb.append("    … 还有 ").append(pl.size() - MAX_LIST).append(" 个\n"); break; }
            sb.append("    ").append(s).append("\n");
        }

        sb.append("  组件数量    : activity ").append(countOf(xml, "<activity"))
          .append(" / service ").append(countOf(xml, "<service"))
          .append(" / receiver ").append(countOf(xml, "<receiver"))
          .append(" / provider ").append(countOf(xml, "<provider")).append("\n");
        sb.append("  uses-sdk    : ").append(firstTag(xml, "uses-sdk")).append("\n");
        return sb.toString();
    }

    // ---------------- 裸 ELF（.so） ----------------

    private static void analyzeElf(File so, StringBuilder sb) {
        sb.append("■ 类型\n  ELF 二进制（.so / 可执行体）\n\n");
        try {
            DartInfo di = DartInfo.analyze(so, so);
            sb.append("■ Dart / Flutter 特征\n");
            sb.append(indent(di.describe() == null ? "(无结论)" : di.describe()));
            sb.append("\n\n");
        } catch (Exception e) {
            sb.append("■ Dart / Flutter 特征\n  !! 探测失败: ").append(e).append("\n\n");
        }
        String p = so.getAbsolutePath();
        sb.append("■ 建议的后续分析\n");
        sb.append("  r2_info     {\"path\":\"").append(p).append("\"}\n");
        sb.append("  r2_functions{\"path\":\"").append(p).append("\"}\n");
        sb.append("  r2_strings  {\"path\":\"").append(p).append("\",\"filter\":\"dart\"}\n");
        sb.append("  r2_symbols  {\"path\":\"").append(p).append("\"}\n");
    }

    // ---------------- 对外只读接口（供分析编排层复用） ----------------

    /**
     * 列出 APK 内所有 lib/&lt;abi&gt;/*.so。
     * @return 每行 { 条目名, 解压后字节数 }
     */
    public static List<String[]> listNativeLibs(File apk) {
        List<String[]> out = new ArrayList<String[]>();
        // 预检：如果文件明显不存在（路径不存在），直接返回空
        // 注意：Android 上 EACCES 时 exists() 可能也返回 false，
        // 所以用 listFiles 的异常来判断，不能只看 exists()
        if (!apk.exists() && apk.length() == 0) {
            // 路径本身就不存在（不是权限问题），保持空列表
            // 调用方看到空列表就会说"没有 so"——这是正确的
            return out;
        }
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new FileInputStream(apk));
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (!e.isDirectory() && name.startsWith("lib/") && name.endsWith(".so")) {
                    out.add(new String[]{name, String.valueOf(e.getSize())});
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            // 文件"应该存在"（上面预检没拦住），但读失败了
            // 可能是 EACCES（宿主目录权限）或 ZIP 损坏
            out.clear();
            out.add(new String[]{"__READ_ERROR__",
                    "读取 " + apk.getAbsolutePath() + " 失败: " + e
                    + "\n（如果是 EACCES，请先把 APK 拷到 RevMCP 可写目录）"});
        } finally {
            AnalysisLog.close(zis);
        }
        return out;
    }

    /**
     * 把 APK 内指定条目解压到 outDir（只读：不改动 APK）。
     * @return 解出的文件；条目不存在返回 null
     */
    public static File extractEntry(File apk, String entry, File outDir) {
        try {
            return extract(apk, entry, outDir);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 工具 ----------------

    private static File extract(File apk, String entry, File outDir) throws IOException {
        if (entry == null) return null;
        outDir.mkdirs();
        File out = new File(outDir, basename(entry));
        ZipInputStream zis = new ZipInputStream(new FileInputStream(apk));
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().equals(entry)) {
                    FileOutputStream fos = new FileOutputStream(out);
                    try {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    } finally {
                        fos.close();
                    }
                    return out;
                }
                zis.closeEntry();
            }
        } finally {
            AnalysisLog.close(zis);
        }
        return null;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        try { f.delete(); } catch (Exception ignore) { /* ignore */ }
    }

    private static void attr(StringBuilder sb, String xml, String label, String key) {
        String v = firstValue(xml, key);
        if (v != null && v.length() > 0) {
            sb.append("  ").append(pad(label, 20)).append(v).append("\n");
        }
    }

    /** 找形如 android:key="value" 的第一个值 */
    static String firstValue(String xml, String key) {
        String needle = key + "=\"";
        int i = xml.indexOf(needle);
        if (i < 0) return null;
        int s = i + needle.length();
        int e = xml.indexOf('"', s);
        return e > s ? xml.substring(s, e) : null;
    }

    static String firstTag(String xml, String tag) {
        int i = xml.indexOf("<" + tag);
        if (i < 0) return "(未找到)";
        int e = xml.indexOf('>', i);
        return e > i ? xml.substring(i, e + 1).trim() : "(未找到)";
    }

    static int countOf(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    /** 抓取 <uses-permission android:name="X"/> 里的 X 列表 */
    static List<String> grepValues(String xml, String tag, String key) {
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (true) {
            int t = xml.indexOf("<" + tag, i);
            if (t < 0) break;
            int e = xml.indexOf("/>", t);
            if (e < 0) e = xml.indexOf(">", t);
            if (e < 0) break;
            String seg = xml.substring(t, e);
            String v = firstValue("<" + seg, key);
            if (v == null) v = firstValue(seg, key);
            if (v != null && v.length() > 0) out.add(v);
            i = e + 1;
        }
        return out;
    }

    private static boolean isZip(byte[] h) {
        return h.length >= 2 && (h[0] & 0xff) == 0x50 && (h[1] & 0xff) == 0x4b;
    }

    private static boolean isElf(byte[] h) {
        return h.length >= 4 && (h[0] & 0xff) == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F';
    }

    private static byte[] readHead(File f, int n) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[n];
            int r = in.read(b);
            if (r <= 0) return new byte[0];
            byte[] o = new byte[r];
            System.arraycopy(b, 0, o, 0, r);
            return o;
        } catch (Exception e) {
            return new byte[0];
        } finally {
            AnalysisLog.close(in);
        }
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        return ApkPack.hex(md.digest());
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static String fmtSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024L * 1024) return String.format("%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format("%.1f MB", n / 1048576.0);
        return String.format("%.2f GB", n / 1073741824.0);
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }

    private static String basename(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static String pad(String s, int w) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder(s);
        while (b.length() < w) b.append(' ');
        return b.toString();
    }

    private static String indent(String block) {
        if (block == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = block.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append("  ").append(lines[i]);
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString();
    }
}
