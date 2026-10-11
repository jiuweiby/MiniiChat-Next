package com.rev.mcptool;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Blutter（Dart AOT 符号还原）的手机端桥接。
 *
 * 二进制来源与 Blutter GUI 1.0.0 一致：预编译 blutter 以 libblutter_&lt;major&gt;_&lt;minor&gt;.so
 * 的形式放进 APK 的 lib/arm64-v8a/，由系统在安装期解压到 nativeLibraryDir。
 * 那个目录是 Android 10+ 上**唯一**能 exec 的位置（app 私有目录被 SELinux 禁止执行），
 * 而其中的条目名必须匹配 lib*.so —— 这就是"可执行文件叫 .so"的原因。
 *
 * 依赖（libcapstone.so / libicuuc.so / libc++_shared.so）同样入库，
 * 且这些 blutter 二进制的 RUNPATH 是 $ORIGIN，因此同目录即可解析，无需额外设置。
 *
 * 调用约定照抄官方 blutter.py：
 *     &lt;blutter&gt; -i &lt;libapp.so&gt; -o &lt;outdir&gt;
 * libflutter.so 不参与执行，只用来取 Dart 版本以选择二进制。
 */
public class BlutterRunner {
    public static final String TAG = "RevMCP";

    public interface Progress { void onProgress(int pct, String msg); }

    /** 最多试跑几个候选（同 minor 多 tag 时才有意义） */
    private static final int MAX_TRY = 2;
    /** 与 Blutter GUI 的核心产物一致 */
    private static final String PP = "pp.txt";

    private final Context ctx;

    public BlutterRunner(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public File getBinDir() {
        try {
            String nld = ctx.getApplicationInfo().nativeLibraryDir;
            if (nld != null) return new File(nld);
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    /** 已入库的 blutter 二进制名（nativeLibraryDir 里符合命名规则的那些） */
    public List<String> listBinaries() {
        List<String> out = new ArrayList<String>();
        File d = getBinDir();
        if (d == null) return out;
        String[] fs = d.list();
        if (fs == null) return out;
        for (String f : fs) if (BlutterPick.parse(f) != null) out.add(f);
        Collections.sort(out);
        return out;
    }

    public static final class Outcome {
        /** 真的跑通（退出码 0 且出产物）的那个；全都失败时为 null */
        public String binary;
        /** 最后一次试跑的二进制名（诊断用 —— 失败时看的就是它） */
        public String lastTried;
        public int exitCode = -1;
        public long elapsedMs;
        public String output = "";
        public boolean ok;
        public File outDir;
        public final List<String> tried = new ArrayList<String>();
        /** 与 tried 一一对应的退出码（-2 = 超时被强杀） */
        public final List<Integer> triedExit = new ArrayList<Integer>();
        /** 与 tried 一一对应的运行输出（用于判定 exit 0 但抛异常的情况） */
        public final List<String> triedOutput = new ArrayList<String>();
        /** 快照版本哈希失配 {expected, found}；非此类失败时 null */
        public String[] snapshotMismatch;
        /** 是否通过「放宽版本校验」重试跑通（同 minor 不同 commit 的兜底） */
        public boolean relaxed;
        /** 放宽重试走的是哪条执行路径（memfd / tmpfile）—— 成功时给调用方看 */
        public String relaxHow;
        /** 放宽重试的说明（成功时给调用方看） */
        public String relaxNote;
        /** 放宽重试失败的具体原因（诊断用；成功时为 null） */
        public String relaxFail;
        public String error;                        // 无法执行时的原因
        /** 依赖闭包检查结果（可能为 null） */
        public Closure closure;
        /** 我们提炼出来的符号索引（dart_symbols.tsv）；没产出时为 null */
        public File symbols;
        /** 可读摘要（dart_symbols.txt）；没产出时为 null */
        public File digest;
        /** 写入符号索引的条数（-1 = 失败） */
        public int symbolCount = -1;
        /** 符号条目最多的 Dart 包（业务包） */
        public String topPackage = "";
    }

    /**
     * 跑 blutter。按候选顺序试跑（最多 {@link #MAX_TRY} 个），
     * 判成功的条件：退出码 0 且输出目录里出现了产物。
     */
    public Outcome analyze(File libapp, File outDir, String dartVersion,
                           int timeoutSec, Progress p) {
        Outcome o = new Outcome();
        o.outDir = outDir;
        long t0 = System.currentTimeMillis();

        File binDir = getBinDir();
        if (binDir == null) { o.error = "拿不到 nativeLibraryDir"; return o; }

        List<String> names = listBinaries();
        if (names.isEmpty()) {
            o.error = "APK 内未内置 blutter 二进制（libblutter_*.so）";
            return o;
        }
        // 依赖闭包 —— 缺一个就必然 CANNOT LINK EXECUTABLE，
        // 这里先算好挂到结果上，让报告能直接给出根因（而不是让用户去猜链接错误）
        o.closure = closureOf(binDir, names);

        List<BlutterPick.Ver> cand = BlutterPick.candidates(names, dartVersion);
        cand = refine(cand, dartVersion);
        for (BlutterPick.Ver v : cand) o.tried.add(v.name);
        if (cand.isEmpty()) {
            o.error = "内置版本 " + BlutterPick.describe(names)
                    + " 里没有能对应 Dart " + dartVersion + " 的 blutter";
            return o;
        }

        outDir.mkdirs();
        int attempt = 0;
        for (BlutterPick.Ver v : cand) {
            if (attempt >= MAX_TRY) break;
            attempt++;
            File bin = new File(binDir, v.name);
            if (!bin.isFile()) continue;
            if (p != null) p.onProgress(10 + attempt * 10,
                    "试跑 " + v.name + "（Dart " + dartVersion + "）…");
            o.lastTried = v.name;
            try {
                String out = exec(bin, binDir, libapp, outDir, timeoutSec);
                o.output = out;
                o.exitCode = lastExit;
                o.triedExit.add(Integer.valueOf(lastExit));
                o.triedOutput.add(out);
                boolean produced = hasOutput(outDir);
                o.ok = (lastExit == 0) && produced;
                if (o.ok) { o.binary = v.name; break; }
            } catch (Exception e) {
                o.output = String.valueOf(e);
                o.triedExit.add(Integer.valueOf(-1));
                o.triedOutput.add(o.output);
                o.error = "执行 " + v.name + " 失败: " + e.getMessage();
            }
        }
        o.elapsedMs = System.currentTimeMillis() - t0;

        // ---- 失败但命中「快照版本哈希不符」：尝试放宽版本校验重试 ----
        // 这是预编译 blutter 的固有局限——每个二进制只对应一个 Dart SDK commit，
        // 而 Flutter 生态里同一 3.x 也有多个 commit。同 minor 不同 commit 通常
        // 快照布局兼容，把二进制内嵌的 expected 哈希改成 App 实际哈希即可跑通。
        if (!o.ok) {
            String[] mm = null;
            for (String t : o.triedOutput) {
                String[] m = BlutterRelax.parseSnapshotMismatch(t);
                if (m != null) { mm = m; break; }
            }
            o.snapshotMismatch = mm;
            if (mm != null) {
                File relaxBin = pickRanCandidateBin(binDir, o, mm[0]);
                if (relaxBin != null) {
                    Outcome r = tryRelax(relaxBin, binDir, libapp, outDir, mm[0], mm[1], timeoutSec, p);
                    if (r == null && lastRelaxFail != null) o.relaxFail = lastRelaxFail;
                    if (r != null) {
                        r.tried.addAll(o.tried);
                        r.triedExit.addAll(o.triedExit);
                        r.triedOutput.addAll(o.triedOutput);
                        r.closure = o.closure;
                        r.lastTried = o.lastTried;
                        r.snapshotMismatch = mm;
                        r.relaxed = true;
                        o = r;
                    }
                }
            }
        }

        if (o.ok) exportSymbols(o);
        return o;
    }

    /**
     * 选「放宽重试」要打补丁的二进制：优先选退出码 0 且自身确实内嵌了 mismatch 里
     * 那个 expected 哈希的候选——这样内存补丁一定能命中。
     *
     * <p>为什么不能盲选「第一个退出码 0」：blutter 报 "expected 'X'" 的那行可能来自
     * 3_11（X=78da37…），而 3_12 也退出码 0、只是报的是它自己的另一个哈希。若盲选 3_12，
     * 其二进制里根本没有 78da37…，{@link BlutterRelax#relaxSnapshotHash} 会因替换不到目标
     * 返回 null，放宽直接落空。优先选「含该 expected」的候选即可同时兜住两种顺序。
     * 都没命中时退化为第一个退出码 0 的候选（此时 relax 多半返回 null，回退清晰诊断）。
     */
    private static File pickRanCandidateBin(File binDir, Outcome o, String expected) {
        File firstExit0 = null;
        for (int i = 0; i < o.tried.size(); i++) {
            int ec = i < o.triedExit.size() ? o.triedExit.get(i).intValue() : -1;
            if (ec != 0) continue;
            File f = new File(binDir, o.tried.get(i));
            if (firstExit0 == null) firstExit0 = f;
            if (expected != null && binaryContainsHash(f, expected)) return f;
        }
        return firstExit0;
    }

    /** 二进制里是否内嵌了某个快照哈希（ASCII 十六进制串或原始 16 字节任一形式均可） */
    private static boolean binaryContainsHash(File f, String hash) {
        if (hash == null || hash.length() != 32) return false;
        byte[] data = readAll(f);
        if (data == null) return false;
        if (contains(data, hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return true;
        try { return contains(data, BlutterRelax.hexToBytes(hash)); }
        catch (Exception e) { return false; }
    }

    /** 朴素子串判定（避免依赖第三方库；子串通常很短） */
    private static boolean contains(byte[] data, byte[] sub) {
        if (sub == null || sub.length == 0 || sub.length > data.length) return false;
        for (int i = 0; i + sub.length <= data.length; i++) {
            boolean eq = true;
            for (int j = 0; j < sub.length; j++) {
                if (data[i + j] != sub[j]) { eq = false; break; }
            }
            if (eq) return true;
        }
        return false;
    }

    /**
     * 把 blutter 的对象池 dump 提炼成**对外交付的符号索引**。
     *
     * <p>blutter 自己那份 dump 固定叫 {@code pp.txt}（工程名，看不出内容，且动辄
     * 2.9 MB / 6 万行），直接交给用户等于没给。这里据此另出两个按内容命名的产物：
     * <ul>
     *   <li>{@code dart_symbols.tsv} —— 地址 ↔ Dart 源位置 ↔ 方法名，一行一条（机器可读）</li>
     *   <li>{@code dart_symbols.txt} —— 对象类型统计 / 按包分布 / 业务包符号（人可读）</li>
     * </ul>
     * 原始 {@code pp.txt} **原样保留**（不覆盖 blutter 的接口约定）。
     * 内容不像对象池 dump 时直接不生成 —— 宁可没有，也不给假符号表。
     */
    public static void exportSymbols(Outcome o) {
        if (o == null || o.outDir == null) return;
        File pp = new File(o.outDir, PP);
        if (!pp.isFile()) return;
        PpDigest.Digest d = PpDigest.parse(pp);
        if (!d.looksLikePp) return;
        o.symbols = new File(o.outDir, PpDigest.TSV_NAME);
        o.digest = new File(o.outDir, PpDigest.TXT_NAME);
        o.symbolCount = PpDigest.writeTsv(d, o.symbols, "libapp.so（" + PP + "）");
        PpDigest.writeDigest(d, o.digest, 18, 12, 40);
        o.topPackage = d.topPackage;
    }

    private int lastExit = -1;
    /** 最近一次 tryRelax 失败的具体原因（诊断用，避免静默 null） */
    private String lastRelaxFail;

    /** 放宽重试时补丁副本的可写落点，按优先级排列 */
    private static final String[] WRITE_DIRS = { "code_cache", "files", "cache" };
    /** 可用于加载 app 私有目录 ELF 的系统动态链接器（Android 上不落 exec 位的标准做法） */
    private static final String[] LINKER64 = {
            "/system/bin/linker64",
            "/apex/com.android.runtime/bin/linker64",
    };

    /**
     * 精排：把「major.minor 与目标完全相同」的一组按内嵌真实版本排序。
     *
     * 依据是实测结论 —— 文件名不保证等于实际版本（libblutter_3_6.so 实为 3.6.1、
     * libblutter_3_0_90.so 实为 3.0.3），所以同一 major.minor 下有多个候选时，
     * 必须读二进制内嵌的 dartsdk 版本才能挑出 patch 最接近的那个。
     * 只在组内多于一个候选时才读文件。
     */
    private List<BlutterPick.Ver> refine(List<BlutterPick.Ver> cand, String dartVersion) {
        int[] want = BlutterPick.majorMinor(dartVersion);
        int wantPatch = BlutterPick.patchOf(dartVersion);
        if (want == null || cand.size() < 2) return cand;

        List<BlutterPick.Ver> exact = new ArrayList<BlutterPick.Ver>();
        List<BlutterPick.Ver> rest = new ArrayList<BlutterPick.Ver>();
        for (BlutterPick.Ver v : cand) {
            if (v.exact(want[0], want[1])) exact.add(v); else rest.add(v);
        }
        if (exact.size() < 2) {
            for (BlutterPick.Ver v : exact) v.realVersion = probeRealVersion(v);
            return cand;
        }

        File dir = getBinDir();
        final Map<String, Integer> dist = new HashMap<String, Integer>();
        for (BlutterPick.Ver v : exact) {
            v.realVersion = probeRealVersion(v);
            int p = BlutterPick.patchOf(v.realVersion);
            dist.put(v.name, (p < 0 || wantPatch < 0) ? 999 : Math.abs(p - wantPatch));
        }
        Collections.sort(exact, new Comparator<BlutterPick.Ver>() {
            public int compare(BlutterPick.Ver a, BlutterPick.Ver b) {
                int da = dist.containsKey(a.name) ? dist.get(a.name) : 999;
                int db = dist.containsKey(b.name) ? dist.get(b.name) : 999;
                if (da != db) return da - db;
                return a.name.compareTo(b.name);
            }
        });
        List<BlutterPick.Ver> out = new ArrayList<BlutterPick.Ver>();
        out.addAll(exact);
        out.addAll(rest);
        return out;
    }

    private String probeRealVersion(BlutterPick.Ver v) {
        File dir = getBinDir();
        if (dir == null) return null;
        return BlutterPick.embeddedDartVersion(new File(dir, v.name));
    }

    private String exec(File bin, File binDir, File libapp, File outDir, int timeoutSec)
            throws Exception {
        List<String> args = new ArrayList<String>();
        args.add(bin.getAbsolutePath());
        args.add("-i"); args.add(libapp.getAbsolutePath());
        args.add("-o"); args.add(outDir.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(libapp.getParentFile());
        pb.environment().put("LD_LIBRARY_PATH", binDir.getAbsolutePath());
        pb.environment().put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        pb.environment().put("HOME", ctx.getFilesDir().getAbsolutePath());
        pb.environment().put("PATH", "/system/bin:/system/xbin");
        pb.redirectErrorStream(true);
        try {
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        } catch (Exception e) {
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        }
        Log.d(TAG, "blutter exec: " + args);

        final Process proc = pb.start();
        try { proc.getOutputStream().close(); } catch (Exception ignore) { /* ignore */ }

        final StringBuilder out = new StringBuilder();
        Thread reader = new Thread() {
            public void run() {
                try {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (out.length() < 200000) out.append(line).append('\n');
                    }
                } catch (Exception e) { /* ignore */ }
            }
        };
        reader.start();
        boolean done = proc.waitFor(Math.max(10, timeoutSec), TimeUnit.SECONDS);
        if (!done) {
            proc.destroyForcibly();
            lastExit = -2;
            return out.toString() + "\n[blutter 超时 " + timeoutSec + "s，已强制终止]";
        }
        reader.join(3000);
        try { lastExit = proc.exitValue(); } catch (Exception e) { lastExit = -1; }
        return out.toString();
    }

    /**
     * 放宽版本校验后重试：把二进制内嵌的 expected 快照哈希改成 App 实际哈希，
     * 再拉起这份补丁副本（原二进制在 nativeLibraryDir，只读、无法就地改）。
     *
     * <p>执行方式是这条路线唯一的真难点。三条路径按「实测最可能成功」排序：
     * <ol>
     *   <li><b>/system/bin/linker64 &lt;补丁副本&gt;</b>：Android 上从 app 私有目录运行 ELF 的
     *       标准手法——{@code execve} 只针对 linker64 本身做检查，目标文件由 linker
     *       直接打开并加载，因而绕开「{@code app_data_file} 不可 execute」的 SELinux 限制。
     *       证据：博主 Minis / Radare2BlutterMCP 的真机日志明确打印「运行位置: /system/bin/linker64」。</li>
     *   <li><b>{@code memfd_create}</b>（syscall 279）：匿名可执行 fd，经 {@code /proc/self/fd/N} 拉起。</li>
     *   <li><b>/data/local/tmp</b> 落盘 + 加执行位，最后兜底。</li>
     * </ol>
     * 注意：{@code android.os.MemoryFile}（ashmem / anon_inode）<b>不可</b>用作执行载体——
     * 内核/SELinux 禁止从 ashmem fd 执行，{@code execve} 直接 EACCES，子进程空退出，
     * 这正是更早版本「放宽重试看着跑了却从不成功」的原因，已弃用。
     *
     * <p>只能缓解「同 minor 不同 commit」的哈希门禁；若两 commit 的快照布局真的不同，
     * blutter 仍会失败或产出不可信符号——所以成功也标 {@code relaxed}，调用方需人工核对。
     * 失败时把具体原因写进 {@code lastRelaxFail}（不再是静默 null），让报告能直接定位。
     */
    private Outcome tryRelax(File bin, File binDir, File libapp, File outDir,
                             String expected, String found, int timeoutSec, Progress p) {
        if (p != null) p.onProgress(60, "放宽版本校验重试（同 minor 不同 commit）…");
        lastRelaxFail = null;
        try {
            byte[] orig = readAll(bin);
            if (orig == null) { lastRelaxFail = "读不到二进制 " + bin.getName(); return null; }
            byte[] patched = BlutterRelax.relaxSnapshotHash(orig, expected, found);
            if (patched == null) {
                lastRelaxFail = "二进制 " + bin.getName() + " 里替换不到 expected 哈希 " + expected;
                return null;
            }

            File relaxedOut = new File(outDir, "relaxed");
            relaxedOut.mkdirs();

            // 路径 ①：/system/bin/linker64 <补丁副本>（首选，有第三方真机证据）
            //   execve 的检查只落在 linker64（/system/bin 本身可执行），目标 ELF 由 linker
            //   直接打开加载，因此补丁副本可以放在 app 私有目录，无需任何可执行位。
            Outcome r = null;
            for (int i = 0; r == null && i < WRITE_DIRS.length; i++) {
                File dir = subDir(WRITE_DIRS[i], "relax");
                if (dir == null) continue;
                File target = new File(dir, "libblutter_relax.so");
                if (!writeFile(target, patched)) {
                    noteFail("补丁副本写入 " + WRITE_DIRS[i] + " 失败");
                    continue;
                }
                for (int k = 0; r == null && k < LINKER64.length; k++) {
                    File lk = new File(LINKER64[k]);
                    if (!lk.exists()) continue;
                    r = runPatched(new String[] { lk.getAbsolutePath(), target.getAbsolutePath() },
                            bin, binDir, libapp, relaxedOut, timeoutSec, "linker64");
                }
                if (r == null) noteFail("linker64 加载补丁副本未产出");
            }

            // 路径 ②：memfd_create（匿名可执行 fd，经 /proc/self/fd/N 拉起）
            if (r == null) {
                int fd = memfdCreate();
                if (fd >= 0) {
                    if (writeFully(fd, patched)) {
                        r = runPatched(new String[] { "/proc/self/fd/" + fd },
                                bin, binDir, libapp, relaxedOut, timeoutSec, "memfd");
                    } else {
                        noteFail("memfd 写入失败");
                    }
                    closeFd(fd);
                } else {
                    noteFail("memfd_create 不可用（syscall 279 调不到）");
                }
            }

            // 路径 ③：兜底，写 /data/local/tmp 再 exec
            if (r == null) {
                File tmp = new File("/data/local/tmp",
                        "blutter_relax_" + System.currentTimeMillis() + ".so");
                if (writeFile(tmp, patched) && tmp.setExecutable(true, false)) {
                    r = runPatched(new String[] { tmp.getAbsolutePath() },
                            bin, binDir, libapp, relaxedOut, timeoutSec, "tmpfile");
                } else {
                    noteFail("/data/local/tmp 写入或加执行位失败");
                }
                try { tmp.delete(); } catch (Exception ignore) { /* ignore */ }
            }

            if (r != null) {
                r.relaxNote = "已放宽快照版本校验（二进制 " + bin.getName() + " 内嵌 expected 哈希 "
                        + expected + " 改为 App 实际 " + found + "，经 " + r.relaxHow + " 执行）。"
                        + "同 minor 不同 commit 通常布局兼容，但符号正确性需人工核对；"
                        + "如需长期稳定，应把按 commit " + found + " 编译的 blutter 重新打包进 APK。";
                return r;
            }
            noteFail("补丁副本已执行但未产出 pp.txt——若上面几条执行路径都已试过，"
                    + "说明该 commit 的快照布局确实不兼容，需按 commit " + found + " 重编 blutter");
            return null;
        } catch (Throwable t) {
            noteFail("放宽重试异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    /** 用给定命令行头（exec 路径 + 可选参数）拉起补丁副本；产出判定复用 {@link #hasOutput} */
    private Outcome runPatched(String[] cmdHead, File bin, File binDir, File libapp, File outDir,
                               int timeoutSec, String how) {
        try {
            List<String> args = new ArrayList<String>();
            for (int i = 0; i < cmdHead.length; i++) args.add(cmdHead[i]);
            args.add("-i"); args.add(libapp.getAbsolutePath());
            args.add("-o"); args.add(outDir.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(args);
            pb.directory(libapp.getParentFile());
            pb.environment().put("LD_LIBRARY_PATH", binDir.getAbsolutePath());
            pb.environment().put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
            pb.environment().put("HOME", ctx.getFilesDir().getAbsolutePath());
            pb.environment().put("PATH", "/system/bin:/system/xbin");
            pb.redirectErrorStream(true);
            try { pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))); }
            catch (Exception e) { pb.redirectInput(ProcessBuilder.Redirect.PIPE); }

            final Process proc = pb.start();
            try { proc.getOutputStream().close(); } catch (Exception ignore) { /* ignore */ }
            final StringBuilder out = new StringBuilder();
            Thread reader = new Thread() {
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(proc.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (out.length() < 200000) out.append(line).append('\n');
                        }
                    } catch (Exception e) { /* ignore */ }
                }
            };
            reader.start();
            boolean done = proc.waitFor(Math.max(10, timeoutSec), TimeUnit.SECONDS);
            if (!done) { proc.destroyForcibly(); return null; }
            reader.join(3000);
            int ec;
            try { ec = proc.exitValue(); } catch (Exception e) { ec = -1; }

            boolean produced = hasOutput(outDir);
            if (!produced) return null;           // 放宽后仍无产物——布局不兼容，回退诊断

            Outcome r = new Outcome();
            r.outDir = outDir;
            r.ok = true;
            r.binary = bin.getName();
            r.exitCode = ec;
            r.output = out.toString();
            r.relaxHow = how;
            return r;
        } catch (Throwable t) {
            noteFail("执行 " + cmdHead[0] + " 抛 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    /** 追加一条放宽失败原因（多条用「; 」串联，报告里一次看全，不静默吞） */
    private void noteFail(String s) {
        lastRelaxFail = (lastRelaxFail == null || lastRelaxFail.isEmpty())
                ? s : lastRelaxFail + "; " + s;
    }

    /** app 私有的可写子目录（relax 补丁副本落点）。返回 null 表示该目录不可用。 */
    private File subDir(String which, String name) {
        try {
            File base;
            if ("code_cache".equals(which)) base = ctx.getCodeCacheDir();
            else if ("files".equals(which)) base = ctx.getFilesDir();
            else base = ctx.getCacheDir();
            if (base == null) return null;
            File d = new File(base, name);
            if (!d.exists() && !d.mkdirs()) return null;
            return d.isDirectory() ? d : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** memfd_create（syscall 279, aarch64）；name=NULL, flags=0；返回 fd，失败 -1。
     *  用 libcore.io.Libcore.os.syscall 调原生 syscall（debug 包不受 hidden API 限制）。 */
    private static int memfdCreate() {
        try {
            Class<?> libcore = Class.forName("libcore.io.Libcore");
            Object os = libcore.getField("os").get(null);
            Method syscall = os.getClass().getMethod("syscall", long.class, Object[].class);
            long fd = (Long) syscall.invoke(os, 279L, new Object[]{ 0L, 0L });
            return (int) fd;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 从 fd int 构造 FileDescriptor（反射写入 descriptor 字段，兼容 int/long 两种声明） */
    private static FileDescriptor fdFromInt(int fdInt) {
        try {
            FileDescriptor fd = new FileDescriptor();
            Field d = FileDescriptor.class.getDeclaredField("descriptor");
            d.setAccessible(true);
            if (d.getType() == int.class) d.setInt(fd, fdInt);
            else d.setLong(fd, (long) fdInt);
            return fd;
        } catch (Exception e) {
            return null;
        }
    }

    /** 把整段字节写进 memfd（经 libcore Os.write，循环直到写完） */
    private static boolean writeFully(int fd, byte[] data) {
        try {
            Class<?> libcore = Class.forName("libcore.io.Libcore");
            Object os = libcore.getField("os").get(null);
            Method write = os.getClass().getMethod("write", FileDescriptor.class, java.nio.ByteBuffer.class);
            FileDescriptor fdObj = fdFromInt(fd);
            if (fdObj == null) return false;
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(data);
            int off = 0;
            while (off < data.length) {
                bb.position(off);
                int n = (Integer) write.invoke(os, fdObj, bb);
                if (n <= 0) return false;
                off += n;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 关闭 memfd */
    private static void closeFd(int fd) {
        try {
            Class<?> libcore = Class.forName("libcore.io.Libcore");
            Object os = libcore.getField("os").get(null);
            Method close = os.getClass().getMethod("close", FileDescriptor.class);
            close.invoke(os, fdFromInt(fd));
        } catch (Throwable ignore) { /* ignore */ }
    }

    /** 把整段字节写进普通文件（/data/local/tmp 兜底路径用） */
    private static boolean writeFile(File f, byte[] data) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            fos.write(data);
            fos.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            AnalysisLog.close(fos);
        }
    }

    private static byte[] readAll(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            return buf;
        } catch (Exception e) {
            return null;
        } finally {
            AnalysisLog.close(raf);
        }
    }

    private static boolean hasOutput(File outDir) {
        File pp = new File(outDir, PP);
        if (pp.isFile() && pp.length() > 0) return true;
        File asm = new File(outDir, "asm");
        if (asm.isDirectory()) {
            String[] fs = asm.list();
            if (fs != null && fs.length > 0) return true;
        }
        File objs = new File(outDir, "objs");
        return objs.isDirectory() && objs.list() != null && objs.list().length > 0;
    }

    // ---------------- 报告 ----------------

    /**
     * 适配成 {@link AnalysisRun.SymbolStage}，供「选文件分析」的第三阶段使用。
     *
     * 走这条路径时报告是**单块**落日志：先把 blutter 自身的报告写好，
     * 再把 pp.txt 的开头若干 KB 追加进去（完整文件路径也会写明），
     * 这样客户端只需读一个块就能看到符号还原结果，不必再找别处。
     */
    public AnalysisRun.SymbolStage stage(final int timeoutSec, final int ppHeadBytes) {
        return new AnalysisRun.SymbolStage() {
            public boolean available() {
                return getBinDir() != null && !listBinaries().isEmpty();
            }

            public String name() { return "Flutter 符号还原"; }

            public int timeoutSec() { return timeoutSec; }

            public String run(File libapp, File libflutter, File outDir,
                              final AnalysisRun.Emit emit) {
                DartInfo di = DartInfo.analyze(libapp, libflutter);
                Outcome o = analyze(libapp, outDir, di.dartVersion, timeoutSec,
                        new Progress() {
                            public void onProgress(int pct, String msg) {
                                if (emit != null) emit.emit("    · " + msg);
                            }
                        });
                StringBuilder sb = new StringBuilder();
                sb.append(report(o, di.dartVersion));

                if (o.ok) {
                    File pp = new File(o.outDir, PP);
                    if (pp.isFile()) {
                        if (o.symbols != null && o.symbols.isFile()) {
                            sb.append("\n  ◆ 交付产物\n");
                            sb.append("      ").append(PpDigest.TSV_NAME).append(" : ")
                              .append(o.symbolCount).append(" 条符号 → ")
                              .append(o.symbols.getAbsolutePath()).append("\n");
                            sb.append("      ").append(PpDigest.TXT_NAME).append(" : 可读摘要（业务包 ")
                              .append(o.topPackage == null || o.topPackage.length() == 0 ? "未识别" : o.topPackage)
                              .append("）\n");
                        }
                        // 先摘要后原文：摘要把 6 万行 dump 压成「类型统计 + 地址↔源位置 + 包分布」
                        sb.append("\n  ◆ 符号摘要\n");
                        sb.append(PpDigest.report(PpDigest.parse(pp), 18, 12, 40));
                        sb.append("\n  ◆ pp.txt 正文（开头 ")
                          .append(ppHeadBytes / 1024).append(" KB；完整文件 ")
                          .append(fmtSize(pp.length())).append("）\n");
                        sb.append("      全文路径 : ").append(pp.getAbsolutePath()).append("\n");
                        sb.append("  ──────────── pp.txt ────────────\n");
                        sb.append(head(pp, ppHeadBytes));
                        sb.append("\n  ────────────────────────────────\n");
                    }
                    File asm = new File(o.outDir, "asm");
                    if (asm.isDirectory()) {
                        String[] fs = asm.list();
                        sb.append("  asm/ 目录 : ").append(fs == null ? 0 : fs.length)
                          .append(" 个文件（带符号反汇编，可直接用 r2_disasm 对照）\n");
                    }
                }
                return sb.toString();
            }
        };
    }

    public String report(Outcome o, String dartVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append("■ Blutter 符号还原（Dart AOT → 可读符号/伪代码）\n");
        sb.append("  目标 Dart   : ").append(dartVersion == null ? "(未知)" : dartVersion).append("\n");
        sb.append("  内置版本    : ").append(BlutterPick.describe(listBinaries())).append("\n");

        if (o.error != null) {
            sb.append("  ✘ 未执行     : ").append(o.error).append("\n");
            if (!o.tried.isEmpty()) sb.append("  候选顺序    : ").append(join(o.tried)).append("\n");
            appendClosure(sb, o.closure, false);
            sb.append("\n  说明: blutter 对 Dart 版本敏感，必须版本匹配；\n");
            sb.append("        内置清单里没有对应版本时，需补进对应版本的 libblutter_<major>_<minor>.so。\n");
            return sb.toString();
        }

        // ---- 实际跑通的是哪一个 ----
        if (o.ok) {
            sb.append("  实际跑通    : ").append(o.binary);
            if (o.relaxed) sb.append("（放宽版本校验）");
            BlutterPick.Ver chosen = BlutterPick.parse(o.binary);
            if (chosen != null) {
                chosen.realVersion = probeRealVersion(chosen);
                sb.append("（内嵌 Dart ")
                  .append(chosen.realVersion == null ? "读不到" : chosen.realVersion).append("）");
                String v = versionVerdict(chosen.realVersion, dartVersion);
                if (v.length() > 0) sb.append("  ").append(v);
            }
            sb.append("\n");
        } else {
            sb.append("  实际跑通    : (无 —— ").append(o.tried.size()).append(" 个候选全部失败)\n");
        }

        // ---- 逐个候选的退出码（真机上"最后一个"很容易被误读成"选中的"，所以全列出来）----
        if (!o.tried.isEmpty()) {
            sb.append("  试跑结果    :\n");
            for (int i = 0; i < o.tried.size(); i++) {
                int ec = i < o.triedExit.size() ? o.triedExit.get(i).intValue() : -1;
                String to = i < o.triedOutput.size() ? o.triedOutput.get(i) : "";
                String et;
                if (ec == 0 && to.contains("exception")) {
                    et = "0（进程退出但抛异常，无产物）✘";
                } else {
                    et = exitText(ec);
                }
                sb.append("      ").append(pad(o.tried.get(i), 24))
                  .append(" 退出码 ").append(et).append("\n");
            }
        }

        // ---- 快照版本哈希失配（预编译 blutter 的固有局限，单独成节）----
        if (!o.ok && o.snapshotMismatch != null) {
            String[] mm = o.snapshotMismatch;
            sb.append("\n  ◆ 快照版本哈希不符（预编译 blutter 的固有局限）\n");
            sb.append("      blutter 内嵌 SDK commit : ").append(mm[0]).append("\n");
            sb.append("      本 App 实际 commit     : ").append(mm[1])
              .append("（Dart ").append(dartVersion == null ? "?" : dartVersion).append("）\n");
            sb.append("      说明: 预编译 blutter 每个二进制只对应一个 Dart SDK commit；\n");
            sb.append("            同一 3.x 内也存在多个 commit，哈希对不上 blutter 就拒绝解析。\n");
            if (o.relaxed) {
                sb.append("      结果: ✔ 已放宽校验重试跑通（").append(pad(o.binary, 22))
                  .append("），符号需人工核对。\n");
            } else {
                sb.append("      修复: 把按 commit ").append(mm[1])
                  .append(" 编译的 blutter 放进 APK\n");
                sb.append("            （lib/arm64-v8a/libblutter_")
                  .append(majorMinorLabel(dartVersion))
                  .append(".so 换成该 commit 的构建）。\n");
                sb.append("      注: 依赖闭包已完整（见上），不是打包漏依赖。\n");
                if (o.relaxFail != null) {
                    sb.append("      放宽重试    : ✘ ").append(o.relaxFail).append("\n");
                }
            }
        }

        // ---- 失败时把闭包问题顶到最前面，这才是真机日志里那行 CANNOT LINK 的根因 ----
        if (!o.ok) appendClosure(sb, o.closure, false);

        if (o.relaxed && o.relaxNote != null) {
            sb.append("\n  ◆ 放宽校验说明\n");
            sb.append("      ").append(o.relaxNote).append("\n");
        }

        sb.append("  调用        : ")
          .append(o.ok ? o.binary : (o.lastTried == null ? "?" : o.lastTried))
          .append(" -i libapp.so -o <out>\n");
        sb.append("  用时        : ").append(o.elapsedMs / 1000).append('.')
          .append((o.elapsedMs % 1000) / 100).append("s\n");
        sb.append("  输出目录    : ").append(o.outDir.getAbsolutePath()).append("\n");

        List<File> files = listTree(o.outDir, 24);
        if (!files.isEmpty()) {
            sb.append("\n  ◆ 产物\n");
            for (File f : files) {
                sb.append("      ").append(f.isDirectory() ? "[dir] " : "      ")
                  .append(pad(f.getName(), 26));
                if (!f.isDirectory()) sb.append(fmtSize(f.length()));
                sb.append("  ").append(rel(o.outDir, f)).append("\n");
            }
        }

        File pp = new File(o.outDir, PP);
        if (pp.isFile()) {
            sb.append("\n  ◆ 交付产物（按内容命名 —— blutter 自己那份 dump 才叫 pp.txt）\n");
            if (o.symbols != null && o.symbols.isFile()) {
                sb.append("      ").append(PpDigest.TSV_NAME).append(" : ")
                  .append(o.symbolCount).append(" 条符号（地址 / Dart 源位置 / 方法），")
                  .append(fmtSize(o.symbols.length())).append("\n");
                sb.append("      ").append(PpDigest.TXT_NAME).append(" : 可读摘要（类型统计 / 按包分布 / 业务包 ")
                  .append(o.topPackage == null || o.topPackage.length() == 0 ? "未识别" : o.topPackage)
                  .append("）\n");
                sb.append("      路径          : ").append(o.symbols.getAbsolutePath()).append("\n");
                sb.append("      读法          : read_artifact {\"path\":\"…/").append(PpDigest.TSV_NAME)
                  .append("\"}；按包或方法名查用 dart_symbols {\"pkg\":\"")
                  .append(o.topPackage == null ? "" : o.topPackage).append("\"}\n");
            } else {
                sb.append("      ⚠ 未能提炼符号索引（内容不像对象池 dump），只保留原始产物\n");
            }
            sb.append("      原始 dump     : ").append(PP).append("  ").append(fmtSize(pp.length()))
              .append("（blutter 固定产物名，原样保留）\n");
            sb.append(PpDigest.report(PpDigest.parse(pp), 18, 12, 40));
            sb.append("\n      ◆ 原文样例（开头 1KB）\n");
            sb.append("  ──────────── pp.txt 开头 ────────────\n");
            sb.append(head(pp, 1024));
            sb.append("\n  ──────────────────────────────────────\n");
        }

        String tail = trim(o.output, 4000);
        if (tail.length() > 0) {
            sb.append("\n  ◆ blutter 运行输出（尾部 4KB）\n");
            sb.append(tail).append("\n");
        }
        return sb.toString();
    }

    /** 内嵌版本与目标版本的匹配判词 —— 真机事故里 "3.9 被说成同 minor" 就是这里写错了 */
    private static String versionVerdict(String real, String target) {
        if (real == null || target == null) return "";
        if (real.equals(target)) return "← 与目标完全一致 ✔";
        int[] a = BlutterPick.majorMinor(real);
        int[] b = BlutterPick.majorMinor(target);
        if (a == null || b == null) return "";
        if (a[0] == b[0] && a[1] == b[1]) {
            return "← 与目标 major.minor 一致，仅 patch 不同"
                 + "（blutter 兼容宏按 major.minor 判定，通常可用；解析出错则需换同 patch 版本）";
        }
        return "← ⚠ 与目标 major.minor 不同（" + a[0] + "." + a[1] + " vs " + b[0] + "." + b[1]
             + "），blutter 兼容宏按 major.minor 判定，很可能解析失败";
    }

    private static String exitText(int ec) {
        if (ec == 0) return "0 ✔";
        if (ec == -2) return "-2（超时被强杀）✘";
        if (ec < 0) return ec + " ✘";
        return ec + " ✘";
    }

    /** "3.12.2" → "3_12"，用于报告里建议的二进制文件名；解析不出返回 "x_y" */
    private static String majorMinorLabel(String dartVersion) {
        int[] mm = BlutterPick.majorMinor(dartVersion);
        return mm == null ? "x_y" : mm[0] + "_" + mm[1];
    }

    /** 渲染依赖闭包；verbose=false 时只列有问题的边 */
    private void appendClosure(StringBuilder sb, Closure c, boolean verbose) {
        if (c == null) return;
        sb.append("\n  ◆ 依赖闭包（读各库 DT_NEEDED；非 Android 平台库都必须在本目录）\n");
        if (!verbose && c.complete) return;
        for (String[] e : c.edges) {
            if (e[1].length() == 0) continue;
            if (!verbose && ("内置".equals(e[2]) || "系统".equals(e[2]))) continue;
            sb.append("      ").append(pad(e[0], 24)).append("→ ")
              .append(pad(e[1], 22)).append(' ').append(e[2]).append("\n");
        }
        if (c.complete) {
            sb.append("      ✔ 闭包完整（共检查 ").append(c.checked.size()).append(" 个库）\n");
        } else {
            sb.append("      ✘ 缺 ").append(join(c.missing))
              .append(" —— 运行时会报 CANNOT LINK EXECUTABLE\n");
            sb.append("        该库必须与 blutter 同目录（即 APK 的 lib/arm64-v8a/），\n");
            sb.append("        由打包脚本按 DT_NEEDED 闭包收集（tools/prepare_blutter.py）。\n");
        }
    }

    public String doctor() {
        StringBuilder sb = new StringBuilder();
        sb.append("■ Blutter 运行时自检\n");
        File d = getBinDir();
        sb.append("  nativeLibraryDir : ").append(d == null ? "(拿不到)" : d.getAbsolutePath()).append("\n");
        List<String> names = listBinaries();
        sb.append("  已内置 blutter   : ").append(BlutterPick.describe(names)).append("\n");
        List<BlutterPick.Ver> avail = BlutterPick.available(names);
        if (!avail.isEmpty()) {
            sb.append("\n  ◆ 清单（文件名 → 二进制内嵌的真实 Dart 版本）\n");
            for (BlutterPick.Ver v : avail) {
                v.realVersion = probeRealVersion(v);
                sb.append("      ").append(pad(v.name, 24))
                  .append("→ Dart ").append(v.realVersion == null ? "(读不到)" : v.realVersion);
                if (v.realVersion != null
                        && !v.realVersion.startsWith(v.major + "." + v.minor + ".")) {
                    sb.append("   ⚠ 文件名与真实版本不一致");
                }
                sb.append("\n");
            }
        }
        if (d == null) {
            sb.append("\n  结论: 拿不到 nativeLibraryDir，无法执行。\n");
            return sb.toString();
        }

        // 依赖闭包 —— 这一节才是"能不能真的跑起来"的判据。
        // 早期版本只硬编码检查 capstone/icuuc/libc++ 三个名字，
        // 于是漏掉 libicudata.so（libicuuc.so 的 DT_NEEDED）时自检照样报"依赖齐备"。
        Closure c = closureOf(d, names);
        sb.append("\n  ◆ 依赖闭包（读各库 DT_NEEDED；非 Android 平台库都必须在本目录）\n");
        for (String[] e : c.edges) {
            if (e[1].length() == 0) {
                if ("不在包内".equals(e[2])) {
                    sb.append("      ").append(pad(e[0], 24)).append("→ ★ 不在包内\n");
                } else {
                    sb.append("      ").append(pad(e[0], 24)).append("→ (").append(e[2]).append(")\n");
                }
                continue;
            }
            sb.append("      ").append(pad(e[0], 24)).append("→ ")
              .append(pad(e[1], 22)).append(' ').append(e[2]).append("\n");
        }
        sb.append("      共检查 ").append(c.checked.size()).append(" 个库\n");

        if (names.isEmpty()) {
            sb.append("\n  结论: 未内置 blutter。需要把对应版本的 libblutter_<major>_<minor>.so\n");
            sb.append("        连同其 DT_NEEDED 闭包（libcapstone.so / libicuuc.so / libicudata.so /\n");
            sb.append("        libc++_shared.so）放进 APK 的 lib/arm64-v8a/。\n");
        } else if (!c.complete) {
            sb.append("\n  结论: ✘ blutter 在但依赖不全（缺 ").append(join(c.missing)).append("），执行必然失败：\n");
            sb.append("        CANNOT LINK EXECUTABLE ... \"<lib>\" not found\n");
            sb.append("        这不是 Dart 版本问题，是打包漏收依赖 ——\n");
            sb.append("        重跑 tools/prepare_blutter.py（它现在按 DT_NEEDED 闭包收集）。\n");
        } else {
            sb.append("\n  结论: ✔ 依赖闭包完整，可按目标 Dart 版本直接跑 blutter_run。\n");
        }
        return sb.toString();
    }

    // ---------------- 依赖闭包 ----------------

    /**
     * Android **平台库**：由系统提供，不需要随 APK 携带。
     * 只列确实会出现在 native 依赖里的那些；`libc++_shared.so` 故意不在其中
     * —— 它是 NDK 运行库，必须自带。
     */
    private static final Set<String> PLATFORM_LIBS = new HashSet<String>(Arrays.asList(
            "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libstdc++.so",
            "libandroid.so", "libjnigraphics.so",
            "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so",
            "libOpenSLES.so", "libOpenMAXAL.so", "libaaudio.so",
            "libcamera2ndk.so", "libmediandk.so", "libnativewindow.so",
            "libsync.so", "libvulkan.so", "libneuralnetworks.so",
            "libnativehelper.so", "libcutils.so", "libutils.so", "libbase.so",
            "libbinder.so", "libui.so", "libgui.so", "libhidlbase.so",
            "libprocessgroup.so", "libion.so", "libwebviewchromium_loader.so"));

    /** 依赖闭包检查结果 */
    public static final class Closure {
        /** {from, dep, kind}；kind ∈ 系统 / 内置 / ★缺失 / 不在包内 / 解析失败:... */
        public final List<String[]> edges = new ArrayList<String[]>();
        public final List<String> missing = new ArrayList<String>();
        /** 实际读到的库（去重） */
        public final List<String> checked = new ArrayList<String>();
        public boolean complete;
    }

    /**
     * 从 roots 出发求 DT_NEEDED 闭包，并判断每个非平台依赖是否落在 binDir 里。
     *
     * 用 {@link ElfReader#depsOf} 而不是 {@link ElfReader#parse}：后者会把整个文件
     * 读进内存，而这里要遍历 20 个 libblutter_*.so（含 33 MB 的 libicudata.so）。
     * 顺带确认 blutter 是否真的都装了。
     */
    public Closure closureOf(File binDir, List<String> roots) {
        Closure c = new Closure();
        if (binDir == null || roots == null) { c.complete = true; return c; }
        List<String> queue = new ArrayList<String>();
        for (String r : roots) queue.add(r);
        Set<String> seen = new HashSet<String>();
        while (!queue.isEmpty()) {
            String cur = queue.remove(0);
            if (!seen.add(cur)) continue;
            c.checked.add(cur);
            File f = new File(binDir, cur);
            if (!f.isFile()) {
                c.missing.add(cur);
                c.edges.add(new String[]{cur, "", "不在包内"});
                continue;
            }
            ElfReader.Deps dep = ElfReader.depsOf(f);
            if (!dep.ok) {
                c.edges.add(new String[]{cur, "", "解析失败: " + dep.error});
                continue;
            }
            for (String n : dep.needed) {
                String kind;
                if (PLATFORM_LIBS.contains(n)) {
                    kind = "系统";
                } else if (new File(binDir, n).isFile()) {
                    kind = "内置";
                    queue.add(n);
                } else {
                    kind = "★缺失";
                    c.missing.add(n);
                }
                c.edges.add(new String[]{cur, n, kind});
            }
        }
        c.complete = c.missing.isEmpty();
        return c;
    }

    // ---------------- 文件小工具 ----------------

    private static List<File> listTree(File dir, int max) {
        List<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        List<File> sorted = new ArrayList<File>();
        for (File f : fs) sorted.add(f);
        Collections.sort(sorted, new Comparator<File>() {
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File f : sorted) {
            if (out.size() >= max) break;
            out.add(f);
            if (f.isDirectory()) {
                File[] sub = f.listFiles();
                if (sub != null) {
                    out.add(new File(f, "(" + sub.length + " 个文件)"));
                }
            }
        }
        return out;
    }

    private static String rel(File base, File f) {
        String b = base.getAbsolutePath();
        String p = f.getAbsolutePath();
        return p.startsWith(b) ? p.substring(b.length()) : p;
    }

    private static String head(File f, int maxBytes) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[maxBytes];
            int n = in.read(buf);
            if (n <= 0) return "(空)";
            String s = new String(buf, 0, n, "UTF-8");
            // 只按行截断，避免最后一行半个多字节字符
            int cut = s.lastIndexOf('\n');
            return cut > 0 ? s.substring(0, cut) : s;
        } catch (Exception e) {
            return "(读取失败: " + e.getMessage() + ")";
        } finally {
            AnalysisLog.close(in);
        }
    }

    /** 供外部（把 pp.txt 正文分块落日志）读取文件头部 */
    public static String headOf(File f, int maxBytes) {
        return head(f, maxBytes);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(" > "); sb.append(s); }
        return sb.toString();
    }

    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) sb.append(' ');
        if (sb.length() > w) return sb.substring(0, w);
        return sb.toString();
    }

    static String fmtSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / (1024.0 * 1024.0));
    }
}
