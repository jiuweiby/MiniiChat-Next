package com.rev.mcptool;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;

/**
 * 外部存储访问能力诊断。
 *
 * 背景：RevMCP 读宿主路径（如 /storage/emulated/0/MT2/apks/xxx.apk）长期 EACCES，
 * 根因是 Manifest 里 READ_EXTERNAL_STORAGE 带 maxSdkVersion="32"，
 * 在 targetSdk=34 上该权限直接失效 —— 应用从来就没有合法的外部存储读权限。
 *
 * Android 上能读 /storage/emulated/0 下任意第三方目录的途径只有两条：
 *   1) MANAGE_EXTERNAL_STORAGE（"所有文件访问权限"）：用户在设置页手动授予，
 *      授予后可直接用文件路径读，copy_to_input / analyze_file 都能直接跑。
 *   2) SAF（ACTION_OPEN_DOCUMENT）：零权限，用户在系统文件选择器里选中文件即可，
 *      拿到的是 content:// URI，由系统临时授权读取。
 *
 * 本类负责把"当前走哪条路、路通不通"讲清楚，并给出对应的跳转 Intent。
 */
public final class StoragePerm {

    private StoragePerm() { }

    /** 是否拥有"所有文件访问权限"（API 30+）；低于 30 时看 READ_EXTERNAL_STORAGE */
    public static boolean hasAllFilesAccess(Context ctx) {
        if (ctx == null) return false;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Exception e) {
                return false;
            }
        }
        return hasReadExternal(ctx);
    }

    /** 是否拥有传统 READ_EXTERNAL_STORAGE 运行时权限 */
    public static boolean hasReadExternal(Context ctx) {
        if (ctx == null) return false;
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return ctx.checkSelfPermission(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 实际试读一个文件，判断这条路径当前到底通不通（权限声明 ≠ 真的能读） */
    public static boolean canRead(String path) {
        if (path == null || path.length() == 0) return false;
        File f = new File(path);
        if (!f.exists()) return false;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[1];
            return in.read(b) >= 0 || true;   // 能打开即视为可读（0 字节文件也算）
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignore) { /* ignore */ }
        }
    }

    /**
     * 生成一段人话状态说明，直接给 UI 或 MCP 工具展示。
     * 会同时说明"当前能不能用文件路径读"和"不能的话该怎么办"。
     */
    public static String describe(Context ctx) {
        StringBuilder sb = new StringBuilder();
        boolean all = hasAllFilesAccess(ctx);
        boolean legacy = hasReadExternal(ctx);

        sb.append("所有文件访问(MANAGE_EXTERNAL_STORAGE) : ").append(all ? "已授予" : "未授予").append("\n");
        if (Build.VERSION.SDK_INT < 30) {
            sb.append("READ_EXTERNAL_STORAGE                 : ")
                    .append(legacy ? "已授予" : "未授予").append("\n");
        }

        if (all) {
            sb.append("\n可以直接用绝对路径读 /storage/emulated/0 下的任意文件，\n");
            sb.append("copy_to_input / analyze_file 传宿主路径即可。\n");
        } else {
            sb.append("\n此时用绝对路径读 /storage/emulated/0/MT2/ 等第三方目录会 EACCES。\n");
            sb.append("两条可用出路：\n");
            sb.append("  1) 在系统设置页给本应用打开「允许访问所有文件」\n");
            sb.append("     （界面点「选择目录」会把你引导到那一页），之后可直接用绝对路径分析 APK。\n");
            sb.append("  2) 改走系统选择器：界面「选择 APK / .SO 文件分析」，或 MCP 的 copy_to_input；\n");
            sb.append("     文件会被拷进 input/ 暂存目录并给出可用路径（零权限通道）。\n");
        }
        return sb.toString();
    }

    /** 单行摘要，给 service_status 这类需要紧凑输出的地方用 */
    public static String brief(Context ctx) {
        if (hasAllFilesAccess(ctx)) return "全部文件可直读（宿主路径可用）";
        return "未授权，宿主路径会 EACCES —— 改用系统选择器选文件，或在设置页打开「允许访问所有文件」";
    }

    /**
     * 跳转到"所有文件访问"授权页。
     * API 30+ 走 ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION（设置页，需用户手动开），
     * 低版本退化为请求 READ_EXTERNAL_STORAGE 运行时权限（由调用方发起 requestPermissions）。
     */
    public static Intent allFilesIntent(Context ctx) {
        if (ctx == null) return null;
        if (Build.VERSION.SDK_INT >= 30) {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            return i;
        }
        return null;
    }

    /** 低版本（<30）需要请求的运行时权限数组 */
    public static String[] legacyPermissions() {
        return new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    /**
     * 产物根目录的**推荐位置**（只是推荐，不再自动使用）：
     * {@code /storage/emulated/0/REVMCP}，就在存储根目录第一层，
     * 任何文件管理器打开都能直接看到。
     *
     * <p>为什么不再自动用：Android 11+ 没有「所有文件访问权限」时，这个目录是
     * **建得出来、写不进去**的（{@code mkdirs()} 成功、写文件 EACCES）。
     * 自动使用它会让用户看到目录以为成功，产物却落在别处 —— 所以现在改成
     * 由用户在界面上用「选择目录」亲手指定（{@link OutDir}），
     * 本方法只用来给界面提供一条「推荐目录」和给旧目录提示用。
     *
     * <p>同时提供旧目录清单：历史版本用过 {@code revmcp}（小写）与 {@code RevMCP}，
     * 里面的历史产物用户可自行迁移或清理（{@link RevPaths#describe()} 会把它们列出来）。
     */
    public static File suggestedDir() {
        try {
            File sd = Environment.getExternalStorageDirectory();
            return sd == null ? null : new File(sd, RevPaths.DIR_NAME);
        } catch (Throwable ignore) { /* 桌面回归 / 异常环境 */ }
        return null;
    }

    /** 存储根目录下历史版本用过的产物目录（{@code revmcp/}、{@code RevMCP/}），仅用于提示 */
    public static File[] legacyDirs() {
        try {
            File sd = Environment.getExternalStorageDirectory();
            if (sd == null) return new File[0];
            File[] out = new File[RevPaths.LEGACY_DIR_NAMES.length];
            for (int i = 0; i < RevPaths.LEGACY_DIR_NAMES.length; i++) {
                out[i] = new File(sd, RevPaths.LEGACY_DIR_NAMES[i]);
            }
            return out;
        } catch (Throwable ignore) { /* ignore */ }
        return new File[0];
    }
}
