package dev.leonardo.ocbeacon.domain.supervisor

import kotlinx.serialization.Serializable

/**
 * operator-view.json 单镜像 DTO（docs/portable-supervisor-contract.md「Operator read model」）。
 *
 * supervisor 发布端每 5s 原子重写该文件；Beacon 只读。字段与
 * ez-omo-config supervisor/src/operator-view.ts 的 OperatorView 逐一对齐。
 * 镜像 ez-omo-config supervisor/src/operator-view-reader.ts 的 zod schema：
 * TS 侧必填字段（无默认值）缺失即校验失败——Kotlin 侧同样不给默认值，
 * kotlinx.serialization 对缺失键抛 MissingFieldException → INVALID_SCHEMA。
 * `root`/`actionClass`/`escalationKind` 为 2026-09-25 增补字段（TS 侧 optional，
 * 旧发布端镜像可缺省——nullable 承载兼容）。TS `.strict()` 的未知键拒绝是
 * 刻意未镜像的偏差：`ignoreUnknownKeys` 保留两侧前向兼容（见下）。
 */
@Serializable
data class OperatorViewDto(
    val schemaVersion: Int,
    val generation: Long,
    val lastSeq: Long,
    val producedAt: String,
    val cards: List<OperatorViewCardDto>,
)

@Serializable
data class OperatorViewCardDto(
    val id: String,
    val rootLabel: String,
    /** 完整 root 路径（增补字段，TS optional）：Seam 4 回复信封按 root 寻址；缺省=失败安全空串。 */
    val root: String? = null,
    val sessionLabel: String,
    val reasonText: String,
    val premiseTexts: List<String>,
    val ageSeconds: Long,
    /** A/B/C/D 频段（A 最紧急）。 */
    val severity: String,
    val jumpAvailable: Boolean,
    /** 队列动作类别（ESCALATE/CONTINUE/STEER…；增补字段，TS optional）。 */
    val actionClass: String? = null,
    /** 升级类别 DECISION/INFORMATION/APPROVAL（增补字段，TS optional；缺省=非升级）。 */
    val escalationKind: String? = null,
)
