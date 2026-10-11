package com.rev.mcptool;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * blutter 产物复用缓存。
 *
 * <p>背景：blutter 跑一次 libapp.so（12MB 级）要几十秒到几分钟，
 * 而 analyze_file 每次都无条件重跑，同一个 APK 连跑 4 次就重生成 4 份 pp.txt，
 * 既慢又把工作目录撑爆。
 *
 * <p>做法：在 blutter_out/ 下放一个 .blutter_meta，记录输入 so 的 SHA-256 与大小。
 * 下次进来先算指纹，指纹一致且产物（dart_symbols.tsv）还在 → 直接复用，跳过 blutter。
 *
 * <p>注意：指纹是对**输入 libapp.so** 算的，不是对 APK 算的。
 * 因为 blutter 的入参就是 so，APK 换名/换路径不影响结果。
 */
public final class BlutterCache {

    /** 元数据文件名（放在 blutter_out/ 下） */
    private static final String META = ".blutter_meta";

    private BlutterCache() { }

    /** 计算文件 SHA-256（12MB 级约几十毫秒，可选开销） */
    public static String sha256(File f) {
        if (f == null || !f.isFile()) return null;
        FileInputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(f);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignore) { /* ignore */ }
        }
    }

    /**
     * 找 blutter_out 下已存在的符号索引。
     * blutter 放宽校验时产物会落在 blutter_out/relaxed/ 子目录，两种结构都兼容。
     */
    public static File findTsv(File outDir) {
        if (outDir == null) return null;
        File direct = new File(outDir, PpDigest.TSV_NAME);
        if (direct.isFile() && direct.length() > 0) return direct;
        File relaxed = new File(new File(outDir, "relaxed"), PpDigest.TSV_NAME);
        if (relaxed.isFile() && relaxed.length() > 0) return relaxed;
        return null;
    }

    /** 找已存在的 pp.txt（同样兼容 relaxed 子目录） */
    public static File findPp(File outDir) {
        if (outDir == null) return null;
        File direct = new File(outDir, "pp.txt");
        if (direct.isFile() && direct.length() > 0) return direct;
        File relaxed = new File(new File(outDir, "relaxed"), "pp.txt");
        if (relaxed.isFile() && relaxed.length() > 0) return relaxed;
        return null;
    }

    /** 元数据文件 */
    public static File metaFile(File outDir) {
        return new File(outDir, META);
    }

    /**
     * 已缓存的产物是否还能用：输入 so 指纹一致 + 产物文件确实存在。
     *
     * @param libapp 本次要分析的 libapp.so
     * @param outDir blutter_out 目录
     * @return 可复用时返回已有的 tsv 文件，否则 null
     */
    public static File reusable(File libapp, File outDir) {
        if (libapp == null || !libapp.isFile() || outDir == null) return null;
        File tsv = findTsv(outDir);
        if (tsv == null) return null;

        File meta = metaFile(outDir);
        if (!meta.isFile()) return null;   // 没有元数据 → 不敢判断，重跑

        String want = sha256(libapp);
        if (want == null) return null;

        String sha = null;
        long size = -1;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(meta), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("so_sha256=")) sha = line.substring("so_sha256=".length()).trim();
                else if (line.startsWith("so_size=")) {
                    try { size = Long.parseLong(line.substring("so_size=".length()).trim()); }
                    catch (Exception ignore) { /* ignore */ }
                }
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignore) { /* ignore */ }
        }

        if (sha == null || !sha.equalsIgnoreCase(want)) return null;
        if (size >= 0 && size != libapp.length()) return null;
        return tsv;
    }

    /** blutter 成功跑完后调用，记录本次输入的指纹 */
    public static void mark(File libapp, File outDir, String dartVersion, int symbolCount) {
        if (libapp == null || outDir == null) return;
        String sha = sha256(libapp);
        if (sha == null) return;
        try {
            if (!outDir.isDirectory()) outDir.mkdirs();
            File meta = metaFile(outDir);
            Writer w = new OutputStreamWriter(new FileOutputStream(meta), "UTF-8");
            try {
                w.write("so_sha256=" + sha + "\n");
                w.write("so_size=" + libapp.length() + "\n");
                w.write("so_name=" + libapp.getName() + "\n");
                w.write("dart_version=" + (dartVersion == null ? "?" : dartVersion) + "\n");
                w.write("symbols=" + symbolCount + "\n");
                w.write("generated=" + new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date()) + "\n");
                w.flush();
            } finally {
                try { w.close(); } catch (Exception ignore) { /* ignore */ }
            }
        } catch (Exception ignore) { /* 写元数据失败不影响主流程 */ }
    }

    /** 给复用场景用的一行说明 */
    public static String reuseNote(File tsv) {
        StringBuilder sb = new StringBuilder();
        sb.append("（复用已有 blutter 产物，未重新生成 —— 输入 libapp.so 指纹与上次一致）\n");
        if (tsv != null) {
            sb.append("  符号索引 : ").append(tsv.getAbsolutePath())
              .append("  ").append(fmtSize(tsv.length())).append("\n");
        }
        sb.append("  若想强制重跑：删掉 blutter_out/.blutter_meta，或直接调 blutter_run\n");
        return sb.toString();
    }

    private static String fmtSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        return String.format(Locale.US, "%.2f MB", n / (1024.0 * 1024.0));
    }
}
