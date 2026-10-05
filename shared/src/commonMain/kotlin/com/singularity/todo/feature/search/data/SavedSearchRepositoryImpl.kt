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
import com.singularity.todo.core.error.runCatchingCancellable

/**
 * Removed 2026-10-05: the file-level suppression of the direct-system-clock rule
 * that opened this file, and the `clock` constructor parameter beside it.
 *
 * The parameter was declared and never read — `clock` appeared once in the file,
 * on its own declaration line, and its default was the only reason the rule had
 * anything to report here. It was almost certainly copied from
 * `SavedAgendaViewsRepositoryImpl`, which does read its clock.
 *
 * Answered 2026-10-05 (#192): a saved search *does* depend on the moment it is
 * run, and it always has — but the clock belongs one layer up. `due:<n days>`
 * is turned into a date range by `DefaultSearchQueryResolver`, which takes
 * `Clock` and `TimeZoneProvider` as required parameters and recomputes the range
 * from `todayAt(clock, zone)` on every `resolve`, so a search saved on Tuesday
 * and opened on Friday asks about Friday. This repository stores the query
 * string and maps rows; it computes no date at all, so a `Clock` here would be
 * a parameter that exists only to look deliberate.
 *
 * So the answer is: recompute from now, and the recomputation already has its
 * clock. Re-adding the parameter would recreate the dead line under a better
 * justification, which is the same defect with a comment attached.
 *
 * The suppression carried no reason, so it switched the rule off for a whole
 * file over the one line that had been written in the codebase's own injectable
 * idiom. `scripts/check-suppression-intent.py` is what makes that shape
 * impossible to add back silently.
 *
 * The prose here deliberately avoids spelling the rule id or the system-clock
 * expression. This comment has to *name* what was removed, and the rule matches
 * inside KDoc as readily as in code — a note explaining a removal re-registers
 * it, which is a gate that punishes writing things down. Recorded as a known
 * false positive on the rule rather than worked around here; see
 * `TestKDocIsNotASuppression` in `scripts/tests/test_check_suppression_intent.py`
 * for the same shape on the gate's side.
 */
class SavedSearchRepositoryImpl(
    private val savedSearchDao: SavedSearchDao,
    private val currentUser: ProfileAwareCurrentUser,
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
