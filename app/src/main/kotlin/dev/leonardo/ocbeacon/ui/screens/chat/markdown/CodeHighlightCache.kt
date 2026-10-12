package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.ui.text.AnnotatedString

/**
 * #488 批2（完结/冷看色 pop 根修）：进程级代码高亮结果缓存。
 *
 * 动机：SafeHighlightedCode 的 produceState 初值是纯文本，着色作业异步完成
 * （首帧纯色→次帧着色的 pop）——组合位随滚动销毁后冷重入每次重付。小块
 * （≤[SYNC_HIGHLIGHT_CAP]）同步初值直接消除；大块同步超帧预算（JVM 实测
 * 1.3µs/char 线性，4.7KB≈7ms，Android ×3 折算超 16ms 帧），改为**缓存命中等
 * 价零成本**：首次异步着色后落缓存，滚动离开再回看（重复阅读史料的常见路径）
 * 首帧即着色。
 *
 * 与 [EverStreamedPartLedger] 同域同模式（进程级 LRU + @Synchronized），但缓存
 * 的是派生着色结果而非渲染状态——键含 theme 身份（主题/AMOLED 切换不串色），
 * 空结果（未知语言静默纯色）不入缓存（命中无法与 miss 区分，且无重取价值）。
 */
internal object CodeHighlightCache {

    /** 上限条数：单屏代码块 ≤3-4 个 × 若干会话回看工作集，32 足够且封顶内存。 */
    private const val MAX_ENTRIES = 32

    /** 单条大小上限（字符）：超长块（巨型粘贴/生成文件）不占缓存槽。 */
    private const val MAX_CACHED_CHARS = 64_000

    private val lru = LinkedHashMap<String, AnnotatedString>(16, 0.75f, true)

    @Synchronized
    fun get(key: String): AnnotatedString? = lru[key]

    @Synchronized
    fun put(key: String, value: AnnotatedString) {
        if (value.text.length > MAX_CACHED_CHARS) return
        lru[key] = value
        while (lru.size > MAX_ENTRIES) {
            lru.remove(lru.keys.first())
        }
    }

    @Synchronized
    fun clear() = lru.clear()
}
