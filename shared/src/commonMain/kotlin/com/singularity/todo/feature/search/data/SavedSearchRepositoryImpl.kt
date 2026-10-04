@file:Suppress("NoDirectClockSystem")

package com.singularity.todo.feature.search.data

import com.singularity.todo.core.database.SavedSearchDao
import com.singularity.todo.core.database.SavedSearchEntity
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
import com.singularity.todo.feature.search.domain.port.SavedSearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import com.singularity.todo.core.error.runCatchingCancellable

class SavedSearchRepositoryImpl(
    private val savedSearchDao: SavedSearchDao,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock = Clock.System,
) : SavedSearchRepository {

    // ─── GenericUserScopedRepository ──────────────────────────────────────────

    override suspend fun currentUserId(): String = currentUser.scopedUserId.value.value

    override fun observeAll(): Flow<List<SavedSearch>> = currentUser.observeForCurrentUser { uid ->
        savedSearchDao.watchAll(uid.value).map { entities -> entities.map { it.toDomain() } }
    }

    override fun observe(id: SavedSearchId): Flow<SavedSearch?> = currentUser.observeForCurrentUser { uid ->
        savedSearchDao.watchById(uid.value, id.raw).map { it?.toDomain() }
    }

    override suspend fun get(id: SavedSearchId): SavedSearch? {
        val uid = currentUser.scopedUserId.value
        return savedSearchDao.getById(uid.value, id.raw)?.toDomain()
    }

    override suspend fun upsert(search: SavedSearch): Result<SavedSearch> = runCatchingCancellable {
        val uid = currentUser.scopedUserId.value
        val toInsert = if (search.userId == uid || search.userId == UserId.anonymous) {
            search.copy(userId = uid)
        } else {
            throw IllegalStateException(
                "Cross-user SavedSearch upsert: search.userId=${search.userId.value}, current=${uid.value}",
            )
        }
        savedSearchDao.upsert(toInsert.toEntity())
        toInsert
    }

    override suspend fun delete(id: SavedSearchId): Result<Unit> = runCatchingCancellable {
        val uid = currentUser.scopedUserId.value
        savedSearchDao.delete(uid.value, id.raw)
    }

    override suspend fun findByNameForUser(userId: String, name: String): SavedSearch? =
        savedSearchDao.findByName(userId, name)?.toDomain()

    // ─── Mapping ───────────────────────────────────────────────────────────────

    private fun SavedSearchEntity.toDomain(): SavedSearch = SavedSearch(
        id = SavedSearchId.fromString(id),
        userId = UserId(userId),
        name = name,
        queryString = queryString,
        createdAt = createdAt.toInstant(),
        updatedAt = updatedAt.toInstant(),
    )

    private fun SavedSearch.toEntity(): SavedSearchEntity = SavedSearchEntity(
        id = id.raw,
        userId = userId.value,
        name = name,
        queryString = queryString,
        createdAt = createdAt.toEpochMillis(),
        updatedAt = updatedAt.toEpochMillis(),
    )
}
