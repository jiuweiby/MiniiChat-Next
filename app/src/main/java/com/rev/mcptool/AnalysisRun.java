package com.rev.mcptool;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「选一个文件 → 完整分析 → 落日志」的执行编排。
 *
 * 三个阶段：
 *   ① 包级画像（ApkAnalyzer）：结构 / 签名 / Manifest / 原生库清单 / Flutter 版本
 *   ② 原生库真分析（NativeAnalyzer + radare2）：结构 + 符号 + 字符串情报 + 按需深度函数分析
 *   ③ Flutter 符号还原（可选，blutter）：Dart AOT → 函数名/类结构/伪代码
 *
 * 界面与 MCP 工具（analyze_file）共用这段逻辑，保证两条路径产物一致。
 *
 * 阶段 ③ 通过内嵌的纯 Java 接口 {@link SymbolStage} 接入：真正的实现（BlutterRunner）
 * 依赖 android.content.Context，放在 android 侧适配，这样本类保持无 android 依赖，
 * 能直接在桌面回归里编译运行。
 *
 * 只读约束：不改动输入文件；只把主库（libapp.so / libflutter.so）留在工作目录
 * 供后续 r2_* 工具直接分析（报告里会写明路径，用户可自行删除），其余临时文件清理。
 */
public final class AnalysisRun {

    /** 进度回调（继承 NativeAnalyzer.Emit，便于直接透传给分析器） */
    public interface Emit extends NativeAnalyzer.Emit {
    }

    /**
     * 可选的「Flutter 符号还原」阶段。
     *
     * 之所以抽成接口而不是直接调 BlutterRunner：BlutterRunner 需要 Context 与
     * nativeLibraryDir，而本类是纯 Java、要被桌面回归直接编译运行。
     */
    public interface SymbolStage {
        /** 运行环境是否可用（无 Context / 未内置 blutter 二进制时返回 false） */
        boolean available();
        /** 阶段名（用于日志标题与进度播报） */
        String name();
        /** 单次执行的超时（秒）。Dart AOT 解析偏重，通常给 300s 以上。 */
        int timeoutSec();
        /**
         * 执行符号还原。
         *
         * @param libapp     libapp.so
         * @param libflutter 同目录 libflutter.so（可为 null，只用于取 Dart 版本）
         * @param outDir     产物目录
         * @return 报告文本（不得为 null）
         */
        String run(File libapp, File libflutter, File outDir, Emit emit);
    }

    /**
     * 逆向**必需**的核心库：Dart 代码（libapp.so）+ 引擎（libflutter.so）。
     * 这两个之外的东西，绝大多数与"看懂/破解这个 APK"无关。
     */
    private static final String[] CORE = {"libapp.so", "libflutter.so"};
    /** 最多实分析的库数量（核心库优先计入） */
    private static final int MAX_LIBS = 5;
    /**
     * 与逆向**无关**的库族：命中即不分析。
     *
     * 依据是"这个 .so 里有没有需要读的业务/防护逻辑"：
     * 媒体编解码（FFmpeg 全家桶）、图形、压缩、存储、基础运行库都是现成第三方实现，
     * 反汇编它们既看不出 App 逻辑，也找不到授权/校验点 —— 纯烧时间。
     */
    private static final String[] IRRELEVANT = {
            "libavcodec", "libavformat", "libavfilter", "libavutil", "libavdevice",
            "libswscale", "libswresample", "libffmpeg", "libx264", "libx265", "libopenh264",
            "libvpx", "libopus", "libvorbis", "libogg", "libflac", "libmp3lame", "libfdk-aac",
            "libEGL", "libGLESv", "libvulkan", "libskia", "libhwui", "libhwc",
            "libz.so", "libzstd", "liblz4", "libbrotli", "libbz2", "libminizip",
            "libc++_shared", "libandroid_support", "libjnigraphics", "libsqlite3", "libsqlcipher",
            "libmmkv", "libdatastore_shared", "libjsc", "libhermes", "libweex",
            "libcrashlytics", "libbugly", "libbreakpad", "libunwind", "libbacktrace",
    };
    /**
     * 命中即视为「与逆向破解相关」的第三方库：加固/壳、授权校验、加解密、
     * 网络协议、风控反调试。这些恰恰是破解时真正要找的东西。
     */
    private static final String[] RELEVANT_HINT = {
            "jiagu", "legu", "naga", "secneo", "bangcle", "packer", "protect", "shield",
            "guard", "doh", "safedk", "sec", "crypto", "cipher", "ssl", "tls", "sign",
            "license", "verify", "auth", "key", "risk", "anti", "detect", "hook",
            "net", "http", "curl", "socket", "vpn", "proxy", "native-lib", "jni",
    };

    public static final class Result {
        public String summary;
        public AnalysisLog.Block apkBlock;
        public AnalysisLog.Block nativeBlock;
        public AnalysisLog.Block symbolBlock;
        public File workDir;
        public int libsAnalyzed;
        public long elapsedMs;
    }

    private AnalysisRun() { }

    /** 兼容旧调用：不跑符号还原 */
    public static Result run(String filePath, File workParent, File logDir,
                             NativeAnalyzer.Runner runner, boolean wantNative,
                             boolean deep, Emit emit) {
        return run(filePath, workParent, logDir, runner, wantNative, deep, emit, null);
    }

    public static Result run(String filePath, File workParent, File logDir,
                             NativeAnalyzer.Runner runner, boolean wantNative,
                             boolean deep, Emit emit, SymbolStage stage) {
        return run(filePath, workParent, logDir, runner, wantNative, deep, emit, stage, new String[0]);
    }

    /**
     * @param extraLibs 用户显式要求一并分析的 .so leaf 名（默认策略会跳过它们时用）
     */
    public static Result run(String filePath, File workParent, File logDir,
                             NativeAnalyzer.Runner runner, boolean wantNative,
                             boolean deep, Emit emit, SymbolStage stage, String[] extraLibs) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        File f = new File(filePath);
        AnalysisLog log = new AnalysisLog(logDir);

        if (!f.isFile()) {
            r.summary = "❌ 文件不存在或不是普通文件: " + f.getAbsolutePath();
            r.elapsedMs = System.currentTimeMillis() - t0;
            return r;
        }

        // ---------- ① 包级画像 ----------
        say(emit, "① 包级画像 " + f.getName());
        String apkReport = ApkAnalyzer.analyze(filePath);
        try {
            r.apkBlock = log.append(AnalysisLog.ANALYSIS, "选文件分析 · 包级画像  " + f.getName(), apkReport);
        } catch (Exception e) {
            say(emit, "  !! 写日志失败: " + e);
        }

        // ---------- ② 原生库真分析 ----------
        StringBuilder note = new StringBuilder();
        if (wantNative && runner != null) {
            try {
                analyzeNative(f, workParent, log, runner, deep, emit, r, note,
                        extraLibs == null ? new String[0] : extraLibs);
            } catch (Exception e) {
                note.append("!! 原生库分析失败: ").append(e).append("\n");
                say(emit, "  !! 原生库分析失败: " + e);
            }
        } else if (!wantNative) {
            note.append("(未启用原生库分析)\n");
        } else {
            note.append("(radare2 运行时不可用，已跳过原生库分析)\n");
        }

        // ---------- ③ Flutter 符号还原（可选） ----------
        if (stage != null && stage.available()) {
            try {
                analyzeSymbols(f, workParent, log, stage, emit, r, note);
            } catch (Exception e) {
                note.append("!! Flutter 符号还原失败: ").append(e).append("\n");
                say(emit, "  !! Flutter 符号还原失败: " + e);
            }
        } else if (stage != null) {
            note.append("(blutter 运行环境不可用 —— 未内置 libblutter_*.so 或拿不到 nativeLibraryDir)\n");
        } else if (wantNative) {
            note.append("(未启用 Flutter 符号还原)\n");
        }

        r.elapsedMs = System.currentTimeMillis() - t0;

        // ---------- ③ 交接摘要 ----------
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 分析完成（耗时 ").append(r.elapsedMs / 1000).append(".")
          .append((r.elapsedMs % 1000) / 100).append("s）\n");
        sb.append("目标文件 : ").append(f.getAbsolutePath())
          .append("（").append(ApkAnalyzer.fmtSize(f.length())).append("）\n");
        if (r.apkBlock != null) {
            sb.append("包级画像 : 第 ").append(r.apkBlock.startLine).append("-").append(r.apkBlock.endLine)
              .append(" 行｜字节 ").append(r.apkBlock.startByte).append("-").append(r.apkBlock.endByte).append("\n");
        }
        if (r.nativeBlock != null) {
            sb.append("原生分析 : 第 ").append(r.nativeBlock.startLine).append("-").append(r.nativeBlock.endLine)
              .append(" 行｜字节 ").append(r.nativeBlock.startByte).append("-").append(r.nativeBlock.endByte)
              .append("（").append(r.libsAnalyzed).append(" 个 .so）\n");
        }
        if (r.symbolBlock != null) {
            sb.append("符号还原 : 第 ").append(r.symbolBlock.startLine).append("-").append(r.symbolBlock.endLine)
              .append(" 行｜字节 ").append(r.symbolBlock.startByte).append("-").append(r.symbolBlock.endByte)
              .append("（").append(stage == null ? "blutter" : stage.name()).append("）\n");
        }
        sb.append("日志文件 : ").append(log.path(AnalysisLog.ANALYSIS)).append("\n");
        if (r.apkBlock != null) {
            sb.append("读包级画像: read_log {\"offset\":").append(r.apkBlock.startByte)
              .append(",\"max_bytes\":20000}\n");
        }
        if (r.nativeBlock != null) {
            sb.append("读原生分析: read_log {\"offset\":").append(r.nativeBlock.startByte)
              .append(",\"max_bytes\":40000}\n");
        }
        if (r.symbolBlock != null) {
            sb.append("读符号还原: read_log {\"offset\":").append(r.symbolBlock.startByte)
              .append(",\"max_bytes\":60000}\n");
        }
        if (note.length() > 0) sb.append(note);
        r.summary = sb.toString();
        return r;
    }

    // ---------------- 原生库分析 ----------------

    private static void analyzeNative(File f, File workParent, AnalysisLog log,
                                      NativeAnalyzer.Runner runner, boolean deep, Emit emit,
                                      Result r, StringBuilder note, String[] extra) throws Exception {
        // 1) 目标库清单
        List<File> targets = new ArrayList<File>();
        boolean isElf = isElf(f);
        File work = null;
        Pick pk = null;
        List<File> kept = new ArrayList<File>();
        List<File> temp = new ArrayList<File>();
        try {
            if (isElf) {
                targets.add(f);
                say(emit, "② 原生库真分析：输入本身是 .so");
            } else {
                List<String[]> all = ApkAnalyzer.listNativeLibs(f);
                // 区分"无权读"和"包里没有 so"：__PERMISSION_DENIED__/__READ_ERROR__ 是标记行
                String permErr = null;
                List<String[]> libs = new ArrayList<String[]>();
                for (String[] row : all) {
                    if ("__PERMISSION_DENIED__".equals(row[0])) permErr = row[1];
                    else if ("__READ_ERROR__".equals(row[0])) permErr = row[1];
                    else libs.add(row);
                }
                if (permErr != null) {
                    note.append("!! 读取 APK 失败: ").append(permErr)
                          .append("\n   请先把 APK 拷到 RevMCP 可写目录（copy_to_input），"
                                  + "再用副本路径调 analyze_file\n");
                    return;
                }
                if (libs.isEmpty()) {
                    note.append("(该 APK 内没有 lib/<abi>/*.so，跳过原生分析)\n");
                    return;
                }
                List<String[]> srcLibs = libs;
                work = new File(workParent, safeName(f.getName()));
                work.mkdirs();
                pk = pick(srcLibs, extra);
                say(emit, "② 原生库真分析：共 " + srcLibs.size() + " 个，"
                        + "只分析 " + pk.targets.size() + " 个（逆向相关），跳过 " + pk.skipped.size() + " 个");
                for (String[] row : pk.targets) {
                    String entry = row[0];
                    File out = ApkAnalyzer.extractEntry(f, entry, work);
                    if (out == null) continue;
                    targets.add(out);
                    kept.add(out);   // 分析过的都是有价值的，全部保留供后续 r2_* 直接分析
                }
            }

            if (targets.isEmpty()) {
                note.append("(未能解出任何 .so)\n");
                return;
            }

            // 2) 逐个分析（核心库做深度分析）
            StringBuilder rep = new StringBuilder();
            rep.append("■ 原生库分析（radare2 ").append(targets.size()).append(" 个库）");
            rep.append("  —— 只分析逆向破解用得到的库\n");
            if (work != null) {
                rep.append("■ 工作目录 : ").append(work.getAbsolutePath()).append("\n");
                rep.append("  分析并保留 : ");
                for (File k : kept) rep.append(k.getName()).append(' ');
                rep.append("（供后续 r2_* 工具直接分析，可随时删除）\n");
            }
            if (pk != null) {
                if (!pk.core.isEmpty()) {
                    rep.append("  ★ 核心 : ");
                    for (String[] c : pk.core) rep.append(leafOf(c[0])).append(' ');
                    rep.append("（Dart 代码 + 引擎，必分析）\n");
                }
                if (!pk.related.isEmpty()) {
                    rep.append("  相关 : ");
                    for (String[] c : pk.related) rep.append(leafOf(c[0])).append(' ');
                    rep.append("（命中加固/授权/加密/网络/风控关键词）\n");
                }
                if (!pk.skipped.isEmpty()) {
                    rep.append("  已跳过 : ").append(pk.skipped.size()).append(" 个\n");
                    for (String[] s : pk.skipped) {
                        rep.append("    - ").append(leafOf(s[0])).append("  ").append(s[2]).append("\n");
                    }
                }
            }
            rep.append("\n");

            int done = 0;
            for (File so : targets) {
                done++;
                say(emit, "   (" + done + "/" + targets.size() + ") " + so.getName());
                boolean deepThis = deep && (isPrimary(so.getName()) || targets.size() == 1);
                rep.append("──────────────────────────────────────────\n");
                try {
                    rep.append(NativeAnalyzer.analyzeSo(runner, so, deepThis, emit));
                } catch (Exception e) {
                    rep.append("!! ").append(so.getName()).append(" 分析失败: ").append(e).append("\n");
                }
                rep.append("\n");
                r.libsAnalyzed++;
            }

            r.nativeBlock = log.append(AnalysisLog.ANALYSIS,
                    "选文件分析 · 原生库 r2 分析  " + f.getName(), rep.toString());
        } finally {
            // 只保留主库，其余临时提取物删掉（不打扰用户磁盘）
            for (File t : temp) {
                try { t.delete(); } catch (Exception ignore) { /* ignore */ }
            }
        }
    }

    // ---------------- Flutter 符号还原（阶段 ③） ----------------

    /**
     * 找出 libapp.so 并对它做符号还原。
     *
     * 只在目标确实是 Flutter 应用时执行；产物落在工作目录下的 blutter_out/，
     * 报告本身按块写进 analysis.log（含 pp.txt 开头若干 KB），
     * 完整 pp.txt 路径也会写进报告，便于客户端或用户直接取用。
     */
    private static void analyzeSymbols(File f, File workParent, AnalysisLog log,
                                       SymbolStage stage, Emit emit, Result r,
                                       StringBuilder note) throws Exception {
        File libapp = null;
        File libflutter = null;

        if (isElf(f)) {
            if (!"libapp.so".equals(f.getName())) {
                note.append("(输入是单个 .so 且不是 libapp.so，跳过 Flutter 符号还原)\n");
                return;
            }
            libapp = f;
            File sib = f.getParentFile() == null ? null : new File(f.getParentFile(), "libflutter.so");
            if (sib != null && sib.isFile()) libflutter = sib;
        } else {
            String appEntry = findLibEntry(f, "libapp.so");
            if (appEntry == null) {
                note.append("(APK 内没有 lib/<abi>/libapp.so —— 非 Flutter 应用，或 debug 构建走 "
                        + "kernel_blob.bin 无 AOT snapshot，跳过符号还原)\n");
                return;
            }
            File work = new File(workParent, safeName(f.getName()));
            work.mkdirs();
            libapp = new File(work, "libapp.so");
            if (!libapp.isFile()) {
                File x = ApkAnalyzer.extractEntry(f, appEntry, work);
                if (x == null) {
                    note.append("(解出 libapp.so 失败: ").append(appEntry).append(")\n");
                    return;
                }
                libapp = x;
            }
            String fltEntry = findLibEntry(f, "libflutter.so");
            if (fltEntry != null) {
                libflutter = new File(work, "libflutter.so");
                if (!libflutter.isFile()) {
                    File x = ApkAnalyzer.extractEntry(f, fltEntry, work);
                    if (x != null) libflutter = x; else libflutter = null;
                }
            }
        }

        say(emit, "③ Flutter 符号还原（" + stage.name() + "，超时 " + stage.timeoutSec() + "s）");
        File outDir = new File(libapp.getParentFile(), "blutter_out");

        // 产物复用：blutter 跑一次要几十秒到几分钟，同一个 so 反复跑纯属浪费。
        // 指纹（libapp.so 的 SHA-256）一致且产物还在 → 直接跳过。
        String rep;
        File reuseTsv = BlutterCache.reusable(libapp, outDir);
        if (reuseTsv != null) {
            say(emit, "   命中产物缓存（libapp.so 指纹与上次一致），跳过 blutter 直接复用");
            rep = BlutterCache.reuseNote(reuseTsv);
        } else {
            rep = stage.run(libapp, libflutter, outDir, emit);
            // 跑完记下指纹，供下次判断能否复用
            if (BlutterCache.findTsv(outDir) != null) {
                BlutterCache.mark(libapp, outDir, null, 0);
            }
        }
        if (rep == null) rep = "(无报告)\n";
        r.symbolBlock = log.append(AnalysisLog.ANALYSIS,
                "选文件分析 · " + stage.name() + "  " + f.getName(), rep);
    }

    /** 在 APK 里找 lib/<abi>/&lt;leaf&gt;，优先 arm64-v8a */
    private static String findLibEntry(File apk, String leaf) {
        List<String[]> all = ApkAnalyzer.listNativeLibs(apk);
        String fallback = null;
        for (String[] row : all) {
            String e = row[0];
            if (!e.endsWith("/" + leaf)) continue;
            if (e.contains("arm64")) return e;
            if (fallback == null) fallback = e;
        }
        return fallback;
    }

    /** 选库结果：分析哪些、跳过哪些、以及每个库为什么被跳过 */
    public static final class Pick {
        /** 实际要分析的库（core + related + 用户指定） */
        public final List<String[]> targets = new ArrayList<String[]>();
        /** 跳过的库：[entry, size, 原因] */
        public final List<String[]> skipped = new ArrayList<String[]>();
        /** 核心库（libapp / libflutter） */
        public final List<String[]> core = new ArrayList<String[]>();
        /** 因命中"加固/授权/加密/网络/风控"关键词而纳入的第三方库 */
        public final List<String[]> related = new ArrayList<String[]>();
    }

    /** 兼容旧调用：只关心要分析哪些库 */
    public static List<String[]> pick(List<String[]> all) {
        return pick(all, new String[0]).targets;
    }

    /**
     * 选库：**只挑逆向破解真正要用到的**。
     *
     * <p>规则（前两条是硬规则，第三条是默认保守）：
     * <ol>
     *   <li>{@link #CORE} 里的 libapp.so / libflutter.so 必选 —— Dart 代码与引擎；</li>
     *   <li>名称命中 {@link #RELEVANT_HINT}（加固/授权/加密/网络/风控）的第三方库纳入，
     *       最多补到 {@link #MAX_LIBS} 个 —— 破解要找的校验点常在这类库里；</li>
     *   <li>名称命中 {@link #IRRELEVANT}（媒体/图形/压缩/存储/基础库）的直接跳过并写明原因；</li>
     *   <li>其余叫不上来的第三方库**默认不分析**，但要能被 {@code extra_libs} 显式捞回来。</li>
     * </ol>
     *
     * <p>同一个 leaf 名只留一个条目，arm64 优先。
     *
     * @param extra 用户显式要求分析的 leaf 名（可为空数组）
     */
    public static Pick pick(List<String[]> all, String[] extra) {
        Pick p = new Pick();
        Map<String, String[]> best = new LinkedHashMap<String, String[]>();
        for (String[] r : all) {
            String leaf = leafOf(r[0]);
            String[] cur = best.get(leaf);
            if (cur == null) {
                best.put(leaf, r);
            } else if (r[0].contains("arm64") && !cur[0].contains("arm64")) {
                best.put(leaf, r);
            }
        }

        Set<String> chosen = new HashSet<String>();
        for (String c : CORE) {
            String[] r = best.get(c);
            if (r != null) { p.core.add(r); p.targets.add(r); chosen.add(r[0]); }
        }
        if (extra != null) {
            for (String leaf : extra) {
                String[] r = best.get(leafOf(leaf));
                if (r != null && !chosen.contains(r[0])) {
                    p.related.add(r); p.targets.add(r); chosen.add(r[0]);
                }
            }
        }
        int room = MAX_LIBS - p.targets.size();
        for (Map.Entry<String, String[]> e : best.entrySet()) {
            String[] r = e.getValue();
            if (chosen.contains(r[0])) continue;
            if (matches(e.getKey(), CORE)) continue;
            if (matches(e.getKey(), IRRELEVANT)) {
                p.skipped.add(new String[]{r[0], r[1], "与逆向无关（媒体/图形/压缩/存储/基础库）"});
            } else if (p.related.size() < room && matches(e.getKey(), RELEVANT_HINT)) {
                p.related.add(r); p.targets.add(r); chosen.add(r[0]);
            } else {
                p.skipped.add(new String[]{r[0], r[1], "第三方库，默认不分析（需要时用 extra_libs 指定）"});
            }
        }
        return p;
    }

    private static String leafOf(String entryOrName) {
        String n = entryOrName == null ? "" : entryOrName;
        int i = n.lastIndexOf('/');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    /** leaf 名是否命中某个模式（忽略大小写的子串匹配） */
    private static boolean matches(String leaf, String[] pats) {
        String low = leaf.toLowerCase();
        for (String pat : pats) if (low.contains(pat.toLowerCase())) return true;
        return false;
    }

    /** 是否核心库（libapp.so / libflutter.so） */
    public static boolean isPrimary(String entryOrName) {
        String n = entryOrName;
        int i = n.lastIndexOf('/');
        if (i >= 0) n = n.substring(i + 1);
        for (String p : CORE) if (n.equals(p)) return true;
        return false;
    }

    private static String safeName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') sb.append(c);
            else sb.append('_');
        }
        return sb.length() == 0 ? "target" : sb.toString();
    }

    private static boolean isElf(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[4];
            if (in.read(b) != 4) return false;
            return (b[0] & 0xff) == 0x7f && b[1] == 'E' && b[2] == 'L' && b[3] == 'F';
        } catch (Exception e) {
            return false;
        } finally {
            AnalysisLog.close(in);
        }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    private static void say(Emit e, String msg) {
        if (e != null) e.emit(msg);
    }
}
