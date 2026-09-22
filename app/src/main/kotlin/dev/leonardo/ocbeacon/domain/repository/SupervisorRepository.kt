package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot

interface SupervisorRepository {
    suspend fun load(serverId: String): Result<SupervisorSnapshot>
}
