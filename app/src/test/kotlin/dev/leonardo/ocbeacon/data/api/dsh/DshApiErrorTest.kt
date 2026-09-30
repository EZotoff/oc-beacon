package dev.leonardo.ocbeacon.data.api.dsh

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DshApiError 七类语义分类表驱动测试（backlog #274 组件 ②；设计文档 §2.4/§5）。
 *
 * 既有 [dev.leonardo.ocbeacon.data.api.ApiErrorTranslator] 是 HTTP 形状分类学
 * （401/403/404/429…），与 DSH「错误恒 HTTP 200 + 39 码闭集」语义不匹配——本层
 * 只做错误语义分类（DshErrorCategory 七类），UI 文案由接入层后续走 strings.xml。
 */
class DshApiErrorTest {

    /**
     * 期望表：0.2.0 观测闭集 64 码（斜杠域 + agent-preset 横杠域）→ 七类（独立于实现的字面清单，钉住语义
     * 映射；#458 重建。码集来源 = 0.2.0-rc.2 live 实测 + npm dist 生成绑定/
     * typert.host 注册表提取，见 journal 2026-09-30-dsh020 批次 §阶段一A）。
     * 分类原则：沿用 0.1.x 点式码原语义（码形迁移不改类）；gateway 按传输
     * 层语义细分（lookup/context 的 not-found→NotFound，unavailable 与
     * internal 与 failed 族→Server，输入与协议违约→Unknown）。
     */
    private val expected: Map<String, DshErrorCategory> = mapOf(
        // NotFound：资源不存在族
        "session/not-found" to DshErrorCategory.NotFound,
        "workspace/not-found" to DshErrorCategory.NotFound,
        "subagent/not-found" to DshErrorCategory.NotFound,
        "job/not-found" to DshErrorCategory.NotFound,
        "session/queue-item-not-found" to DshErrorCategory.NotFound,
        "agent-preset/not-found" to DshErrorCategory.NotFound,
        "gateway/lookup-not-found" to DshErrorCategory.NotFound,
        "gateway/context-not-found" to DshErrorCategory.NotFound,
        // Busy：资源被占用/暂不可继续族
        "session/agent-busy" to DshErrorCategory.Busy,
        "session/steer-unavailable" to DshErrorCategory.Busy,
        "session/writer-held" to DshErrorCategory.Busy,
        "subagent/not-resumable" to DshErrorCategory.Busy,
        "agent-preset/locked" to DshErrorCategory.Busy,
        "workspace/session-active" to DshErrorCategory.Busy,
        "terminal/limit-reached" to DshErrorCategory.Busy,
        // Conflict：状态/命名冲突族
        "session/conflict" to DshErrorCategory.Conflict,
        "workspace/name-conflict" to DshErrorCategory.Conflict,
        "agent-preset/conflict" to DshErrorCategory.Conflict,
        "directory-picker/exists" to DshErrorCategory.Conflict,
        // Auth：DSH 无鉴权面，仅栅栏 unauthorized + 上游凭据两处
        "subagent/unauthorized" to DshErrorCategory.Auth,
        "credential/rejected" to DshErrorCategory.Auth,
        "session/provider-credentials-unavailable" to DshErrorCategory.Auth,
        // Server：服务端执行失败族
        "gateway/internal" to DshErrorCategory.Server,
        "gateway/service-unavailable" to DshErrorCategory.Server,
        "gateway/context-failed" to DshErrorCategory.Server,
        "gateway/context-unavailable" to DshErrorCategory.Server,
        "gateway/definition-unavailable" to DshErrorCategory.Server,
        "gateway/invocation-unavailable" to DshErrorCategory.Server,
        "gateway/lookup-failed" to DshErrorCategory.Server,
        "gateway/lookup-unavailable" to DshErrorCategory.Server,
        "gateway/method-unavailable" to DshErrorCategory.Server,
        "gateway/provider-mismatch" to DshErrorCategory.Server,
        "gateway/result-invalid" to DshErrorCategory.Server,
        "gateway/uplink-overflow" to DshErrorCategory.Server,
        "session/model-unavailable" to DshErrorCategory.Server,
        "session/projections-unavailable" to DshErrorCategory.Server,
        "session/provider-models-unavailable" to DshErrorCategory.Server,
        "session/workspace-attach-failed" to DshErrorCategory.Server,
        "subagent/catalog-diagnostic" to DshErrorCategory.Server,
        "subagent/delivery-unavailable" to DshErrorCategory.Server,
        "subagent/parent-unavailable" to DshErrorCategory.Server,
        "llm/model-discovery-rejected" to DshErrorCategory.Server,
        "directory-picker/create-failed" to DshErrorCategory.Server,
        "directory-picker/unavailable" to DshErrorCategory.Server,
        "directory-picker/unreadable" to DshErrorCategory.Server,
        "terminal/control-unavailable" to DshErrorCategory.Server,
        "terminal/unavailable" to DshErrorCategory.Server,
        // Unknown：客户端输入违约/无领域语义族（闭集其余成员）
        "gateway/ambiguous-endpoint" to DshErrorCategory.Unknown,
        "gateway/arguments-invalid" to DshErrorCategory.Unknown,
        "gateway/bad-request" to DshErrorCategory.Unknown,
        "gateway/binding-invalid" to DshErrorCategory.Unknown,
        "gateway/cancelled" to DshErrorCategory.Unknown,
        "gateway/input-invalid" to DshErrorCategory.Unknown,
        "gateway/protocol" to DshErrorCategory.Unknown,
        "gateway/signature-invalid" to DshErrorCategory.Unknown,
        "session/attachment-invalid" to DshErrorCategory.Unknown,
        "session/fork-unavailable" to DshErrorCategory.Unknown,
        "session/invalid-time-zone" to DshErrorCategory.Unknown,
        "session/title-invalid" to DshErrorCategory.Unknown,
        "subagent/attachment-invalid" to DshErrorCategory.Unknown,
        "subagent/invalid-time-zone" to DshErrorCategory.Unknown,
        "workspace/invalid-path" to DshErrorCategory.Unknown,
        "workspace/move-invalid" to DshErrorCategory.Unknown,
        "agent-preset/invalid" to DshErrorCategory.Unknown,
    )

    @Test
    fun `all 0_2_0 closed set codes classify per table`() {
        assertEquals(64, expected.size)
        assertEquals(expected.keys, DshRpcErrorCode.ALL.map { it.wire }.toSet())
        DshRpcErrorCode.ALL.forEach { code ->
            val error = DshApiError(code = code, message = "m", details = null, httpStatus = 200)
            assertEquals("category mismatch for " + code.wire, expected[code.wire], error.category)
        }
    }

    @Test
    fun `unknown code falls back to Unknown category`() {
        val error = DshApiError(
            code = DshRpcErrorCode.fromWire("not-pending"),
            message = "no pending request",
            details = null,
            httpStatus = 200,
        )
        assertEquals(DshErrorCategory.Unknown, error.category)
        assertEquals("not-pending", error.code?.wire)
    }

    @Test
    fun `code takes precedence over httpStatus`() {
        // 业务错误恒 HTTP 200：有 code 时按闭集表分类
        val error = DshApiError(DshRpcErrorCode.GatewayInternal, "boom", null, httpStatus = 200)
        assertEquals(DshErrorCategory.Server, error.category)
    }

    @Test
    fun `http status only errors map by transport semantics`() {
        // §5：HTTP 状态只表搬运层——404 未知方法 / 403 Host 栅栏 / 500 崩溃 /
        // 415 非 JSON / 400 非 JSON body / 426 需 WS
        assertEquals(DshErrorCategory.NotFound, DshApiError(null, "m", null, 404).category)
        assertEquals(DshErrorCategory.Auth, DshApiError(null, "m", null, 403).category)
        assertEquals(DshErrorCategory.Server, DshApiError(null, "m", null, 500).category)
        assertEquals(DshErrorCategory.Server, DshApiError(null, "m", null, 502).category)
        assertEquals(DshErrorCategory.Unknown, DshApiError(null, "m", null, 415).category)
        assertEquals(DshErrorCategory.Unknown, DshApiError(null, "m", null, 400).category)
        assertEquals(DshErrorCategory.Unknown, DshApiError(null, "m", null, 426).category)
    }

    @Test
    fun `transport failure without code or status is Network`() {
        // 传输层失败（IOException/超时）：无信封、无状态码 → Network
        val error = DshApiError(code = null, message = "conn refused", details = null, httpStatus = null)
        assertEquals(DshErrorCategory.Network, error.category)
    }

    @Test
    fun `fields are carried through`() {
        val details = buildJsonObject { put("sessionId", "fixture-0001") }
        val error = DshApiError(DshRpcErrorCode.SessionNotFound, "(not attached)", details, 200)
        assertEquals("session/not-found", error.code?.wire)
        assertEquals("(not attached)", error.message)
        assertEquals(details, error.details)
        assertEquals(200, error.httpStatus)
        assertNull(DshApiError(null, "m", null, null).code)
    }
}
