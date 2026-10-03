package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #504（2026-10-02 真机定罪）：DSH 完结换装闪塌——合成 part.id→权威 seq id
 * 换代经 key(part.id) 销毁 pilot 子树，#472 的本地 pilotEverRendered 保持记忆
 * 丢失 → 异步终态 Loading 占位（8754px→200px 塌缩 260ms）。
 *
 * 修复=换代 seed 桥（CompletionHandoff 单槽交接所 + 内容相等门）：dispose 侧
 * 存归一化终态，新组合侧内容相等才取用。本类钉**内容门**语义——相等命中
 * （含归一化等价形态）、不等/空槽不命中（宁缺勿错配=自校验安全）。
 */
class CompletionHandoffGateTest {

    private fun motive(msg: String) = println("[MOTIVE] $msg")

    @Test
    fun `归一化等价文本命中（换装两侧同变换）`() {
        motive("#504 主断言：stash 存的是归一化形态、取用侧对原文归一化后比对——DSH 换装「流式终帧原文 vs 权威 seq 原文」若字面相同或仅归一化等价，必命中（换装视觉恒等前提）")
        val normalized = normalizeForStreaming("# Title\n\nbody text")
        // 取用侧传原文（归一化后与 stash 相等）
        assertTrue(completionHandoffMatches(normalized, "# Title\n\nbody text"))
    }

    @Test
    fun `尾差容错 前缀相等且缺口小 命中`() {
        motive("#504 首验定罪修正：pilot 终帧落后终态 2 字符（末批 delta/扣留尾）致严格相等恒 miss——前缀相等+缺口≤512=同文档命中，hold 渲染旧帧、终态原子补齐（2 字符差替代 10330px 塌缩）")
        val stashKey = normalizeForStreaming("# T\n\n" + "body ".repeat(100))
        val incoming = "# T\n\n" + "body ".repeat(100) + "尾批两字"
        assertTrue(completionHandoffMatches(stashKey, incoming))
    }

    @Test
    fun `尾部区域改写命中（真机 19h58m 定罪形态）`() {
        motive("真机取证：gap=16 但 startsWith=false——完结内容对尾部区域改写（围栏闭合/末段修正），非纯追加。公共前缀分叉点落在两串末 256 字符内=同文档命中")
        val body = "paragraph line ".repeat(300)
        val stashKey = normalizeForStreaming("# T\n\n$body```py\nprint(")
        val incoming = "# T\n\n$body```py\nprint(x)\n```"
        assertTrue(completionHandoffMatches(stashKey, incoming))
    }

    @Test
    fun `长文中段分叉不命中（宁缺勿错配）`() {
        motive("#505 后语义收窄：全文异构（分叉带宽贯穿——前缀锚之外连后缀锚也无公共区）=不同文档，恒 miss；中段**小**分叉由头尾锚用例覆盖")
        val head = "shared prefix ".repeat(80)
        val a = head + "document A unique middle ".repeat(60)
        val b = head + "document B DIFFERENT middle ".repeat(60)
        assertFalse(completionHandoffMatches(normalizeForStreaming(a), b))
        // 短串（<2048）生产上不进此门（同步解析路径）——松弛门对短串无区分力，不测
    }

    @Test
    fun `缺口超容错上限不命中`() {
        motive("大缺口=不同文档（非换装尾差量级）——512 字符上限防长前缀误配")
        val stashKey = "body ".repeat(100)
        val incoming = "body ".repeat(100) + "tail ".repeat(200)
        assertTrue(incoming.length - stashKey.length > COMPLETION_HANDOFF_TAIL_TOLERANCE_CH)
        assertFalse(completionHandoffMatches(stashKey, incoming))
    }

    @Test
    fun `空槽不命中`() {
        motive("无 stash（首跑/进程冷启）恒 miss——不制造幻影 seed")
        assertFalse(completionHandoffMatches(null, "any content"))
    }

    @Test
    fun `归一化重写形态两侧等价命中`() {
        motive("归一化闭合重写（如任务列表预览/tex 围栏类变换）使原文不等但归一化相等——两侧同变换保证坐标一致，此为换装恒等命中的鲁棒性来源")
        val rawA = "# T\n\n- [ ] item"
        val rawB = "# T\n\n- [ ] item"
        val stashKey = normalizeForStreaming(rawA)
        assertTrue(stashKey == normalizeForStreaming(rawB))
        assertTrue(completionHandoffMatches(stashKey, rawB))
    }

    @Test
    fun `中段小缺口命中（真机 turn 30 形态）- #505 头尾锚`() {
        motive("#505 真机定罪：流式累积 3724 vs 终态 3734，中段 10 字重复短语被旧 endsWith 去重误杀 → startsWith 恒 false → miss → 200px 占位闪塌。首尾各留 ≥256 干净区=同文档换装残余形态，命中（门只选解析策略不选内容，误命中代价≈10ms 同步解析）")
        val head = "调度器在绑定瞬间评估节点容量与亲和性权重。".repeat(20)   // 620
        val hole = "这十个字会被误杀！！".repeat(2)                      // 20
        val tail = "资源上限减去已分配量得到可调度余量的完整公式。".repeat(20) // 620
        val doc = head + hole + tail
        val stashed = head + tail   // 中段洞：pfx=620 sfx=620 gap=-20
        assertTrue(completionHandoffMatches(normalizeForStreaming(stashed), doc))
    }

    @Test
    fun `中段小多余命中（重投形态，stash 多字）- #505`() {
        motive("反向形态：累积比重投多 10 字（重复投递未被去重的假想残余）——头尾锚对称覆盖；两误判方向都由完结权威替换自愈，门只需识别同文档")
        val head = "调度器在绑定瞬间评估节点容量与亲和性权重。".repeat(20)
        val mid = "这十个字是重复投递！！"
        val tail = "资源上限减去已分配量得到可调度余量的完整公式。".repeat(20)
        val doc = head + mid + tail
        val stashed = head + mid + mid + tail   // stash 多 10 字：pfx/sfx 干净
        assertTrue(completionHandoffMatches(normalizeForStreaming(stashed), doc))
    }

    @Test
    fun `首部脏不命中（前缀锚破坏）- #505`() {
        motive("安全门保留：开篇即异（共享后缀再长也枉然）——换装残余不可能改写文档开头，首 256 内分叉=真异构")
        val tail = "共享的结尾段足够长以通过后缀锚的检验区域要求。".repeat(30)
        assertFalse(completionHandoffMatches(normalizeForStreaming("甲" + tail), "乙" + tail))
    }

    // ===== #509 replayHoldCandidate（重灌在途检测）=====

    @Test
    fun `重灌中间态是指纹短前缀则保持 - #509`() {
        motive("毕业重灌的每个中间态都是终文前缀：4 字存根 vs 1178 字指纹 → 保持判定成立，pilot 分支不退场")
        CompletionHandoff.resetForTest()
        val doc = "虚构编程语言对比表格如下，逐列说明类型系统与并发模型的取舍。".repeat(40)
        CompletionHandoff.noteActive(normalizeForStreaming(doc))
        val stub = doc.substring(0, 4)
        assertTrue(CompletionHandoff.replayHoldCandidate(stub))
    }

    @Test
    fun `重灌追平（余量不足）则释放 - #509`() {
        motive("markdown 长到指纹 -256 以内（尾批补齐量级）不再是重灌中间态——保持释放，freeze 解除")
        CompletionHandoff.resetForTest()
        val doc = "段落正文持续累积直到超过余量阈值以上的长度才会被视为仍在重灌途中。".repeat(30)
        CompletionHandoff.noteActive(normalizeForStreaming(doc))
        assertFalse(CompletionHandoff.replayHoldCandidate(doc.substring(0, doc.length - 256)))
        assertTrue(CompletionHandoff.replayHoldCandidate(doc.substring(0, doc.length - 257)))
    }

    @Test
    fun `非前缀短文不保持（真异构小文本）- #509`() {
        motive("全新内容（不共享前缀）即使很短也不是重灌——门必须前缀命中才保持")
        CompletionHandoff.resetForTest()
        CompletionHandoff.noteActive(normalizeForStreaming("甲".repeat(600)))
        assertFalse(CompletionHandoff.replayHoldCandidate("乙".repeat(4)))
    }

    @Test
    fun `空 markdown 不保持 - #509`() {
        motive("空串短路——part 尚未有内容的瞬间不做保持判定")
        CompletionHandoff.resetForTest()
        CompletionHandoff.noteActive(normalizeForStreaming("内容".repeat(300)))
        assertFalse(CompletionHandoff.replayHoldCandidate(""))
    }

    @Test
    fun `无指纹（槽空）不保持 - #509`() {
        motive("冷组合/进程重启后无登记指纹：无从判定重灌，走默认路径")
        CompletionHandoff.resetForTest()
        assertFalse(CompletionHandoff.replayHoldCandidate("任意开头文本"))
    }
}

