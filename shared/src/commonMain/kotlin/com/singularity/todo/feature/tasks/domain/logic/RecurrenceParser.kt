package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec

/**
 * Parses Orgzly / Tasks.org compatible recurrence DSL strings into [RecurrenceSpec].
 *
 * ## Supported syntax
 *
 * | Input | Output |
 * |-------|--------|
 * | `+1d` | Interval(FROM_COMPLETION, 1, DAY) |
 * | `+1w` | Interval(FROM_COMPLETION, 1, WEEK) |
 * | `+1m` | Interval(FROM_COMPLETION, 1, MONTH) |
 * | `+1y` | Interval(FROM_COMPLETION, 1, YEAR) |
 * | `++1w` | Interval(FROM_DUE, 1, WEEK) |
 * | `!+1w` | Interval(CATCH_UP, 1, WEEK) |
 * | `.+1m` | Interval(FROM_COMPLETION, 1, MONTH) |
 * | `.++1m` | Interval(FROM_DUE, 1, MONTH) |
 * | `every Mon,Wed,Fri` | Weekly(FROM_DUE, [1,3,5]) |
 * | `every week` | Interval(FROM_DUE, 1, WEEK) |
 * | `every 2 weeks` | Interval(FROM_DUE, 2, WEEK) |
 * | `every 3 days` | Interval(FROM_DUE, 3, DAY) |
 * | `1st of month` | Monthly(FROM_DUE, 1) |
 * | `15th of month` | Monthly(FROM_DUE, 15) |
 * | `every Jan 1` | Yearly(FROM_DUE, 1, 1) |
 * | `every December 25` | Yearly(FROM_DUE, 12, 25) |
 *
 * @throws IllegalArgumentException if the string does not match a known pattern.
 */
expect object RecurrenceParser {
    fun parse(input: String): RecurrenceSpec
}
