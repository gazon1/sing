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
 *   is the statement's own price: the query, the binding, and the mapping of
 *   the rows into the list a DAO would hand back.
 * - **one-shot** — the same SQL as a suspend call: Room's call path
 *   (`performSuspending` — the very call a suspend DAO method is compiled
 *   into), without a Flow and without an observer.
 * - **flow** — the DAO's Flow, collected once (`first()`): the read as the app
 *   makes it — observer registration, Flow plumbing, and the statement.
 * - **tracker** — the invalidation observer alone: the tracker registers the
 *   observer and emits, without running a query.
 *
 * A fifth measurement prices the connection itself: taking a connection from
 * the pool and giving it back, with no query. The statement form holds its
 * connection for the whole measurement, so the price of taking one is not in
 * it — every other form pays it, and it is therefore measured on its own.
 *
 * Every form that runs the query maps its rows into a list and keeps it, the
 * way a DAO does. The forms therefore differ only in the machinery around the
 * query, and the difference between two of them is never the price of
 * building the result.
 *
 * The overhead of a read is `flow − statement`, and it splits into two parts
 * that add up in every iteration:
 *
 * | part | what it is | how it is priced |
 * |---|---|---|
 * | call path | the coroutine hop and the connection the call takes | `one-shot − statement` |
 * | Flow machinery | the observer's registration and the rest of the Flow machinery | `flow − one-shot` |
 *
 * The parts are priced per iteration, not as the difference of two medians:
 * the three forms run in one loop, so the difference between two of them is
 * the difference between the calls. The difference of two medians of two
 * separate passes is not that — under load it can come out negative.
 *
 * The observer is measured on its own as well, and it is published as a lower
 * bound of the Flow machinery rather than subtracted from it: measured apart
 * it pays for a connection and a collector of its own, which the Flow path
 * pays for as well.
 *
 * The overhead of a read does not include the connection: the statement form
 * holds one. A read that pays for its connection costs `overhead` plus the
 * price of the connection, and the report prints both.
 *
 * A fifth number prices the observer on a *change* rather than on an opening:
 * a live collector, a write, and the wait for the re-read the observer
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
 * one-shot call and the Flow are each asked for the same task, and their
 * answers are compared. A comparison of a statement with a Flow that answers
 * something else would be a comparison of two different reads, and the SQL
 * here is a copy of the DAO's — a copy that has drifted would be exactly such
 * a pair. The comparison is a `check`, so a drift fails the run instead of
 * publishing a number.
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
private data class FormTimings(
    val statement: List<Duration>,
    val oneShot: List<Duration>,
    val flow: List<Duration>,
    val tracker: List<Duration>,
) {
    /** The overhead of a read: what the Flow costs over the statement alone. */
    val overhead: List<Duration> get() = flow.zip(statement) { flow, statement -> flow - statement }

    /** The path a call takes around the statement. */
    val callPath: List<Duration> get() = oneShot.zip(statement) { oneShot, statement -> oneShot - statement }

    /**
     * The Flow machinery around the query: the observer's registration and
     * everything the Flow adds around the call path.
     *
     * The observer is *not* subtracted from this. The observer measured on its
     * own pays for a connection of its own and for a collector of its own,
     * and the Flow path pays for those as well — subtracting the isolated
     * number would count the observer twice, and the remainder would go
     * negative on a loaded machine. The isolated observer is therefore
     * published as its own measurement and read as a lower bound of this part.
     */
    val flowMachinery: List<Duration> get() = flow.zip(oneShot) { flow, oneShot -> flow - oneShot }

    /**
     * The observer's price, measured on its own.
     *
     * The observer is the same for every iteration of a read, so its median is
     * the price of one registration.
     */
    val observer: Duration get() = percentile(tracker, 0.5)
}

/**
 * The rows a read answers with, in the shape the DAO answers in.
 *
 * The forms are compared on these, so the comparison asks whether they
 * answered the same question — not whether they counted the same rows.
 */
private sealed interface ReadRows {
    /** Tag ids and blocking edge ids: the DAO hands back plain strings. */
    data class Texts(val values: List<String>) : ReadRows

    /** Typed dependencies: the DAO hands back cross-ref objects. */
    data class Dependencies(val values: List<TaskDependencyCrossRef>) : ReadRows
}

/** One priced change: a write, and the re-read the observer triggers. */
private data class ChangeMeasurement(
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
            val probes = probeTasks(db, read.relation)
            check(probes.isNotEmpty()) {
                "the dataset holds no rows of ${read.relation} — there is nothing to read"
            }
            collectRows(db, read, probes, config.owner)
            val timings = measureForms(db, read, probes, config)
            measurements += priced(read, "statement", timings.statement)
            measurements += priced(read, "one-shot", timings.oneShot)
            measurements += priced(read, "flow", timings.flow)
            measurements += priced(read, "tracker", timings.tracker)
            measurements += priced(read, "call path", timings.callPath)
            measurements += priced(read, "Flow machinery", timings.flowMachinery)
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
    relation: String,
): List<String> = db.texts("SELECT DISTINCT task_id FROM $relation ORDER BY task_id LIMIT 10")

/**
 * The rows the three query forms answer with, and the check that they agree.
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
        val fromFlow = flowRows(db, read, taskId, owner)
        check(fromOneShot == fromStatement) {
            "the one-shot form answers a different question than the statement for $taskId — " +
                "the SQL of ${read.label} has drifted from the DAO"
        }
        check(fromFlow == fromStatement) {
            "the Flow form answers a different question than the statement for $taskId — " +
                "the SQL of ${read.label} has drifted from the DAO"
        }
    }
}

/** The read as the statement alone: the SQL on a connection that is already held. */
private suspend fun statementRows(
    db: AppDatabase,
    read: Read,
    taskId: String,
    owner: UserId,
): ReadRows =
    db.useConnection(isReadOnly = false) { transactor ->
        transactor.usePrepared(read.sql) { statement ->
            read.bind(statement, taskId, owner)
            read.mapRows(statement)
        }
    }

/**
 * Times the four forms of one read, side by side.
 *
 * The connection of the statement form is taken once and held for the whole
 * measurement, so that the statement pays for the query alone and not for
 * taking a connection. The other forms take their connections from the pool
 * while it is held — the pool opens as many connections as are asked for.
 *
 * Every form runs the same warmup, the same iteration count and the same
 * clock, in the same loop: the difference between two forms is then the
 * difference between the calls, not between the runs.
 */
private suspend fun measureForms(
    db: AppDatabase,
    read: Read,
    probes: List<String>,
    config: ReadOverheadConfig,
): FormTimings {
    val tracker = measureTracker(db, read, probes.size, config)
    val statement = mutableListOf<Duration>()
    val oneShot = mutableListOf<Duration>()
    val flow = mutableListOf<Duration>()
    db.useConnection(isReadOnly = false) { transactor ->
        for (index in 0 until config.warmup) {
            for (taskId in probes) {
                transactor.usePrepared(read.sql) { prepared ->
                    read.bind(prepared, taskId, config.owner)
                    read.mapRows(prepared)
                }
                oneShotRows(db, read, taskId, config.owner)
                flowRows(db, read, taskId, config.owner)
            }
        }
        for (index in 0 until config.iterations) {
            for (taskId in probes) {
                var mark = TimeSource.Monotonic.markNow()
                transactor.usePrepared(read.sql) { prepared ->
                    read.bind(prepared, taskId, config.owner)
                    read.mapRows(prepared)
                }
                statement += mark.elapsedNow()

                mark = TimeSource.Monotonic.markNow()
                oneShotRows(db, read, taskId, config.owner)
                oneShot += mark.elapsedNow()

                mark = TimeSource.Monotonic.markNow()
                flowRows(db, read, taskId, config.owner)
                flow += mark.elapsedNow()
            }
        }
    }
    return FormTimings(statement = statement, oneShot = oneShot, flow = flow, tracker = tracker)
}

/**
 * Times the invalidation observer alone.
 *
 * The observer registers itself and emits, without running a query. It is
 * measured in its own pass, because registering the observer makes the
 * tracker write its triggers, and a write cannot happen while a read
 * connection is held.
 *
 * The observer does not depend on the task a read is about, so it is priced
 * once per probe to keep the sample count of every form the same.
 */
private suspend fun measureTracker(
    db: AppDatabase,
    read: Read,
    probes: Int,
    config: ReadOverheadConfig,
): List<Duration> {
    for (index in 0 until config.warmup) {
        repeat(probes) { trackerRows(db, read) }
    }
    val durations = mutableListOf<Duration>()
    for (index in 0 until config.iterations) {
        repeat(probes) {
            val mark = TimeSource.Monotonic.markNow()
            trackerRows(db, read)
            durations += mark.elapsedNow()
        }
    }
    return durations
}

/**
 * The same SQL as a suspend call.
 *
 * `performSuspending` is the call a suspend DAO method is compiled into: the
 * coroutine hop, the connection, and the statement — without a Flow and
 * without an observer. The rows are mapped into a list the way the DAO maps
 * them, so the two forms do the same work and only the call around it
 * differs.
 */
private suspend fun oneShotRows(
    db: AppDatabase,
    read: Read,
    taskId: String,
    owner: UserId,
): ReadRows =
    performSuspending(db, isReadOnly = true, inTransaction = false) { connection ->
        val statement = connection.prepare(read.sql)
        try {
            read.bind(statement, taskId, owner)
            read.mapRows(statement)
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
): ReadRows {
    val dao = db.taskDao()
    return when (read) {
        Read.BlockingEdges -> {
            ReadRows.Texts(dao.getBlockingEdgeIdsForUser(taskId, owner.value, BLOCKING_VERB).first())
        }

        Read.TypedDependencies -> {
            ReadRows.Dependencies(dao.observeTypedDependenciesForUser(taskId, owner.value).first())
        }

        Read.TagIds -> {
            ReadRows.Texts(dao.getTagIdsForUser(taskId, owner.value).first())
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
 * The rows a statement returns, mapped into a list the way a DAO maps them.
 *
 * The mapping is part of the price of a read, so it is here and not a row
 * count: the forms have to do the same work for the comparison to mean
 * anything, and the result list is work a real read pays too.
 */
private fun Read.mapRows(statement: SQLiteStatement): ReadRows =
    when (this) {
        Read.BlockingEdges, Read.TagIds -> {
            ReadRows.Texts(
                buildList {
                    while (statement.step()) {
                        add(statement.getText(0))
                    }
                },
            )
        }

        Read.TypedDependencies -> {
            ReadRows.Dependencies(
                buildList {
                    while (statement.step()) {
                        add(
                            TaskDependencyCrossRef(
                                taskId = statement.getText(0),
                                dependsOnTaskId = statement.getText(1),
                                verb = statement.getText(2),
                            ),
                        )
                    }
                },
            )
        }
    }

/**
 * The observer on a change: a live collector, a write, and the re-read.
 *
 * Three numbers are priced: the write alone — no collector is alive, so
 * nothing is notified; the same write with a collector alive, which waits for
 * the re-read the observer triggers; and the **observer's latency**: the time
 * from the end of the write to the emission the observer triggers.
 *
 * The observer's latency is priced on its own rather than as the difference
 * of two medians. The difference of the two writes would count the observer's
 * share, but on a shared machine that difference comes out negative: the
 * write alone is charged for the background work the tracker of the previous
 * iteration left behind, and a negative price is not a price. The latency is
 * a quantity in its own right — how long a screen waits for the re-read — and
 * it cannot be negative.
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
                observerLatency = Duration.ZERO,
                reEmissions = 0,
            )

    for (index in 0 until config.warmup) {
        write()
    }

    val alone = mutableListOf<Duration>()
    for (index in 0 until config.iterations) {
        val mark = TimeSource.Monotonic.markNow()
        write()
        alone += mark.elapsedNow()
    }

    val observed = mutableListOf<Duration>()
    val latencies = mutableListOf<Duration>()
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
                val written = mark.elapsedNow()
                val reRead = withTimeoutOrNull(30.seconds) { emissions.receive() }
                if (reRead != null) {
                    val total = written + mark.elapsedNow()
                    observed += total
                    latencies += total - written
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
        observerLatency = if (latencies.isEmpty()) Duration.ZERO else percentile(latencies, 0.5),
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
 * The price of taking a connection from the pool and giving it back.
 *
 * No query runs here: this is the cost every read pays before it can run
 * its statement. It is measured before anything else, while no connection is
 * held and none has been taken yet.
 */
private suspend fun measureConnection(
    db: AppDatabase,
    config: ReadOverheadConfig,
): List<Duration> {
    for (index in 0 until config.warmup) {
        db.useConnection(isReadOnly = false) { }
    }
    val durations = mutableListOf<Duration>()
    for (index in 0 until config.iterations) {
        val mark = TimeSource.Monotonic.markNow()
        db.useConnection(isReadOnly = false) { }
        durations += mark.elapsedNow()
    }
    return durations
}

/** One priced form of one read. */
private fun priced(
    read: Read,
    form: String,
    durations: List<Duration>,
): OverheadMeasurement =
    OverheadMeasurement(
        read = read,
        form = form,
        p50 = percentile(durations, 0.5),
        p95 = percentile(durations, 0.95),
        samples = durations.size,
    )

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
        appendLine("- rows checked: the statement, the one-shot call and the Flow answered the same rows")
        appendLine()
        appendLine("| read | form | p50 ms | p95 ms | samples |")
        appendLine("|---|---|---|---|---|")
        for (m in measurements) {
            appendLine("| ${m.read.label} | ${m.form} | ${millis(m.p50)} | ${millis(m.p95)} | ${m.samples} |")
        }
        appendLine()
        appendLine(
            "| read | statement | one-shot | flow | call path | Flow machinery | overhead | observer alone |",
        )
        appendLine("|---|---|---|---|---|---|---|---|")
        for (read in Read.entries) {
            val statement = measurements.median(read, "statement")
            val oneShot = measurements.median(read, "one-shot")
            val flow = measurements.median(read, "flow")
            val tracker = measurements.median(read, "tracker")
            val callPath = measurements.median(read, "call path")
            val machinery = measurements.median(read, "Flow machinery")
            val overhead = measurements.median(read, "overhead")
            appendLine(
                "| ${read.label} | ${millis(statement)} | ${millis(oneShot)} | ${millis(flow)} | " +
                    "${millis(callPath)} | ${millis(machinery)} | ${millis(overhead)} | ${millis(tracker)} |",
            )
        }
        appendLine()
        appendLine(
            "The overhead is the call path plus the Flow machinery, in every iteration. " +
                "The observer is published on its own: measured apart it pays for a connection and a " +
                "collector of its own, so it is not subtracted from the Flow machinery — it is a lower " +
                "bound of it.",
        )
        appendLine()
        appendLine("### The connection the query forms do not pay for")
        appendLine()
        appendLine(
            "The statement form holds its connection, so the price of taking one is not in it. " +
                "Every other form pays it: p50 ${millis(percentile(connection, 0.5))} ms, " +
                "p95 ${millis(percentile(connection, 0.95))} ms, ${connection.size} samples.",
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
