package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #448（2026-09-27 前提修正版）：非 SSE 响应快速嗅探 + 坏帧跳过计数。
 *
 * 真机取证（v2.0.18 + V1 线面误连）：SPA fallback 以 200 text/html 应答 SSE 端点
 * → 读循环零事件、body 放完流正常完成 → 重连循环被当成「读超时」计数 → 5 次后
 * 5min 冷却 → 条幅常驻+倒计时冻结。content-type 嗅探在流打开前把这种形态
 * 升格为显式 SseProtocolMismatchException（配置/版本类错误，重试不能自愈）。
 */
class SseProtocolMismatchTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun conn(v2: Boolean) = ServerConnection.from(
        "http://localhost:4096", "opencode", null,
        if (v2) ApiVersion.V2 else ApiVersion.V1,
    )

    @Test
    fun `V1 client raises protocol mismatch on HTML response`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                "<!doctype html><html><body>web ui</body></html>",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("text/html")),
            )
        }
        val client = SseClient(HttpClient(engine), json)
        try {
            client.connectToGlobalEvents(conn(v2 = false)).collect {}
            fail("expected SseProtocolMismatchException")
        } catch (_: SseProtocolMismatchException) {
            // 预期：不再以 0 事件静默完成
        }
    }

    @Test
    fun `V2 client raises protocol mismatch on HTML response`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                "<!doctype html><html><body>web ui</body></html>",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("text/html")),
            )
        }
        val client = dev.leonardo.ocbeacon.data.api.v2.SseClientV2(json, HttpClient(engine))
        try {
            client.connectToEvents(conn(v2 = true)).collect {}
            fail("expected SseProtocolMismatchException")
        } catch (_: SseProtocolMismatchException) {
            // 预期
        }
    }

    @Test
    fun `V1 client counts skipped bad frames instead of dying`() = runTest {
        val body = "data: not-json\n\ndata: {corrupt\n\ndata: also-bad\n\n"
        val engine = MockEngine { _ ->
            respond(
                ByteReadChannel(body.encodeToByteArray()),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("text/event-stream")),
            )
        }
        val client = SseClient(HttpClient(engine), json)
        client.connectToGlobalEvents(conn(v2 = false)).collect {}
        // #448：坏帧跳过要有计数——三帧全坏，流不因单帧死亡
        assertEquals(3, client.skippedFrameCount)
    }

    @Test
    fun `V2 client counts skipped bad frames instead of dying`() = runTest {
        val body = "data: not-json\n\ndata: {corrupt\n\n"
        val engine = MockEngine { _ ->
            respond(
                ByteReadChannel(body.encodeToByteArray()),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("text/event-stream")),
            )
        }
        val client = dev.leonardo.ocbeacon.data.api.v2.SseClientV2(json, HttpClient(engine))
        client.connectToEvents(conn(v2 = true)).collect {}
        assertEquals(2, client.skippedFrameCount)
    }
}
