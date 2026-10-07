package com.singularity.todo.core.database

import androidx.room3.withWriteTransaction

/**
 * A write that either lands whole or not at all.
 *
 * ## Why this exists
 *
 * Every repository that writes a synced entity does the same two things: it writes the
 * row, and then it enqueues a patch describing the change. Those are two stores — the
 * entity table and `sync_outbox` — reached through two different abstractions, inside
 * one database.
 *
 * Without a transaction the pair can be split by any failure between them, and the two
 * halves fail in opposite directions:
 *
 * - **Row written, patch not enqueued.** The user's edit exists locally and will never
 *   reach the server. There is no counter, no queue row, and nothing on the pull path
 *   to notice — the device is quietly a local-only edit the user believes is syncing.
 * - **Patch enqueued, row not written.** The server is told about a document the device
 *   does not have. The receiving devices apply a change nobody made here, and the
 *   originating row's next edit is diffed against a shadow describing a state the
 *   device never had.
 *
 * See ADR `2026-10-05-who-owns-a-row-and-the-patch-that-describes-it`.
 *
 * ## Why a port and not the database itself
 *
 * Six repositories need this, and handing each of them the `AppDatabase` would put the
 * transaction shape in six places — each of which will next be edited for an unrelated
 * reason by a different person, and each of which is correct only while nobody adds a
 * write to the middle. One unit of work is correct once and is checked once. See
 * option 2 in that ADR, and option 1 for what was rejected.
 */
interface UnitOfWork {
    /**
     * Runs [block] inside one write transaction.
     *
     * Nested calls join the outer transaction rather than opening their own, so a
     * repository that already writes through another one does not deadlock or commit
     * twice.
     *
     * @return whatever [block] returns, so a call site that needs the written row still
     *   has it.
     */
    suspend fun <R> write(block: suspend () -> R): R
}

/**
 * [UnitOfWork] over the Room database.
 *
 * Room's [withWriteTransaction] is the seam, and it works because a suspend DAO call
 * made inside the block reuses the transaction's connection rather than taking its own
 * — which is what lets the entity write and the outbox write, both of them DAO calls
 * reached through different repositories, share one commit. `UnitOfWorkIsAtomicTest`
 * asserts that against a real database rather than trusting it.
 */
class RoomUnitOfWork(private val database: AppDatabase) : UnitOfWork {
    override suspend fun <R> write(block: suspend () -> R): R =
        database.withWriteTransaction { block() }
}
