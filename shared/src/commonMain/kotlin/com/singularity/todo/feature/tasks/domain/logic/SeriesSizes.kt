package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.Task

/**
 * How many dates each series has.
 *
 * The count is the number of rows that share the id — the dates the work is
 * scheduled on. A task that stands alone is not in the table: it has no series
 * to say anything about.
 *
 * Counted once for the whole list rather than per row, so a screen rendering
 * many rows does not rebuild the table once per row.
 */
fun seriesSizes(tasks: List<Task>): Map<String, Int> =
    tasks.mapNotNull { it.seriesId?.value }
        .groupingBy { it }
        .eachCount()
