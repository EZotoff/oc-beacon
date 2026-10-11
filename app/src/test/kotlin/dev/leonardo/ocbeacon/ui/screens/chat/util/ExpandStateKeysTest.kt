package dev.leonardo.ocbeacon.ui.screens.chat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #547：展开状态稳定键键律——派生契约 id 内嵌宿主消息 id，多消息 turn 重组换
 * 宿主时前缀级联漂移 → partId 键控展开态 map 脱靶 → 静默回退收起（soak5r
 * r13：toggle 后 79ms 无 toggle 日志清回）。修复：派生 id 改按「kind + 空白
 * 归一化前缀指纹」键控；服务器赋值 id 直通；键演化窗口由消费点别名收养
 * （migrateExpandAlias）兜住。
 */
class ExpandStateKeysTest {

    private val oldHost = "msg_12b9107bf001JPaf83"
    private val newHost = "msg_12b91c58a001eWpX9i"

    /** 归一化后 >96 字符（指纹窗内）：稳定键域的「常态」文本。 */
    private val long =
        (1..30).joinToString(" ") { "streaming reasoning sentence number $it" }

    @Test
    fun `换宿主同内容同键`() {
        // r13 场景：多消息 turn 重组，reasoning part 换宿主消息 → 派生 id 前缀变
        val oldKey = ExpandStateKeys.forPart("${oldHost}_reasoning_ord_0", long)
        val newKey = ExpandStateKeys.forPart("${newHost}_reasoning_ord_0", long)
        assertEquals(oldKey, newKey)
    }

    @Test
    fun `流式 append 增长同键`() {
        // toggle 后继续输出：归一化长度已 ≥ 指纹窗，追加不换键
        val growing = long + " Then more streamed content arrives after the toggle action."
        assertEquals(
            ExpandStateKeys.forPart("${oldHost}_reasoning_ord_0", long),
            ExpandStateKeys.forPart("${oldHost}_reasoning_ord_0", growing),
        )
    }

    @Test
    fun `成长跨指纹窗键变（别名收养的存在理由）`() {
        // toggle 早于指纹窗填满（soak S2 思考期连点形态）：短文本键=当前全量
        // 归一化指纹，跨窗那刻键变 → 消费点 migrateExpandAlias 旧键状态前移。
        // 本测试钉住「键确实会变」这一事实——收养管线的触发前提。
        val short = "Let me think about this"
        val grown = short + " " + long
        assertNotEquals(
            ExpandStateKeys.forPart("${oldHost}_reasoning_ord_0", short),
            ExpandStateKeys.forPart("${oldHost}_reasoning_ord_0", grown),
        )
    }

    @Test
    fun `空白折叠两套字节表示同键`() {
        // #515 H1 同源：服务器侧空行折叠使同一 part 出现两套字节表示
        val folded = long.replace(". ", ".\n\n").replace("  ", " \t ")
        assertEquals(
            ExpandStateKeys.forPart("${oldHost}_reasoning_ord_0", long),
            ExpandStateKeys.forPart("${oldHost}_reasoning_ord_0", folded),
        )
    }

    @Test
    fun `服务器赋值 id 直通`() {
        // 工具/patch/shell 等 part 的 id 自身稳定（callId 等），非派生契约域
        assertEquals("call_xyz_123", ExpandStateKeys.forPart("call_xyz_123", "anything"))
    }

    @Test
    fun `空白文本退回原 id`() {
        // started 未收 delta 的零内容卡无可指纹物（且此态无展开交互价值）
        assertEquals(
            "${oldHost}_reasoning_ord_0",
            ExpandStateKeys.forPart("${oldHost}_reasoning_ord_0", ""),
        )
        assertEquals(
            "${oldHost}_reasoning_ord_0",
            ExpandStateKeys.forPart("${oldHost}_reasoning_ord_0", "  \n\t "),
        )
    }

    @Test
    fun `不同内容异键`() {
        assertNotEquals(
            ExpandStateKeys.forPart("${oldHost}_reasoning_ord_0", long),
            ExpandStateKeys.forPart("${oldHost}_reasoning_ord_0",
                (1..30).joinToString(" ") { "a completely different sentence $it" }),
        )
    }

    @Test
    fun `kind 标记参与键`() {
        // 同内容 text/reasoning 分属不同卡型，不得同键
        assertNotEquals(
            ExpandStateKeys.forPart("${oldHost}_reasoning_ord_0", long),
            ExpandStateKeys.forPart("${oldHost}_text_ord_0", long),
        )
    }

    @Test
    fun `归一化前缀全同时尾部差异同键`() {
        // 键语义：指纹只取归一化前 96 字符——前缀全同的两卡同键（同开同关，
        // 会话内存态下罕见且无害；这是稳定性换来的有界取舍，非错位）。
        // 基文本归一化后 >96 字符，确保尾部落在指纹窗之外。
        val a = long
        val b = long + " tail divergence beyond the fingerprint window."
        assertEquals(
            ExpandStateKeys.forPart("${oldHost}_reasoning_ord_0", a),
            ExpandStateKeys.forPart("${newHost}_reasoning_ord_1", b),
        )
    }

    @Test
    fun `稳定键不等于任何宿主派生 id`() {
        // 键域与 id 域隔离——不与直通键（服务器 id）碰撞
        val key = ExpandStateKeys.forPart("${oldHost}_reasoning_ord_0", long)
        assertNotEquals("${oldHost}_reasoning_ord_0", key)
        assertNotEquals("${newHost}_reasoning_ord_0", key)
        assertTrue(key.startsWith("xr:"))
    }
}
