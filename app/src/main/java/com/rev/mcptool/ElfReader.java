package com.rev.mcptool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 纯 Java 的 ELF64 解析器 —— **不依赖 radare2**。
 *
 * 为什么必须有它：真机反馈显示 r2 子进程在手机上可能挂住/超时，
 * 于是"选文件分析"里的 .so 分析就整个没了。但 .so 的结构信息
 * （段表、动态符号、导出/导入、依赖库、字符串、Dart 源路径）
 * 本身用 Java 读字节就能拿到 —— 而且不需要等待、不会卡死。
 *
 * 因此策略调整为：**ELF 结构部分纯 Java 解析（必定成功）**，
 * radare2 只作为可选增强（反汇编、aa 函数识别）。
 *
 * 支持 ELF64 小端（arm64 / x86_64），这正是 Android 的主流形态；
 * 大端或 ELF32 会明确说明"未支持"而不是给错数据。
 */
public final class ElfReader {

    public static final int SHT_PROGBITS = 1;
    public static final int SHT_SYMTAB = 2;
    public static final int SHT_STRTAB = 3;
    public static final int SHT_RELA = 4;
    public static final int SHT_DYNAMIC = 6;
    public static final int SHT_NOBITS = 8;
    public static final int SHT_DYNSYM = 11;

    public static final int PT_LOAD = 1;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Section {
        public String name = "";
        public int type;
        public long flags;
        public long addr;
        public long offset;
        public long size;
        public int link;
        public long entsize;
    }

    public static final class Sym {
        public String name = "";
        public long value;
        public long size;
        public int bind;
        public int type;
        public int shndx;
        public boolean undef;
    }

    /** 一次字符串扫描的统计与样例 */
    public static final class StrHit {
        public int total;                       // 命中总数
        public final List<String[]> sample = new ArrayList<String[]>();   // {文件偏移, 字符串}
    }

    public final File file;
    private byte[] d;
    public boolean elf64;
    public boolean little;
    public int machine;
    public int elfType;
    public long entry;
    public final List<Section> sections = new ArrayList<Section>();
    public final List<Sym> dynsyms = new ArrayList<Sym>();
    public final List<String> needed = new ArrayList<String>();
    public String soname = "";
    public String unsupported = "";
    /** PT_LOAD 覆盖的 [offset, filesz] 区间，字符串扫描用 */
    private final List<long[]> loads = new ArrayList<long[]>();

    private ElfReader(File f) { this.file = f; }

    public static ElfReader parse(File f) throws IOException {
        ElfReader r = new ElfReader(f);
        r.load();
        r.parseAll();
        return r;
    }

    private void load() throws IOException {
        long len = file.length();
        if (len <= 0 || len > 300L * 1024 * 1024) throw new IOException("文件大小异常: " + len);
        d = new byte[(int) len];
        InputStream in = new FileInputStream(file);
        try {
            int off = 0;
            while (off < d.length) {
                int n = in.read(d, off, d.length - off);
                if (n <= 0) break;
                off += n;
            }
        } finally {
            AnalysisLog.close(in);
        }
    }

    private void parseAll() {
        if (d.length < 64 || (d[0] & 0xff) != 0x7f || d[1] != 'E' || d[2] != 'L' || d[3] != 'F') {
            unsupported = "不是 ELF 文件";
            return;
        }
        int cls = d[4] & 0xff;         // 1=32bit 2=64bit
        int enc = d[5] & 0xff;         // 1=LE 2=BE
        elf64 = (cls == 2);
        little = (enc == 1);
        if (!elf64 || !little) {
            unsupported = "仅支持 ELF64 小端（arm64/x86_64）；本文件是 "
                    + (elf64 ? "ELF64" : "ELF32") + (little ? " 小端" : " 大端");
            return;
        }

        elfType = u16(16);
        machine = u16(18);
        entry = u64(24);
        long phoff = u64(32);
        long shoff = u64(40);
        int phentsize = u16(54);
        int phnum = u16(56);
        int shentsize = u16(58);
        int shnum = u16(60);
        int shstrndx = u16(62);

        // Program headers（取 PT_LOAD 区间）
        for (int i = 0; i < phnum; i++) {
            int off = (int) (phoff + (long) i * phentsize);
            if (off < 0 || off + 56 > d.length) break;
            int ptype = u32(off);
            if (ptype != PT_LOAD) continue;
            long poff = u64(off + 8);
            long pfilesz = u64(off + 32);
            loads.add(new long[]{poff, pfilesz});
        }

        // Section headers
        List<Section> raw = new ArrayList<Section>();
        for (int i = 0; i < shnum; i++) {
            int off = (int) (shoff + (long) i * shentsize);
            if (off < 0 || off + 64 > d.length) break;
            Section s = new Section();
            s.name = "";
            s.type = u32(off + 4);
            s.flags = u64(off + 8);
            s.addr = u64(off + 16);
            s.offset = u64(off + 24);
            s.size = u64(off + 32);
            s.link = u32(off + 40);
            s.entsize = u64(off + 56);
            raw.add(s);
        }
        // 段名（来自 shstrtab）
        long shstrOff = 0, shstrSize = 0;
        if (shstrndx >= 0 && shstrndx < raw.size()) {
            shstrOff = raw.get(shstrndx).offset;
            shstrSize = raw.get(shstrndx).size;
        }
        for (Section s : raw) {
            // 段名偏移在 sh_name@0，这里用同一次读取补上
            int idx = raw.indexOf(s);
            int off = (int) (shoff + (long) idx * shentsize);
            s.name = cstr(shstrOff, shstrSize, u32(off));
            sections.add(s);
        }

        // 动态符号表
        for (Section s : sections) {
            if (s.type != SHT_DYNSYM) continue;
            String strtab = "";
            long sOff = 0, sSize = 0;
            if (s.link >= 0 && s.link < sections.size()) {
                sOff = sections.get(s.link).offset;
                sSize = sections.get(s.link).size;
            }
            long entsize = s.entsize > 0 ? s.entsize : 24;
            int n = (int) (s.size / entsize);
            for (int i = 0; i < n; i++) {
                int off = (int) (s.offset + (long) i * entsize);
                if (off < 0 || off + 24 > d.length) break;
                Sym y = new Sym();
                y.name = cstr(sOff, sSize, u32(off));
                int info = d[off + 4] & 0xff;
                y.bind = (info >> 4) & 0xf;
                y.type = info & 0xf;
                y.shndx = u16(off + 6);
                y.value = u64(off + 8);
                y.size = u64(off + 16);
                y.undef = (y.shndx == 0);
                if (y.name.length() > 0) dynsyms.add(y);
            }
        }

        // .dynamic -> DT_NEEDED / DT_SONAME
        for (Section s : sections) {
            if (s.type != SHT_DYNAMIC) continue;
            long sOff = 0, sSize = 0;
            if (s.link >= 0 && s.link < sections.size()) {
                sOff = sections.get(s.link).offset;
                sSize = sections.get(s.link).size;
            }
            int n = (int) (s.size / 16);
            for (int i = 0; i < n; i++) {
                int off = (int) (s.offset + (long) i * 16);
                if (off < 0 || off + 16 > d.length) break;
                long tag = u64(off);
                long val = u64(off + 8);
                if (tag == 1) needed.add(cstr(sOff, sSize, (int) val));
                else if (tag == 14) soname = cstr(sOff, sSize, (int) val);
                else if (tag == 0) break;
            }
        }
    }

    // ---------------- 报告 ----------------

    /** 机器码 → 可读名 */
    public String machineName() {
        switch (machine) {
            case 183: return "AArch64 (arm64)";
            case 62: return "x86-64";
            case 40: return "ARM (arm32)";
            case 3: return "x86";
            default: return "machine#" + machine;
        }
    }

    public String typeName() {
        switch (elfType) {
            case 3: return "ET_DYN（共享库 / PIE）";
            case 2: return "ET_EXEC（可执行）";
            case 1: return "ET_REL（可重定位）";
            default: return "type#" + elfType;
        }
    }

    public static String sectionTypeName(int t) {
        switch (t) {
            case 0: return "NULL";
            case 1: return "PROGBITS";
            case 2: return "SYMTAB";
            case 3: return "STRTAB";
            case 4: return "RELA";
            case 5: return "HASH";
            case 6: return "DYNAMIC";
            case 7: return "NOTE";
            case 8: return "NOBITS";
            case 9: return "REL";
            case 11: return "DYNSYM";
            case 14: return "INIT_ARRAY";
            case 15: return "FINI_ARRAY";
            case 16: return "PREINIT_ARRAY";
            case 17: return "GROUP";
            case 18: return "SYMTAB_SHNDX";
            case 0x6ffffff6: return "GNU_HASH";
            case 0x6fffffff: return "GNU_VERSYM";
            case 0x6ffffffe: return "GNU_VERNEED";
            default: return "0x" + Integer.toHexString(t);
        }
    }

    private static String sectionPerm(long flags) {
        StringBuilder sb = new StringBuilder();
        sb.append((flags & 0x2) != 0 ? 'W' : '-');
        sb.append((flags & 0x4) != 0 ? 'X' : '-');
        sb.append((flags & 0x1) != 0 ? 'R' : '-');
        return sb.toString();
    }

    public List<Sym> exports() {
        List<Sym> out = new ArrayList<Sym>();
        for (Sym s : dynsyms) if (!s.undef) out.add(s);
        return out;
    }

    public List<Sym> imports() {
        List<Sym> out = new ArrayList<Sym>();
        for (Sym s : dynsyms) if (s.undef) out.add(s);
        return out;
    }

    /** 只挑有趣的名字（Dart / JNI / 网络 / 加密相关），避免清单淹没报告 */
    public List<Sym> interestingExports(int limit) {
        List<Sym> out = new ArrayList<Sym>();
        for (Sym s : exports()) {
            String n = s.name.toLowerCase(Locale.US);
            if (n.contains("dart") || n.contains("jni") || n.contains("java_")
                    || n.contains("flutter") || n.contains("snapshot")
                    || n.contains("mmkv") || n.contains("sqlite") || n.contains("verify")
                    || n.contains("sign") || n.contains("root") || n.contains("frida")
                    || n.contains("anti") || n.contains("debug") || n.contains("ssl")
                    || n.startsWith("jni_")) {
                out.add(s);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    // ---------------- 字符串扫描 ----------------

    /**
     * 单次扫描所有 PT_LOAD 数据，统计各模式命中数并留样例（含文件偏移）。
     * 一次遍历同时算多个模式，避免对 12MB 文件反复扫描。
     */
    public Map<String, StrHit> scanStrings(String[] patterns, int minLen, int samplePerPattern) {
        Map<String, StrHit> res = new LinkedHashMap<String, StrHit>();
        List<String> pats = new ArrayList<String>();
        for (String p : patterns) {
            res.put(p, new StrHit());
            pats.add(p.toLowerCase(Locale.US));
        }
        int totalStrings = 0;

        for (long[] r : loads) {
            int start = (int) r[0];
            int end = (int) Math.min(r[0] + r[1], d.length);
            if (start < 0 || start >= end) continue;
            int i = start;
            StringBuilder sb = new StringBuilder();
            int runStart = -1;
            while (i <= end) {
                int c = i < end ? (d[i] & 0xff) : 0;
                boolean printable = c >= 0x20 && c < 0x7f;
                if (printable) {
                    if (sb.length() == 0) runStart = i;
                    sb.append((char) c);
                } else {
                    if (sb.length() >= minLen) {
                        totalStrings++;
                        String s = sb.toString();
                        String low = s.toLowerCase(Locale.US);
                        for (int k = 0; k < pats.size(); k++) {
                            if (hit(low, pats.get(k))) {
                                StrHit h = res.get(patterns[k]);
                                h.total++;
                                if (h.sample.size() < samplePerPattern) {
                                    h.sample.add(new String[]{"0x" + Integer.toHexString(runStart), s});
                                }
                            }
                        }
                    }
                    sb.setLength(0);
                    runStart = -1;
                }
                i++;
            }
        }
        StrHit meta = res.get("\u0000meta\u0000");
        if (meta == null) {
            meta = new StrHit();
            res.put("\u0000meta\u0000", meta);
        }
        meta.total = totalStrings;
        return res;
    }

    public int totalStringCount(int minLen) {
        Map<String, StrHit> m = scanStrings(new String[0], minLen, 0);
        StrHit h = m.get("\u0000meta\u0000");
        return h == null ? 0 : h.total;
    }

    /**
     * 仅当前面已是完整词时才计命中的模式（防「词头误报」）。
     *
     * 实测：模式 "frida" 会命中英文词 <b>Friday</b>（friday 的小写含 "frida"），
     * 在 libflutter.so / libavformat.so 里都出现过这种假阳性，
     * 而 "Frida" 反调试/插桩相关的真实字符串几乎都带分隔符
     * （frida-gadget、frida_agent、libfrida.so、frida:），所以规则取
     * 「**匹配处后面不能再跟字母**」—— Friday 被拒，frida-gadget 照常命中。
     *
     * 只对这类短词加这条规则；"http" 必须继续命中 https、".dart"/"package:"/"dart:"
     * 本身就是带分隔符的模式，不受影响。
     */
    private static final java.util.Set<String> WORD_PREFIX_SENSITIVE =
            new java.util.HashSet<String>(java.util.Arrays.asList("frida"));

    /** 模式命中判定（带词头误报过滤，遍历所有出现位置） */
    private static boolean hit(String low, String pat) {
        boolean strict = WORD_PREFIX_SENSITIVE.contains(pat);
        int from = 0;
        while (true) {
            int i = low.indexOf(pat, from);
            if (i < 0) return false;
            int after = i + pat.length();
            if (!strict || after >= low.length() || !isLetter(low.charAt(after))) return true;
            from = i + 1;
        }
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * 是否是 Dart AOT 快照库（libapp.so 这类）。
     *
     * 判据是导出表里出现 _kDart*Snapshot* 符号 —— Dart AOT 会剥掉绝大部分符号，
     * 但快照锚点是链接器导出、必然保留。这个判定决定报告该走哪条定位路线：
     * 是 Dart AOT 就必须先做符号还原（blutter），r2 的线性分析对它是无效的。
     */
    public boolean isDartAot() {
        for (Sym s : exports()) {
            String n = s.name;
            if (n != null && n.startsWith("_kDart") && n.indexOf("Snapshot") > 0) return true;
        }
        return false;
    }

    /**
     * 在整个文件里搜索若干 ASCII 串，返回命中的文件偏移（每个串最多 8 处）。
     *
     * 用途：定位"Dart AOT 快照符号名"这类锚点 —— AOT 编译会剥掉符号表，
     * 但符号名字符串通常仍留在 .rodata 里；拿到它的文件偏移就能用 r2_xrefs
     * 顺着引用找到快照初始化代码。
     *
     * 先比对首字节再进内层循环，避免对 12MB 文件做无谓的全量比较。
     */
    public long[] findBytes(String[] needles) {
        List<Long> out = new ArrayList<Long>();
        if (needles == null) return new long[0];
        final int PER_NEEDLE = 8;
        for (String n : needles) {
            if (n == null || n.length() == 0) continue;
            byte[] pat = n.getBytes(UTF8);
            int found = 0;
            for (int i = 0; i + pat.length <= d.length && found < PER_NEEDLE; i++) {
                if (d[i] != pat[0]) continue;
                boolean hit = true;
                for (int k = 1; k < pat.length; k++) {
                    if (d[i + k] != pat[k]) { hit = false; break; }
                }
                if (hit) {
                    out.add((long) i);
                    found++;
                    i += pat.length - 1;
                }
            }
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }

    // ---------------- 轻量依赖读取（不载入整个文件） ----------------

    /** 只有动态链接信息的轻量结果，供依赖闭包检查用 */
    public static final class Deps {
        public final List<String> needed = new ArrayList<String>();
        public String soname = "";
        public String runpath = "";
        public boolean ok;
        public String error = "";
    }

    /**
     * 只读 DT_NEEDED / DT_SONAME / DT_RUNPATH，**不把整个文件读进内存**。
     *
     * 依赖闭包检查要在手机上看 20 个 libblutter_*.so 加它们的依赖
     * （其中 libicudata.so 有 33 MB），用 {@link #parse} 会把每个文件整体载入；
     * 而这里只需要 ELF 头 + 段表 + 动态段 + 动态字符串表，合计几 KB。
     *
     * 真机事故背景：打包时按硬编码白名单收依赖，漏了 libicudata.so，
     * 设备上直接报 CANNOT LINK EXECUTABLE ... library "libicudata.so" not found，
     * blutter 一步都没跑起来。静态闭包检查必须在装机前就抓住这类问题。
     */
    public static Deps depsOf(File f) {
        Deps out = new Deps();
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] hdr = new byte[64];
            if (raf.read(hdr) != 64 || (hdr[0] & 0xff) != 0x7f || hdr[1] != 'E'
                    || hdr[2] != 'L' || hdr[3] != 'F'
                    || (hdr[4] & 0xff) != 2 || (hdr[5] & 0xff) != 1) {
                out.error = "不是 ELF64 小端";
                return out;
            }
            long phoff = le64(hdr, 32);
            int phentsize = le16(hdr, 54);
            int phnum = le16(hdr, 56);
            if (phentsize < 56 || phnum <= 0 || phnum > 256) {
                out.error = "段表头异常";
                return out;
            }

            List<long[]> loads = new ArrayList<long[]>();   // {offset, vaddr, filesz}
            long dynOff = -1, dynSize = 0;
            byte[] ph = new byte[phentsize * phnum];
            readAt(raf, phoff, ph);
            for (int i = 0; i < phnum; i++) {
                int o = i * phentsize;
                int ptype = le32(ph, o);
                long poff = le64(ph, o + 8);
                long pvaddr = le64(ph, o + 16);
                long pfilesz = le64(ph, o + 32);
                if (ptype == 1) loads.add(new long[]{poff, pvaddr, pfilesz});
                else if (ptype == 2) { dynOff = poff; dynSize = pfilesz; }
            }
            if (dynOff < 0 || dynSize <= 0) {
                out.error = "没有 PT_DYNAMIC";
                return out;
            }

            int dn = (int) Math.min(dynSize, 8192);
            byte[] dyn = new byte[dn];
            readAt(raf, dynOff, dyn);
            long strtabV = -1, strsz = 0, sonameV = -1, runpathV = -1;
            List<Long> neededV = new ArrayList<Long>();
            for (int o = 0; o + 16 <= dn; o += 16) {
                long tag = le64(dyn, o);
                long val = le64(dyn, o + 8);
                if (tag == 0) break;                 // DT_NULL
                if (tag == 1) neededV.add(Long.valueOf(val));      // DT_NEEDED
                else if (tag == 5) strtabV = val;                  // DT_STRTAB (vaddr)
                else if (tag == 10) strsz = val;                   // DT_STRSZ
                else if (tag == 14) sonameV = val;                 // DT_SONAME
                else if (tag == 15 || tag == 29) {                 // DT_RPATH / DT_RUNPATH
                    if (runpathV < 0) runpathV = val;
                }
            }
            out.ok = true;
            if (strtabV < 0) return out;

            long strOff = vaddrToOff(loads, strtabV);
            if (strOff < 0) strOff = strtabV;
            if (strsz <= 0 || strsz > (256 << 10)) strsz = 256 << 10;
            byte[] str = new byte[(int) strsz];
            readAt(raf, strOff, str);
            for (Long v : neededV) out.needed.add(strAt(str, v.longValue()));
            if (sonameV >= 0) out.soname = strAt(str, sonameV);
            if (runpathV >= 0) out.runpath = strAt(str, runpathV);
            return out;
        } catch (Exception e) {
            out.error = String.valueOf(e.getMessage());
            return out;
        } finally {
            AnalysisLog.close(raf);
        }
    }

    private static void readAt(RandomAccessFile raf, long off, byte[] buf) throws IOException {
        if (off < 0) return;
        raf.seek(off);
        int got = 0;
        while (got < buf.length) {
            int n = raf.read(buf, got, buf.length - got);
            if (n <= 0) break;
            got += n;
        }
    }

    private static long vaddrToOff(List<long[]> loads, long vaddr) {
        for (long[] l : loads) {
            if (vaddr >= l[1] && vaddr < l[1] + l[2]) return l[0] + (vaddr - l[1]);
        }
        return -1;
    }

    private static String strAt(byte[] tab, long nameOff) {
        if (nameOff < 0 || nameOff >= tab.length) return "";
        int p = (int) nameOff;
        int e = p;
        while (e < tab.length && tab[e] != 0) e++;
        try {
            return new String(tab, p, e - p, UTF8);
        } catch (Exception ex) {
            return "";
        }
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8)
                | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static long le64(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xff);
        return v;
    }

    // ---------------- 原始读取 ----------------

    private int u16(int o) { return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8); }

    private int u32(int o) {
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8) | ((d[o + 2] & 0xff) << 16)
                | ((d[o + 3] & 0xff) << 24);
    }

    private long u64(int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (d[o + i] & 0xff);
        return v;
    }

    /** 从 strtab 区间里读取以 NUL 结尾的字符串 */
    private String cstr(long tabOff, long tabSize, int nameOff) {
        if (tabOff <= 0 || nameOff < 0) return "";
        long p = tabOff + nameOff;
        if (p < 0 || p >= d.length) return "";
        long end = Math.min(tabOff + tabSize, d.length);
        if (tabSize <= 0) end = d.length;
        if (p >= end) return "";
        int e = (int) p;
        while (e < end && d[e] != 0) e++;
        try {
            return new String(d, (int) p, e - (int) p, UTF8);
        } catch (Exception ex) {
            return "";
        }
    }
}
