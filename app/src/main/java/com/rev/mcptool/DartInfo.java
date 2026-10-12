package com.rev.mcptool;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Dart AOT 信息提取 —— 纯 Java 复刻 blutter 上游的 {@code extract_dart_info.py}。
 *
 * 为什么需要它：blutter 的 C++ 工具必须链接与目标 **Dart 版本严格对应**的 Dart VM 静态库，
 * 所以"该用哪个版本的 blutter"完全取决于从产物里读出的 Dart 版本号。
 * blutter 自己就是这么做的，本类把同样的逻辑搬到手机端，解出：
 * <ul>
 *   <li>{@code _kDartVmSnapshotData} 符号处的 snapshot hash（32 字节）与 features 串</li>
 *   <li>{@code libflutter.so} 的 {@code .rodata} 里的 engine id（40 位十六进制）与 Dart 版本号</li>
 * </ul>
 *
 * 无 {@code android.*} 依赖，桌面 JVM 可直接运行验证（见 {@code tools/DartInfoTest.java}）。
 */
public final class DartInfo {

    // ---- ELF 常量 ----
    private static final int SHT_PROGBITS = 1;
    private static final int SHT_STRTAB = 3;
    private static final int SHT_DYNSYM = 11;
    private static final int PT_LOAD = 1;

    /** libapp.so 里承载 VM snapshot 默认内容的导出符号 */
    private static final String SYM_VM_SNAPSHOT = "_kDartVmSnapshotData";
    /** 兜底：isolate snapshot 符号（部分引擎只导出这个） */
    private static final String SYM_ISO_SNAPSHOT = "_kDartIsolateSnapshotData";

    public String dartVersion;          // 形如 "3.4.2"；无法确定时为 null
    public String snapshotHash = "";    // 32 字节 ASCII
    public String flags = "";           // 空格分隔，如 "compressed-pointers ..."
    public boolean compressedPtrs;
    public String arch = "unknown";     // arm64 / x64
    public final List<String> engineIds = new ArrayList<String>();
    public String snapshotSymUsed = "";
    public String note = "";

    /** 一次解析一对库；任一参数可为 null 表示缺失 */
    public static DartInfo analyze(File libapp, File libflutter) {
        DartInfo d = new DartInfo();
        StringBuilder nb = new StringBuilder();

        if (libapp != null && libapp.isFile()) {
            try {
                RandomAccessFile raf = new RandomAccessFile(libapp, "r");
                try { d.readSnapshot(raf); } finally { raf.close(); }
            } catch (Exception e) {
                nb.append("libapp 解析失败: ").append(e.getMessage()).append("; ");
            }
        } else {
            nb.append("缺少 libapp.so（无法读 snapshot hash/flags）; ");
        }

        if (libflutter != null && libflutter.isFile()) {
            try {
                RandomAccessFile raf = new RandomAccessFile(libflutter, "r");
                try { d.readEngineInfo(raf); } finally { raf.close(); }
            } catch (Exception e) {
                nb.append("libflutter 解析失败: ").append(e.getMessage()).append("; ");
            }
        } else {
            nb.append("缺少 libflutter.so（无法确定 Dart 版本）; ");
        }

        d.note = nb.toString();
        d.compressedPtrs = d.flags.contains("compressed-pointers");
        return d;
    }

    // ================= libapp.so：snapshot hash + flags =================

    private void readSnapshot(RandomAccessFile raf) throws Exception {
        long[] hdr = elfHeader(raf);
        int eMachine = (int) hdr[0];
        long shoff = hdr[1];
        int shentsize = (int) hdr[2];
        int shnum = (int) hdr[3];
        int shstrndx = (int) hdr[4];

        arch = eMachine == 183 ? "arm64" : (eMachine == 62 ? "x64" : ("machine=" + eMachine));

        byte[] shstr = readSectionNameTable(raf, shoff, shentsize, shstrndx);
        byte[] sh = readAt(raf, shoff, shnum * shentsize);

        long dynOff = -1, dynSize = -1;
        int dynLink = -1;
        long rodataOff = -1, rodataSize = -1;
        for (int i = 0; i < shnum; i++) {
            int b = i * shentsize;
            int type = u32(sh, b + 4);
            String nm = cstr(shstr, (int) u32(sh, b));
            if (type == SHT_DYNSYM) {
                dynOff = u64(sh, b + 0x18);
                dynSize = u64(sh, b + 0x20);
                dynLink = u32(sh, b + 0x28);
            } else if (".rodata".equals(nm)) {
                rodataOff = u64(sh, b + 0x18);
                rodataSize = u64(sh, b + 0x20);
            }
        }
        readSnapshotFromDynsym(raf, sh, shentsize, shnum, dynOff, dynSize, dynLink, shstr, shstrndx);
        // flags 有时不在 dynsym 指向处，用 .rodata 兜底扫 features 串
        if (flags.isEmpty() && rodataOff >= 0) {
            String f = scanForMarker(raf, rodataOff, rodataSize, "compressed-pointers");
            if (f != null) flags = f;
        }
    }

    private void readSnapshotFromDynsym(RandomAccessFile raf, byte[] sh, int shentsize, int shnum,
                                        long dynOff, long dynSize, int dynLink,
                                        byte[] shstr, int shstrndx) throws Exception {
        if (dynOff < 0 || dynSize <= 0) return;
        // 符号表关联的字符串表
        int strSec = dynLink;
        long strOff = -1, strSize = -1;
        if (strSec >= 0 && strSec < shnum) {
            strOff = u64(sh, strSec * shentsize + 0x18);
            strSize = u64(sh, strSec * shentsize + 0x20);
        }
        if (strOff < 0) return;
        byte[] symtab = readAt(raf, dynOff, (int) dynSize);
        byte[] strtab = readAt(raf, strOff, (int) Math.min(strSize, 4L * 1024 * 1024));

        // 需要 vaddr -> 文件偏移 的映射（PT_LOAD）
        long[] ph = mapSegments(raf);

        for (String want : new String[]{SYM_VM_SNAPSHOT, SYM_ISO_SNAPSHOT}) {
            long stValue = -1, stSize = -1;
            for (int off = 0; off + 24 <= symtab.length; off += 24) {
                String name = cstr(strtab, (int) u32(symtab, off));
                if (want.equals(name)) {
                    stValue = u64(symtab, off + 8);
                    stSize = u64(symtab, off + 0x10);
                    break;
                }
            }
            if (stValue < 0) continue;
            // blutter 的 py 直接把它当文件偏移用；这里优先走 vaddr 映射，失败再回退
            long fileOff = vaddrToOff(ph, stValue);
            if (fileOff < 0) fileOff = stValue;
            if (stSize > 0 && stSize < 128) { note += want + " 符号过小; "; continue; }

            byte[] hb = readAt(raf, fileOff + 20, 32);
            if (hb == null || hb.length < 32) continue;
            snapshotHash = ascii(hb);
            byte[] fb = readAt(raf, fileOff + 20 + 32, 256);
            if (fb != null) {
                StringBuilder s = new StringBuilder();
                for (byte b : fb) {
                    if (b == 0) break;
                    s.append((char) (b & 0xff));
                }
                flags = s.toString().trim();
            }
            snapshotSymUsed = want;
            break;
        }
    }

    // ================= libflutter.so：engine id + Dart 版本 =================

    private void readEngineInfo(RandomAccessFile raf) throws Exception {
        long[] hdr = elfHeader(raf);
        int eMachine = (int) hdr[0];
        long shoff = hdr[1];
        int shentsize = (int) hdr[2];
        int shnum = (int) hdr[3];
        int shstrndx = (int) hdr[4];
        if ("unknown".equals(arch)) {
            arch = eMachine == 183 ? "arm64" : (eMachine == 62 ? "x64" : ("machine=" + eMachine));
        }
        if (eMachine == 50) arch = "x64";          // EM_IA_64（blutter 对 macOS 的判定）
        else if (eMachine == 183) arch = "arm64";

        byte[] shstr = readSectionNameTable(raf, shoff, shentsize, shstrndx);
        byte[] sh = readAt(raf, shoff, shnum * shentsize);

        long roOff = -1, roSize = -1;
        for (int i = 0; i < shnum; i++) {
            int b = i * shentsize;
            String nm = cstr(shstr, (int) u32(sh, b));
            if (".rodata".equals(nm)) {
                roOff = u64(sh, b + 0x18);
                roSize = u64(sh, b + 0x20);
                break;
            }
        }
        if (roOff < 0) { note += "libflutter 无 .rodata; "; return; }

        scanRodata(raf, roOff, roSize);
    }

    /**
     * 分块扫描 .rodata（避免把几十 MB 一次性读入内存）：
     *  - 匹配 `\x00` + 40 位小写十六进制 + 后随 `\x00` → engine id
     *  - 匹配 " (stable) (" → 取其前面最近一个 NUL 之后的字符串作为 Dart 版本
     */
    private void scanRodata(RandomAccessFile raf, long off, long size) throws Exception {
        final int CHUNK = 4 * 1024 * 1024;
        final int OVER = 64; // 重叠，避免跨块漏匹配
        byte[] buf = new byte[CHUNK + OVER];
        long pos = 0;
        String pending = null;   // 跨块保留的尾部
        while (pos < size) {
            int want = (int) Math.min(CHUNK, size - pos);
            byte[] got = readAt(raf, off + pos, want);
            if (got == null) break;

            int base = 0;
            if (pending != null) {
                // 上块尾部与本块头部拼接后再扫一次（简化：直接对拼接区做匹配）
                byte[] joined = new byte[pending.length() + got.length];
                for (int i = 0; i < pending.length(); i++) joined[i] = (byte) pending.charAt(i);
                System.arraycopy(got, 0, joined, pending.length(), got.length);
                scanBlock(joined, 0, joined.length);
                // 本块自身也扫（可能重复计入 engine id，去重后无影响）
                pos += want;
                pending = tailAsString(got);
                continue;
            }
            scanBlock(got, 0, got.length);
            pending = tailAsString(got);
            pos += want;
        }
        if (pos >= size && size > 0 && engineIds.isEmpty() && dartVersion == null) {
            note += "libflutter .rodata 未找到 engine id / Dart 版本; ";
        }
    }

    private static String tailAsString(byte[] b) {
        // 只保留尾部 ASCII 片段，用于跨块拼接检查（不需要精确，覆盖 OVER 字节即可）
        int n = Math.min(64, b.length);
        StringBuilder s = new StringBuilder(n);
        for (int i = b.length - n; i < b.length; i++) {
            int c = b[i] & 0xff;
            s.append(c < 128 ? (char) c : '\u0000');
        }
        return s.toString();
    }

    private void scanBlock(byte[] d, int from, int to) {
        // engine id: NUL + 40*[0-9a-f] + NUL
        for (int i = from; i + 42 <= to; i++) {
            if (d[i] != 0) continue;
            boolean ok = true;
            for (int j = 1; j <= 40; j++) {
                int c = d[i + j] & 0xff;
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
                if (!hex) { ok = false; break; }
            }
            if (ok && d[i + 41] == 0) {
                String id = new String(d, i + 1, 40, StandardCharsets.US_ASCII);
                if (!engineIds.contains(id)) engineIds.add(id);
            }
        }
        // Dart 版本: 找 " (stable) ("，取前一个 NUL 之后的内容
        byte[] marker = " (stable) (".getBytes(StandardCharsets.US_ASCII);
        for (int i = from; i + marker.length <= to; i++) {
            boolean hit = true;
            for (int j = 0; j < marker.length; j++) {
                if (d[i + j] != marker[j]) { hit = false; break; }
            }
            if (!hit) continue;
            int p = i - 1;
            while (p >= from && d[p] != 0) p--;
            int s = p + 1;
            if (s <= i) {
                String v = new String(d, s, i - s, StandardCharsets.US_ASCII).trim();
                if (looksLikeVersion(v) && dartVersion == null) dartVersion = v;
            }
        }
    }

    /**
     * 判断候选串是否像 Dart 版本号（形如 3.12.0 / 3.0.0-90.0 / 2.14.0）。
     * 必须至少含一个点且以数字开头，避免把引擎里其它字符串误当成版本。
     */
    private static boolean looksLikeVersion(String v) {
        if (v == null) return false;
        int n = v.length();
        if (n < 3 || n >= 40) return false;
        if (v.charAt(0) < '0' || v.charAt(0) > '9') return false;
        boolean hasDot = false;
        for (int i = 0; i < n; i++) {
            char c = v.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '.' || c == '-' || c == '+' || c == '_' || c == ' ';
            if (!ok) return false;
            if (c == '.') hasDot = true;
        }
        return hasDot;
    }

    /** 在指定区间里找一个以 NUL 分隔且含 marker 的字符串，用于 features 兜底 */
    private String scanForMarker(RandomAccessFile raf, long off, long size, String marker) throws Exception {
        final int CHUNK = 1024 * 1024;
        long pos = 0;
        while (pos < size) {
            byte[] got = readAt(raf, off + pos, (int) Math.min(CHUNK, size - pos));
            if (got == null) break;
            String s = new String(got, StandardCharsets.ISO_8859_1);
            int i = s.indexOf(marker);
            if (i >= 0) {
                int st = s.lastIndexOf('\0', i) + 1;
                int en = s.indexOf('\0', i);
                if (en < 0) en = s.length();
                String v = s.substring(st, en).trim();
                if (v.length() > 0 && v.length() < 1024) return v;
            }
            pos += got.length;
        }
        return null;
    }

    // ================= ELF 基础设施 =================

    /** @return {e_machine, e_shoff, e_shentsize, e_shnum, e_shstrndx} */
    private static long[] elfHeader(RandomAccessFile raf) throws Exception {
        byte[] h = readAt(raf, 0, 64);
        if (h == null || h.length < 64) throw new IllegalArgumentException("文件过小，非 ELF");
        if (!(h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F'))
            throw new IllegalArgumentException("不是 ELF 文件");
        if (h[4] != 2) throw new IllegalArgumentException("仅支持 ELF64");
        return new long[]{
                u16(h, 0x12),   // e_machine
                u64(h, 0x28),   // e_shoff
                u16(h, 0x3a),   // e_shentsize
                u16(h, 0x3c),   // e_shnum
                u16(h, 0x3e)    // e_shstrndx
        };
    }

    private static byte[] readSectionNameTable(RandomAccessFile raf, long shoff, int shentsize, int idx) throws Exception {
        byte[] sh = readAt(raf, shoff + (long) idx * shentsize, 64);
        long off = u64(sh, 0x18);
        long size = u64(sh, 0x20);
        return readAt(raf, off, (int) Math.min(size, 4L * 1024 * 1024));
    }

    /** PT_LOAD 段表：{p_offset, p_vaddr, p_filesz} × n，扁平存放在 long[] */
    private static long[] mapSegments(RandomAccessFile raf) throws Exception {
        byte[] h = readAt(raf, 0, 64);
        long phoff = u64(h, 0x20);
        int phentsize = u16(h, 0x36);
        int phnum = u16(h, 0x38);
        if (phoff <= 0 || phnum <= 0) return new long[0];
        List<Long> out = new ArrayList<Long>();
        for (int i = 0; i < phnum; i++) {
            byte[] p = readAt(raf, phoff + (long) i * phentsize, phentsize);
            if (p == null || u32(p, 0) != PT_LOAD) continue;
            out.add(u64(p, 0x08));  // p_offset
            out.add(u64(p, 0x10));  // p_vaddr
            out.add(u64(p, 0x20));  // p_filesz
        }
        long[] r = new long[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private static long vaddrToOff(long[] segs, long v) {
        for (int i = 0; i + 2 < segs.length; i += 3) {
            long off = segs[i], va = segs[i + 1], sz = segs[i + 2];
            if (v >= va && v < va + sz) return off + (v - va);
        }
        return -1;
    }

    private static byte[] readAt(RandomAccessFile raf, long off, int len) throws Exception {
        if (off < 0 || len <= 0 || off > raf.length()) return null;
        int n = (int) Math.min(len, raf.length() - off);
        if (n <= 0) return null;
        byte[] b = new byte[n];
        raf.seek(off);
        raf.readFully(b);
        return b;
    }

    private static String cstr(byte[] b, int off) {
        if (b == null || off < 0 || off >= b.length) return "";
        int e = off;
        while (e < b.length && b[e] != 0) e++;
        return new String(b, off, e - off, StandardCharsets.US_ASCII);
    }

    private static String ascii(byte[] b) { return new String(b, StandardCharsets.US_ASCII); }

    private static int u16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }

    private static int u32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static long u64(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xffL);
        return v;
    }

    // ================= 输出 =================

    /** blutter 的版本目录名，形如 dartvm3.4.2 */
    public String libName() {
        return dartVersion == null ? null : ("dartvm" + dartVersion);
    }

    public boolean hasCompressedPtrs() { return compressedPtrs; }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Dart version : ").append(dartVersion == null ? "(未确定)" : dartVersion).append("\n");
        sb.append("Snapshot hash: ").append(snapshotHash.isEmpty() ? "(未取到)" : snapshotHash).append("\n");
        sb.append("flags        : ").append(flags.isEmpty() ? "(未取到)" : flags).append("\n");
        sb.append("架构          : ").append(arch).append("\n");
        sb.append("指针压缩      : ").append(flags.isEmpty()
                ? "(未知 —— 缺 libapp.so，读不到 features 串；仅影响 blutter 变体选择)"
                : (compressedPtrs ? "开（Android 默认）" : "关 → 需 _no-compressed-ptrs 版 blutter")).append("\n");
        if (!engineIds.isEmpty()) {
            sb.append("engine id    : ");
            for (int i = 0; i < engineIds.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(engineIds.get(i));
            }
            sb.append("\n");
        }
        if (!snapshotSymUsed.isEmpty()) sb.append("snapshot 符号 : ").append(snapshotSymUsed).append("\n");
        if (!note.isEmpty()) sb.append("备注          : ").append(note).append("\n");
        return sb.toString();
    }
}
