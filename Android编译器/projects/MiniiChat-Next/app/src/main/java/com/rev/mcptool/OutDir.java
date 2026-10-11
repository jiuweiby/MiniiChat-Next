package com.rev.mcptool;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.File;

/**
 * 产物根目录的**用户选择**链路（唯一入口）。
 *
 * <p>为什么要有这个类：产物路径以前是应用自己猜的（{@code REVMCP/}、小写 {@code revmcp/}、
 * {@code Android/data/…/files/}、cache…）。猜的后果是「目录建出来了、产物却写在别处」——
 * 没拿到「所有文件访问权限」时，公共目录是**建得出来、写不进去**的，而应用又在猜错后
 * 静默换了落点，用户只能靠 OS 文件管理器去猜哪个目录才是真的。
 *
 * <p>现在改成**用户指定**：
 * <ol>
 *   <li>界面点「选择目录」→ 系统目录选择器（{@code ACTION_OPEN_DOCUMENT_TREE}）；</li>
 *   <li>选中的树 URI 换回**真实绝对路径**（{@link RevPaths#absFromDocId}）——
 *       blutter / radare2 是子进程，只认文件系统路径，{@code content://} 给不了它们；</li>
 *   <li>真的写一个探针文件验证可写（{@link RevPaths#canWrite}），
 *       然后连同 URI 一起落 {@link RevPrefs}；</li>
 *   <li>{@link #apply(Context)} 把选择灌给 {@link RevPaths} ——
 *       界面进程与 MCP 服务进程都调它，保证两边用的是同一个目录。</li>
 * </ol>
 *
 * <p><b>为什么还要求「所有文件访问权限」</b>：SAF 给的是 {@code content://} 读写信道，
 * 但 blutter / r2 需要真实路径，本工具的全部产物路径都是绝对路径。
 * 所以路径模式下必须拿到该权限；没拿到时不会假装成功 ——
 * 选择会被记下来，但 {@link RevPaths#describe()} 会写明「已选目录不可写」和当前实际落点。
 */
public final class OutDir {

    private OutDir() { }

    // ===== 选择 / 读取 / 清除 =====

    /** 用户当前选定的产物根目录；未选择返回 null */
    public static File chosen(Context ctx) {
        if (ctx == null) return null;
        String p = RevPrefs.getOutDirPath(ctx);
        return (p == null) ? null : new File(p);
    }

    /** 把当前选择灌给 {@link RevPaths}（未选择时 → 暂用 app 私有目录并把状态标为「未选择」） */
    public static void apply(Context ctx) {
        File fb = privateRoot(ctx);
        RevPaths.configureChosen(chosen(ctx), fb);
    }

    /** 清除选择（回到「未选择」状态，不影响已落盘的产物） */
    public static void clear(Context ctx) {
        if (ctx == null) return;
        RevPrefs.setOutDir(ctx, null, null);
        apply(ctx);
    }

    /** app 私有外部目录 {@code Android/data/<pkg>/files/REVMCP}（拿不到存储权限时的过渡落点） */
    public static File privateRoot(Context ctx) {
        File base = null;
        if (ctx != null) {
            try { base = ctx.getExternalFilesDir(null); } catch (Exception e) { base = null; }
            if (base == null) {
                try { base = ctx.getFilesDir(); } catch (Exception e) { base = null; }
            }
        }
        return base == null ? null : new File(base, RevPaths.DIR_NAME);
    }

    // ===== SAF：树 URI ↔ 真实路径 =====

    /** 系统目录选择器 Intent（只让选目录，允许新建） */
    public static Intent picker(Context ctx) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        Uri init = initialUri(ctx);
        if (init != null && Build.VERSION.SDK_INT >= 26) {
            try { i.putExtra("android.provider.extra.INITIAL_URI", init); } catch (Exception ignore) { /* 旧 ROM */ }
        }
        return i;
    }

    /**
     * 目录选择器的初始位置：优先上次选过的目录，否则尝试「内部存储根目录」
     * （用户多半就在那里新建一个目录）。返回 null 时系统用默认位置。
     */
    public static Uri initialUri(Context ctx) {
        try {
            String saved = RevPrefs.getOutDirUri(ctx);
            if (saved != null && saved.length() > 0) return Uri.parse(saved);
        } catch (Exception ignore) { /* ignore */ }
        return treeUriOf(externalRoot() == null ? null : externalRoot().getAbsolutePath());
    }

    /** 树 URI → 真实绝对路径。不支持的位置（如 Downloads 提供方）返回 null */
    public static String realPathOf(Uri treeUri) {
        if (treeUri == null) return null;
        String docId = null;
        try { docId = DocumentsContract.getTreeDocumentId(treeUri); } catch (Exception ignore) { /* ignore */ }
        if (docId == null) {
            // 兜底：URI 末段形如 "primary%3AREVMCP"
            try {
                String seg = treeUri.getLastPathSegment();
                if (seg != null && seg.indexOf(':') > 0) docId = seg;
            } catch (Exception ignore) { /* ignore */ }
        }
        File sd = externalRoot();
        String primary = sd == null ? null : sd.getAbsolutePath();
        return RevPaths.absFromDocId(docId, primary, "/storage");
    }

    /** 绝对路径 → 该路径所在卷的树 URI（只支持内部存储；用于给选择器定位初始位置） */
    public static Uri treeUriOf(String absPath) {
        if (absPath == null || absPath.length() == 0) return null;
        File sd = externalRoot();
        if (sd == null) return null;
        try {
            String base = sd.getAbsolutePath();
            String rel;
            if (absPath.equals(base)) rel = "";
            else if (absPath.startsWith(base + File.separator)) rel = absPath.substring(base.length() + 1);
            else return null;                       // 外置卷/私有目录：不给初始位置
            rel = rel.replace('\\', '/');
            StringBuilder u = new StringBuilder(
                    "content://com.android.externalstorage.documents/tree/primary%3A");
            for (int i = 0; i < rel.length(); i++) {
                char c = rel.charAt(i);
                if (c == '/') u.append("%2F");
                else if (c == '%') u.append("%25");
                else if (c > 127) u.append(Uri.encode(String.valueOf(c)));
                else u.append(c);
            }
            return Uri.parse(u.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** 内部存储根目录（{@code /storage/emulated/0}） */
    public static File externalRoot() {
        try { return Environment.getExternalStorageDirectory(); } catch (Exception e) { return null; }
    }

    /**
     * 选到「存储根目录本身」时，落点会变成「根目录下直接铺 logs/、work/」——
     * 太脏。这里统一收进根目录下的 {@link RevPaths#DIR_NAME} 子目录，
     * 并把最终路径回显给用户（不是静默改写：界面显示的就是最终路径）。
     */
    public static File normalize(File dir) {
        if (dir == null) return null;
        File sd = externalRoot();
        File storage = new File("/storage");
        try {
            if (sd != null && dir.getAbsolutePath().equals(sd.getAbsolutePath())) {
                return new File(dir, RevPaths.DIR_NAME);
            }
            if (dir.getAbsolutePath().equals(storage.getAbsolutePath())) {
                return new File(dir, RevPaths.DIR_NAME);
            }
        } catch (Exception ignore) { /* ignore */ }
        return dir;
    }

    // ===== 选择结果（给界面一次性拿走全部结论） =====

    /** 一次「选择目录」的结果：路径、是否可写、以及给用户看的一句话 */
    public static final class Pick {
        public final File dir;      // 最终的产物根目录（已 normalize），失败时为 null
        public final boolean writable;
        public final String message; // 直接 Toast / 显示

        Pick(File dir, boolean writable, String message) {
            this.dir = dir;
            this.writable = writable;
            this.message = message;
        }
    }

    /**
     * 处理系统目录选择器返回的树 URI：解析真实路径 → 写探针 → 落盘 → 生效。
     * 会就地完成 {@link RevPrefs} 持久化与 {@link #apply(Context)}，调用方只管显示结果。
     */
    public static Pick accept(Context ctx, Uri treeUri) {
        if (ctx == null) return new Pick(null, false, "内部错误：无 Context");
        String path = realPathOf(treeUri);
        if (path == null) {
            return new Pick(null, false,
                    "这个位置不支持：请在「内部存储」下选一个目录（可以现场新建）");
        }
        File dir = normalize(new File(path));
        boolean can = RevPaths.canWrite(dir);
        try {
            RevPrefs.setOutDir(ctx, dir.getAbsolutePath(), treeUri == null ? "" : treeUri.toString());
        } catch (Exception ignore) { /* ignore */ }
        apply(ctx);
        if (can) {
            return new Pick(dir, true, "产物目录已设为 " + dir.getAbsolutePath()
                    + "\n以后所有产物（logs/ input/ work/ out/）都落在这里");
        }
        return new Pick(dir, false, "已记住该目录，但它现在**写不进去**："
                + dir.getAbsolutePath()
                + "\n需要「所有文件访问权限」（重选一次「选择目录」会引导你去授权）\n"
                + "在此之前产物暂时写在：" + RevPaths.root().getAbsolutePath());
    }

    /** 界面「产物目录」一行的文案（未选择 / 已选 / 已选但不可写，三种都写清楚） */
    public static String label(Context ctx) {
        File dir = RevPaths.root();
        StringBuilder sb = new StringBuilder();
        File chosen = RevPaths.chosen();
        if (chosen != null) {
            sb.append("产物目录: ").append(chosen.getAbsolutePath());
            sb.append(RevPaths.isPublic() ? "   ← 已生效" : "   ← 已选，但当前写不进去");
        } else {
            sb.append("产物目录: 未选择（点「选择目录」指定）");
        }
        if (!RevPaths.isPublic() && dir != null) {
            sb.append("\n  实际写入: ").append(dir.getAbsolutePath());
            sb.append(RevPaths.isChosen() && chosen != null
                    ? "   ⚠ 选了但不可写（缺「所有文件访问权限」）"
                    : "   ⚠ 过渡位置，选定目录后自动切换");
        }
        sb.append("\n  logs/ 报告 ｜ input/ 暂存副本 ｜ work/ 解析产物 ｜ out/ 改包");
        return sb.toString();
    }
}
