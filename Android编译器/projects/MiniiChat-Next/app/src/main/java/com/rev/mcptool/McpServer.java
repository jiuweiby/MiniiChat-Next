package com.rev.mcptool;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 手机端本地 MCP 服务（Streamable HTTP 传输）。
 *
 * 角色定位（v1.2）：**只做分析，只写日志**。
 *   分析类工具把结果全量落到 {@link AnalysisLog} 的 analysis.log，
 *   返回值只给"日志定位 + 预览"；客户端再用 read_log 分页取全文，
 *   然后拿日志里的结论去调用别的 MCP（例如 MT 管理器）完成 .so 替换、
 *   打补丁、重打包与签名。本服务不再暴露任何写入 / 打包 / 签名工具。
 *
 * 传输兼容性（针对"连不上"的加固）：
 *   - 支持 Transfer-Encoding: chunked 请求体（Node fetch / httpx 常用）
 *   - 支持 Expect: 100-continue（先回 100 再读 body）
 *   - body 按**字节**精确读取（旧版用 Reader 按字符读，长度语义错位）
 *   - 按 Accept 头决定响应为 text/event-stream（SSE）或 application/json
 *   - GET /mcp 提供 SSE endpoint 事件（兼容旧式 HTTP+SSE 客户端）
 *   - 405/204/404 补 Allow 头，CORS 补齐 DELETE 与 Last-Event-ID
 *   - 每次请求写一行 server.log，便于远程排查连接问题
 *
 * 默认监听 0.0.0.0:8000，端点 /mcp（POST / 亦兼容）。
 */
public class McpServer implements Runnable {
    public static final String TAG = "RevMCP";
    public static final int DEFAULT_PORT = 8000;
    public static final String PATH_MCP = "/mcp";
    public static final String VERSION = "1.2.0";

    /** 分析工具返回值的预览字符数（超出部分只在日志里） */
    private static final int PREVIEW_CHARS = 1500;
    private static final int READ_MAX = 200000;
    private static final int READ_DEFAULT = 20000;

    /**
     * r2 大文件预检阈值：超过这个大小的文件不做 r2，直接引导用 blutter / 日志里读。
     * 8MB 是实测边界 —— 再大在手机端基本就是 3-10 分钟起步或卡死。
     * 想强行跑可以在同一次调用里带 {"force":"true"} 跳过预检。
     */
    private static final long R2_SIZE_LIMIT = 8L * 1024 * 1024;

    /**
     * r2 调用的硬超时（秒）。刻意取小值：
     * MCP 客户端通常 30-60s 就放弃，服务器再等下去只会让客户端看到"ETIMEDOUT"，
     * 而不是我们给出的可读结论。宁可超时返回，也不让调用方空等。
     */
    private static final int R2_TIMEOUT = 25;
    private static final int R2_TIMEOUT_LONG = 45;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final byte[] CRLFCRLF = new byte[]{'\r', '\n', '\r', '\n'};

    /** 服务端事件回调（在服务线程触发，实现方自行切主线程） */
    public interface Listener {
        void onProgress(int pct, String msg);
        void onReady(int port);
        void onError(String err);
    }

    private static final Set<String> SUPPORTED_PROTOCOLS = new HashSet<String>();
    static {
        SUPPORTED_PROTOCOLS.add("2024-11-05");
        SUPPORTED_PROTOCOLS.add("2025-03-26");
        SUPPORTED_PROTOCOLS.add("2025-06-18");
    }
    private static final String DEFAULT_PROTOCOL = "2025-06-18";

    private final Context ctx;
    private final int port;
    private final String sessionId;
    private final Listener listener;
    private ServerSocket ss;
    private volatile boolean running = false;
    private R2Bridge r2;
    private BlutterRunner blutter;
    private AnalysisLog lg;

    /** 请求线程池：旧实现每请求 new Thread 无上限，并发重活会把 CPU/内存打满 */
    private ExecutorService pool;

    /** radare2 运行时是否已就绪（冷启动/重装后需要在后台解压，期间不能阻塞 accept） */
    private volatile boolean runtimeReady = false;
    private volatile String runtimeError = null;
    private volatile String runtimePhase = "未开始";

    /** 异步任务表：重活（analyze_file 等）提交后返回 jobId，客户端轮询取结果 */
    private final Map<String, Job> jobs = new ConcurrentHashMap<String, Job>();
    private final AtomicLong jobSeq = new AtomicLong(1);

    /**
     * 并发重活上限。
     * 连续提交多个 analyze_file / blutter_run 会把 CPU 和 IO 打满，
     * 表现就是「所有接口一起超时，停一会儿又能 curl 通」。
     * 超过上限的任务直接拒绝并说明原因，而不是排队堆积把服务拖垮。
     */
    private static final int MAX_HEAVY_JOBS = 2;
    private final Semaphore heavySem = new Semaphore(MAX_HEAVY_JOBS);

    /**
     * 动态产物白名单（canonical path）。工具把产物写到用户指定的目录时登记进来，
     * 保证「写得出、读得回」—— blutter_run 的 out_dir、extract_entry 的 out_dir、
     * replace_entry 的 out_apk 等都可能落在产物根之外。
     */
    private final Set<String> extraRoots =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    /**
     * 读类工具的**串行闸门**（容量 1，公平排队）。
     *
     * <p>为什么需要：{@code list_artifacts} / {@code read_artifact} / {@code dart_symbols}
     * 会在 blutter 产物树里扫成千上万个文件。客户端一次性并发发三个读请求时
     * （实测：3 个并发全 timeout，改成逐个调用就成功），它们抢同一个存储卷、
     * 互相把对方拖慢，最后一起挂到客户端超时。
     *
     * <p>服务端只留一个读槽位：请求**排队**而不是互相踩，等太久会明确回
     * "在读队列里排队"，而不是让客户端一直干等。写类 / 重活工具不走这道门
     * （它们有各自的 {@link #heavySem}）。
     */
    private static final long READ_GATE_WAIT_MS = 20000;
    private final Semaphore readGate = new Semaphore(1, true);

    /** 走串行闸门的只读工具名 */
    private static final Set<String> GATED_READS = new HashSet<String>(java.util.Arrays.asList(
            "list_artifacts", "list_workdir_artifacts", "list_workdirs",
            "read_artifact", "read_pp_summary", "read_so_bytes", "dart_symbols"));

    public McpServer(Context ctx, int port, Listener listener) {
        this.ctx = ctx == null ? null : ctx.getApplicationContext();
        this.port = port;
        this.listener = listener;
        this.sessionId = UUID.randomUUID().toString();
        configurePaths();
    }

    /**
     * 选定产物根目录：**用用户在界面上选的那个**（{@link OutDir}）。
     *
     * <p>服务与界面共用同一份选择（{@link RevPrefs} 持久化），所以「界面显示哪个目录、
     * 服务就往哪个目录写」永远一致。未选择时落 app 私有目录，并由
     * {@link RevPaths#describe()}（service_status 可见）明确写出「未选择 / 非最终位置」，
     * 不静默改写落点。
     */
    private void configurePaths() {
        OutDir.apply(ctx);
    }

    public McpServer(Context ctx, int port) {
        this(ctx, port, null);
    }

    /** 惰性创建 R2Bridge：与参数校验无关，缺参数时先抛出参数错误 */
    private R2Bridge r2() {
        if (r2 == null) r2 = new R2Bridge(ctx);
        return r2;
    }

    /** 惰性创建 BlutterRunner（无 Context 的桌面环境返回 null，调用方给出说明） */
    private BlutterRunner blutter() {
        if (blutter == null && ctx != null) blutter = new BlutterRunner(ctx);
        return blutter;
    }

    /**
     * 分析类工作目录：{@code <产物根>/work}。
     *
     * <p>MCP 调用解析与界面「手动选择解析」走的是**同一个** work 目录，
     * 这样两条路径的产物都落在同一个根下（根由用户在界面上选定），
     * 一个目标一个子目录，文件管理器里直接能翻。
     */
    private File workDir() {
        return RevPaths.work();
    }

    /**
     * 日志目录：{@code <产物根>/logs}。
     *
     * <p>根目录由 {@link RevPaths} 决定（用户选定 / 未选择时的过渡位置），
     * 这样 analysis.log 也能被文件管理器直接打开，不用去翻 Android/data/。
     */
    public AnalysisLog log() {
        // 目录可能被用户在界面上改掉（选择目录 / 清除选择）：这里跟着当前根目录走，
        // 否则会出现「界面显示新目录、分析报告还写旧目录」的错位。
        File want = RevPaths.logs();
        if (lg == null || !want.equals(lg.dir())) {
            lg = new AnalysisLog(want);
        }
        return lg;
    }

    /** 从宿主目录拷进来的副本目录 */
    private File inputDir() { return RevPaths.input(); }

    /** 改包产物默认输出目录 */
    private File outDir() { return RevPaths.out(); }

    public int getPort() { return port; }

    public boolean isRunning() { return running; }

    public String getEndpoint() { return "http://127.0.0.1:" + port + PATH_MCP; }

    public void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (Exception e) { /* ignore */ }
        try { if (pool != null) pool.shutdownNow(); } catch (Exception e) { /* ignore */ }
        try { jobs.clear(); } catch (Exception e) { /* ignore */ }
    }

    public void run() {
        try {
            File d = log().dir();
            if (d != null && !d.exists()) d.mkdirs();
            log().line(AnalysisLog.SERVER, "=== RevMCP " + VERSION + " 启动，端口 " + port
                    + "，日志目录 " + (d == null ? "?" : d.getAbsolutePath()) + " ===");

            // 关键改动：**先 bind 再准备运行时**。
            // 旧实现把 r2.ensureExtracted（解压几十 MB 的 radare2 运行时）放在 bind 之前同步执行，
            // 冷启动 / 重装后这段解压要几十秒，期间端口根本没在监听 —— 客户端看到的就是
            // "所有接口全部超时"，而且连 work 目录都还没建出来。
            // 现在端口立刻可用，运行时在后台准备，未就绪时相关工具返回明确的"准备中"提示。
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("0.0.0.0", port));
            running = true;
            Log.d(TAG, "MCP server listening on :" + port);
            if (listener != null) listener.onReady(port);

            // 有界线程池 + AbortPolicy：
            // 绝对不能用 CallerRunsPolicy —— 它会在队列满时让 **accept 线程自己执行任务**，
            // 直接把 accept 循环阻塞住，表现就是「短时密集请求 → 全接口超时，停一下又好了」。
            // 现在队列满就直接拒绝并返回 503，accept 线程始终只负责接连接。
            pool = new ThreadPoolExecutor(
                    4, 16, 60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>(32),
                    new ThreadPoolExecutor.AbortPolicy());

            prepareRuntimeAsync();

            // 行号缓存预热放后台：避免首个 read_log 撞上全文件 countLines
            new Thread(new Runnable() {
                public void run() {
                    try { log().warmLines(); } catch (Exception ignore) { /* ignore */ }
                }
            }, "log-warm").start();

            while (running && !ss.isClosed()) {
                Socket s = null;
                try {
                    s = ss.accept();
                    s.setSoTimeout(120000);
                    pool.execute(new Handler(s));
                    s = null;   // 已交给线程池，别在 finally 里关掉
                } catch (RejectedExecutionException e) {
                    // 过载：快速失败并明确告知，绝不阻塞 accept 循环
                    Log.w(TAG, "too many concurrent requests, rejecting");
                    if (s != null) writeBusy(s);
                } catch (Exception e) {
                    if (running) Log.e(TAG, "accept error", e);
                    if (s != null) {
                        try { s.close(); } catch (Exception ignore) { /* ignore */ }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "server fatal", e);
            running = false;
            try { log().line(AnalysisLog.SERVER, "!! 启动失败: " + e); } catch (Exception ignore) { /* ignore */ }
            if (listener != null) {
                String m = e.getMessage();
                listener.onError(m == null ? e.toString() : m);
            }
        }
    }

    /**
     * 后台准备 radare2 运行时。
     *
     * 冷启动 / 重装后这段解压可能要几十秒。放在后台，端口已经能收请求，
     * 期间调用 r2_* 会拿到明确的"准备中"提示，而不是把 socket 一直挂到超时。
     */
    private void prepareRuntimeAsync() {
        if (ctx == null) {
            // 桌面测试环境：无 assets，直接视为就绪
            runtimeReady = true;
            runtimePhase = "已就绪（桌面环境，无 radare2 运行时）";
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    runtimePhase = "正在解压 radare2 运行时…";
                    if (listener != null) listener.onProgress(1, runtimePhase);
                    r2 = new R2Bridge(ctx);
                    r2.ensureExtracted(new R2Bridge.Progress() {
                        public void onProgress(int pct, String msg) {
                            runtimePhase = msg;
                            if (listener != null) listener.onProgress(pct, msg);
                        }
                    });
                    runtimeReady = true;
                    runtimePhase = "radare2 运行时已就绪";
                    if (listener != null) listener.onProgress(100, runtimePhase);
                } catch (Throwable e) {
                    runtimeError = String.valueOf(e.getMessage());
                    runtimePhase = "radare2 运行时准备失败: " + runtimeError;
                    Log.e(TAG, "prepare runtime", e);
                    try { log().line(AnalysisLog.SERVER, "!! 运行时准备失败: " + e); }
                    catch (Exception ignore) { /* ignore */ }
                }
            }
        }, "r2-prepare").start();
    }

    /**
     * r2_* 工具的必填参数预检（与各分支里的 {@code req()} 保持一致）。
     *
     * <p>存在的意义：冷启动时运行时守卫排在前面，客户端传空参数会先撞上
     * "【radare2 运行时尚未就绪】"，看起来像工具坏了。先查参数就能给出
     * "缺少参数: path" —— 这是调用方真正需要的信息。
     *
     * @return 缺失的参数名；不缺则 null
     */
    private String missingParamOf(String name, JSONObject a) {
        if (a.optString("path", "").isEmpty()) return "path";
        if (("r2_disasm".equals(name) || "r2_decompile".equals(name))
                && a.optString("addr", "").isEmpty()) return "addr";
        if ("r2_search".equals(name) && a.optString("pattern", "").isEmpty()) return "pattern";
        if ("r2_cmd".equals(name) && a.optString("cmd", "").isEmpty()) return "cmd";
        return null;
    }

    /** r2_* 工具在运行时未就绪时的统一提示 */
    private String guardRuntime() {
        if (runtimeReady) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("【radare2 运行时尚未就绪】\n\n");
        sb.append("当前阶段 : ").append(runtimePhase).append("\n");
        if (runtimeError != null) sb.append("错误     : ").append(runtimeError).append("\n");
        sb.append("\n服务端口已经在监听，但 radare2 运行时正在后台解压（冷启动/重装后首次较慢）。\n");
        sb.append("请稍候几秒后重试；解压完成后本提示消失。\n");
        sb.append("不依赖 radare2 的工具（analyze_file 的包级画像、dart_symbols、read_log 等）现在就能用。\n");
        sb.append("可用 r2_doctor 查看运行时状态。");
        return sb.toString();
    }

    // ================= 异步任务（job） =================

    /** 一个后台分析任务：重活提交后立即返回 jobId，客户端轮询取结果 */
    public static final class Job {
        public final String id;
        public final String tool;
        public final String target;
        public final long submitMs = System.currentTimeMillis();
        public volatile String state = "RUNNING";   // RUNNING / DONE / FAILED
        public volatile String progress = "";
        public volatile String result = null;
        public volatile long doneMs = 0;
        /** 失败原因摘要（便于一眼看出为什么失败，不用翻完整堆栈） */
        public volatile String error = null;
        /** 结果在 analysis.log 里的落盘区间 —— 即使客户端看不到 job_result 工具也能用 read_log 回收 */
        public volatile String logFile = null;
        public volatile long logStart = 0;
        public volatile long logEnd = 0;

        Job(String id, String tool, String target) {
            this.id = id;
            this.tool = tool;
            this.target = target;
        }

        public long elapsedMs() {
            return (doneMs > 0 ? doneMs : System.currentTimeMillis()) - submitMs;
        }
    }

    /** 提交一个后台任务并立刻返回（不等它跑完） */
    private Job submitJob(String tool, String target, final Callable<String> task) {
        // 并发重活限流：拿不到名额就明确拒绝，绝不无限排队把整台设备拖死
        boolean acquired = false;
        try {
            acquired = heavySem.tryAcquire(90, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            acquired = false;
        }
        final Job j = new Job("job-" + jobSeq.getAndIncrement(), tool, target);
        jobs.put(j.id, j);
        if (!acquired) {
            j.state = "FAILED";
            j.error = "并发重活已达上限 " + MAX_HEAVY_JOBS + " 个，本次任务被拒绝";
            j.result = "!! " + j.error
                    + "\n\n原因：同时跑太多 analyze_file / blutter_run 会把设备 CPU/IO 打满，"
                    + "导致所有接口一起超时。\n"
                    + "处理：先用 job_list 看现有任务，等它们跑完再提交；"
                    + "或只保留最必要的那一个。";
            j.doneMs = System.currentTimeMillis();
            return j;
        }
        // 只保留最近 20 个任务，避免长期运行堆积
        if (jobs.size() > 20) {
            try {
                List<Job> all = new ArrayList<Job>(jobs.values());
                Collections.sort(all, new Comparator<Job>() {
                    public int compare(Job x, Job y) {
                        return x.submitMs < y.submitMs ? -1 : (x.submitMs == y.submitMs ? 0 : 1);
                    }
                });
                for (int i = 0; i < all.size() - 20; i++) jobs.remove(all.get(i).id);
            } catch (Exception ignore) { /* ignore */ }
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                String out;
                try {
                try {
                    out = task.call();
                    j.state = "DONE";
                } catch (Throwable e) {
                    // 错误必须外抛：旧版只留一句 "任务失败: xxx"，宿主路径 EACCES 之类的
                    // 真实原因被吞掉，表现为「后台静默失败、list_copied_inputs 一直空」
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    j.error = msg;
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    out = "!! 任务失败: " + e + "\n\n失败原因: " + msg
                            + "\n\n完整堆栈:\n" + sw.toString();
                    j.state = "FAILED";
                    try {
                        log().line(AnalysisLog.SERVER,
                                "!! " + j.id + " " + j.tool + " FAILED: " + msg);
                    } catch (Exception ignore) { /* ignore */ }
                }
                j.result = out;
                j.doneMs = System.currentTimeMillis();

                // 结果（含失败堆栈）一律落盘到 analysis.log。
                // 这样即使客户端的工具清单是旧缓存、看不到 job_result（TOOL_NOT_FOUND），
                // 也能用 read_log 这条一定存在的老通道把结果捞回来。
                try {
                    AnalysisLog.Block b = log().append(AnalysisLog.ANALYSIS,
                            "[" + j.id + "] " + j.tool + " " + j.target + " → " + j.state,
                            out);
                    j.logFile = b.file;
                    j.logStart = b.startByte;
                    j.logEnd = b.endByte;
                } catch (Exception ignore) { /* ignore */ }
                } finally {
                    heavySem.release();
                }
            }
        }, j.id);
        t.setDaemon(true);
        t.start();
        return j;
    }

    /** analyze_file 的实际执行体（同步 / 异步共用） */
    /**
     * 把"可能没权限读的路径"解析成"当前真的能读的路径"：
     *   1) 原路径能读 → 原样返回
     *   2) 否则去 input/ 缓存找同名副本 → 找到就用缓存路径
     *   3) 都没有 → null（由调用方给出明确指引）
     */
    private String resolveReadable(String path) {
        if (path == null || path.length() == 0) return null;
        File f = new File(path);
        if (f.isFile() && StoragePerm.canRead(path)) return path;

        File inDir = new File(log().dir().getParentFile(), "input");
        String name = f.getName();
        File cand = new File(inDir, name);
        if (cand.isFile() && StoragePerm.canRead(cand.getAbsolutePath())) {
            return cand.getAbsolutePath();
        }
        // 导入时文件名里的 / 会被替换成 _，再试一次
        File cand2 = new File(inDir, name.replace('/', '_'));
        if (cand2.isFile() && StoragePerm.canRead(cand2.getAbsolutePath())) {
            return cand2.getAbsolutePath();
        }
        return null;
    }

    private String runAnalyzeFile(String p, File work, File logsDir,
                                  boolean withNative, boolean deep, String[] extra)
            throws Exception {
        // 宿主路径（MT2/ 等）常常 EACCES：先试缓存里有没有同名副本，有就直接用，
        // 免得用户还要手工找路径。
        String resolved = resolveReadable(p);
        if (resolved == null) {
            return "!! 读不到目标文件，且 input/ 缓存里也没有同名副本\n\n"
                    + "  原路径 : " + p + "\n"
                    + "  原因   : " + (StoragePerm.hasAllFilesAccess(ctx)
                        ? "路径不存在或不可读" : "未授予「所有文件访问」权限，第三方目录会 EACCES")
                    + "\n\n"
                    + "  两个办法：\n"
                    + "  1) 让用户在 RevMCP 界面点「选择 APK / .SO 文件分析」，用系统选择器选中该文件；\n"
                    + "     选中后会被拷进 input/ 暂存目录并给出可用路径（零权限通道）\n"
                    + "  2) 或在系统设置页给 RevMCP 打开「允许访问所有文件」"
                    + "（界面「选择目录」会引导到那一页），之后可直接用绝对路径\n\n"
                    + "  当前权限状态: " + StoragePerm.brief(ctx);
        }
        p = resolved;
        BlutterRunner br = blutter();
        AnalysisRun.SymbolStage stage = null;
        if (br != null) {
            try { stage = br.stage(420, 200000); } catch (Exception e) { /* ignore: skip flutter */ }
        }
        AnalysisRun.Result res = AnalysisRun.run(p, work, logsDir,
                ctx == null ? null : R2Bridge.runner(r2()), withNative, deep, null, stage, extra);
        // 把完整的分析块（含字节区间）一并返回，AI 可按区间分页回读全文
        StringBuilder sb = new StringBuilder();
        sb.append("=== analyze_file 完成 ===\n");
        sb.append("文件   : ").append(p).append("\n");
        sb.append("工作目录 : ").append(res.workDir != null ? res.workDir.getAbsolutePath() : "(未知)").append("\n");
        sb.append("耗时   : ").append(res.elapsedMs).append("ms\n");
        if (res.libsAnalyzed > 0) sb.append("原生库分析 : ").append(res.libsAnalyzed).append(" 个\n");
        sb.append("\n--- 摘要 ---\n").append(res.summary).append("\n");
        sb.append("--- 完整内容区间（用 read_log 分页读） ---\n");
        appendBlock(sb, "包级画像", res.apkBlock);
        appendBlock(sb, "原生库分析", res.nativeBlock);
        appendBlock(sb, "Flutter 符号", res.symbolBlock);
        return logged("analyze_file", p, sb.toString());
    }

    /** blutter_run 的实际执行体（同步 / 异步共用） */
    private String runBlutterRun(JSONObject a) throws Exception {
            BlutterRunner br = blutter();
            if (br == null) return "!! 桌面环境（无 Context），无法执行 blutter";
            String apkPath = a.optString("apk_path", "");
            String soPath = a.optString("so_path", "");
            int timeout = a.optInt("timeout_sec", 300);
            int ppBytes = a.optInt("pp_bytes", 200000);

            File libapp;
            File libflutter = null;
            String label;
            if (!apkPath.isEmpty()) {
                File apk = new File(apkPath);
                if (!apk.isFile()) return "!! 找不到 APK: " + apkPath;
                File work = new File(workDir(), nameOf(apk.getName()));
                work.mkdirs();
                String appEntry = pickLibEntry(apkPath, "libapp.so");
                String fltEntry = pickLibEntry(apkPath, "libflutter.so");
                if (appEntry == null) {
                    return "!! 该 APK 里没有 lib/<abi>/libapp.so —— 可能不是 Flutter 应用，"
                            + "或它是 debug 构建（走 kernel_blob.bin，没有 AOT snapshot，blutter 不适用）。\n"
                            + "   用 list_apk_entries 确认 lib/ 下的实际条目。";
                }
                libapp = ApkAnalyzer.extractEntry(apk, appEntry, work);
                if (fltEntry != null) libflutter = ApkAnalyzer.extractEntry(apk, fltEntry, work);
                label = apkPath;
                if (libapp == null) return "!! 解出 libapp.so 失败: " + appEntry;
            } else {
                libapp = new File(soPath);
                if (!libapp.isFile()) return "!! 找不到文件: " + soPath;
                File parent = libapp.getParentFile();
                File f = parent == null ? null : new File(parent, "libflutter.so");
                if (f != null && f.isFile()) libflutter = f;
                label = soPath;
            }

            DartInfo di = DartInfo.analyze(libapp, libflutter);
            File outDir = blutterOutDir(libapp);

            // 产物复用：同一个 libapp.so（SHA-256 一致）且产物还在 → 直接返回已有结果，
            // 不重跑 blutter。传 force=true 可强制重跑。
            if (!"true".equalsIgnoreCase(a.optString("force", "false"))) {
                File reuseTsv = BlutterCache.reusable(libapp, outDir);
                if (reuseTsv != null) {
                    File reusePp = BlutterCache.findPp(outDir);
                    StringBuilder rb = new StringBuilder();
                    rb.append("✅ blutter 命中产物缓存（未重新运行）\n");
                    rb.append("原因     : libapp.so 的 SHA-256 与上次一致\n");
                    rb.append("符号索引 : ").append(reuseTsv.getAbsolutePath()).append("\n");
                    if (reusePp != null) {
                        rb.append("原始 dump: ").append(reusePp.getAbsolutePath()).append("\n");
                    }
                    rb.append("Dart 版本: ").append(di.dartVersion).append("\n");
                    rb.append("\n下一步（直接用，不必再等）:\n");
                    rb.append("  dart_symbols {\"pp_dir\":\"").append(outDir.getAbsolutePath())
                      .append("\",\"q\":\"方法名\"}\n");
                    rb.append("\n若确认要强制重跑：blutter_run {...,\"force\":\"true\"}\n");
                    return logged("blutter_run（复用）", label, rb.toString());
                }
            }

            BlutterRunner.Outcome o = br.analyze(libapp, outDir, di.dartVersion, timeout, null);
            // 成功后记录指纹，供下次复用判断
            if (o.ok) BlutterCache.mark(libapp, outDir, di.dartVersion, 0);
            String loc = logged("blutter_run", label + "（Dart " + di.dartVersion + "）",
                    br.report(o, di.dartVersion));

            // 符号摘要 + 原始 dump 头部单独落一个大块（工具返回里只放概要），
            // 产物文件本身用 read_artifact / dart_symbols / list_artifacts 读
            StringBuilder sb = new StringBuilder();
            sb.append("✅ blutter ").append(o.ok ? "完成" : "未产出可读结果").append("\n");
            sb.append("概要     : ").append(loc).append("\n");
            // 版本匹配标记：让用户/AI 明确知道是否精确匹配、是否放宽
            sb.append("版本匹配 : 目标 Dart ").append(di.dartVersion)
              .append(" ｜ blutter ").append(o.lastTried != null ? o.lastTried : "?")
              .append("\n");
            if (o.ok) {
                sb.append("匹配类型 : ").append(o.relaxed
                        ? "⚠ 放宽校验（patch version 不完全一致，偏移可能有偏差，需人工核对）"
                        : "✓ 精确匹配（对象池偏移可信）").append("\n");
            }
            if (o.ok) {
                File pp = new File(o.outDir, "pp.txt");
                if (o.symbols != null && o.symbols.isFile()) {
                    sb.append("符号索引 : ").append(o.symbols.getAbsolutePath()).append("\n");
                    sb.append("        : ").append(o.symbolCount).append(" 条（地址 / Dart 源位置 / Class::method）\n");
                    sb.append("摘要     : ").append(new File(o.outDir, PpDigest.TXT_NAME).getAbsolutePath())
                      .append("（业务包 ").append(o.topPackage == null || o.topPackage.length() == 0
                              ? "未识别" : o.topPackage).append("）\n");
                }
                if (pp.isFile()) {
                    String body = BlutterRunner.headOf(pp, Math.max(4096, ppBytes));
                    String loc2 = logged("blutter_pp",
                            label + " :: 符号摘要 + 原始 dump 前 " + (body.length() / 1024) + "KB",
                            PpDigest.report(PpDigest.parse(pp), 18, 12, 40)
                            + "\n  ──────────── 原始 pp.txt 开头 ────────────\n" + body);
                    sb.append("概览     : ").append(PpDigest.oneLine(PpDigest.parse(pp))).append("\n");
                    sb.append("        : 结构摘要 + 原始 dump 前 ").append(body.length() / 1024)
                      .append("KB 已入日志（").append(loc2).append("）\n");
                sb.append("原始 dump: ").append(pp.getAbsolutePath())
                  .append("（blutter 固定产物名 pp.txt，原样保留）\n");
                sb.append("查符号   : dart_symbols {\"pp_dir\":\"").append(o.outDir.getAbsolutePath())
                  .append("\",\"pkg\":\"业务包名\",\"q\":\"方法名\"}\n");
                sb.append("读产物   : read_log 只能读日志；产物请用 list_artifacts / "
                        + "read_artifact / dart_symbols\n");
                }
                File asm = new File(o.outDir, "asm");
                if (asm.isDirectory()) {
                    String[] fs = asm.list();
                    sb.append("asm/     : ").append(asm.getAbsolutePath())
                      .append("（").append(fs == null ? 0 : fs.length).append(" 个符号反汇编文件）\n");
                }
            } else if (o.error != null) {
                sb.append("原因     : ").append(o.error).append("\n");
            } else {
                // 有候选、跑完却都没产出（真机最常见的情形：依赖没链接上）
                sb.append("原因     : ").append(o.tried.size()).append(" 个候选都没跑通");
                if (o.lastTried != null) {
                    sb.append("（最后试跑 ").append(o.lastTried)
                      .append("，退出码 ").append(o.exitCode).append("）");
                }
                sb.append("\n");
                if (o.closure != null && !o.closure.complete) {
                    sb.append("依赖闭包 : ✘ 缺 ").append(o.closure.missing)
                      .append(" —— CANNOT LINK EXECUTABLE 的根因\n")
                      .append("        : 先跑 blutter_doctor 看完整闭包\n");
                } else {
                    sb.append("详情     : 读上面那块日志的「blutter 运行输出」\n");
                }
            }
            return sb.toString();
    }

    /** 任务刚提交时的返回文本：告诉客户端 jobId 和怎么取结果 */
    private String jobAccepted(Job j, String hint) {
        StringBuilder sb = new StringBuilder();
        sb.append("⏳ 已提交后台任务（不会阻塞连接）\n\n");
        sb.append("  job_id  : ").append(j.id).append("\n");
        sb.append("  工具    : ").append(j.tool).append("\n");
        sb.append("  目标    : ").append(j.target).append("\n");
        sb.append("\n取结果（三选一，都秒回）：\n");
        sb.append("  1) job_result {\"job_id\":\"").append(j.id).append("\"}\n");
        sb.append("  2) job_status {\"job_id\":\"").append(j.id).append("\"}\n");
        sb.append("  3) 结果同时会写进 analysis.log，可用 read_log 按字节区间回读：\n");
        sb.append("     read_log {\"offset\":\"<见 job_status 的日志区间>\",\"max_bytes\":100000}\n");
        sb.append("\n若 job_result 报 TOOL_NOT_FOUND：那是客户端缓存了旧的工具清单，\n");
        sb.append("重启/重连 MCP 客户端刷新工具列表即可；在刷新之前请走上面第 3 条 read_log 通道。\n");
        sb.append("\n任务完成后结果自动带出（含失败堆栈），未完成会告诉你已跑了多少秒。\n");
        if (hint != null && hint.length() > 0) sb.append("\n").append(hint).append("\n");
        return sb.toString();
    }

    private Job findJob(JSONObject a) {
        String id = a.optString("job_id", "");
        if (id.length() == 0) return null;
        return jobs.get(id);
    }

    private String jobStatusText(Job j, boolean withResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("■ 任务 ").append(j.id).append("\n");
        sb.append("  工具    : ").append(j.tool).append("\n");
        sb.append("  目标    : ").append(j.target).append("\n");
        sb.append("  状态    : ").append("DONE".equals(j.state) ? "✅ 完成"
                : "FAILED".equals(j.state) ? "❌ 失败" : "⏳ 运行中").append("\n");
        sb.append("  已耗时  : ").append(j.elapsedMs() / 1000).append("s\n");
        if (j.progress.length() > 0) sb.append("  进度    : ").append(j.progress).append("\n");
        if (j.error != null) sb.append("  失败原因: ").append(j.error).append("\n");
        if (j.logFile != null) {
            sb.append("  日志区间: read_log {\"offset\":\"").append(j.logStart)
              .append("\",\"max_bytes\":\"").append(j.logEnd - j.logStart).append("\"}\n");
            sb.append("           （文件 ").append(j.logFile).append("）\n");
        }
        if ("DONE".equals(j.state) || "FAILED".equals(j.state)) {
            sb.append("\n");
            if (withResult && j.result != null) sb.append(j.result);
            else sb.append("（用 job_result {\"job_id\":\"").append(j.id)
                    .append("\"} 取完整结果）");
        } else {
            sb.append("\n还在跑，稍后再查。分析类重活通常 30-60 秒。");
        }
        return sb.toString();
    }

    /** 过载时给客户端一个明确答复，而不是让连接挂死或直接断掉 */
    private void writeBusy(Socket s) {
        try {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32000,"
                    + "\"message\":\"服务繁忙：并发请求过多，请稍后重试"
                    + "（重活请用 job_* 异步接口，别连续同步提交）\"}}";
            byte[] b = body.getBytes(UTF8);
            OutputStream os = s.getOutputStream();
            os.write(("HTTP/1.1 503 Service Unavailable\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + b.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(LATIN1));
            os.write(b);
            os.flush();
        } catch (Exception ignore) { /* ignore */ }
        finally {
            try { s.close(); } catch (Exception ignore) { /* ignore */ }
        }
    }

    // ================= HTTP 处理 =================

    private class Handler implements Runnable {
        private final Socket socket;
        private Map<String, String> hdrs;
        private String method = "?";
        private String path = "/";
        private int code = 0;
        private int respLen = 0;
        private String extra = "";

        Handler(Socket s) { this.socket = s; }

        public void run() {
            long t0 = System.currentTimeMillis();
            try {
                socket.setSoTimeout(120000);
                InputStream in = new BufferedInputStream(socket.getInputStream());

                byte[] rawHead = readHeaderBlock(in);
                if (rawHead == null) return;

                String headText = new String(rawHead, LATIN1);
                String[] lines = headText.split("\r\n");
                if (lines.length == 0 || lines[0].isEmpty()) return;

                String[] parts = lines[0].split(" ");
                method = parts.length > 0 ? parts[0].toUpperCase(Locale.US) : "";
                path = parts.length > 1 ? parts[1] : "/";
                int q = path.indexOf('?');
                if (q >= 0) path = path.substring(0, q);

                Map<String, String> h = new HashMap<String, String>();
                for (int i = 1; i < lines.length; i++) {
                    int idx = lines[i].indexOf(':');
                    if (idx > 0) {
                        h.put(lines[i].substring(0, idx).trim().toLowerCase(Locale.US),
                                lines[i].substring(idx + 1).trim());
                    }
                }
                hdrs = h;

                // Expect: 100-continue —— 必须先回 100，否则严格客户端会一直等
                String expect = h.get("expect");
                if (expect != null && expect.toLowerCase(Locale.US).indexOf("100-continue") >= 0) {
                    try {
                        OutputStream os0 = socket.getOutputStream();
                        os0.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(LATIN1));
                        os0.flush();
                    } catch (Exception ignore) { /* ignore */ }
                }

                byte[] body = readBody(in, h);

                boolean isMcpPost = path.equals(PATH_MCP) || path.equals(PATH_MCP + "/")
                        || path.equals("/");
                boolean isMcpGet = path.equals(PATH_MCP) || path.equals(PATH_MCP + "/");
                String accept = h.get("accept");
                // 兼容策略：只有当客户端**明确只要** event-stream 时才回 SSE；
                // 若同时接受 application/json（真实客户端最常见的写法），优先回 JSON，最稳。
                boolean wantSse = accept != null
                        && accept.indexOf("text/event-stream") >= 0
                        && accept.indexOf("application/json") < 0;

                if ("OPTIONS".equals(method)) {
                    code = 204;
                    extra = "cors-preflight";
                    writeResponse(null, null);
                } else if ("DELETE".equals(method) && isMcpGet) {
                    code = 200;
                    extra = "session-delete";
                    writeResponse("{\"jsonrpc\":\"2.0\",\"id\":null,\"result\":{\"closed\":true}}",
                            "application/json; charset=utf-8");
                } else if ("POST".equals(method) && isMcpPost) {
                    String rpc = handleRpc(new String(body, UTF8));
                    if (rpc == null) {
                        code = 202;
                        extra = "notify";
                        writeResponse(null, null);
                    } else if (wantSse) {
                        code = 200;
                        extra = "sse";
                        writeResponse("event: message\ndata: " + rpc + "\n\n",
                                "text/event-stream; charset=utf-8");
                    } else {
                        code = 200;
                        extra = "json";
                        writeResponse(rpc, "application/json; charset=utf-8");
                    }
                } else if ("GET".equals(method) && isMcpGet) {
                    code = 200;
                    extra = "sse-stream";
                    writeSseStream();
                } else if ("GET".equals(method) || "HEAD".equals(method)) {
                    code = 200;
                    extra = "status";
                    writeResponse("HEAD".equals(method) ? null : statusJson(),
                            "application/json; charset=utf-8");
                } else {
                    code = 404;
                    writeResponse("{\"error\":\"not found\",\"hint\":\"MCP 端点为 POST " + PATH_MCP + "\"}",
                            "application/json; charset=utf-8");
                }
            } catch (Exception e) {
                Log.e(TAG, "handler error", e);
                code = 500;
                try {
                    writeResponse("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,"
                                    + "\"message\":\"internal error: " + esc(e.toString()) + "\"}}",
                            "application/json; charset=utf-8");
                } catch (Exception ignore) { /* ignore */ }
                try { log().line(AnalysisLog.SERVER, "!! handler 异常: " + e); } catch (Exception ignore) { /* ignore */ }
            } finally {
                try { logHit(method, path, code, respLen, hdrs, extra, t0); } catch (Exception ignore) { /* ignore */ }
                closeQuietly(socket);
            }
        }

        private void writeResponse(String body, String contentType) throws IOException {
            byte[] rb = body == null ? new byte[0] : body.getBytes(UTF8);
            respLen = rb.length;
            OutputStream os = socket.getOutputStream();
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(code).append(statusText(code)).append("\r\n");
            if (contentType != null && rb.length > 0) {
                h.append("Content-Type: ").append(contentType).append("\r\n");
            }
            h.append("Content-Length: ").append(rb.length).append("\r\n");
            h.append("Mcp-Session-Id: ").append(sessionId).append("\r\n");
            if (code == 405 || code == 204 || code == 404) {
                h.append("Allow: POST, GET, OPTIONS, DELETE\r\n");
            }
            h.append("Access-Control-Allow-Origin: *\r\n");
            h.append("Access-Control-Allow-Methods: POST, GET, OPTIONS, DELETE\r\n");
            h.append("Access-Control-Allow-Headers: Content-Type, Mcp-Session-Id, "
                    + "MCP-Protocol-Version, Accept, Authorization, Last-Event-ID\r\n");
            h.append("Access-Control-Expose-Headers: Mcp-Session-Id\r\n");
            h.append("Access-Control-Max-Age: 86400\r\n");
            h.append("Connection: close\r\n\r\n");
            os.write(h.toString().getBytes(LATIN1));
            if (rb.length > 0) os.write(rb);
            os.flush();
        }

        /** GET /mcp：SSE 端点事件 + 保活（兼容旧式 HTTP+SSE 传输） */
        private void writeSseStream() {
            OutputStream os = null;
            try {
                os = socket.getOutputStream();
                StringBuilder hz = new StringBuilder();
                hz.append("HTTP/1.1 200 OK\r\n");
                hz.append("Content-Type: text/event-stream; charset=utf-8\r\n");
                hz.append("Cache-Control: no-cache\r\n");
                hz.append("Mcp-Session-Id: ").append(sessionId).append("\r\n");
                hz.append("Access-Control-Allow-Origin: *\r\n");
                hz.append("Connection: close\r\n\r\n");
                os.write(hz.toString().getBytes(LATIN1));
                os.write(("event: endpoint\ndata: " + PATH_MCP + "?sessionId=" + sessionId
                        + "\n\n").getBytes(UTF8));
                os.write(": keepalive\n\n".getBytes(UTF8));
                os.flush();
                for (int i = 0; i < 12; i++) {
                    Thread.sleep(5000);
                    os.write(": keepalive\n\n".getBytes(UTF8));
                    os.flush();
                }
            } catch (Exception e) {
                // 客户端主动断开属正常
            } finally {
                closeQuietly(os);
            }
        }
    }

    private static String statusText(int code) {
        if (code == 200) return " OK";
        if (code == 202) return " Accepted";
        if (code == 204) return " No Content";
        if (code == 400) return " Bad Request";
        if (code == 404) return " Not Found";
        if (code == 405) return " Method Not Allowed";
        if (code == 406) return " Not Acceptable";
        if (code == 500) return " Internal Server Error";
        return " OK";
    }

    private static void closeQuietly(Socket s) {
        if (s != null) { try { s.close(); } catch (Exception e) { /* ignore */ } }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) { try { c.close(); } catch (Exception e) { /* ignore */ } }
    }

    // ---- 原始请求解析（按字节） ----

    /** 读到 \r\n\r\n 为止（含） */
    private static byte[] readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(1024);
        int matched = 0;
        int v;
        while ((v = in.read()) != -1) {
            b.write(v);
            if (v == CRLFCRLF[matched]) {
                matched++;
                if (matched == 4) return b.toByteArray();
            } else {
                matched = (v == CRLFCRLF[0]) ? 1 : 0;
            }
            if (b.size() > 64 * 1024) return b.toByteArray();
        }
        return b.size() > 0 ? b.toByteArray() : null;
    }

    /** 读 body：优先 chunked，其次 content-length；两者都没有则视为无 body */
    private static byte[] readBody(InputStream in, Map<String, String> h) throws IOException {
        String te = h.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.US).indexOf("chunked") >= 0) {
            return readChunked(in);
        }
        String cl = h.get("content-length");
        if (cl == null) return new byte[0];
        int n;
        try { n = Integer.parseInt(cl.trim()); } catch (Exception e) { return new byte[0]; }
        if (n <= 0) return new byte[0];
        if (n > 32 * 1024 * 1024) n = 32 * 1024 * 1024;
        byte[] buf = new byte[n];
        int total = 0, r;
        while (total < n && (r = in.read(buf, total, n - total)) != -1) total += r;
        if (total == n) return buf;
        byte[] cut = new byte[total];
        System.arraycopy(buf, 0, cut, 0, total);
        return cut;
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String line = readAsciiLine(in);
            if (line == null) break;
            int semi = line.indexOf(';');
            if (semi >= 0) line = line.substring(0, semi);
            int size;
            try { size = Integer.parseInt(line.trim(), 16); } catch (Exception e) { break; }
            if (size <= 0) {
                while (true) {
                    String t = readAsciiLine(in);
                    if (t == null || t.isEmpty()) break;
                }
                break;
            }
            byte[] buf = new byte[size];
            int total = 0, r;
            while (total < size && (r = in.read(buf, total, size - total)) != -1) total += r;
            if (total < size) break;
            out.write(buf, 0, size);
            readAsciiLine(in); // 尾随 CRLF
            if (out.size() > 32 * 1024 * 1024) break;
        }
        return out.toByteArray();
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(64);
        int v = -1;
        while ((v = in.read()) != -1) {
            if (v == '\n') break;
            if (v != '\r') b.write(v);
            if (b.size() > 8192) break;
        }
        if (v == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), LATIN1);
    }

    private void logHit(String method, String path, int code, int len,
                        Map<String, String> h, String extra, long t0) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(ts).append("] ").append(code).append(" ")
                .append(method).append(" ").append(path);
        sb.append("  ").append(len).append("B  ").append(System.currentTimeMillis() - t0).append("ms");
        if (extra != null && extra.length() > 0) sb.append("  ").append(extra);
        if (h != null) {
            appendKv(sb, "ct", h.get("content-type"), 70);
            appendKv(sb, "cl", h.get("content-length"), 20);
            appendKv(sb, "te", h.get("transfer-encoding"), 20);
            appendKv(sb, "pv", h.get("mcp-protocol-version"), 20);
            String sid = h.get("mcp-session-id");
            if (sid != null) appendKv(sb, "sid", shortId(sid), 16);
            appendKv(sb, "accept", h.get("accept"), 60);
            appendKv(sb, "ua", h.get("user-agent"), 45);
        }
        log().line(AnalysisLog.SERVER, sb.toString());
    }

    private static void appendKv(StringBuilder sb, String k, String v, int max) {
        if (v == null) return;
        sb.append("  | ").append(k).append("=").append(v.length() > max ? v.substring(0, max) : v);
    }

    private static String shortId(String s) {
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private String statusJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":\"ok\",\"service\":\"RevMCP\",\"version\":\"").append(VERSION)
                .append("\",\"mode\":\"analysis-only\",\"port\":").append(port)
                .append(",\"endpoint\":\"").append(getEndpoint())
                .append("\",\"tools\":").append(toolCount())
                .append(",\"log_dir\":\"").append(esc(String.valueOf(log().dir())))
                .append("\",\"analysis_log\":\"").append(esc(log().path(AnalysisLog.ANALYSIS)))
                .append("\",\"server_log\":\"").append(esc(log().path(AnalysisLog.SERVER)))
                .append("\"}");
        return sb.toString();
    }

    // ================= JSON-RPC =================

    private String handleRpc(String body) throws Exception {
        try {
            if (body == null || body.trim().isEmpty()) {
                return rpcError(JSONObject.NULL,
                        new JSONObject().put("code", -32700).put("message", "parse error: empty body"));
            }
            JSONObject req = new JSONObject(body);
            String method = req.optString("method", "");
            Object id = req.has("id") ? req.get("id") : JSONObject.NULL;

            if (method.equals("initialize")) {
                JSONObject params = req.optJSONObject("params");
                String clientVer = params == null ? "" : params.optString("protocolVersion", "");
                String ver = SUPPORTED_PROTOCOLS.contains(clientVer) ? clientVer : DEFAULT_PROTOCOL;

                JSONObject caps = new JSONObject();
                caps.put("tools", new JSONObject().put("listChanged", false));

                JSONObject res = new JSONObject();
                res.put("protocolVersion", ver);
                res.put("capabilities", caps);
                res.put("serverInfo", new JSONObject()
                        .put("name", "RevMCP")
                        .put("version", VERSION)
                        .put("title", "RevMCP 手机端逆向分析服务（只读）"));
                res.put("instructions",
                        "手机端只读分析服务。用于逆向分析 APK / Flutter App。\n"
                        + "\n【完整分析流程】\n"
                        + "1. analyze_file {\"path\":\"<apk路径>\",\"deep\":true}\n"
                        + "   → 自动做：包级画像 + radare2 原生库分析 + Flutter 符号还原（blutter），全部写入日志\n"
                        + "2. read_log {\"lines\":80}  → 读末尾分析摘要\n"
                        + "3. read_pp_summary → 解析最近一次 blutter_run 的 pp.txt 结构摘要\n"
                        + "4. dart_symbols {\"pkg\":\"业务包名\",\"q\":\"关键字\"} → 查地址↔源位置映射\n"
                        + "5. list_workdirs → 列出所有分析产物项目；list_artifacts → 看某个项目的产物文件\n"
                        + "\n【Flutter 专项】\n"
                        + "- blutter_doctor → 检查内置 blutter 版本与依赖状态\n"
                        + "- flutter_probe {\"apk_path\":\"...\"} → 探测 Dart 版本/snapshot hash\n"
                        + "- analyze_so {\"so_path\":\"...\",\"deep\":false} → 单 .so 分析（更快）\n"
                        + "\n【只读原则】本服务不修改任何文件。分析结论拿到后，"
                        + "用其他工具（如 MT 管理器）完成 .so 替换/打补丁/重打包。\n"
                        + "\n【日志阅读】所有工具结果均写入 analysis.log；用 read_log 按 offset/max_bytes 分页读全文。\n"
                        + "\n路径请使用手机上的绝对路径（如 /sdcard/Download/app.apk）。");
                return rpcResult(id, res);
            }
            if (method.startsWith("notifications/")) {
                return null; // 通知：无响应体（按 202 处理）
            }
            if (method.equals("ping")) {
                return rpcResult(id, new JSONObject());
            }
            if (method.equals("tools/list")) {
                return rpcResult(id, new JSONObject().put("tools", buildTools()));
            }
            if (method.equals("tools/call")) {
                JSONObject params = req.optJSONObject("params");
                String name = params == null ? "" : params.optString("name", "");
                JSONObject args = params == null ? null : params.optJSONObject("arguments");
                if (args == null) args = new JSONObject();
                try {
                    String out = dispatch(name, args);
                    return rpcResult(id, new JSONObject()
                            .put("content", new JSONArray().put(
                                    new JSONObject().put("type", "text").put("text", out == null ? "" : out)))
                            .put("isError", false));
                } catch (Exception e) {
                    // 绝不允许再出现光秃秃的 "null"：
                    // 很多 JDK/Android 异常（如 NonWritableChannelException）getMessage() 返回 null，
                    // 这里统一补上异常类名 + 堆栈首帧，保证任何失败都能定位。
                    String err = "[" + name + " 执行失败] " + describeError(e);
                    Log.e(TAG, err, e);
                    try { log().line(AnalysisLog.SERVER, "!! 工具 " + name + " 失败: " + err); } catch (Exception ignore) { /* ignore */ }
                    return rpcResult(id, new JSONObject()
                            .put("content", new JSONArray().put(
                                    new JSONObject().put("type", "text").put("text", err)))
                            .put("isError", true));
                }
            }
            JSONObject err = new JSONObject().put("code", -32601).put("message", "method not found: " + method);
            return rpcError(id, err);
        } catch (Exception e) {
            Log.e(TAG, "rpc parse error", e);
            return rpcError(JSONObject.NULL,
                    new JSONObject().put("code", -32700).put("message", "parse error: " + e.getMessage()));
        }
    }

    private String rpcResult(Object id, JSONObject result) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("jsonrpc", "2.0");
        o.put("id", id == JSONObject.NULL ? JSONObject.NULL : id);
        o.put("result", result);
        return o.toString();
    }

    private String rpcError(Object id, JSONObject error) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("jsonrpc", "2.0");
        o.put("id", id == JSONObject.NULL ? JSONObject.NULL : id);
        o.put("error", error);
        return o.toString();
    }

    private int toolCount() {
        try { return buildTools().length(); } catch (Exception e) { return -1; }
    }

    /** 文件名安全化（用作工作目录名） */
    private static String nameOf(String n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') sb.append(c);
            else sb.append('_');
        }
        return sb.length() == 0 ? "target" : sb.toString();
    }

    /** 找 APK 内某个叶子名的 lib 条目，优先 arm64-v8a */
    private static String pickLibEntry(String apkPath, String leaf) throws Exception {
        String fallback = null;
        for (String n : ApkPack.entryNames(apkPath)) {
            if (!n.startsWith("lib/") || !n.endsWith("/" + leaf)) continue;
            if (n.contains("arm64")) return n;
            if (fallback == null) fallback = n;
        }
        return fallback;
    }

    // ================= 工具分发（只读） =================

    /**
     * 把异常格式化成"绝不为 null、能定位"的错误串。
     * 很多 JDK/Android 异常（NonWritableChannelException、NullPointerException 等）
     * 的 getMessage() 返回 null，直接拼进错误信息就只剩一个光秃秃的 "null"，
     * 客户端完全无法定位。这里统一补上：异常类名 + cause + 堆栈前 3 帧。
     */
    private static String describeError(Throwable e) {
        if (e == null) return "(未知错误：异常对象为 null)";
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName());
        String m = e.getMessage();
        if (m != null && m.length() > 0) sb.append(": ").append(m);
        else sb.append(": (该异常没有 message —— 仅看类名定位)");

        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            sb.append("\n  原因: ").append(cause.getClass().getName());
            String cm = cause.getMessage();
            if (cm != null && cm.length() > 0) sb.append(": ").append(cm);
        }
        StackTraceElement[] st = e.getStackTrace();
        if (st != null && st.length > 0) {
            sb.append("\n  位置:");
            for (int i = 0; i < st.length && i < 3; i++) {
                sb.append("\n    ").append(st[i].toString());
            }
        }
        return sb.toString();
    }

    /**
     * 工具分发入口。只读类工具先过 {@link #readGate} 串行闸门（见字段注释），
     * 其余直通 {@link #dispatchInner}。
     */
    private String dispatch(String name, JSONObject a) throws Exception {
        if (!GATED_READS.contains(name)) return dispatchInner(name, a);
        boolean got;
        try {
            got = readGate.tryAcquire(READ_GATE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "!! 读请求被中断";
        }
        if (!got) {
            return "⏳ 【读队列繁忙】" + name + " 等待超过 " + (READ_GATE_WAIT_MS / 1000) + " 秒仍未轮到。\n"
                    + "RevMCP 的读类工具（list_artifacts / read_artifact / dart_symbols / "
                    + "read_so_bytes / read_pp_summary / list_workdirs）在服务端**串行排队**：\n"
                    + "并发发多个读请求只会互相拖慢、最后一起超时，所以这里让它们排队。\n\n"
                    + "怎么办：\n"
                    + "  1) 等几秒重试（前面的读通常 1 秒内完成）；\n"
                    + "  2) 别一次性并发发多个读请求，逐个调用；\n"
                    + "  3) 缩小扫描范围：list_artifacts 传 dir / under / suffix 缩小目录，\n"
                    + "     read_artifact 传 offset+max_bytes 分段读，不要整读大文件。\n"
                    + "  另：service_status（不受闸门影响）能秒回，可用它确认服务本身是活的。";
        }
        try {
            return dispatchInner(name, a);
        } finally {
            readGate.release();
        }
    }

    private String dispatchInner(String name, JSONObject a) throws Exception {
        // ---------- 健康探针：永远秒回，用来区分"服务死了"还是"某个重活慢" ----------
        if (name.equals("service_status")) {
            StringBuilder sb = new StringBuilder();
            sb.append("■ RevMCP 服务状态\n");
            sb.append("  版本     : ").append(VERSION).append("\n");
            sb.append("  端口     : ").append(port)
              .append("（").append(running ? "✅ 监听中" : "❌ 未监听").append("）\n");
            sb.append("  radare2  : ").append(runtimeReady ? "✅ 已就绪" : "⏳ " + runtimePhase).append("\n");
            if (runtimeError != null) sb.append("  运行时错误: ").append(runtimeError).append("\n");
            sb.append(RevPaths.describe());
            sb.append("  日志目录 : ").append(log().dir().getAbsolutePath()).append("\n");
            sb.append("  日志大小 : analysis ")
              .append(BlutterRunner.fmtSize(log().size(AnalysisLog.ANALYSIS)))
              .append(" / server ")
              .append(BlutterRunner.fmtSize(log().size(AnalysisLog.SERVER))).append("\n");
            sb.append("  后台任务 : ").append(jobs.size()).append(" 个")
              .append("（重活并发上限 ").append(MAX_HEAVY_JOBS).append("，")
              .append("已占用 ").append(MAX_HEAVY_JOBS - heavySem.availablePermits()).append("）\n");
            sb.append("  读队列   : ")
              .append(readGate.availablePermits() == 0 ? "⏳ 有读请求在跑（其他读会排队）" : "空闲")
              .append("（读类工具服务端串行，避免并发互相拖慢导致一起超时）\n");
            sb.append("  写操作   : patch_so（VA / 批量 patches / peek 预演）/ patch_file / "
                    + "replace_entry（默认带签名）/ sign_apk / so_map_addr\n");
            sb.append("  签名能力 : ").append("内置 debug keystore（assets/" + ApkSign.ASSET_NAME
                    + "，口令 android，别名 revmcp）\n");
            sb.append("  文件访问 : ").append(StoragePerm.brief(ctx)).append("\n");
            File inDir = inputDir();
            sb.append("  input 缓存: ").append(inDir.getAbsolutePath()).append("\n");
            File[] cached = inDir.listFiles();
            if (cached != null && cached.length > 0) {
                sb.append("    ").append(cached.length).append(" 个文件：\n");
                for (int i = 0; i < cached.length && i < 10; i++) {
                    sb.append("      ").append(cached[i].getName())
                      .append("  ").append(BlutterRunner.fmtSize(cached[i].length())).append("\n");
                }
            } else {
                sb.append("    （空 —— 宿主路径读不到时，用 App 里的「选择 APK / .SO 文件分析」走系统选择器）\n");
            }
            sb.append("\n本工具不碰任何重活：能秒回就说明服务活着。\n");
            sb.append("若它超时 → 进程/端口层面问题；若它秒回但别的工具超时 → 是那个工具的重活慢，用 job_* 异步化。");
            return sb.toString();
        }

        // ---------- 异步任务查询（永远秒回，不受重活影响） ----------
        if (name.equals("job_status") || name.equals("job_result")) {
            Job j = findJob(a);
            if (j == null) {
                return "!! 找不到任务 " + a.optString("job_id", "(未给 job_id)")
                        + "\n用 job_list 看当前有哪些任务";
            }
            return jobStatusText(j, name.equals("job_result"));
        }
        if (name.equals("job_list")) {
            if (jobs.isEmpty()) return "（当前没有后台任务）";
            List<Job> all = new ArrayList<Job>(jobs.values());
            Collections.sort(all, new Comparator<Job>() {
                public int compare(Job x, Job y) {
                    return x.submitMs < y.submitMs ? -1 : (x.submitMs == y.submitMs ? 0 : 1);
                }
            });
            StringBuilder sb = new StringBuilder();
            sb.append("■ 后台任务（最近 ").append(all.size()).append(" 个）\n\n");
            for (Job j : all) {
                sb.append("  ").append(j.id).append("  ")
                  .append("DONE".equals(j.state) ? "✅" : "FAILED".equals(j.state) ? "❌" : "⏳")
                  .append("  ").append(j.tool)
                  .append("  ").append(j.elapsedMs() / 1000).append("s\n")
                  .append("     ").append(j.target).append("\n");
            }
            sb.append("\n用 job_result {\"job_id\":\"...\"} 取完整结果");
            return sb.toString();
        }

        // ---------- 路径工具：宿主路径 → RevMCP 缓存目录 ----------
        if (name.equals("copy_to_input")) {
            return toolCopyToInput(a);
        }
        if (name.equals("list_copied_inputs")) {
            return toolListCopiedInputs(a);
        }

        // ---------- radare2 分析 ----------
        // 冷启动/重装后 radare2 运行时在后台解压，未就绪时明确告知而不是挂到超时。
        // 但**必须先查必填参数**：客户端传空参数时要看到"缺少参数: path"，
        // 而不是一段"运行时正在准备"的说明 —— 否则会被误判成工具坏了。
        if (name.startsWith("r2_") && !name.equals("r2_doctor")) {
            String miss = missingParamOf(name, a);
            if (miss != null) throw new IllegalArgumentException("缺少参数: " + miss);
            String rt = guardRuntime();
            if (rt != null) return rt;
        }
        // 先把"容器文件直接交给 r2"挡掉：r2 会把 .apk/.zip 当裸二进制做代码分析，
        // 几十 MB 的包在手机上要几分钟甚至卡死 —— 这正是 r2_* 工具在客户端侧超时的根因。
        if (name.startsWith("r2_") && !name.equals("r2_doctor")) {
            String guard = guardR2Target(a.optString("path", ""));
            if (guard != null) return guard;
            // 大文件预检：>8MB 的 so 上 r2_functions/decompile 在手机上几乎必然超时，
            // 直接引导用 blutter/dart_symbols 替代
            String sizeGuard = guardR2Size(a.optString("path", ""), name, a);
            if (sizeGuard != null) return sizeGuard;
        }
        if (name.equals("r2_doctor")) {
            StringBuilder sb = new StringBuilder();
            sb.append("■ 运行时状态\n");
            sb.append("  radare2 : ").append(runtimeReady ? "✅ 已就绪" : "⏳ " + runtimePhase).append("\n");
            if (runtimeError != null) sb.append("  错误    : ").append(runtimeError).append("\n");
            sb.append("  服务    : ").append(running ? "✅ 端口已在监听" : "❌ 未运行").append("\n");
            sb.append("  后台任务: ").append(jobs.size()).append(" 个\n");
            sb.append("\n");
            if (runtimeReady) {
                sb.append(r2().selftest());
            } else {
                sb.append("radare2 运行时还在后台解压（冷启动/重装后首次较慢），selftest 暂不可用。\n");
                sb.append("不依赖 radare2 的工具现在就能用：analyze_file（包级画像）、dart_symbols、\n");
                sb.append("read_log、list_artifacts、copy_to_input 等。");
            }
            return sb.toString();
        }
        if (name.equals("r2_info")) {
            String p = req(a, "path");
            return logged("r2_info", p, r2().r2(p, new String[]{"iI", "ie", "il"}, 180));
        }
        if (name.equals("r2_functions")) {
            String p = req(a, "path");
            String f = a.optString("filter", "");
            return logged("r2_functions", p + (f.isEmpty() ? "" : " ~ " + f),
                    r2().r2(p, new String[]{"afl" + (f.isEmpty() ? "" : "~" + f)}, 240));
        }
        if (name.equals("r2_disasm")) {
            String p = req(a, "path");
            String addr = req(a, "addr");
            int n = a.optInt("n", 64);
            return logged("r2_disasm", p + " @ " + addr,
                    r2().r2(p, new String[]{"pd " + n + " @ " + addr}, 180));
        }
        if (name.equals("r2_decompile")) {
            String p = req(a, "path");
            String addr = req(a, "addr");
            return logged("r2_decompile", p + " @ " + addr,
                    r2().r2(p, new String[]{"pdc @ " + addr}, 240));
        }
        if (name.equals("r2_strings")) {
            String p = req(a, "path");
            String f = a.optString("filter", "");
            return logged("r2_strings", p + (f.isEmpty() ? "" : " ~ " + f),
                    r2().r2(p, new String[]{"izz" + (f.isEmpty() ? "" : "~" + f)}, 180));
        }
        if (name.equals("r2_search")) {
            String p = req(a, "path");
            String pat = req(a, "pattern");
            String type = a.optString("type", "string");
            String cmd;
            if (type.equals("hex")) cmd = "/x " + pat;
            else if (type.equals("asm")) cmd = "/a " + pat;
            else cmd = "izz~" + pat;
            return logged("r2_search", p + " [" + type + "] " + pat,
                    r2().r2(p, new String[]{cmd}, 180));
        }
        if (name.equals("r2_symbols")) {
            String p = req(a, "path");
            return logged("r2_symbols", p, r2().r2(p, new String[]{"is"}, 180));
        }
        if (name.equals("r2_cmd")) {
            String p = req(a, "path");
            String cmd = req(a, "cmd");
            return logged("r2_cmd", p + " :: " + cmd, r2().r2(p, new String[]{cmd}, 240));
        }

        // ---------- APK / Flutter 结构分析 ----------
        if (name.equals("list_apk_entries")) {
            String p = req(a, "apk_path");
            String f = a.optString("filter", "");
            String out = ApkPack.listEntries(p);
            if (!f.isEmpty()) out = filterLines(out, f);
            return logged("list_apk_entries", p, out);
        }
        if (name.equals("read_manifest")) {
            String p = req(a, "apk_path");
            byte[] axml = p.toLowerCase(Locale.US).endsWith(".xml")
                    ? ApkPack.readFile(new File(p))
                    : ApkPack.readEntry(p, "AndroidManifest.xml");
            return logged("read_manifest", p, AxmReader.decodeForOutput(axml));
        }
        if (name.equals("verify_apk")) {
            String p = req(a, "apk_path");
            return logged("verify_apk", p, ApkPack.verifyApk(p));
        }
        if (name.equals("extract_entry")) {
            String p = req(a, "apk_path");
            String e = req(a, "entry_name");
            // out_dir 可选：不传时默认落到 <产物根>/work/<包名>/patch/，
            // 和 analyze_file 的产物在同一棵树下，省得每次自己拼路径。
            String d = a.optString("out_dir", "").trim();
            if (d.length() == 0) {
                d = new File(new File(workDir(), nameOf(new File(p).getName())), "patch").getAbsolutePath();
            }
            String f = ApkPack.extractEntry(p, e, d);
            allowRoot(new File(d));   // 导出到哪儿，就允许从哪儿读回
            String msg = "已导出: " + f
                    + "\n（这是**磁盘上的普通文件**，可以随便 patch；"
                    + "要放回 APK 内请用 replace_entry，参数是 entry=\"" + e + "\"）";
            if (BinaryPatch.isSoLib(e)) {
                msg += "\n⚠ 这是 lib/*.so：patch 完记得用 replace_entry 写回，"
                        + "工具会保留原压缩方式并保持 STORED 条目的 4096 对齐。";
            }
            return logged("extract_entry", p + " :: " + e, msg);
        }
        if (name.equals("unpack_apk_libs")) {
            String p = req(a, "apk_path");
            String d = req(a, "out_dir");
            allowRoot(new File(d));   // 解到哪儿，就允许从哪儿读回
            return logged("unpack_apk_libs", p, ApkPack.unpackLibs(p, d));
        }
        // ---------- Blutter 符号还原（预编译 blutter，从 nativeLibraryDir 执行） ----------
        if (name.equals("blutter_doctor")) {
            BlutterRunner br = blutter();
            if (br == null) return "!! 桌面环境（无 Context），无法检查 blutter 运行时";
            return logged("blutter_doctor", "(自检)", br.doctor());
        }
        if (name.equals("blutter_run")) {
            BlutterRunner br0 = blutter();
            if (br0 == null) return "!! 桌面环境（无 Context），无法执行 blutter";
            String apkPath0 = a.optString("apk_path", "");
            String soPath0 = a.optString("so_path", "");
            if (apkPath0.isEmpty() && soPath0.isEmpty()) {
                return "!! 需要 apk_path（Flutter APK）或 so_path（libapp.so）";
            }
            // blutter 是 30-60 秒的重活：默认后台跑，提交即返回 job_id，
            // 不让客户端 socket 一直挂到超时。传 async=false 可退回同步。
            if (!"false".equalsIgnoreCase(a.optString("async", "true"))) {
                final JSONObject aa = a;
                Job jb = submitJob("blutter_run", apkPath0.isEmpty() ? soPath0 : apkPath0,
                        new Callable<String>() {
                            public String call() throws Exception { return runBlutterRun(aa); }
                        });
                return jobAccepted(jb, "blutter 通常 30-60 秒，后台跑完后用 job_result 取"
                        + "符号索引路径，再用 dart_symbols(pp_dir=...) 查符号。");
            }
            return runBlutterRun(a);
        }

        if (name.equals("analyze_file")) {
            final String p = req(a, "path");
            final boolean withNative = !"false".equalsIgnoreCase(a.optString("native", "true"));
            final boolean deep = "true".equalsIgnoreCase(a.optString("deep", "false"));
            final String[] extra = csv(a.optString("extra_libs", ""));
            final File logsDir = log().dir();
            final File work = workDir();

            // 默认异步：这类重活 30-60s，同步执行会让客户端 socket 一直挂到超时。
            // 提交后立即返回 jobId，客户端用 job_result 轮询取结果。
            boolean async = !"false".equalsIgnoreCase(a.optString("async", "true"));
            if (!async) {
                return runAnalyzeFile(p, work, logsDir, withNative, deep, extra);
            }
            Job j = submitJob("analyze_file", p, new Callable<String>() {
                public String call() throws Exception {
                    return runAnalyzeFile(p, work, logsDir, withNative, deep, extra);
                }
            });
            return jobAccepted(j,
                    "注意：若目标是宿主目录（如 /storage/emulated/0/MT2/…）下读不到的 APK，"
                            + "analyze_file 会报 EACCES —— 请先 copy_to_input 拿到缓存副本路径再重试。");
        }

        if (name.equals("analyze_so")) {
            String p = req(a, "so_path");
            boolean deep = "true".equalsIgnoreCase(a.optString("deep", "false"));
            NativeAnalyzer.Runner runner = ctx == null ? null : R2Bridge.runner(r2());
            String out = NativeAnalyzer.analyzeSo(runner, new File(p), deep, null);
            // 写入日志并返回区间
            AnalysisLog.Block blk = log().append(AnalysisLog.ANALYSIS,
                    "analyze_so " + p, out);
            StringBuilder sb = new StringBuilder();
            sb.append("=== analyze_so 完成 ===\n文件: ").append(p).append("\n深度 : ").append(deep).append("\n");
            sb.append("--- 摘要 ---\n").append(out).append("\n");
            sb.append("--- 完整内容区间（用 read_log 分页读） ---\n");
            appendBlock(sb, "分析结果", blk);
            return sb.toString();
        }

        // ---------- 产物目录浏览与符号摘要 ----------
        if (name.equals("list_workdirs")) {
            File w = workDir();
            if (!w.isDirectory()) return "（还没有任何分析产物）工作目录不存在：" + w.getAbsolutePath();
            final File ww = w;
            final String[] out = {null};
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        File[] dirs = ww.listFiles();
                        if (dirs == null || dirs.length == 0) {
                            out[0] = "工作目录存在但为空：" + ww.getAbsolutePath()
                                    + "\n请先跑 analyze_file 或 blutter_run 生成产物";
                        } else {
                            StringBuilder x = new StringBuilder();
                            x.append("■ 分析产物目录  ").append(ww.getAbsolutePath()).append("\n")
                              .append("  共 ").append(dirs.length).append(" 个项目\n\n");
                            for (File d : dirs) {
                                x.append("  ").append(d.getName()).append("\n")
                                  .append("    路径 : ").append(d.getAbsolutePath()).append("\n")
                                  .append("    时间 : ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                                          .format(new java.util.Date(d.lastModified()))).append("\n");
                            }
                            x.append("\n用法: 进入某个项目后\n");
                            x.append("  list_artifacts {\"dir\":\"/path/to/…\"} 浏览产物\n");
                            x.append("  read_artifact {\"path\":\"...\"} 读具体文件\n");
                            x.append("  dart_symbols {\"pp_dir\":\".../blutter_out\",\"q\":\"关键字\"} 查符号\n");
                            out[0] = x.toString();
                        }
                    } catch (Exception e) { out[0] = "!! 扫描工作目录失败: " + e; }
                }
            }, "list-workdirs");
            t.setDaemon(true);
            t.start();
            t.join(5000);
            if (t.isAlive()) {
                return "⚠ list_workdirs 扫描超时（5s），工作目录文件较多。\n"
                        + "   请直接用 blutter_run 返回的 out_dir 路径调 dart_symbols（pp_dir 参数），不必等待列表";
            }
            return out[0] != null ? out[0] : "（扫描结果为空）";
        }

        if (name.equals("list_workdir_artifacts")) {
            return toolListArtifacts(a);
        }

        if (name.equals("read_pp_summary")) {
            // 支持直接指定 pp.txt 路径（最高优先级），避免全量扫描
            String ppFile = a.optString("pp_file", "");
            String ppDir = a.optString("pp_dir", "");
            File best;
            if (ppFile.length() > 0) {
                best = new File(ppFile);
                if (!best.isFile()) return "!! 找不到文件: " + ppFile;
            } else if (ppDir.length() > 0) {
                best = new File(ppDir, "pp.txt");
                if (!best.isFile()) {
                    // 兼容 relaxed 子目录
                    best = new File(new File(ppDir, "relaxed"), "pp.txt");
                    if (!best.isFile()) return "!! 找不到 pp.txt: " + ppDir;
                }
            } else {
                File w = workDir();
                List<File> candidates = new ArrayList<File>();
                if (w.isDirectory()) collectFiles(w, candidates, 4);
                best = null;
                for (File f : candidates) {
                    if (!"pp.txt".equals(f.getName())) continue;
                    if (best == null || f.lastModified() > best.lastModified()) best = f;
                }
                if (best == null) {
                    return "（还没有 pp.txt）\n"
                            + "   先跑 analyze_file 或 blutter_run，成功后在产物目录里会有 pp.txt。\n"
                            + "   也可以用 list_workdirs + list_artifacts 找路径\n"
                            + "   或直接传 pp_dir / pp_file 参数指定路径";
                }
            }
            try {
                PpDigest.Digest d = PpDigest.parse(best);
                StringBuilder sb = new StringBuilder();
                sb.append("■ pp.txt 摘要  ").append(best.getAbsolutePath()).append("\n");
                sb.append("  总字节   : ").append(d.bytes).append("\n");
                sb.append("  总行数   : ").append(d.lines).append("\n");
                sb.append("  识别条目 : ").append(d.recognized).append(" 条\n");
                sb.append("  未识别   : ").append(d.unrecognized).append(" 条\n");
                sb.append("  续行     : ").append(d.continuations).append(" 行\n");
                if (d.topPackage.length() > 0) {
                    sb.append("  业务包   : ").append(d.topPackage)
                      .append("（").append(d.appSymbols.size()).append(" 条符号）\n");
                }
                sb.append("\n--- 类型分布 ---\n");
                int shownK = 0;
                for (Map.Entry<String, Integer> e : d.kinds.entrySet()) {
                    if (shownK++ >= 20) { sb.append("  …（其余省略）\n"); break; }
                    sb.append("  ").append(pad(e.getKey(), 24)).append(e.getValue()).append(" 条\n");
                }
                sb.append("\n--- 业务包符号 Top10 ---\n");
                for (int i = 0; i < Math.min(10, d.appSymbols.size()); i++) {
                    PpDigest.Sym s = d.appSymbols.get(i);
                    sb.append("  ").append(s.addr).append("  ")
                      .append(s.name).append("  ").append(s.pkgPath).append("\n");
                }
                return sb.toString();
            } catch (Exception e) {
                return "!! 解析 pp.txt 失败: " + e;
            }
        }

        // ---------- 日志工具 ----------
        if (name.equals("read_log")) return toolReadLog(a);
        if (name.equals("write_note")) return toolWriteNote(a);

        // ---------- 产物文件（不是日志：read_log 读不到） ----------
        if (name.equals("list_artifacts")) return toolListArtifacts(a);
        if (name.equals("read_artifact")) return toolReadArtifact(a);
        if (name.equals("dart_symbols")) return toolDartSymbols(a);

        // ---------- 写入类工具（谨慎使用，需备份原文件）----------
        if (name.equals("patch_so")) return toolPatchSo(a);
        if (name.equals("so_map_addr")) return toolSoMapAddr(a);
        if (name.equals("patch_file")) return toolPatchFile(a);
        if (name.equals("replace_entry")) return toolReplaceEntry(a);
        if (name.equals("sign_apk")) return toolSignApk(a);
        if (name.equals("read_so_bytes")) return toolReadSoBytes(a);
        throw new IllegalArgumentException("未知工具: " + name);
    }

    /**
     * 挡住"把容器文件（.apk/.zip/.jar/.aar）直接交给 radare2"这种用法。
     * r2 会把整个 ZIP 当裸二进制做代码分析：43MB 的包在手机上要几分钟甚至卡死，
     * 客户端侧表现为 r2_* 全部超时，而日志类工具正常 —— 容易被误判成"8000 端口的子服务没起来"。
     * 实际 radare2 是 ProcessBuilder 拉起的子进程，并不存在第二个服务。
     */
    private String guardR2Target(String path) {
        if (path == null || path.length() == 0) return null;
        String low = path.toLowerCase(Locale.US);
        boolean isContainer = low.endsWith(".apk") || low.endsWith(".zip")
                || low.endsWith(".jar") || low.endsWith(".aar") || low.endsWith(".xapk");
        if (!isContainer) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("【已拦截：radare2 不能直接分析容器文件】\n\n");
        sb.append("目标: ").append(path).append("\n\n");
        sb.append("原因: .apk/.zip 是 ZIP 容器，radare2 会把它当裸机器码解析，\n");
        sb.append("     几十 MB 的包会跑几分钟甚至卡死 → 客户端侧看到的就是 r2_* 超时。\n");
        sb.append("     （radare2 是本服务拉起的子进程，不存在\"第二个 8000 端口服务\"）\n\n");
        sb.append("正确做法（两步，都不需要 r2）：\n");
        sb.append("1) 先看包级画像：\n");
        sb.append("   analyze_file {\"path\":\"").append(path).append("\"}\n");
        sb.append("2) 再把 .so 解出来，然后对 .so 跑 r2：\n");
        sb.append("   unpack_apk_libs {\"apk_path\":\"").append(path).append("\",\"out_dir\":\"<可写目录>\"}\n");
        sb.append("   analyze_so   {\"so_path\":\"<out_dir>/lib/arm64-v8a/libapp.so\"}\n");
        sb.append("   r2_functions {\"path\":\"<上一步解出的 .so>\"}\n\n");
        sb.append("提示: analyze_so / analyze_file 第 1 层是纯 Java ELF 解析，不依赖 radare2，\n");
        sb.append("     即使 r2 在设备上跑不起来也能出结果（段表/符号/依赖/字符串/Dart 线索）。\n");
        sb.append("     想确认 radare2 本身是否能跑，用 r2_doctor。");
        return sb.toString();
    }

    /**
     * 大文件预检：>8MB 的 .so 上 r2_functions / r2_decompile / r2_disasm 在手机上几乎必然超时。
     * 返回引导文案，不返回 null 时调用方应直接返回（不执行 r2）。
     *
     * <p><b>这是「预检」不是「报错」</b>：文件本身没问题，只是手机端跑 radare2 太慢。
     * 想强行跑就在同一次调用里带 {@code "force":"true"} —— 预检让路，
     * 该超时还是会超时，但决定权在调用方。
     */
    private String guardR2Size(String path, String toolName, JSONObject a) {
        if (path == null || path.length() == 0) return null;
        File f = new File(path);
        if (!f.isFile() || f.length() <= R2_SIZE_LIMIT) return null;
        // 统一拦截所有 r2_* 工具（函数/反编译/反汇编/字符串/符号/搜索/任意命令）
        String sn = toolName;
        if (sn.equals("r2_doctor")) return null; // doctor 不分析文件，不受限
        // 显式要求强行跑：跳过预检（调用方自己承担超时风险）
        if (a != null && "true".equalsIgnoreCase(a.optString("force", "false"))) return null;

        // 先判断这是不是 Dart AOT 快照库：如果是，结论只能有一个 —— 走 blutter。
        // 复盘里出现过"连试 3 次 r2_disasm 全被拦"的浪费，所以这里把结论写在最前面，
        // 并明确说"别再试其它 r2_*"，避免再白跑两趟。
        boolean dartAot = looksDartAot(f);

        StringBuilder sb = new StringBuilder();
        sb.append("【预检拦截：文件 ").append(BlutterRunner.fmtSize(f.length()))
          .append("，超过 ").append(BlutterRunner.fmtSize(R2_SIZE_LIMIT)).append(" 阈值】\n");
        sb.append("注意：这不是错误，文件没问题；只是 radare2 在手机上跑大 .so 太慢。\n\n");
        if (dartAot) {
            sb.append("══ 结论先说：这是 **Dart AOT 快照库（Flutter libapp.so）** ══\n");
            sb.append("  r2 对它的价值极低 —— 业务函数名都在 Dart 对象池里，r2 只能给出 sub_xxxx。\n");
            sb.append("  **不要再试其它 r2_* 工具**（r2_functions / r2_decompile / r2_strings /\n");
            sb.append("  r2_search / r2_symbols 都会被同一条预检拦下，纯属白跑一趟）。\n");
            sb.append("  这类目标的正确路径只有一条：\n");
            sb.append("    1) blutter_run  {\"so_path\":\"").append(path).append("\"}\n");
            sb.append("       → 产出 blutter_out/（dart_symbols.tsv + asm/）\n");
            sb.append("    2) dart_symbols {\"pp_dir\":\"<上一步 out_dir>\",\"q\":\"vip|needVip|CloudVip\"}\n");
            sb.append("       → 拿到 地址 ↔ Dart 源文件 ↔ Class::method\n");
            sb.append("    3) read_artifact {\"path\":\"<asm/.../xxx.dart>\",\"addr\":\"0x970b94\"}\n");
            sb.append("       → 按地址看带符号反汇编（addr 锚点 + 上下文行）\n");
            sb.append("    4) read_so_bytes {\"path\":\"...\",\"va\":\"0x970b94\"}\n");
            sb.append("       → 看该地址的原始字节，确认确实是函数入口再动手改\n\n");
            sb.append("  下面这些替代方案对该文件**不适用**，仅供非 Dart 的大 .so 参考：\n");
        } else {
            sb.append("radare2 在 ").append(BlutterRunner.fmtSize(f.length())).append(" 的 .so 上任何操作\n");
            sb.append("（afl/pdc/pd/izz/is//x）在手机端通常需要 3-10 分钟甚至卡死。\n\n");
            sb.append("推荐替代方案（按优先级）：\n");
        }
        sb.append("  1) 如果是 Flutter 的 libapp.so（Dart AOT）：\n");
        sb.append("     blutter_run {\"so_path\":\"").append(path).append("\"}\n");
        sb.append("     跑完用 list_artifacts + read_artifact 读 asm/ 下的带符号反汇编，\n");
        sb.append("     或用 dart_symbols {\"pp_dir\":\"<blutter 返回的 out_dir>\",\"q\":\"needVip\"}\n\n");
        sb.append("  2) 只想看字符串/结构（不超时）：\n");
        sb.append("     analyze_so {\"so_path\":\"").append(path).append("\"} 第 1 层纯 Java 解析，\n");
        sb.append("     会给出字符串与 Dart 线索；或用 MT 管理器的「文本搜索」在手机上直接搜。\n\n");
        sb.append("  3) 确实要在这个文件上跑 r2（接受 3-10 分钟甚至卡死）：\n");
        sb.append("     在同一次调用里加 \"force\":\"true\" 跳过本预检，例如\n");
        sb.append("     ").append(sn).append(" {\"path\":\"").append(path)
          .append("\",\"force\":\"true\", ...}\n\n");
        sb.append("  4) 完整反编译：拉到电脑上用 IDA Pro / Ghidra 处理最省事。");
        return sb.toString();
    }

    /**
     * 这是不是 Dart AOT 快照库（Flutter 的 libapp.so）。
     *
     * <p>判据：文件名像 libapp/libflutter，或 ELF 动态符号里有 Dart 的两个快照符号
     * {@code _kDartVmSnapshotData} / {@code _kDartIsolateSnapshotData}。
     * 只用来把 r2 预检的文案说得更准 —— 判错最差就是多给一段通用建议。
     */
    private boolean looksDartAot(File f) {
        try {
            String n = f.getName().toLowerCase(Locale.US);
            if (n.contains("libapp.so") || n.contains("libflutter.so")) return true;
            ElfReader e = ElfReader.parse(f);
            for (ElfReader.Sym s : e.dynsyms) {
                if (s.name != null && (s.name.indexOf("_kDartVmSnapshotData") >= 0
                        || s.name.indexOf("_kDartIsolateSnapshotData") >= 0)) {
                    return true;
                }
            }
        } catch (Throwable ignore) { /* 判不出来就当普通大文件处理 */ }
        return false;
    }

    /**
     * copy_to_input：把宿主目录（如 MT 管理器 /sdcard/MT2/）的 APK 复制到
     * RevMCP 可写的缓存目录（RevMCP/input/），解决 EACCES 问题。
     */
    private String toolCopyToInput(JSONObject a) throws Exception {
        String src = req(a, "path");
        File srcFile = new File(src);
        if (!srcFile.isFile()) {
            return "!! 找不到源文件: " + src;
        }
        File inDir = inputDir();
        if (!inDir.exists()) inDir.mkdirs();
        File dst = new File(inDir, srcFile.getName());

        // 如果目标已存在且相同，直接提示
        if (dst.isFile() && dst.length() == srcFile.length()) {
            return "⚠ 文件已存在于缓存目录（跳过复制）\n"
                    + "源: " + src + "\n"
                    + "缓存: " + dst.getAbsolutePath()
                    + "\n可直接用 analyze_file {\"path\":\"" + dst.getAbsolutePath() + "\"} 继续。";
        }

        // 大文件走后台：几十 MB 的 APK 在手机上复制要几十秒，同步会顶到客户端超时
        final long size = srcFile.length();
        boolean async = size > 4L * 1024 * 1024
                && !"false".equalsIgnoreCase(a.optString("async", "true"));
        if (async) {
            final File srcF = srcFile;
            Job j = submitJob("copy_to_input", src, new Callable<String>() {
                public String call() throws Exception {
                    return doCopyToInput(srcF, dst);
                }
            });
            return jobAccepted(j, "源大小 " + BlutterRunner.fmtSize(size)
                    + "，后台复制中。完成后用 job_result 取缓存路径，再调 analyze_file。");
        }
        return doCopyToInput(srcFile, dst);
    }

    /** copy_to_input 的实际复制执行体（同步 / 异步共用） */
    private String doCopyToInput(File srcFile, File dst) throws Exception {
        String src = srcFile.getAbsolutePath();
        java.io.FileInputStream fis = null;
        java.io.FileOutputStream fos = null;
        long total = 0;
        try {
            fis = new java.io.FileInputStream(srcFile);
            fos = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
            }
            fos.flush();
        } finally {
            if (fis != null) try { fis.close(); } catch (Exception ignore) { /* ignore */ }
            if (fos != null) try { fos.close(); } catch (Exception ignore) { /* ignore */ }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("✅ 已复制到 RevMCP 缓存目录（可读写）\n");
        sb.append("源文件  : ").append(src).append("\n");
        sb.append("缓存路径: ").append(dst.getAbsolutePath()).append("\n");
        sb.append("大小    : ").append(BlutterRunner.fmtSize(total)).append("\n\n");
        sb.append("后续可用：\n");
        sb.append("  analyze_file {\"path\":\"").append(dst.getAbsolutePath()).append("\"}\n");
        sb.append("  unpack_apk_libs {\"apk_path\":\"").append(dst.getAbsolutePath())
          .append("\",\"out_dir\":\"").append(workDir().getAbsolutePath()).append("\"}\n");
        log().line(AnalysisLog.SERVER, "copy_to_input: " + src + " → " + dst.getAbsolutePath());
        return sb.toString();
    }

    /** 列出已复制到 RevMCP 缓存目录（input/）的文件 */
    private String toolListCopiedInputs(JSONObject a) {
        File inDir = inputDir();
        if (!inDir.isDirectory()) {
            return "（还没有缓存文件）input/ 目录不存在：" + inDir.getAbsolutePath();
        }
        File[] files = inDir.listFiles();
        if (files == null || files.length == 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("input/ 目录为空：").append(inDir.getAbsolutePath()).append("\n");
            // 目录为空时，把 copy_to_input 任务的状态和失败原因一并带出来。
            // 否则宿主路径 EACCES 之类的后台失败就是「静默失败」：
            // 目录一直空，却没有任何工具能说明为什么。
            List<Job> rel = new ArrayList<Job>();
            for (Job j : jobs.values()) {
                if ("copy_to_input".equals(j.tool)) rel.add(j);
            }
            if (!rel.isEmpty()) {
                Collections.sort(rel, new Comparator<Job>() {
                    public int compare(Job x, Job y) {
                        return x.submitMs < y.submitMs ? -1 : (x.submitMs == y.submitMs ? 0 : 1);
                    }
                });
                sb.append("\n■ 相关的 copy_to_input 任务：\n");
                for (Job j : rel) {
                    sb.append("  ").append(j.id).append("  ")
                      .append("DONE".equals(j.state) ? "✅完成" : "FAILED".equals(j.state) ? "❌失败" : "⏳运行中")
                      .append("  ").append(j.target).append("\n");
                    if (j.error != null) {
                        sb.append("     失败原因: ").append(j.error).append("\n");
                        if (j.error.indexOf("EACCES") >= 0 || j.error.indexOf("Permission") >= 0
                                || j.error.indexOf("denied") >= 0) {
                            sb.append("     → 宿主目录（如 /storage/emulated/0/MT2/…）RevMCP 无权读取。\n")
                              .append("       请改用文件管理器把 APK 手动拷到上面这个 input/ 目录，\n")
                              .append("       或给 RevMCP 存储权限后重试。\n");
                        }
                    }
                    if (j.logFile != null) {
                        sb.append("     日志区间: read_log {\"offset\":\"").append(j.logStart)
                          .append("\",\"max_bytes\":\"").append(j.logEnd - j.logStart).append("\"}\n");
                    }
                }
                sb.append("\n用 job_result {\"job_id\":\"...\"} 看完整错误堆栈。");
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("■ 已缓存文件  ").append(inDir.getAbsolutePath()).append("\n");
        sb.append("  共 ").append(files.length).append(" 个\n");
        for (File f : files) {
            sb.append("  ").append(BlutterRunner.fmtSize(f.length())).append("  ")
              .append(f.getName()).append("\n    ").append(f.getAbsolutePath()).append("\n");
        }
        return sb.toString();
    }

    /** 分析结果落日志 + 返回定位与预览 */
    private String logged(String tool, String subject, String out) throws Exception {
        String body = out == null ? "" : out;
        AnalysisLog.Block b = log().append(AnalysisLog.ANALYSIS, tool + "  " + subject, body);

        StringBuilder sb = new StringBuilder();
        sb.append("【分析完成 · 完整结果已写入日志】\n");
        sb.append("工具     : ").append(tool).append("\n");
        if (subject != null && subject.length() > 0) sb.append("对象     : ").append(subject).append("\n");
        sb.append("日志文件 : ").append(b.file).append("\n");
        sb.append("位置     : 第 ").append(b.startLine).append("-").append(b.endLine)
                .append(" 行（共 ").append(b.endLine).append(" 行）｜ 字节 ")
                .append(b.startByte).append("-").append(b.endByte)
                .append("（").append(b.bytes()).append(" 字节）\n");
        sb.append("取全文   : read_log {\"offset\":").append(b.startByte)
                .append(",\"max_bytes\":20000}\n");
        sb.append("\n");
        if (body.length() <= PREVIEW_CHARS) {
            sb.append("--- 结果全文（").append(body.length()).append(" 字符）---\n").append(body);
        } else {
            sb.append("--- 预览（前 ").append(PREVIEW_CHARS).append(" 字符，共 ")
                    .append(body.length()).append(" 字符）---\n")
                    .append(body, 0, PREVIEW_CHARS).append("\n……（其余内容见日志）\n");
        }
        return sb.toString();
    }

    // ---------- 写操作工具实现 ----------

    /** 解析地址：支持 0x 十六进制与十进制 */
    private static long parseAddr(String s) throws Exception {
        String t = String.valueOf(s).trim();
        if (t.length() == 0) throw new IllegalArgumentException("地址为空");
        boolean neg = false;
        if (t.charAt(0) == '-') { neg = true; t = t.substring(1); }
        String low = t.toLowerCase(Locale.US);
        long v = low.startsWith("0x") ? Long.parseLong(t.substring(2), 16) : Long.parseLong(t);
        return neg ? -v : v;
    }

    /**
     * VA → 文件偏移：按 ELF 节表 [addr, addr+size) → [offset, offset+size) 映射。
     *
     * 逆向时拿到的函数地址（dart_symbols / IDA / 反汇编）基本都是 VA，
     * 而 patch 要的是文件偏移 —— 少了这一步就只能靠外部工具手算。
     */
    private static long vaToOffset(File f, long va) throws Exception {
        ElfReader e = ElfReader.parse(f);
        if (e.unsupported != null && e.unsupported.length() > 0) {
            throw new IOException("ELF 解析失败: " + e.unsupported);
        }
        ElfReader.Section best = null;
        for (ElfReader.Section s : e.sections) {
            if (s.addr == 0 || s.size == 0) continue;
            if (va >= s.addr && va < s.addr + s.size) { best = s; break; }
        }
        if (best == null) {
            throw new IOException("VA 0x" + Long.toHexString(va)
                    + " 不落在任何节的地址区间内（该 so 的节表可能被剥离）\n"
                    + "   改用 offset 参数直接给文件偏移，或用 so_map_addr 看节表映射");
        }
        return best.offset + (va - best.addr);
    }

    /** VA ↔ 文件偏移互转 + 列出可映射区间 */
    private String toolSoMapAddr(JSONObject a) throws Exception {
        String p = req(a, "path");
        File f = new File(p);
        if (!f.isFile()) return "!! 找不到文件: " + p;
        ElfReader e = ElfReader.parse(f);
        StringBuilder sb = new StringBuilder();
        sb.append("■ ELF 地址映射  ").append(p).append("\n");
        if (e.unsupported != null && e.unsupported.length() > 0) {
            sb.append("  ").append(e.unsupported).append("\n");
        }
        sb.append("\n可映射区间（VA → 文件偏移）：\n");
        int n = 0;
        for (ElfReader.Section s : e.sections) {
            if (s.addr == 0 || s.size == 0) continue;
            n++;
            sb.append("  ").append(s.name.isEmpty() ? "(unnamed)" : s.name)
              .append("  VA 0x").append(Long.toHexString(s.addr))
              .append("-0x").append(Long.toHexString(s.addr + s.size))
              .append("  → off 0x").append(Long.toHexString(s.offset))
              .append("\n");
        }
        if (n == 0) {
            sb.append("  （没有带地址的节：节表可能被剥离，无法做 VA 映射，请直接用文件偏移）\n");
        }
        String addrStr = a.optString("addr", "");
        if (addrStr.length() > 0) {
            long v = parseAddr(addrStr);
            sb.append("\n");
            try {
                long off = vaToOffset(f, v);
                sb.append("  VA 0x").append(Long.toHexString(v))
                  .append("  →  文件偏移 0x").append(Long.toHexString(off))
                  .append("（十进制 ").append(off).append("）\n\n");
                sb.append("  直接打补丁：\n    patch_so {\"path\":\"").append(p)
                  .append("\",\"va\":\"0x").append(Long.toHexString(v))
                  .append("\",\"hex\":\"<十六进制字节>\"}\n");
            } catch (Exception ex) {
                sb.append("  !! ").append(ex.getMessage()).append("\n");
            }
        } else {
            sb.append("\n传 addr 可查询具体地址的映射（如 addr=\"0x8b7798\"）。");
        }
        return sb.toString();
    }

    /**
     * patch_so：对 .so 打字节补丁，**支持一次改多处**（{@code patches} 数组）与
     * **写前预演**（{@code peek=true}）。
     *
     * <p>为什么加批量：解锁会员这类目标往往要同时改 3-5 个判定点
     * （needVip / isCloudVipRequiredToBook / …），分 5 次调用既慢又容易只改了
     * 一半。批量版一次打开文件、全部偏移先校验、统一 sync，不会留下"半补丁"。
     *
     * <p>为什么加 peek / 写前回显：blutter 的 asm 只覆盖"生成了 asm 的 dart 文件"，
     * 有时候要改的函数所在 dart 没生成 asm，就只能盲信符号地址。
     * 这里先把目标地址的前 N 字节回显出来，并粗判"像不像函数入口"
     * （stp x29,x30 / sub sp,sp / push {…,lr}），把"改之前自检"变成可自动验证的一步。
     */
    private String toolPatchSo(JSONObject a) throws Exception {
        String path = req(a, "path");
        File f = new File(path);
        if (!f.isFile()) throw new IOException("找不到文件: " + path);
        boolean peek = "true".equalsIgnoreCase(a.optString("peek", "false"));
        int pn = a.optInt("peek_bytes", 16);
        if (pn < 4) pn = 4;
        if (pn > 64) pn = 64;

        // ---- 1) 组装补丁清单：patches[] 优先，否则单处 va/offset + hex ----
        List<String> titles = new ArrayList<String>();
        List<Long> offs = new ArrayList<Long>();
        List<byte[]> datas = new ArrayList<byte[]>();
        JSONArray arr = a.optJSONArray("patches");
        if (arr != null && arr.length() > 0) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                addPatch(f, o, i + 1, titles, offs, datas);
            }
        }
        if (offs.isEmpty()) {
            addPatch(f, a, 1, titles, offs, datas);
        }
        if (offs.isEmpty()) {
            throw new IllegalArgumentException("没有可用的补丁：需要 patches 数组，或单处 va/offset + hex");
        }

        // ---- 2) 回显写前字节（同时用于"像不像函数入口"的自检） ----
        StringBuilder sb = new StringBuilder();
        sb.append("■ patch_so  ").append(path).append("\n");
        sb.append("  文件大小 : ").append(f.length()).append(" 字节\n");
        sb.append("  补丁数   : ").append(offs.size())
          .append(peek ? "（peek=true **预演，不写入**）" : "").append("\n\n");
        String[] befores = new String[offs.size()];
        for (int i = 0; i < offs.size(); i++) {
            long off = offs.get(i);
            byte[] was = readRange(f, off, pn);
            befores[i] = hexOf(was);
            sb.append("  ── #").append(i + 1).append("  ").append(titles.get(i)).append("\n");
            sb.append("     写前 : 0x").append(Long.toHexString(off)).append("  ")
              .append(hexSpaced(was));
            String hint = armHint(was);
            if (hint.length() > 0) sb.append("   ← ").append(hint);
            sb.append("\n");
            sb.append("     写后 : ").append(hexSpaced(datas.get(i))).append("\n");
            String newHint = armHint(datas.get(i));
            if (newHint.length() > 0) sb.append("            （").append(newHint).append("）\n");
        }

        if (peek) {
            sb.append("\n【预演模式】以上是当前字节，文件**未做任何修改**。\n");
            sb.append("确认无误后去掉 peek 再调用同一份参数即可真正写入。\n");
            log().line(AnalysisLog.ANALYSIS, "PATCH_SO(peek) " + path + " ×" + offs.size());
            return sb.toString();
        }

        // ---- 3) 写入（一次打开、全量校验、统一 sync） ----
        long[] offArr = new long[offs.size()];
        byte[][] dataArr = new byte[offs.size()][];
        for (int i = 0; i < offs.size(); i++) {
            offArr[i] = offs.get(i);
            dataArr[i] = datas.get(i);
        }
        String result = BinaryPatch.patchMany(path, offArr, dataArr);
        allowRoot(f.getParentFile());   // 改过哪个文件，就允许读回哪个文件

        // ---- 4) 写后回读，给出"确实改到了"的证据 ----
        StringBuilder after = new StringBuilder();
        for (int i = 0; i < offs.size(); i++) {
            byte[] now = readRange(f, offs.get(i), datas.get(i).length);
            boolean ok = hexOf(now).equals(hexOf(datas.get(i)));
            after.append("     #").append(i + 1).append("  ")
                 .append(ok ? "✅ 一致" : "⚠ 不一致（期望 " + hexOf(datas.get(i))
                         + "，实际 " + hexOf(now) + "）").append("\n");
        }
        log().append(AnalysisLog.ANALYSIS, "PATCH_SO",
                path + " ×" + offs.size() + " 处 " + result.replace('\n', ' '));
        sb.append("\n").append(result).append("\n\n写后回读校验（重新打开文件读回）：\n")
          .append(after);
        sb.append("\n改 .so 只是工作副本；要生效需 replace_entry 打回 APK 并重签名"
                + "（replace_entry 默认已带签名）。\n");
        return sb.toString();
    }

    /** 把一处补丁（va 或 offset + hex）解析成文件偏移 + 字节，追加到清单 */
    private void addPatch(File f, JSONObject o, int idx, List<String> titles,
                          List<Long> offs, List<byte[]> datas) throws Exception {
        String hex = o.optString("hex", "").trim();
        if (hex.length() == 0) {
            throw new IllegalArgumentException("补丁 #" + idx + " 缺少 hex（要写入的十六进制字节）");
        }
        String vaStr = o.optString("va", "").trim();
        String offStr = o.optString("offset", "").trim();
        long off;
        String title;
        if (vaStr.length() > 0) {
            long v = parseAddr(vaStr);
            off = vaToOffset(f, v);   // 逆向拿到的都是 VA，这里自动换算成文件偏移
            title = "VA 0x" + Long.toHexString(v) + " → 文件偏移 0x" + Long.toHexString(off)
                    + "（" + hexByteCount(hex) + " 字节）";
        } else if (offStr.length() > 0) {
            off = parseOff(offStr);
            title = "文件偏移 0x" + Long.toHexString(off) + "（" + hexByteCount(hex) + " 字节）";
        } else {
            throw new IllegalArgumentException("补丁 #" + idx + " 需要 va 或 offset（二选一）");
        }
        byte[] data = parseHexBytes(hex);
        offs.add(off);
        datas.add(data);
        titles.add(title);
    }

    private static int hexByteCount(String hex) {
        int n = 0;
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) n++;
        }
        return n / 2;
    }

    static byte[] parseHexBytes(String hex) {
        StringBuilder h = new StringBuilder();
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) h.append(c);
        }
        if (h.length() % 2 != 0) {
            throw new IllegalArgumentException("hex 长度不是偶数（数一下字节）: " + hex);
        }
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    static String hexOf(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    static String hexSpaced(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * 粗判"这段字节像不像 ARM 函数入口" —— 不依赖反汇编器，纯字节模式。
     *
     * <p>用途：改 so 之前确认符号地址没写错。blutter 的 asm 只覆盖部分 dart 文件，
     * 没有 asm 时只能盲信地址；这里至少能验证"第一句是不是压栈序言"。
     */
    static String armHint(byte[] b) {
        if (b == null || b.length < 4) return "";
        long w = (b[0] & 0xFFL) | ((b[1] & 0xFFL) << 8)
                | ((b[2] & 0xFFL) << 16) | ((b[3] & 0xFFL) << 24);
        // ARM64: stp x29, x30, [sp, #-N]!   高 10 位 = 0x2A6（最常见入口序言）
        if ((w >>> 22) == 0x2A6L) {
            return "✅ 像函数入口：stp x29,x30,[sp,#-N]!（ARM64 标准序言）";
        }
        // ARM64: sub sp, sp, #N   （无帧指针的入口）
        if ((w & 0xFF8003FFL) == 0xD1000000L) {
            return "✅ 像函数入口：sub sp,sp,#N（ARM64 无帧指针序言）";
        }
        // ARM64: ret —— 说明这是函数**结尾**
        if (w == 0xD65F03C0L) {
            return "⚠ 这是 ret（函数结尾），不是函数入口 —— 确认地址是否指到了 return 处";
        }
        // ARM32: push {…, lr}   （xx 40 2d e9）
        if ((b[3] & 0xFF) == 0xE9 && (b[2] & 0xFF) == 0x2D && (b[1] & 0xFF) == 0x40) {
            return "✅ 像函数入口：push {…,lr}（ARM32 序言）";
        }
        // Thumb: push {…, lr}  （2 字节，xx b5 且寄存器位含 lr）
        if (b.length >= 2 && (b[1] & 0xFF) == 0xB5 && ((b[0] & 0x0F) >= 0x08)) {
            return "可能像函数入口：Thumb push {…,lr}（16 位 Thumb 序言）";
        }
        return "⚠ 开头字节不像典型 ARM 函数序言，调用前请确认地址是否正确";
    }

    private String toolPatchFile(JSONObject a) throws Exception {
        String path = req(a, "path");
        String offset = req(a, "offset");
        String hex = req(a, "hex");
        String result = BinaryPatch.patchHex(path, offset, hex);
        allowRoot(new File(path).getParentFile());   // 改过哪个文件，就允许读回哪个文件
        log().append(AnalysisLog.ANALYSIS, "PATCH_FILE", path + " @ " + offset + " hex=" + hex);
        return result;
    }

    /**
     * read_so_bytes：按 VA / 文件偏移读任意字节，返回 hexdump（含 ASCII 列）。
     *
     * <p>补的是旧工具链 {@code mt_apk_read_bytes} 的缺口：想确认"0x970b94 是不是
     * 函数入口"，只能靠 blutter 的 {@code asm/*.dart} 间接看，而没生成 asm 的 dart
     * 文件（例如 {@code pages/diary/add/logic.dart}）就完全读不到，
     * 只能盲信符号地址。有了它，"patch 前自检"是一步可自动验证的操作。
     *
     * <p>{@code va} 走 ELF 节表换算（本 build 上 VA==文件偏移时结果也一样），
     * {@code offset} 直接当文件偏移用 —— 非 ELF 文件（dex / 任意二进制）只能用 offset。
     */
    private String toolReadSoBytes(JSONObject a) throws Exception {
        String path = req(a, "path");
        File f = new File(path);
        if (!f.isFile()) return "!! 找不到文件: " + path;
        int len = a.optInt("length", 256);
        if (len < 4) len = 4;
        if (len > 65536) len = 65536;

        String vaStr = a.optString("va", "").trim();
        String offStr = a.optString("offset", "").trim();
        long off;
        String how;
        if (vaStr.length() > 0) {
            long v = parseAddr(vaStr);
            try {
                off = vaToOffset(f, v);
            } catch (Exception e) {
                return "!! VA 换算失败: " + e.getMessage()
                        + "\n   （该地址不在任何可映射节内。若目标不是 ELF，请改用 offset 直接给文件偏移）";
            }
            how = "VA 0x" + Long.toHexString(v) + "  →  文件偏移 0x" + Long.toHexString(off);
        } else if (offStr.length() > 0) {
            off = parseOff(offStr);
            how = "文件偏移 0x" + Long.toHexString(off) + "（" + off + "）";
        } else {
            throw new IllegalArgumentException("需要 va 或 offset（二选一）："
                    + "va 走 ELF 节表换算，offset 直接给文件偏移");
        }

        byte[] data = readRange(f, off, len);
        StringBuilder sb = new StringBuilder();
        sb.append("■ read_so_bytes  ").append(path).append("\n");
        sb.append("  文件大小 : ").append(f.length()).append(" 字节\n");
        sb.append("  读取位置 : ").append(how).append("\n");
        sb.append("  读取长度 : ").append(data.length).append(" 字节");
        if (data.length == 0) sb.append("（**已到文件末尾**，检查 offset 是否超出）");
        sb.append("\n");
        String hint = armHint(data);
        if (hint.length() > 0) sb.append("  字节判读 : ").append(hint).append("\n");
        sb.append("------------------------------------------------------------\n");
        sb.append(hexDump(data, off));
        sb.append("------------------------------------------------------------\n");
        sb.append("接下去：patch_so {\"path\":\"").append(path)
          .append("\",\"va\":\"").append(vaStr.length() > 0 ? vaStr : ("0x" + Long.toHexString(off)))
          .append("\",\"hex\":\"...\"}\n")
          .append("       不确定改法时先 peek：patch_so {…, \"peek\":\"true\"} 只回显不写入。\n");
        return sb.toString();
    }

    /**
     * sign_apk：给 APK 重签名（v1 JAR + v2 + v3），闭合"改包 → 能装"这最后一环。
     *
     * <p>不用自己写签名器：本工程的构建脚本早就把官方 apksig 整份 dex 进包了
     * （原本只给 verify_apk 做只读校验），同一份库里就有 {@code ApkSigner}。
     * 所以设备端能直接签，**不需要电脑、不需要 apksigner 可执行文件**。
     */
    private String toolSignApk(JSONObject a) throws Exception {
        String p = req(a, "path");
        File in = new File(p);
        if (!in.isFile()) return "!! 找不到 APK: " + p;
        boolean inPlace = "true".equalsIgnoreCase(a.optString("in_place", "false"));
        String outStr = a.optString("out_apk", "").trim();
        File out;
        if (outStr.length() > 0) {
            out = new File(outStr);
        } else if (inPlace) {
            out = in;
        } else {
            String base = in.getName();
            if (base.toLowerCase(Locale.US).endsWith(".apk")) base = base.substring(0, base.length() - 4);
            out = new File(outDir(), base + "_signed.apk");
        }
        if (out.isDirectory()) return "!! 输出路径是个目录: " + outStr;

        String ksPath = a.optString("ks_path", "").trim();
        File ks;
        if (ksPath.length() > 0) {
            ks = new File(ksPath);
            if (!ks.isFile()) return "!! 指定的 keystore 不存在: " + ksPath;
        } else {
            ks = defaultKeystore();
            if (ks == null || !ks.isFile()) {
                return "!! 内置 debug keystore 不可用（assets/" + ApkSign.ASSET_NAME
                        + " 缺失，且尚未释放到磁盘）。\n"
                        + "   改用 ks_path 指定你自己的 keystore/.jks，"
                        + "或在界面点「导出 debug keystore」。";
            }
        }
        String storePass = a.optString("ks_pass", ApkSign.DEFAULT_STORE_PASS);
        String alias = a.optString("key_alias", "");
        String keyPass = a.optString("key_pass", "");
        boolean v1 = !"false".equalsIgnoreCase(a.optString("v1", "true"));
        boolean v2 = !"false".equalsIgnoreCase(a.optString("v2", "true"));
        boolean v3 = !"false".equalsIgnoreCase(a.optString("v3", "true"));
        int minSdk = a.optInt("min_sdk", 0);

        ApkSign.Result r = ApkSign.sign(in, out, ks, storePass, alias, keyPass, v1, v2, v3, minSdk);
        allowRoot(out.getParentFile());
        log().append(AnalysisLog.ANALYSIS, "SIGN_APK", p + " -> " + out + " | " + r.text);

        StringBuilder sb = new StringBuilder();
        sb.append("✅ ").append(r.text).append("\n\n");
        sb.append("输入      : ").append(in.getAbsolutePath()).append("\n");
        sb.append("输出      : ").append(out.getAbsolutePath())
          .append("  ").append(BlutterRunner.fmtSize(out.length())).append("\n");
        sb.append("keystore  : ").append(ks.getAbsolutePath())
          .append(ksPath.length() == 0 ? "（内置 debug keystore，口令 android）" : "（用户指定）")
          .append("\n");
        sb.append("证书 SHA-256: ").append(r.certSha256).append("\n");
        sb.append(ApkSign.alignmentReport(out, 4096));
        sb.append("\n【签名后校验】\n").append(ApkPack.verifyApk(out.getAbsolutePath()));
        sb.append("\n【提示】\n");
        sb.append("  · 签名覆盖整个包内容，改包后必须重签才能装；本工具就是干这个的。\n");
        sb.append("  · 换签名等于换身份 —— 已装的同包名应用需先卸载，否则报签名冲突。\n");
        sb.append("  · .so 那一行若报 ⚠：只有**原本 STORED 的 .so 掉对齐**才需要处理，\n");
        sb.append("    加 v1=false 重签即可（v2-only 路径逐字节搬运原内容，不可能掉对齐）；\n");
        sb.append("    DEFLATE 的 .so 是合规的（extractNativeLibs=true，安装期解压），不用管。\n");
        return sb.toString();
    }

    /**
     * 内置 debug keystore：首次使用时从 assets 释放到产物根 keystore/ 下，之后复用。
     * 桌面回归环境（ctx == null）没有 assets，返回路径由调用方判断是否存在。
     */
    private File defaultKeystore() {
        File f = new File(workDir(), "keystore/" + ApkSign.ASSET_NAME);
        if (f.isFile() && f.length() > 0) return f;
        if (ctx == null) return f;
        InputStream is = null;
        OutputStream os = null;
        try {
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            is = ctx.getAssets().open(ApkSign.ASSET_NAME);
            os = new java.io.FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } catch (Exception e) {
            Log.w(TAG, "释放内置 keystore 失败: " + e);
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignore) { /* ignore */ }
            if (os != null) try { os.close(); } catch (Exception ignore) { /* ignore */ }
        }
        return f;
    }

    /**
     * replace_entry：把 APK 内一个（或一批）条目换成新文件的内容。
     *
     * <p>这里**不再用 ZipOutputStream 全量重压** —— 那样会把原包 STORED 的
     * {@code lib/&lt;abi&gt;/*.so} 全部压成 DEFLATE、丢掉 zipalign 对齐，
     * 装到手机上就是 {@code INSTALL_FAILED_INVALID_APK: Failed to extract native libraries}。
     * 现在改成逐条原样搬运：未指定条目保留原压缩方式与元数据，
     * {@code lib/**\/*.so} 一律 STORED + 4096 字节对齐。详见 {@link BinaryPatch}。
     */
    private String toolReplaceEntry(JSONObject a) throws Exception {
        String apkPath = req(a, "apk_path");

        List<BinaryPatch.Swap> swaps = new ArrayList<BinaryPatch.Swap>();
        JSONArray arr = a.optJSONArray("entries");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String e = o.optString("entry", "").trim();
                String f = o.optString("new_file", "").trim();
                if (e.length() == 0 || f.length() == 0) {
                    throw new IllegalArgumentException(
                            "entries[" + i + "] 缺字段：需要 entry（APK 内条目名）与 new_file（新文件路径）");
                }
                swaps.add(new BinaryPatch.Swap(e, f));
            }
        }
        if (swaps.isEmpty()) {
            swaps.add(new BinaryPatch.Swap(req(a, "entry"), req(a, "new_file")));
        }

        // 不给 out_apk 时默认落到 <产物根>/out/，省得再问一次路径，也方便文件管理器直接翻
        String outApk = a.optString("out_apk", "").trim();
        if (outApk.length() == 0) {
            String base = new File(apkPath).getName();
            if (base.toLowerCase(Locale.US).endsWith(".apk")) base = base.substring(0, base.length() - 4);
            outApk = new File(outDir(), base + "_patched.apk").getAbsolutePath();
        }

        File outApkFile = new File(outApk);
        if (outApkFile.isDirectory()) throw new IllegalArgumentException("out_apk 是个目录: " + outApk);

        // 默认**顺手签名**：改包后原签名必然失效，不签就是个装不上的包 ——
        // 这是整条链路唯一需要人肉介入的断链点（以前得回头开 MT 管理器手签）。
        boolean wantSign = !"false".equalsIgnoreCase(a.optString("sign", "true"));

        // 顺序不能反：必须先换条目（保真搬运 + STORED/4096 对齐），再原地重签名。
        // 签名覆盖整个包内容，先签后改必然失效。
        String result = BinaryPatch.replaceEntries(apkPath, swaps, outApk);
        allowRoot(outApkFile.getParentFile());   // 改到哪儿，就允许从哪儿读回
        log().append(AnalysisLog.ANALYSIS, "REPLACE_ENTRY",
                apkPath + " -> " + swaps.size() + " 条目 -> " + outApk);

        ApkSign.Result sr = null;
        String signErr = null;
        if (wantSign) {
            try {
                String ksPath = a.optString("ks_path", "").trim();
                File ks = ksPath.length() > 0 ? new File(ksPath) : defaultKeystore();
                if (!ks.isFile()) {
                    signErr = "keystore 不可用: " + ks.getAbsolutePath()
                            + "（内置 assets/" + ApkSign.ASSET_NAME + " 缺失？）";
                } else {
                    sr = ApkSign.sign(outApkFile, outApkFile, ks,
                            a.optString("ks_pass", ApkSign.DEFAULT_STORE_PASS),
                            a.optString("key_alias", ""),
                            a.optString("key_pass", ""),
                            !"false".equalsIgnoreCase(a.optString("v1", "true")),
                            true, true,
                            a.optInt("min_sdk", 0));
                }
            } catch (Exception e) {
                signErr = String.valueOf(e.getMessage());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("✅ ").append(result).append("\n\n");
        sb.append("原 APK    : ").append(apkPath).append("\n");
        sb.append("输出 APK  : ").append(outApk).append("\n");
        sb.append("替换条目  : ").append(swaps.size()).append(" 个\n");
        for (BinaryPatch.Swap s : swaps) {
            sb.append("  - ").append(s.entry).append("\n")
              .append("       ← ").append(s.file)
              .append("  (").append(BlutterRunner.fmtSize(new File(s.file).length()))
              .append(")\n");
        }
        sb.append("\n【.so 怎么打包】\n");
        sb.append("  lib/<abi>/*.so 的压缩方式**原样保留**，不强制改写 —— 合法性取决于 manifest：\n");
        sb.append("    extractNativeLibs=true  → 安装期由系统解压到 nativeLibraryDir，DEFLATE 可用（还省体积）；\n");
        sb.append("    extractNativeLibs=false → loader 直接 mmap APK 内数据，必须 STORED + 页对齐，\n");
        sb.append("                              被压缩或不对齐都会报 INSTALL_FAILED_INVALID_APK。\n");
        sb.append("  本工具保留原压缩方式，并给所有 STORED 的 .so 补上 4096 字节对齐 —— 两种模式都安全。\n");
        sb.append("\n【只改了本次列出的条目】\n");
        sb.append("  双 ABI 的包（arm64-v8a + armeabi-v7a）如果只替换了 arm64，\n");
        sb.append("  32 位的 v7a 那个 so 保持原样 —— 64 位手机只加载 arm64，v7a 只用于安装校验，\n");
        sb.append("  这是正常且够用的。要一起改就在一次调用里传 entries 数组（对齐只算一遍）。\n");

        sb.append("\n【签名：闭合交付链】\n");
        if (sr != null) {
            sb.append("  ✅ 已重签名：").append(sr.text).append("\n");
            sb.append("     证书 SHA-256: ").append(sr.certSha256).append("\n");
            sb.append("     keystore: ").append(a.optString("ks_path", "").trim().length() > 0
                    ? a.optString("ks_path").trim() : "内置 debug keystore（口令 android）").append("\n");
            sb.append("     → 输出包可直接安装，**不需要**再开 MT 管理器手签。\n");
        } else if (signErr != null) {
            sb.append("  ❌ 自动签名失败：").append(signErr).append("\n");
            sb.append("     换条目本身已成功，包是**未签名**的（装不上）。补签一次：\n");
            sb.append("       sign_apk {\"path\":\"").append(outApk).append("\",\"in_place\":\"true\"}\n");
        } else {
            sb.append("  ⏭ 按 sign=false 跳过 —— 输出包**未签名**，安装前必须重签：\n");
            sb.append("       sign_apk {\"path\":\"").append(outApk).append("\",\"in_place\":\"true\"}\n");
        }
        sb.append(ApkSign.alignmentReport(outApkFile, 4096));
        sb.append("\n【注意】换签名等于换身份：设备上若已装同包名应用，先卸载再装，\n");
        sb.append("  否则会报「签名冲突 / 应用未安装」。\n");
        sb.append("  改包后原签名必然失效，verify_apk 报「签名失效」是**预期**；\n");
        sb.append("  只有报「结构损坏」才是真失败。\n");
        log().line(AnalysisLog.SERVER, "replace_entry 完成: " + apkPath + " -> " + outApk
                + (sr != null ? "（已签名）" : "（未签名）"));
        return sb.toString();
    }

    // ---------- 日志工具实现 ----------

    private String toolReadLog(JSONObject a) {
        String which = a.optString("which", AnalysisLog.ANALYSIS);
        if (!AnalysisLog.SERVER.equals(which)) which = AnalysisLog.ANALYSIS;
        File f = log().file(which);
        if (!f.exists()) {
            return "日志尚不存在: " + f.getAbsolutePath()
                    + "\n（先调用任意分析工具产生内容；which 可选 analysis | server）";
        }
        long total = log().size(which);
        int totalLines = log().lines(which);
        int lineN = a.optInt("lines", 0);

        StringBuilder sb = new StringBuilder();
        if (lineN > 0) {
            sb.append("日志文件 : ").append(f.getAbsolutePath()).append("\n");
            sb.append("总字节   : ").append(total).append("  ｜ 总行数: ").append(totalLines).append("\n");
            sb.append("读取模式 : tail 最后 ").append(lineN).append(" 行\n");
            sb.append("------------------------------------------------------------\n");
            sb.append(log().tail(which, lineN));
            return sb.toString();
        }

        long offset = 0;
        Object ov = a.opt("offset");
        if (ov != null && !JSONObject.NULL.equals(ov)) {
            if (ov instanceof Number) {
                offset = ((Number) ov).longValue();
            } else {
                String s = String.valueOf(ov).trim();
                try {
                    offset = s.toLowerCase(Locale.US).startsWith("0x")
                            ? Long.parseLong(s.substring(2), 16) : Long.parseLong(s);
                } catch (Exception e) { offset = 0; }
            }
        }
        if (offset < 0) offset = 0;
        int max = a.optInt("max_bytes", READ_DEFAULT);
        if (max <= 0) max = READ_DEFAULT;
        if (max > READ_MAX) max = READ_MAX;

        byte[] data = log().readBytes(which, offset, max);
        long next = offset + data.length;
        sb.append("日志文件 : ").append(f.getAbsolutePath()).append("\n");
        sb.append("总字节   : ").append(total).append("  ｜ 总行数: ").append(totalLines).append("\n");
        sb.append("本次区间 : ").append(offset).append(" - ").append(next)
                .append("（").append(data.length).append(" 字节）\n");
        if (next < total) {
            sb.append("还有更多 : 是 → 继续 read_log {\"offset\":").append(next)
                    .append(",\"max_bytes\":").append(max).append("}\n");
        } else {
            sb.append("还有更多 : 否（已读到末尾）\n");
        }
        sb.append("------------------------------------------------------------\n");
        sb.append(new String(data, UTF8));
        return sb.toString();
    }

    // ---------------- 产物文件（与日志区分：read_log 只认 analysis/server） ----------------

    /** 逗号分隔参数 → 数组（去空白、忽略空项） */
    private static String[] csv(String s) {
        if (s == null || s.trim().length() == 0) return new String[0];
        String[] raw = s.split(",");
        List<String> out = new ArrayList<String>();
        for (String r : raw) {
            String t = r.trim();
            if (t.length() > 0) out.add(t);
        }
        return out.toArray(new String[out.size()]);
    }

    /**
     * 允许读取的根目录：整个产物根 + 日志/导入/改包四个子目录。
     *
     * <p>**为什么要把整个产物根也算进去**：blutter 把产物写在 libapp.so 的**同级**
     * blutter_out/ 下。如果调用方用 so_path 指了一个产物根之外的 .so，
     * blutter_out/ 就落在白名单外 —— 产物明明是我们自己生成的，读的时候却被
     * 「拒绝越界读取」挡住。把整个 root 放开 + {@link #allowRoot} 动态登记，
     * 保证「我们自己写出来的东西，一定能读回去」。
     */
    private File[] artifactRoots() {
        List<File> l = new ArrayList<File>();
        try { l.add(RevPaths.root()); } catch (Throwable ignore) { /* ignore */ }
        l.add(workDir());
        l.add(log().dir());
        l.add(inputDir());
        l.add(outDir());
        for (String s : extraRoots) l.add(new File(s));
        return l.toArray(new File[l.size()]);
    }

    /**
     * 登记一个「本会话实际往里写过产物」的目录，把它加入可读白名单。
     * 任何把产物写到用户指定目录的工具都应该调一次 —— 否则写得出、读不回。
     */
    private void allowRoot(File dir) {
        if (dir == null) return;
        try {
            String c = dir.getCanonicalPath();
            if (extraRoots.add(c)) {
                try { log().line(AnalysisLog.SERVER, "产物白名单 + " + c); }
                catch (Exception ignore) { /* 日志失败不影响主流程 */ }
            }
        } catch (Exception ignore) { /* ignore */ }
    }

    /** 读取越界时给出的允许根清单（排查用：直接看到底允许读哪儿） */
    private String allowedRootsText() {
        StringBuilder sb = new StringBuilder();
        for (File r : artifactRoots()) {
            if (r == null) continue;
            sb.append("   ").append(r.getAbsolutePath())
              .append(r.isDirectory() ? "" : "（不存在）").append("\n");
        }
        return sb.toString();
    }

    /** f 是否位于 root 之内（避免工具被用来读设备上任意文件） */
    private static boolean inside(File f, File root) {
        if (root == null) return false;
        try {
            String c = f.getCanonicalPath();
            String r = root.getCanonicalPath();
            return c.equals(r) || c.startsWith(r + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean allowedArtifact(File f) {
        if (f == null) return false;
        for (File r : artifactRoots()) if (inside(f, r)) return true;
        return false;
    }

    /**
     * blutter 产物目录：**一律落在产物根的 work/ 下**。
     *
     * <p>为什么不能简单地取 {@code libapp.so} 的同级目录：blutter_run 支持直接传
     * {@code so_path}，用户可能指 {@code /sdcard/MT2/libapp.so} 这种产物根之外的位置。
     * 那样 blutter_out/ 就落在白名单外 —— 产物是我们自己生成的，读的时候却被
     * 「拒绝越界读取」挡住；文件管理器里也得跑去 /sdcard 找，和「产物统一」的目标相反。
     *
     * <p>所以：已经在产物根内 → 放在 so 同级（和 analyze_file 的产物同一棵树）；
     * 在根之外 → 收拢到 {@code work/<so 名>/blutter_out}。
     */
    private File blutterOutDir(File libapp) {
        File parent = libapp.getParentFile();
        if (parent != null && inside(parent, RevPaths.root())) {
            return new File(parent, "blutter_out");
        }
        String n = libapp.getName();
        if (n.length() == 0) n = "libapp.so";
        File w = new File(workDir(), n);
        w.mkdirs();
        return new File(w, "blutter_out");
    }

    private static void collectFiles(File dir, List<File> out, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                if (depth > 1) collectFiles(k, out, depth - 1);
            } else {
                out.add(k);
            }
        }
    }

    /** 从任意同签名位置解析偏移（支持 0x 十六进制与十进制） */
    private static long parseOff(Object ov) {
        if (ov == null || JSONObject.NULL.equals(ov)) return 0;
        if (ov instanceof Number) {
            long v = ((Number) ov).longValue();
            return v < 0 ? 0 : v;
        }
        String s = String.valueOf(ov).trim();
        try {
            long v = s.toLowerCase(Locale.US).startsWith("0x")
                    ? Long.parseLong(s.substring(2), 16) : Long.parseLong(s);
            return v < 0 ? 0 : v;
        } catch (Exception e) {
            return 0;
        }
    }

    private static byte[] readRange(File f, long off, int len) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            long skipped = 0;
            while (skipped < off) {
                long s = in.skip(off - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int got;
            while (bos.size() < len && (got = in.read(buf, 0, Math.min(buf.length, len - bos.size()))) > 0) {
                bos.write(buf, 0, got);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        } finally {
            AnalysisLog.close(in);
        }
    }

    /** 标准 hex dump：偏移 + 16 进制 + ASCII 列（核对补丁字节用） */
    private static String hexDump(byte[] d, long baseOff) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 16) {
            sb.append(String.format(Locale.US, "%08X  ", baseOff + i));
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16; j++) {
                if (i + j < d.length) {
                    int v = d[i + j] & 0xFF;
                    sb.append(String.format(Locale.US, "%02X ", v));
                    ascii.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
                } else {
                    sb.append("   ");
                }
                if (j == 7) sb.append(' ');
            }
            sb.append(' ').append(ascii).append('\n');
        }
        return sb.toString();
    }

    /** 这段字节像不像二进制（含大量 0x00 或不可打印字节） */
    private static boolean looksBinary(byte[] d) {
        if (d == null || d.length == 0) return false;
        int bad = 0, n = Math.min(d.length, 4096);
        for (int i = 0; i < n; i++) {
            int v = d[i] & 0xFF;
            if (v == 0 || (v < 0x09) || (v > 0x0D && v < 0x20)) bad++;
        }
        return bad * 100 / Math.max(1, n) > 5;
    }

    /**
     * 按文件名后缀判断是不是二进制产物。
     *
     * <p>为什么要有它：{@code list_artifacts filter=diary} 一次命中 97 个文件，
     * 里面混着一堆 {@code .so} 和图片，真正想找的 dart 源文件反而被淹掉。
     * 默认把这些挡掉，要找再显式 {@code binary="include"}。
     */
    private static final String[] BIN_EXT = {
            ".so", ".dex", ".apk", ".aab", ".aar", ".jar", ".zip", ".class", ".o", ".a",
            ".arsc", ".bin", ".dat", ".db", ".sqlite", ".so-xz",
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".ico",
            ".ttf", ".otf", ".woff", ".woff2",
            ".mp3", ".mp4", ".ogg", ".wav", ".m4a", ".webm", ".aac",
            ".wasm", ".elf", ".dylib", ".dll", ".exe",
    };

    private static boolean isBinaryName(String lowerName) {
        for (String e : BIN_EXT) {
            if (lowerName.endsWith(e)) return true;
        }
        return false;
    }

    /** "a|b|c" → {"a","b","c"}；空串 → 空数组（表示不过滤） */
    private static String[] splitKeywords(String s) {
        if (s == null || s.trim().length() == 0) return new String[0];
        String[] parts = s.split("\\|");
        List<String> out = new ArrayList<String>();
        for (String p : parts) {
            String t = p.trim().toLowerCase(Locale.US);
            if (t.length() > 0) out.add(t);
        }
        return out.toArray(new String[out.size()]);
    }

    private static boolean containsAny(String hay, String[] needles) {
        if (hay == null) return false;
        String l = hay.toLowerCase(Locale.US);
        for (String n : needles) if (l.indexOf(n) >= 0) return true;
        return false;
    }

    private static boolean matchesAnyPrefix(String s, String[] prefixes) {
        for (String p : prefixes) if (s.startsWith(p)) return true;
        return false;
    }

    /** 最近一次生成的 Dart 符号索引 */
    private File newestSymbols() {
        List<File> all = new ArrayList<File>();
        File w = workDir();
        if (w.isDirectory()) collectFiles(w, all, 4);
        File best = null;
        for (File f : all) {
            if (!PpDigest.TSV_NAME.equals(f.getName())) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    private String toolListArtifacts(JSONObject a) {
        String given = a.optString("dir", "");
        File dir = given.length() > 0 ? new File(given) : workDir();
        if (!dir.isDirectory()) {
            return "（还没有产物）" + dir.getAbsolutePath()
                    + "\n   先跑 analyze_file 或 blutter_run 生成产物。";
        }
        if (!allowedArtifact(dir)) {
            return "!! 只允许列出本工具产物目录内的文件。\n"
                    + "   当前允许的根：\n" + allowedRootsText();
        }
        // 深度必须够深：blutter 反汇编在
        //   work/<apk>/blutter_out/relaxed/asm/<package>/<dir>/<file>.dart
        // 从 work/ 算起要 7 层才够。之前固定 3 层，asm/ 下的几百个 .dart 全被漏掉。
        int depth = a.optInt("depth", 8);
        if (depth < 1) depth = 1;
        if (depth > 12) depth = 12;
        String filter = a.optString("filter", "").toLowerCase(Locale.US);
        // 每个目录最多列几个文件。blutter 的 asm/ 下动辄几百上千个 .dart，
        // 全平铺返回会刷出 100KB+ 文本、把客户端上下文冲掉 —— 默认按目录截断，
        // 但**不隐藏信息**：会明确写出"本目录还有 N 个"并给出缩小范围的方法。
        int perDir = a.optInt("per_dir", 12);
        if (perDir < 1) perDir = 1;
        if (perDir > 500) perDir = 500;

        List<File> files = new ArrayList<File>();
        collectFiles(dir, files, depth);

        // ---- 过滤：默认把二进制产物挡掉 ----
        // filter=diary 一次命中 97 个文件、里面混着一堆 .so，要找的
        // pages/diary/add/logic.dart 反而被淹掉 —— 所以：
        //   · binary 默认 exclude（.so/.dex/.apk/图片/字体…）
        //   · exact=true 按文件名精确匹配（不看路径）
        //   · under / suffix 把范围收到 asm/ 或 *.dart
        boolean exact = "true".equalsIgnoreCase(a.optString("exact", "false"));
        String suffix = a.optString("suffix", "").trim().toLowerCase(Locale.US);
        String under = a.optString("under", "").trim().toLowerCase(Locale.US);
        String binMode = a.optString("binary", "exclude").trim().toLowerCase(Locale.US);
        if (!suffix.isEmpty() && suffix.charAt(0) != '.') suffix = "." + suffix;
        String suf = suffix;
        int skippedBin = 0, skippedUnder = 0, skippedSuffix = 0, skippedFilter = 0;
        java.util.Iterator<File> it = files.iterator();
        while (it.hasNext()) {
            File f = it.next();
            String ap = f.getAbsolutePath().toLowerCase(Locale.US);
            String nm = f.getName().toLowerCase(Locale.US);
            if (under.length() > 0 && ap.indexOf(under) < 0) { it.remove(); skippedUnder++; continue; }
            if (suf.length() > 0 && !nm.endsWith(suf)) { it.remove(); skippedSuffix++; continue; }
            if (filter.length() > 0) {
                boolean hit = exact ? nm.equals(filter) : ap.indexOf(filter) >= 0;
                if (!hit) { it.remove(); skippedFilter++; continue; }
            }
            boolean bin = isBinaryName(nm);
            if (bin && "exclude".equals(binMode)) { it.remove(); skippedBin++; continue; }
            if (!bin && "only".equals(binMode)) { it.remove(); continue; }
        }
        Collections.sort(files, new Comparator<File>() {
            public int compare(File x, File y) {
                long d = y.lastModified() - x.lastModified();
                if (d > 0) return 1;
                if (d < 0) return -1;
                return x.getName().compareTo(y.getName());
            }
        });
        StringBuilder sb = new StringBuilder();
        sb.append("■ 产物清单  ").append(dir.getAbsolutePath()).append("\n");
        sb.append("  共 ").append(files.size()).append(" 个文件");
        if (filter.length() > 0) sb.append("（过滤: ").append(filter)
                .append(exact ? "，文件名精确" : "，路径子串").append("）");
        if (under.length() > 0) sb.append("（限定路径含: ").append(under).append("）");
        if (suf.length() > 0) sb.append("（后缀: ").append(suf).append("）");
        sb.append(" ｜ 扫描深度 ").append(depth)
          .append(" ｜ 每目录最多列 ").append(perDir).append(" 个\n");
        if (skippedBin > 0 || binMode.equals("exclude")) {
            sb.append("  已排除二进制 ").append(skippedBin).append(" 个（.so/.dex/.apk/图片/字体…）");
            if (skippedBin > 0) sb.append(" → 要看它们用 binary=\"include\" 或 binary=\"only\"");
            sb.append("\n");
        }
        if (skippedUnder > 0 || skippedSuffix > 0 || skippedFilter > 0) {
            sb.append("  未命中被排除：路径不含 under ").append(skippedUnder).append(" ｜ 后缀不符 ")
              .append(skippedSuffix).append(" ｜ 关键字不符 ").append(skippedFilter).append("\n");
        }

        // 按目录分组：先给每个目录一行汇总（数量），再列该目录下的文件。
        // 重要产物（dart_symbols.tsv / pp.txt）在浅层，几百个 .dart 在深层 ——
        // 分组后一眼就能分清哪个是哪个。
        Map<String, List<File>> byDir = new java.util.LinkedHashMap<String, List<File>>();
        for (File f : files) {
            File p = f.getParentFile();
            String key = p == null ? dir.getAbsolutePath() : p.getAbsolutePath();
            List<File> l = byDir.get(key);
            if (l == null) { l = new ArrayList<File>(); byDir.put(key, l); }
            l.add(f);
        }
        if (byDir.size() > 1) {
            sb.append("\n  目录分布:\n");
            List<String> keys = new ArrayList<String>(byDir.keySet());
            Collections.sort(keys);
            for (String k : keys) {
                sb.append("    ").append(byDir.get(k).size()).append(" 个  ").append(k).append("\n");
            }
            sb.append("\n");
        }

        int shownTotal = 0;
        for (Map.Entry<String, List<File>> e : byDir.entrySet()) {
            List<File> l = e.getValue();
            sb.append("  ── ").append(e.getKey()).append("  （").append(l.size()).append(" 个）\n");
            int show = Math.min(l.size(), perDir);
            for (int i = 0; i < show; i++) {
                File f = l.get(i);
                sb.append("     ").append(BlutterRunner.fmtSize(f.length())).append("  ")
                  .append(f.getName()).append("\n")
                  .append("        ").append(f.getAbsolutePath()).append("\n");
                shownTotal++;
            }
            if (l.size() > show) {
                sb.append("     …（本目录还有 ").append(l.size() - show)
                  .append(" 个未列出，用 filter=\"关键字\" 或 per_dir=200 展开）\n");
            }
        }
        sb.append("\n本次列出 ").append(shownTotal).append(" / ").append(files.size()).append(" 个文件\n");
        sb.append("读法 : read_artifact {\"path\":\"…\"}（二进制加 hex=true）；Dart 符号表用 dart_symbols\n");
        return sb.toString();
    }

    private String toolReadArtifact(JSONObject a) {
        String p = a.optString("path", "");
        if (p.length() == 0) return "!! 需要 path（产物文件绝对路径，先用 list_artifacts 拿）";
        File f = new File(p);
        if (!f.isFile()) return "!! 不是文件: " + p;
        if (!allowedArtifact(f)) {
            return "!! 只允许读本工具产物目录内的文件，拒绝越界读取。\n"
                    + "   当前允许的根：\n" + allowedRootsText()
                    + "   blutter 产物若在别处，请用 blutter_run 重新跑一次"
                    + "（产物会自动收拢到产物根的 work/ 下）。";
        }
        long total = f.length();
        long off = parseOff(a.opt("offset"));
        int max = a.optInt("max_bytes", READ_DEFAULT);
        if (max <= 0) max = READ_DEFAULT;
        if (max > READ_MAX) max = READ_MAX;
        String quote = a.optString("quote", "").toLowerCase(Locale.US);
        boolean hexMode = "true".equalsIgnoreCase(a.optString("hex", "false"));
        // 按地址锚点读：符号表里拿到的地址（0x970b94）直接定位到那一行，
        // 并把它前后的 N 行一起给出 —— 只看命中行（quote 的旧行为）看不到上下文。
        String addr = a.optString("addr", "").trim();
        int context = a.optInt("context", addr.length() > 0 ? 25 : 0);
        if (context < 0) context = 0;
        if (context > 500) context = 500;

        StringBuilder sb = new StringBuilder();
        sb.append("产物文件 : ").append(f.getAbsolutePath()).append("\n");
        sb.append("总字节   : ").append(total).append("\n");

        // addr + hex：把 addr 当文件偏移做 hex dump（核对补丁字节用）
        if (addr.length() > 0 && hexMode) {
            long at = parseOff(addr);
            int width = a.optInt("width", 256);
            if (width < 16) width = 16;
            if (width > 4096) width = 4096;
            int pad = a.optInt("pre", 16);
            if (pad < 0) pad = 0;
            if (pad > 4096) pad = 4096;
            long start = Math.max(0, at - pad);
            byte[] data = readRange(f, start, pad + width);
            sb.append("模式     : hex dump @ 地址锚点（每行 16 字节）\n");
            sb.append("锚点     : 0x").append(Long.toHexString(at))
              .append("（链锚点前后各 ").append(pad).append(" 字节，共 ")
              .append(data.length).append(" 字节）\n");
            sb.append("------------------------------------------------------------\n");
            sb.append(hexDump(data, start));
            sb.append("------------------------------------------------------------\n");
            sb.append("上面打 * 的那一段就是锚点位置附近；核对补丁就看锚点起始行的 16 进制。\n");
            return sb.toString();
        }

        // 二进制产物（改过字节的 .so）直接按 UTF-8 输出就是一片 \u0000 乱码，
        // 根本没法核对补丁字节 —— hex=true 走标准 hexdump（偏移 + 16 进制 + ASCII）。
        if (hexMode) {
            int width = a.optInt("width", 512);
            if (width < 16) width = 16;
            if (width > 4096) width = 4096;
            byte[] data = readRange(f, off, width);
            long next = off + data.length;
            sb.append("模式     : hex dump（每行 16 字节）\n");
            sb.append("本次区间 : ").append(off).append(" - ").append(next)
              .append("（").append(data.length).append(" 字节）\n");
            if (next < total) {
                sb.append("还有更多 : 是 → read_artifact {\"path\":\"").append(f.getAbsolutePath())
                  .append("\",\"hex\":true,\"offset\":").append(next).append("}\n");
            } else {
                sb.append("还有更多 : 否（已读到末尾）\n");
            }
            sb.append("------------------------------------------------------------\n");
            sb.append(hexDump(data, off));
            sb.append("------------------------------------------------------------\n");
            sb.append("提示：核对补丁时先看 read_artifact 的 offset，找那一行的 16 进制字节即可。\n");
            return sb.toString();
        }

        // 文本 + 地址锚点：定位含该地址的行，带上下文
        if (addr.length() > 0) {
            long scanFrom = off;
            int scanLen = a.optInt("scan_bytes", 4 << 20);
            if (scanLen < 4096) scanLen = 4096;
            if (scanLen > (16 << 20)) scanLen = 16 << 20;
            byte[] data = readRange(f, scanFrom, scanLen);
            String[] lines = new String(data, UTF8).split("\n", -1);
            int hit = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].indexOf(addr) >= 0
                        || lines[i].toLowerCase(Locale.US).indexOf(addr.toLowerCase(Locale.US)) >= 0) {
                    hit = i;
                    break;
                }
            }
            sb.append("模式     : 地址锚点 + 上下文（锚点 \"").append(addr)
              .append("\"，前后各 ").append(context).append(" 行）\n");
            sb.append("------------------------------------------------------------\n");
            if (hit < 0) {
                sb.append("  （没找到含 \"").append(addr).append("\" 的行）\n");
                sb.append("\n  说明：符号表里的地址列形如 0x970b94（不带前导零）；\n");
                sb.append("        若地址在文件的更后面，加大 scan_bytes 或先用 quote 试关键字；\n");
                sb.append("        二进制文件（.so/.dex）请改用 hex=true + addr（按文件偏移 dump）。\n");
                return sb.toString();
            }
            int from = Math.max(0, hit - context);
            int to = Math.min(lines.length - 1, hit + context);
            for (int i = from; i <= to; i++) {
                sb.append(i == hit ? "▶ " : "  ").append(lines[i]).append("\n");
            }
            sb.append("------------------------------------------------------------\n");
            sb.append("命中行号（本次扫描窗口内）: ").append(hit)
              .append("｜窗口 ").append(from).append("-").append(to)
              .append("｜扫描起点 ").append(scanFrom).append(" 起 ").append(data.length).append(" 字节\n");
            return sb.toString();
        }

        if (quote.length() == 0) {
            byte[] data = readRange(f, off, max);
            long next = off + data.length;
            sb.append("模式     : 文本（UTF-8）\n");
            sb.append("本次区间 : ").append(off).append(" - ").append(next)
              .append("（").append(data.length).append(" 字节）\n");
            if (next < total) {
                sb.append("还有更多 : 是 → read_artifact {\"path\":\"").append(f.getAbsolutePath())
                  .append("\",\"offset\":").append(next).append("}\n");
            } else {
                sb.append("还有更多 : 否（已读到末尾）\n");
            }
            if (looksBinary(data)) {
                sb.append("注意     : 这段内容像**二进制**（含大量 0x00），按文本读会是乱码。\n");
                sb.append("           核对字节请加 hex=true：read_artifact {\"path\":\"")
                  .append(f.getAbsolutePath()).append("\",\"hex\":true,\"offset\":").append(off)
                  .append("}\n");
            }
            sb.append("------------------------------------------------------------\n");
            sb.append(new String(data, UTF8));
            return sb.toString();
        }

        // 带 quote：在有限窗口内按行过滤（几万行的符号表里精确定位用），
        // context > 0 时给出每个命中点的上下文行。
        byte[] data = readRange(f, off, Math.min(4 << 20, max * 200));
        String[] lines = new String(data, UTF8).split("\n", -1);
        sb.append("过滤     : 含 \"").append(quote).append("\" 的行");
        if (context > 0) sb.append("（每个命中点带前后 ").append(context).append(" 行上下文）");
        sb.append("\n");
        sb.append("------------------------------------------------------------\n");
        int hits = 0, used = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() == 0) continue;
            if (line.toLowerCase(Locale.US).indexOf(quote) < 0) continue;
            hits++;
            int from = Math.max(0, i - context);
            int to = Math.min(lines.length - 1, i + context);
            if (context == 0) {
                if (used + line.length() + 1 > max) { sb.append("…（命中过多，已截断）\n"); break; }
                sb.append(line).append("\n");
                used += line.length() + 1;
            } else {
                for (int k = from; k <= to; k++) {
                    if (used + lines[k].length() + 1 > max) { sb.append("…（命中过多，已截断）\n"); return sb.toString(); }
                    sb.append(k == i ? "▶ " : "  ").append(lines[k]).append("\n");
                    used += lines[k].length() + 1;
                }
                sb.append("  --\n");
                used += 4;
                if (used > max) { sb.append("…（命中过多，已截断）\n"); break; }
            }
        }
        sb.append("------------------------------------------------------------\n");
        sb.append("命中 ").append(hits).append(" 行");
        if (hits == 0) sb.append("（换个关键字试试；符号表里的包名形如 package:hgdj/…）");
        sb.append("\n");
        return sb.toString();
    }

    private String toolDartSymbols(JSONObject a) {
        String given = a.optString("file", "");
        String ppDir = a.optString("pp_dir", "");
        File tsv;
        if (given.length() > 0) {
            tsv = new File(given);
            if (!tsv.isFile()) return "!! 找不到符号文件: " + given;
        } else if (ppDir.length() > 0) {
            // 直接指向 blutter_out 目录（blutter_run 返回的 out_dir），不做全量扫描
            tsv = new File(ppDir, PpDigest.TSV_NAME);
            if (!tsv.isFile()) {
                // 兼容 relaxed 子目录结构
                File relaxed = new File(ppDir, "relaxed");
                tsv = new File(relaxed, PpDigest.TSV_NAME);
                if (!tsv.isFile()) {
                    return "!! 找不到符号文件: " + tsv.getAbsolutePath()
                            + "\n   blutter_out 目录下没有 dart_symbols.tsv，"
                            + "   先用 list_artifacts 看产物结构，或用 file 参数直接指定路径";
                }
            }
        } else {
            tsv = newestSymbols();
            if (tsv == null) {
                return "（还没有 Dart 符号索引）\n"
                        + "   先跑 blutter_run（或界面里直接选 APK 分析 —— 会自动做符号还原），"
                        + "成功后会在工作目录的 blutter_out/ 下生成 " + PpDigest.TSV_NAME + "。\n"
                        + "   也可以用 list_artifacts 看当前有哪些产物。\n"
                        + "   或者用 pp_dir 参数直接指定 blutter_out 目录（blutter_run 结果里有）";
            }
        }
        String pkg = a.optString("pkg", "");
        String q = a.optString("q", "").toLowerCase(Locale.US);
        int limit = a.optInt("limit", 50);
        if (limit <= 0) limit = 50;
        if (limit > 500) limit = 500;
        boolean dedup = "true".equalsIgnoreCase(a.optString("dedup", "false"));

        // 多关键字：q / pkg 支持 "a|b|c" 一次命中多个。
        // 以前为了枚举 vip / needVip / CloudVip / limit / AppVip 要连查 5 次，
        // 每次一个来回，光往返就把上下文耗掉了。
        String[] qs = splitKeywords(q);
        String[] pkgs = splitKeywords(pkg.toLowerCase(Locale.US));
        String[] prefix = new String[pkgs.length];
        for (int i = 0; i < pkgs.length; i++) prefix[i] = "package:" + pkgs[i] + "/";

        int total = 0, matched = 0, shown = 0;
        java.util.LinkedHashMap<String, String> dedupMap = new java.util.LinkedHashMap<String, String>();
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(tsv), UTF8), 1 << 16);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                int c1 = line.indexOf('\t');
                if (c1 < 0) continue;
                int c2 = line.indexOf('\t', c1 + 1);
                if (c2 < 0) continue;
                total++;
                String addr = line.substring(0, c1);
                String path = line.substring(c1 + 1, c2);
                String name = line.substring(c2 + 1);
                if (prefix.length > 0 && !matchesAnyPrefix(path, prefix)) continue;
                if (qs.length > 0 && !containsAny(path, qs) && !containsAny(name, qs)) continue;
                matched++;
                if (dedup) {
                    // 按 addr+name 去重（不同对象池偏移会重复引用同一方法）
                    String key = addr + "\t" + name;
                    if (dedupMap.containsKey(key)) continue;
                    dedupMap.put(key, addr + "  " + path + "  " + name);
                } else if (shown < limit) {
                    sb.append("  ").append(addr).append("  ").append(path).append("  ").append(name).append("\n");
                    shown++;
                }
            }
        } catch (Exception e) {
            return "!! 读符号表失败: " + e;
        } finally {
            AnalysisLog.close(br);
        }

        // 处理 dedup 结果
        if (dedup) {
            int dedupCount = dedupMap.size();
            StringBuilder dedupSb = new StringBuilder();
            for (String entry : dedupMap.values()) {
                if (shown < limit) {
                    dedupSb.append("  ").append(entry).append("\n");
                    shown++;
                }
            }
            sb = dedupSb;
            total = dedupCount; // 去重后的总数
        }

        StringBuilder head = new StringBuilder();
        head.append("■ Dart 符号索引  ").append(tsv.getAbsolutePath()).append("\n");
        head.append("  总条目 ").append(dedup ? dedupMap.size() : total);
        if (pkgs.length > 0) {
            head.append("  ｜ 包 ").append(pkg);
            if (pkgs.length > 1) head.append("（").append(pkgs.length).append(" 个，或关系）");
        }
        if (qs.length > 0) {
            head.append("  ｜ 关键字 ").append(q);
            if (qs.length > 1) head.append("（").append(qs.length).append(" 个，或关系）");
        }
        if (dedup) head.append("  ｜ 去重：按(地址+方法名)");
        head.append("  ｜ 命中 ").append(dedup ? shown : matched)
            .append(shown < (dedup ? shown : matched) ? "" : "").append("\n");

        // 版本匹配标记
        head.append("  ⚠ 注意：Dart 符号还原结果基于 blutter 内置版本的对象池偏移，\n");
        head.append("     如目标 so 的 Dart 版本与内置 blutter 不完全一致，偏移可能有细微偏差\n");
        head.append("     （blutter_run 结果里的「是否精确版本匹配 / 是否放宽」说明可进一步确认）\n");

        head.append("  用法 : 拿地址回 r2/IDA 命名 sub_xxxx；改 pkg/q 继续筛；dedup=true 可去重\n");
        head.append("------------------------------------------------------------\n");
        if (matched == 0) {
            head.append("  （无命中）");
            if (total == 0) head.append(" —— 该文件里没有符号条目，可能解析失败（看 dart_symbols.txt 摘要）");
            head.append("\n");
            return head.toString();
        }
        return head.append(sb).toString();
    }

    private String toolWriteNote(JSONObject a) throws Exception {
        String section = a.optString("section", "NOTE");
        String text = req(a, "text");
        AnalysisLog.Block b = log().append(AnalysisLog.ANALYSIS, section, text);
        return "已写入日志:\n" + b.describe();
    }

    /** 在 sb 里追加一个日志块的可读区间说明；block 为 null 则不追加 */
    private static void appendBlock(StringBuilder sb, String title, AnalysisLog.Block block) {
        if (block == null) return;
        sb.append("[").append(title).append("]\n");
        sb.append(block.describe()).append("\n");
    }

    /** 按子串过滤行（忽略大小写），保留命中行并加过滤说明 */
    private static String filterLines(String text, String needle) {
        if (text == null) return "";
        String n = needle.toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder();
        String[] ls = text.split("\n", -1);
        int hit = 0;
        for (int i = 0; i < ls.length; i++) {
            if (ls[i].toLowerCase(Locale.US).indexOf(n) >= 0) {
                sb.append(ls[i]).append("\n");
                hit++;
            }
        }
        return "过滤条件: 含 \"" + needle + "\"，命中 " + hit + " 行 / 共 " + ls.length + " 行\n\n" + sb;
    }

    private static String pad(String s, int w) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder(s);
        while (b.length() < w) b.append(' ');
        return b.toString();
    }

    private String req(JSONObject a, String k) {
        if (!a.has(k) || a.optString(k, "").isEmpty())
            throw new IllegalArgumentException("缺少参数: " + k);
        return a.optString(k);
    }

    // ================= 工具清单（只读分析 + 日志） =================

    private JSONArray buildTools() throws JSONException {
        JSONArray t = new JSONArray();
        t.put(tool("service_status", "服务健康探针：端口/运行时/工作目录/日志/后台任务一览，**永远秒回**。"
                        + "排障第一步——它超时说明进程或端口层面有问题；它秒回而别的工具超时，"
                        + "说明是那个工具的重活慢，改用异步 job",
                opt("_noop", "string", "无需参数")));
        t.put(tool("job_result", "取后台任务结果：完成后返回完整结果，未完成则告知已跑多少秒。"
                        + "配合 analyze_file（默认异步）使用，避免客户端 socket 挂到超时",
                prop("job_id", "string", "提交任务时返回的 job_id")));
        t.put(tool("job_status", "查后台任务状态（不含完整结果，更快）",
                prop("job_id", "string", "提交任务时返回的 job_id")));
        t.put(tool("job_list", "列出最近的后台任务及其状态",
                opt("_noop", "string", "无需参数")));
        t.put(tool("list_artifacts", "列出分析产物文件（**不是日志**，read_log 读不到）。"
                        + "按优先级，定位 Dart 代码真正靠的是这两样："
                        + "① dart_symbols.tsv（地址↔方法名索引，先拿它定位 VA）；"
                        + "② asm/**/*.dart（blutter 按源文件组织的带注释反汇编，用它读懂逻辑）。"
                        + "pp.txt 只是 blutter 的原始对象池 dump，是上面两者的上游原料，一般不直接读。"
                        + "默认递归 8 层，asm/ 下的几百个 .dart 也能列出；文件多时用 filter 缩小范围。"
                        + "**默认排除二进制产物**（.so/.dex/.apk/图片/字体）—— filter=diary 曾经命中 97 个"
                        + "文件、里面混着一堆 .so，要找的 dart 源文件反而被淹掉；要看二进制用 binary=include",
                opt("dir", "string", "目录（默认分析工作目录）"),
                opt("filter", "string", "过滤关键字（大小写不敏感），如 util.dart 或 utils/"),
                opt("exact", "string", "true=filter 按**文件名**精确匹配（不看路径），默认 false=路径子串"),
                opt("under", "string", "只在路径含该子串的目录下找，如 under=\"asm\" 只看反汇编目录"),
                opt("suffix", "string", "只看该后缀，如 suffix=\".dart\"、suffix=\"tsv\""),
                opt("binary", "string", "exclude(默认，挡掉二进制) | include(一起列) | only(只看二进制)"),
                opt("per_dir", "string", "每个目录最多列几个，默认 12，上限 500"),
                opt("depth", "number", "递归深度，默认 8（asm/ 需要 7 层），范围 1-12")));
        t.put(tool("read_artifact", "按路径分页读取**产物文件**（只读）。用来读 dart_symbols.tsv / pp.txt / asm/*.dart ——"
                        + "这些都是产物不是日志，read_log 读不到。"
                        + "**addr=0x970b94 可直接按地址锚点定位**：给出含该地址的行，并带前后 context 行上下文"
                        + "（只看命中行看不到连续指令）；quote 也支持 context 给上下文。"
                        + "二进制（改过的 .so）加 hex=true 走 hexdump；hex=true + addr 则把 addr 当文件偏移 dump。"
                        + "读类调用在服务端串行排队（避免并发互相拖慢），请逐个调用",
                prop("path", "string", "产物文件绝对路径（先用 list_artifacts 拿）"),
                opt("addr", "string", "地址锚点：文本模式=含该地址的行 + 上下文；hex=true 时=按文件偏移 dump"),
                opt("context", "string", "上下文行数（addr 默认 25，quote 默认 0），上限 500"),
                opt("offset", "string", "起始字节偏移，默认 0；支持 0x 十六进制"),
                opt("max_bytes", "number", "本次最多读多少字节，默认 20000，上限 200000"),
                opt("quote", "string", "只返回包含该子串的行（大小写不敏感）"),
                opt("hex", "string", "true=十六进制 dump（读二进制必须加，否则是一堆 \\u0000）"),
                opt("width", "string", "hex 模式读多少字节，默认 512"),
                opt("pre", "string", "hex+addr 时锚点前再显示多少字节，默认 16")));
        t.put(tool("dart_symbols", "查询 Dart 符号索引（dart_symbols.tsv）：按 Dart 包名 / 方法·类名过滤，"
                        + "返回「函数地址 ↔ Dart 源文件 ↔ Class::method」。"
                        + "**q 支持多关键字一次命中**：q=\"vip|needVip|CloudVip|limit|AppVip\" —— "
                        + "枚举一类符号不必连查 5 次（每次一个来回）。pkg 同样支持 a|b|c。"
                        + "dedup=true 可按(地址+方法名)去重（不同对象池偏移会重复引用同一方法）。"
                        + "结果附版本匹配说明（精确/放宽），放宽时偏移可能有偏差需人工核对。"
                        + "拿到地址后用 read_so_bytes 可回看该地址的原始字节（确认是不是函数入口）",
                opt("file", "string", "dart_symbols.tsv 完整路径（最高优先级，直接读这个文件）"),
                opt("pp_dir", "string", "blutter_out 目录（blutter_run 结果里的 out_dir），"
                        + "直接读该目录下的 tsv，不做全量扫描，效率最高"),
                opt("pkg", "string", "只保留这些 Dart 包（如 hgdj、flutter），支持 a|b|c"),
                opt("q", "string", "方法名/类名关键字（大小写不敏感），支持 a|b|c 多关键字或关系"),
                opt("dedup", "string", "true=按(地址+方法名)去重，默认 false"),
                opt("limit", "number", "最多返回条数，默认 50，上限 500")));
        t.put(tool("analyze_file", "一键完整分析：包级画像（结构/签名/Manifest/Flutter版本）"
                        + " + 解包并分析**逆向用得到的**内部 .so（默认只分析 libapp.so/libflutter.so 与"
                        + "命中加固·授权·加密·网络·风控关键词的第三方库，媒体/图形/压缩等一律跳过并在报告里列出原因）。"
                        + "结果全部写入 analysis.log，返回日志区间；耗时约 1-3 分钟。"
                        + "**默认异步**：提交后立即返回 job_id，用 job_result 轮询取结果（避免客户端挂到超时）；"
                        + "想同步等结果就传 async=false（仅限小文件）",
                prop("path", "string", "APK 或 .so 的绝对路径"),
                opt("native", "string", "是否分析内部 .so，默认 true"),
                opt("deep", "string", "是否做深度函数分析 aa，默认 false（较慢，主库约 20-60 秒）"),
                opt("async", "string", "是否异步，默认 true（返回 job_id，配合 job_result 取结果）"),
                opt("extra_libs", "string", "额外要分析的 .so 文件名，逗号分隔（默认策略跳过它们时用）")));
        t.put(tool("patch_so", "对 .so（ELF）或任意二进制文件做字节级补丁，用于注入 NOP、修改指令、"
                        + "改跳转等。**支持直接传 va（虚拟地址）**：逆向拿到的函数地址（dart_symbols / "
                        + "IDA / 反汇编）都是 VA，本工具会按 ELF 节表自动换算成文件偏移再写入。"
                        + "**一次可改多处**（patches 数组，同一文件只开一次、全部偏移先校验、统一落盘，"
                        + "不会留下改了一半的 so）；**写前可预演**（peek=true 只回显当前字节不写入）。"
                        + "输出含每处的写前/写后字节与「像不像函数入口」的字节判读（stp x29,x30 / sub sp,sp），"
                        + "无 asm 产物时也能自检地址。不修改原文件时请先用 copy_to_input 之类留副本",
                prop("path", "string", "目标 .so 或二进制文件路径（必须可写）"),
                opt("patches", "string", "批量补丁（JSON 数组）：[{\"va\":\"0x970b94\",\"hex\":\"...\"},"
                        + "{\"offset\":\"0x1234\",\"hex\":\"...\"}]。给了它就忽略 va/offset/hex"),
                opt("hex", "string", "要写入的十六进制字节串（单处模式必填），空格可选，"
                        + "如 B800000000 或 B8 00 00 00 00"),
                opt("va", "string", "虚拟地址（如 0x8b7798），自动换算为文件偏移；与 offset 二选一"),
                opt("offset", "string", "直接给文件偏移，支持十进制或 0x 十六进制；给了 va 则忽略此项"),
                opt("peek", "string", "true=只回显写前字节并给出写后预告，**不修改文件**（预演模式）"),
                opt("peek_bytes", "string", "预演/回显读多少字节，默认 16，范围 4-64")));
        t.put(tool("read_so_bytes", "按 VA / 文件偏移读任意文件的原始字节，返回 hex + ASCII dump，"
                        + "并粗判「这段字节像不像 ARM 函数入口」（stp x29,x30 / sub sp,sp / push {…,lr} / ret）。"
                        + "用途：改 so 前确认符号地址没写错、patch 前回显原字节 —— "
                        + "blutter 的 asm 只覆盖「生成了 asm 的 dart 文件」，没 asm 时不必再盲信地址。"
                        + "va 走 ELF 节表换算；非 ELF（dex/任意二进制）用 offset",
                prop("path", "string", "目标文件（.so / 任意二进制）绝对路径"),
                opt("va", "string", "虚拟地址（如 0x970b94），走 ELF 节表换算为文件偏移；与 offset 二选一"),
                opt("offset", "string", "文件偏移，支持十进制或 0x 十六进制；与 va 二选一"),
                opt("length", "string", "读取字节数，默认 256，上限 65536")));
        t.put(tool("sign_apk", "给 APK 重签名（v1 JAR + v2 + v3），闭合「改包 → 能装」最后一环。"
                        + "**手机内完成，不需要电脑、不需要 apksigner**（用包内自带的官方 apksig 库）。"
                        + "默认用内置 debug keystore（口令 android），也可用 ks_path 指定自己的 "
                        + ".keystore/.jks。输出含签名后校验（apksig 复核）+ .so 打包自检"
                        + "（读 manifest 的 extractNativeLibs 判定 STORED+4096 对齐 / DEFLATE 哪种合规）。"
                        + "replace_entry 默认已自动调用它，一般不用单独跑",
                prop("path", "string", "要签名的 APK 路径"),
                opt("out_apk", "string", "输出路径；不传则默认 out/<原名>_signed.apk"),
                opt("in_place", "string", "true=原地覆盖输入文件（默认 false，出到新文件）"),
                opt("ks_path", "string", "自定义 keystore 路径（.keystore/.jks）；不传用内置 debug keystore"),
                opt("ks_pass", "string", "keystore 口令，内置的为 android"),
                opt("key_alias", "string", "密钥别名，内置的为 revmcp；不传取第一个带私钥的条目"),
                opt("key_pass", "string", "私钥口令，不传则与 ks_pass 相同"),
                opt("v1", "string", "是否启用 v1(JAR) 签名，默认 true。若签名后报 .so 不对齐，"
                        + "改 false 走 v2-only 逐字节搬运路径"),
                opt("v2", "string", "是否启用 v2 签名，默认 true"),
                opt("v3", "string", "是否启用 v3 签名，默认 true"),
                opt("min_sdk", "string", "目标最低 SDK；不传由 apksig 自行判断")));
        t.put(tool("so_map_addr", "ELF 地址映射：列出各节的 VA→文件偏移区间，并可查询某个 VA 对应的文件偏移。"
                        + "改 so 前用它把 dart_symbols / 反汇编里的 VA 换算成 patch_so 需要的 offset",
                prop("path", "string", "目标 .so 路径"),
                opt("addr", "string", "要换算的地址（0x 十六进制），给了就同时输出映射结果")));
        t.put(tool("patch_file", "对任意文件在指定偏移写入十六进制字节，用于打补丁、替换常量等。"
                        + "hex 支持空格分隔，offset 支持 0x 前缀。修改前请备份原文件",
                prop("path", "string", "目标文件路径（必须可写）"),
                prop("offset", "string", "写入起始偏移，支持 0x 十六进制"),
                prop("hex", "string", "十六进制字节串")));
        t.put(tool("replace_entry", "替换 APK 内条目内容（不改原 APK，输出到新文件），用于把改好的 .so 打包回去。"
                        + "**未指定的条目原样搬运**：保留原压缩方式（STORED/DEFLATE）、时间戳与属性；"
                        + "lib/**/*.so 的压缩方式也**原样保留**，只给原本 STORED 的补 4096 字节对齐 —— "
                        + "extractNativeLibs=true 的包允许 DEFLATE（安装期解压，省体积），"
                        + "false 的包必须 STORED+对齐，两种模式都安全。"
                        + "写后会自动重读产物、逐条回报 .so 的压缩方式与对齐偏移作为证据。"
                        + "**默认顺手重签名**（sign=true，内置 debug keystore）—— 改包后原签名必然失效，"
                        + "不签就得到装不上的包，以前得回头开 MT 管理器手签；现在一步出可安装包。"
                        + "双 ABI 用 entries 数组一次替换（对齐只算一遍）",
                prop("apk_path", "string", "原 APK 路径"),
                opt("entry", "string", "APK 内条目名，如 lib/arm64-v8a/libapp.so（单条目时用）"),
                opt("new_file", "string", "新文件路径（替换内容来源；单条目时用）"),
                opt("entries", "string", "多条替换（JSON 数组）：[{\"entry\":\"lib/arm64-v8a/libapp.so\","
                        + "\"new_file\":\"/path/a.so\"},{\"entry\":\"lib/armeabi-v7a/libapp.so\","
                        + "\"new_file\":\"/path/b.so\"}]。给了它就忽略 entry/new_file"),
                opt("out_apk", "string", "输出 APK 完整路径；不传则默认写到产物根目录的 out/ 下"),
                opt("sign", "string", "是否顺手重签名，默认 true。false=出未签名包（之后用 sign_apk 补签）"),
                opt("v1", "string", "签名时是否启用 v1(JAR)，默认 true；签名后报 .so 不对齐时改 false"),
                opt("ks_path", "string", "签名用的自定义 keystore；不传用内置 debug keystore（口令 android）"),
                opt("ks_pass", "string", "keystore 口令，内置的为 android"),
                opt("key_alias", "string", "密钥别名，内置的为 revmcp"),
                opt("key_pass", "string", "私钥口令，不传与 ks_pass 相同"),
                opt("min_sdk", "string", "签名目标最低 SDK，不传由 apksig 判断")));
        t.put(tool("r2_doctor", "radare2 运行时自检：主程序/原生库/资源释放/试跑，用于排查 r2_* 工具不可用的原因",
                opt("path", "string", "忽略此参数，仅为兼容")));
        t.put(tool("r2_info", "radare2 概览：架构/位数/入口/段/导入导出/库依赖 (iI ie il)。完整结果写入日志",
                prop("path", "string", "目标 .so 或二进制路径"), forceOpt()));
        t.put(tool("r2_functions", "列出函数 (afl)，可按名称过滤。完整结果写入日志",
                prop("path", "string", "目标路径"), opt("filter", "string", "名称子串（可选）"), forceOpt()));
        t.put(tool("r2_disasm", "反汇编指定地址 (pd n @ addr)。完整结果写入日志",
                prop("path", "string", "目标路径"), prop("addr", "string", "地址，如 0x1234"),
                opt("n", "number", "反汇编条数，默认 64"), forceOpt()));
        t.put(tool("r2_decompile", "伪代码反编译 (pdc @ addr)。完整结果写入日志",
                prop("path", "string", "目标路径"), prop("addr", "string", "函数地址"), forceOpt()));
        t.put(tool("r2_strings", "提取字符串 (izz)，可过滤。完整结果写入日志",
                prop("path", "string", "目标路径"), opt("filter", "string", "子串（可选）"), forceOpt()));
        t.put(tool("r2_search", "搜索 string/hex/asm，定位修改点首选。完整结果写入日志",
                prop("path", "string", "目标路径"), prop("pattern", "string", "搜索内容"),
                opt("type", "string", "string|hex|asm，默认 string"), forceOpt()));
        t.put(tool("r2_symbols", "符号表 (is)。完整结果写入日志",
                prop("path", "string", "目标路径"), forceOpt()));
        t.put(tool("r2_cmd", "执行任意只读 r2 命令（高级用法）。完整结果写入日志",
                prop("path", "string", "目标路径"), prop("cmd", "string", "r2 命令，如 afl~main"), forceOpt()));
        t.put(tool("list_apk_entries", "列出 APK 内所有条目（名称/压缩方式/大小），支持名称过滤",
                prop("apk_path", "string", "APK 路径"), opt("filter", "string", "条目名子串（可选）")));
        t.put(tool("read_manifest", "解码二进制 AndroidManifest.xml，输出可读 XML（包名/权限/组件/uses-sdk）",
                prop("apk_path", "string", "APK 路径，或直接给 .xml(AXML) 文件")));
        t.put(tool("verify_apk", "校验 APK 签名（v1/v2/v3/v4）并输出签名者证书与 SHA-256。"
                        + "**会明确区分两种失败**：「签名已失效（预期）」= 改过包的正常结果，重签名即可；"
                        + "「结构损坏（真失败）」= ZIP 头/中央目录读不出来，产物真坏了",
                prop("apk_path", "string", "APK 路径")));
        t.put(tool("extract_entry", "导出 APK 内任意条目到目录（只读，不改动原 APK）。"
                        + "不传 out_dir 时默认落到产物根目录的 work/<包名>/patch/，"
                        + "和 analyze_file 的产物在同一棵树下 —— 这里的文件是磁盘普通文件，可随意 patch；"
                        + "要放回 APK 内请用 replace_entry",
                prop("apk_path", "string", "APK 路径"), prop("entry_name", "string", "条目全名"),
                opt("out_dir", "string", "输出目录；不传则默认 <产物根>/work/<包名>/patch/")));
        t.put(tool("unpack_apk_libs", "解包 APK 内所有 lib/<abi>/*.so 到目录（只读）",
                prop("apk_path", "string", "APK 路径"), prop("out_dir", "string", "输出目录")));
        t.put(tool("copy_to_input", "把宿主目录（MT管理器/sdcard）的 APK/so 复制到 RevMCP 可写的缓存目录（input/），"
                        + "解决 EACCES 权限问题。如果目标已在缓存目录则跳过。返回可直接用于后续工具的路径",
                prop("path", "string", "源文件绝对路径（如 /sdcard/MT2/xxx.apk）")));
        t.put(tool("list_copied_inputs", "列出已复制到 RevMCP 缓存目录（input/）的文件，便于找到之前已拉取的分析目标",
                opt("_noop", "string", "无需参数")));
                t.put(tool("blutter_run", "用内置的预编译 blutter 对 Flutter 的 libapp.so 做 Dart AOT 符号还原："
                        + "产出 pp.txt（函数地址↔源位置对应、类/对象池结构）与 asm/*.asm（带符号反汇编）。"
                        + "按目标 Dart 版本自动挑选匹配的 blutter 二进制（内置 2.14–3.12），"
                        + "并读二进制内嵌的真实版本做校正。结果写入 analysis.log，通常 20 秒–3 分钟",
                prop("apk_path", "string", "Flutter APK 路径（二选一）"),
                prop("so_path", "string", "libapp.so 路径（二选一，自动找同目录 libflutter.so）"),
                opt("timeout_sec", "string", "执行超时秒数，默认 300"),
                opt("pp_bytes", "string", "pp.txt 正文写入日志的字节数，默认 200000")));
        t.put(tool("blutter_doctor", "检查内置 blutter 运行时：列出可用版本（含二进制内嵌的真实 Dart 版本，"
                        + "注意文件名可能与之不符）以及 capstone/icu/libc++ 依赖是否齐备",
                opt("_noop", "string", "无需参数")));
        t.put(tool("analyze_so", "分析单个 .so：第 1 层纯 Java 解析 ELF（架构/段/节/符号/依赖/字符串/"
                        + "Dart 包路径），不依赖 radare2；给 deep=true 追加 radare2 深度函数分析。结果写入 analysis.log",
                prop("so_path", "string", "目标 .so 绝对路径"),
                opt("deep", "string", "是否追加 radare2 深度分析，默认 false")));
        t.put(tool("read_log", "读取分析日志（分页）。默认从 analysis.log 头部取 max_bytes 字节；给 lines 则读末尾 N 行",
                opt("which", "string", "analysis（默认）| server（HTTP 访问流水）"),
                opt("offset", "string", "起始字节偏移，默认 0；支持 0x 十六进制"),
                opt("max_bytes", "number", "本次最多读多少字节，默认 20000，上限 200000"),
                opt("lines", "number", "改为读末尾 N 行（优先于 offset）")));
        t.put(tool("write_note", "把结论或给其他工具的修改说明写进分析日志，便于追溯与交接",
                prop("text", "string", "要写入的内容"),
                opt("section", "string", "小节标题，默认 NOTE")));
        t.put(tool("list_workdirs", "列出所有分析产物目录（每个 APK/so 一个工作目录），用于导航"
                        + "到具体项目下再使用 list_artifacts / read_artifact / dart_symbols / read_pp_summary",
                opt("_noop", "string", "无需参数")));
        t.put(tool("list_workdir_artifacts", "浏览某个分析产物目录（默认工作目录）里的全部文件，"
                        + "支持传入 dir 参数切换到某个具体项目",
                opt("dir", "string", "产物目录（默认自动取 workDir）")));
        t.put(tool("read_pp_summary", "解析 pp.txt（blutter 对象池原始 dump），输出结构摘要。"
                        + "支持 pp_file 直接指定路径（最快）；pp_dir 指定 blutter_out 目录；"
                        + "都不传则自动找最近一次 blutter 产出的 pp.txt（全量扫描，较慢）",
                opt("pp_file", "string", "pp.txt 的完整路径（最高优先级，直接解析）"),
                opt("pp_dir", "string", "blutter_out 目录路径（读其下的 pp.txt）")));
        return t;
    }

    /** 可选参数（不进 required） */
    private JSONObject opt(String k, String type, String desc) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("__k", k);
        o.put("__v", new JSONObject().put("type", type).put("description", desc));
        o.put("__req", false);
        return o;
    }

    private JSONObject tool(String name, String desc, JSONObject... props) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("description", desc);
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        JSONObject p = new JSONObject();
        for (JSONObject pr : props) p.put(pr.getString("__k"), pr.get("__v"));
        schema.put("properties", p);
        JSONArray req = new JSONArray();
        for (JSONObject pr : props) if (pr.optBoolean("__req", false)) req.put(pr.getString("__k"));
        schema.put("required", req);
        o.put("inputSchema", schema);
        return o;
    }

    private JSONObject prop(String k, String type, String desc) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("__k", k);
        o.put("__v", new JSONObject().put("type", type).put("description", desc));
        o.put("__req", true);
        return o;
    }

    /**
     * r2_* 工具共用的「跳过 8MB 大文件预检」开关。
     * 预检只是性能保护（手机端跑大 .so 要 3-10 分钟甚至卡死），文件本身没坏；
     * 想强行跑就带 {@code force=true}，跳过后该慢还是慢，但不会再被拦。
     */
    private JSONObject forceOpt() throws JSONException {
        return opt("force", "string",
                "设为 true 跳过 8MB 大文件预检（预检只是性能保护，文件没坏；"
                        + "跳过后在手机上可能要 3-10 分钟甚至卡死）");
    }
}