package dev.leonardo.ocbeacon.ui.screens.chat.tools

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * DSH spill 溢出通知的固定拼写识别器（#536；backlog #332 用户裁决降级实现，2026-10-09）。
 *
 * ## 约定来源（逐常量对照 dsh-spill-policy@0.2.0-rc.2 `lib/types/notice.js`——
 * 现网运行安装树实证，勿凭 0.2.1-alpha 调研版改拼写）
 *
 * 通知格式（`formatSpillNotice`）：
 * ```
 * (Omitted N bytes.[ Omitted M images.] Full formatted result stored at: <locator>. <retrievalHint>)
 * ```
 * - 溢出子句三档（`describeOmitted(omitted, "bytes")`）：exact=`Omitted N bytes.`；
 *   unknown=`More bytes were omitted.`；none=``（空）。图像子句 ` Omitted M images.`
 *   仅 M≥1 出现，追加在溢出子句后。
 * - `LOCATION = " Full formatted result stored at: "`、`GUIDANCE_SEPARATOR = ". "`、
 *   预览与通知之间 `SEPARATOR = "\n\n"`、整体包裹 `(`…`)`。
 * - 识别（`hasSpillNotice`）：文本以 `)` 结尾；按 `\n\n(` 逐段扫描，候选段以 `(` 开头、
 *   含 LOCATION、且 `(` 与 LOCATION 之间的子句通过 `isOmission`（可选尾随图像子句
 *   正则 + 三档匹配，count 须安全整数 ≥0），再要求 LOCATION 之后存在 `. ` 指引分隔。
 *
 * ## 上游明示的局限（实现必须随附，防止后人误当结构化契约）
 *
 * 上游类型注释与 README Known Limitations 首条原文：识别的是**持久化文本约定**，
 * "not authenticated tool-output origin"——任何工具打印同样文案都会命中；这不是
 * 服务器对溢出的结构化断言。#332 的结构化信号前提（session 事件/tool-result 载荷
 * 带 spill 字段）在 0.2.1-alpha.1 仍不存在（2026-10-09 复验），本识别器是降级方案；
 * 上游若暴露结构化字段，应切换字段并退役本文件（观察锚=backlog #332）。
 *
 * ## 为何在渲染层识别而非数据层改写
 *
 * mapper/存储层对服务器文本保真透传是取证基线（journal 原文可复查）；本识别器只在
 * 展示前把尾部通知块剥离为横幅（[previewOf]），存储与回放路径不感知。与
 * BashToolCard 既有 OpenCode 截断约定（"...output truncated... Full output saved to:"）
 * 的处理同构——两约定并存（OpenCode/DSH 服务器拼写不同），互不干扰。
 */
internal data class SpillNotice(
    /** exact 档省略字节数；null=unknown/none 档（横幅走无数量文案）。 */
    val omittedBytes: Long?,
    /** 省略图像数（≥1 才出现于通知；null=无图像子句）。 */
    val omittedImages: Int?,
    /** 全文落盘定位符（路径/locator 原样）。 */
    val locator: String,
    /** 取回指引（服务器措辞原样，如 "Use read with offset/limit…"）。 */
    val retrievalHint: String,
    /** 通知块在原文中的起点（含前导 `\n\n` 分隔）——展示文本截至此处。 */
    val previewEndExclusive: Int,
)

internal object SpillNoticeMatcher {

    private const val OPEN = "("
    private const val CLOSE = ")"
    private const val LOCATION = " Full formatted result stored at: "
    private const val GUIDANCE = ". "
    private const val SEGMENT = "\n\n"

    /** 图像子句（上游 ` Omitted ([1-9][0-9]*) images\.$`——仅 ≥1，无前导空格差异容忍）。 */
    private val IMAGE_CLAUSE = Regex(""" Omitted ([1-9][0-9]*) images\.$""")

    /** exact 档全串匹配（上游以 `describeOmitted({exact,count})` 重构比对等价；18 位上限防伪 Long 溢出）。 */
    private val EXACT_BYTES = Regex("""^Omitted (\d{1,18}) bytes\.$""")

    private const val UNKNOWN_CLAUSE = "More bytes were omitted."

    /**
     * 识别并解析文本尾部的 spill 通知；无通知返回 null。容忍多段文本（跳过非通知段，
     * 与上游逐段扫描一致）；上游在 isOmission 通过而指引缺失时直接判否，本实现放宽为
     * 继续扫描（更宽容，不影响标准格式）。
     */
    fun match(text: String): SpillNotice? {
        if (!text.endsWith(CLOSE)) return null
        var start = 0
        while (true) {
            val next = text.indexOf(SEGMENT + OPEN, start)
            val candidateEnd = if (next < 0) text.length - CLOSE.length else next
            val candidate = text.substring(start, candidateEnd)
            val location = candidate.indexOf(LOCATION, OPEN.length)
            if (candidate.startsWith(OPEN) && location >= 0) {
                val omission = parseOmission(candidate.substring(OPEN.length, location))
                if (omission != null) {
                    val after = candidate.substring(location + LOCATION.length)
                    val guidance = after.indexOf(GUIDANCE)
                    if (guidance >= 0) {
                        return SpillNotice(
                            omittedBytes = omission.bytes,
                            omittedImages = omission.images,
                            locator = after.substring(0, guidance),
                            retrievalHint = after.substring(guidance + GUIDANCE.length),
                            previewEndExclusive = start,
                        )
                    }
                }
            }
            if (next < 0) return null
            start = next + SEGMENT.length
        }
    }

    /** 展示用文本：截去尾部通知块（含前导 `\n\n`）并去尾随空白；无通知原样返回。 */
    fun previewOf(text: String): String {
        val notice = match(text) ?: return text
        return text.substring(0, notice.previewEndExclusive).trimEnd()
    }

    private data class Omission(val bytes: Long?, val images: Int?)

    /** 上游 isOmission 移植：可选尾随图像子句 → 三档（none 空/unknown/exact）。 */
    private fun parseOmission(clause: String): Omission? {
        var text = clause
        var images: Int? = null
        val imageMatch = IMAGE_CLAUSE.find(text)
        if (imageMatch != null) {
            images = imageMatch.groupValues[1].toIntOrNull() ?: return null
            text = text.substring(0, imageMatch.range.first)
        }
        return when {
            text.isEmpty() -> Omission(bytes = null, images = images)
            text == UNKNOWN_CLAUSE -> Omission(bytes = null, images = images)
            else -> EXACT_BYTES.find(text)?.let { Omission(it.groupValues[1].toLongOrNull(), images) }
        }
    }
}

/** spill 横幅（BashToolCard 的 OpenCode 截断横幅同款视觉：labelSmall + tertiary）。 */
@Composable
internal fun SpillNoticeBanner(notice: SpillNotice, modifier: Modifier = Modifier) {
    val bytes = notice.omittedBytes
    val omitted = when {
        bytes != null && notice.omittedImages != null -> "$bytes bytes + ${notice.omittedImages} images"
        bytes != null -> "$bytes bytes"
        notice.omittedImages != null -> "${notice.omittedImages} images"
        else -> null
    }
    val text = if (omitted != null) {
        stringResource(R.string.chat_output_spilled, omitted, notice.locator)
    } else {
        stringResource(R.string.chat_output_spilled_unknown, notice.locator)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = modifier.padding(horizontal = SpacingTokens.XS.dp),
    )
}
