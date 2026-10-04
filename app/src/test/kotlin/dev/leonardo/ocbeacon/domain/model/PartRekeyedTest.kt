package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #509 rekeyed 穷尽性守护（2026-10-04 覆盖审计补口）：
 * Part.rekeyed 是 MessageIdSwapped 原地换名的唯一改写原语——契约 =
 * messageId 换新、**id 不动**（part 身份与一切 id 键控缓存跨毕业连续）、
 * 其余字段逐字节保持、类型不漂移。生产 when 编译器强制穷尽（新增子类即
 * 编译红）；本测试钉**运行时语义**（copy 漏字段/换错字段在此红）。
 */
class PartRekeyedTest {

    private val old = "msg-old"
    private val new = "msg-new"

    private fun assertRekeyContract(original: Part, rekeyed: Part) {
        assertEquals("类型不得漂移", original::class, rekeyed::class)
        assertEquals("part.id 必须不动（身份跨换装连续）", original.id, rekeyed.id)
        assertEquals(original.sessionId, rekeyed.sessionId)
        assertEquals("原实例不得被就地改写", old, original.messageId)
        assertEquals("新实例归属换到目标消息", new, rekeyed.messageId)
    }

    @Test
    fun `全 19 子类 rekey 契约`() {
        val parts: List<Part> = listOf(
            Part.Text("p1", "s", old, text = "表格前半", time = Part.Text.Time(1, 2)),
            Part.Reasoning("p2", "s", old, text = "思考"),
            Part.Tool("p3", "s", old, callId = "c3", tool = "bash", state = ToolState.Pending()),
            Part.Shell("p4", "s", old, command = "ls", status = "done", exit = 0),
            Part.StepStart("p5", "s", old, snapshot = "snap"),
            Part.StepFinish("p6", "s", old, reason = "stop"),
            Part.File("p7", "s", old, mime = "text/plain", filename = "a.txt"),
            Part.Snapshot("p8", "s", old, snapshot = "snap"),
            Part.Patch("p9", "s", old, hash = "h", files = listOf("f")),
            Part.Subtask("p10", "s", old, prompt = "go"),
            Part.Compaction("p11", "s", old, summary = "压缩摘要"),
            Part.Retry("p12", "s", old, attempt = 2),
            Part.Agent("p13", "s", old, name = "build"),
            Part.Permission("p14", "s", old, message = "允许？"),
            Part.Question("p15", "s", old, question = "继续？"),
            Part.Abort("p16", "s", old, reason = "user"),
            Part.SessionTurn("p17", "s", old),
            Part.Deliverables("p18", "s", old),
            Part.Unknown("p19", "s", old),
        )
        assertEquals("子类清点（新增子类须补此处与生产 when）", 19, parts.size)
        parts.forEach { p -> assertRekeyContract(p, p.rekeyed(new)) }
    }

    @Test
    fun `Text 载荷字段逐字节保持`() {
        val t = Part.Text(
            "p1", "s", old, text = "# 表格\n\n|a|b|\n|-|-|\n|1|2|",
            synthetic = false, ignored = false,
            time = Part.Text.Time(start = 7, end = 9),
        )
        val r = t.rekeyed(new) as Part.Text
        assertEquals(t.text, r.text)
        assertEquals(t.synthetic, r.synthetic)
        assertEquals(t.ignored, r.ignored)
        assertEquals(t.time, r.time)
    }
}
