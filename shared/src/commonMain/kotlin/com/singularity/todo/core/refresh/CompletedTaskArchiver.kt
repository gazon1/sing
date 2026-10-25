package com.singularity.todo.core.refresh

/**
 * Moves every completed task of the active profile into the archive.
 *
 * The core declares the contract it needs rather than taking the archive
 * feature's own port. A feature depends on the core and not the other way
 * round, and the archive feature's port inherits this one — so the
 * implementation does not change by a line.
 */
interface CompletedTaskArchiver {
    /**
     * Moves every completed, not-yet-archived task of the active profile into
     * the archive.
     *
     * @return the number of tasks this call moved. Zero is a success — it
     *   means there was nothing to move, not that the move failed.
     */
    suspend fun archiveCompletedTasks(): Result<Int>
}
