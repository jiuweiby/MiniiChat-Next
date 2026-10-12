package com.rev.mcptool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * APK **只读**检查实现（纯 Java，零 android.* 依赖，可在桌面 JVM 原样验证）。
 *
 * 本工具定位是「只分析、只写日志」：
 *   分析结果 → analysis.log → AI 客户端 read_log 读取 → 交给别的工具改包。
 * 因此这里**刻意不含任何写入 APK / 签名的方法**，只保留读取与校验能力，
 * 保证服务端不可能意外改动用户文件。
 *
 * 只读能力：条目清单、条目字节、条目导出、原生库解包、签名校验。
 */
public class ApkPack {

    /** 打开详细日志（默认关） */
    public static boolean verbose = false;

    private static void log(String s) {
        if (verbose) System.out.println("[ApkPack] " + s);
    }

    // ---------------- 基础读取 ----------------

    public static byte[] readFile(File f) throws IOException {
        long len = f.length();
        if (len > Integer.MAX_VALUE - 8) throw new IOException("文件过大(>2GB): " + len);
        byte[] d = new byte[(int) len];
        FileInputStream fis = new FileInputStream(f);
        try {
            int off = 0, r;
            while (off < d.length && (r = fis.read(d, off, d.length - off)) > 0) off += r;
        } finally {
            fis.close();
        }
        return d;
    }

    // ---------------- APK 结构 ----------------

    /** 列出 APK 内全部条目及压缩方式与大小 */
    public static String listEntries(String apkPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        ZipInputStream zis = new ZipInputStream(new FileInputStream(apkPath));
        ZipEntry e;
        int n = 0, stored = 0;
        long total = 0;
        try {
            while ((e = zis.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    n++;
                    boolean isStored = e.getMethod() == ZipEntry.STORED;
                    if (isStored) stored++;
                    if (e.getSize() > 0) total += e.getSize();
                    sb.append(e.getName())
                            .append("  [").append(isStored ? "STORED" : "DEFLATE").append("]  ")
                            .append(e.getSize() >= 0 ? String.valueOf(e.getSize()) : "?").append(" bytes\n");
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        return "共 " + n + " 个条目（STORED " + stored + " / DEFLATE " + (n - stored)
                + "），解压后合计 " + total + " bytes\n" + sb;
    }

    /** 解包 APK 内所有 lib/<abi>/*.so 到 outDir（只读，不改动原 APK） */
    public static String unpackLibs(String apkPath, String outDir) throws IOException {
        File od = new File(outDir);
        od.mkdirs();
        List<String> extracted = new ArrayList<String>();
        ZipInputStream zis = new ZipInputStream(new FileInputStream(apkPath));
        ZipEntry e;
        byte[] buf = new byte[65536];
        try {
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (name.startsWith("lib/") && name.endsWith(".so") && !e.isDirectory()) {
                    File f = new File(od, name);
                    File p = f.getParentFile();
                    if (p != null) p.mkdirs();
                    FileOutputStream fos = new FileOutputStream(f);
                    try {
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    } finally {
                        fos.close();
                    }
                    extracted.add(f.getAbsolutePath() + "  (" + f.length() + " bytes)");
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("提取 ").append(extracted.size()).append(" 个 .so 到 ").append(od.getAbsolutePath()).append("\n");
        for (String p : extracted) sb.append(" - ").append(p).append("\n");
        return sb.toString();
    }

    /** 读取 APK 内指定条目的原始字节 */
    public static byte[] readEntry(String apkPath, String entryName) throws IOException {
        ZipInputStream zis = new ZipInputStream(new FileInputStream(apkPath));
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().equals(entryName)) {
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = zis.read(b)) > 0) o.write(b, 0, n);
                    return o.toByteArray();
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        throw new IOException("APK 内不存在条目: " + entryName);
    }

    /** 把 APK 内指定条目导出到 outDir，返回落盘路径（不改动原 APK） */
    public static String extractEntry(String apkPath, String entryName, String outDir) throws IOException {
        byte[] data = readEntry(apkPath, entryName);
        File od = new File(outDir);
        od.mkdirs();
        File f = new File(od, new File(entryName).getName());
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
        log("extracted " + entryName + " -> " + f.getAbsolutePath());
        return f.getAbsolutePath() + " (" + data.length + " bytes)";
    }

    /** 列出 APK 内全部条目名 */
    public static List<String> entryNames(String apkPath) throws IOException {
        List<String> names = new ArrayList<String>();
        ZipInputStream zis = new ZipInputStream(new FileInputStream(apkPath));
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.isDirectory()) names.add(e.getName());
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        return names;
    }

    // ---------------- 校验 ----------------

    /**
     * 用内置 apksig 校验 APK 签名，输出方案与证书信息（只读）。
     *
     * <p><b>关键的语义区分</b>：改包（replace_entry / 重打包）之后，
     * {@code isVerified()} 必然是 false —— 因为签名覆盖整个文件内容，
     * 内容一变签名就失效，这是**预期结果**，不是打包失败。
     * 旧版把这两种情况都报成"整体校验未通过"，很容易让人以为产物坏了。
     *
     * <p>所以这里给出明确判词：
     * <ul>
     *   <li><b>签名失效（预期）</b> —— ZIP 结构完好，只是签名对不上了 → 重签名即可</li>
     *   <li><b>结构损坏（真失败）</b> —— ZIP 头/中央目录/EOCD 读不出来 → 产物真坏了</li>
     * </ul>
     */
    public static String verifyApk(String apkPath) {
        StringBuilder sb = new StringBuilder();
        try {
            com.android.apksig.ApkVerifier.Result r =
                    new com.android.apksig.ApkVerifier.Builder(new File(apkPath)).build().verify();
            sb.append("APK: ").append(apkPath).append("\n");
            sb.append("签名校验: ").append(r.isVerified() ? "✅ 通过" : "❌ 未通过").append("\n");
            sb.append("  v1(JAR) : ").append(r.isVerifiedUsingV1Scheme()).append("\n");
            sb.append("  v2      : ").append(r.isVerifiedUsingV2Scheme()).append("\n");
            sb.append("  v3      : ").append(r.isVerifiedUsingV3Scheme()).append("\n");
            sb.append("  v4      : ").append(r.isVerifiedUsingV4Scheme()).append("\n");
            List<java.security.cert.X509Certificate> certs = r.getSignerCertificates();
            int n = certs == null ? 0 : certs.size();
            sb.append("签名者数量: ").append(n).append("\n");
            if (certs != null) {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                for (java.security.cert.X509Certificate c : certs) {
                    sb.append("  - ").append(c.getSubjectX500Principal().getName()).append("\n");
                    sb.append("    SHA-256: ").append(hex(md.digest(c.getEncoded()))).append("\n");
                    sb.append("    有效期至: ").append(c.getNotAfter()).append("\n");
                }
            }
            List<?> errs = r.getErrors();
            boolean structural = false;
            StringBuilder errText = new StringBuilder();
            if (errs != null && !errs.isEmpty()) {
                for (Object o : errs) {
                    String s = String.valueOf(o);
                    errText.append("  ! ").append(s).append("\n");
                    if (looksStructural(s)) structural = true;
                }
            }
            List<?> warns = r.getWarnings();

            // 判词：结构 vs 签名
            sb.append("\n【结论】");
            if (r.isVerified()) {
                sb.append("签名有效，可直接安装。\n");
            } else if (structural) {
                sb.append("❌ 结构损坏（**真失败**）\n");
                sb.append("  ZIP 头 / 中央目录 / EOCD 读不出来，这个包不能用，需重新打包。\n");
                sb.append("  自检命令：list_apk_entries {\"apk_path\":\"").append(apkPath).append("\"}\n");
            } else if (n == 0) {
                sb.append("⚠ 签名已失效（**预期**，不是打包失败）\n");
                sb.append("  这个 APK 原本就没带 v1(JAR) 签名（只有 v2/v3），\n");
                sb.append("  而 v2/v3 签名覆盖整个文件内容 —— 只要改过任何条目就必然失效。\n");
                sb.append("  处理：用 MT 管理器「签名」页重签名（v1+v2），或 apksigner sign。\n");
            } else {
                sb.append("⚠ 签名已失效（**预期**，不是打包失败）\n");
                sb.append("  证书还在，但内容摘要对不上 —— 说明这个包被改过（改 .so / 换条目 / 重打包）。\n");
                sb.append("  处理：用 MT 管理器「签名」页重签名（v1+v2），或 apksigner sign。\n");
            }
            if (errText.length() > 0) {
                sb.append("\n错误明细:\n").append(errText);
            }
            if (warns != null && !warns.isEmpty()) {
                sb.append("告警:\n");
                for (Object o : warns) sb.append("  ~ ").append(o).append("\n");
            }
        } catch (Exception e) {
            sb.append("[校验失败] ").append(e).append("\n");
        }
        return sb.toString();
    }

    /** 这条错误是不是在说"ZIP 结构坏了"（而不是"签名对不上"） */
    private static boolean looksStructural(String s) {
        if (s == null) return false;
        String l = s.toLowerCase(java.util.Locale.US);
        String[] keys = {
                "local file header", "central directory", "end of central directory",
                "eocd", "malformed", "not a zip", "zip64", "truncated",
                "invalid zip", "unexpected eof", "invalid entry", "corrupt",
        };
        for (String k : keys) if (l.indexOf(k) >= 0) return true;
        return false;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
