// The helpers every perf harness of this package needs.
//
// The harnesses measure the same things in the same way: a sample of
// durations, a percentile of it, a number in milliseconds, a row or a number
// read straight from the database, and the state of the machine the sample was
// taken in. When each harness carried its own copy of those, the copies
// drifted — the same percentile computed under two names, and the same
// millisecond formatted two ways. A number that is published under two
// definitions is two numbers, and the reader cannot tell which one they are
// looking at.
//
// So the helpers live here, once, and every harness of the package uses them.
// This file holds no experiment of its own: it is the vocabulary the
// experiments share, not a framework around them.
//
// What is here:
//
// - `nearestRank` — the percentile of a sample of durations.
// - `millis` — a duration in the milliseconds the reports publish.
// - `loadAverage` — the state of the machine, so a number is never published
//   without the state it was taken in.
// - `texts` and `scalarInt` — the rows and the single numbers a harness reads
//   straight from the database, on a reader connection.

package com.singularity.todo.feature.tasks.performance

import com.singularity.todo.core.database.AppDatabase
import java.util.Locale
import kotlin.math.ceil
import kotlin.time.Duration

/**
 * The nearest-rank percentile of a set of durations: the value at rank
 * `ceil(fraction × n)` of the sorted sample.
 *
 * Nearest rank, not an interpolated quantile — the sample is small (ten
 * iterations in the smoke runs), and an interpolated value would be a number
 * no iteration produced. With ten samples p50 is the fifth smallest duration
 * and p95 the tenth.
 */
internal fun nearestRank(
    values: List<Duration>,
    fraction: Double,
): Duration {
    check(values.isNotEmpty()) { "a percentile of an empty sample is not a number" }
    val sorted = values.sorted()
    val rank = ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

/** A duration in milliseconds, with two decimals. */
internal fun millis(duration: Duration): String =
    String.format(Locale.ROOT, "%.2f", duration.inWholeMicroseconds / 1000.0)

/**
 * The load average of the machine, as the kernel sees it.
 *
 * The latencies of a shared machine are only readable together with the state
 * of the machine: the same harness on an idle host and on a loaded one gives
 * different numbers for the same work. The load goes into the report, so that
 * a number is never published without the state it was taken in.
 */
internal fun loadAverage(): String =
    runCatching {
        java.io
            .File("/proc/loadavg")
            .readText()
            .substringBefore(' ')
            .trim()
    }.getOrDefault("unknown")

/** One column of text per row. */
internal suspend fun AppDatabase.texts(sql: String): List<String> =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            buildList {
                while (statement.step()) {
                    add(statement.getText(0))
                }
            }
        }
    }

/**
 * The single number a query returns.
 *
 * [arg] is bound to the first parameter when it is given, so that the
 * harnesses that ask a scoped question do not have to spell the binding out
 * at every call site.
 */
internal suspend fun AppDatabase.scalarInt(
    sql: String,
    arg: String? = null,
): Int =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            if (arg != null) statement.bindText(1, arg)
            check(statement.step()) { "the query has to answer: $sql" }
            statement.getLong(0).toInt()
        }
    }
