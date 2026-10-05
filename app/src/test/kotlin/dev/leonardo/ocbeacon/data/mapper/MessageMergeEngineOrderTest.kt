package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #485 完结闪烁根修回归：REST 快照归并的有序性。
 *
 * 真机定罪（2026-09-30 18:31:42，journal #485）：V2 SSE 把消息 created 写成
 * 信封/客户端钟（V2SseMapper），流式期内存行的 created 比 REST 权威值早
 * 0.5-1s；完结刷新 mergeSortedMessages 契约「合并行保持 existing 原位」，
 * user 行内容被 REST 权威替换（created 前跳）却留在 SSE 时期槽位 → 列表
 * 失序（user 冒到 agent-switched 信封上方）→ computeTurnAnchors 的 Older
 * 侧相邻变成信封 → t_ 键漂移 → LazyColumn 弃整棵子树 → asyncTerminal
 * 全新实例 State.Loading ≈0 高 = 「完结前内容闪灭再重现」。
 *
 * 本测试钉死：REST 快照归并后列表必须按 time.created 有序（服务端真相序），
 * 且二次刷新（下一轮完结）键不振荡（真机曾观测 t_626b → t_628d → t_626b 振荡）。
 */
class MessageMergeEngineOrderTest {

    private fun user(id: String, created: Long, role: String = "user") =
        Message.User(id = id, sessionId = "s", role = role, time = TimeInfo(created = created))

    private fun assistant(id: String, created: Long, completed: Long? = null) =
        Message.Assistant(
            id = id, sessionId = "s", time = TimeInfo(created = created, completed = completed),
            parentId = "",
        )

    /**
     * SSE 时期内存态（升序）：两轮 user+assistant，信封尚未到达。
     * 时钟形状对齐真机（2026-09-30 18:31:42 wire 实测）：user 回显的信封时间
     * 比 REST 持久化 created 早 ~13-50ms（ admitted 与 persist 的服务端时序差）
     * ——足以把 user 行挤到 agent-switched 信封的 Older 侧；assistant 的
     * step.started 信封 ≈ 服务端钟（偏 ≤ 数 ms）且服务端 user→assistant 固有
     * ~20ms 间隔，assistant 恒在 user 之上。
     */
    private fun sseEraState(): List<Message> = listOf(
        assistant("m_prevA", 1000, completed = 1001),
        user("m_kU", 2112),
        assistant("m_kA", 2140, completed = 2141),
        user("m_nU", 2561),
        assistant("m_nA", 2570, completed = 2571),
    )

    /** REST 快照（服务端 created，升序）：V2 每轮四消息 agent-switched/user/assistant/idle。 */
    private fun restSnapshot(): List<Message> = listOf(
        assistant("m_prevA", 990, completed = 991),
        user("m_kSw", 2115, role = "agent-switched"),
        user("m_kU", 2125),
        assistant("m_kA", 2142, completed = 2143),
        user("m_kIdle", 2356, role = "idle"),
        user("m_nSw", 2562, role = "agent-switched"),
        user("m_nU", 2565),
        assistant("m_nA", 2572, completed = 2573),
        user("m_nIdle", 3011, role = "idle"),
    )

    private fun List<Message>.assertSortedByCreated() {
        zipWithNext().forEach { (a, b) ->
            assertTrue(
                "归并输出失序: " + a.id + "(" + a.time.created + ") 应 <= " + b.id + "(" + b.time.created + ")",
                a.time.created <= b.time.created,
            )
        }
    }

    @Test
    fun restSnapshotMergeKeepsServerCreatedOrder() {
        val merged = MessageMergeEngine.mergeRestSnapshot(sseEraState(), restSnapshot()) { sse, rest ->
            MessageMergeEngine.mergeMessageMeta(sse, rest)
        }
        merged.assertSortedByCreated()
        // user 行应落在 agent-switched 信封与 assistant 之间（服务端真相位）
        val uk = merged.indexOfFirst { it.id == "m_kU" }
        val ukSw = merged.indexOfFirst { it.id == "m_kSw" }
        val ukA = merged.indexOfFirst { it.id == "m_kA" }
        assertTrue("user 应在 agent-switched 之后", ukSw < uk)
        assertTrue("user 应在 assistant 之前", uk < ukA)
    }

    @Test
    fun doubleRefreshOrderStableNoOscillation() {
        val once = MessageMergeEngine.mergeRestSnapshot(sseEraState(), restSnapshot()) { sse, rest ->
            MessageMergeEngine.mergeMessageMeta(sse, rest)
        }
        val twice = MessageMergeEngine.mergeRestSnapshot(once, restSnapshot()) { sse, rest ->
            MessageMergeEngine.mergeMessageMeta(sse, rest)
        }
        assertEquals("二次刷新不应再移动任何行（键振荡源）", once.map { it.id }, twice.map { it.id })
        twice.assertSortedByCreated()
    }

    @Test
    fun mergedUserRowCarriesRestCreated() {
        val merged = MessageMergeEngine.mergeRestSnapshot(sseEraState(), restSnapshot()) { sse, rest ->
            MessageMergeEngine.mergeMessageMeta(sse, rest)
        }
        val un = merged.first { it.id == "m_nU" }
        assertEquals("user 行 created 应为 REST 权威值", 2565L, un.time.created)
    }
}
