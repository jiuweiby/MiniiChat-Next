package com.rev.mcptool;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Flutter / Dart AOT 信息提取与 blutter 调度指引。
 *
 * 主路径是纯 Java 的 {@link DartInfo}（等价 blutter 上游的 extract_dart_info.py），
 * 直接解出 **Dart 版本 / snapshot hash / features / engine id** —— 这正是决定
 * "该用哪个版本的 blutter 二进制"的全部依据。
 *
 * 输入可以是：
 *   - 一个 APK（自动从 lib/&lt;abi&gt;/ 里取出 libapp.so 与 libflutter.so）
 *   - 一个 libapp.so（自动找同目录的 libflutter.so）
 *
 * r2 侧探测作为补充（magic/字符串交叉印证），不做为主判定。
 */
public class FlutterProbe {

    private static final String DART_MAGIC_HEX = "f5f5dcdc"; // 0xdcdcf5dcdc 链的小端写法（iI 里为 0xdcdcf5

    /** 兼容旧调用：只给一个 .so 路径 */
    public static String probe(Context ctx, String soPath, String outDir) throws Exception {
        return probeAny(ctx, soPath, outDir);
    }

    /**
     * @param target APK 路径 或 libapp.so 路径
     * @param outDir 输出/解包目录（APK 输入时用；为空则用 App 缓存目录）
     */
    public static String probeAny(Context ctx, String target, String outDir) throws Exception {
        File t = new File(target);
        if (!t.isFile()) throw new IllegalArgumentException("找不到文件: " + target);

        StringBuilder sb = new StringBuilder();
        sb.append("== Flutter / Dart AOT 信息提取 ==\n");
        sb.append("目标: ").append(t.getAbsolutePath())
                .append("  (").append(t.length() / 1024).append(" KB)\n");

        File libapp;
        File libflutter;
        String fromApk = null;

        if (target.toLowerCase().endsWith(".apk")) {
            String dir = (outDir != null && !outDir.isEmpty())
                    ? outDir
                    : new File(ctx == null ? new File(".") : ctx.getCacheDir(), "flutter_probe").getAbsolutePath();
            sb.append("模式: APK —— 从 lib/<abi>/ 提取 libapp.so 与 libflutter.so 到 ").append(dir).append("\n");
            libapp = extractFirst(t, "libapp.so", dir);
            libflutter = extractFirst(t, "libflutter.so", dir);
            if (libapp == null) {
                sb.append("✗ APK 内未找到 libapp.so —— 可能不是 Flutter 应用（或该 APK 走 "
                        + "kernel_blob.bin 的 debug 构建，无 AOT snapshot）\n");
                if (libflutter != null) sb.append("  （但存在 libflutter.so，说明是 Flutter 引擎壳）\n");
                sb.append("  提示: 用 list_apk_entries 确认 lib/ 下的实际文件名\n");
                appendGuide(sb);
                return sb.toString();
            }
            fromApk = t.getName();
        } else {
            libapp = t;
            File parent = t.getParentFile();
            libflutter = parent == null ? null : new File(parent, "libflutter.so");
            sb.append("模式: 单个 .so —— 同目录 libflutter.so "
                    + (libflutter != null && libflutter.isFile() ? "已找到" : "未找到") + "\n");
        }

        sb.append("\n== Dart AOT 指纹 ==\n");
        DartInfo d = DartInfo.analyze(libapp, libflutter);
        sb.append(d.describe());

        // ---- 结论：直接给出"该用哪个 blutter" ----
        sb.append("\n== 结论：blutter 版本选择 ==\n");
        if (d.dartVersion != null) {
            sb.append("✓ Dart ").append(d.dartVersion).append("\n");
            sb.append("· 上游 blutter 的目录名形式: dartvm").append(d.dartVersion)
                    .append("_android_").append(d.arch).append("\n");
            sb.append("· 若使用「按版本预编译好的 blutter 二进制」方案，请挑 **二进制自身 Dart 版本 == "
                    + d.dartVersion + "** 的那个文件\n");
            sb.append("  ⚠ 不要按文件名猜版本：实测存在文件名与实际版本不一致的情况\n");
            sb.append("    （例如名为 3_0_90 的实为 3.0.3、名为 3_6 的实为 3.6.1）。\n");
            sb.append("    本工具在给出结论时读的就是二进制自身的版本串，可直接信任。\n");
            if (d.hasCompressedPtrs()) {
                sb.append("· features 含 compressed-pointers —— 需 **默认** 版 blutter（Android 常规构建）\n");
            } else if (!d.flags.isEmpty()) {
                sb.append("· features 不含 compressed-pointers —— 需 **_no-compressed-ptrs** 版 blutter\n");
            } else {
                sb.append("· features 未取到（缺 libapp.so）—— 指针压缩与否会影响 blutter 变体选择\n");
            }
            if (!d.engineIds.isEmpty()) {
                sb.append("· engine id 可用于反查 Flutter 引擎版本（共 ").append(d.engineIds.size()).append(" 个）\n");
            }
        } else {
            sb.append("? 未能确定 Dart 版本");
            if (libflutter == null || !libflutter.isFile()) {
                sb.append(" —— 缺 libflutter.so。请用 unpack_apk_libs 解出整组库后再探测。\n");
            } else {
                sb.append(" —— libflutter.so 的 .rodata 里没有 ` (stable) (` 标记，"
                        + "可能是 beta/dev 通道版本（上游 blutter 此时会去下载 dart-sdk 头 4KB 反查）。\n");
            }
        }

        // ---- r2 侧交叉印证（可选，失败不影响上面结论）----
        if (ctx != null) {
            sb.append("\n== r2 交叉印证（辅助）==\n");
            try {
                R2Bridge r2 = new R2Bridge(ctx);
                String out = r2.r2(libapp.getAbsolutePath(), new String[]{
                        "?e [snapshot magic 0xdcdcf5 出现次数]",
                        "/x " + DART_MAGIC_HEX,
                        "?e [Dart 相关符号]",
                        "is~Dart",
                        "?e [package:/dart 字符串抽样]",
                        "izz~package:",
                        "izz~.dart"
                }, 240);
                sb.append(out).append("\n");
            } catch (Exception e) {
                sb.append("[r2 探测跳过] ").append(e.getMessage()).append("\n");
            }
        }

        if (fromApk != null) sb.append("\n(input APK: ").append(fromApk).append(")\n");
        if (outDir != null && !outDir.isEmpty()) {
            sb.append("· 建议输出目录: ").append(new File(outDir).getAbsolutePath()).append("\n");
        }
        appendGuide(sb);
        return sb.toString();
    }

    /** 从 APK 里找第一个 lib/<abi>/&lt;leaf&gt; 并写到 dir */
    private static File extractFirst(File apk, String leaf, String dir) throws IOException {
        List<String> names = ApkPack.entryNames(apk.getAbsolutePath());
        String hit = null;
        for (String n : names) {
            if (n.startsWith("lib/") && n.endsWith("/" + leaf)) { hit = n; break; }
        }
        if (hit == null) return null;
        File outDir = new File(dir);
        if (!outDir.isDirectory() && !outDir.mkdirs() && !outDir.isDirectory()) {
            throw new IOException("无法创建目录: " + dir);
        }
        byte[] data = ApkPack.readEntry(apk.getAbsolutePath(), hit);
        File out = new File(outDir, leaf);
        FileOutputStream fo = new FileOutputStream(out);
        try {
            fo.write(data);
        } finally {
            fo.close();
        }
        return out;
    }

    private static void appendGuide(StringBuilder sb) {
        sb.append("\n== 后续：符号还原路径 ==\n");
        sb.append("blutter 的 C++ 工具必须链接与目标 Dart 版本严格对应的 Dart VM 静态库。\n");
        sb.append("**构建期**需要 Dart SDK 源码 + CMake/ninja 交叉编译；**运行期**只需要一个\n");
        sb.append("已编译好的、与 Dart 版本匹配的 blutter 可执行体 —— 所以「按版本预编译再内置」是可行的。\n\n");

        sb.append("[A] 桌面 / Linux（推荐）\n");
        sb.append("  git clone https://github.com/worawit/blutter && cd blutter\n");
        sb.append("  python3 -m pip install pyelftools requests\n");
        sb.append("  # 需要 cmake / ninja / clang>=16 / libicu-dev / capstone\n");
        sb.append("  python3 blutter.py <libdir> <outdir>\n");
        sb.append("  → 首次按检测到的 Dart 版本编译 Dart VM（较慢），之后复用缓存\n");
        sb.append("  → 产出 asm/*.asm（带符号反汇编）、pp.txt（对象池 dump）、objs.txt、ida_script/\n\n");

        sb.append("[B] 纯手机（Termux，无需电脑）\n");
        sb.append("  pkg install -y git cmake ninja build-essential pkg-config libicu capstone fmt python\n");
        sb.append("  pip install requests pyelftools\n");
        sb.append("  git clone https://github.com/Neywerson/blutter-termux && cd blutter-termux\n");
        sb.append("  python3 blutter.py <libdir> <outdir>\n\n");

        sb.append("[C] 不用 blutter：r2 去符号分析\n");
        sb.append("  · r2_strings 过滤 \".dart\" / \"package:\" → 定位 Dart 库与类名字符串\n");
        sb.append("  · pp.txt 里形如 [package:xxx/yyy.dart] Class::method (0xaddr) 的条目\n");
        sb.append("    可直接得到**函数地址 ↔ 源位置的对应**，据此在 r2 里 aaf 后按地址定位函数\n");
        sb.append("  · r2_search type=hex 定位 ObjectPool / 常量表引用，r2_xrefs 追引用链\n");
    }
}
