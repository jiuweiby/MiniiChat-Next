package com.miniichatNext.carter.data.mcp

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.miniichatNext.carter.debug.DebugLog
import com.rev.mcptool.DexParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 内置工具集。
 *
 * 设计原则：
 *  1. 文件操作一律以「整个文件」为单位读写，不提供按行号、按字节区间读写的能力。
 *  2. 所有工具默认开启（enabled=true），直接进入 AI 的可用工具清单；
 *     调用默认免审批。如需限制，可在 MCP 管理页关闭主开关，或打开「调用时需要用户审批」。
 *  3. 所有路径都经过 safeFile 收敛到允许的根目录，防止路径逃逸。
 *
 * 关于权限归属：
 *  内置工具的 [McpTool.serverId] 固定为空串，表示「不属于任何外部 MCP 服务器」。
 *  权限记录里内置工具对应的 [McpToolPermission.serverId] 固定为 [BUILTIN_PERMISSION_ID]。
 *  这两者刻意区分开：前者是工具定义的一部分，后者是权限归属的标记，
 *  混用会导致「权限配置写了 builtin，工具定义却是空串」这类隐性不一致。
 */
object BuiltInFileTools {

    /** 权限记录中内置工具的归属 id，必须与设置页写入的取值保持一致 */
    const val BUILTIN_PERMISSION_ID = "builtin"

    /**
     * 需求澄清工具名。
     *
     * 它是模型向用户提问的唯一通道，语义上不属于「操作本机资源」，
     * 因此有两条特殊规则：
     *   1. 始终注入给模型（不受 enabled 影响），否则模型需要确认需求时无从下手
     *   2. 始终需要用户审批，任何自动放行策略都不能代答
     */
    const val CLARIFICATION_TOOL = "interactive_clarification"

    private val json = Json { ignoreUnknownKeys = true }

    /** 单次读取的默认上限，防止把超大文件一次性拉进内存 */
    private const val DEFAULT_READ_LIMIT = 2 * 1024 * 1024

    /** 读取上限的最大可配置值 */
    private const val MAX_READ_LIMIT = 32 * 1024 * 1024

    /**
     * 内置工具调用前的默认审批策略。
     *
     * 返回 false 表示「工具调用默认免审批，直接自动执行」，
     * 与 [McpToolPermission.requireApproval] 的默认值保持一致。
     *
     * 之所以保留成函数而不是直接用常量，是为了给将来「按风险等级区分」留出改造点：
     * 例如想把高危写操作改成仍需审批时，只需在这里加一个集合判断，
     * 不必再去动 collectAvailableTools / checkAutoApproveTools 里的判定分支。
     *
     * 注意：这个函数只影响「审批」，不影响「是否启用」。
     * 工具是否可用由 [McpToolPermission.enabled] 单独决定，默认 true。
     */
    fun requiresApprovalByDefault(toolName: String): Boolean = false

    private fun arg(args: JsonObject, key: String): String? =
        args[key]?.jsonPrimitive?.contentOrNull

    private fun requireArg(args: JsonObject, key: String): String =
        arg(args, key)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("missing argument: $key")

    private fun safeFile(rawPath: String): File {
        val trimmed = rawPath.trim()
        val targetPath = when {
            trimmed == "/" || trimmed == "." || trimmed == "./" -> "/storage/emulated/0"
            trimmed.startsWith("/tmp") ->
                "/storage/emulated/0/Download" + trimmed.removePrefix("/tmp")
            !trimmed.startsWith("/") -> "/storage/emulated/0/$trimmed"
            else -> trimmed
        }
        val f = File(targetPath).canonicalFile
        val allowed = allowedRoots()
        val ok = allowed.any { root ->
            f.path == root.path || f.path.startsWith(root.path + File.separator)
        }
        if (!ok) {
            return File(
                "/storage/emulated/0/Download",
                f.name.ifBlank { "mcp_workspace" }
            ).canonicalFile
        }
        return f
    }

    private var cachedRoots: List<File>? = null
    private var appFilesDir: File? = null

    fun init(filesDir: File) {
        appFilesDir = filesDir
        cachedRoots = null
    }

    private fun allowedRoots(): List<File> = cachedRoots ?: buildList {
        add(File("/storage/emulated/0"))
        appFilesDir?.let { add(it) }
        runCatching { add(File("/sdcard")) }
    }.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .distinctBy { it.path }
        .also { cachedRoots = it }

    // ---------------------------------------------------------------
    // 工具声明
    // ---------------------------------------------------------------

    val schema: List<McpTool> = listOf(
        tool(
            "file_read",
            "读取一个文件的完整内容并返回。这是读取文件的唯一方式，始终返回整个文件。" +
                "超过上限的文件会被截断，返回值中会明确标注。",
            """{"type":"object","properties":{"path":{"type":"string","description":"文件绝对路径，例如 /storage/emulated/0/Download/a.java"},"max_bytes":{"type":"integer","description":"可选，读取上限（字节）。默认 2097152（2MB），上限 33554432（32MB）"}},"required":["path"]}"""
        ),
        tool(
            "file_write",
            "把完整内容写入文件（整体覆盖）。父目录不存在会自动创建。" +
                "这是写入文件的唯一方式，写入内容必须是一次完整的文件内容，不支持部分替换或追加。",
            """{"type":"object","properties":{"path":{"type":"string","description":"文件绝对路径"},"content":{"type":"string","description":"要写入的完整文件内容"}},"required":["path","content"]}"""
        ),
        tool(
            "file_list",
            "列出目录下的文件与子目录，返回名称、类型与大小。",
            """{"type":"object","properties":{"path":{"type":"string","description":"目录绝对路径，例如 /storage/emulated/0/Download"}},"required":["path"]}"""
        ),
        tool(
            "file_info",
            "获取文件或目录的详细信息：类型、大小、最后修改时间、可读可写状态。",
            """{"type":"object","properties":{"path":{"type":"string","description":"文件或目录绝对路径"}},"required":["path"]}"""
        ),
        tool(
            "file_exists",
            "判断路径是否存在，并返回它是文件还是目录。",
            """{"type":"object","properties":{"path":{"type":"string","description":"路径"}},"required":["path"]}"""
        ),
        tool(
            "file_search",
            "在指定目录下按文件名关键字递归搜索，返回匹配的完整路径列表。",
            """{"type":"object","properties":{"path":{"type":"string","description":"搜索根目录"},"keyword":{"type":"string","description":"文件名关键字"},"max_results":{"type":"integer","description":"最大返回条数，默认 50"}},"required":["path","keyword"]}"""
        ),
        tool(
            "file_delete",
            "删除文件或目录。目录会被递归删除。此操作不可撤销。",
            """{"type":"object","properties":{"path":{"type":"string","description":"要删除的文件或目录绝对路径"}},"required":["path"]}"""
        ),
        tool(
            "file_move",
            "把文件或目录移动到新位置。",
            """{"type":"object","properties":{"from":{"type":"string","description":"源路径"},"to":{"type":"string","description":"目标路径"}},"required":["from","to"]}"""
        ),
        tool(
            "file_copy",
            "复制文件或目录到新位置。",
            """{"type":"object","properties":{"from":{"type":"string","description":"源路径"},"to":{"type":"string","description":"目标路径"}},"required":["from","to"]}"""
        ),
        tool(
            "file_mkdir",
            "创建目录，含多级父目录。",
            """{"type":"object","properties":{"path":{"type":"string","description":"目录绝对路径"}},"required":["path"]}"""
        ),
        tool(
            "execute_shell",
            "在安卓本机执行 Shell 脚本或命令，返回标准输出与错误输出。",
            """{"type":"object","properties":{"script":{"type":"string","description":"要执行的 Shell 脚本或命令"},"timeout_seconds":{"type":"integer","description":"超时秒数，默认 30，上限 300"}},"required":["script"]}"""
        ),
        tool(
            "execute_python",
            "在 App 内置 Python 运行时执行脚本，自动捕获 print 输出与异常。",
            """{"type":"object","properties":{"code":{"type":"string","description":"要执行的 Python 代码"},"timeout_seconds":{"type":"integer","description":"超时秒数，默认 20，上限 300"}},"required":["code"]}"""
        ),
        tool(
            "dex_list_classes",
            "【逆向】列出 APK 或 DEX 中包含的全部类，输出类描述符与点号格式类名。",
            """{"type":"object","properties":{"file_path":{"type":"string","description":"APK 或 DEX 文件绝对路径"}},"required":["file_path"]}"""
        ),
        tool(
            "dex_get_class_smali",
            "【逆向】反编译输出 APK 或 DEX 中指定类的完整 Smali 代码。",
            """{"type":"object","properties":{"file_path":{"type":"string","description":"APK 或 DEX 文件绝对路径"},"class_name":{"type":"string","description":"类名，支持 Lcom/foo/Bar; 或 com.foo.Bar"}},"required":["file_path","class_name"]}"""
        ),
        tool(
            "dex_get_method_smali",
            "【逆向】反编译输出指定类中单个方法的完整 Smali 代码。",
            """{"type":"object","properties":{"file_path":{"type":"string","description":"APK 或 DEX 文件绝对路径"},"class_name":{"type":"string","description":"类名，支持 Lcom/foo/Bar; 或 com.foo.Bar"},"method_name":{"type":"string","description":"方法名或方法全签名"}},"required":["file_path","class_name","method_name"]}"""
        ),
        tool(
            CLARIFICATION_TOOL,
            "当需要向用户确认需求、偏好或方案时调用。向用户展示一个问题与最多 4 个预设选项，" +
                "用户可单选、多选，并在末尾自定义补充。",
            """{"type":"object","properties":{"question":{"type":"string","description":"需要向用户确认的问题"},"options":{"type":"array","items":{"type":"string"},"description":"选项列表，最多 4 个"},"allow_multiple":{"type":"boolean","description":"是否允许多选"}},"required":["question","options"]}"""
        )
    )

    private fun tool(name: String, desc: String, inputSchema: String) =
        McpTool(
            name = name,
            description = desc,
            inputSchema = inputSchema,
            serverId = "",
            builtIn = true
        )

    fun isBuiltIn(name: String): Boolean = schema.any { it.name == name }

    /** 取内置工具的定义，用于统一比对工具名与参数结构 */
    fun find(name: String): McpTool? = schema.find { it.name == name }

    // ---------------------------------------------------------------
    // 分发
    // ---------------------------------------------------------------

    fun execute(name: String, argumentsJson: String): McpToolResult = runCatching {
        val args = if (argumentsJson.isBlank()) {
            JsonObject(emptyMap())
        } else {
            json.parseToJsonElement(argumentsJson).jsonObject
        }
        when (name) {
            "file_read" -> fileRead(args)
            "file_write" -> fileWrite(args)
            "file_list" -> fileList(args)
            "file_info" -> fileInfo(args)
            "file_exists" -> fileExists(args)
            "file_search" -> fileSearch(args)
            "file_delete" -> fileDelete(args)
            "file_move" -> fileMoveOrCopy(args, copy = false)
            "file_copy" -> fileMoveOrCopy(args, copy = true)
            "file_mkdir" -> fileMkdir(args)
            "execute_shell" -> executeShell(args)
            "execute_python" -> executePython(args)
            "dex_list_classes" -> dexListClasses(args)
            "dex_get_class_smali" -> dexGetClassSmali(args)
            "dex_get_method_smali" -> dexGetMethodSmali(args)
            CLARIFICATION_TOOL -> interactiveClarification(args)
            else -> McpToolResult(
                ok = false,
                content = "",
                error = "未知的内置工具：$name。" +
                    "可用工具为：${schema.joinToString("、") { it.name }}"
            )
        }
    }.getOrElse { e ->
        DebugLog.e("BuiltInTools", "execute $name failed: ${e.message}", e)
        McpToolResult(ok = false, content = "", error = e.message ?: "内置工具执行异常")
    }

    // ---------------------------------------------------------------
    // 文件读写：整文件语义
    // ---------------------------------------------------------------

    /**
     * 读取整个文件。
     *
     * 不做行号/区间切片：返回的始终是从文件开头到结尾（或到上限被截断）的完整内容。
     * 截断时会同时给出原始大小与已读字节数，让调用方能判断是否拿到了全量。
     */
    private fun fileRead(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        if (!f.exists()) return fail("文件不存在: ${f.path}")
        if (!f.isFile) return fail("目标不是文件: ${f.path}")

        val requested = arg(args, "max_bytes")?.toIntOrNull() ?: DEFAULT_READ_LIMIT
        val limit = requested.coerceIn(1, MAX_READ_LIMIT)
        val total = f.length()

        val bytes = f.readBytes()
        if (bytes.size <= limit) {
            return ok(
                buildString {
                    append("路径: ").append(f.path).append('\n')
                    append("大小: ").append(total).append(" 字节\n")
                    append("--- 文件内容开始 ---\n")
                    append(String(bytes, Charsets.UTF_8))
                    append("\n--- 文件内容结束 ---")
                }
            )
        }

        val slice = bytes.copyOfRange(0, limit)
        return ok(
            buildString {
                append("路径: ").append(f.path).append('\n')
                append("大小: ").append(total).append(" 字节\n")
                append("警告: 文件超过读取上限，仅返回前 ").append(limit).append(" 字节，")
                append("内容不完整。如需完整内容请调大 max_bytes 参数。\n")
                append("--- 文件内容开始（已截断）---\n")
                append(String(slice, Charsets.UTF_8))
                append("\n--- 文件内容结束（已截断）---")
            }
        )
    }

    /**
     * 写入整个文件。
     *
     * 始终整体覆盖：语义是「把这个文件的全部内容替换为 content」。
     * 不提供追加模式，避免 AI 在多次调用之间产生内容叠加的误操作。
     */
    private fun fileWrite(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        val content = arg(args, "content") ?: ""
        f.parentFile?.mkdirs()
        f.writeText(content)
        return ok(
            buildString {
                append("已写入: ").append(f.path).append('\n')
                append("字符数: ").append(content.length).append('\n')
                append("文件大小: ").append(f.length()).append(" 字节")
            }
        )
    }

    private fun fileList(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        if (!f.exists()) return fail("目录不存在: ${f.path}")
        if (!f.isDirectory) return fail("目标不是目录: ${f.path}")
        val children = f.listFiles()?.sortedBy { it.name } ?: emptyList()
        val sb = StringBuilder()
        sb.append("目录: ").append(f.path).append('\n')
        children.forEach { c ->
            val type = if (c.isDirectory) "DIR " else "FILE"
            val size = if (c.isDirectory) "-" else c.length().toString()
            sb.append(type).append('\t').append(size).append('\t').append(c.name).append('\n')
        }
        if (children.isEmpty()) sb.append("(空目录)\n")
        return ok(sb.toString())
    }

    private fun fileInfo(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        if (!f.exists()) return fail("路径不存在: ${f.path}")
        val sb = StringBuilder()
        sb.append("路径: ").append(f.path).append('\n')
        sb.append("类型: ").append(if (f.isDirectory) "目录" else "文件").append('\n')
        sb.append("大小: ").append(if (f.isDirectory) "-" else f.length().toString()).append('\n')
        sb.append("最后修改: ").append(f.lastModified()).append('\n')
        sb.append("可读: ").append(f.canRead()).append('\n')
        sb.append("可写: ").append(f.canWrite()).append('\n')
        return ok(sb.toString())
    }

    private fun fileExists(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        val desc = when {
            !f.exists() -> "不存在"
            f.isDirectory -> "目录"
            f.isFile -> "文件"
            else -> "未知类型"
        }
        return ok("${f.path}: $desc")
    }

    private fun fileSearch(args: JsonObject): McpToolResult {
        val root = safeFile(requireArg(args, "path"))
        val keyword = requireArg(args, "keyword")
        val max = (arg(args, "max_results")?.toIntOrNull() ?: 50).coerceIn(1, 2000)
        if (!root.isDirectory) return fail("搜索根路径不是目录: ${root.path}")

        val out = mutableListOf<String>()
        root.walkTopDown()
            .onEnter { it == root || out.size < max }
            .forEach { f ->
                if (out.size >= max) return@forEach
                if (f.name.contains(keyword, ignoreCase = true)) out.add(f.path)
            }
        val sb = StringBuilder()
        sb.append("在 ").append(root.path).append(" 下匹配 \"").append(keyword).append("\" 共 ")
            .append(out.size).append(" 条\n")
        out.forEach { sb.append(it).append('\n') }
        return ok(sb.toString())
    }

    private fun fileDelete(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        if (!f.exists()) return fail("路径不存在: ${f.path}")
        val deleted = if (f.isDirectory) f.deleteRecursively() else f.delete()
        return if (deleted) ok("已删除: ${f.path}") else fail("删除失败: ${f.path}")
    }

    private fun fileMoveOrCopy(args: JsonObject, copy: Boolean): McpToolResult {
        val from = safeFile(requireArg(args, "from"))
        val to = safeFile(requireArg(args, "to"))
        if (!from.exists()) return fail("源路径不存在: ${from.path}")
        val target = if (to.isDirectory) File(to, from.name) else to
        target.parentFile?.mkdirs()

        return if (copy) {
            runCatching {
                if (from.isDirectory) {
                    from.copyRecursively(target, overwrite = true)
                } else {
                    from.copyTo(target, overwrite = true)
                }
            }.fold(
                onSuccess = { ok("已复制 ${from.path} -> ${target.path}") },
                onFailure = { fail("复制失败: ${it.message}") }
            )
        } else {
            val renamed = runCatching { from.renameTo(target) }.getOrDefault(false)
            if (renamed) {
                ok("已移动 ${from.path} -> ${target.path}")
            } else {
                // 跨分区时 renameTo 会失败，退化为复制后删除
                runCatching {
                    if (from.isDirectory) {
                        from.copyRecursively(target, overwrite = true)
                        from.deleteRecursively()
                    } else {
                        from.copyTo(target, overwrite = true)
                        from.delete()
                    }
                }.fold(
                    onSuccess = { ok("已移动 ${from.path} -> ${target.path}") },
                    onFailure = { fail("移动失败: ${it.message}") }
                )
            }
        }
    }

    private fun fileMkdir(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        val done = if (f.exists()) f.isDirectory else f.mkdirs()
        return if (done) ok("目录已就绪: ${f.path}") else fail("创建目录失败: ${f.path}")
    }

    // ---------------------------------------------------------------
    // Shell / Python
    // ---------------------------------------------------------------

    /**
     * Shell 执行。
     *
     * 必须先异步抽干 stdout 再 waitFor：
     * 若脚本输出超过管道缓冲区（通常 64KB），子进程会阻塞在 write 上永不退出，
     * waitFor 会一直等到超时，表现为工具调用长时间无响应。
     */
    private fun executeShell(args: JsonObject): McpToolResult {
        val script = requireArg(args, "script")
        val timeoutSec = (arg(args, "timeout_seconds")?.toLongOrNull() ?: 30L)
            .coerceIn(1L, 300L)

        val tmpDir = File("/storage/emulated/0/Download/scripts").apply { mkdirs() }
        val scriptFile = File(
            tmpDir,
            "run_${System.currentTimeMillis()}_${Thread.currentThread().id}.sh"
        )
        var process: Process? = null
        return try {
            scriptFile.writeText(script)
            scriptFile.setExecutable(true)

            val pb = ProcessBuilder("sh", scriptFile.absolutePath)
            pb.directory(File("/storage/emulated/0"))
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p

            val outputFuture = CompletableFuture.supplyAsync {
                runCatching {
                    BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
                }.getOrDefault("")
            }

            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                runCatching { p.destroyForcibly() }
                outputFuture.cancel(true)
                return fail("执行超时（超过 $timeoutSec 秒），已强制终止进程")
            }

            val output = runCatching { outputFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
            val exitCode = runCatching { p.exitValue() }.getOrDefault(-1)

            if (exitCode == 0) {
                ok(if (output.isNotBlank()) output else "(执行成功，无输出)")
            } else {
                fail("执行退出代码: $exitCode\n输出:\n$output")
            }
        } catch (e: Throwable) {
            fail("Shell 执行失败: ${e.message}")
        } finally {
            runCatching { process?.destroy() }
            runCatching { scriptFile.delete() }
        }
    }

    private fun executePython(args: JsonObject): McpToolResult {
        val code = requireArg(args, "code")
        val timeoutSec = (arg(args, "timeout_seconds")?.toLongOrNull() ?: 20L)
            .coerceIn(1L, 300L)
        return try {
            if (!Python.isStarted()) {
                return fail("Python 运行时尚未启动")
            }
            val runner = Python.getInstance().getModule("runner")
            val resultText = runPythonWithTimeout(runner, code, timeoutSec)
            ok(if (resultText.isNotBlank()) resultText else "(执行完成，未产生控制台输出)")
        } catch (e: Throwable) {
            DebugLog.e("BuiltInTools", "Python 运行异常: ${e.message}", e)
            fail("Python 执行异常:\n${e.message}")
        }
    }

    /**
     * 带超时的 Python 执行。
     *
     * Chaquopy 的 PyObject.callAttr 是同步跨语言调用，没有超时参数：
     * 脚本一旦死循环或阻塞，Java 侧会永久卡死在该调用上，
     * 工具状态就会永远停留在 executing。
     * 处理方式：放到独立守护线程执行，主调用方用 join(timeout) 兜住。
     */
    private fun runPythonWithTimeout(
        runner: PyObject,
        code: String,
        timeoutSeconds: Long
    ): String {
        val holder = arrayOfNulls<String>(1)
        val errBox = arrayOfNulls<String>(1)

        val worker = Thread({
            try {
                val obj = runCatching {
                    // 新版 runner 提供带超时的 f()
                    runner.callAttr("f", code, timeoutSeconds.toInt())
                }.getOrElse { first ->
                    // 老版 runner 没有 f()，兼容回退到 b()
                    runCatching {
                        runner.callAttr("b", code)
                    }.getOrElse { second ->
                        DebugLog.w(
                            "BuiltInTools",
                            "callAttr f() failed: ${first.message}; " +
                                "fallback b() failed: ${second.message}"
                        )
                        throw second
                    }
                }
                holder[0] = obj?.toString()
            } catch (t: Throwable) {
                errBox[0] = t.message ?: t.javaClass.simpleName
                DebugLog.e("BuiltInTools", "Python worker failed: ${t.message}", t)
            }
        }, "mcp-python-call")

        worker.isDaemon = true
        worker.start()
        // Python 侧自身也有超时，这里再加 3 秒余量，避免两层超时同时触发产生歧义
        worker.join((timeoutSeconds + 3L) * 1000L)

        if (worker.isAlive) {
            return "Python 执行超时（超过 $timeoutSeconds 秒），已放弃等待。\n" +
                "代码可能仍在后台运行，请检查是否存在无限循环、阻塞式网络请求或死锁。"
        }
        errBox[0]?.let { return "Python 执行异常:\n$it" }
        return holder[0].orEmpty()
    }

    // ---------------------------------------------------------------
    // DEX 逆向
    // ---------------------------------------------------------------

    private fun dexListClasses(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "file_path"))
        if (!f.exists()) return fail("文件不存在: ${f.path}")
        val res = DexParser(f).a()
        if (!res.success) return fail("解析 DEX 失败: ${res.error}")
        val sb = StringBuilder()
        sb.append("找到 ${res.total} 个类:\n")
        res.classes.take(1500).forEach { item ->
            sb.append("${item.descriptor}\t(${item.dotName})\t[${item.dexEntry}]\n")
        }
        if (res.total > 1500) {
            sb.append("... 其余 ${res.total - 1500} 个类已截断显示\n")
        }
        return ok(sb.toString())
    }

    private fun dexGetClassSmali(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "file_path"))
        if (!f.exists()) return fail("文件不存在: ${f.path}")
        val className = requireArg(args, "class_name")
        val res = DexParser(f).a(className)
        return if (res.success) ok(res.smali) else fail("获取类 Smali 失败: ${res.error}")
    }

    private fun dexGetMethodSmali(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "file_path"))
        if (!f.exists()) return fail("文件不存在: ${f.path}")
        val className = requireArg(args, "class_name")
        val methodName = requireArg(args, "method_name")
        val res = DexParser(f).a(className, methodName)
        return if (res.success) {
            ok(
                buildString {
                    append("# Class: ").append(res.classDescriptor).append('\n')
                    append("# Method: ").append(res.methodSignature).append('\n')
                    append("# Return: ").append(res.returnType).append("\n\n")
                    append(res.smali)
                }
            )
        } else {
            fail("获取方法 Smali 失败: ${res.error}")
        }
    }

    // ---------------------------------------------------------------
    // 需求澄清
    // ---------------------------------------------------------------

    private fun interactiveClarification(args: JsonObject): McpToolResult {
        val question = arg(args, "question") ?: "需求选项确认"
        val options = args["options"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.take(4)
            ?: emptyList()
        val allowMultiple = args["allow_multiple"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false

        val sb = StringBuilder()
        sb.append("已向用户展示选项卡片并收集反馈：\n")
        sb.append("问题：").append(question).append('\n')
        sb.append("模式：").append(if (allowMultiple) "多选" else "单选").append('\n')
        options.forEachIndexed { i, opt ->
            sb.append("选项").append(i + 1).append("：").append(opt).append('\n')
        }
        sb.append("末尾选项：其他（支持用户手动输入自定义需求）\n")
        return ok(sb.toString())
    }

    // ---------------------------------------------------------------
    // 结果包装
    // ---------------------------------------------------------------

    private fun ok(text: String) = McpToolResult(ok = true, content = text)
    private fun fail(msg: String) = McpToolResult(ok = false, content = "", error = msg)
}
