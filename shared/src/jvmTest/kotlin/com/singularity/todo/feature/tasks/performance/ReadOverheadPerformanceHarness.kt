// A measurement harness is a program, not a test: it measures a dataset that
// lives outside the test run, and a test that needed one would either skip
// itself or fail for a reason that has nothing to do with the claim.
@file:Suppress("NoRunBlocking")

package com.singularity.todo.feature.tasks.performance

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.math.ceil
import kotlin.time.Duration

/**
 * The price of a read that is not the statement — measured on its own.
 *
 * ## What is measured
 *
 * The SQL rewrites of [#3157], [#3176] and [#3195] left the price of a read
 * independent of the size of the database: what is left is the call around the
 * statement — 0.3–2.1 ms per read. The numbers in
 * `docs/architecture/dependency-query-performance.md` came out of a comparison
 * of SQL forms, so they say how much a read costs, not what the cost is made
 * of. This program answers the second question and asks the same read four
 * ways:
 *
 * - **statement** — the SQL on a reader connection that the call takes for
 *   itself: the query, the binding, and the mapping of the rows into the list
 *   a DAO would hand back.
 * - **one-shot** — the same SQL as a suspend call: Room's call path
 *   (`performSuspending` — the very call a suspend DAO method is compiled
 *   into), without a Flow and without an observer.
 * - **flow without observer** — the Flow's own machinery around the call: the
 *   same pipeline the DAO's Flow is, with the invalidation observer replaced
 *   by a single emission.
 * - **flow** — the DAO's Flow, collected once (`first()`): the read as the app
 *   makes it — observer registration, Flow plumbing, and the statement.
 *
 * All four take their connection from the same pool — the readers' — and every
 * one of them takes a connection of its own. No form is measured on a
 * connection another form holds, so no part of a difference between two forms
 * is the difference between two pools.
 *
 * The overhead of a read is `flow − statement`, and it splits into three parts
 * that add up in every iteration:
 *
 * | part | what it is | how it is priced |
 * |---|---|---|
 * | call path | the hop to the database's dispatcher and the call around the statement | `one-shot − statement` |
 * | Flow plumbing | the Flow's own machinery around the call | `flow without observer − one-shot` |
 * | invalidation | the observer: its registration, the trigger sync and the emission | `flow − flow without observer` |
 *
 * The invalidation part is priced *in place*: the two forms differ only in
 * the observer, both run the same pipeline on the same pool, and the observer
 * is what the difference is. An observer measured on its own would not answer
 * the question — it pays for a connection and a collector of its own, and it
 * is published separately, as the price of one registration.
 *
 * The parts are priced per iteration, not as the difference of two medians:
 * the forms run in one loop, so the difference between two of them is the
 * difference between the calls. The difference of two medians of two separate
 * passes is not that — under load it can come out negative.
 *
 * A sixth measurement prices the connection itself: taking a reader from the
 * pool and giving it back, with no query. Every form pays it inside its
 * statement, and the report says how much of a read it is.
 *
 * A seventh number prices the observer on a *change* rather than on an
 * opening: a live collector, a write, and the wait for the re-read the observer
 * triggers. The observer's latency — the time from the end of the write to the
 * re-read — is priced on its own: a screen waits for that, and it is a
 * quantity that cannot come out negative.
 *
 * ## What is asserted
 *
 * Nothing about the clock: the latencies are recorded, not asserted.
 *
 * What the program does check is that the forms of a read answer the same
 * question — the same rows. Before the clock starts, the statement, the
 * one-shot call, the Flow without the observer and the Flow are each asked for
 * the same task, and their answers are compared. A comparison of a statement
 * with a Flow that answers something else would be a comparison of two
 * different reads, and the SQL here is a copy of the DAO's — a copy that has
 * drifted would be exactly such a pair. The comparison is a `check`, so a
 * drift fails the run instead of publishing a number.
 *
 * ## How to run it
 *
 * The dataset comes from the shared generator of [#3090]:
 *
 * ```
 * ./gw :import-cli:run --args="generate --profile 10k --seed 4242 --output $PWD/build/perf/10k-tags.zip"
 * ./gw :import-cli:run --args="generate --profile 100k --seed 4242 --tags 30 \
 *   --dependency-density 0.1 --format dir --output $PWD/build/perf/100k-tags"
 * ```
 *
 * The two datasets differ in size — 10 000 and 100 000 tasks — and are
 * measured the same way:
 *
 * ```
 * ./gw :shared:readOverheadPerf -Pperf.archive=$PWD/build/perf/10k-tags.zip
 * ./gw :shared:readOverheadPerf -Pperf.dataset=$PWD/build/perf/100k-tags
 * ```
 *
 * One invocation produces one set of percentiles. The published ranges come
 * from three independent invocations of each dataset, kept as they were, so
 * that the spread between runs stays visible instead of being averaged away.
 *
 * A dataset that fits a backup archive is measured the same way, through
 * `-Pperf.archive`.
 */
fun main(args: Array<String>) {
    val config = ReadOverheadConfig.parse(args)
    println(runBlocking { measure(config) })
}

/** What to measure, and how often. */
internal data class ReadOverheadConfig(
    val dataset: PerfDataset,
    val iterations: Int,
    val warmup: Int,
    val owner: UserId,
) {
    companion object {
        fun parse(args: Array<String>): ReadOverheadConfig {
            val values = perfArgs(args)
            val iterations = (values["iterations"] ?: "10").toInt()
            val warmup = (values["warmup"] ?: "3").toInt()
            require(iterations > 0) { "a measurement needs at least one iteration, got $iterations" }
            require(warmup >= 0) { "the warmup cannot be negative, got $warmup" }
            return ReadOverheadConfig(
                dataset = PerfDataset.parse(values),
                iterations = iterations,
                warmup = warmup,
                owner = UserId(values["owner"] ?: "perf-owner"),
            )
        }
    }
}

/** The verb the blocking read is asked for. */
private const val BLOCKING_VERB = "BLOCKS"

/**
 * The reads under measurement.
 *
 * [sql] is the SQL the DAO runs, verbatim; [tables] are the tables the DAO's
 * Flow observes — the same names the generated code hands to the invalidation
 * tracker. [relation] is where the probe tasks come from: the tasks that
 * carry rows of the relation this read asks about, and [probeSql] picks them.
 */
internal enum class Read(
    val label: String,
    val sql: String,
    val tables: List<String>,
    val relation: String,
    val probeSql: String,
) {
    BlockingEdges(
        "getBlockingEdgeIdsForUser",
        """
        SELECT d.depends_on_task_id FROM task_dependencies d
        JOIN tasks t ON t.id = d.task_id
        WHERE d.task_id = ? AND d.verb = ? AND t.user_id = ?
        """.trimIndent(),
        listOf("task_dependencies", "tasks"),
        "task_dependencies",
        "SELECT DISTINCT task_id FROM task_dependencies WHERE verb = '$BLOCKING_VERB' ORDER BY task_id LIMIT 10",
    ),
    TypedDependencies(
        "observeTypedDependenciesForUser",
        """
        SELECT td.task_id, td.depends_on_task_id, td.verb FROM task_dependencies td
        JOIN tasks t ON t.id = td.task_id
        WHERE td.task_id = ? AND t.user_id = ?
        """.trimIndent(),
        listOf("task_dependencies", "tasks"),
        "task_dependencies",
        "SELECT DISTINCT task_id FROM task_dependencies ORDER BY task_id LIMIT 10",
    ),
    TagIds(
        "getTagIdsForUser",
        """
        SELECT tt.tag_id FROM task_tags tt
        JOIN tasks t ON t.id = tt.task_id
        WHERE tt.task_id = ? AND t.user_id = ?
        """.trimIndent(),
        listOf("task_tags", "tasks"),
        "task_tags",
        "SELECT DISTINCT task_id FROM task_tags ORDER BY task_id LIMIT 10",
    ),
}

/** One priced read. */
internal data class OverheadMeasurement(
    val read: Read,
    val form: String,
    val p50: Duration,
    val p95: Duration,
    val samples: Int,
)

/**
 * The four forms of one read, timed side by side in one loop.
 *
 * The forms are timed in the same iteration, one after another, so that the
 * machine's noise falls on all of them alike. The parts are then measured
 * directly, as the difference of two forms of the *same* iteration, rather
 * than as the difference of two separately measured medians: on a shared
 * machine the latter can come out negative, and a negative part is not a
 * price.
 */
internal data class FormTimings(
    val statement: List<Duration>,
    val oneShot: List<Duration>,
    val plumbing: List<Duration>,
    val flow: List<Duration>,
    val tracker: List<Duration>,
) {
    /** The overhead of a read: what the Flow costs over the statement alone. */
    val overhead: List<Duration> get() = flow.zip(statement) { flow, statement -> flow - statement }

    /** The path a call takes around the statement. */
    val callPath: List<Duration> get() = oneShot.zip(statement) { oneShot, statement -> oneShot - statement }

    /** The Flow's own machinery around the call, without the observer. */
    val flowPlumbing: List<Duration> get() = plumbing.zip(oneShot) { plumbing, oneShot -> plumbing - oneShot }

    /**
     * The observer's share of the Flow's price, measured in place.
     *
     * The two forms differ only in the observer: the same pipeline, the same
     * pool, the same call — and the difference is what the observer costs
     * where the Flow really uses it.
     */
    val invalidation: List<Duration> get() = flow.zip(plumbing) { flow, plumbing -> flow - plumbing }

    /**
     * The observer's price, measured on its own.
     *
     * This is the price of one registration, in a pass of its own: it pays for
     * a connection and a collector that the Flow path shares with the rest of
     * the machinery. It is published, not subtracted.
     */
    val observer: Duration get() = nearestRank(tracker, 0.5)
}

/**
 * The rows a read answers with, in the shape the DAO answers in.
 *
 * The forms are compared on these, so the comparison asks whether they
 * answered the same question — not whether they counted the same rows.
 */
internal sealed interface ReadRows {
    /** Tag ids and blocking edge ids: the DAO hands back plain strings. */
    data class Texts(val values: List<String>) : ReadRows

    /** Typed dependencies: the DAO hands back cross-ref objects. */
    data class Dependencies(val values: List<TaskDependencyCrossRef>) : ReadRows
}

/** One priced change: a write, and the re-read the observer triggers. */
internal data class ChangeMeasurement(
    val read: Read,
    val writeAlone: Duration,
    val writeAndObserve: Duration,
    val observerLatency: Duration,
    val reEmissions: Int,
)

private suspend fun measure(config: ReadOverheadConfig): String {
    val dataset = config.dataset
    check(dataset.file.exists()) { "no dataset to measure: ${dataset.file.absolutePath}" }
    val fixture = DatabaseTestFixture.onDisk()
    try {
        val db = fixture.database("perf")
        restore(db, fixture, dataset, config.owner)
        val tasks = db.scalarInt("SELECT count(*) FROM tasks")
        check(tasks > 0) { "the dataset holds no tasks — there is nothing to read" }

        val measurements = mutableListOf<OverheadMeasurement>()
        val changes = mutableListOf<ChangeMeasurement>()
        val connection = measureConnection(db, config)
        for (read in Read.entries) {
            val probes = probeTasks(db, read)
            check(probes.isNotEmpty()) {
                "the dataset holds no rows of ${read.relation} — there is nothing to read"
            }
            collectRows(db, read, probes, config.owner)
            val timings = measureForms(db, read, probes, config)
            measurements += priced(read, "statement", timings.statement)
            measurements += priced(read, "one-shot", timings.oneShot)
            measurements += priced(read, "flow without observer", timings.plumbing)
            measurements += priced(read, "flow", timings.flow)
            measurements += priced(read, "observer alone", timings.tracker)
            measurements += priced(read, "call path", timings.callPath)
            measurements += priced(read, "Flow plumbing", timings.flowPlumbing)
            measurements += priced(read, "invalidation", timings.invalidation)
            measurements += priced(read, "overhead", timings.overhead)
            changes += measureChange(db, read, probes.first(), config)
        }
        return render(dataset, tasks, db, config, measurements, changes, connection)
    } finally {
        fixture.close()
    }
}

/**
 * The tasks a read is priced on: the first ten tasks that carry rows of the
 * relation. The same coordinate the SQL comparison used, so the two reports
 * speak about the same rows.
 */
private suspend fun probeTasks(
    db: AppDatabase,
    read: Read,
): List<String> = db.texts(read.probeSql)

/**
 * The rows the query forms answer with, and the check that they agree.
 *
 * The SQL of a read is a copy of the DAO's. A copy that has drifted would
 * compare a statement with a Flow that answers something else, and the
 * comparison would be of two different reads. The rows are compared here, so
 * that such a drift fails the run instead of publishing a number.
 */
private suspend fun collectRows(
    db: AppDatabase,
    read: Read,
    probes: List<String>,
    owner: UserId,
) {
    for (taskId in probes) {
        val fromStatement = statementRows(db, read, taskId, owner)
        val fromOneShot = oneShotRows(db, read, taskId, owner)
        val fromPlumbing = plumbingRows(db, read, taskId, owner)
        val fromFlow = flowRows(db, read, taskId, owner)
        check(fromOneShot == fromStatement) {
            "the one-shot form answers a different question than the statement for $taskId — " +
                "the SQL of ${read.label} has drifted from the DAO"
        }
        check(fromPlumbing == fromStatement) {
            "the Flow without the observer answers a different question than the statement for $taskId — " +
                "the SQL of ${read.label} has drifted from the DAO"
        }
        check(fromFlow == fromStatement) {
            "the Flow form answers a different question than the statement for $taskId — " +
                "the SQL of ${read.label} has drifted from the DAO"
        }
    }
}

/** One priced form of one read. */
internal fun priced(
    read: Read,
    form: String,
    durations: List<Duration>,
): OverheadMeasurement =
    OverheadMeasurement(
        read = read,
        form = form,
        p50 = nearestRank(durations, 0.5),
        p95 = nearestRank(durations, 0.95),
        samples = durations.size,
    )

/** The report. */
private suspend fun render(
    dataset: PerfDataset,
    tasks: Int,
    db: AppDatabase,
    config: ReadOverheadConfig,
    measurements: List<OverheadMeasurement>,
    changes: List<ChangeMeasurement>,
    connection: List<Duration>,
): String =
    buildString {
        appendLine("## Per-read overhead — measurement")
        appendLine()
        appendLine("- dataset: `${dataset.file.absolutePath}` (${dataset.form.name.lowercase()})")
        appendLine("- tasks: $tasks")
        appendLine("- edges: ${db.scalarInt("SELECT count(*) FROM task_dependencies")}")
        appendLine("- tag rows: ${db.scalarInt("SELECT count(*) FROM task_tags")}")
        appendLine("- owner: `${config.owner.value}`")
        appendLine("- jvm: ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")})")
        appendLine("- machine: ${Runtime.getRuntime().availableProcessors()} cores, load ${loadAverage()}")
        appendLine(
            "- rows checked: the statement, the one-shot call, the Flow without the observer and " +
                "the Flow answered the same rows",
        )
        appendLine()
        appendLine("| read | form | p50 ms | p95 ms | samples |")
        appendLine("|---|---|---|---|---|")
        for (m in measurements) {
            appendLine("| ${m.read.label} | ${m.form} | ${millis(m.p50)} | ${millis(m.p95)} | ${m.samples} |")
        }
        appendLine()
        appendLine(
            "| read | statement | one-shot | flow w/o observer | flow | call path | Flow plumbing | " +
                "invalidation | overhead | observer alone |",
        )
        appendLine("|---|---|---|---|---|---|---|---|---|---|")
        for (read in Read.entries) {
            val statement = measurements.median(read, "statement")
            val oneShot = measurements.median(read, "one-shot")
            val plumbing = measurements.median(read, "flow without observer")
            val flow = measurements.median(read, "flow")
            val tracker = measurements.median(read, "observer alone")
            val callPath = measurements.median(read, "call path")
            val machinery = measurements.median(read, "Flow plumbing")
            val invalidation = measurements.median(read, "invalidation")
            val overhead = measurements.median(read, "overhead")
            appendLine(
                "| ${read.label} | ${millis(statement)} | ${millis(oneShot)} | ${millis(plumbing)} | " +
                    "${millis(flow)} | ${millis(callPath)} | ${millis(machinery)} | ${millis(invalidation)} | " +
                    "${millis(overhead)} | ${millis(tracker)} |",
            )
        }
        appendLine()
        appendLine(
            "The overhead is the call path plus the Flow plumbing plus the invalidation, in every " +
                "iteration. All four forms take their connection from the readers' pool, so no part of " +
                "a difference is a difference of pools. The invalidation is priced in place: the Flow " +
                "and the Flow without the observer differ only in the observer. The observer alone is " +
                "published, not subtracted: it is the price of one registration, in a pass of its own.",
        )
        appendLine()
        appendLine("### The connection every form pays")
        appendLine()
        appendLine(
            "Every form takes a reader connection of its own, so the price of taking one is inside " +
                "the statement: p50 ${millis(nearestRank(connection, 0.5))} ms, " +
                "p95 ${millis(nearestRank(connection, 0.95))} ms, ${connection.size} samples.",
        )
        appendLine()
        appendLine("### The observer on a change")
        appendLine()
        appendLine("| read | write alone ms | write + observer ms | observer's latency ms | re-reads seen |")
        appendLine("|---|---|---|---|---|")
        for (change in changes) {
            appendLine(
                "| ${change.read.label} | ${millis(change.writeAlone)} | ${millis(change.writeAndObserve)} | " +
                    "${millis(change.observerLatency)} | ${change.reEmissions} |",
            )
        }
        appendLine()
        appendLine(
            "The observer's latency is the time from the end of the write to the emission the observer " +
                "triggers — how long a screen waits for the re-read.",
        )
    }

/** The p50 of one form of one read. */
private fun List<OverheadMeasurement>.median(
    read: Read,
    form: String,
): Duration = first { it.read == read && it.form == form }.p50

/**
 * The load average of the machine, as the kernel sees it.
 *
 * The latencies of a shared machine are only readable together with the state
 * of the machine: the same harness on an idle host and on a loaded one gives
 * different numbers for the same work. The load goes into the report, so that
 * a number is never published without the state it was taken in.
 */
private fun loadAverage(): String =
    runCatching {
        java.io
            .File("/proc/loadavg")
            .readText()
            .substringBefore(' ')
            .trim()
    }.getOrDefault("unknown")

/**
 * The nearest-rank percentile of a set of durations: the value at rank
 * `ceil(fraction × n)` of the sorted sample.
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

/** One column of text per row. */
private suspend fun AppDatabase.texts(sql: String): List<String> =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            buildList {
                while (statement.step()) {
                    add(statement.getText(0))
                }
            }
        }
    }

/** The single number a query returns. */
private suspend fun AppDatabase.scalarInt(sql: String): Int =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            check(statement.step()) { "the query has to answer: $sql" }
            statement.getLong(0).toInt()
        }
    }

/** A duration in milliseconds, with two decimals. */
private fun millis(duration: Duration): String =
    String.format(Locale.ROOT, "%.2f", duration.inWholeMicroseconds / 1000.0)
