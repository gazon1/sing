package com.singularity.todo.core.seed

import com.singularity.todo.feature.tasks.domain.model.Task

/**
 * The batch write a *fixture* needs, and the one thing a fixture does not need: sync.
 *
 * ## Why this is not on [com.singularity.todo.feature.tasks.domain.port.TaskRepository]
 *
 * Writing a list of tasks was, until this port, an operation every task writer could
 * ask for. Exactly one did — [SeedWriter] — and it paid for the whole ordinary create
 * path per row: an upsert, an outgoing-links write, a fresh-row read, an outbox write,
 * a shadow write and a JSON patch, per row, about 30 ms of it. A thousand-row fixture
 * therefore took half a minute, which is longer than any wait the Maestro layer has
 * (`config/docs/ui-wait-budgets.txt`), so a flow could not seed the data it asserts on.
 *
 * The cost that made the fixture slow is the cost that makes it *correct* for a user's
 * edit: the patch is how the server learns about the row. A seeded row is the opposite
 * kind of row — the flow asserts on it on this device, in this run, and a debug build
 * has no server to tell. So the two writes are two contracts, and the port says which
 * one a caller is asking for. A single `writeFixtures` on the repository made that choice
 * invisible, which is why it is not there.
 *
 * ## What it still does
 *
 * Everything that makes the rows *the rows the fixture asked for*: the profile scope
 * (`user_id` comes from the active profile), the tags and the follow-up edges the task
 * carries, and one transaction, so a bulk that fails halfway leaves nothing behind.
 *
 * @see SeedWriter.createTasks the only caller.
 */
interface SeedTaskWriter {
    /**
     * Writes every task of [tasks] in one transaction, without enqueueing a patch.
     *
     * The caller has built the rows (ids, timestamps and owner included); this writes
     * them as they are, under the active profile. It does not dedupe by title — a bulk
     * fixture's titles are distinct by construction, and the per-title read that
     * [SeedWriter.writeTask] does is the O(n²) this exists to avoid.
     *
     * @return the rows as written, in the order they were given.
     * @throws IllegalStateException when a task belongs to another profile.
     */
    suspend fun writeFixtures(tasks: List<Task>): List<Task>
}
