package com.singularity.todo.core.repository

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Adapts a user-scoped data source into a reactive [Flow] that automatically
 * re-subscribes when the current user changes.
 *
 * ```
 * taskDao.watchTasks(userId, filter)
 *     ↓ wrap with observeForCurrentUser
 * currentUser.observeForCurrentUser { uid -> taskDao.watchTasks(uid, filter) }
 * ```
 *
 * How it works:
 * - [ProfileAwareCurrentUser.scopedUserId] is a [StateFlow] (Eagerly seeded with
 *   `UserId.anonymous`), so the `source` emits immediately on first collect.
 * - [flatMapLatest] cancels the previous subscription whenever userId changes,
 *   ensuring stale data is never observed.
 * - `distinctUntilChanged` is NOT applied here: on a plain `StateFlow` it is a
 *   no-op, and the `flatMapLatest` already prevents redundant re-subscriptions
 *   for the same userId. Deduplication (if needed) belongs in the test double,
 *   not in this production helper.
 *
 * @param source A lambda that receives the current [UserId] and returns a
 *   [Flow]. The returned flow is re-created (and re-subscribed) on every
 *   userId change. The lambda must be pure — same userId always produces
 *   the same stream of data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> ProfileAwareCurrentUser.observeForCurrentUser(
    source: (userId: UserId) -> Flow<T>,
): Flow<T> = scopedUserId.flatMapLatest { userId -> source(userId) }
