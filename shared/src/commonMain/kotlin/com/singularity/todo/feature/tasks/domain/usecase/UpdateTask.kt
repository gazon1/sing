package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.error.AppError
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Updates a task with automatic updatedAt timestamp injection.
 *
 * ## Why the read-modify-write is serialised
 *
 * [invoke] reads the row, applies a caller's transform and writes the *whole* row back.
 * Two of those running at once both read the row as it was, and whichever writes second
 * silently reverts the other's field: this is a lost update, and the caller has no way to
 * notice — its write reported success.
 *
 * It is reachable from one screen. The task detail's slots share one scope on
 * [Dispatchers.Default][kotlinx.coroutines.Dispatchers.Default], and the debounced title
 * write, the description flush and an entity edit are three independent coroutines all
 * calling in here for the same task.
 *
 * The lock makes the pair of calls one critical section, so the second reader sees what the
 * first wrote. It does not make a write optimistic — a row edited on another device between
 * the read and the write is still overwritten whole, which is the version-column problem
 * `2026-09-27-write-layer-soundness.md` records as deferred.
 *
 * ## What the lock covers — and what it does not
 *
 * **The instance, and nothing else.** DI binds this class as a `factory`, so every injection
 * point receives its own instance with its own `Mutex`. One ViewModel's writes to a task
 * therefore take turns, which is the sharing the defect above needs. Two ViewModels do
 * **not** synchronise with each other, even in the same process: the task detail and, say,
 * a project screen each hold a different `UpdateTaskUseCase`, so their `Mutex`es are
 * different objects and protect nothing against one another. Two live instances editing one
 * task can still lose an update in a single process — this is not only a cross-device
 * problem.
 *
 * That gap is deliberate here rather than solved: widening the boundary means a lock owner
 * with a longer lifetime than a ViewModel, which is a DI and architecture decision, not a
 * local one. It is recorded as known limitation and tracked in #3280. Whatever solves it,
 * a local `Mutex` cannot: it would need the same instance at every call site.
 *
 * The lock also does not make a write optimistic — a row edited on another device between
 * the read and the write is still overwritten whole, which is the version-column problem
 * `2026-09-27-write-layer-soundness.md` records as deferred.
 *
 * shortcut: the boundary is one use-case instance, so separate ViewModels are unsynchronised;
 * upgrade when a shared owner is decided in #3280. Series writes are a second instance
 * (`UpdateTaskSeriesUseCase`), so they can still race an occurrence edit of the same row.
 */
class UpdateTaskUseCase(private val repo: TaskRepository, private val clock: Clock) {
    /** Serialises the read-then-write of [invoke]. */
    private val lock = Mutex()

    /**
     * Full-entity update. Prefer [invoke(id, transform)] which re-reads before write
     * to avoid overwriting concurrent external changes.
     */
    @Deprecated(
        message = "Use invoke(id, transform) instead to avoid stale-snapshot overwrites",
        replaceWith = ReplaceWith("invoke(id, transform)"),
    )
    suspend operator fun invoke(task: Task): Result<Task> {
        val updated = task.copy(updatedAt = clock.now())
        return repo.update(updated)
    }

    /**
     * Read-modify-write update for atomic partial updates.
     *
     * Re-reads current state before applying [transform], and holds [lock] across both
     * halves — so a concurrent call on this instance sees the row this one wrote rather
     * than the one it read. See the class kdoc for what the lock does not cover.
     */
    suspend operator fun invoke(
        id: TaskId,
        transform: (Task) -> Task,
    ): Result<Task> =
        lock.withLock {
            val current =
                repo.get(id)
                    ?: return@withLock Result.failure(AppError.NotFound("Task $id not found", code = "task.not_found"))
            val updated = transform(current).copy(updatedAt = clock.now())
            repo.update(updated)
        }
}
