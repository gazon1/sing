package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow

internal class RemoteConfigRepositoryImpl(
    private val dao: RemoteConfigDao,
    private val clock: com.singularity.todo.core.platform.Clock,
    private val log: Logger = Logger.withTag("RemoteConfigRepository"),
) : RemoteConfigRepository {

    override val defaultConfig: Flow<RemoteConfigEntity?> = dao.watchDefault()

    override suspend fun getConfig(): RemoteConfigEntity? {
        return dao.getDefault()
    }

    override suspend fun saveConfig(supabaseUrl: String, anonKey: String) {
        val entity = RemoteConfigEntity(
            id = "default",
            supabaseUrl = supabaseUrl,
            anonKey = anonKey,
            updatedAt = clock.now().toEpochMilliseconds(),
        )
        dao.upsert(entity)
        log.d { "Remote config saved" }
    }

    override suspend fun deleteConfig() {
        dao.deleteDefault()
        log.d { "Remote config deleted" }
    }
}
