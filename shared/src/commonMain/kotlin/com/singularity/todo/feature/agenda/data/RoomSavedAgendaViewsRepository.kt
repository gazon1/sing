package com.singularity.todo.feature.agenda.data

import com.singularity.todo.core.database.AgendaViewDao
import com.singularity.todo.core.database.AgendaViewEntity
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSavedAgendaViewsRepository(
    private val agendaViewDao: AgendaViewDao,
    private val currentUser: ProfileAwareCurrentUser,
) : SavedAgendaViewsRepository {

    // ─── UserId-free observation (Phase 2 pattern) ───────────────────────────────

    override fun watchAllForCurrentUser(): Flow<List<SavedAgendaView>> =
        currentUser.observeForCurrentUser { uid ->
            agendaViewDao.watchAll(uid.value).map { entities -> entities.map { it.toDomain() } }
        }

    override fun watchByIdForCurrentUser(id: SavedAgendaViewId): Flow<SavedAgendaView?> =
        currentUser.observeForCurrentUser { uid ->
            agendaViewDao.watchById(uid.value, id.raw).map { it?.toDomain() }
        }

    override suspend fun upsert(view: SavedAgendaView): Result<SavedAgendaView> {
        return runCatching {
            agendaViewDao.upsert(view.toEntity())
            view
        }
    }

    override suspend fun delete(id: SavedAgendaViewId): Result<Unit> {
        return runCatching {
            val uid = currentUser.scopedUserId.value
            agendaViewDao.delete(uid.value, id.raw)
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
