package com.rev.mcptool;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 统一输出路径：**所有**产物（分析日志 / 解析产物 / 改包产物）都落在**同一个根目录**下，
 * 用系统文件管理器（或 MT 管理器）打开就能直接看到，不用去翻 {@code Android/data/}。
 *
 * <pre>
 * &lt;产物根&gt;/                       ← 由用户在界面上用系统目录选择器指定
 *   ├─ logs/    analysis.log（分析报告） / server.log（访问流水）
 *   ├─ input/   从宿主目录（MT2/ 等）拷进来的 APK / so 副本
 *   ├─ work/    MCP 调用解析与手动选择解析的全部产物，一个目标一个子目录
 *   └─ out/     打补丁 / 替换条目后产出的 APK
 * </pre>
 *
 * <p><b>产物根由用户选定</b>（{@link #configureChosen(File, File)}）：以前是应用
 * 自己猜位置 —— 小写 {@code revmcp/}、私有 {@code Android/data/…/files/REVMCP/}、
 * cache {@code /data/user/0/<pkg>/cache/REVMCP/}，还试过统一到
 * {@code /storage/emulated/0/REVMCP/}。猜错的代价很实际：没拿到「所有文件访问权限」时
 * 公共目录**建得出来却写不进去**，用户看到目录以为成了，产物其实一直写在旧路径。
 * 现在改成：界面上点「选择目录」让用户亲手指定，选择持久化在 {@code RevPrefs}，
 * 未选择时明确显示「未选择」并暂用 app 私有目录（不假装成功）。
 *
 * <p><b>权限回落必须可升级</b>：Android 11+ 往公共根目录写文件需要「所有文件访问权限」。
 * 拿不到时**不静默换目录**，而是回落到 app 私有外部目录，并把当前实际用的路径
 * 通过 {@link #describe()} 明确报出来（界面与 service_status 都会显示）。
 * 这种回落**必须保持可升级**（{@link #provisional()}）：早期实现把它当成终局决定
 * （provisional=false），用户在设置页补齐权限回来后**再也切不回去** ——
 * 现象就是「手机存储里建出了 REVMCP 目录，产物却一直写在旧路径」。
 *
 * <p><b>「临时目录」必须可升级（重要）</b>：初始化顺序天生不确定 —— 界面在
 * {@code onCreate} 里读一次日志目录、service 可能在别处先起，任何一方只要
 * **早于** {@code configure()} 碰到本类，就会先落到兜底临时目录。
 * Android 上 {@code java.io.tmpdir} 就是 app 的 cache 目录，于是产物会跑到
 * {@code /data/user/0/<pkg>/cache/REVMCP/} 这种既隐藏又会被系统清理的地方。
 * 所以兜底产生的 root 标记为 {@link #provisional()}，**允许**后续
 * {@code configure()} 用真正的候选目录覆盖它；只有拿到「正式」目录后才不再变动。
 *
 * <p>本类刻意**不 import 任何 android.\*** —— 公共根目录与私有根目录由持有 Context 的
 * 调用方（{@link McpServer} / {@link MainActivity}）算好传进来，
 * 这样桌面回归（无 Context）也能原样跑。
 */
public final class RevPaths {

    /** 目录名（即 {@code /storage/emulated/0/REVMCP}） */
    public static final String DIR_NAME = "REVMCP";

    /**
     * 历史目录名，**只用于搬迁，永不新建**：
     * <ul>
     *   <li>{@code revmcp} —— 上一版的公共目录名（小写）</li>
     *   <li>{@code RevMCP} —— 更早一版的私有/cache 目录名</li>
     * </ul>
     * 统一成「用户选定目录」后，这两个名字只出现在「旧目录提示」里
     * （见 {@code StoragePerm#legacyDirs()} 与 {@link #describe()}）。
     */
    public static final String[] LEGACY_DIR_NAMES = { "revmcp", "RevMCP" };

    private static volatile File root;
    private static volatile boolean pub;
    /** root 是否只是「临时兜底」——true 时允许后续 configure() 换成真正的候选目录 */
    private static volatile boolean provisional = true;
    private static volatile String why = "未初始化";
    private static volatile File fallback;
    private static volatile File wanted;
    /** 用户在界面上手选的产物目录（null = 尚未选择） */
    private static volatile File chosen;
    /** 是否处于「用户已选定」模式：此时自动 configure() 不得覆盖用户的选择 */
    private static volatile boolean userPicked;

    private RevPaths() {}

    /**
     * 选定根目录。
     *
     * <p>可重复调用：一旦定下**正式**目录就不再改（避免运行中路径漂移）；
     * 但如果当前 root 只是临时兜底（{@link #provisional()}），
     * 而这次给的候选可用，则升级过去 —— 这正是「先启动后授权」场景需要的。
     *
     * @param publicRoot   期望的公共根目录（{@code /storage/emulated/0/REVMCP}），可为 null
     * @param fallbackRoot 回落目录（{@code …/Android/data/pkg/files/REVMCP}），可为 null
     */
    public static synchronized void configure(File publicRoot, File fallbackRoot) {
        if (fallbackRoot != null) fallback = fallbackRoot;
        if (publicRoot != null) wanted = publicRoot;

        // 用户已经手选过目录：自动策略一律让位（运行中更不能悄悄把路径挪走）
        if (userPicked && root != null) {
            ensureDirs();
            return;
        }

        if (root != null && !provisional) {   // 已是正式目录：不动
            ensureDirs();
            return;
        }

        if (publicRoot != null && usable(publicRoot)) {
            root = publicRoot;
            pub = true;
            provisional = false;
            why = "公共存储根目录（文件管理器可直接看到）";
        } else if (fallbackRoot != null && usable(fallbackRoot)) {
            root = fallbackRoot;
            pub = false;
            // **必须是 provisional**：私有目录是「拿到权限前暂时用」，不是终局。
            // 否则「先启动、后授权」的机器会永远把产物写在 Android/data/ 里 ——
            // 界面上看得到 /storage/emulated/0/REVMCP 目录，产物却在旧路径，正是这个坑。
            provisional = true;
            why = publicRoot == null
                    ? "app 私有外部目录（未提供公共根目录，非最终位置）"
                    : "app 私有外部目录（公共根目录不可写：未授予「所有文件访问权限」，非最终位置）";
        } else {
            // 候选都不可写（或压根没给）：兜底到**确实能写**的临时目录。
            //
            // 这里刻意不去选「看起来更对」的那个候选路径 —— 选一个不可写的目录当 root，
            // 后面所有 mkdirs / 写文件都会失败，比路径不好看严重得多。
            // 期望目录记在 wanted 里，授权后再 configure 一次即可升级过去。
            //
            // **必须标记 provisional** —— 否则「先启动、后授权」就永远切不回去了
            // （Android 上 java.io.tmpdir 就是 app 的 cache 目录，
            //   被钉死在那里会导致产物既隐藏、又会被系统清理）。
            File t = new File(System.getProperty("java.io.tmpdir", "."), DIR_NAME);
            root = t;
            pub = false;
            provisional = true;
            why = usable(t)
                    ? "临时目录（候选目录不可写：未授予「所有文件访问权限」；"
                        + "授权后重启服务会自动切到正式目录）"
                    : "临时目录也不可写，路径可能无法落盘（环境异常）";
        }
        ensureDirs();
    }

    /**
     * 采用**用户在界面上手选**的产物目录（界面的「选择目录」→ 系统目录选择器）。
     *
     * <p>与 {@link #configure(File, File)} 的区别是「谁说了算」：本方法是用户显式指定，
     * 因此**无条件生效**（可以把路径从 A 换到 B，不受 sticky 限制），并且此后自动策略不再覆盖它。
     *
     * <p>选了但写不进去（典型：没给「所有文件访问权限」）时，**不静默改落点**：
     * root 退回可写的私有/临时目录，但 {@link #describe()} 会同时写出
     * 「已选目录」和不可写原因，界面也照原样显示 —— 用户一眼能看出「我选的没生效、为什么没生效」。
     *
     * @param dir          用户选定的目录，null = 清除选择（回到「未选择」状态）
     * @param fallbackRoot 选不了时的回落目录（app 私有外部目录）
     */
    public static synchronized void configureChosen(File dir, File fallbackRoot) {
        if (fallbackRoot != null) fallback = fallbackRoot;
        chosen = dir;
        userPicked = dir != null;

        if (dir != null && usable(dir)) {
            root = dir;
            pub = true;
            provisional = false;
            why = "用户选定目录（界面「选择目录」）";
        } else {
            File r = (fallbackRoot != null && usable(fallbackRoot)) ? fallbackRoot : null;
            if (r == null) r = new File(System.getProperty("java.io.tmpdir", "."), DIR_NAME);
            root = r;
            pub = false;
            provisional = true;     // 保持可升级：下次选定 / 授权后调一次即生效
            if (dir != null) {
                why = "已选目录不可写（" + dir.getAbsolutePath()
                        + "：需要「所有文件访问权限」）→ 暂时写入 " + r.getAbsolutePath();
            } else {
                why = "未选择产物目录（界面点「选择目录」指定；当前暂用 app 私有目录，非最终位置）";
            }
        }
        ensureDirs();
    }

    /** 用户手选的目录（可能尚未生效），未选返回 null */
    public static File chosen() { return chosen; }

    /** 是否已由用户手选目录（true 时自动策略不覆盖） */
    public static boolean isChosen() { return userPicked; }

    /**
     * 当前生效的产物根目录。
     *
     * <p>未初始化时惰性给一个兜底目录 —— 但**只标记为临时**，
     * 等持有 Context 的一方调用 {@link #configure} 时会被换成正式目录。
     * （早期实现这里直接把兜底目录当最终结果，导致产物被永久钉在
     * app 的 cache 目录里，见类注释。）
     */
    public static File root() {
        if (root == null) {
            synchronized (RevPaths.class) {
                if (root == null) {
                    // 惰性兜底：优先用已登记的私有候选（比 cache 可见、也不会被系统清），
                    // 它不可写就退到临时目录。两种情况都标 provisional，
                    // 等持有 Context 的一方调用 configure() 时会被换成正式目录。
                    File fb = fallback;
                    if (fb == null || !usable(fb)) {
                        fb = new File(System.getProperty("java.io.tmpdir", "."), DIR_NAME);
                    }
                    root = fb;
                    pub = false;
                    provisional = true;
                    why = "尚未初始化（临时目录，等待 configure 指定正式目录）";
                    ensureDirs();
                }
            }
        }
        return root;
    }

    /** 分析报告 / 访问流水 */
    public static File logs() { return child("logs"); }

    /** 解析产物（MCP 调用解析 + 手动选择解析，同一个根） */
    public static File work() { return child("work"); }

    /** 从宿主目录拷进来的 APK / so 副本 */
    public static File input() { return child("input"); }

    /** 打补丁 / 替换条目后产出的 APK，默认落这里 */
    public static File out() { return child("out"); }

    /** 日志目录的父目录（有些老代码按 log().dir().getParentFile() 推目录，兜底用） */
    public static File parentOfLogs() { return root(); }

    /** 当前用的是不是公共根目录（文件管理器可见） */
    public static boolean isPublic() { return pub; }

    /** 当前 root 是否只是临时兜底（尚未拿到正式目录） */
    public static boolean provisional() { return provisional; }

    /** 为什么选了这个目录 */
    public static String why() { return why; }

    /**
     * 期望的公共根目录（{@code /storage/emulated/0/REVMCP}）。
     * 供界面显示「授权后应该切到哪里」，未提供时返回 null。
     */
    public static File wanted() { return wanted; }

    /** 一眼看清当前路径与权限状态的说明块（界面 / service_status 直接贴） */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("产物根目录 : ").append(root().getAbsolutePath()).append("\n");
        sb.append("  来源     : ").append(why).append("\n");
        if (userPicked && chosen != null) {
            sb.append("  已选目录 : ").append(chosen.getAbsolutePath())
              .append(pub ? "（已生效）" : "（不可写，未生效）").append("\n");
        }
        if (!userPicked) {
            sb.append("  选择方式 : 界面点「选择目录」用系统目录选择器指定产物落盘位置\n");
            sb.append("  未选择   : 当前是过渡位置，选定后所有产物（含历史 work/）从下次调用起改到新目录\n");
        }
        if (provisional) {
            sb.append("  状态     : ⚠ 非最终目录（选定目录 / 授权「所有文件访问」后会自动切换）\n");
        }
        File w = wanted;
        if (!pub && w != null) {
            sb.append("  期望目录 : ").append(w.getAbsolutePath())
              .append("（授权「所有文件访问」后重启服务即可切过去）\n");
        }
        // 旧目录只剩「提示」价值：产物已按用户选择落盘，旧目录里的历史内容
        // 用户可以自行迁移或删除。
        for (File old : legacyRoots()) {
            if (old.isDirectory()) {
                sb.append("  旧目录   : ").append(old.getAbsolutePath())
                  .append("（历史位置，内容可自行迁移或删除）\n");
            }
        }
        sb.append("  结构     : logs/ 报告与流水 ｜ input/ 导入副本 ｜ work/ 解析产物 ｜ out/ 改包产物\n");
        return sb.toString();
    }

    /**
     * 可能存在的旧产物根目录（公共存储侧的历史目录名）。
     * 只用于提示与搬迁，不会新建。找不到外部存储时返回空数组。
     */
    public static File[] legacyRoots() {
        try {
            File sd = new File("/storage/emulated/0");
            if (!sd.isDirectory()) return new File[0];
            File[] out = new File[LEGACY_DIR_NAMES.length];
            for (int i = 0; i < LEGACY_DIR_NAMES.length; i++) {
                out[i] = new File(sd, LEGACY_DIR_NAMES[i]);
            }
            return out;
        } catch (Throwable t) {
            return new File[0];
        }
    }

    /** 小工具：确保目录存在，返回是否最终可用 */
    public static boolean ensure(File d) {
        if (d == null) return false;
        if (!d.exists()) d.mkdirs();
        return d.isDirectory();
    }

    /**
     * 清掉已选定的状态，回到「未初始化」。
     * <p><b>仅供桌面回归使用</b>（{@code tools/RevPathsTest.java}）——
     * 本类的状态是静态单例，测「先兜底再升级」这条路径必须能重来一次。
     * 生产代码不要调用：运行中把 root 清掉会让新旧产物分家。
     */
    public static synchronized void resetForTest() {
        root = null;
        fallback = null;
        wanted = null;
        chosen = null;
        userPicked = false;
        pub = false;
        provisional = true;
        why = "未初始化";
    }

    /**
     * f 是否位于当前产物根之内。
     * 用于识别**过期路径**：例如服务重启前广播出来的旧日志目录，
     * 或用户在界面切换目录后仍握着的老路径。
     */
    public static boolean insideRoot(File f) {
        return inside(f, root());
    }

    private static boolean inside(File f, File r) {
        if (f == null || r == null) return false;
        try {
            String c = f.getCanonicalPath();
            String b = r.getCanonicalPath();
            return c.equals(b) || c.startsWith(b + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * SAF 目录树 URI 里的 documentId → 真实绝对路径。
     *
     * <p>形如 {@code primary:REVMCP/work} → {@code /storage/emulated/0/REVMCP/work}，
     * {@code 1A2B-3C4D:out} → {@code /storage/1A2B-3C4D/out}（可移动存储）。
     *
     * <p>之所以要换回真实路径：blutter / radare2 这些子进程只认文件系统路径，
     * {@code content://} 给不了它们；而拿到「所有文件访问权限」后，
     * 应用是可以用绝对路径直接读写用户选中的目录的。
     *
     * <p>刻意写成**纯函数**（不 import android.*）：桌面回归可以拿它跑断言
     * （见 {@code tools/RevPathsTest.java}），真机上由 {@code OutDir} 传入
     * {@code Environment.getExternalStorageDirectory()} 与 {@code /storage}。
     *
     * @param docId       如 {@code "primary:REVMCP"}；不含 ':' 或为 null 时返回 null
     * @param primaryRoot 内部存储根，如 {@code /storage/emulated/0}
     * @param storageRoot 外置卷挂载点，如 {@code /storage}
     * @return 绝对路径；无法解析（如 Downloads 提供方给的 {@code "downloads"}）时返回 null
     */
    public static String absFromDocId(String docId, String primaryRoot, String storageRoot) {
        if (docId == null || primaryRoot == null) return null;
        int c = docId.indexOf(':');
        if (c < 0) return null;                       // 无卷标：不是 externalstorage 的树
        String vol = docId.substring(0, c).trim();
        String rel = docId.substring(c + 1).replace('\\', '/').trim();
        while (rel.startsWith("/")) rel = rel.substring(1);
        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        String base;
        if (vol.length() == 0 || "primary".equalsIgnoreCase(vol)) {
            base = primaryRoot;
        } else {
            String sr = (storageRoot == null || storageRoot.length() == 0) ? "/storage" : storageRoot;
            base = new File(sr, vol).getAbsolutePath();
        }
        if (rel.length() == 0) return new File(base).getAbsolutePath();
        return new File(base, rel).getAbsolutePath();
    }

    /**
     * 目录是否存在且真的能写（mkdirs 成功 ≠ 能写：Scoped Storage 下常是"建得出来写不进去"）。
     * <p>界面在用户选完目录后、以及每次选目录前都用它做一次**真实写探针**判断。
     */
    public static boolean canWrite(File d) { return usable(d); }

    private static boolean usable(File d) {
        try {
            if (!d.exists() && !d.mkdirs()) return false;
            if (!d.isDirectory() || !d.canWrite()) return false;
            File probe = new File(d, ".revmcp_write_test");
            FileOutputStream o = new FileOutputStream(probe);
            try { o.write(new byte[]{0x52}); } finally {
                try { o.close(); } catch (Exception ignore) { /* ignore */ }
            }
            if (!probe.delete()) probe.deleteOnExit();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static File child(String name) {
        File d = new File(root(), name);
        ensure(d);
        return d;
    }

    private static void ensureDirs() {
        ensure(root);
        ensure(new File(root, "logs"));
        ensure(new File(root, "work"));
        ensure(new File(root, "input"));
        ensure(new File(root, "out"));
    }
}
