package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上次轮询的健康快照（root failing 集合 + errorsLastHourPeak）。
 * 用于判断「新进入 failing 的 root」与「错误峰值上升」。
 */
@Serializable
data class SupervisorHealthSnapshot(
    val failingRoots: Set<String> = emptySet(),
    val errorsPeak: Int = 0,
)

/**
 * Supervisor 后台轮询的已见状态持久化（DataStore，按 serverId 分键）。
 *
 * 与 [UnreadStateStore] 同款约定：同一 DataStore 实例、按 serverId 前缀分键、
 * JSON 序列化、读取失败降级为空（不阻塞轮询）。
 *
 * 去重语义：只记录**开放事项 id**（resolved 项不记录）。每次成功轮询后由
 * 调用方把 seen 集合覆盖为当前开放 id 集合——已 resolved 的 id 被自然淘汰，
 * 避免 seen 集合随 queue.json 无限增长（queue 本身的增长是已知债务，见 backlog）。
 */
@Singleton
class SupervisorSeenStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private const val SEEN_ITEMS_PREFIX = "supervisor_seen_items_"
        private const val HEALTH_PREFIX = "supervisor_health_"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val idsSerializer = ListSerializer(String.serializer())

        private fun seenKey(serverId: String) = stringPreferencesKey(SEEN_ITEMS_PREFIX + serverId)
        private fun healthKey(serverId: String) = stringPreferencesKey(HEALTH_PREFIX + serverId)
    }

    /** 该服务器已通知过的开放事项 id 集合。无记录为空集。 */
    fun seenItemIds(serverId: String): Flow<Set<String>> = dataStore.data.map { prefs ->
        val raw = prefs[seenKey(serverId)]
        if (raw.isNullOrBlank()) emptySet()
        else runCatching { json.decodeFromString(idsSerializer, raw).toSet() }.getOrDefault(emptySet())
    }

    /** 覆盖写入已见事项 id 集合（调用方传入当前开放 id 集合）。 */
    suspend fun saveSeenItemIds(serverId: String, ids: Set<String>) {
        dataStore.edit { prefs ->
            prefs[seenKey(serverId)] = json.encodeToString(idsSerializer, ids.toList())
        }
    }

    /** 该服务器上次轮询的健康快照。无记录为默认空快照。 */
    fun healthSnapshot(serverId: String): Flow<SupervisorHealthSnapshot> = dataStore.data.map { prefs ->
        val raw = prefs[healthKey(serverId)]
        if (raw.isNullOrBlank()) SupervisorHealthSnapshot()
        else runCatching { json.decodeFromString<SupervisorHealthSnapshot>(raw) }
            .getOrDefault(SupervisorHealthSnapshot())
    }

    /** 覆盖写入健康快照。 */
    suspend fun saveHealthSnapshot(serverId: String, snapshot: SupervisorHealthSnapshot) {
        dataStore.edit { prefs ->
            prefs[healthKey(serverId)] = json.encodeToString(snapshot)
        }
    }
}
