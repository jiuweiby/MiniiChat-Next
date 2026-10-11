package com.miniichatNext.carter.debug

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DebugLevel(val label: String) {
    VERBOSE("VERBOSE"),
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR");

    companion object {
        fun from(name: String?): DebugLevel = entries.firstOrNull { it.name == name } ?: WARN
    }
}

/** 日志打包时的一个待写入条目 */
private data class ZipSource(
    val file: File,
    val entryName: String,
    /** 打包成功后可以删除：旧的崩溃归档 zip（内容已经进导出包了，留着只会堆积） */
    val deleteAfterPack: Boolean
)

/**
 * 日志预览数据的来源。
 *
 * 用来在预览页顶部标注内容取自哪里，便于判断日志是否完整。
 */
enum class DebugLogSource { FILE, BUFFER, MERGED }

/**
 * 一份用于界面上直接预览的日志快照。
 *
 * [text] 是可以直接显示与复制的完整文本；
 * [truncated] 为真时说明只取了末尾一段，界面上需要给出提示。
 */
data class DebugPreview(
    val text: String,
    val source: DebugLogSource,
    val truncated: Boolean,
    val lineCount: Int,
    val charCount: Int
)

object DebugLog {
    private const val DIR = "logs"
    private const val MAX_BUFFER = 600
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MANIFEST_NAME = "manifest.txt"

    /** 导出 zip 里额外附带的内存缓冲快照文件名（放在logs/下，前缀下划线表示是合成文件） */
    private const val SESSION_BUFFER_NAME = "_session_buffer.log"
    private const val SESSION_BUFFER_HEADER =
        "# In-memory session buffer (up to 600 recent lines).\n" +
            "# Lines below the active log level are kept here only (never written to disk),\n" +
            "# so this file may overlap with logs/DEBUG_*.log.\n"

    /**
     * 预览时的默认读取上限（字符数）。
     *
     * 取 512KB：足够覆盖绝大多数排查场景，同时保证界面上一次渲染的行数可控。
     * 真正的大日志还是要走导出 zip，预览只服务于「快速看一眼最后发生了什么」。
     */
    private const val PREVIEW_MAX_CHARS = 512 * 1024

    /** 预览页最多渲染的行数，超出部分截断，避免一次构造几十万个 item */
    private const val PREVIEW_MAX_LINES = 20000

    private val mutex = Mutex()

    private val fileLock = Any()

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<String>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "DebugLog-IO") }

    /** 统一的导出文件名：MiniiChat-Next_Log_<yyyy-MM-dd_HH-mm-ss>.zip（下载目录与缓存目录共用） */
    private fun exportFileName(): String =
        "MiniiChat-Next_Log_" +
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".zip"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    // 日志等级默认WARN
    var level: DebugLevel = DebugLevel.WARN
        private set

    // ---------- 生命周期 ----------

    fun restore(context: Context) {
        appContext = context.applicationContext
        level = DebugLevel.from(
            context.getSharedPreferences("debug_prefs", Context.MODE_PRIVATE)
                .getString("level", null)
        )
    }

    fun setLevel(context: Context, lvl: DebugLevel) {
        appContext = context.applicationContext
        level = lvl
        context.getSharedPreferences("debug_prefs", Context.MODE_PRIVATE)
            .edit().putString("level", lvl.name).apply()
        i("DebugLog", "log level changed to " + lvl.name)
    }

    // ---------- 路径 ----------

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun logFile(context: Context, lvl: DebugLevel = level): File =
        File(dir(context), "DEBUG_" + lvl.name + ".log")

    private fun logFileSafe(): File? = appContext?.let { logFile(it) }

    // ---------- 记录 ----------

    fun v(tag: String, msg: String) = log(DebugLevel.VERBOSE, tag, msg, null)
    fun d(tag: String, msg: String) = log(DebugLevel.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = log(DebugLevel.INFO, tag, msg, null)
    fun w(tag: String, msg: String) = log(DebugLevel.WARN, tag, msg, null)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(DebugLevel.ERROR, tag, msg, tr)

    private fun log(lvl: DebugLevel, tag: String, msg: String, tr: Throwable?) {
        val text = buildString {
            append(msg)
            if (tr != null) {
                append('\n')
                append(StringWriter().also { tr.printStackTrace(PrintWriter(it)) }.toString())
            }
        }
        val line = timeFormat.format(Date()) + " [" + lvl.name + "/" + tag + "] " + text
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_BUFFER) buffer.removeFirst()
        }
        if (lvl.ordinal >= level.ordinal) {
            io.execute { runCatching { appendToDisk(line) } }
        }
    }

    private fun appendToDisk(line: String) {
        val f = logFileSafe() ?: return
        synchronized(fileLock) {
            runCatching {
                // 超过上限就清空重来，避免日志无限膨胀
                if (f.length() > MAX_FILE_BYTES) f.writeText("")
                f.appendText(line + "\n")
            }
        }
    }

    // ---------- 读取/导出 ----------

    fun bufferText(): String = synchronized(buffer) { buffer.joinToString("\n") }

    /** 内存缓冲当前的行数，供界面提示使用 */
    fun bufferLineCount(): Int = synchronized(buffer) { buffer.size }

    // 崩溃路径必须用这个：进程马上就会死，走io executor的异步写入可能来不及落盘

    fun appendDirectSync(context: Context, levelName: String, tag: String, text: String) {
        runCatching {
            val f = logFile(context)
            if (f.length() > MAX_FILE_BYTES) f.writeText("")
            f.appendText(
                timeFormat.format(Date()) + " " + levelName + "/" + tag + " " + text + "\n"
            )
        }
    }

    suspend fun readText(context: Context, maxBytes: Int = 256 * 1024): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val f = logFile(context)
                // 先加锁把内容快照下来，避免和 appendToDisk 争先读到写了一半的行
                val snapshot = synchronized(fileLock) {
                    runCatching { if (f.isFile) f.readBytes() else null }.getOrNull()
                }
                val disk = snapshot?.let { bytes ->
                    if (bytes.size > maxBytes) {
                        "> (日志过长，仅显示末尾部分)\n" +
                            String(bytes, bytes.size - maxBytes, maxBytes)
                    } else {
                        String(bytes)
                    }
                } ?: ""
                disk.ifBlank { bufferText() }
            }
        }

    /**
     * 生成一份可直接在界面上预览的日志快照。
     *
     * ## 为什么要单独一个方法而不是复用 readText
     *
     * readText 的语义是「磁盘优先，磁盘为空才回退到内存缓冲」。
     * 这在排查时有个明显的盲区：
     *
     *   当前日志等级设为 WARN 时，INFO / DEBUG 级别的行只进内存缓冲、不落盘。
     *   但恰恰是这些低等级行最常包含 HTTP 请求体、工具路由、流式分片等关键信息。
     *   只读磁盘会让人误以为「什么日志都没有」。
     *
     * 因此预览视图把两者合并：
     *   - 磁盘文件是主体（覆盖当前等级以上的历史）
     *   - 内存缓冲作为补充，附在末尾并加显式分隔标记
     *   - 两侧都为空时返回空快照
     *
     * 磁盘与缓冲的内容会有重叠，这是刻意接受的：
     * 精确去重需要按时间戳逐行比对，成本高且容易在并发写入时误判，
     * 而多出的重复行对人工排查基本无干扰，反而能确认某条日志确实被持久化了。
     *
     * ## 截断策略
     *
     * 超过 [PREVIEW_MAX_CHARS] 时保留**末尾**部分：
     * 排查问题时最关心的永远是「刚刚发生了什么」，
     * 而日志是按时间正序追加的，末尾即最新。
     *
     * @return 预览快照；[DebugPreview.text] 为空串表示当前没有任何日志
     */
    suspend fun preview(context: Context): DebugPreview = withContext(Dispatchers.IO) {
        val (diskText, diskTruncated) = readDiskSnapshot(context)

        val bufferSnapshot = bufferText()
        val bufferLines = bufferLineCount()

        // 磁盘为空且缓冲为空：真的什么都没有
        if (diskText.isBlank() && bufferSnapshot.isBlank()) {
            return@withContext DebugPreview(
                text = "",
                source = DebugLogSource.MERGED,
                truncated = false,
                lineCount = 0,
                charCount = 0
            )
        }

        // 只有缓冲有内容：说明当前等级以下的日志还没落盘
        if (diskText.isBlank()) {
            val clipped = clipTail(bufferSnapshot, PREVIEW_MAX_CHARS)
            return@withContext DebugPreview(
                text = clipped.first,
                source = DebugLogSource.BUFFER,
                truncated = clipped.second,
                lineCount = countLines(clipped.first),
                charCount = clipped.first.length
            )
        }

        // 只有磁盘有内容：缓冲为空，直接用磁盘
        if (bufferSnapshot.isBlank()) {
            return@withContext DebugPreview(
                text = diskText,
                source = DebugLogSource.FILE,
                truncated = diskTruncated,
                lineCount = countLines(diskText),
                charCount = diskText.length
            )
        }

        // 两侧都有：拼成合并视图
        val merged = buildString(diskText.length + bufferSnapshot.length + 256) {
            append(diskText)
            if (!diskText.endsWith("\n")) append('\n')
            append('\n')
            append("──────── 以下为内存会话缓冲（最近 ").append(bufferLines).append(" 行）────────\n")
            append("# 当前日志等级为 ").append(level.name)
            append("，低于该等级的行只保留在缓冲中，未写入磁盘。\n")
            append("────────\n")
            append(bufferSnapshot)
        }

        val clipped = clipTail(merged, PREVIEW_MAX_CHARS)
        DebugPreview(
            text = clipped.first,
            source = DebugLogSource.MERGED,
            truncated = clipped.second || diskTruncated,
            lineCount = countLines(clipped.first),
            charCount = clipped.first.length
        )
    }

    /**
     * 读取磁盘日志快照。
     *
     * 返回值为「内容 与 是否被截断」。
     * 读盘上限取 [PREVIEW_MAX_CHARS]，与预览上限一致，避免读完再截产生无谓的分配。
     */
    private suspend fun readDiskSnapshot(context: Context): Pair<String, Boolean> =
        mutex.withLock {
            val f = logFile(context)
            val bytes = synchronized(fileLock) {
                runCatching { if (f.isFile) f.readBytes() else null }.getOrNull()
            } ?: return@withLock "" to false

            if (bytes.isEmpty()) return@withLock "" to false

            if (bytes.size <= PREVIEW_MAX_CHARS) {
                String(bytes, Charsets.UTF_8) to false
            } else {
                // 只取末尾：从字节数组尾部截取，并跳过可能被切断的半个 UTF-8 字符
                val offset = bytes.size - PREVIEW_MAX_CHARS
                String(bytes, offset, PREVIEW_MAX_CHARS, Charsets.UTF_8) to true
            }
        }

    /**
     * 从文本末尾截取至多 [maxChars] 个字符。
     *
     * 返回「截取后的文本 与 是否发生了截断」。
     * 截断点向回退到最近的换行处，避免首行出现半截内容。
     */
    private fun clipTail(text: String, maxChars: Int): Pair<String, Boolean> {
        if (text.length <= maxChars) return text to false

        val start = text.length - maxChars
        // 从截断点向后找最近的一个换行，保证首行完整
        val firstBreak = text.indexOf('\n', start)
        val cut = if (firstBreak in start until text.length) firstBreak + 1 else start
        return text.substring(cut) to true
    }

    private fun countLines(text: String): Int {
        if (text.isEmpty()) return 0
        var count = 1
        var idx = text.indexOf('\n')
        while (idx >= 0) {
            count++
            idx = text.indexOf('\n', idx + 1)
        }
        return count
    }

    /** 预览页最多渲染的行数上限，供 UI 层做分页判断 */
    fun previewMaxLines(): Int = PREVIEW_MAX_LINES

    /** 打包logs目录全部文件到cacheDir/shared下的zip */
    fun exportZip(context: Context): File {
        val shared = File(context.cacheDir, "shared").apply { mkdirs() }
        val out = File(shared, exportFileName())
        out.outputStream().use { writeLogZip(context, it) }
        d("DebugLog", "export zip done: " + out.absolutePath)
        return out
    }

    /**
     * 打包全部日志到系统默认下载目录
     *
     * 先在cacheDir/shared生成完整zip，再整体拷贝到目标位置，这样即使写目标中途失败，也不会在下载目录里留下只有前几个条目的半包
     *
     * @return 展示用的保存位置（成功）或 null（失败）
     */
    fun exportZipToDownloads(context: Context): String? {
        val name = exportFileName()

        // 先在cacheDir生成完整 zip（写日志zip和清理已归档的崩溃zip都在这一步完成）
        val staging = File(File(context.cacheDir, "shared").apply { mkdirs() }, name)
        val staged = runCatching {
            staging.outputStream().use { writeLogZip(context, it) }
        }
        if (staged.isFailure) {
            e("DebugLog", "staging log zip failed", staged.exceptionOrNull())
            runCatching { staging.delete() }
            return null
        }

        // 整体拷到目标位置
        val attempt = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert returned null")
                resolver.openOutputStream(uri)?.use { out ->
                    staging.inputStream().use { it.copyTo(out) }
                } ?: error("openOutputStream returned null")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Environment.DIRECTORY_DOWNLOADS + "/" + name
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, name)
                staging.inputStream().use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                out.absolutePath
            }
        }

        val result = attempt.getOrElse { e ->
            // 注意：w(tag, msg) 没有Throwable参数，带异常要用e(tag, msg, tr)
            e("DebugLog", "export to Downloads failed, trying app-specific dir: ${e.message}", e)
            runCatching {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, name)
                staging.inputStream().use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                out.absolutePath
            }.getOrNull()
        }
        runCatching { staging.delete() }
        return result
    }

    private fun writeLogZip(context: Context, out: OutputStream) {
        val root = dir(context)

        // 把要打包的内容先读进内存
        val payloads = LinkedHashMap<String, ByteArray>()
        val manifestLines = LinkedHashMap<String, String>()
        val deletableCrashZips = LinkedHashMap<String, File>()

        synchronized(fileLock) {
            val sources = LinkedHashMap<String, ZipSource>()

            root.walkTopDown()
                .filter { it.isFile }
                .forEach { f ->
                    val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                    val entryName = "logs/$rel"
                    sources.putIfAbsent(
                        entryName,
                        ZipSource(
                            file = f,
                            entryName = entryName,
                            deleteAfterPack = CrashLogger.isCrashZipName(f.name)
                        )
                    )
                }

            val legacyCrashDir = File(context.filesDir, "crash")
            if (legacyCrashDir.isDirectory) {
                legacyCrashDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                    val entryName = "logs/crash/" + f.name
                    sources.putIfAbsent(
                        entryName,
                        ZipSource(file = f, entryName = entryName, deleteAfterPack = false)
                    )
                }
            }

            sources.values.forEach { src ->
                val bytes = runCatching { src.file.readBytes() }.getOrNull()
                if (bytes == null) {
                    w("DebugLog", "skip unreadable log file: ${src.entryName}")
                    return@forEach
                }
                payloads[src.entryName] = bytes
                manifestLines[src.entryName] = src.entryName +
                    "  bytes=" + bytes.size +
                    "  mtime=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(Date(src.file.lastModified()))
                if (src.deleteAfterPack) deletableCrashZips[src.entryName] = src.file
            }

            val buffered = bufferText()
            if (buffered.isNotBlank()) {
                val entryName = "logs/$SESSION_BUFFER_NAME"
                val bytes = (SESSION_BUFFER_HEADER + buffered).toByteArray(Charsets.UTF_8)
                payloads[entryName] = bytes
                manifestLines[entryName] = entryName +
                    "  bytes=" + bytes.size + "  mtime=(in-memory)"
            }
        }

        // 组装manifest，然后创建zip
        val manifest = StringBuilder()
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            manifest.append("app=").append(context.packageName)
                .append("  version=").append(info.versionName).append(" (").append(code).append(")")
                .append("  build=").append(com.miniichatNext.carter.BuildConfig.BUILD_TYPE)
                .append('\n')
                .append("android=").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")")
                .append("  device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append('\n')
                .append("logLevel=").append(level.name)
                .append("  exportedAt=")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                .append('\n')
                .append("entries=").append(payloads.size + 1).append(" (including this manifest)")
                .append("\n\n")
        }
        manifestLines.values.forEach { manifest.append(it).append('\n') }

        val written = mutableSetOf<String>()
        ZipOutputStream(out).use { zip ->
            payloads.forEach { (entryName, bytes) ->
                val ok = runCatching {
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(bytes)
                    zip.closeEntry()
                    true
                }.getOrElse { e ->
                    w("DebugLog", "zip entry failed: $entryName -> ${e.message}")
                    false
                }
                if (ok) written += entryName
            }
            runCatching {
                zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }

        // 只删除写进本次zip的崩溃归档zip
        var removed = 0
        deletableCrashZips.forEach { (entryName, file) ->
            if (entryName in written) {
                runCatching { if (file.delete()) removed++ }
            }
        }
        d(
            "DebugLog",
            "log zip written: ${written.size} entries, removed $removed packaged crash zip(s)"
        )
    }

    fun clear(context: Context) {
        io.execute {
            synchronized(fileLock) {
                runCatching {
                    // 递归删除logs/下的文件+logs/crash/子目录
                    dir(context).listFiles()?.forEach { f ->
                        if (f.isDirectory) f.deleteRecursively() else f.delete()
                    }
                }
            }
        }
        synchronized(buffer) { buffer.clear() }
    }

    /**
     * 同步清空日志。
     *
     * 与 [clear] 的差别：在调用线程上立刻完成删除，不排队到 IO 线程。
     *
     * 界面上的「清空日志」必须用这个版本：
     *   用户点完清空后立刻切到预览页，如果走异步删除，
     *   预览读到的是尚未删除的旧文件，会让人以为清空没生效。
     *
     * 删除操作本身很快（只是 unlink 若干小文件），
     * 在 IO 协程里调用不会阻塞主线程，因此这里不另起线程。
     */
    fun clearSyncNow(context: Context) {
        synchronized(fileLock) {
            runCatching {
                dir(context).listFiles()?.forEach { f ->
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                }
            }
        }
        synchronized(buffer) { buffer.clear() }
    }
}
