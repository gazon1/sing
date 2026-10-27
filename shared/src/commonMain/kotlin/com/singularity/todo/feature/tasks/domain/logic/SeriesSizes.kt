package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.Task

/**
 * How many dates each series has.
 *
 * The count is the number of rows that share the series id — the dates the
 * work is scheduled on. A row says which work it is one date of, so the count
 * is counted once for the whole list rather than per row.
 *
 * One owner, because two screens that answer this question separately answer
 * it separately: the agenda counts the dates of a series to label a row, and
 * the timeline counts the same dates to label its row, and a row that says
 * "2 of 7" in one place and "2 of 8" in another is a defect neither screen can
 * see.
 *
 * A task that stands alone is not in the table: it has no series to say
 * anything about.
 */
fun seriesSizes(tasks: List<Task>): Map<String, Int> =
    tasks.mapNotNull { it.seriesId?.value }
        .groupingBy { it }
        .eachCount()
