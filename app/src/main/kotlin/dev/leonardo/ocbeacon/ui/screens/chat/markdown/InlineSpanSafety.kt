package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * #472 行内闭合构造安全扫描器（纯函数，JVM 可测）。
 *
 * 语义：给定快照与未放行起点 [from]，返回可安全增量放行的最大前缀位置
 * （快照坐标，恒 >= from）。已闭合的 emphasis/strong/strikethrough/code span
 * 与其后纯文字 = 字面定案可直出；以下情况扣留：
 *  - 未闭合开标记（emphasis 家族 canOpen 悬空 / 反引号 span 无等长闭符）；
 *  - 硬停字符：\ [ ] ! | # > 与 $$（转义/链接/表格/引用/数学的回溯重释义面）
 *    及 ☐☑✅（完结归一化变换字符）——硬停前的纯文字仍放行；
 *  - 快照尾未知侧翼：run 触尾时下一字符可翻转 flanking 判定（同时防跨批
 *    run 撕裂——run 永不在 EOF 处放行，后缀不可能把已放行 run 接长）。
 *
 * 判定逐字镜像 markdown-jvm 0.7.9（渲染库）：
 *  - DelimiterParser.canOpenClose：* 与 ~ canSplitText=true（纯侧翼判定），
 *    _ 走严格分支（词内不成对）；
 *  - EmphasisLikeParser.balanceDelimiters：cmark look-back（近者优先）+
 *    rule of 3 + openers_bottom 下界（run 粒度等价改写——逐 char 条目的
 *    邻接跳跃在 run 粒度退化为逐 run 递减，匹配结果不变）；
 *  - BacktickParser：顺序等长闭符（中间不等长 run 作内容跳过）。
 *
 * 安全论证（归纳不变量）：已放行区无悬空 canOpen run、无跨切点配对 span；
 * 库算法中已配对 run 永不重配，后缀只可能消费悬空 opener——故放行前缀的
 * 渲染在后缀到达后不变。保守方向：任何不确定（EOF 侧翼、跨反斜杠闭符搜索、
 * 混合长度歧义）一律扣留——最坏不劣于旧闸的整行扣留。
 */
internal object InlineSpanSafety {

    fun safeCut(snapshot: String, from: Int): Int {
        val n = snapshot.length
        val start = from.coerceIn(0, n)
        if (start >= n) return start
        var cut = n
        val runs = ArrayList<Run>()
        var i = start
        walk@ while (i < n) {
            val c = snapshot[i]
            when {
                c == '\\' || c == '[' || c == ']' || c == '!' || c == '|' ||
                    c == '#' || c == '>' || c == '☐' || c == '☑' || c == '✅' -> {
                    cut = i
                    break@walk
                }
                c == '$' -> {
                    var l = 1
                    while (i + l < n && snapshot[i + l] == '$') l++
                    // 双美元=完结数学变换面；行尾单美元下一字符未知（可能成 $$）
                    if (l >= 2 || i + l >= n) {
                        cut = i
                        break@walk
                    }
                    i += l
                }
                c == '`' -> {
                    var l = 1
                    while (i + l < n && snapshot[i + l] == '`') l++
                    // 顺序等长闭符搜索（镜像 BacktickParser.findOfSize）。反斜杠
                    // 屏障：库对 escaped-backtick 有有效长度-1/-2 怪语义，镜像
                    // 有分歧风险——保守不跨（宁可整 span 扣到段落末）。
                    var j = i + l
                    var spanEnd = -1
                    search@ while (j < n) {
                        val cj = snapshot[j]
                        if (cj == '\\') break@search
                        if (cj == '`') {
                            var m = 1
                            while (j + m < n && snapshot[j + m] == '`') m++
                            if (m == l) {
                                spanEnd = j + m
                                break@search
                            }
                            j += m
                        } else {
                            j++
                        }
                    }
                    if (spanEnd < 0) {
                        cut = i
                        break@walk
                    }
                    i = spanEnd
                }
                c == '*' || c == '_' || c == '~' -> {
                    var l = 1
                    while (i + l < n && snapshot[i + l] == c) l++
                    if (i + l >= n) {
                        cut = i
                        break@walk
                    }
                    runs.add(classifyRun(snapshot, i, l, c))
                    i += l
                }
                else -> i++
            }
        }
        if (runs.isNotEmpty()) cut = minOf(cut, matchAndBlock(runs, cut))
        return maxOf(start, cut)
    }

    // ===== run 侧翼分类（镜像 DelimiterParser.canOpenClose） =====

    private class Run(
        val marker: Char,
        val start: Int,
        val length: Int,
        var canOpen: Boolean,
        var canClose: Boolean,
        var closerRun: Int = -1,
    )

    private fun classifyRun(s: String, start: Int, length: Int, marker: Char): Run {
        val prev: Char? = if (start > 0) s[start - 1] else null
        val next = s[start + length]
        val wsP = prev?.let { isMdWs(it) } ?: true
        val punctP = prev?.let { isMdPunct(it) } ?: false
        val wsN = isMdWs(next)
        val punctN = isMdPunct(next)
        val left = !wsN && (!punctN || wsP || punctP)
        val right = (prev == null || prev != marker) && !wsP && (!punctP || wsN || punctN)
        val canSplit = marker == '*' || marker == '~'
        val canOpen = if (canSplit) left else left && (!right || punctP)
        val canClose = if (canSplit) right else right && (!left || punctN)
        return Run(marker, start, length, canOpen, canClose)
    }

    // ===== 配对镜像（EmphasisLikeParser.balanceDelimiters，run 粒度） =====

    private fun matchAndBlock(runs: List<Run>, walkCut: Int): Int {
        val bottom = HashMap<Char, IntArray>()
        for (ci in runs.indices) {
            val closer = runs[ci]
            if (!closer.canClose) continue
            val arr = bottom.getOrPut(closer.marker) { IntArray(6) { -1 } }
            val slot = (if (closer.canOpen) 3 else 0) + closer.length % 3
            val minOpener = arr[slot]
            var oi = ci - 1
            val firstCandidate = oi
            var matched = false
            while (oi > minOpener) {
                val opener = runs[oi]
                if (opener.marker == closer.marker && opener.canOpen &&
                    opener.closerRun < 0 && !violatesRuleOfThree(opener, closer)
                ) {
                    opener.closerRun = ci
                    opener.canClose = false
                    closer.canOpen = false
                    matched = true
                    break
                }
                oi--
            }
            if (!matched) arr[slot] = firstCandidate
        }
        var limit = walkCut
        for (r in runs) {
            if (r.canOpen && r.closerRun < 0) limit = minOf(limit, r.start)
        }
        if (limit < walkCut) {
            // 配对 span 跨越扣留点 → 开标记一并扣（防行中截断 span 的字面→重排闪烁）
            var changed = true
            while (changed) {
                changed = false
                for (r in runs) {
                    if (r.closerRun >= 0 && runs[r.closerRun].start >= limit && r.start < limit) {
                        limit = r.start
                        changed = true
                    }
                }
            }
        }
        return limit
    }

    private fun violatesRuleOfThree(opener: Run, closer: Run): Boolean =
        (opener.canClose || closer.canOpen) &&
            ((opener.length + closer.length) % 3 == 0) &&
            (opener.length % 3 != 0 || closer.length % 3 != 0)

    // ===== 字符分类（镜像 CommonDefsImplJvm） =====

    private fun isMdWs(c: Char): Boolean =
        c == '\u0000' || Character.isSpaceChar(c) || c.isWhitespace()

    private fun isMdPunct(c: Char): Boolean =
        c == '$' || c == '^' || c == '`' || (PUNCTUATION_MASK shr Character.getType(c)) and 1 != 0

    private val PUNCTUATION_MASK = (1 shl Character.DASH_PUNCTUATION.toInt()) or
        (1 shl Character.START_PUNCTUATION.toInt()) or
        (1 shl Character.END_PUNCTUATION.toInt()) or
        (1 shl Character.CONNECTOR_PUNCTUATION.toInt()) or
        (1 shl Character.OTHER_PUNCTUATION.toInt()) or
        (1 shl Character.INITIAL_QUOTE_PUNCTUATION.toInt()) or
        (1 shl Character.FINAL_QUOTE_PUNCTUATION.toInt()) or
        (1 shl Character.MATH_SYMBOL.toInt())
}
