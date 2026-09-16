package com.singularity.todo.feature.agenda.data

import com.singularity.todo.core.database.AgendaViewDao
import com.singularity.todo.core.database.AgendaViewEntity
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSavedAgendaViewsRepository(
    private val agendaViewDao: AgendaViewDao,
) : SavedAgendaViewsRepository {

    override fun watchAll(userId: String): Flow<List<SavedAgendaView>> =
        agendaViewDao.watchAll(userId).map { entities ->
            entities.map { it.toDomain() }
        }

    override fun watchById(id: SavedAgendaViewId, userId: String): Flow<SavedAgendaView?> =
        agendaViewDao.watchById(userId, id.raw).map { it?.toDomain() }

    override suspend fun upsert(view: SavedAgendaView): Result<SavedAgendaView> {
        return runCatching {
            agendaViewDao.upsert(view.toEntity())
            view
        }
    }

    override suspend fun delete(id: SavedAgendaViewId, userId: String): Result<Unit> {
        return runCatching {
            agendaViewDao.delete(userId, id.raw)
        }
    }

    // ─── Mapping ───────────────────────────────────────────────────────────────

    private fun AgendaViewEntity.toDomain(): SavedAgendaView = SavedAgendaView(
        id = SavedAgendaViewId.fromString(id),
        userId = userId,
        name = name,
        sectionsJson = sectionsJson,
        createdAt = createdAt.toInstant(),
        updatedAt = updatedAt.toInstant(),
    )

    private fun SavedAgendaView.toEntity(): AgendaViewEntity = AgendaViewEntity(
        id = id.raw,
        userId = userId,
        name = name,
        sectionsJson = sectionsJson,
        createdAt = createdAt.toEpochMillis(),
        updatedAt = updatedAt.toEpochMillis(),
    )
}
