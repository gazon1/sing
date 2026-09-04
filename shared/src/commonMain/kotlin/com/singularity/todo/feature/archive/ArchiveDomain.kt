package com.singularity.todo.feature.archive

import com.singularity.todo.feature.tasks.Task
import kotlin.time.Instant

/**
 * Pure domain rules for the Archive feature. Testable without Compose or DB.
 */

/**
 * Returns the IDs of completed tasks that are not yet archived.
 */
fun completedButNotArchived(tasks: List<Task>): List<String> =
    tasks.filter { it.completedAt != null && it.archivedAt == null }
        .map { it.id.value }

/**
 * Computes the cutoff instant for "what counts as completed and ready to archive".
 * Pure — clock is injected so tests can pass a fixed Instant.
 */
fun archiveCutoff(now: Instant): Instant = now
