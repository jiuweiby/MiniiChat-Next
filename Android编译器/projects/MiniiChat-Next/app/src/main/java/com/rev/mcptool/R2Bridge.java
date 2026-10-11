package com.rev.mcptool;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * radare2 运行时的手机端桥接。
 *
 * 资源布局（关键优化）：
 *  - libr_*.so（23 个 / 26.7MB）打进 APK 的 lib/arm64-v8a/，由系统在【安装期】
 *    解压到 nativeLibraryDir，运行时零释放开销 —— 这是"点启动即可用"的前提。
 *  - 主程序同样进 nativeLibraryDir，文件名 libr2.so。share 数据（3MB zip）放 assets 首次释放。
 *
 * 关于 libr2.so（真机实测 error=13 Permission denied 的结论）：
 *  Android 10+ 对 targetSdk>=29 的应用，禁止 exec app 私有目录（/data/user/0/<pkg>/files/…）
 *  里的任何文件 —— 与文件权限位无关，是 SELinux 层面的规则。唯一可执行的位置是
 *  nativeLibraryDir（系统安装期解压 APK 的 lib/<abi>/），但那里的条目名必须匹配 lib*.so。
 *
 *  而 radare2 的启动器是 multicall 分发器：它取 r_file_basename(argv[0]) 再交给 r_main_new，
 *  名字不认识就报错退出。所以 libr2.so 在入库前被打过一个小补丁 ——
 *  把「取 basename」那三条指令换成加载常量字符串 "r2"（见 tools/prepare_blutter.py）。
 *  这样无论文件叫什么，它都稳定以 r2 的身份工作。
 */
public class R2Bridge {
    public static final String TAG = "RevMCP";

    /** 释放进度回调（0-100，-1 表示不确定） */
    public interface Progress {
        void onProgress(int pct, String msg);
    }

    private static final String ASSET_BIN = "r2bin/r2";
    private static final String ASSET_SHARE_ZIP = "r2share.zip";
    private static final String MARK = ".ready.v2";

    private final Context ctx;
    private final File r2Home;       // files/r2
    private final File binHome;      // files/r2/bin
    private final File shareHome;    // files/r2/share
    private final File projectsHome; // files/r2/projects
    private boolean ready = false;

    public R2Bridge(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.r2Home = new File(this.ctx.getFilesDir(), "r2");
        this.binHome = new File(r2Home, "bin");
        this.shareHome = new File(r2Home, "share");
        this.projectsHome = new File(r2Home, "projects");
    }

    public File getR2Home() { return r2Home; }

    /** 原生库目录：APK 的 lib/arm64-v8a/*.so 由系统安装期解压到这里 */
    public String getLibDir() {
        String nld = null;
        try { nld = ctx.getApplicationInfo().nativeLibraryDir; } catch (Exception e) { /* ignore */ }
        if (nld != null && new File(nld, "libr_core.so").exists()) return nld;
        File fb = new File(r2Home, "lib");
        if (fb.isDirectory()) return fb.getAbsolutePath();
        return nld != null ? nld : fb.getAbsolutePath();
    }

    /** r2 主程序路径：优先 nativeLibraryDir 里的 libr2.so（Android 10+ 唯一可 exec 的位置） */
    public File getR2BinFile() {
        File nldBin = new File(getLibDir(), "libr2.so");
        if (nldBin.isFile()) return nldBin;
        return new File(binHome, "r2");
    }

    public String getR2BinPath() {
        return getR2BinFile().getAbsolutePath();
    }

    /** 是否已完成释放（可跳过首次准备） */
    public boolean isExtracted() {
        boolean binOk = getR2BinFile().isFile();
        return new File(r2Home, MARK).exists()
                && binOk
                && new File(shareHome, "radare2").exists();
    }

    /** 释放 r2 主程序并解压 share 数据（合计约 3MB，通常 1 秒内完成） */
    public synchronized void ensureExtracted(Progress p) throws Exception {
        if (ready) return;
        r2Home.mkdirs();
        binHome.mkdirs();
        projectsHome.mkdirs();
        if (isExtracted()) {
            getR2BinFile().setExecutable(true, false);
            ready = true;
            if (p != null) p.onProgress(100, "radare2 运行时已就绪");
            return;
        }

        // 主程序：nativeLibraryDir 里有 libr2.so 就不必再释放（那种情况下也 exec 不了）
        if (!new File(getLibDir(), "libr2.so").isFile()) {
            if (p != null) p.onProgress(2, "释放 radare2 主程序…");
            File r2bin = new File(binHome, "r2");
            copyAsset(ASSET_BIN, r2bin);
            r2bin.setReadable(true, false);
            r2bin.setWritable(true, false);
            r2bin.setExecutable(true, false);
        }

        if (p != null) p.onProgress(5, "解压 radare2 数据（约 3MB，仅首次）…");
        unzipAssetTo(ASSET_SHARE_ZIP, r2Home, p);
        chmodTree(shareHome);

        new File(r2Home, MARK).createNewFile();
        ready = true;
        if (p != null) p.onProgress(100, "radare2 运行时已就绪");
    }

    /** 执行一组 r2 命令，返回合并输出 */
    public String r2(String target, String[] cmds, int timeoutSec) throws Exception {
        ensureExtracted(null);
        StringBuilder c = new StringBuilder();
        for (String cmd : cmds) {
            if (c.length() > 0) c.append(';');
            c.append(cmd);
        }
        String libDir = getLibDir();
        List<String> args = new ArrayList<String>();
        args.add(getR2BinPath());
        args.add("-q");
        args.add("-e"); args.add("scr.color=0");
        args.add("-e"); args.add("scr.interactive=false");
        args.add("-e"); args.add("bin.relocs.apply=true");
        args.add("-e"); args.add("dir.prefix=" + r2Home.getAbsolutePath());
        args.add("-e"); args.add("dir.home=" + r2Home.getAbsolutePath());
        args.add("-e"); args.add("dir.projects=" + projectsHome.getAbsolutePath());
        args.add("-c"); args.add(c.toString());
        args.add(target);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.environment().put("LD_LIBRARY_PATH", libDir);
        pb.environment().put("HOME", r2Home.getAbsolutePath());
        pb.environment().put("R2_PREFIX", r2Home.getAbsolutePath());
        pb.environment().put("R2_RDATAHOME", r2Home.getAbsolutePath());
        pb.environment().put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        pb.environment().put("PATH", "/system/bin:/system/xbin");
        pb.redirectErrorStream(true);
        // 关键：把 stdin 接到 /dev/null。否则 r2 一旦进入交互模式（命令串解析失败时会发生）
        // 就会阻塞在读 stdin 上永不退出 —— 这正是"r2 类工具在 MCP 客户端侧超时"的典型成因。
        try {
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        } catch (Exception e) {
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        }
        Log.d(TAG, "exec: " + args.toString() + " LD_LIBRARY_PATH=" + libDir);

        final Process p = pb.start();
        // 保险：把子进程 stdin 立刻关闭，杜绝交互等待
        try { p.getOutputStream().close(); } catch (Exception ignore) { /* ignore */ }

        final StringBuilder out = new StringBuilder();
        Thread reader = new Thread() {
            public void run() {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) out.append(line).append('\n');
                } catch (Exception e) { /* ignore */ }
            }
        };
        reader.start();
        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            return out.toString() + "\n[radare2 超时 " + timeoutSec + "s，已强制终止]"
                    + "\n[提示] 目标是大文件（APK/ZIP/超大 .so）时 r2 解析极慢；"
                    + "请先解出 .so 再分析，或直接用纯 Java 的 analyze_file。";
        }
        // 交接必须确定：读完再拼结果。子进程已退出 → 管道到 EOF → reader 必然结束；
        // 若 2s 内没结束说明读流卡住，主动关流催它结束，再给一次机会。
        // （早期写死 join(2000) 后直接 toString()，一旦读线程还没跑完就会拿到半截输出，
        //   表现为"r2 偶发无输出"——这是实测踩到过的竞态。）
        reader.join(2000);
        if (reader.isAlive()) {
            try { p.getInputStream().close(); } catch (Exception ignore) { /* ignore */ }
            reader.join(3000);
        }
        int code = -1;
        try { code = p.exitValue(); } catch (Exception ignore) { /* ignore */ }
        if (code != 0) {
            out.append("\n[radare2 退出码 ").append(code).append("]");
            if (out.length() < 8) {
                out.append("\n[无任何输出 —— 常见原因：启动器不在 nativeLibraryDir（Android 10+ "
                        + "禁止 exec app 私有目录），或依赖库缺失。用 r2_doctor 可得到完整自检信息。]");
            }
        }
        return out.toString();
    }

    /**
     * radare2 自检：把"能不能跑起来"这件事查清楚，而不是靠猜。
     * 供 r2_doctor 工具使用，输出可直接贴给用户看。
     */
    public String selftest() {
        StringBuilder sb = new StringBuilder();
        sb.append("■ radare2 运行时自检\n");
        File bin = getR2BinFile();
        boolean fromNld = bin.getName().equals("libr2.so");
        sb.append("  主程序路径   : ").append(bin.getAbsolutePath()).append("\n");
        sb.append("  来源         : ").append(fromNld
                ? "nativeLibraryDir（可执行，期望位置）"
                : "app 私有目录（targetSdk<29 才可执行；本 App 为 34，预计被拒）").append("\n");
        sb.append("  主程序存在   : ").append(bin.exists()).append("\n");
        sb.append("  可执行位     : ").append(bin.canExecute()).append("\n");
        sb.append("  主程序大小   : ").append(bin.length()).append(" bytes\n");
        String libDir = getLibDir();
        sb.append("  原生库目录   : ").append(libDir).append("\n");
        sb.append("  libr_core.so : ").append(new File(libDir, "libr_core.so").exists()).append("\n");
        int libCount = 0;
        File ld = new File(libDir);
        String[] fs = ld.list();
        if (fs != null) for (String f : fs) if (f.endsWith(".so")) libCount++;
        sb.append("  库文件数量   : ").append(libCount).append("\n");
        sb.append("  share 数据   : ").append(new File(shareHome, "radare2").exists()).append("\n");

        try {
            long t0 = System.currentTimeMillis();
            ensureExtracted(null);
            sb.append("  资源释放     : OK（").append(System.currentTimeMillis() - t0).append("ms）\n");
        } catch (Exception e) {
            sb.append("  资源释放     : 失败 ").append(e).append("\n");
            return sb.toString();
        }

        sb.append("\n■ 试跑（r2 -q -c \"?V\" -，超时 20s）\n");
        try {
            long t0 = System.currentTimeMillis();
            String out = r2("-", new String[]{"?V"}, 20);
            long ms = System.currentTimeMillis() - t0;
            sb.append("  用时         : ").append(ms).append("ms\n");
            sb.append("  输出         : ").append(out.trim().isEmpty() ? "(空)" : out.trim()).append("\n");
            boolean ok = out.indexOf("6.") >= 0 || out.indexOf("5.") >= 0;
            sb.append("  结论         : ").append(ok
                    ? "radare2 可正常执行 ✔"
                    : "radare2 未返回版本号 ✘ —— 执行被系统阻止或依赖缺失").append("\n");
            if (!ok) {
                sb.append("\n  常见原因与对策：\n");
                sb.append("   1) 启动器不在 nativeLibraryDir。Android 10+ 禁止 exec app 私有目录中的二进制\n");
                sb.append("      （targetSdk>=29，SELinux 规则，与文件权限位无关）。\n");
                sb.append("      对策：构建时把启动器放进 lib/arm64-v8a/libr2.so，由系统在安装期解压。\n");
                sb.append("      本机状态：libr2.so 存在 = ").append(new File(getLibDir(), "libr2.so").isFile()).append("\n");
                sb.append("   2) LD_LIBRARY_PATH 未生效导致 libr_*.so 加载失败。\n");
                sb.append("   3) 设备 ABI 不匹配（本项目只内置 arm64-v8a）。\n");
                sb.append("  → 以上都不影响纯 Java 的 ELF 分析（analyze_file / analyze_so 的第 1 层）。\n");
            }
        } catch (Exception e) {
            sb.append("  !! 试跑异常: ").append(e).append("\n");
        }
        return sb.toString();
    }

    /**
     * 适配 NativeAnalyzer / AnalysisRun 需要的最小执行接口。
     * 这样分析逻辑（NativeAnalyzer）可以完全不依赖 android.*，在桌面用真 r2 原样回归。
     */
    public static NativeAnalyzer.Runner runner(final R2Bridge b) {
        return new NativeAnalyzer.Runner() {
            public String run(String target, String[] cmds, int timeoutSec) throws Exception {
                return b.r2(target, cmds, timeoutSec);
            }
        };
    }

    // ---------------- 资源释放 ----------------

    private void copyAsset(String asset, File dst) throws Exception {
        InputStream is = ctx.getAssets().open(asset);
        dst.getParentFile().mkdirs();
        OutputStream os = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
        os.close();
        is.close();
    }

    /** 把 assets 里的 zip 流式解压到 outDir（按字节上报进度） */
    private void unzipAssetTo(String asset, File outDir, Progress p) throws Exception {
        File tmp = new File(ctx.getCacheDir(), "r2share_tmp.zip");
        copyAsset(asset, tmp);
        ZipFile zf = new ZipFile(tmp);
        try {
            List<ZipEntry> list = new ArrayList<ZipEntry>();
            long totalBytes = 0;
            int totalFiles = 0;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                list.add(e);
                if (!e.isDirectory()) {
                    totalFiles++;
                    if (e.getSize() > 0) totalBytes += e.getSize();
                }
            }
            if (p != null) p.onProgress(6, "解压 radare2 数据（" + totalFiles + " 个文件）…");

            long done = 0;
            int count = 0;
            int lastPct = -1;
            byte[] buf = new byte[65536];
            for (ZipEntry e : list) {
                File out = new File(outDir, e.getName());
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                InputStream is = zf.getInputStream(e);
                OutputStream os = new FileOutputStream(out);
                int n;
                while ((n = is.read(buf)) > 0) { os.write(buf, 0, n); done += n; }
                os.flush();
                os.close();
                is.close();
                count++;
                if (p != null && totalBytes > 0) {
                    int pct = 6 + (int) (90L * done / totalBytes);
                    if (pct >= lastPct + 5 || count == totalFiles) {
                        lastPct = pct;
                        p.onProgress(pct, "解压 radare2 数据 " + count + "/" + totalFiles + "（" + pct + "%）");
                    }
                }
            }
        } finally {
            try { zf.close(); } catch (Exception ignore) { /* ignore */ }
            try { tmp.delete(); } catch (Exception ignore) { /* ignore */ }
        }
    }

    private void chmodTree(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) chmodTree(f);
            else {
                f.setReadable(true, false);
                f.setWritable(true, false);
                f.setExecutable(false, false);
            }
        }
    }
}
