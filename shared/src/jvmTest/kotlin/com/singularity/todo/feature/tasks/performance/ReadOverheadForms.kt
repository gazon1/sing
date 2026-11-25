package com.singularity.todo.feature.tasks.performance

import androidx.room3.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The verb the blocking read is asked for. */
private const val BLOCKING_VERB = "BLOCKS"

// The forms of a read, and the change the observer reacts to.
//
// Every form takes its connection from the readers' pool and gives it back
// before the next form starts: a form measured on a connection another form
// holds would make the difference between two forms the difference between two
// pools, and the parts of the overhead would not be parts of the overhead.

/** The read as the statement alone: the SQL on a reader connection of its own. */
internal suspend fun statementRows(
    db: AppDatabase,
    read: Read,
    taskId: String,
    owner: UserId,
): ReadRows =
    db.useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(read.sql) { statement ->
            read.bind(statement, taskId, owner)
            read.mapRows(statement)
        }
    }

/**
 * The same SQL as a suspend call.
 *
 * `performSuspending` is the call a suspend DAO method is compiled into: the
 * hop to the database's dispatcher, the connection, and the statement —
 * without a Flow and without an observer. The rows are mapped into a list the
 * way the DAO maps them, so the two forms do the same work and only the call
 * around it differs.
 */
internal suspend fun oneShotRows(
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

/**
 * The Flow's own machinery, without the invalidation observer.
 *
 * The DAO's Flow is the observer's flow, conflated, mapped onto the call. This
 * form is the same pipeline with the observer replaced by a single emission:
 * what is left of the difference between the two is the price of the observer,
 * measured where the Flow really uses it.
 */
internal suspend fun plumbingRows(
    db: AppDatabase,
    read: Read,
    taskId: String,
    owner: UserId,
): ReadRows =
    flowOf(Unit)
        .conflate()
        .map { oneShotRows(db, read, taskId, owner) }
        .first()

/** The read as the app makes it: the DAO's Flow, collected once. */
internal suspend fun flowRows(
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
 * This is the price of the observer's registration in a pass of its own — it
 * pays for a connection and a collector of its own, which the Flow path
 * shares with the rest of the machinery. It is published next to the parts,
 * never subtracted from them.
 */
internal suspend fun trackerRows(
    db: AppDatabase,
    read: Read,
) {
    db.invalidationTracker.createFlow(*read.tables.toTypedArray()).first()
}

/**
 * Times the four forms of one read, side by side.
 *
 * Every form runs the same warmup, the same iteration count and the same
 * clock, in the same loop: the difference between two forms is then the
 * difference between the calls, not between the runs. No form holds a
 * connection while another one runs.
 */
internal suspend fun measureForms(
    db: AppDatabase,
    read: Read,
    probes: List<String>,
    config: ReadOverheadConfig,
): FormTimings {
    val tracker = measureTracker(db, read, probes.size, config)
    val statement = mutableListOf<Duration>()
    val oneShot = mutableListOf<Duration>()
    val plumbing = mutableListOf<Duration>()
    val flow = mutableListOf<Duration>()
    for (index in 0 until config.warmup) {
        for (taskId in probes) {
            statementRows(db, read, taskId, config.owner)
            oneShotRows(db, read, taskId, config.owner)
            plumbingRows(db, read, taskId, config.owner)
            flowRows(db, read, taskId, config.owner)
        }
    }
    for (index in 0 until config.iterations) {
        for (taskId in probes) {
            var mark = TimeSource.Monotonic.markNow()
            statementRows(db, read, taskId, config.owner)
            statement += mark.elapsedNow()

            mark = TimeSource.Monotonic.markNow()
            oneShotRows(db, read, taskId, config.owner)
            oneShot += mark.elapsedNow()

            mark = TimeSource.Monotonic.markNow()
            plumbingRows(db, read, taskId, config.owner)
            plumbing += mark.elapsedNow()

            mark = TimeSource.Monotonic.markNow()
            flowRows(db, read, taskId, config.owner)
            flow += mark.elapsedNow()
        }
    }
    return FormTimings(
        statement = statement,
        oneShot = oneShot,
        plumbing = plumbing,
        flow = flow,
        tracker = tracker,
    )
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
internal suspend fun measureTracker(
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
internal suspend fun measureChange(
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
                // One mark for the whole iteration: it measures the write and
                // then the wait for the emission, and the difference between
                // the two is the wait alone.
                val mark = TimeSource.Monotonic.markNow()
                write()
                val written = mark.elapsedNow()
                val reRead = withTimeoutOrNull(30.seconds) { emissions.receive() }
                if (reRead != null) {
                    val total = mark.elapsedNow()
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
        writeAlone = nearestRank(alone, 0.5),
        writeAndObserve = if (observed.isEmpty()) Duration.ZERO else nearestRank(observed, 0.5),
        observerLatency = if (latencies.isEmpty()) Duration.ZERO else nearestRank(latencies, 0.5),
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
 * only thing the observer has to react to is the write itself. A write of a
 * row the relation does not hold would add a row instead, and the read would
 * answer a different question afterwards.
 */
internal suspend fun changeWriter(
    db: AppDatabase,
    read: Read,
    taskId: String,
    owner: UserId,
): (suspend () -> Unit)? =
    when (read) {
        Read.BlockingEdges, Read.TypedDependencies -> {
            val verb = if (read == Read.BlockingEdges) BLOCKING_VERB else null
            val edge = db.dependencyRow(taskId, verb)
            if (edge == null) {
                null
            } else {
                val (depId, edgeVerb) = edge
                suspend { db.taskDao().upsertDependencyForUser(taskId, depId, edgeVerb, owner.value) }
            }
        }

        Read.TagIds -> {
            val tagId = db.tagRow(taskId)
            if (tagId == null) {
                null
            } else {
                suspend { db.taskDao().upsertTagCrossRefForUser(taskId, tagId, owner.value) }
            }
        }
    }

/**
 * One row of the dependency relation: the edge's target and its verb.
 *
 * The verb is part of the answer, so the write has to put back exactly what
 * is there — a write with another verb would change the rows the read answers
 * with. The blocking read asks for one verb only, and its row is taken with
 * that verb.
 */
private suspend fun AppDatabase.dependencyRow(
    taskId: String,
    verb: String?,
): Pair<String, String>? =
    useConnection(isReadOnly = true) { transactor ->
        val sql =
            if (verb == null) {
                "SELECT depends_on_task_id, verb FROM task_dependencies WHERE task_id = ? LIMIT 1"
            } else {
                "SELECT depends_on_task_id, verb FROM task_dependencies " +
                    "WHERE task_id = ? AND verb = ? LIMIT 1"
            }
        transactor.usePrepared(sql) { statement ->
            statement.bindText(1, taskId)
            if (verb != null) {
                statement.bindText(2, verb)
            }
            if (statement.step()) {
                statement.getText(0) to statement.getText(1)
            } else {
                null
            }
        }
    }

/** One tag the task already carries. */
private suspend fun AppDatabase.tagRow(taskId: String): String? =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared("SELECT tag_id FROM task_tags WHERE task_id = ? LIMIT 1") { statement ->
            statement.bindText(1, taskId)
            if (statement.step()) {
                statement.getText(0)
            } else {
                null
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
internal suspend fun measureConnection(
    db: AppDatabase,
    config: ReadOverheadConfig,
): List<Duration> {
    for (index in 0 until config.warmup) {
        db.useConnection(isReadOnly = true) { }
    }
    val durations = mutableListOf<Duration>()
    for (index in 0 until config.iterations) {
        val mark = TimeSource.Monotonic.markNow()
        db.useConnection(isReadOnly = true) { }
        durations += mark.elapsedNow()
    }
    return durations
}

/** The parameters of a statement, in the order they are written. */
internal fun Read.bind(
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
 * The rows a statement returns, mapped into a list the way the DAO maps them.
 *
 * The mapping is part of the price of a read, so it is here and not a row
 * count: the forms have to do the same work for the comparison to mean
 * anything, and the result list is work a real read pays too.
 */
internal fun Read.mapRows(statement: SQLiteStatement): ReadRows =
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
