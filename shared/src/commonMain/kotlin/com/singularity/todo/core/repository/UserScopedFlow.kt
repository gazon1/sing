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
 * - [ProfileAwareCurrentUser.liveScopedUserId] is a `combine` over two `StateFlow`s, so
 *   it emits the *current* identity synchronously on collection. It is deliberately
 *   NOT [ProfileAwareCurrentUser.scopedUserId]: that one is a `StateFlow` seeded at
 *   construction and corrected by an async collector, so collecting it right after a
 *   profile switch yields the previous identity — the old profile's rows, or none.
 * - [flatMapLatest] cancels the previous subscription whenever userId changes,
 *   ensuring stale data is never observed.
 * - `distinctUntilChanged` is NOT applied here: it is a no-op on `StateFlow` upstreams,
 *   and the `flatMapLatest` already prevents redundant re-subscriptions for the same
 *   userId. Deduplication (if needed) belongs in the test double, not in this helper.
 *
 * @param source A lambda that receives the current [UserId] and returns a
 *   [Flow]. The returned flow is re-created (and re-subscribed) on every
 *   userId change. The lambda must be pure — same userId always produces
 *   the same stream of data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> ProfileAwareCurrentUser.observeForCurrentUser(source: (userId: UserId) -> Flow<T>): Flow<T> =
    liveScopedUserId.flatMapLatest { userId -> source(userId) }
