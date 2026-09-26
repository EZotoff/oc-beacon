package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.BeaconReply
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot

interface SupervisorRepository {
    suspend fun load(serverId: String): Result<SupervisorSnapshot>

    /**
     * Seam 4 beacon reply ingress（Amendment 2026-09-25）：把一条回复作为单个顶层
     * user 消息（信封 JSON）发送进 [root] 的 per-root 收件箱会话
     * 「[Beacon replies] <basename(root)>」（缺失则创建；标题前缀禁用「[Supervisor]」）。
     * 走应用既有 OpenCode 连接——不新增监听/端口/凭据。
     */
    suspend fun sendReply(serverId: String, root: String, reply: BeaconReply): Result<Unit>
}
