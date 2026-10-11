package com.rev.mcptool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * 二进制 AndroidManifest.xml (AXML) 解码器 —— 纯 Java，零 android.* 依赖。
 *
 * AXML 结构：
 *   RES_XML_TYPE(0x0003) 头 -> [StringPool(0x0001)] [ResourceMap(0x0180)] 若干 XML 节点
 *   节点类型：START_NS(0x0100) END_NS(0x0101) START_ELEM(0x0102) END_ELEM(0x0103) CDATA(0x0104)
 *
 * 用途：让「分析 APK」不依赖 aapt2，手机端直接读清单（包名/权限/组件/uses-sdk 等）。
 */
public class AxmReader {

    private static final int RES_STRING_POOL = 0x0001;
    private static final int RES_XML_START_NS = 0x0100;
    private static final int RES_XML_END_NS = 0x0101;
    private static final int RES_XML_START_ELEM = 0x0102;
    private static final int RES_XML_END_ELEM = 0x0103;
    private static final int RES_XML_CDATA = 0x0104;

    private final byte[] d;
    private String[] strings = new String[0];
    private int[] resIds = new int[0];
    private final StringBuilder out = new StringBuilder();
    private int indent = 0;

    public AxmReader(byte[] data) {
        this.d = data;
    }

    public static String decode(byte[] data) throws IOException {
        return new AxmReader(data).parse();
    }

    public static String decodeFile(File f) throws IOException {
        return decode(ApkPack.readFile(f));
    }

    private String parse() throws IOException {
        if (d.length < 8) throw new IOException("文件过短，不是 AXML");
        int type = u16(0);
        if (type != 0x0003) throw new IOException("不是二进制 XML（type=0x" + Integer.toHexString(type) + "）");
        int off = u16(2); // headerSize of the top chunk

        while (off + 8 <= d.length) {
            int ctype = u16(off);
            int headerSize = u16(off + 2);
            int csize = i32(off + 4);
            if (csize < 8 || off + csize > d.length) break;

            if (ctype == RES_STRING_POOL) {
                readStringPool(off);
            } else if (ctype == RES_XML_START_NS) {
                String prefix = str(i32(off + headerSize));
                String uri = str(i32(off + headerSize + 4));
                // 只记录/输出有意义的命名空间
                if (prefix != null && !prefix.isEmpty()) {
                    out.append(pad()).append("xmlns:").append(prefix).append("=\"").append(uri).append("\"\n");
                }
            } else if (ctype == RES_XML_START_ELEM) {
                readStartElement(off, headerSize);
            } else if (ctype == RES_XML_END_ELEM) {
                indent--;
                if (indent < 0) indent = 0;
                out.append(pad()).append("</").append(str(i32(off + headerSize + 4))).append(">\n");
            } else if (ctype == RES_XML_CDATA) {
                int textIdx = i32(off + headerSize + 4);
                out.append(pad()).append("<!-- cdata: ").append(str(textIdx)).append(" -->\n");
            }
            off += csize;
        }
        return out.toString();
    }

    private void readStartElement(int off, int headerSize) throws IOException {
        int base = off + headerSize;
        int nsIdx = i32(base);
        int nameIdx = i32(base + 4);
        int attrStart = u16(base + 8);      // 相对 base
        int attrSize = u16(base + 10);
        int attrCount = u16(base + 12);

        String name = str(nameIdx);
        String ns = str(nsIdx);
        StringBuilder tag = new StringBuilder();
        tag.append(pad()).append("<").append(name);

        int ab = base + attrStart;
        for (int i = 0; i < attrCount; i++) {
            int ao = ab + i * attrSize;
            if (ao + 20 > d.length) break;
            int aNs = i32(ao);
            int aName = i32(ao + 4);
            int aRaw = i32(ao + 8);
            int aType = d[ao + 15] & 0xff;
            int aData = i32(ao + 16);

            String aNameStr = str(aName);
            String aNsStr = str(aNs);
            String qualified = aNameStr;
            if (aNsStr != null && aNsStr.contains("/apk/res/android")) qualified = "android:" + aNameStr;
            else if (aNsStr != null && !aNsStr.isEmpty()) qualified = aNameStr; // 其它命名空间保留原名

            tag.append("\n").append(pad()).append("    ")
                    .append(qualified).append("=\"").append(formatValue(aType, aData, aRaw)).append("\"");
        }
        tag.append(">\n");
        out.append(tag);
        indent++;
        if (ns != null && !ns.isEmpty() && nameIdx < 0) { /* no-op */ }
    }

    private String formatValue(int type, int data, int rawIdx) {
        switch (type) {
            case 0x03: // STRING
                {
                    String s = str(data);
                    if (s != null) return escape(s);
                    String r = str(rawIdx);
                    return r != null ? escape(r) : "?";
                }
            case 0x01: // REFERENCE
                return "@" + Integer.toHexString(data);
            case 0x02: // ATTRIBUTE
                return "?" + Integer.toHexString(data);
            case 0x04: // FLOAT
                return String.valueOf(Float.intBitsToFloat(data));
            case 0x05: // DIMENSION
                return fmtFloat(data) + "dip";
            case 0x06: // FRACTION
                return fmtFloat(data) + "%";
            case 0x10: // INT_DEC
                return String.valueOf(data);
            case 0x11: // INT_HEX
                return "0x" + Integer.toHexString(data);
            case 0x12: // INT_BOOLEAN
                return data != 0 ? "true" : "false";
            case 0x1c: case 0x1d: case 0x1e: case 0x1f: // colors
                return "#" + String.format("%08x", data);
            default:
                {
                    String r = str(rawIdx);
                    if (r != null) return escape(r);
                    return "0x" + Integer.toHexString(data);
                }
        }
    }

    private static String fmtFloat(int bits) {
        float f = Float.intBitsToFloat(bits);
        if (f == (int) f) return String.valueOf((int) f);
        return String.valueOf(f);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }

    private String pad() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < indent; i++) s.append("  ");
        return s.toString();
    }

    // ---------------- 字符串池 ----------------

    private void readStringPool(int off) {
        int headerSize = u16(off + 2);
        int stringCount = i32(off + 8);
        int styleCount = i32(off + 12);
        int flags = i32(off + 16);
        int stringsStart = i32(off + 20);
        boolean utf8 = (flags & 0x100) != 0;
        int[] offsets = new int[stringCount];
        for (int i = 0; i < stringCount; i++) offsets[i] = i32(off + headerSize + i * 4);
        String[] arr = new String[stringCount];
        for (int i = 0; i < stringCount; i++) {
            int p = off + stringsStart + offsets[i];
            try {
                arr[i] = utf8 ? readUtf8(p) : readUtf16(p);
            } catch (Exception e) {
                arr[i] = "";
            }
        }
        strings = arr;
        if (styleCount > 0) { /* styles 暂不使用 */ }
    }

    private String readUtf8(int p) {
        int len16 = d[p] & 0xff;
        p++;
        if ((len16 & 0x80) != 0) {
            len16 = ((len16 & 0x7f) << 8) | (d[p] & 0xff);
            p++;
        }
        int len8 = d[p] & 0xff;
        p++;
        if ((len8 & 0x80) != 0) {
            len8 = ((len8 & 0x7f) << 8) | (d[p] & 0xff);
            p++;
        }
        try {
            return new String(d, p, len8, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * UTF-16 池：长度是 **2 字节小端 uint16**（>0x7FFF 时用 2 个 uint16 组成 31 位长度），
     * 随后是 len 个 UTF-16LE 码元，最后 2 字节 0x0000 结尾。
     */
    private String readUtf16(int p) {
        int len = u16(p);
        p += 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7fff) << 16) | u16(p);
            p += 2;
        }
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int o = p + i * 2;
            if (o + 1 >= d.length) break;
            sb.append((char) ((d[o] & 0xff) | ((d[o + 1] & 0xff) << 8)));
        }
        return sb.toString();
    }

    private String str(int idx) {
        if (idx == -1 || idx < 0 || idx >= strings.length) return null;
        return strings[idx];
    }

    // ---------------- 小端读取 ----------------

    private int u16(int o) {
        if (o + 2 > d.length) return 0;
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8);
    }

    private int i32(int o) {
        if (o + 4 > d.length) return 0;
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8) | ((d[o + 2] & 0xff) << 16) | ((d[o + 3] & 0xff) << 24);
    }

    // ---------------- 桌面自测 ----------------

    public static void main(String[] args) throws Exception {
        System.out.println(decodeFile(new File(args[0])));
    }

    /** 便捷：把解码结果包一层，便于 MCP 输出 */
    public static String decodeForOutput(byte[] data) {
        try {
            return decode(data);
        } catch (IOException e) {
            return "[AXML 解码失败] " + e.getMessage();
        }
    }

    static byte[] readAll(ByteArrayOutputStream b) {
        return b.toByteArray();
    }
}
