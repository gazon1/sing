package com.singularity.todo.core.ui.components

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * The ISO local date of [date] — `2026-10-15`.
 *
 * ## Why the spelling is spelled out
 *
 * The test tags of the calendar and of the multi-date picker name a day by its
 * date, so every tag that names one has to spell the date the same way. The
 * spelling is a contract with the Maestro flows, not a property of the date type,
 * which is why it is written out here rather than inherited from
 * `LocalDate.toString()` — a change in the type's formatting would otherwise
 * rename every day in every flow, silently.
 *
 * ## Why it lives here
 *
 * Every month-shaped picker needs it, and a sheet in the shared layer cannot
 * reach into a feature. The calendar keeps its own public `isoLocalDate` and
 * delegates to this one.
 */
internal fun isoLocalDate(date: LocalDate): String =
    "${date.year}-${date.month.number.toString().padStart(2, '0')}-${date.day.toString().padStart(2, '0')}"
