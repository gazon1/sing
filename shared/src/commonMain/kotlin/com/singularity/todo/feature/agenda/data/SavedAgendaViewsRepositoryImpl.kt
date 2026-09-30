@file:Suppress("NoDirectClockSystem")

package com.singularity.todo.feature.agenda.data

import com.singularity.todo.core.database.AgendaViewDao
import com.singularity.todo.core.database.AgendaViewEntity
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.assertCanWrite
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

class SavedAgendaViewsRepositoryImpl(
    private val agendaViewDao: AgendaViewDao,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock = Clock.System,
) : SavedAgendaViewsRepository {

    // ─── GenericUserScopedRepository ──────────────────────────────────────────

    override suspend fun currentUserId(): String = currentUser.scopedUserId.value.value

    override fun observeAll(): Flow<List<SavedAgendaView>> = currentUser.observeForCurrentUser { uid ->
        agendaViewDao.watchAll(uid.value).map { entities -> entities.map { it.toDomain() } }
    }

    override fun observe(id: SavedAgendaViewId): Flow<SavedAgendaView?> = currentUser.observeForCurrentUser { uid ->
        agendaViewDao.watchById(uid.value, id.raw).map { it?.toDomain() }
    }

    override suspend fun get(id: SavedAgendaViewId): SavedAgendaView? {
        val uid = currentUser.scopedUserId.value
        return agendaViewDao.getById(uid.value, id.raw)?.toDomain()
    }

    override suspend fun create(item: SavedAgendaView): Result<SavedAgendaView> = upsert(item)

    override suspend fun update(item: SavedAgendaView): Result<SavedAgendaView> = upsert(item)

    override suspend fun upsert(view: SavedAgendaView): Result<SavedAgendaView> = runCatching {
        val uid = currentUser.scopedUserId.value
        // Legacy rows may hold the "" sentinel; normalise to UserId.anonymous so
        // assertCanWrite treats both consistently with other repositories.
        val entityUserId = if (view.userId.value == "") UserId.anonymous else view.userId
        currentUser.assertCanWrite(entityId = view.id.raw, entityUserId = entityUserId)
        val toInsert = view.copy(userId = uid)
        agendaViewDao.upsert(toInsert.toEntity())
        toInsert
    }

    override suspend fun duplicateForProfile(view: SavedAgendaView, targetUserId: String): Result<SavedAgendaView> {
        val now = clock.now()
        val copy = view.copy(
            id = SavedAgendaViewId.generate(),
            userId = UserId(targetUserId),
            createdAt = now,
            updatedAt = now,
        )
        return upsert(copy)
    }

    override suspend fun delete(id: SavedAgendaViewId): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value
        agendaViewDao.delete(uid.value, id.raw)
    }

    // ─── Mapping ───────────────────────────────────────────────────────────────

    private fun AgendaViewEntity.toDomain(): SavedAgendaView = SavedAgendaView(
        id = SavedAgendaViewId.fromString(id),
        userId = UserId(userId),
        name = name,
        sectionsJson = sectionsJson,
        createdAt = createdAt.toInstant(),
        updatedAt = updatedAt.toInstant(),
    )

    private fun SavedAgendaView.toEntity(): AgendaViewEntity = AgendaViewEntity(
        id = id.raw,
        userId = userId.value,
        name = name,
        sectionsJson = sectionsJson,
        createdAt = createdAt.toEpochMillis(),
        updatedAt = updatedAt.toEpochMillis(),
    )
}
