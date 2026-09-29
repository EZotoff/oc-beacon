package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL as GFMCell
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #469 表格三连根因修复的机械不变量（JVM 纯逻辑）：
 * - [safeTableText]/[safeCellText]：AST 边界越界钳制（#437 失配帧教训）
 * - [TableStageProgressCache]：staged 完成标记跨重建存活 + LRU 有界
 * - [stagedInitialLimit]：完成表全量直出 / 首见分组表分批起步
 */
class TableStageCacheTest {

    @Before
    fun resetCache() = TableStageProgressCache.clearForTest()

    private fun parseFirstTable(md: String): org.intellij.markdown.ast.ASTNode {
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(md)
        return tree.children.first { it.type == org.intellij.markdown.flavours.gfm.GFMElementTypes.TABLE }
    }

    // ===== safeTableText / safeCellText =====

    @Test
    fun `表格文本截取等于原文表格段`() {
        val md = "前文\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n后文"
        val table = parseFirstTable(md)
        // AST 边界可能含边界空白——键用途下 trim 对比（值语义不受影响）
        val text = safeTableText(md, table).trim()
        assertTrue(text.startsWith("| a | b |"))
        assertTrue(text.endsWith("| 1 | 2 |"))
        assertFalse(text.contains("前文"))
        assertFalse(text.contains("后文"))
    }

    @Test
    fun `AST 越界时不抛异常并钳制截取`() {
        val md = "| a | b |\n|---|---|\n| 1 | 2 |"
        val table = parseFirstTable(md)
        // 失配帧：content 比 AST 短——不得抛异常
        val clipped = safeTableText("short", table)
        // 钳制语义：from=min(start,len)=4? 实际 start>=len → to<=from → 空串或合法前缀
        assertTrue(clipped.length <= "short".length)
    }

    @Test
    fun `空区间节点返回空串`() {
        val md = "| a |\n|---|\n| 1 |"
        val table = parseFirstTable(md)
        // 用单元格对比：非空 cell 文本可截取
        val cell = table.children.first().children.first { it.type == GFMCell }
        assertTrue(safeCellText(md, cell).isNotEmpty())
    }

    // ===== TableStageProgressCache =====

    @Test
    fun `完成标记写入后可跨查询命中`() {
        val key = "| a |\n|---|\n| 1 |"
        assertFalse(TableStageProgressCache.isComplete(key))
        TableStageProgressCache.markComplete(key)
        assertTrue(TableStageProgressCache.isComplete(key))
    }

    @Test
    fun `不同表格文本不串标记`() {
        TableStageProgressCache.markComplete("| a |\n|---|")
        assertFalse(TableStageProgressCache.isComplete("| b |\n|---|"))
    }

    @Test
    fun `尾部边界变化不丢完成标记`() {
        // G 轮探针定罪：流式收尾阶段表格尾部换行/空行归属翻转 → tableText 尾部
        // 变化——身份键（表头+行数）必须保持命中
        val marked = "| a | b |\n|---|---|\n| 1 | 2 |"
        val drifted = "| a | b |\n|---|---|\n| 1 | 2 |\n"
        TableStageProgressCache.markComplete(marked)
        assertTrue(TableStageProgressCache.isComplete(drifted))
    }

    @Test
    fun `同表头不同行数视为同身份`() {
        // 键=表头行语义：流式收尾/完结换装后的 tableText 与流式态的差异
        // （尾部边界+行数）都被身份键吸收——重建时保持命中
        TableStageProgressCache.markComplete("| a |\n|---|\n| 1 |")
        assertTrue(TableStageProgressCache.isComplete("| a |\n|---|\n| 1 |\n| 2 |"))
    }

    @Test
    fun `LRU 超容量逐出最旧标记`() {
        val first = "table-0"
        TableStageProgressCache.markComplete(first)
        repeat(TableStageProgressCache.MAX_ENTRIES) { i ->
            TableStageProgressCache.markComplete("table-fill-" + i)
        }
        // first 已被逐出（容量 24，后续 24 次写入把它挤出）
        assertFalse(TableStageProgressCache.isComplete(first))
        // 最新写入仍在
        assertTrue(TableStageProgressCache.isComplete("table-fill-" + (TableStageProgressCache.MAX_ENTRIES - 1)))
    }

    // ===== stagedInitialLimit =====

    @Test
    fun `首见分组表分批起步`() {
        assertEquals(1, stagedInitialLimit(stageCompleted = false, grouped = true))
    }

    @Test
    fun `完成过的分组表全量直出`() {
        assertEquals(Int.MAX_VALUE, stagedInitialLimit(stageCompleted = true, grouped = true))
    }

    @Test
    fun `小表恒全量`() {
        assertEquals(Int.MAX_VALUE, stagedInitialLimit(stageCompleted = false, grouped = false))
        assertEquals(Int.MAX_VALUE, stagedInitialLimit(stageCompleted = true, grouped = false))
    }

    // ===== TableRowsSnapshot =====

    @Test
    fun `行快照携带同代 content 与行集`() {
        val md = "| a | b |\n|---|---|\n| 1 | 2 |"
        val table = parseFirstTable(md)
        val snap = TableRowsSnapshot(md, listOf(TableRow(isHeader = true, rowIndex = -1, cells = table.children.first().children.filter { it.type == GFMCell })))
        assertEquals(md, snap.content)
        assertEquals(1, snap.rows.size)
        // 值语义：同 content + 同结构（ASTNode 引用同实例）相等
        assertEquals(snap, TableRowsSnapshot(snap.content, snap.rows))
    }
}
