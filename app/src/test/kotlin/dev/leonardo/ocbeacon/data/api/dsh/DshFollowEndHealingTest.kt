package dev.leonardo.ocbeacon.data.api.dsh

import org.junit.Test

/**
 * #441-A2（2026-09-28）：follow 流结束自愈的纯函数测试。
 *
 * 原缺口（issue441-research 定罪）：mux End/StreamError 帧到达后引擎无动作
 * （End 分支 Unit「无需动作」）→ followed 幂等集残留 → 本连接代内该会话
 * 永久静默（事件驱动补开 #319 与聚焦请求 #333 全被去重拦截）。修复：End/
 * StreamError → followSessionIdOf 反解 → followed.remove（重开时机交服务器
 * 事件驱动，不自动立即重开——End=会话正常消亡，立即重开会成风暴）。
 */
class DshFollowEndHealingTest {

    @Test
    fun `follow stream id resolves to session id`() {
        check(followSessionIdOf("f:msg_12345") == "msg_12345")
        check(followSessionIdOf("f:abc") == "abc")
    }

    @Test
    fun `non-follow streams are not follow sessions`() {
        check(followSessionIdOf("evt") == null)
        check(followSessionIdOf("ctl") == null)
        check(followSessionIdOf("wsp") == null)
        check(followSessionIdOf("") == null)
    }

    @Test
    fun `prefix must be exact`() {
        check(followSessionIdOf("ff:abc") == null) // 非 f: 前缀
        check(followSessionIdOf("f:") == "") // 空会话 id：跟随 followStreamId 对称性
    }
}
