// A measurement harness is a program, not a test: it measures a dataset that
// lives outside the test run, and a test that needed one would either skip
// itself or fail for a reason that has nothing to do with the claim.
@file:Suppress("NoRunBlocking")

package com.singularity.todo.feature.tasks.performance

import androidx.room3.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
 * - **statement** — the SQL alone, on a connection that is already held. This
 *   is the statement's own price.
 * - **one-shot** — the same SQL as a suspend call: Room's call path
 *   (`performSuspending` — the very call a suspend DAO method is compiled
 *   into), without a Flow and without an observer.
 * - **flow** — the DAO's Flow, collected once (`first()`): the read as the app
 *   makes it — observer registration, Flow plumbing, and the statement.
 * - **tracker** — the invalidation observer alone: the tracker registers the
 *   observer and emits, without running a query.
 *
 * The overhead of a read is `flow − statement`, and it splits into three parts
 * that add up:
 *
 * | part | what it is | how it is priced |
 * |---|---|---|
 * | call path | the coroutine hop and the connection the call takes | `one-shot − statement` |
 * | observer | the invalidation observer's registration and first emission | `tracker` |
 * | Flow plumbing | the rest of the machinery around the query | `flow − one-shot − tracker` |
 *
 * A fifth number prices the observer on a *change* rather than on an opening:
 * a live collector, a write, and the wait for the re-read the observer
 * triggers. The write alone is priced next to it, because the difference
 * between the two is the observer's share.
 *
 * ## What is asserted
 *
 * Nothing about the clock: the latencies are recorded, not asserted. What the
 * program does check is that the forms of a read answer the same question —
 * the same rows — because a comparison of a statement with a Flow that returns
 * something else would be a comparison of two different reads.
 *
 * ## How to run it
 *
 * The dataset comes from the shared generator of [#3090]:
 *
 * ```
 * ./gw :import-cli:run --args="generate --profile 100k --seed 4242 --tags 30 --format dir --output $PWD/build/perf/100k-tags"
 * ./gw :shared:readOverheadPerf -Pperf.dataset=$PWD/build/perf/100k-tags
 * ```
 *
 * A dataset that fits a backup archive is measured the same way, through
 * `-Pperf.archive`.
 */
fun main(args: Array<String>) {
    val config = ReadOverheadConfig.parse(args)
    println(runBlocking { measure(config) })
}

/** What to measure, and how often. */
private data class ReadOverheadConfig(
    val dataset: PerfDataset,
    val iterations: Int,
    val warmup: Int,
    val owner: UserId,
) {
    companion object {
        fun parse(args: Array<String>): ReadOverheadConfig {
            val values = perfArgs(args)
            return ReadOverheadConfig(
                dataset = PerfDataset.parse(values),
                iterations = (values["iterations"] ?: "10").toInt(),
                warmup = (values["warmup"] ?: "3").toInt(),
                owner = UserId(values["owner"] ?: "perf-owner"),
            )
        }
    }
}

/**
 * The reads under measurement.
 *
 * [sql] is the SQL the DAO runs, verbatim; [tables] are the tables the DAO's
 * Flow observes — the same names the generated code hands to the invalidation
 * tracker. [relation] is where the probe tasks come from: the tasks that
 * carry rows of the relation this read asks about.
 */
private enum class Read(val label: String, val sql: String, val tables: List<String>, val relation: String) {
    BlockingEdges(
        "getBlockingEdgeIdsForUser",
        """
        SELECT d.depends_on_task_id FROM task_dependencies d
        JOIN tasks t ON t.id = d.task_id
        WHERE d.task_id = ? AND d.verb = ? AND t.user_id = ?
        """.trimIndent(),
        listOf("task_dependencies", "tasks"),
        "task_dependencies",
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
    ),
}

/** The verb the blocking read is asked for. */
private const val BLOCKING_VERB = "BLOCKS"

/** One priced read. */
private data class OverheadMeasurement(
    val read: Read,
    val form: String,
    val p50: Duration,
    val p95: Duration,
    val samples: Int,
)

/** One priced change: a write, and the re-read the observer triggers. */
private data class ChangeMeasurement(
    val read: Read,
    val writeAlone: Duration,
    val writeAndObserve: Duration,
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
        for (read in Read.entries) {
            val probes = probeTasks(db, read.relation)
            check(probes.isNotEmpty()) {
                "the dataset holds no rows of ${read.relation} — there is nothing to read"
            }
            measurements += measureStatement(db, read, probes, config)
            measurements += price(db, read, probes, config, "one-shot", ::oneShotRows)
            measurements += price(db, read, probes, config, "flow", ::flowRows)
            measurements += price(db, read, probes, config, "tracker") { _, _, _, _ -> trackerRows(db, read) }
            changes += measureChange(db, read, probes.first(), config)
        }
        return render(dataset, tasks, db, config, measurements, changes)
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
    relation: String,
): List<String> = db.texts("SELECT DISTINCT task_id FROM $relation ORDER BY task_id LIMIT 10")

/**
 * The statement alone.
 *
 * The connection is taken once and held for the whole measurement, and the
 * clock runs around the statement alone — prepare, bind, step. What is priced
 * here is SQLite's work; the connection and the coroutine around it are
 * priced by the other forms.
 */
private suspend fun measureStatement(
    db: AppDatabase,
    read: Read,
    probes: List<String>,
    config: ReadOverheadConfig,
): OverheadMeasurement {
    val durations = mutableListOf<Duration>()
    var rows = 0
    db.useConnection(isReadOnly = true) { transactor ->
        for (index in 0 until config.warmup) {
            for (taskId in probes) {
                transactor.usePrepared(read.sql) { statement ->
                    read.bind(statement, taskId, config.owner)
                    rows = read.countRows(statement)
                }
            }
        }
        for (index in 0 until config.iterations) {
            for (taskId in probes) {
                val mark = TimeSource.Monotonic.markNow()
                transactor.usePrepared(read.sql) { statement ->
                    read.bind(statement, taskId, config.owner)
                    rows = read.countRows(statement)
                }
                durations += mark.elapsedNow()
            }
        }
    }
    return OverheadMeasurement(
        read = read,
        form = "statement",
        p50 = percentile(durations, 0.5),
        p95 = percentile(durations, 0.95),
        samples = durations.size,
    )
}

/**
 * Prices one form of one read over the probe tasks.
 *
 * The form is a named lambda so that every form runs the same warmup, the
 * same iteration count and the same clock — the difference between two forms
 * is then the difference between the calls, not between the runs.
 */
private suspend fun price(
    db: AppDatabase,
    read: Read,
    probes: List<String>,
    config: ReadOverheadConfig,
    name: String,
    call: suspend (AppDatabase, Read, String, UserId) -> Unit,
): OverheadMeasurement {
    for (index in 0 until config.warmup) {
        for (taskId in probes) {
            call(db, read, taskId, config.owner)
        }
    }
    val durations = mutableListOf<Duration>()
    for (index in 0 until config.iterations) {
        for (taskId in probes) {
            val mark = TimeSource.Monotonic.markNow()
            call(db, read, taskId, config.owner)
            durations += mark.elapsedNow()
        }
    }
    return OverheadMeasurement(
        read = read,
        form = name,
        p50 = percentile(durations, 0.5),
        p95 = percentile(durations, 0.95),
        samples = durations.size,
    )
}

/**
 * The same SQL as a suspend call.
 *
 * `performSuspending` is the call a suspend DAO method is compiled into: the
 * coroutine hop, the connection, and the statement — without a Flow and
 * without an observer. The rows are mapped the way the DAO maps them, so the
 * two forms do the same work and only the call around it differs.
 */
private suspend fun oneShotRows(
    db: AppDatabase,
    read: Read,
    taskId: String,
    owner: UserId,
): Int =
    performSuspending(db, isReadOnly = true, inTransaction = false) { connection ->
        val statement = connection.prepare(read.sql)
        try {
            read.bind(statement, taskId, owner)
            read.countRows(statement)
        } finally {
            statement.close()
        }
    }

/** The read as the app makes it: the DAO's Flow, collected once. */
private suspend fun flowRows(
    db: AppDatabase,
    read: Read,
    taskId: String,
    owner: UserId,
): Int {
    val dao = db.taskDao()
    return when (read) {
        Read.BlockingEdges -> {
            dao.getBlockingEdgeIdsForUser(taskId, owner.value, BLOCKING_VERB).first().size
        }

        Read.TypedDependencies -> {
            dao.observeTypedDependenciesForUser(taskId, owner.value).first().size
        }

        Read.TagIds -> {
            dao.getTagIdsForUser(taskId, owner.value).first().size
        }
    }
}

/**
 * The invalidation observer alone: the tracker registers the observer and
 * emits, without running a query.
 *
 * This is the price of the observer's registration — the part of a Flow read
 * that has nothing to do with the statement.
 */
private suspend fun trackerRows(
    db: AppDatabase,
    read: Read,
) {
    db.invalidationTracker.createFlow(*read.tables.toTypedArray()).first()
}

/** The parameters of a statement, in the order they are written. */
private fun Read.bind(
    statement: SQLiteStatement,
    taskId: String,
    owner: UserId,
) {
    statement.bindText(1, taskId)
    when (this) {
        Read.BlockingEdges -> {
            statement.bindText(2, BLOCKING_VERB)
            statement.bindText(3, owner.value)
        }

        Read.TypedDependencies, Read.TagIds -> {
            statement.bindText(2, owner.value)
        }
    }
}

/**
 * The rows a statement returns, mapped the way the DAO maps them.
 *
 * The mapping is part of the price of a read, so it is here and not a row
 * count: the two forms have to do the same work for the comparison to mean
 * anything.
 */
private fun Read.countRows(statement: SQLiteStatement): Int {
    var rows = 0
    while (statement.step()) {
        when (this) {
            Read.BlockingEdges, Read.TagIds -> {
                statement.getText(0)
            }

            Read.TypedDependencies -> {
                TaskDependencyCrossRef(
                    taskId = statement.getText(0),
                    dependsOnTaskId = statement.getText(1),
                    verb = statement.getText(2),
                )
            }
        }
        rows++
    }
    return rows
}

/**
 * The observer on a change: a live collector, a write, and the re-read.
 *
 * Two numbers are priced: the write alone — no collector is alive, so nothing
 * is notified — and the same write with a collector alive, which waits for the
 * re-read the observer triggers. The difference between them is the
 * observer's share of a change.
 *
 * The write is idempotent — the same row, written again — so the rows of the
 * relation do not change and the two measurements stay comparable.
 */
private suspend fun measureChange(
    db: AppDatabase,
    read: Read,
    taskId: String,
    config: ReadOverheadConfig,
): ChangeMeasurement {
    val write =
        changeWriter(db, read, taskId, config.owner)
            ?: return ChangeMeasurement(
                read = read,
                writeAlone = Duration.ZERO,
                writeAndObserve = Duration.ZERO,
                reEmissions = 0,
            )

    val alone = mutableListOf<Duration>()
    for (index in 0 until config.warmup) {
        write()
    }
    for (index in 0 until config.iterations) {
        val mark = TimeSource.Monotonic.markNow()
        write()
        alone += mark.elapsedNow()
    }

    val observed = mutableListOf<Duration>()
    var reEmissions = 0
    for (index in 0 until config.iterations) {
        val emissions = Channel<Unit>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector =
            scope.launch {
                collectEmissions(db, read, taskId, config.owner) { emissions.send(Unit) }
            }
        try {
            // A write that did not reach the observer is a fact of the run, not
            // a crash: the report says how many re-reads were seen. The timeout
            // is asked for with `withTimeoutOrNull`, so nothing is swallowed to
            // get here — a cancelled measurement still fails the run.
            val opened = withTimeoutOrNull(30.seconds) { emissions.receive() }
            if (opened != null) {
                val mark = TimeSource.Monotonic.markNow()
                write()
                val reRead = withTimeoutOrNull(30.seconds) { emissions.receive() }
                if (reRead != null) {
                    observed += mark.elapsedNow()
                    reEmissions++
                }
            }
        } finally {
            collector.cancel()
            scope.cancel()
            emissions.close()
        }
    }
    return ChangeMeasurement(
        read = read,
        writeAlone = percentile(alone, 0.5),
        writeAndObserve = if (observed.isEmpty()) Duration.ZERO else percentile(observed, 0.5),
        reEmissions = reEmissions,
    )
}

/** The DAO's Flow of the read, collected for as long as the scope lives. */
private suspend fun collectEmissions(
    db: AppDatabase,
    read: Read,
    taskId: String,
    owner: UserId,
    onEmission: suspend () -> Unit,
) {
    val dao: TaskDao = db.taskDao()
    when (read) {
        Read.BlockingEdges -> {
            dao.getBlockingEdgeIdsForUser(taskId, owner.value, BLOCKING_VERB).collect { onEmission() }
        }

        Read.TypedDependencies -> {
            dao.observeTypedDependenciesForUser(taskId, owner.value).collect { onEmission() }
        }

        Read.TagIds -> {
            dao.getTagIdsForUser(taskId, owner.value).collect { onEmission() }
        }
    }
}

/**
 * The write that invalidates the tables the read observes.
 *
 * It writes a row the relation already holds — the same edge, the same tag —
 * so the write is idempotent and the rows of the relation do not change: the
 * only thing the observer has to react to is the write itself.
 */
private suspend fun changeWriter(
    db: AppDatabase,
    read: Read,
    taskId: String,
    owner: UserId,
): (suspend () -> Unit)? =
    when (read) {
        Read.BlockingEdges, Read.TypedDependencies -> {
            val depId = db.texts("SELECT id FROM tasks WHERE id != ? ORDER BY id LIMIT 1", taskId).firstOrNull()
            if (depId == null) {
                null
            } else {
                suspend { db.taskDao().upsertDependencyForUser(taskId, depId, BLOCKING_VERB, owner.value) }
            }
        }

        Read.TagIds -> {
            val tagId = db.texts("SELECT id FROM tags ORDER BY id LIMIT 1").firstOrNull()
            if (tagId == null) {
                null
            } else {
                suspend { db.taskDao().upsertTagCrossRefForUser(taskId, tagId, owner.value) }
            }
        }
    }

/**
 * The nearest-rank percentile of a set of durations: the value at rank
 * `ceil(fraction × n)` of the sorted sample.
 */
private fun percentile(
    values: List<Duration>,
    fraction: Double,
): Duration {
    check(values.isNotEmpty()) { "a percentile of an empty sample is not a number" }
    val sorted = values.sorted()
    val rank = ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

/** The report. */
private suspend fun render(
    dataset: PerfDataset,
    tasks: Int,
    db: AppDatabase,
    config: ReadOverheadConfig,
    measurements: List<OverheadMeasurement>,
    changes: List<ChangeMeasurement>,
): String =
    buildString {
        appendLine("## Per-read overhead — measurement")
        appendLine()
        appendLine("- dataset: `${dataset.file.absolutePath}` (${dataset.form.name.lowercase()})")
        appendLine("- tasks: $tasks")
        appendLine("- edges: ${db.scalarInt("SELECT count(*) FROM task_dependencies")}")
        appendLine("- tag rows: ${db.scalarInt("SELECT count(*) FROM task_tags")}")
        appendLine("- owner: `${config.owner.value}`")
        appendLine()
        appendLine("| read | form | p50 ms | p95 ms | samples |")
        appendLine("|---|---|---|---|---|")
        for (m in measurements) {
            appendLine("| ${m.read.label} | ${m.form} | ${millis(m.p50)} | ${millis(m.p95)} | ${m.samples} |")
        }
        appendLine()
        appendLine("| read | statement | one-shot | flow | tracker | call path | observer | Flow plumbing |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (read in Read.entries) {
            val statement = measurements.median(read, "statement")
            val oneShot = measurements.median(read, "one-shot")
            val flow = measurements.median(read, "flow")
            val tracker = measurements.median(read, "tracker")
            appendLine(
                "| ${read.label} | ${millis(
                    statement,
                )} | ${millis(oneShot)} | ${millis(flow)} | ${millis(tracker)} | " +
                    "${millis(oneShot - statement)} | ${millis(tracker)} | ${millis(flow - oneShot - tracker)} |",
            )
        }
        appendLine()
        appendLine("### The observer on a change")
        appendLine()
        appendLine("| read | write alone ms | write + observer ms | observer's share ms | re-reads seen |")
        appendLine("|---|---|---|---|---|")
        for (change in changes) {
            appendLine(
                "| ${change.read.label} | ${millis(change.writeAlone)} | ${millis(change.writeAndObserve)} | " +
                    "${millis(change.writeAndObserve - change.writeAlone)} | ${change.reEmissions} |",
            )
        }
    }

/** The p50 of one form of one read. */
private fun List<OverheadMeasurement>.median(
    read: Read,
    form: String,
): Duration = first { it.read == read && it.form == form }.p50

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

/** One column of text per row, with one text parameter. */
private suspend fun AppDatabase.texts(
    sql: String,
    value: String,
): List<String> =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            statement.bindText(1, value)
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
