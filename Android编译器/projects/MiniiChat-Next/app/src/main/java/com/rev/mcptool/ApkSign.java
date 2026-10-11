package com.rev.mcptool;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * APK 重签名（v1 JAR + v2 + v3），供 {@code replace_entry} 与 {@code sign_apk} 使用。
 *
 * <h3>为什么不自己写签名器</h3>
 * 改包后原签名必然失效，产出的是**未签名** APK，装不上 —— 这是"改 so 解锁会员"
 * 这类任务唯一的断链点。设备上原本没有 apksigner，只能回头开 MT 管理器手签。
 *
 * <p>但本工程的构建脚本早就把整份 {@code com.android.apksig}（官方 apksig 库）
 * dex 进 APK 了（原本只给 {@link ApkPack#verifyApk} 做只读校验用）。
 * 同一份库里就有 {@link ApkSigner}，所以设备端可以直接做完整签名，
 * **不需要 NDK、不需要 apksigner 可执行文件、不需要电脑**。
 *
 * <h3>为什么默认 v1+v2+v3 全开</h3>
 * apksig 签名有两条路径：
 * <ul>
 *   <li><b>只开 v2/v3</b>：只在末尾追加 APK Signing Block，逐字节搬运原内容 ——
 *       压缩方式与对齐 100% 保持，但会**删掉**原包的 META-INF v1 条目
 *       （实测条目 58 → 55），Android 7.0 以下装不上。</li>
 *   <li><b>开 v1</b>：需要新增 META-INF/MANIFEST.MF·SF·RSA 三个条目，
 *       走的是"重写 zip"路径。实测对本工程 143.7MB 产物：
 *       条目 58 → 58，{@code lib/**}{@code /*.so} **48/48 仍是 STORED、
 *       48/48 仍 4096 对齐（余数全 0）** —— apksig 会保留各条目的局部头
 *       extra（我们的 0xD935 对齐填充就在里面），所以偏移没有漂移。</li>
 * </ul>
 * 既然 v1 不会破坏 STORED/对齐，就默认三方案全开，兼容性最好。
 *
 * <p>万一某个包在 v1 重写路径下真的掉了对齐，签名后 {@link #alignmentReport}
 * 会如实报告，此时改用 {@code v1=false}（v2-only 路径逐字节搬运，不可能掉对齐）。
 *
 * <p>本类**零 android.* 依赖**，桌面回归可以直接编译运行（apksig 由 build-tools 提供）。
 */
public final class ApkSign {

    /** 内置 debug keystore 的口令（与构建脚本 build_apk.py 生成时一致） */
    public static final String DEFAULT_STORE_PASS = "android";
    public static final String DEFAULT_KEY_PASS = "android";
    public static final String DEFAULT_ALIAS = "revmcp";

    /** 内置 keystore 的资产名（构建时打进 assets/） */
    public static final String ASSET_NAME = "revmcp.keystore";

    private ApkSign() { }

    /** 签名结果：可读报告 + 产物 */
    public static final class Result {
        public String text;
        public File out;
        public int signedCount;      // 实际启用的方案数
        public String v1, v2, v3;    // 各方案启用情况（"开"/"关"）
        public String certSha256;
    }

    // ================================================================
    //  keystore
    // ================================================================

    /**
     * 载入 keystore。先按 PKCS12 试（JDK/新版 keytool 的默认格式），
     * 失败再按 JKS 试 —— 用户从别处拿到的老 keystore 多半是 JKS。
     */
    public static KeyStore loadKeyStore(File ks, char[] storePass) throws Exception {
        InputStream in = new FileInputStream(ks);
        try {
            KeyStore k;
            try {
                k = KeyStore.getInstance("PKCS12");
                k.load(in, storePass);
                return k;
            } catch (Throwable e1) {
                in.close();
                in = new FileInputStream(ks);
                k = KeyStore.getInstance("JKS");
                k.load(in, storePass);
                return k;
            }
        } finally {
            try { in.close(); } catch (Exception ignore) { /* ignore */ }
        }
    }

    /** keystore 里所有条目别名（别名写错时报出来，省得用户猜） */
    public static List<String> aliases(KeyStore ks) throws Exception {
        List<String> out = new ArrayList<String>();
        Enumeration<String> e = ks.aliases();
        while (e.hasMoreElements()) out.add(e.nextElement());
        return out;
    }

    /** 按名字找别名（大小写不敏感；不传时取第一个带私钥的条目） */
    public static String resolveAlias(KeyStore ks, String want) throws Exception {
        List<String> all = aliases(ks);
        if (want != null && want.length() > 0) {
            for (String a : all) if (a.equalsIgnoreCase(want)) return a;
            throw new IOException("keystore 里没有别名 \"" + want + "\"；现有别名: " + all);
        }
        for (String a : all) {
            if (ks.isKeyEntry(a)) return a;
        }
        throw new IOException("keystore 里没有带私钥的条目；现有别名: " + all);
    }

    // ================================================================
    //  签名
    // ================================================================

    /**
     * 对 {@code in} 签名并写出到 {@code out}。
     *
     * @param v1/v2/v3 各方案是否启用（正常情况三个都 true）
     * @param minSdk   &gt;0 时显式告知 apksig 目标最低 SDK；&le;0 则不设（由 apksig 自行判断）
     */
    public static Result sign(File in, File out, File ks, String storePass, String alias,
                              String keyPass, boolean v1, boolean v2, boolean v3, int minSdk)
            throws Exception {
        if (in == null || !in.isFile()) throw new IOException("输入 APK 不存在: " + in);
        if (ks == null || !ks.isFile()) {
            throw new IOException("keystore 不存在: " + ks
                    + "\n   内置 keystore 缺失时，可在界面点「导出 debug keystore」，"
                    + "或用 ks_path 参数指定自己的 .keystore/.jks");
        }
        if (!v1 && !v2 && !v3) {
            throw new IOException("v1/v2/v3 至少要开一个，否则签了等于没签");
        }

        char[] sp = (storePass == null ? DEFAULT_STORE_PASS : storePass).toCharArray();
        char[] kp = (keyPass == null || keyPass.length() == 0 ? sp
                : keyPass.toCharArray());
        KeyStore keyStore = loadKeyStore(ks, sp);
        String a = resolveAlias(keyStore, alias);

        Key key = keyStore.getKey(a, kp);
        if (!(key instanceof PrivateKey)) {
            throw new IOException("别名 \"" + a + "\" 不是私钥条目（isKeyEntry=" + keyStore.isKeyEntry(a)
                    + "）—— 别把只含证书的 keystore 拿来签名");
        }
        PrivateKey priv = (PrivateKey) key;

        List<X509Certificate> certs = new ArrayList<X509Certificate>();
        Certificate[] chain = keyStore.getCertificateChain(a);
        if (chain != null) {
            for (Certificate c : chain) if (c instanceof X509Certificate) certs.add((X509Certificate) c);
        }
        if (certs.isEmpty()) {
            Certificate c = keyStore.getCertificate(a);
            if (c instanceof X509Certificate) certs.add((X509Certificate) c);
        }
        if (certs.isEmpty()) {
            throw new IOException("别名 \"" + a + "\" 没有证书链，无法签名");
        }

        // 输出写临时文件再搬运：一是 apksig 明确不支持原地覆盖，
        // 二是签名失败时绝不能把原文件留成半截。
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        if (tmp.exists()) tmp.delete();
        if (tmp.exists()) throw new IOException("临时文件占用: " + tmp);

        long t0 = System.currentTimeMillis();
        try {
            ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder(
                    a.toUpperCase(Locale.US), priv, certs).build();
            ApkSigner.Builder b = new ApkSigner.Builder(Collections.singletonList(cfg))
                    .setInputApk(in)
                    .setOutputApk(tmp)
                    .setV1SigningEnabled(v1)
                    .setV2SigningEnabled(v2)
                    .setV3SigningEnabled(v3)
                    .setCreatedBy("RevMCP");
            if (minSdk > 0) b.setMinSdkVersion(minSdk);
            b.build().sign();
        } catch (Throwable e) {
            try { tmp.delete(); } catch (Exception ignore) { /* ignore */ }
            throw new IOException("签名失败: " + e, e);
        }
        long ms = System.currentTimeMillis() - t0;

        if (out.exists() && !out.delete()) {
            try { tmp.delete(); } catch (Exception ignore) { /* ignore */ }
            throw new IOException("无法覆盖已存在的输出文件: " + out);
        }
        if (!tmp.renameTo(out)) {
            // 跨挂载点 rename 会失败，退化为流拷贝
            copyFile(tmp, out);
            try { tmp.delete(); } catch (Exception ignore) { /* ignore */ }
        }

        Result r = new Result();
        r.out = out;
        r.v1 = v1 ? "开" : "关";
        r.v2 = v2 ? "开" : "关";
        r.v3 = v3 ? "开" : "关";
        r.signedCount = (v1 ? 1 : 0) + (v2 ? 1 : 0) + (v3 ? 1 : 0);
        r.certSha256 = sha256Hex(certs.get(0).getEncoded());
        r.text = "签名完成（v1=" + r.v1 + " v2=" + r.v2 + " v3=" + r.v3
                + "，别名 " + a + "，用时 " + ms + "ms）";
        return r;
    }

    /** 证书 SHA-256 指纹（小写十六进制，带冒号分隔便于人眼比对） */
    public static String certFingerprint(File ks, String storePass, String alias, String keyPass)
            throws Exception {
        char[] sp = (storePass == null ? DEFAULT_STORE_PASS : storePass).toCharArray();
        char[] kp = (keyPass == null || keyPass.length() == 0 ? sp : keyPass.toCharArray());
        KeyStore keyStore = loadKeyStore(ks, sp);
        String a = resolveAlias(keyStore, alias);
        Certificate c = keyStore.getCertificate(a);
        if (c == null) throw new IOException("别名 \"" + a + "\" 没有证书");
        return sha256Hex(c.getEncoded());
    }

    // ================================================================
    //  对齐自检：签名"会不会顺手把 .so 搞坏"的唯一可信答案
    // ================================================================

    /**
     * 报告 APK 内所有 {@code lib/**}{@code /*.so} 的压缩方式与数据偏移对齐情况。
     *
     * <p>必须读**局部头**的 nameLen/extraLen 算数据偏移 ——
     * zipalign 的填充写在局部头 extra 里，中央目录那份长度可能不同，
     * 用中央目录算会得出"全都没对齐"的假警报（这坑踩过一次，见工作日志）。
     *
     * <p>注意 {@code java.util.zip.ZipEntry} **没有** getOffset()（那是 Python
     * {@code ZipInfo.header_offset} 才有的），所以这里自己扫中央目录拿局部头偏移。
     */
    public static String alignmentReport(File apk, int align) {
        java.io.RandomAccessFile in = null;
        try {
            in = new java.io.RandomAccessFile(apk, "r");
            long len = in.length();
            int tailLen = (int) Math.min(len, 65557 + 22);
            byte[] tail = new byte[tailLen];
            in.seek(len - tailLen);
            in.readFully(tail);
            int eocd = -1;
            for (int i = tailLen - 22; i >= 0; i--) {
                if ((tail[i] & 0xFF) == 0x50 && (tail[i + 1] & 0xFF) == 0x4B
                        && (tail[i + 2] & 0xFF) == 0x05 && (tail[i + 3] & 0xFF) == 0x06) { eocd = i; break; }
            }
            if (eocd < 0) return "  .so 打包   : 检查失败（找不到 EOCD）\n";
            int count = u16(tail, eocd + 10);
            long cdOff = u32(tail, eocd + 16);
            long cdSize = u32(tail, eocd + 12);
            if (cdSize <= 0 || cdSize > (64L << 20)) {
                return "  .so 打包   : 检查失败（中央目录大小异常 " + cdSize + "）\n";
            }
            byte[] cd = new byte[(int) cdSize];
            in.seek(cdOff);
            in.readFully(cd);

            int so = 0, stored = 0, storedAligned = 0, deflated = 0;
            StringBuilder bad = new StringBuilder();
            int p = 0;
            for (int i = 0; i < count && p + 46 <= cd.length; i++) {
                int method = u16(cd, p + 10);
                int nameLen = u16(cd, p + 28);
                int extraLen = u16(cd, p + 30);
                int commentLen = u16(cd, p + 32);
                long localOff = u32(cd, p + 42);
                String name = new String(cd, p + 46, nameLen, java.nio.charset.Charset.forName("UTF-8"));
                p += 46 + nameLen + extraLen + commentLen;
                if (!name.startsWith("lib/") || !name.endsWith(".so")) continue;
                so++;
                in.seek(localOff);
                byte[] h = new byte[30];
                in.readFully(h);
                int nl = u16(h, 26);
                int el = u16(h, 28);
                long dataOff = localOff + 30 + nl + el;
                if (method == 0) {
                    stored++;
                    if (dataOff % align == 0) storedAligned++;
                    else if (bad.length() < 400) {
                        bad.append("      ").append(name)
                           .append(" STORED 偏移余数=").append(dataOff % align).append("\n");
                    }
                } else {
                    deflated++;
                }
            }
            if (so == 0) return "  .so 打包   : （包内没有 lib/**/*.so，无需检查）\n";

            // 判据随 manifest 的 extractNativeLibs 走 —— 这两种模式的要求是**相反**的：
            //   true  ：安装期由系统解压到 nativeLibraryDir，DEFLATE 完全可用（还省体积）
            //   false ：只 mmap APK 内数据，必须 STORED + 页对齐，压缩或不对齐都会
            //           INSTALL_FAILED_INVALID_APK: Failed to extract native libraries
            // 早期实现只认「STORED + 对齐」，于是给自产的压缩包报了假警报，
            // 还会把人误导去做 v1=false 重签这种无效操作。
            Boolean ext = extractNativeLibs(apk);
            StringBuilder sb = new StringBuilder();
            sb.append("  .so 打包   : ").append(stored).append("/").append(so).append(" STORED")
              .append("（其中 ").append(storedAligned).append(" 个 ").append(align)
              .append(" 字节对齐） ｜ ").append(deflated).append("/").append(so).append(" DEFLATE\n");
            sb.append("  manifest   : extractNativeLibs=")
              .append(ext == null ? "（读不到）" : ext.toString()).append("\n");

            if (Boolean.FALSE.equals(ext)) {
                if (stored == so && storedAligned == so) {
                    sb.append("  结论       : ✅ 合规（extractNativeLibs=false 要求全 STORED + ")
                      .append(align).append(" 字节对齐）\n");
                } else {
                    sb.append("  结论       : ⚠ 有 ").append(so - storedAligned)
                      .append(" 个 .so 不满足 STORED+对齐，装包会报\n")
                      .append("     INSTALL_FAILED_INVALID_APK: Failed to extract native libraries\n")
                      .append(bad)
                      .append("     对策: 用 sign_apk 时加 v1=false 重签（v2 路径逐字节搬运，不会掉对齐）\n");
                }
            } else if (deflated > 0) {
                sb.append("  结论       : ✅ 合规（extractNativeLibs=true，安装期解压到 nativeLibraryDir，")
                  .append("DEFLATE 完全可用；STORED 条目也一并保留了对齐）\n");
            } else {
                sb.append("  结论       : ✅ 合规（全 STORED + ").append(align)
                  .append(" 字节对齐；此模式下同样允许，只是包体更大）\n");
            }
            if (ext == null) {
                sb.append("     提示: 读不到 extractNativeLibs 时无法判定 —— 若该值为 false，")
                  .append("则上面的 DEFLATE 条目会导致安装失败。\n");
            }
            return sb.toString();
        } catch (Throwable e) {
            return "  .so 打包   : 检查失败（" + e + "）\n";
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignore) { /* ignore */ }
        }
    }

    /**
     * 读目标包的二进制 AndroidManifest.xml，判断 {@code android:extractNativeLibs}。
     *
     * <p>这个值决定 lib 该怎么打包，两种模式的要求完全相反：
     * <ul>
     *   <li>{@code true} —— 安装期由系统把 {@code lib/<abi>/*.so} 解压到 nativeLibraryDir，
     *       所以 APK 内**允许 DEFLATE**（还省体积）；</li>
     *   <li>{@code false} —— loader 直接 mmap APK 内的数据，
     *       必须 STORED + 页对齐，否则装包报 Failed to extract native libraries。</li>
     * </ul>
     *
     * @return {@code TRUE}/{@code FALSE}；读不到（老包、异常结构）时返回 {@code null}，
     *         调用方据此把结论写成「无法判定」而不是硬报错
     */
    private static Boolean extractNativeLibs(File apk) {
        try {
            byte[] axm = ApkPack.readEntry(apk.getAbsolutePath(), "AndroidManifest.xml");
            if (axm == null || axm.length == 0) return null;
            String text = AxmReader.decode(axm);
            for (String line : text.split("\n")) {
                int i = line.indexOf("extractNativeLibs");
                if (i < 0) continue;
                int q1 = line.indexOf('"', i);
                int q2 = q1 < 0 ? -1 : line.indexOf('"', q1 + 1);
                if (q1 > 0 && q2 > q1) {
                    return Boolean.valueOf("true".equalsIgnoreCase(line.substring(q1 + 1, q2)));
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int u16(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int p) {
        return (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8)
                | ((b[p + 2] & 0xFFL) << 16) | ((b[p + 3] & 0xFFL) << 24);
    }

    // ================================================================
    //  小工具
    // ================================================================

    public static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "(计算失败: " + e + ")";
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        try {
            java.io.FileOutputStream os = new java.io.FileOutputStream(to);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.getFD().sync();
            } finally {
                try { os.close(); } catch (Exception ignore) { /* ignore */ }
            }
        } finally {
            try { in.close(); } catch (Exception ignore) { /* ignore */ }
        }
    }
}
