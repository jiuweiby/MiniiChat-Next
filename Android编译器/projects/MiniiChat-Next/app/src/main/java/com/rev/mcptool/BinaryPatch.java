package com.rev.mcptool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.InflaterInputStream;

/**
 * 二进制补丁工具：对 .so / 任意文件做字节级写入，或对 APK 条目做替换。
 *
 * <p>三种能力：
 * <ul>
 *   <li>{@link #patchBytes}  对任意文件在指定偏移写入字节（支持十六进制偏移）</li>
 *   <li>{@link #patchHex}    对任意文件在指定偏移写入十六进制字符串（如 "90 90 90"）</li>
 *   <li>{@link #replaceEntry} 替换 APK 内某个条目的内容（只读原 APK，写到新文件）</li>
 * </ul>
 *
 * <h3>replaceEntry 为什么是自己手写 ZIP 重写，而不是 ZipOutputStream</h3>
 *
 * <p>旧实现是「用 {@code ZipFile} 读全部条目 → 用 {@code ZipOutputStream} 写新包」。
 * 这看着没问题，但 {@code ZipOutputStream} 会**按当前压缩级别重新压缩每一个条目**，
 * 于是把原包里 STORED 的 {@code lib/<abi>/*.so} 全部变成了 DEFLATE，
 * 顺带丢掉 zipalign 的偏移对齐。后果是真机上装不上：
 *
 * <pre>
 * INSTALL_FAILED_INVALID_APK: Failed to extract native libraries
 * </pre>
 *
 * <p>而且这个 bug 是**累加式**的：只保住「本次指定的那一个」条目，
 * 上一次已经改好的条目会在下一次调用里被重新压回 DEFLATE ——
 * 双 ABI 的包要先换 arm64 再换 v7a，两次调用后 8 个 so 里只剩 1 个是 STORED。
 *
 * <p>现在改成**逐条原样搬运**：
 * <ul>
 *   <li>未指定的条目：直接从原包拷**压缩后的原始字节**（DEFLATE 流原样，不再重压），
 *       压缩方式、时间戳、注释、外部属性、内部属性全部保留</li>
 *   <li>{@code lib/<abi>/*.so}：压缩方式**原样保留**，不强制改写 ——
 *       两种打包方式都由 manifest 的 {@code extractNativeLibs} 决定合法性：
 *       {@code true} 时安装期解压（DEFLATE 可用且省体积），
 *       {@code false} 时 loader 直接 mmap APK 内数据（必须 STORED + 页对齐）。
 *       强制改成 STORED 会把 47MB 的包撑回 143MB；强制压回去又会弄坏前者。
 *       <b>只有那些原本就是 STORED 的 .so</b> 才补 4096 字节对齐。</li>
 *   <li>{@code lib/<abi>/*.so} 若是 STORED，数据偏移按 **4096 字节** 对齐；
 *       其余 STORED 条目按 4 字节对齐（DEFLATE 条目无需对齐）</li>
 *   <li>对齐用标准 ZIP 对齐扩展字段（id {@code 0xD935}）+ 尾部补零实现，
 *       长度写回本地头与中央目录，Android 的 libziparchive 能正常解析</li>
 *   <li>数据描述符标志（bit 3）在输出里清掉 —— 我们写的是真实 CRC/长度，不需要它</li>
 * </ul>
 *
 * <p>写完后会**自检一遍输出包**（重新扫中央目录），把每个 {@code .so} 的
 * 压缩方式与对齐偏移回报给调用方，避免"以为打好了其实又压回去了"。
 *
 * <p>注意：这些操作是<strong>不可逆</strong>的。调用前务必先备份原文件。
 */
public final class BinaryPatch {

    /** ZIP 本地文件头 / 中央目录 / EOCD 签名 */
    private static final long SIG_LFH = 0x04034b50L;
    private static final long SIG_CD = 0x02014b50L;
    private static final long SIG_EOCD = 0x06054b50L;

    /** zipalign 用的「对齐扩展字段」ID（AOSP libziparchive kAlignmentZipExtraFieldId） */
    private static final int ALIGN_EXTRA_ID = 0xD935;

    /** lib/**\/*.so 的对齐要求（页对齐；Android 15 起要 16KB，但 4KB 兼容性最广） */
    private static final int SO_ALIGN = 4096;
    /** 其他 STORED 条目的对齐要求 */
    private static final int STORED_ALIGN = 4;

    private BinaryPatch() {}

    // ================================================================
    //  任意文件字节写入
    // ================================================================

    /**
     * 对文件在指定偏移写入原始字节。
     *
     * @param filePath 目标文件路径（必须是可写路径）
     * @param offset   写入起始偏移（支持十进制或 0x 十六进制）
     * @param data     要写入的字节数组
     * @return 成功信息字符串
     * @throws IOException 写入失败时抛出
     */
    public static String patchBytes(String filePath, String offset, byte[] data) throws IOException {
        File f = new File(filePath);
        if (!f.exists()) {
            throw new IOException("文件不存在: " + filePath);
        }
        if (!f.isFile()) {
            throw new IOException("不是普通文件（可能是目录）: " + filePath);
        }
        if (!f.canWrite()) {
            throw new IOException("文件不可写: " + filePath
                    + "（权限不足 / 只读挂载 / 位于其他应用私有目录）");
        }
        if (data == null || data.length == 0) {
            throw new IOException("要写入的字节为空（hex 解析结果为空？）");
        }
        long off;
        try {
            off = parseOffset(offset);
        } catch (NumberFormatException e) {
            throw new IOException("偏移格式错误: '" + offset
                    + "'（应为十进制或 0x 十六进制）");
        }
        if (off < 0) {
            throw new IOException("偏移不能为负: " + offset);
        }

        // 关键：必须用 RandomAccessFile 的通道。
        // 之前用 FileInputStream.getChannel() —— 那是只读通道，write() 会抛
        // NonWritableChannelException，而它的 getMessage() 返回 null，
        // 客户端侧就只看到一个光秃秃的 "null"，完全无法定位。
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        try {
            FileChannel chan = raf.getChannel();
            long fileSize = chan.size();
            if (off > fileSize) {
                throw new IOException("偏移 0x" + Long.toHexString(off)
                        + " 超出文件大小 0x" + Long.toHexString(fileSize));
            }
            if (off + data.length > fileSize) {
                raf.setLength(off + data.length);   // 扩展文件
            }
            chan.position(off);
            int written = chan.write(ByteBuffer.wrap(data));
            if (written != data.length) {
                throw new IOException("写入不完整: 期望 " + data.length
                        + " 字节，实际 " + written + " 字节");
            }
            try {
                raf.getFD().sync();   // 落盘，避免进程被杀后补丁丢失
            } catch (Exception ignore) { /* 部分文件系统不支持 sync，忽略 */ }
        } finally {
            try { raf.close(); } catch (Exception ignore) { /* ignore */ }
        }
        return String.format("已写入 %d 字节到 %s@0x%X", data.length, filePath, off);
    }

    /**
     * 对文件在指定偏移写入十六进制字符串。
     *
     * <p>hexString 支持空格分隔的两位十六进制数，例如：
     * <ul>
     *   <li>{@code "B8 00 00 00 00"} → 5 字节</li>
     *   <li>{@code "90 90 90 90"} → 4 字节 NOP</li>
     *   <li>{@code "01 00 00 D4"} → ARM64 ret 指令</li>
     * </ul>
     *
     * @param filePath   目标文件路径
     * @param offset     写入起始偏移（支持十进制或 0x 十六进制）
     * @param hexString  十六进制字符串
     * @return 成功信息字符串
     * @throws IOException 写入失败或 hexString 格式错误时抛出
     */
    public static String patchHex(String filePath, String offset, String hexString) throws IOException {
        byte[] data = parseHex(hexString);
        return patchBytes(filePath, offset, data);
    }

    /**
     * 一次性写入**多处**补丁（同一文件只开一次 fd、只 fsync 一次）。
     *
     * <p>为什么需要：解一个会员校验往往要改 3-5 个地址（needVip / isVip 判定的
     * 各个返回点）。分开多次调用 {@link #patchBytes} 会有两个问题：
     * <ul>
     *   <li>每次都要重新打开/同步文件，慢且吵；</li>
     *   <li>中途某一处失败（偏移越界、hex 写错），文件已经改了一半 ——
     *       留下一个"半补丁"的 so，很难判断到底改没改。</li>
     * </ul>
     * 本方法先把全部偏移校验一遍，全部合法才逐处写入，最后统一 sync。
     *
     * @param offsets 各补丁的文件偏移
     * @param datas   各补丁的字节内容（与 offsets 一一对应）
     * @return 逐处结果的可读描述
     */
    public static String patchMany(String filePath, long[] offsets, byte[][] datas) throws IOException {
        if (offsets == null || datas == null || offsets.length != datas.length || offsets.length == 0) {
            throw new IOException("补丁列表为空或长度不匹配");
        }
        File f = new File(filePath);
        if (!f.isFile()) throw new IOException("文件不存在: " + filePath);
        if (!f.canWrite()) throw new IOException("文件不可写: " + filePath);

        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        try {
            FileChannel chan = raf.getChannel();
            long fileSize = chan.size();
            long maxEnd = fileSize;
            // 先全量校验：宁可一处都不写，也不要写一半
            for (int i = 0; i < offsets.length; i++) {
                if (offsets[i] < 0) throw new IOException("补丁[" + i + "] 偏移为负: " + offsets[i]);
                if (offsets[i] > fileSize) {
                    throw new IOException("补丁[" + i + "] 偏移 0x" + Long.toHexString(offsets[i])
                            + " 超出文件大小 0x" + Long.toHexString(fileSize));
                }
                if (datas[i] == null || datas[i].length == 0) {
                    throw new IOException("补丁[" + i + "] 内容为空");
                }
                maxEnd = Math.max(maxEnd, offsets[i] + datas[i].length);
            }
            if (maxEnd > fileSize) raf.setLength(maxEnd);   // 需要时才扩展一次
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < offsets.length; i++) {
                chan.position(offsets[i]);
                int w = chan.write(ByteBuffer.wrap(datas[i]));
                if (w != datas[i].length) {
                    throw new IOException("补丁[" + i + "] 写入不完整: 期望 " + datas[i].length
                            + " 字节，实际 " + w + " 字节");
                }
                if (i > 0) sb.append("\n");
                sb.append(String.format("  #%d  0x%X  %d 字节", i + 1, offsets[i], datas[i].length));
            }
            try {
                raf.getFD().sync();
            } catch (Exception ignore) { /* 部分文件系统不支持 sync，忽略 */ }
            return "已写入 " + offsets.length + " 处补丁到 " + filePath + "：\n" + sb;
        } finally {
            try { raf.close(); } catch (Exception ignore) { /* ignore */ }
        }
    }

    // ================================================================
    //  APK 条目替换
    // ================================================================

    /** 单个条目替换请求 */
    public static final class Swap {
        public final String entry;
        public final String file;
        public Swap(String entry, String file) { this.entry = entry; this.file = file; }
    }

    /**
     * 替换 APK 内**一个**条目的内容。
     *
     * @param apkPath   原 APK 路径
     * @param entryName APK 内条目名（如 {@code "lib/arm64-v8a/libapp.so"}）
     * @param newFile   新文件路径（替换内容来源）
     * @param outApkPath 输出 APK 路径
     * @return 含自检结论的成功信息
     * @throws IOException ZIP 操作失败时抛出
     */
    public static String replaceEntry(String apkPath, String entryName, String newFile, String outApkPath)
            throws IOException {
        List<Swap> one = new ArrayList<Swap>();
        one.add(new Swap(entryName, newFile));
        return replaceEntries(apkPath, one, outApkPath);
    }

    /**
     * 替换 APK 内**多个**条目的内容（一次重写，对齐只算一遍）。
     *
     * <p>双 ABI 的包请用这个而不是连着调两次单条目版本 —— 虽然现在单条目版本
     * 也不会再把别的条目压回 DEFLATE，但一次写完语义更清楚、也更快。
     */
    public static String replaceEntries(String apkPath, List<Swap> swaps, String outApkPath)
            throws IOException {
        if (swaps == null || swaps.isEmpty()) {
            throw new IOException("没有要替换的条目（entry/new_file 为空）");
        }
        File apk = new File(apkPath);
        if (!apk.isFile()) throw new IOException("APK 不存在: " + apkPath);
        if (!apk.canRead()) throw new IOException("APK 不可读: " + apkPath
                + "（权限不足 —— 先 copy_to_input 拿到缓存副本）");

        // 校验每个新文件
        for (Swap s : swaps) {
            File nf = new File(s.file);
            if (!nf.isFile()) throw new IOException("新文件不存在: " + s.file);
            if (nf.length() == 0) throw new IOException("新文件是空文件: " + s.file);
        }

        File dst = new File(outApkPath);
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (parent != null && !parent.canWrite()) {
            throw new IOException("输出目录不可写: " + parent.getAbsolutePath());
        }
        // 输出不能落在原 APK 上做原地写：先写临时文件，最后原子替换
        File tmp = new File(dst.getAbsolutePath() + ".tmp");

        Set<String> want = new LinkedHashSet<String>();
        for (Swap s : swaps) want.add(norm(s.entry));

        RandomAccessFile in = new RandomAccessFile(apk, "r");
        try {
            List<Src> srcs = readCentralDirectory(in);
            // 条目必须都存在，否则白写一遍
            Set<String> have = new LinkedHashSet<String>();
            for (Src s : srcs) have.add(s.name);
            for (String w : want) {
                if (!have.contains(w)) {
                    throw new IOException("未找到条目: " + w + "（在 " + apkPath + " 中）\n"
                            + "  用 list_apk_entries {\"apk_path\":\"...\"} 看真实条目名");
                }
            }
            writeApk(in, srcs, swaps, tmp);
        } finally {
            try { in.close(); } catch (Exception ignore) { /* ignore */ }
        }

        // 原子落盘
        if (dst.exists() && !dst.delete()) {
            throw new IOException("无法覆盖已存在的输出文件: " + outApkPath);
        }
        if (!tmp.renameTo(dst)) {
            copyFile(tmp, dst);
            if (!tmp.delete()) { /* ignore */ }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("已替换 %d 个条目，输出到: %s", swaps.size(), outApkPath));
        sb.append("\n\n").append(verifyApkStructure(dst, swaps));
        return sb.toString();
    }

    /** 原包里的一个条目（含它在原文件中压缩数据的位置） */
    private static final class Src {
        String name;                 // UTF-8 解码后的条目名（用于匹配）
        byte[] nameBytes;            // 原始名字字节（写回时原样搬运）
        byte[] extra;                // 本地头的扩展字段（已剥掉对齐字段）
        byte[] comment;              // 条目注释
        int versionMadeBy, versionNeeded, flags, method, time, date, intAttr;
        long crc, compSize, uncompSize, localOff, extAttr;
        long dataStart;              // 压缩数据在**原**文件里的起始偏移
    }

    // ---------------- 中央目录解析 ----------------

    private static List<Src> readCentralDirectory(RandomAccessFile in) throws IOException {
        long fileLen = in.length();
        if (fileLen < 22) throw new IOException("文件太小，不是 ZIP/APK（" + fileLen + " 字节）");

        // 反向扫 EOCD（最多 64KB 注释 + 22 字节结构）
        int scan = (int) Math.min(fileLen, 65557L + 22L);
        byte[] tail = new byte[scan];
        in.seek(fileLen - scan);
        in.readFully(tail);
        int eocd = -1;
        for (int i = tail.length - 22; i >= 0; i--) {
            if (u32(tail, i) == SIG_EOCD) { eocd = i; break; }
        }
        if (eocd < 0) {
            throw new IOException("不是有效的 ZIP/APK：未找到 EOCD。\n"
                    + "  文件可能被截断、或根本不是 APK（是不是把 .so 当 APK 传进来了？）");
        }

        int nEntries = (int) u16(tail, eocd + 10);
        long cdSize = u32(tail, eocd + 12);
        long cdOff = u32(tail, eocd + 16);

        if (nEntries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) {
            throw new IOException("该 APK 使用 ZIP64 扩展结构，暂不支持重写。\n"
                    + "  条目数 / 中央目录偏移超过 32 位，请改用电脑上的 zip 工具处理。");
        }
        if (cdOff + cdSize > fileLen) {
            throw new IOException("ZIP 结构损坏：中央目录偏移 " + cdOff + " + 长度 " + cdSize
                    + " 超出文件大小 " + fileLen);
        }

        byte[] cd = new byte[(int) cdSize];
        in.seek(cdOff);
        in.readFully(cd);

        List<Src> out = new ArrayList<Src>(Math.max(16, nEntries));
        int p = 0;
        while (p + 46 <= cd.length && u32(cd, p) == SIG_CD) {
            Src s = new Src();
            s.versionMadeBy = (int) u16(cd, p + 4);
            s.versionNeeded = (int) u16(cd, p + 6);
            s.flags = (int) u16(cd, p + 8);
            s.method = (int) u16(cd, p + 10);
            s.time = (int) u16(cd, p + 12);
            s.date = (int) u16(cd, p + 14);
            s.crc = u32(cd, p + 16);
            s.compSize = u32(cd, p + 20);
            s.uncompSize = u32(cd, p + 24);
            int nameLen = (int) u16(cd, p + 28);
            int extraLen = (int) u16(cd, p + 30);
            int cmtLen = (int) u16(cd, p + 32);
            s.intAttr = (int) u16(cd, p + 36);
            s.extAttr = u32(cd, p + 38);
            s.localOff = u32(cd, p + 42);

            int np = p + 46;
            s.nameBytes = slice(cd, np, nameLen);
            s.name = new String(s.nameBytes, java.nio.charset.Charset.forName("UTF-8"));
            s.comment = slice(cd, np + nameLen + extraLen, cmtLen);
            // 本地头的扩展字段才是有用的那份（可能与中央目录不同），去本地头取
            s.extra = readLocalExtra(in, s);

            p = np + nameLen + extraLen + cmtLen;
            out.add(s);
        }
        if (out.isEmpty()) throw new IOException("APK 中央目录为空（文件可能损坏）");
        return out;
    }

    /** 读本地文件头，拿到名字/扩展字段长度 → 算出压缩数据起点，并取出本地扩展字段 */
    private static byte[] readLocalExtra(RandomAccessFile in, Src s) throws IOException {
        byte[] lfh = new byte[30];
        in.seek(s.localOff);
        in.readFully(lfh);
        if (u32(lfh, 0) != SIG_LFH) {
            throw new IOException("条目 " + s.name + " 的本地头签名错误（ZIP 结构损坏，偏移 "
                    + s.localOff + "）");
        }
        int nameLenL = (int) u16(lfh, 26);
        int extraLenL = (int) u16(lfh, 28);

        // 关键：本地头后面**先跟着条目名**，扩展字段在名字之后。
        // 早期版本漏了这一步，直接从 localOff+30 读 extraLenL 个字节，
        // 于是把「名字 + 扩展字段的前半截」当成扩展字段收下了。
        // 第一轮还看不出问题（原包扩展字段通常为空），第二轮把这个串位的数据
        // 再写出去，整个扩展字段就彻底乱了 —— JDK 的 ZipFile 会直接拒绝：
        //   Invalid CEN header (invalid extra data field size for tag: 0x696c)
        // （0x696c 就是 "li" —— 条目名 lib/... 的头两个字节）
        byte[] lfhExtra;
        if (extraLenL > 0) {
            in.seek(s.localOff + 30 + nameLenL);
            lfhExtra = new byte[extraLenL];
            in.readFully(lfhExtra);
        } else {
            lfhExtra = new byte[0];
        }

        s.dataStart = s.localOff + 30 + nameLenL + extraLenL;
        if (s.dataStart + s.compSize > in.length()) {
            throw new IOException("条目 " + s.name + " 的数据超出文件末尾（ZIP 结构损坏）");
        }
        return normalizeExtra(lfhExtra);
    }

    /**
     * 规范化扩展字段：只保留**完整**的字段，丢掉旧的对齐字段与尾部残字节。
     *
     * <p>为什么要做这一步：重写后偏移全变了，旧的对齐字段值没有意义；
     * 而"尾部残字节"（不足 4 字节的部分）不是合法字段，留着会让严格的解析器
     * （例如 JDK 的 {@code ZipFile}）续读到我们的对齐字段中间、把它当成
     * 一个长度巨大的非法字段而拒绝整个包。
     */
    private static byte[] normalizeExtra(byte[] extra) {
        if (extra == null || extra.length < 4) return new byte[0];
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(extra.length);
        int p = 0;
        while (p + 4 <= extra.length) {
            int id = (int) u16(extra, p);
            int sz = (int) u16(extra, p + 2);
            if (p + 4 + sz > extra.length) break;      // 不完整的尾部，丢掉
            if (id != ALIGN_EXTRA_ID) o.write(extra, p, 4 + sz);
            p += 4 + sz;
        }
        return o.toByteArray();
    }

    // ---------------- 重写输出 ----------------

    private static void writeApk(RandomAccessFile in, List<Src> srcs, List<Swap> swaps, File out)
            throws IOException {
        // 条目名 → 新文件
        java.util.LinkedHashMap<String, File> map = new java.util.LinkedHashMap<String, File>();
        for (Swap s : swaps) map.put(norm(s.entry), new File(s.file));

        FileOutputStream fos = new FileOutputStream(out);
        try {
            BufCounter w = new BufCounter(fos);
            List<OutEntry> outs = new ArrayList<OutEntry>();
            byte[] buf = new byte[1 << 16];

            for (Src s : srcs) {
                File nf = map.get(s.name);
                OutEntry oe = new OutEntry();
                oe.src = s;
                oe.nameBytes = s.nameBytes;
                oe.comment = s.comment;
                oe.localOff = w.pos();

                int outMethod;
                long outCompSize, outUncompSize, outCrc;
                boolean inflate = false;

                if (nf != null) {
                    outMethod = java.util.zip.ZipEntry.STORED;
                    outCompSize = nf.length();
                    outUncompSize = nf.length();
                    outCrc = crc32(nf);
                } else {
                    outMethod = s.method;
                    // .so 的压缩方式**原样保留**，不强制 STORED。
                    // 为什么：两种打包方式都是合法的，取决于 manifest 的 extractNativeLibs ——
                    //   true  → 安装期由系统解压到 nativeLibraryDir，DEFLATE 可用（还省体积）；
                    //   false → loader 直接 mmap APK 内数据，必须 STORED + 页对齐。
                    // 强制改成 STORED 会凭空把 47MB 的包撑到 143MB；反过来把 STORED 压回
                    // DEFLATE 则会让 extractNativeLibs=false 的包装不上。
                    // 「保留 + 对 STORED 的补对齐」两种模式都安全，也最小改动。
                    if (outMethod == java.util.zip.ZipEntry.STORED) {
                        outCompSize = s.uncompSize;
                        outUncompSize = s.uncompSize;
                        outCrc = s.crc;
                        // 原包是 DEFLATE 的 .so → 必须先解开再按 STORED 写
                        inflate = (s.method != java.util.zip.ZipEntry.STORED);
                    } else {
                        outCompSize = s.compSize;
                        outUncompSize = s.uncompSize;
                        outCrc = s.crc;
                    }
                }
                if (s.name.endsWith("/")) {          // 目录条目
                    outMethod = java.util.zip.ZipEntry.STORED;
                    outCompSize = 0; outUncompSize = 0; outCrc = 0;
                }

                int align = 0;
                if (outMethod == java.util.zip.ZipEntry.STORED) {
                    align = isSoLib(s.name) ? SO_ALIGN : STORED_ALIGN;
                }
                byte[] extra = buildExtra(s.extra, align, w.pos() + 30 + s.nameBytes.length);
                oe.extra = extra;
                oe.method = outMethod;
                oe.crc = outCrc;
                oe.compSize = outCompSize;
                oe.uncompSize = outUncompSize;

                // ---- 本地文件头 ----
                int flags = s.flags & ~0x0008;      // 清掉数据描述符标志
                w.u32(SIG_LFH);
                w.u16(s.versionNeeded);
                w.u16(flags);
                w.u16(outMethod);
                w.u16(s.time);
                w.u16(s.date);
                w.u32(outCrc);
                w.u32(outCompSize);
                w.u32(outUncompSize);
                w.u16(s.nameBytes.length);
                w.u16(extra.length);
                w.write(s.nameBytes);
                w.write(extra);
                oe.dataStart = w.pos();

                // ---- 数据 ----
                if (nf != null) {
                    writeFile(nf, w, buf);
                } else if (inflate) {
                    // ZIP 条目里是**裸 deflate**（RFC 1951，无 zlib 头），
                    // 必须 new Inflater(true)（nowrap）；用默认的 Inflater() 会按 zlib 包装
                    // （RFC 1950）去校验前两个字节，直接抛
                    //   java.util.zip.ZipException: unknown compression method
                    // 这条分支只在「原包的 .so 是 DEFLATE」时才走到 —— 早期自产 APK 的 .so
                    // 全是 STORED，于是这个 bug 一直潜伏到把 lib 改成压缩存储才暴露。
                    InputStream is = new InflaterInputStream(
                            new SliceIn(in, s.dataStart, s.compSize),
                            new java.util.zip.Inflater(true));
                    long n = 0;
                    int r;
                    while ((r = is.read(buf)) > 0) { w.write(buf, 0, r); n += r; }
                    if (n != outUncompSize) {
                        throw new IOException("条目 " + s.name + " 解压长度不符：期望 "
                                + outUncompSize + " 实际 " + n);
                    }
                } else if (outCompSize > 0) {
                    copyRange(in, s.dataStart, outCompSize, w, buf);
                }

                oe.flags = flags;
                outs.add(oe);
            }

            // ---- 中央目录 ----
            long cdOff = w.pos();
            for (OutEntry oe : outs) {
                w.u32(SIG_CD);
                w.u16(oe.src.versionMadeBy);
                w.u16(oe.src.versionNeeded);
                w.u16(oe.flags);
                w.u16(oe.method);
                w.u16(oe.src.time);
                w.u16(oe.src.date);
                w.u32(oe.crc);
                w.u32(oe.compSize);
                w.u32(oe.uncompSize);
                w.u16(oe.nameBytes.length);
                w.u16(oe.extra.length);
                w.u16(oe.comment.length);
                w.u16(0);                              // 起始磁盘号
                w.u16(oe.src.intAttr);
                w.u32(oe.src.extAttr);
                w.u32(oe.localOff);
                w.write(oe.nameBytes);
                w.write(oe.extra);
                w.write(oe.comment);
            }
            long cdSize = w.pos() - cdOff;

            // ---- EOCD ----
            w.u32(SIG_EOCD);
            w.u16(0); w.u16(0);
            w.u16(outs.size()); w.u16(outs.size());
            w.u32(cdSize);
            w.u32(cdOff);
            w.u16(0);                                   // 不保留原包注释
            w.flush();
        } finally {
            try { fos.close(); } catch (Exception ignore) { /* ignore */ }
        }
    }

    private static final class OutEntry {
        Src src;
        byte[] nameBytes, extra, comment;
        long localOff, dataStart, crc, compSize, uncompSize;
        int method, flags;
    }

    /** 填充字段 ID（自定义/保留值，解析方一律忽略） */
    private static final int PAD_EXTRA_ID = 0xCAFE;

    /**
     * 构造本地头扩展字段：保留原有完整字段 + 追加「0xD935 对齐字段」+「填充字段」。
     *
     * <p>为什么要一个"填充字段"而不是直接补零字节：扩展字段是
     * {@code (id, size, data)} 的序列，解析器会一路往下走。
     * 直接补零的话，在补零段与对齐字段交界处会读到"tag=0、size=对齐字段的两个字节"，
     * 那个 size 往往大到越界，严格的解析器（JDK 的 {@code ZipFile}）会直接拒绝整个 APK：
     * {@code Invalid CEN header (invalid extra data field size for tag: 0x0)}。
     * 所以把补零段包进一个 size 明确的字段里，整个扩展字段就是**严格合法**的：
     *
     * <pre>
     * [原有字段…][ 0xD935 | 2 | align ][ 0xCAFE | pad-4 | 0x00… ]
     * </pre>
     *
     * <p>另外 pad 必须 ≥ 4（填充字段头要放得下）；不够时再加一整轮 align，
     * 绝不能为了省几个字节产出一个非法字段。
     *
     * @param baseRaw   原扩展字段（已 normalize）
     * @param align     对齐字节数；≤0 表示不需要对齐（DEFLATE 条目不处理）
     * @param noExtraPos 扩展字段会写在这里（= 本地头起点 + 30 + 名字长度）
     */
    private static byte[] buildExtra(byte[] base, int align, long noExtraPos) {
        if (align <= 0) return base;
        int head = 6;                                   // 0xD935 对齐字段：id(2)+size(2)+align(2)
        long afterBase = noExtraPos + base.length + head;
        int pad = (int) ((align - (afterBase % align)) % align);
        while (pad < 4) pad += align;                   // 给填充字段头留出 4 字节
        byte[] out = new byte[base.length + head + pad];
        System.arraycopy(base, 0, out, 0, base.length);
        int p = base.length;
        out[p] = (byte) (ALIGN_EXTRA_ID & 0xFF);
        out[p + 1] = (byte) ((ALIGN_EXTRA_ID >>> 8) & 0xFF);
        out[p + 2] = 2;
        out[p + 3] = 0;
        out[p + 4] = (byte) (align & 0xFF);
        out[p + 5] = (byte) ((align >>> 8) & 0xFF);
        p += 6;
        int body = pad - 4;
        out[p] = (byte) (PAD_EXTRA_ID & 0xFF);
        out[p + 1] = (byte) ((PAD_EXTRA_ID >>> 8) & 0xFF);
        out[p + 2] = (byte) (body & 0xFF);
        out[p + 3] = (byte) ((body >>> 8) & 0xFF);
        // 其余留 0（字段内容）
        return out;
    }

    // ---------------- 写后自检 ----------------

    /**
     * 输出包结构自检：重新读一遍产物，回报条目的压缩方式与 .so 对齐情况。
     * 这段输出就是「补丁真的生效了」的证据，避免只靠"未抛异常"判断成功。
     */
    public static String verifyApkStructure(File apk, List<Swap> swaps) {
        StringBuilder sb = new StringBuilder();
        RandomAccessFile in = null;
        try {
            in = new RandomAccessFile(apk, "r");
            List<Src> list = readCentralDirectory(in);
            int stored = 0, deflated = 0, bad = 0;
            List<String> sos = new ArrayList<String>();
            Set<String> want = new LinkedHashSet<String>();
            for (Swap s : swaps) want.add(norm(s.entry));

            for (Src s : list) {
                boolean st = s.method == java.util.zip.ZipEntry.STORED;
                if (st) stored++; else deflated++;
                if (isSoLib(s.name)) {
                    long off = s.dataStart;
                    boolean aligned = (off % SO_ALIGN) == 0;
                    // DEFLATE 的 .so 无需对齐（extractNativeLibs=true 时由安装器解压）；
                    // 只有 STORED 的才必须 4096 对齐（extractNativeLibs=false 的 mmap 模式）。
                    boolean ok = !st || aligned;
                    if (!ok) bad++;
                    StringBuilder l = new StringBuilder();
                    l.append("    ").append(ok ? "✅" : "❌").append(" ")
                     .append(s.name).append("  ").append(st ? "STORED" : "DEFLATE")
                     .append("  ").append(s.uncompSize).append(" B")
                     .append("  data@0x").append(Long.toHexString(off))
                     .append(st ? (aligned ? "（4096 对齐 ✓）" : "（⚠ 未对齐）")
                               : "（压缩存储，无需对齐）");
                    if (want.contains(s.name)) l.append("  ← 本次替换");
                    sos.add(l.toString());
                }
            }
            sb.append("【输出包结构自检】").append(apk.getName()).append("\n");
            sb.append("  条目总数 : ").append(list.size())
              .append("（STORED ").append(stored)
              .append(" / DEFLATE ").append(deflated).append("）\n");
            sb.append("  lib/*.so : ").append(sos.size()).append(" 个\n");
            for (String l : sos) sb.append(l).append("\n");
            if (sos.isEmpty()) {
                sb.append("    （本包内没有 lib/*.so）\n");
            }
            if (bad == 0) {
                sb.append("  ✔ 原生库打包合规（STORED 的全部 4096 对齐，DEFLATE 的原样保留）"
                        + " —— 可直接重签名安装\n");
            } else {
                sb.append("  ✘ 有 ").append(bad).append(" 个 STORED 的 .so 未按 4096 对齐，"
                        + "extractNativeLibs=false 的包装不上（INSTALL_FAILED_INVALID_APK）\n");
            }
            sb.append("  签名状态 : 改包后原签名必然失效，需重签（MT 管理器「签名」页 / apksigner）\n");
        } catch (Exception e) {
            sb.append("!! 输出包自检失败: ").append(e).append("\n");
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignore) { /* ignore */ }
        }
        return sb.toString();
    }

    // ---------------- 小工具 ----------------

    private static String norm(String entry) {
        String s = entry == null ? "" : entry.trim();
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    static boolean isSoLib(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.startsWith("lib/") && n.endsWith(".so") && !n.endsWith("/");
    }

    /** 带位置计数的输出流（ZIP 偏移必须自己算） */
    private static final class BufCounter {
        private final OutputStream o;
        private final byte[] b = new byte[1 << 16];
        private int n;
        private long pos;
        BufCounter(OutputStream o) { this.o = o; }
        long pos() { return pos + n; }
        void write(byte[] d) throws IOException { write(d, 0, d.length); }
        void write(byte[] d, int off, int len) throws IOException {
            while (len > 0) {
                int room = b.length - n;
                int take = Math.min(room, len);
                System.arraycopy(d, off, b, n, take);
                n += take; off += take; len -= take;
                if (n == b.length) { o.write(b); pos += n; n = 0; }
            }
        }
        void u16(long v) throws IOException { write(new byte[]{(byte)(v & 0xFF), (byte)((v >>> 8) & 0xFF)}); }
        void u32(long v) throws IOException {
            write(new byte[]{(byte)(v & 0xFF), (byte)((v >>> 8) & 0xFF),
                             (byte)((v >>> 16) & 0xFF), (byte)((v >>> 24) & 0xFF)});
        }
        void flush() throws IOException { if (n > 0) { o.write(b, 0, n); pos += n; n = 0; } o.flush(); }
    }

    /** 原文件上的一段只读切片，供 InflaterInputStream 用 */
    private static final class SliceIn extends InputStream {
        private final RandomAccessFile raf;
        private long remain;
        SliceIn(RandomAccessFile raf, long off, long len) throws IOException {
            this.raf = raf; this.remain = len; raf.seek(off);
        }
        public int read() throws IOException {
            if (remain <= 0) return -1;
            int b = raf.read();
            if (b >= 0) remain--;
            return b;
        }
        public int read(byte[] b, int o, int l) throws IOException {
            if (remain <= 0) return -1;
            int n = raf.read(b, o, (int) Math.min(l, remain));
            if (n > 0) remain -= n;
            return n;
        }
    }

    private static void copyRange(RandomAccessFile in, long off, long len, BufCounter w, byte[] buf)
            throws IOException {
        in.seek(off);
        long left = len;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n <= 0) throw new IOException("读取源包数据中断（偏移 " + (off + len - left) + "）");
            w.write(buf, 0, n);
            left -= n;
        }
    }

    private static void writeFile(File f, BufCounter w, byte[] buf) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        try {
            int n;
            while ((n = fis.read(buf)) > 0) w.write(buf, 0, n);
        } finally {
            try { fis.close(); } catch (Exception ignore) { /* ignore */ }
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        FileInputStream fis = new FileInputStream(from);
        FileOutputStream fos = new FileOutputStream(to);
        try {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = fis.read(b)) > 0) fos.write(b, 0, n);
        } finally {
            try { fis.close(); } catch (Exception ignore) { /* ignore */ }
            try { fos.close(); } catch (Exception ignore) { /* ignore */ }
        }
    }

    private static byte[] slice(byte[] a, int off, int len) {
        if (len <= 0) return new byte[0];
        byte[] r = new byte[len];
        System.arraycopy(a, off, r, 0, len);
        return r;
    }

    private static long u16(byte[] b, int p) {
        return (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8);
    }

    private static long u32(byte[] b, int p) {
        return (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8)
                | ((b[p + 2] & 0xFFL) << 16) | ((b[p + 3] & 0xFFL) << 24);
    }

    /** 解析十六进制字符串为字节数组 */
    private static byte[] parseHex(String hex) {
        String cleaned = hex.replaceAll("\\s+", "");
        if (cleaned.length() % 2 != 0) {
            throw new IllegalArgumentException("十六进制字符串长度必须为偶数: " + hex);
        }
        byte[] result = new byte[cleaned.length() / 2];
        for (int i = 0; i < cleaned.length(); i += 2) {
            result[i / 2] = (byte) Integer.parseInt(cleaned.substring(i, i + 2), 16);
        }
        return result;
    }

    /** 解析偏移（支持 0x 前缀） */
    private static long parseOffset(String offset) {
        String s = offset.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Long.parseLong(s.substring(2), 16);
        }
        return Long.parseLong(s);
    }

    /** 计算文件 CRC32 */
    private static long crc32(File f) throws IOException {
        CRC32 crc = new CRC32();
        FileInputStream fis = new FileInputStream(f);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = fis.read(buf)) > 0) crc.update(buf, 0, n);
        } finally {
            try { fis.close(); } catch (Exception ignore) { /* ignore */ }
        }
        return crc.getValue();
    }
}
