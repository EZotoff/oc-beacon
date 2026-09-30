package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * #437 两级安全放行闸（2026-09-26 二次重写，纯函数，零库依赖）。
 *
 * 用户裁决修订（2026-09-26）：**纯文字增量直出**——纯文字（无活动标记）的
 * 字面临时排版=最终排版（库 stable/unstable 分裂第二道防线），未完行逐批
 * 增量放行=终端式流式节奏，不再等整段收完；含标记行/表格仍按闭合语义
 * （未闭合零输出，闭合整体上屏；表格按完整行渐显）。
 *
 * #487 用户裁决 B（2026-09-30）：**流式开放围栏行级放行**——围栏开行完整
 * 即放、内部完整行整放、未完行按纯文字增量放（代码字面=最终，真 token 级）、
 * 闭栏形完整行（同字符 run≥开栏长+仅尾空白）即放即闭。依据：解析器按
 * CommonMark 将未闭合围栏延伸至前缀尾渲染为持续增长的代码块（探针实证
 * 4/4），空行毕业机制早已在生产渲染此形态——本修订将量化从空行粒度提升到
 * 行粒度，「未闭合零输出」语义废止。
 *
 * 放行规则（对快照 S、已放行长度 floor）：
 *  1. **空行毕业**：开放区中最后一个空行块之前的全部内容已定案 → 无条件放行；
 *  2. **块级行扫描**（最后开放段内逐行状态机，单批预算 MAX_RELEASE_PER_BATCH
 *     循环内逐次扣减）：
 *     a. 围栏代码块（#487）：开栏行完整即放（未完开行扣留——半行闭栏误判
 *        块态+语言标注未定）；块内完整行整行放、未完行增量放；闭栏形完整行
 *        即放即闭（开栏长度感知匹配）。超预算截断后下批 fenceOpenAt 恢复
 *        开栏描述符续放；
 *     b. 表格：表头+分隔行齐备后整体放行，其后每条完整表行逐行放行（半行不
 *        放——表格文本重排闪烁；预算不足整行等下批）；
 *     c. 纯文字行（无活动标记）：完整行整行放行；未完行增量放行至快照尾或
 *        预算耗尽——中点截断只落在纯文字上（字面=最终，安全）；
 *     c2. GFM 任务列表项行（#471④-a）：完整行整行放行；复选框+空白前缀
 *        齐备后未完行的条目文字渐进直出——复选框后空白从构造排除 ]( / ][
 *        续接=前缀定案；条目文本经 InlineSpanSafety 安全帽；含 $$/\[ 的
 *        条目行仍整行扣留（跨行数学配对面，与表行同口径）；
 *     d. 含活动标记的行：整行扣留等闭合（空行毕业/完结 flush）。
 *
 * 不变量：
 *  - 结果单调不回退（>= alreadyReleased）——放行流只增不减；
 *  - 放行边界三类：行边界、纯文字字面边界（无重释义）、块边界；
 *  - 扣留内容去路恒两条：毕业或完结 EOF 全量 flush——一字不丢；
 *  - 行续段（floor 落在行中）无表头/围栏语义——表头与开/闭栏判定一律要求
 *    真实行首，防增量截断后的续段被误判成块构造。
 *  - setext 升格（"---"/"===" 紧随文字行）为已知理论缺口（旧闸同样存在），
 *    模型输出以 ATX 标题为主，接受。
 */
internal object SafePrefixGate {

    /**
     * 活动标记字符集：行内出现即差终止符（未闭合内联构造，未来行可闭合回溯
     * 重释义已上屏内容）。☐/☑/✅（#437 阶段 C）：normalizeTaskListMarkers 完结
     * 变换字符——流中字面放行会在完结时替换为 GFM 任务列表，扣留等空行闭合。
     */
        private const val ACTIVE_MARKERS = "*_~#>|[]!`\\☐☑✅"

    /** CommonMark 有序列表起始标记的最多位数。 */
    private const val ORDERED_LIST_MAX_DIGITS = 9

    /**
     * 计算快照 [snapshot] 相对已放行长度 [alreadyReleased] 的安全放行长度。
     * 调用方保证 [alreadyReleased] 是此前某次调用结果（或 0/重建清零）。
     *
     * #438①（2026-09-27）：[maxReleaseChars] 单次调用放行量上限（默认无限——
     * 兼容既有调用/测试）。第一级空行毕业原本不受 [MAX_RELEASE_PER_BATCH]
     * 约束（cand 直接放行）——中继缓冲突发下首跑/catch-up 单批可达整段，
     * 调用方（pilot 铺开循环）以本参数逐批喂入。截断点可落在行中：
     * released 是快照坐标，续放时行扫描的 lineStartReal 防御已覆盖行续段。
     */
    fun releaseLength(snapshot: String, alreadyReleased: Int, maxReleaseChars: Int = Int.MAX_VALUE): Int {
        val floor = alreadyReleased.coerceIn(0, snapshot.length)
        if (floor >= snapshot.length) return floor

        // —— 第一级：空行毕业——开放区内最后一个空行块之后的起点。
        var cand = floor
        var i = floor
        var atLineStart = true
        while (i < snapshot.length) {
            if (atLineStart && isBlankLineAt(snapshot, i)) {
                i = skipBlankLines(snapshot, i)
                cand = i
            } else {
                atLineStart = snapshot[i] == '\n'
                i++
            }
        }

        // —— 第二级：块级行扫描 + 纯文字增量直出（2026-09-26 二次重写）。
        var allowed = cand
        var j = cand
        var curFence: Pair<Char, Int>? = fenceOpenAt(snapshot, cand)
        while (j < snapshot.length) {
            val nl = snapshot.indexOf('\n', j)
            val complete = nl >= 0
            val lineEnd = if (complete) nl else snapshot.length
            val line = snapshot.substring(j, lineEnd)
            val lineStartReal = j == 0 || snapshot[j - 1] == '\n'
            val budgetLeft = MAX_RELEASE_PER_BATCH - (allowed - floor)
            if (budgetLeft <= 0) break
            // #471④-a：任务项行放行目标预计算——锚定**真实行首**判定（lineStartReal
            // 为 false 的行续段同样可恢复：未完行先被兜底分支放行 "- " 后，完整行
            // 到达仍能整行毕业，不再冻结到空行——真机 E2E 实证 v1 行首守卫的冻结面）。
            // 复选框+空白前缀一旦齐备即定案（后续仅追加）：未完行的条目尾部纯文字
            // 经安全帽渐进放行（字面=最终）。含未定案数学开定界符的行不走本分支。
            val taskItemTarget: Int = run {
                val ls = if (lineStartReal) j else snapshot.lastIndexOf('\n', j - 1) + 1
                val realLine = if (ls == j) line else snapshot.substring(ls, lineEnd)
                val off = taskItemMarkerEnd(realLine)
                if (off < 0 || hasUndecidedMathOpen(realLine)) {
                    -1
                } else {
                    val scanFrom = maxOf(j, ls + off)
                    val cut = InlineSpanSafety.safeCut(snapshot, scanFrom)
                    if (complete) minOf(nl + 1, cut) else cut
                }
            }
            when {
                curFence != null -> {
                    // #487 流式块内放行（原预算续放路径升级为主路径）：代码文本
                    // 字面稳定，完整行整行放、未完行增量放。闭栏判定**开栏长度
                    // 感知**（同字符 run ≥ 开栏长 + 仅尾空白）——长度盲判定会让
                    // ```` 外栏内的 ``` 行误闭栏（预算续放路径存量隐患，本批修复）。
                    val open = curFence!!
                    val isClose = complete && lineStartReal && run {
                        val t = line.trim(' ', '\t')
                        var n = 0
                        while (n < t.length && t[n] == open.first) n++
                        n >= open.second && n == t.length
                    }
                    if (isClose) {
                        // 闭栏行只整行放（半行闭栏会误判块态）
                        if (nl + 1 - allowed > budgetLeft) break
                        allowed = nl + 1
                        j = nl + 1
                        curFence = null
                    } else {
                        val want = if (complete) nl + 1 else lineEnd
                        allowed = if (want - allowed > budgetLeft) allowed + budgetLeft else want
                        j = allowed
                        if (j >= snapshot.length) break
                    }
                }
                lineStartReal && isFenceOpen(line) != null -> {
                    // #487 用户裁决 B：开栏行完整即放，不再等闭合整块放行——
                    // 渲染器按 CommonMark 将未闭合围栏延伸至前缀尾渲染为持续
                    // 增长的代码块（探针实证；空行毕业早已在生产渲染此形态）。
                    // 未完开行扣留等完整。
                    if (!complete) break
                    if (nl + 1 - allowed > budgetLeft) break
                    allowed = nl + 1
                    j = nl + 1
                    curFence = isFenceOpen(line)
                }
                // #441 稳态粒度：表格正文行跨批续放——上批已放内容以表格族行收尾时，
                // 本批正文行直接整行放行（原逻辑正文行被 isTableHeaderRow 误判为新表头
                // → 等不存在的分隔行 → 扣留到 EOF = 「表头先出、正文整块最后出」根因）。
                // #471③：含 $$/\[ 的表行扣留（块级数学跨行配对面，闭合到达时归一化
                // 会改写本行——完整行≠定案；性质测试 random 轮实证）。
                lineStartReal && complete && isTableRowLine(line) &&
                    !hasUndecidedMathOpen(line) &&
                    prevReleasedLineIsTableFamily(snapshot, allowed) -> {
                    if (nl + 1 - allowed > budgetLeft) break
                    allowed = nl + 1
                    j = nl + 1
                }
                lineStartReal && complete && isTableHeaderRow(line) &&
                    !hasUndecidedMathOpen(line) -> {
                    val sepNl = snapshot.indexOf('\n', nl + 1)
                    val sep = if (sepNl < 0) "" else snapshot.substring(nl + 1, sepNl)
                    if (!(sepNl >= 0 && isTableSeparatorRow(sep))) break // 表未成形：扣留
                    if (sepNl + 1 - allowed > budgetLeft) break
                    allowed = sepNl + 1
                    var k = sepNl + 1
                    while (k < snapshot.length) {
                        val rNl = snapshot.indexOf('\n', k)
                        if (rNl < 0) break
                        val rowLine = snapshot.substring(k, rNl)
                        if (!isTableRowLine(rowLine) || hasUndecidedMathOpen(rowLine)) break
                        if (rNl + 1 - allowed > budgetLeft) break
                        allowed = rNl + 1
                        k = rNl + 1
                    }
                    j = k
                }
                // #441 粒度扩展：≥4 空格缩进行扣留——缩进代码块/列表延续的歧义形态
                // （半行放行的重释义面不可控），保守等闭合。置于纯文字之前。
                lineStartReal && isIndentedCodeLine(line) -> break
                // #471④-a：GFM 任务列表项行（目标预计算见上）——行级定案 + 未完行
                // 条目文字渐进（用户验收发现「4 条整块一次性吐出」：归一化后 [ 为
                // 硬停字符，整列表只能等空行毕业——原扣留理由（☐ 完结归一化改写）
                // 已随 #471③ 归一化前移消失）。定案论证：复选框后空白/行尾从构造
                // 排除 ]( / ][ 链接续接，后续任何行不可回改已放行的标记前缀；列表
                // 后续行为追加语义（新条目/懒延续文字）。条目文本内未闭合构造经
                // InlineSpanSafety 安全帽（标记后起扫）。已知残余与既有行级分支
                // （引用/ATX）同列：setext 懒延续升格、宽松列表段距变化——内容前缀
                // 永不回写，视觉重排与表格行高度变化同类。
                taskItemTarget > allowed -> {
                    if (taskItemTarget - allowed > budgetLeft) break
                    allowed = taskItemTarget
                    j = taskItemTarget
                }
                !lineHasActiveMarker(line) -> {
                    // 纯文字（完整或未完）：字面=最终——整行/增量直出
                    val want = if (complete) nl + 1 else lineEnd
                    allowed = if (want - allowed > budgetLeft) allowed + budgetLeft else want
                    j = allowed
                    if (j >= snapshot.length) break
                }
                // #441 稳态粒度：'* ' 无序列表项——列表语义行级定案（后续行不会重释义
                // 本行块类型），完整行整行放行（半行扣留：续接内容未定）。'*' 后非空格
                // （强调构造开头）不进本分支，维持扣留。
                lineStartReal && complete && isStarBulletItemLine(line) -> {
                    // #472：行级定案 + 行内安全帽——未闭合构造起扣（修复列表行
                    // 悬空 opener 先放后重排的既有缺口；'* ' 标记星后随空白=
                    // 非侧翼，扫描器天然放行）
                    val target = minOf(nl + 1, InlineSpanSafety.safeCut(snapshot, j))
                    if (target <= allowed || target - allowed > budgetLeft) break
                    allowed = target
                    j = target
                }
                // #441 粒度扩展：引用块行——行级定案（引用行本身不被后续行重释义；
                // 懒延续行（无 > 前缀）仍走扣留分支等空行毕业）。
                lineStartReal && complete && isBlockQuoteLine(line) -> {
                    // #472：行内安全帽从内容前缀起扫（'>' 本身是扫描器硬停符）
                    val target = minOf(
                        nl + 1,
                        InlineSpanSafety.safeCut(snapshot, j + blockMarkerPrefixEnd(line)),
                    )
                    if (target <= allowed || target - allowed > budgetLeft) break
                    allowed = target
                    j = target
                }
                // #441 粒度扩展：ATX 标题行（#{1,6}+空格/行尾）——完整行定案。
                lineStartReal && complete && isAtxHeadingLine(line) -> {
                    // #472：行内安全帽从内容前缀起扫（'#' 本身是扫描器硬停符）
                    val target = minOf(
                        nl + 1,
                        InlineSpanSafety.safeCut(snapshot, j + atxMarkerPrefixEnd(line)),
                    )
                    if (target <= allowed || target - allowed > budgetLeft) break
                    allowed = target
                    j = target
                }
                else -> {
                    // #472：已闭合行内构造即时放行——InlineSpanSafety 给出安全
                    // 前缀（可行中截断，预算内推进）；扣留点起维持旧语义（空行
                    // 毕业/完结 EOF flush 整体接管，一字不丢）
                    val target = InlineSpanSafety.safeCut(snapshot, j)
                    if (target <= allowed) break
                    val adv = minOf(target - allowed, budgetLeft)
                    allowed += adv
                    j = allowed
                    if (j >= snapshot.length) break
                }
            }
        }
        // #438①：放行量总上限（含第一级空行毕业——原不受批预算约束）。
        // 先比较后相加——floor+Int.MAX_VALUE 的任何溢出形态（Long→Int 收窄
        // wrap 同样踩坑）从构造上排除。
        val budget = allowed - floor
        val capped = if (maxReleaseChars >= budget) allowed else floor + maxReleaseChars
        // #471③ 数学定界符流式破口收口（spec §3.3 矩阵修订）：$ 逐字符到达时，
        // 首字符以单美元身份走纯文字直出、次字符以行续段身份再放——跨批凑成
        // $$ 后闭合字符到达触发归一化改写 = 非前缀（性质测试 math-block
        // k=21/22 实证）。截点在快照尾且以 $ run 结尾时退过整个 run（下一
        // 字符可能延长 run 并成对）；截点在快照内部时右邻字符已定案，无需收口。
        var result = maxOf(floor, capped)
        if (result == snapshot.length) {
            while (result > floor && snapshot[result - 1] == '$') result--
        }
        // #471③ 表头行待定三行结构回退（spec §3.3 矩阵修订②）：截点前
        // 紧邻行是表头行、其前紧邻行是文字行（非空、非 | 结尾）时，
        // 「文字行\n表头\n分隔行」结构待定——分隔行到达时归一化会在
        // 文字行与表头行间插空行（ensureBlankLineBeforeGfmTables，改写已
        // 放行前缀——性质测试 random 轮 k=318 实证：空行毕业先放
        // 「文字\n| a |\n\n」，|---| 后到即插空行）。退到表头行行首：
        // 该行走表头分支（分隔行到达后整块放行）或 EOF flush，一字不丢。
        // 正文行虽也匹配 isTableHeaderRow 形态，但其前是 | 行（endsWith
        // "|"）不触发；归一化已插空行后表头前是空行，同样不触发——自洽。
        if (result > floor && result > 0 && snapshot[result - 1] == '\n') {
            val headerLs = snapshot.lastIndexOf('\n', result - 2) + 1
            val headerLine = snapshot.substring(headerLs, result - 1)
            if (isTableHeaderRow(headerLine) && headerLs > 0) {
                val textLe = headerLs - 1
                val textLs = snapshot.lastIndexOf('\n', textLe - 1) + 1
                val textLine = snapshot.substring(textLs, textLe)
                if (textLine.isNotEmpty() && !textLine.endsWith("|")) {
                    result = maxOf(floor, headerLs)
                }
            }
        }
        // 2026-09-30 坍缩重建根修补口（14:58 真机定罪）：批预算把截点落在表头行
        // **行中**（result 非行首、非行尾）时，上方回退以 snapshot[result-1]=='\n'
        // 为前提漏检本形态——纯文字分支视表头行为无标记文本整行/增量直出，已
        // 放行前缀越过未来插空行点（ensureBlankLineBeforeGfmTables 在表头行行
        // 首前插入）→ 分隔行到达即非前缀改写 → RESETKEY 重建坍缩。补口：截点
        // 所在行（到行尾/快照尾）为表头行形态、或截点前行内仅空白（表头缩进未定案
        // ——行首空白不放行视觉零代价）时，回退到该行行首。
        if (result > floor && snapshot[result - 1] != '\n') {
            val ls = snapshot.lastIndexOf('\n', result - 1) + 1
            if (ls in floor until result) {
                val le = snapshot.indexOf('\n', ls).let { if (it < 0) snapshot.length else it }
                val lineComplete = le < snapshot.length
                val fullLine = snapshot.substring(ls, le)
                val beforeCut = snapshot.substring(ls, result)
                val headerShaped = fullLine.isNotEmpty() && isTableHeaderRow(fullLine)
                val onlySpacesBeforeCut =
                    beforeCut.isNotEmpty() && beforeCut.all { it == ' ' || it == '\t' }
                when {
                    headerShaped || onlySpacesBeforeCut -> result = maxOf(floor, ls)
                    // 截点落在**未完分隔行**内（分隔行无换行=三行结构未定案，插空行
                    // 将落在表头行前，而放行已含整条表头行）——级联回退到表头行行首。
                    !lineComplete && isTableSeparatorRow(fullLine) && ls - 1 > floor -> {
                        val hLs = snapshot.lastIndexOf('\n', ls - 2) + 1
                        result = maxOf(floor, hLs)
                    }
                }
            }
        }
        return result
    }

    /**
     * #471③：行含未定案块级数学开定界符（$$ / \[——transformMathFallback
     * 跨行配对面，闭合到达前归一化对本行的改写未定案）。完整行 ≠ 定案，
     * 整行扣留到空行毕业/EOF。\( 行内闭合同行定案，不在此列。
     */
    private fun hasUndecidedMathOpen(line: String): Boolean =
        line.contains("$$") || line.contains("\\[")

    // ===== 块级判定辅助（2026-09-26 行扫描二次重写） =====

    /** #441：已放行内容以表格族行（表头/分隔/正文）收尾——表格续放判据。 */
    private fun prevReleasedLineIsTableFamily(snapshot: String, allowed: Int): Boolean {
        if (allowed <= 0) return false
        var lineStart = snapshot.lastIndexOf('\n', allowed - 1) + 1
        var lineEnd = allowed
        // 跳过 allowed 位置的假行尾：上一真实行 = [lineStart, 上一个\n]
        val prevNl = snapshot.lastIndexOf('\n', allowed - 1)
        if (prevNl < 0) { lineStart = 0; lineEnd = allowed } 
        else { lineStart = snapshot.lastIndexOf('\n', prevNl - 1) + 1; lineEnd = prevNl }
        if (lineEnd <= lineStart) return false
        val prevLine = snapshot.substring(lineStart, lineEnd)
        return isTableHeaderRow(prevLine) || isTableSeparatorRow(prevLine) || isTableRowLine(prevLine)
    }

    /** #441 粒度扩展：引用块行——≤3 缩进 + '>' 起始。 */
    private fun isBlockQuoteLine(line: String): Boolean {
        var i = 0
        var indent = 0
        while (i < line.length && indent < 4 && (line[i] == ' ' || line[i] == '\t')) { i++; indent++ }
        return i < line.length && line[i] == '>'
    }

    /** #441 粒度扩展：ATX 标题行——≤3 缩进 + 1..6 个 '#' + 空格或行尾。 */
    private fun isAtxHeadingLine(line: String): Boolean {
        var i = 0
        var indent = 0
        while (i < line.length && indent < 4 && (line[i] == ' ' || line[i] == '\t')) { i++; indent++ }
        var h = 0
        while (i < line.length && h < 6 && line[i] == '#') { i++; h++ }
        if (h == 0) return false
        return i >= line.length || line[i] == ' ' || line[i] == '\t'
    }

    /** #441 粒度扩展：≥4 空格缩进行（缩进代码块/列表延续歧义形态）。 */
    private fun isIndentedCodeLine(line: String): Boolean {
        var sp = 0
        for (k in 0 until line.length.coerceAtMost(8)) {
            when (line[k]) {
                ' ' -> sp++
                '\t' -> return true
                else -> return sp >= 4
            }
        }
        return sp >= 4
    }

    /**
     * #441：列表项行（≤3 缩进）——'* '/'- '/'+ ' 无序或 数字+'.'/')' 有序开头。
     * 列表项行级定案（后续行不重释义本行块类型）；'- '/'+ ' 本就无标记走纯文字，
     * 此处覆盖 '*' 与有序两形态（原二者整块扣留 = 用户「列表整块出」根因）。
     */
    private fun isStarBulletItemLine(line: String): Boolean {
        var i = 0
        var indent = 0
        while (i < line.length && indent < 4 && (line[i] == ' ' || line[i] == '\t')) { i++; indent++ }
        if (i >= line.length) return false
        val c = line[i]
        if (c == '*') {
            val next = i + 1
            return next >= line.length || line[next] == ' ' || line[next] == '\t'
        }
        // 有序：1-9 位数字 + '.'或')' + 空格/行尾
        if (c.isDigit()) {
            var d = i
            while (d < line.length && d - i < ORDERED_LIST_MAX_DIGITS && line[d].isDigit()) d++
            if (d > i && d < line.length && (line[d] == '.' || line[d] == ')')) {
                val next = d + 1
                return next >= line.length || line[next] == ' ' || line[next] == '\t'
            }
        }
        return false
    }

    /**
     * #471④-a：GFM 任务列表项行——≤3 缩进 + 列表标记（无序 -、+、* 或
     * 有序 数字加'.'或')'）+ 空白 + 复选框 [ ]、[x]、[X] + 空白或行尾（GFM
     * 任务项语义，与
     * normalizeTaskListMarkers 的产出形态一致）。
     * 返回复选框标记后首字符相对行首的偏移（InlineSpanSafety 起扫点）；
     * 裸项（复选框即行尾）返回行长；非任务项行返回 -1。
     */
    private fun taskItemMarkerEnd(line: String): Int {
        var i = 0
        var indent = 0
        while (i < line.length && indent < 4 && (line[i] == ' ' || line[i] == '\t')) { i++; indent++ }
        if (i >= line.length) return -1
        val c = line[i]
        val afterListMarker: Int = when {
            c == '-' || c == '+' || c == '*' -> i + 1
            c.isDigit() -> {
                var d = i
                while (d < line.length && d - i < ORDERED_LIST_MAX_DIGITS && line[d].isDigit()) d++
                if (d > i && d < line.length && (line[d] == '.' || line[d] == ')')) d + 1 else return -1
            }
            else -> return -1
        }
        var m = afterListMarker
        if (m >= line.length || (line[m] != ' ' && line[m] != '\t')) return -1
        while (m < line.length && (line[m] == ' ' || line[m] == '\t')) m++
        if (m + 3 > line.length || line[m] != '[') return -1
        val box = line[m + 1]
        if (box != ' ' && box != 'x' && box != 'X') return -1
        if (line[m + 2] != ']') return -1
        val afterBox = m + 3
        if (afterBox < line.length && line[afterBox] != ' ' && line[afterBox] != '\t') return -1
        return if (afterBox < line.length) afterBox + 1 else line.length
    }

    /** 开栏行：≤3 空白缩进 + ≥3 个反引号或 ~ + info string（反引号栏 info 不得含反引号）。返回 栏字符 to 栏长。 */
    private fun isFenceOpen(line: String): Pair<Char, Int>? {
        var i = 0
        var indent = 0
        while (i < line.length && indent < 3 && (line[i] == ' ' || line[i] == '\t')) { i++; indent++ }
        if (i >= line.length) return null
        val c = line[i]
        if (c != '`' && c != '~') return null
        var n = 0
        while (i + n < line.length && line[i + n] == c) n++
        if (n < 3) return null
        val rest = line.substring(i + n)
        if (c == '`' && rest.indexOf('`') >= 0) return null
        return c to n
    }

    /**
     * [pos] 处所处的未闭合围栏开栏描述符（栏字符 to 栏长）；栏外 = null——
     * 从快照行首扫描到 pos 恢复围栏状态。行含 pos 时读**整行**（开栏判定需
     * 完整行）；闭栏只在该行完整越过 pos 时生效（pos 在闭栏行中=仍在栏内——
     * 闭栏行经整行放行，floor 不会落在其中，此防御针对任意调用方）。
     * #487：返回值从 Boolean 升级为开栏描述符——预算续放/流中闭栏判定需要
     * 开栏长度感知匹配（防 ```` 外栏内 ``` 行误闭栏）。每批 O(n)（n=快照长，
     * 48ms 批节奏下可忽略）。
     */
    private fun fenceOpenAt(snapshot: String, pos: Int): Pair<Char, Int>? {
        var open: Pair<Char, Int>? = null
        var k = 0
        while (k < pos) {
            val nl = snapshot.indexOf('\n', k)
            val lineEnd = if (nl < 0) snapshot.length else nl
            val fullLine = snapshot.substring(k, lineEnd)
            val linePassed = nl >= 0 && nl + 1 <= pos
            val cur = open
            if (cur != null) {
                if (linePassed) {
                    val t = fullLine.trim(' ', '\t')
                    var n = 0
                    while (n < t.length && t[n] == cur.first) n++
                    if (n >= cur.second && n == t.length) open = null
                }
            } else {
                val o = isFenceOpen(fullLine)
                if (o != null) open = o
            }
            if (nl < 0 || lineEnd >= pos) break
            k = lineEnd + 1
        }
        return open
    }

    /** #472：引用行块标记前缀长（≤3 缩进 + '>' 串 + 至多一空格）——行内扫描起点。 */
    private fun blockMarkerPrefixEnd(line: String): Int {
        var i = 0
        var indent = 0
        while (i < line.length && indent < 4 && (line[i] == ' ' || line[i] == '\t')) { i++; indent++ }
        while (i < line.length && line[i] == '>') i++
        if (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return i
    }

    /** #472：ATX 行块标记前缀长（≤3 缩进 + '#' 串 + 至多一空格）。 */
    private fun atxMarkerPrefixEnd(line: String): Int {
        var i = 0
        var indent = 0
        while (i < line.length && indent < 4 && (line[i] == ' ' || line[i] == '\t')) { i++; indent++ }
        while (i < line.length && line[i] == '#') i++
        if (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return i
    }

    /** 行内含活动标记（未闭合内联构造风险；#472 增补 ''——转义/escaped-backtick 重释义面）、双美元（完结数学变换）或行首有序列表起始。单美元是普通文字。 */
    private fun lineHasActiveMarker(line: String): Boolean {
        for (k in line.indices) {
            val c = line[k]
            if (ACTIVE_MARKERS.indexOf(c) >= 0) return true
            if (c == '$' && k + 1 < line.length && line[k + 1] == '$') return true
        }
        return isOrderedListStart(line, 0)
    }

    /** 表行：非空白起始为 |（与 GFM 宽松判定一致——逐行渐显足够）。 */
    private fun isTableRowLine(line: String): Boolean = line.trimStart(' ', '\t').startsWith("|")

    /** 放行决策：新放行长度（快照坐标）+ 实际交给 append 的 delta 文本。 */
    internal class ReleaseDecision(val newReleased: Int, val delta: String)

    /**
     * #471③：放行决策（gate 入口已收归一化文本）。
     *
     * #437 阶段 C 的「放行 delta 内表格粘边空行注入」退役——归一化前移后
     * ensureBlankLineBeforeGfmTables 已在 pilot ingest（gate 入口前）对整快照
     * 插空行，注入恒空转。「state.content 与快照长度解耦」语义随之消失
     * （delta = 快照区间原文，坐标重新耦合）。
     */
    fun releaseDelta(snapshot: String, alreadyReleased: Int, maxReleaseChars: Int = Int.MAX_VALUE): ReleaseDecision {
        val r = releaseLength(snapshot, alreadyReleased, maxReleaseChars)
        val from = alreadyReleased.coerceIn(0, snapshot.length)
        return ReleaseDecision(r, snapshot.substring(from, r))
    }

    /** 单批放行预算（字符）——纯文字直出的节奏上限与毕业/块铺开的单批量上限。 */
    private const val MAX_RELEASE_PER_BATCH = 400

    /** 表头行：可选缩进 + | 开头 + | 结尾（含至少一个内部字符）。 */
    private fun isTableHeaderRow(line: String): Boolean {
        val t = line.trimStart(' ', '\t')
        return t.length >= 2 && t.startsWith('|') && t.endsWith('|') && t.length > 2
    }

    /** 分隔行：可选缩进 + | 包裹，内部仅 - : 空格 | 且含 -。 */
    private fun isTableSeparatorRow(line: String): Boolean {
        val t = line.trimStart(' ', '\t')
        if (t.length < 3 || !t.startsWith('|') || !t.endsWith('|')) return false
        var hasDash = false
        for (c in t.substring(1, t.length - 1)) {
            when {
                c == '-' -> hasDash = true
                c == ':' || c == ' ' || c == '\t' || c == '|' -> Unit
                else -> return false
            }
        }
        return hasDash
    }

    /** [i] 是行首且该行是空行（仅空白字符直到换行/结尾）。 */
    private fun isBlankLineAt(s: String, i: Int): Boolean {
        var k = i
        while (k < s.length) {
            val c = s[k]
            if (c == '\n') return true
            if (c != ' ' && c != '\t' && c != '\r') return false
            k++
        }
        return true // 结尾的空白行
    }

    /** 从空行行首 [i] 起跨过连续空行，返回下一非空行的行首。 */
    private fun skipBlankLines(s: String, i: Int): Int {
        var k = i
        while (k < s.length && isBlankLineAt(s, k)) {
            while (k < s.length && s[k] != '\n') k++
            if (k < s.length) k++ // 跨过换行
            else return s.length
        }
        return k
    }

    /** [i] 是行首且匹配有序列表起始：1-9 位数字 + [.)] + 空格或行尾。 */
    private fun isOrderedListStart(s: String, i: Int): Boolean {
        var k = i
        var digits = 0
        while (k < s.length && s[k] in '0'..'9' && digits < ORDERED_LIST_MAX_DIGITS) { k++; digits++ }
        if (digits == 0 || digits > ORDERED_LIST_MAX_DIGITS) return false
        if (k >= s.length) return false
        val marker = s[k]
        if (marker != '.' && marker != ')') return false
        k++
        return k >= s.length || s[k] == ' ' || s[k] == '\t'
    }
}
