package com.rev.mcptool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 分析日志中心。
 *
 * 设计定位：RevMCP 只负责"分析并落日志"，不再自己改包。
 * 每条分析结果都写成文件里一个**带字节/行号区间**的块，
 * MCP 客户端可以按区间分页回读完整内容，再把结论交给别的工具
 * （例如 MT 管理器 MCP）去执行 .so 替换、打补丁、重打包。
 *
 * 两个日志文件：
 *   analysis.log —— 分析结果与交接说明（客户端主要读这个）
 *   server.log   —— HTTP 访问与错误流水（排查"连不上"用）
 *
 * 纯 Java 实现，不依赖任何 android.* 类型，便于在桌面直接跑回归。
 */
public final class AnalysisLog {

    public static final String ANALYSIS = "analysis";
    public static final String SERVER = "server";

    /** 单文件上限，超过则滚动为 .1（保留一份历史，避免无限增长） */
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String EQ = rep('=', 72);
    private static final String DASH = rep('-', 72);
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    /** 一次写入在文件中的位置，供客户端按区间回读 */
    public static final class Block {
        public final String file;
        public final long startByte;
        public final long endByte;
        public final int startLine;
        public final int endLine;

        Block(String file, long startByte, long endByte, int startLine, int endLine) {
            this.file = file;
            this.startByte = startByte;
            this.endByte = endByte;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        public long bytes() { return endByte - startByte; }

        public String describe() {
            return file + "\n  字节区间 : " + startByte + " - " + endByte
                    + "（" + bytes() + " 字节）\n  行区间   : 第 "
                    + startLine + " - " + endLine + " 行（共 " + endLine + " 行）";
        }
    }

    private final File dir;
    private final Map<String, Integer> lineCache = new HashMap<String, Integer>();

    /**
     * 读写锁替代全局 synchronized。
     *
     * 之前所有方法都是 synchronized：analyze_file 这类重活每写一行日志都要抢这把独占锁，
     * 而 read_log 读日志也要抢同一把锁；更要命的是 lines() 在缓存未命中时会在**持锁状态下**
     * 做全文件 countLines（日志几 MB 就要几秒）。结果就是：一个重活在跑，
     * 所有碰日志的请求（read_log / r2_doctor / list_workdirs）全部排队 → 集体超时。
     *
     * 现在：读操作走读锁（可并发），写操作走写锁；全文件扫描一律移到锁外做。
     */
    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();

    public AnalysisLog(File dir) {
        this.dir = dir;
        if (dir != null && !dir.exists()) {
            // mkdirs 失败不致命：后续写入会各自报错，但不影响 MCP 服务本身
            dir.mkdirs();
        }
    }

    public File dir() { return dir; }

    public File file(String which) {
        return new File(dir, SERVER.equals(which) ? "server.log" : "analysis.log");
    }

    public String path(String which) {
        return file(which).getAbsolutePath();
    }

    public long size(String which) {
        File f = file(which);
        return f.exists() ? f.length() : 0L;
    }

    /**
     * 总行数（换行符个数）；带缓存，append 时增量维护。
     *
     * 关键：缓存未命中时的全文件 countLines **在锁外执行**，
     * 绝不在持锁状态下扫几 MB 的文件（旧实现正是这样把所有请求拖垮的）。
     */
    public int lines(String which) {
        String k = key(which);
        rw.readLock().lock();
        try {
            Integer c = lineCache.get(k);
            if (c != null) return c;
        } finally {
            rw.readLock().unlock();
        }
        int n = countLines(file(which));   // 锁外慢扫描
        rw.writeLock().lock();
        try {
            Integer c = lineCache.get(k);
            if (c != null) return c;       // 期间别人算好了
            lineCache.put(k, n);
            return n;
        } finally {
            rw.writeLock().unlock();
        }
    }

    /** 行号缓存预热：服务启动时后台调一次，避免首个请求撞上全文件扫描 */
    public void warmLines() {
        lines(ANALYSIS);
        lines(SERVER);
    }

    /**
     * 追加一个带边框的结果块。
     *
     * @param which analysis | server
     * @param tag   块标题（通常为 "工具名 目标"）
     * @param body  正文
     * @return 该块在文件中的区间
     */
    public Block append(String which, String tag, String body) {
        rw.writeLock().lock();
        try {
        File f = file(which);
        if (f.exists() && f.length() > MAX_BYTES) roll(f);

        long start = f.exists() ? f.length() : 0L;
        // 直接用缓存行号（缓存缺失时从 0 起算）：绝不在这里触发全文件 countLines，
        // 否则重活每写一块日志都要扫一遍几 MB 的文件，把整把锁占死。
        Integer cached = lineCache.get(key(which));
        int startLine = (cached != null ? cached.intValue() : 0) + 1;

        String now = TS.format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(EQ).append("\n");
        sb.append("[").append(now).append("] ").append(tag == null ? "" : tag).append("\n");
        sb.append(DASH).append("\n");
        if (body != null && body.length() > 0) {
            sb.append(body);
            if (body.charAt(body.length() - 1) != '\n') sb.append("\n");
        }
        sb.append(DASH).append("\n");
        sb.append("[END ").append(now).append("]\n");

        byte[] data = sb.toString().getBytes(UTF8);
        writeAppend(f, data);

        int nl = countNewlines(data);
        int endLine = startLine - 1 + nl;
        lineCache.put(key(which), endLine);
        return new Block(f.getAbsolutePath(), start, start + data.length, startLine, endLine);
        } finally {
            rw.writeLock().unlock();
        }
    }

    /** 直接追加一行（用于 server.log 这种流水记录，不加边框） */
    public void line(String which, String text) {
        if (text == null) return;
        rw.writeLock().lock();
        try {
        File f = file(which);
        if (f.exists() && f.length() > MAX_BYTES) roll(f);
        byte[] data = (text + "\n").getBytes(UTF8);
        writeAppend(f, data);
        String k = key(which);
        Integer c = lineCache.get(k);
        lineCache.put(k, (c != null ? c.intValue() : 0) + 1);
        } finally {
            rw.writeLock().unlock();
        }
    }

    /** 读取 [offset, offset+maxBytes) 区间的原始字节（读锁，可与其它读并发） */
    public byte[] readBytes(String which, long offset, int maxBytes) {
        rw.readLock().lock();
        try {
        File f = file(which);
        if (!f.exists()) return new byte[0];
        if (offset < 0) offset = 0;
        if (maxBytes <= 0) maxBytes = 20000;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            if (offset >= len) return new byte[0];
            int n = (int) Math.min(maxBytes, len - offset);
            byte[] buf = new byte[n];
            raf.seek(offset);
            raf.readFully(buf);
            return buf;
        } catch (Exception e) {
            return new byte[0];
        } finally {
            close(raf);
        }
        } finally {
            rw.readLock().unlock();
        }
    }

    /** 读末尾 n 行 */
    public String tail(String which, int n) {
        if (n <= 0) n = 50;
        File f = file(which);
        if (!f.exists()) return "";
        long len = f.length();
        int window = 256 * 1024;
        long off = len > window ? len - window : 0;
        byte[] buf = readBytes(which, off, window);
        String s = new String(buf, UTF8);
        String[] all = s.split("\n", -1);
        int from = all.length > n ? all.length - n : 0;
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < all.length; i++) {
            sb.append(all[i]);
            if (i < all.length - 1) sb.append("\n");
        }
        return sb.toString();
    }

    /** 列出日志目录下的所有日志文件（countLines 在锁外做，避免占住读锁） */
    public List<String[]> list() {
        File[] fs;
        rw.readLock().lock();
        try {
            fs = dir == null ? null : dir.listFiles();
        } finally {
            rw.readLock().unlock();
        }
        List<String[]> out = new ArrayList<String[]>();
        if (fs == null) return out;
        for (int i = 0; i < fs.length; i++) {
            File f = fs[i];
            if (f.isFile() && f.getName().endsWith(".log")) {
                out.add(new String[]{
                        f.getName(), String.valueOf(f.length()), String.valueOf(countLines(f))});
            }
        }
        return out;
    }

    /** 清空指定日志；which=all 时清空全部 */
    public void clear(String which) {
        if ("all".equals(which)) {
            clear(ANALYSIS);
            clear(SERVER);
            return;
        }
        rw.writeLock().lock();
        try {
        File f = file(which);
        try {
            if (f.exists()) f.delete();
        } catch (Exception e) { /* ignore */ }
        lineCache.remove(key(which));
        } finally {
            rw.writeLock().unlock();
        }
    }

    /** 复制日志到目标文件 */
    public File export(String which, File dest) throws IOException {
        rw.readLock().lock();
        try {
        File src = file(which);
        if (!src.exists()) throw new IOException("日志不存在: " + src.getAbsolutePath());
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent.getAbsolutePath());
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            out.flush();
        } finally {
            close(in);
            close(out);
        }
        return dest;
        } finally {
            rw.readLock().unlock();
        }
    }

    // ---------------- 内部 ----------------

    private static String key(String which) {
        return SERVER.equals(which) ? SERVER : ANALYSIS;
    }

    private void writeAppend(File f, byte[] data) {
        OutputStream os = null;
        try {
            os = new FileOutputStream(f, true);
            os.write(data);
            os.flush();
        } catch (IOException e) {
            // 日志写失败绝不能影响主流程
        } finally {
            close(os);
        }
    }

    /** 超限滚动：x.log -> x.log.1（旧的 .1 直接覆盖） */
    private void roll(File f) {
        try {
            File old = new File(f.getParentFile(), f.getName() + ".1");
            if (old.exists()) old.delete();
            f.renameTo(old);
            lineCache.remove(ANALYSIS.equals(stem(f.getName())) ? ANALYSIS : SERVER);
        } catch (Exception e) { /* ignore */ }
    }

    private static String stem(String name) {
        return name.startsWith("server") ? SERVER : ANALYSIS;
    }

    private static int countLines(File f) {
        if (f == null || !f.exists()) return 0;
        InputStream in = null;
        int n = 0;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) != -1) {
                for (int i = 0; i < r; i++) if (buf[i] == '\n') n++;
            }
        } catch (Exception e) {
            return n;
        } finally {
            close(in);
        }
        return n;
    }

    private static int countNewlines(byte[] d) {
        int n = 0;
        for (int i = 0; i < d.length; i++) if (d[i] == '\n') n++;
        return n;
    }

    private static String rep(char c, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    static void close(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Exception e) { /* ignore */ }
        }
    }
}
