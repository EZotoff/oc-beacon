package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.data.api.auth

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.sse.parsers.*
import dev.leonardo.ocbeacon.domain.model.*
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import io.ktor.utils.io.ClosedReadChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SseClient"
private const val HEARTBEAT_TIMEOUT_MS = 40_000L

/** #305-net：SSE socket 读超时（engine 级）——封顶「响应头等待」死区（黑洞/半开下
 *  原MAX_VALUE致execute永挂、重连单飞门被永久占用→永不自动重连）。必须大于
 *  [HEARTBEAT_TIMEOUT_MS]（流中 #108 应用层 40s 防护先触发，本值不改流行为）。
 *  详见 SseClientV2.SSE_SOCKET_TIMEOUT_MS 同款注释（2026-09-03 黑洞 E2E 实证）。 */
internal const val SSE_SOCKET_TIMEOUT_MS = HEARTBEAT_TIMEOUT_MS + 5_000L
/** 单行上限：防止恶意/异常 server 推送超长行（无 \n 终结）导致 OOM。超限行被丢弃，连接保持。 */
private const val MAX_SSE_LINE_SIZE = 512 * 1024
/** 单事件上限：多条 data: 行累计超过此大小时丢弃整个事件（1MB，与上游 oc-remote 一致）。 */
private const val MAX_SSE_EVENT_SIZE = 1_048_576

/**
 * 读取原始字节直到遇到 \n，不做 UTF-8 解码。
 * 返回 null 表示 channel 已关闭且无更多数据。
 * 兼容 CRLF：跳过 \r 字节。
 *
 * 单行超过 [MAX_SSE_LINE_SIZE] 时**丢弃该行**（继续消费到行尾）
 * 并继续读取下一行——不中断 SSE 连接（2026-08-10 #63：原实现 abort
 * 整个读循环触发重连，超大 payload 批次会造成无谓断连与丢帧窗口）。
 */
/**
 * #97（H-5）：返回 ByteArray（原 List<Byte> 逐字节装箱——流式 20-60 事件/s
 * 持续制造 KB 级装箱垃圾；ByteArrayOutputStream 内部原始字节存储，无装箱）。
 */
internal suspend fun ByteReadChannel.readRawLineBytes(): ByteArray? {
    while (true) {
        val out = java.io.ByteArrayOutputStream(256)
        var size = 0
        var discarded = false
        try {
            while (true) {
                val b = readByte()
                if (b == '\n'.code.toByte()) break
                if (b == '\r'.code.toByte()) continue  // 兼容 CRLF
                out.write(b.toInt())
                if (++size > MAX_SSE_LINE_SIZE) {
                    // 单行 OOM 防护：丢弃整行（清空已收集字节，继续消费到行尾），连接不断开
                    AppLogger.w(TAG, "SSE line exceeds $MAX_SSE_LINE_SIZE bytes, discarding line")
                    discarded = true
                    out.reset()
                    while (true) {
                        val c = readByte()
                        if (c == '\n'.code.toByte()) break
                    }
                    break  // 回到外层循环读取下一行
                }
            }
        } catch (e: ClosedReadChannelException) {
            // 通道关闭：已收集字节作为部分行返回（原语义）；无字节则视为无更多数据
            if (size == 0 && out.size() == 0) return null
            return out.toByteArray()
        } catch (e: java.io.EOFException) {
            // #108：对端 FIN 关闭时 readByte 抛 EOFException（而非
            // ClosedReadChannelException）——同样视为流结束返回 null，
            // 避免正常 EOF 被当作异常走 catch 重连路径（日志误导）。
            if (size == 0 && out.size() == 0) return null
            return out.toByteArray()
        }
        if (!discarded) return out.toByteArray()
        // 本行已丢弃：继续外层循环读取下一行
    }
}

/**
 * 带超时的行读取——#108 核心防护。
 *
 * 半开 TCP（kill -9/NAT 静默断）下 [readRawLineBytes] 的阻塞读永久挂起
 * （socketTimeout=Long.MAX_VALUE），心跳检查永不执行 → 连接永久挂死。
 * 本函数保证最多等待 [timeoutMs]，超时返回 null——调用方据此断开走重连。
 */
internal suspend fun ByteReadChannel.readRawLineBytesWithTimeout(
    timeoutMs: Long = HEARTBEAT_TIMEOUT_MS
): ByteArray? = withTimeoutOrNull(timeoutMs) { readRawLineBytes() }

/**
 * 将 byte 块列表拼接为完整字节数组，然后一次性 UTF-8 解码。
 */internal fun buildStringFromBytes(chunks: List<ByteArray>): String {
    if (chunks.isEmpty()) return ""
    // SSE 规范：多条 data: 行必须以 \n（LF）连接。
    // 之前的实现未加分隔符直接拼接，导致多行 JSON
    // payload 中的换行丢失（例如 Markdown 表格行）。
    val separatorCount = chunks.size - 1
    val totalSize = chunks.sumOf { it.size } + separatorCount
    val array = ByteArray(totalSize)
    var pos = 0
    for ((idx, chunk) in chunks.withIndex()) {
        if (idx > 0) {
            array[pos++] = '\n'.code.toByte()  // SSE 规范：data: 行之间以 \n 分隔
        }
        chunk.copyInto(array, pos)
        pos += chunk.size
    }
    return array.toString(Charsets.UTF_8)
}

/**
 * 追加一条 data 行到事件 buffer，带事件级 OOM 防护。
 * 多条 data 行累计超过 [maxEventSize] 时清空 buffer 并跳过当前 payload
 * （超大事件通常是异常情况，跳过比断连更友好；下一帧仍可正常解析）。
 *
 * 可见性为 internal 以支持单元测试（[maxEventSize] 参数允许测试用小限制快速验证）。
 */
internal fun appendDataLine(
    buffer: MutableList<ByteArray>,
    payload: ByteArray,
    maxEventSize: Int = MAX_SSE_EVENT_SIZE
) {
    val projected = buffer.sumOf { it.size } + payload.size + buffer.size // buffer.size ≈ \n 分隔符数
    if (projected > maxEventSize) {
        AppLogger.w(TAG, "SSE event exceeds $maxEventSize bytes (${buffer.size + 1} data lines), clearing buffer")
        buffer.clear()
        // 不 add 当前 payload — 丢弃这个超大事件
    } else {
        buffer.add(payload)
    }
}

/**
 * SSE（Server-Sent Events）客户端
 *
 * 无状态——所有连接信息来自 [ServerConnection] 参数。
 * 可安全地并发用于多个服务器。
 */
@Singleton
class SseClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) {
    /**
     * #448：本实例累计跳过的坏帧数（parse 失败）——诊断观测（日志汇总 +
     * 测试断言）。实例生命周期 = 注入单例，连接层据此感知「线在对吐坏数据」。
     */
    @Volatile
    var skippedFrameCount: Int = 0
        private set

    private val parsers: List<SseEventParser> = listOf(
        MiscEventParser(),
        SessionEventParser(json),
        MessageEventParser(json),
        PermissionEventParser(),
        QuestionEventParser(),
        PtyEventParser(),
        SessionNextEventParser(json)
    )

    /** session.next 解析器的公共访问器（供测试与 parseSessionNextEvent 公共 API 消费）。 */
    val sessionNextParser: SessionNextEventParser get() = parsers.filterIsInstance<SessionNextEventParser>().firstOrNull()
        ?: throw IllegalStateException("SessionNextEventParser not found in parser list")

    /**
     * 连接到全局事件流。
     * 返回一个发射 SSE 事件的 Flow。
     * 该 Flow 不会在内部自动重连——调用方应自行处理
     * 重连（service 已实现指数退避）。
     */
    fun connectToGlobalEvents(
        conn: ServerConnection,
        directory: String? = null,
        socketTimeoutMs: Long = SSE_SOCKET_TIMEOUT_MS,
    ): Flow<SseEvent> = flow {
        val sseUrl = "${conn.baseUrl}/global/event"
        AppLogger.i(TAG, "Connecting to SSE: $sseUrl (auth=${conn.authHeader != null})")

        val statement = httpClient.prepareGet(sseUrl) {
            auth(conn)
            header("Accept", "text/event-stream")
            directory?.let { header("x-opencode-directory", URLEncoder.encode(it, "UTF-8")) }

            timeout {
                // requestTimeout 必须无限（SSE 流不限时长）；socketTimeout 封顶
                // 响应头等待死区（见 [SSE_SOCKET_TIMEOUT_MS]）——流中 #108 行级 40s
                // 超时对纯零字节停顿有效（SIGSTOP 实测），StreamStallWatchdog 兜底
                // 僵尸拆链竞态盲区（journal 2026-09-30-467 §4 矩阵）。
                requestTimeoutMillis = Long.MAX_VALUE
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = socketTimeoutMs
            }
        }

        // #467/#441：看门狗强杀若以取消形态浮出（引擎实现差异），翻译为连接错误——
        // 否则上层 catch(CancellationException) 会误判「主动取消」而跳过退避梯子。
        val stalledByWatchdog = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
        statement.execute { response ->
            val statusCode = response.status.value
            AppLogger.i(TAG, "SSE response: status=$statusCode, contentType=${response.headers["content-type"]}")

            if (statusCode == 401) {
                AppLogger.e(TAG, "SSE auth failed (401). Check username/password.")
                throw SseAuthException("Authentication failed (401)")
            }

            if (statusCode !in 200..299) {
                AppLogger.e(TAG, "SSE failed with HTTP $statusCode")
                throw SseConnectionException("HTTP $statusCode")
            }

            // #448 嗅探：200 但非 event-stream = 对面不是 SSE 端点（SPA fallback /
            // 反代错误页）——升格为协议不匹配异常，不再以 0 事件静默完成
            val v1ContentType = response.headers["content-type"] ?: ""
            if (!v1ContentType.substringBefore(';').trim().equals("text/event-stream", ignoreCase = true)) {
                AppLogger.e(TAG, "SSE endpoint returned non-event-stream content-type '$v1ContentType' (status $statusCode)")
                throw SseProtocolMismatchException(
                    "Expected text/event-stream but got '$v1ContentType' (HTTP $statusCode) — endpoint is not an SSE stream"
                )
            }

            val channel = response.bodyAsChannel()
            var lastHeartbeat = System.currentTimeMillis()
            val buffer = mutableListOf<ByteArray>()
            var eventCount = 0

            AppLogger.i(TAG, "SSE stream opened, reading events...")

            // #467/#441 流相 stall 看门狗（字节级第二道执法）：零字节判死窗 =
            // SSE_SOCKET_TIMEOUT_MS（心跳契约 ~6s/次 wire 实证，7.5x 余量）。#108 行级
            // 40s 在纯零字节停顿（SIGSTOP 实测）正常触发；reverse 僵尸拆链竞态曾一次
            // 4min04s 零检测挂死（机制未定，§4 矩阵）——本看门狗以独立字节计时 + 双保险
            // 强杀兜底该盲区。
            val stallWatchdog = StreamStallWatchdog(
                stallThresholdMs = SSE_SOCKET_TIMEOUT_MS,
                onStall = {
                    stalledByWatchdog.set(true)
                    AppLogger.w(TAG, "SSE stalled >" + SSE_SOCKET_TIMEOUT_MS + "ms (no bytes) — force-cancelling call (stream watchdog)")
                    // 双保险（Ktor 3.5.2 HttpResponse 无 cancel 成员）：
                    // ① 取消 response 自身 Job —— 引擎 cleanup 触发 okhttp call.cancel()，
                    //    socket 同步关闭，非可取消的原生阻塞读立即解除；
                    // ② 取消读通道 —— 已挂起的 read 以异常恢复（内存通道/测试环境兜底）。
                    try { response.coroutineContext[kotlinx.coroutines.Job]?.cancel() } catch (_: Exception) { }
                    try { channel.cancel() } catch (_: Exception) { }
                },
            )
            kotlinx.coroutines.coroutineScope {
                val watchdogJob = stallWatchdog.startIn(this)
                try {
            while (!channel.isClosedForRead) {
                // #108：阻塞读超时防护——半开 TCP（kill -9/NAT 静默断）下
                // readRawLineBytes 永久挂起（socketTimeout=Long.MAX_VALUE），
                // 心跳检查永不执行 → 连接永久挂死，重连/冷却失效。
                // withTimeoutOrNull 保证最多等待一个心跳周期，超时即断开走重连。
                val lineBytes = channel.readRawLineBytesWithTimeout()
                if (lineBytes == null) {
                    if (!channel.isClosedForRead) {
                        AppLogger.w(TAG, "SSE read timed out after ${HEARTBEAT_TIMEOUT_MS}ms (no data / half-open), reconnecting...")
                    }
                    break
                }
                // #108：任何行到达（含空行=事件边界）都是连接存活的证据 → 刷新心跳。
                // 对齐 V2 语义：V1 服务器长流式期间不发 server.heartbeat，
                // 若只在 ServerHeartbeat 时刷新，活跃会话每 40s 假超时断连。
                lastHeartbeat = System.currentTimeMillis()
                stallWatchdog.markProgress()

                if (lineBytes.isEmpty()) {
                    // 空白行 = SSE event 边界 → 解码整个 buffer
                    val data = buildStringFromBytes(buffer)
                    if (data.isNotEmpty()) {
                        try {
                            val event = parseEvent(data)
                            if (event != null) {
                                eventCount++
                                // 心跳已由行级刷新覆盖（任意行到达即刷新，见循环顶部）
                                if (event !is SseEvent.ServerHeartbeat) {
                                    emit(event)
                                }
                            }
                        } catch (e: Exception) {
                            // #448：坏帧跳过要有计数——单帧坏不致死（既有语义），
                            // 但静默吞掉让「整条线在吐 HTML/坏数据」不可观测。
                            skippedFrameCount++
                            if (skippedFrameCount % 50 == 1) {
                                AppLogger.w(TAG, "SSE skipped " + skippedFrameCount + " bad frames so far (last: " + data.take(80) + ": " + e.message + ")")
                            } else {
                                AppLogger.e(TAG, "Parse error: " + data.take(200), e)
                            }
                        }
                        buffer.clear()
                    }
                } else {
                    // data: 行 → 提取 payload 的原始字节
                    val prefix = "data:".encodeToByteArray()
                    var start = 0
                    if (lineBytes.size >= prefix.size &&
                        lineBytes.copyOfRange(0, prefix.size).contentEquals(prefix)) {
                        start = prefix.size
                        if (start < lineBytes.size && lineBytes[start] == ' '.code.toByte()) {
                            start++  // 跳过 "data: " 中的空格
                        }
                    }
                    if (start < lineBytes.size) {
                        appendDataLine(buffer, lineBytes.copyOfRange(start, lineBytes.size))
                    }
                }
            }
                } finally {
                    watchdogJob.cancel()
                }
            }

            AppLogger.w(TAG, "SSE stream closed after $eventCount events")
        }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (stalledByWatchdog.get()) {
                throw SseConnectionException("SSE stream stalled >" + SSE_SOCKET_TIMEOUT_MS + "ms (watchdog force-cancel)")
            }
            throw e
        }
    }

    /**
     * 从原始 JSON 解析 SSE 事件。
     * 全局端点包装事件：{directory, payload: {type, properties}}
     * 实例级端点直接发送：{type, properties}
     */
    private fun parseEvent(data: String): SseEvent? {
        val root = json.parseToJsonElement(data).jsonObject

        val payload = root["payload"]?.jsonObject ?: root
        val type = payload["type"]?.jsonPrimitive?.content ?: return null
        val properties = payload["properties"]?.jsonObject ?: JsonObject(emptyMap())

        return parseEventByType(type, properties)
    }

    private fun parseEventByType(type: String, props: JsonObject): SseEvent? {
        for (parser in parsers) {
            if (parser.canParse(type)) {
                return parser.parse(type, props)
            }
        }
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Unhandled event: $type")
        return null
    }

    /**
     * 为向后兼容保留的公共 API（供测试使用），委托给 [sessionNextParser]。
     */
    fun parseSessionNextEvent(type: String, props: JsonObject): SessionNextEvent {
        return sessionNextParser.parseSessionNextEvent(type, props)
    }
}

// ============ SSE 读取超时跟踪 ============

/**
 * SSE 读取超时行为的常量。
 */
object SseClientDefaults {
    const val DEFAULT_READ_TIMEOUT_MS = 30_000L
    const val MAX_CONSECUTIVE_TIMEOUTS = 5
    const val COOLDOWN_DURATION_MS = 300_000L
}

/**
 * 跟踪连续 SSE 读取超时并管理冷却状态。
 *
 * 在连续 [maxConsecutiveTimeouts] 次超时后，跟踪器进入
 * 冷却期（[cooldownDurationMs]），在此期间重连会被延迟。
 */
class SseReadTimeoutTracker(
    val maxConsecutiveTimeouts: Int = SseClientDefaults.MAX_CONSECUTIVE_TIMEOUTS,
    val cooldownDurationMs: Long = SseClientDefaults.COOLDOWN_DURATION_MS
) {
    var consecutiveTimeouts: Int = 0
        private set
    private var cooldownUntilMs: Long = 0L

    /** 记录一次读取超时事件。 */
    fun recordTimeout() {
        consecutiveTimeouts++
    }

    /** 记录一次成功读取——重置连续计数器。 */
    fun recordSuccess() {
        consecutiveTimeouts = 0
    }

    /** 跟踪器是否已达到冷却阈值。 */
    fun shouldEnterCooldown(): Boolean = consecutiveTimeouts >= maxConsecutiveTimeouts

    /** 进入冷却模式。 */
    fun enterCooldown() {
        // 2026-08-18 修复（SSE 冷却永续循环）：进入冷却时清零连续计数——
        // 否则冷却到期后第一个 0 事件连接/超时（consecutiveTimeouts 仍 ≥ 阈值）
        // 立即再次 enterCooldown，形成「5min 冷却 → 40s 尝试 → 5min 冷却」
        // 永续循环（beta-17595 无心跳服务器实测：冷却后 SSE 仅 ~12% 时间在线）。
        // 冷却期本身就是 5 次连续失败的代价，付清后应重新计数。
        consecutiveTimeouts = 0
        cooldownUntilMs = System.currentTimeMillis() + cooldownDurationMs
    }

    /** 是否当前处于冷却期内。 */
    fun isInCooldown(): Boolean = System.currentTimeMillis() < cooldownUntilMs

    /**
     * #448（2026-09-27）：冷却剩余毫秒（非冷却期恒 0）——连接层进入冷却时
     * 据此排程 reconnectAt，UI 倒计时才能反映真实冷却等待（原：冻结在旧
     * 退避排程 → 卡 0 五分钟）。
     */
    fun cooldownRemainingMs(): Long = (cooldownUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    /** 完全重置跟踪器（同时清除超时计数和冷却状态）。 */
    fun reset() {
        consecutiveTimeouts = 0
        cooldownUntilMs = 0L
    }
}

/** SSE 返回 401 时抛出 */
class SseAuthException(message: String) : Exception(message)

/**
 * #448（2026-09-27）：SSE 端点返回非 event-stream 内容时抛出。
 *
 * 真机取证（v2.0.18 + V1 线面误连）：SPA fallback 以 200 text/html 应答 →
 * 读循环零事件、body 放完「流正常完成」→ 被当读超时计数 → 5 次后 5min 冷却
 * → 重连条幅常驻 + 倒计时冻结。此异常把「对面根本不在说 SSE」升格为显式
 * 配置/版本类错误——调用方（SseConnectionManager）据此不计冷却、长退避。
 */
class SseProtocolMismatchException(message: String) : Exception(message)

/** 非 2xx SSE 响应时抛出 */
class SseConnectionException(message: String) : Exception(message)
