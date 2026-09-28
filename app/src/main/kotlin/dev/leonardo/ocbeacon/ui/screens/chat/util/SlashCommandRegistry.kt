package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R

/**
 * 建议弹窗的斜杠命令定义。
 * @param name 不含 "/" 前缀的命令名
 * @param description 人类可读的描述
 * @param type "server" 命令通过 API 发送，"client" 命令触发本地动作
 */
internal data class SlashCommand(
    val name: String,
    val description: String?,
    val type: String, // "server"、"client" 或 "skill"（#324④ skills 触发组）
    /** 打字路径携带的自由参数（/rename testx → "testx"）；面板 tap 恒 null。2026-09-09 发现通道级缺失：typed 分支曾丢弃 commandArgs。 */
    val args: String? = null,
    /** 需要自由输入参数（DSH commands/list 的 input.hint 非空）：选择即填入输入框而非直接执行。 */
    val requiresInput: Boolean = false,
    /** #324④：skill 专属——模型可自主调用标识（skills/list modelInvocable）。 */
    val modelInvocable: Boolean = false,
)

/** 客户端斜杠命令注册表 —— 从 ChatInputBar.kt 抽取。 */
internal object SlashCommandRegistry {

    /**
     * 客户端命令名集（顺序 = 建议列表展示序）。单一真相源：建议列表
     * （[clientCommands]）与发送缝分流（ChatScreenBottomBar doSend）共源，
     * 防两处漂移。2026-09-09（G2-① 根修）增设——打字路径的客户端命令
     * 此前静默落 prompt 通道喂模型。
     */
    val clientCommandNames: List<String> = listOf(
        "new", "compact", "fork", "share", "unshare", "undo", "redo", "rename", "shell",
    )

    /** 镜像原始 opencode TUI 的客户端斜杠命令。 */
    @Composable
    fun clientCommands(): List<SlashCommand> = clientCommandNames.map { name ->
        SlashCommand(name, descriptionFor(name), "client")
    }

    @Composable
    private fun descriptionFor(name: String): String? = when (name) {
        "new" -> stringResource(R.string.cmd_new)
        "compact" -> stringResource(R.string.cmd_compact)
        "fork" -> stringResource(R.string.cmd_fork)
        "share" -> stringResource(R.string.cmd_share)
        "unshare" -> stringResource(R.string.cmd_unshare)
        "undo" -> stringResource(R.string.cmd_undo)
        "redo" -> stringResource(R.string.cmd_redo)
        "rename" -> stringResource(R.string.cmd_rename)
        "shell" -> stringResource(R.string.cmd_shell_mode)
        else -> null
    }
}
/**
 * 2026-09-16（用户需求）：光标所在 slash token 触发建议——
 * 旧实现仅当整串以 "/" 开头才弹建议；现在光标所在的空白分隔词以 "/"
 * 开头即触发（中段输入可用），查询词 = "/" 后到光标的部分。
 *
 * @return "/" 之后的查询词（已小写）；光标不在 slash token 内时 null。
 */
internal fun slashQueryAt(text: String, cursor: Int): String? {
    val range = slashTokenRangeAt(text, cursor) ?: return null
    return text.substring(range.first + 1, cursor).lowercase()
}

/**
 * 光标所在 slash token 的完整字符区间（含 "/"，覆盖光标左右直到空白）。
 * 查询仅看 slash→光标；点选命令时用完整区间替换，避免光标在词中间时残留后缀。
 */
internal fun slashTokenRangeAt(text: String, cursor: Int): IntRange? {
    if (cursor !in 0..text.length) return null
    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    val prefix = text.substring(start, cursor)
    if (!prefix.startsWith("/")) return null
    var endExclusive = cursor
    while (endExclusive < text.length && !text[endExclusive].isWhitespace()) endExclusive++
    return IntRange(start, endExclusive - 1)
}

internal data class SlashCommandInsertion(val text: String, val cursor: Int)

/** 用完整命令替换光标所在 slash token；不发送。 */
internal fun insertSlashCommandAt(text: String, cursor: Int, commandText: String): SlashCommandInsertion {
    val range = slashTokenRangeAt(text, cursor)
    val start = range?.first ?: text.length
    var endExclusive = range?.last?.plus(1) ?: text.length
    // commandText 自带尾随空格：若 token 后已有单个空格则一并吞掉，避免双空格。
    if (commandText.endsWith(' ') && endExclusive < text.length && text[endExclusive] == ' ') {
        endExclusive++
    }
    val newText = text.replaceRange(start, endExclusive, commandText)
    return SlashCommandInsertion(newText, start + commandText.length)
}
