package dev.leonardo.ocbeacon.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.leonardo.ocbeacon.data.local.ArchiveBucketDao
import dev.leonardo.ocbeacon.data.local.CachedSessionDao
import dev.leonardo.ocbeacon.data.local.LogDao
import dev.leonardo.ocbeacon.data.local.MessageDao
import dev.leonardo.ocbeacon.data.local.Migrations
import dev.leonardo.ocbeacon.data.local.OcBeaconDatabase
import dev.leonardo.ocbeacon.data.local.SessionSyncDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** #478：freelist 回收阈值（低于此值不值得 VACUUM 重写整个库文件）。 */
    private const val VACUUM_FREE_BYTES_THRESHOLD = 64L * 1024 * 1024

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OcBeaconDatabase =
        // WAL 模式：Room 对 targetSdk>=16 默认开启（JournalMode.WRITE_AHEAD_LOGGING）
        // #272：openHelperFactory 换捆绑 SQLite（io.requery，含 FTS5 模块）——
        // 小米等 ROM 系统 SQLite 无 fts5，BM25 检索需全设备可用的 FTS5。
        Room.databaseBuilder(context, OcBeaconDatabase::class.java, "ocbeacon.db")
            .openHelperFactory(io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory())
            .addMigrations(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5, OcBeaconDatabase.MIGRATION_5_6, Migrations.MIGRATION_6_7, OcBeaconDatabase.MIGRATION_7_8, OcBeaconDatabase.MIGRATION_8_9, OcBeaconDatabase.MIGRATION_9_10)
            // #478③：freelist 阈值 VACUUM 兜底（onOpen，迁移已完成）。
            // 常态静默：requery 建库 auto_vacuum=FULL（真机实测），DROP/DELETE 的
            // 页即时归还 OS——迁移释放的 ~800MB 由平台自动回收，无需 VACUUM；
            // 此回调仅防御非 FULL 库/异常残留（freelist>64MB 时重写文件），
            // 阈值滤掉日常 prune 的 MB 级波动。失败仅告警不阻断，下次启动重试。
            //
            // 取舍记录（2026-09-30 真机实证）：page_size 1024→4096 升级**放弃**——
            // WAL 下 PRAGMA page_size 静默无效，须临时切 journal_mode=DELETE，
            // 而 requery 连接池架构下切换永远无法独占库（busy_timeout 8s 仍
            // code 5 锁死）；external-content 化后稳态库 ~60MB 级，小页开销
            // 收益仅 ~5MB，不值得 VACUUM INTO+文件替换的破坏性方案。
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    try {
                        // requery execSQL 拒 PRAGMA 语句（真机实测 code 0）——
                        // PRAGMA/VACUUM 一律走 query()
                        val pageSize = pragmaLong(db, "PRAGMA page_size") ?: return
                        val freePages = pragmaLong(db, "PRAGMA freelist_count") ?: return
                        val freeBytes = pageSize * freePages
                        if (freeBytes < VACUUM_FREE_BYTES_THRESHOLD) return
                        val t0 = System.currentTimeMillis()
                        val beforeBytes = db.path?.let { java.io.File(it).length() } ?: 0L
                        pragmaQuery(db, "VACUUM")
                        val afterBytes = db.path?.let { java.io.File(it).length() } ?: 0L
                        // android.util.Log：onOpen 阶段 AppLogger 写库会重入
                        android.util.Log.i(
                            "OcBeaconDB",
                            "[478-vacuum] reclaimed: freelist=" + (freeBytes / 1048576) + "MB, file " +
                                (beforeBytes / 1048576) + "MB→" + (afterBytes / 1048576) + "MB, " +
                                (System.currentTimeMillis() - t0) + "ms",
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("OcBeaconDB", "[478-vacuum] skipped (retry next open): " + e.message)
                    }
                }

                private fun pragmaLong(db: SupportSQLiteDatabase, pragma: String): Long? =
                    try {
                        db.query(pragma).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                    } catch (e: Exception) {
                        null
                    }

                /** PRAGMA/VACUUM 统一走 query()（requery execSQL 拒绝）。 */
                private fun pragmaQuery(db: SupportSQLiteDatabase, sql: String) {
                    db.query(sql).use { it.moveToFirst() }
                }
            })
            .build()

    @Provides
    fun provideLogDao(database: OcBeaconDatabase): LogDao = database.logDao()

    @Provides
    fun provideMessageDao(database: OcBeaconDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideArchiveBucketDao(database: OcBeaconDatabase): ArchiveBucketDao = database.archiveBucketDao()

    @Provides
    fun provideSessionSyncDao(database: OcBeaconDatabase): SessionSyncDao = database.sessionSyncDao()

    @Provides
    fun provideCachedSessionDao(database: OcBeaconDatabase): CachedSessionDao = database.cachedSessionDao()

    /** 时钟源（冷存桶时间戳用）。生产用系统时钟；测试经 MessageStore 构造参数注入固定值。 */
    @Provides
    @Singleton
    fun provideClock(): () -> Long = System::currentTimeMillis
}
