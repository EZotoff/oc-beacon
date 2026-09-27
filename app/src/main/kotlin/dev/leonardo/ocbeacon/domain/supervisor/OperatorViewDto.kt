package dev.leonardo.ocbeacon.domain.supervisor

import kotlinx.serialization.Serializable

/**
 * operator-view.json 单镜像 DTO（docs/portable-supervisor-contract.md「Operator read model」）。
 *
 * supervisor 发布端每 5s 原子重写该文件；Beacon 只读。字段与
 * ez-omo-config supervisor/src/operator-view.ts 的 OperatorView 逐一对齐；
 * `root`/`actionClass`/`escalationKind` 为 2026-09-25 增补字段（可缺省，
 * 旧发布端镜像不带它们——`ignoreUnknownKeys` + nullable 承载两侧兼容）。
 */
@Serializable
data class OperatorViewDto(
    val schemaVersion: Int = 1,
    val generation: Long = 0L,
    val lastSeq: Long = 0L,
    val producedAt: String = "",
    val cards: List<OperatorViewCardDto> = emptyList(),
)

@Serializable
data class OperatorViewCardDto(
    val id: String = "",
    val rootLabel: String = "",
    /** 完整 root 路径（增补字段）：Seam 4 回复信封按 root 寻址；缺省=失败安全空串。 */
    val root: String? = null,
    val sessionLabel: String = "",
    val reasonText: String = "",
    val premiseTexts: List<String> = emptyList(),
    val ageSeconds: Long = 0L,
    /** A/B/C/D 频段（A 最紧急）。 */
    val severity: String = "",
    val jumpAvailable: Boolean = true,
    /** 队列动作类别（ESCALATE/CONTINUE/STEER…；增补字段）。 */
    val actionClass: String? = null,
    /** 升级类别 DECISION/INFORMATION/APPROVAL（增补字段；缺省=非升级）。 */
    val escalationKind: String? = null,
)
