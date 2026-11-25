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

/** How long the observer gets to say something before the run is broken. */
private val OBSERVER_TIMEOUT = 30.seconds

/**
 * The four forms of a read.
 *
 * The forms are named in the order they are published in, but they are **not**
 * run in that order: see [rotatedForms].
 */
internal enum class ReadForm(val label: String) {
    Statement("statement"),
    OneShot("one-shot"),
    Plumbing("flow without observer"),
    Flow("flow"),
}

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
 * The order the forms are run in, rotated by the slot they belong to.
 *
 * The four forms used to run in one fixed order — statement, one-shot, Flow
 * without observer, Flow — for every probe and every iteration. A fixed order
 * is a systematic bias: the form that runs first pays for the cold pool, the
 * cold caches and the cold code path, and the form that runs last is measured
 * on everything the earlier forms warmed up. The differences between the forms
 * would then be partly the differences between their **positions**, and the
 * count of samples would not remove that — every sample carried the same bias.
 *
 * Rotating the order by the slot removes the bias: over 300 slots every form
 * sits in every position the same number of times, so the position a form was
 * measured in is not a property of the form. The forms still run one after
 * another in the same slot, so the difference between two of them is still the
 * difference between two calls in the same neighbourhood of the machine's
 * state.
 *
 * The rotation is by slot and not by iteration, so the ten probes of one
 * iteration do not all measure the forms in the same order either.
 */
internal fun rotatedForms(slot: Int): List<ReadForm> {
    val offset = slot % ReadForm.entries.size
    return ReadForm.entries.drop(offset) + ReadForm.entries.take(offset)
}

/** Runs one form of one read of one task. */
private suspend fun runForm(
    form: ReadForm,
    db: AppDatabase,
    read: Read,
    taskId: String,
    owner: UserId,
): ReadRows =
    when (form) {
        ReadForm.Statement -> statementRows(db, read, taskId, owner)
        ReadForm.OneShot -> oneShotRows(db, read, taskId, owner)
        ReadForm.Plumbing -> plumbingRows(db, read, taskId, owner)
        ReadForm.Flow -> flowRows(db, read, taskId, owner)
    }

/**
 * Times the four forms of one read, side by side.
 *
 * Every form runs the same warmup, the same iteration count and the same
 * clock, in the same slot: the difference between two forms is then the
 * difference between the calls, not between the runs. No form holds a
 * connection while another one runs, and the order of the forms is rotated
 * from slot to slot, so that no form is always the first one and no form is
 * always the last one.
 */
internal suspend fun measureForms(
    db: AppDatabase,
    read: Read,
    probes: List<String>,
    config: ReadOverheadConfig,
): FormTimings {
    val tracker = measureTracker(db, read, probes.size, config)
    val samples = ReadForm.entries.associateWith { mutableListOf<Duration>() }
    var slot = 0
    for (index in 0 until config.warmup) {
        for (taskId in probes) {
            for (form in rotatedForms(slot)) {
                runForm(form, db, read, taskId, config.owner)
            }
            slot++
        }
    }
    for (index in 0 until config.iterations) {
        for (taskId in probes) {
            for (form in rotatedForms(slot)) {
                val mark = TimeSource.Monotonic.markNow()
                runForm(form, db, read, taskId, config.owner)
                val bucket = samples.getValue(form)
                bucket += mark.elapsedNow()
            }
            slot++
        }
    }
    return FormTimings(
        statement = samples.getValue(ReadForm.Statement).toList(),
        oneShot = samples.getValue(ReadForm.OneShot).toList(),
        plumbing = samples.getValue(ReadForm.Plumbing).toList(),
        flow = samples.getValue(ReadForm.Flow).toList(),
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
 *
 * A missing emission is not a slow machine, it is a broken measurement: the
 * waits are bounded, and a wait that runs out **fails the run**. The count of
 * the re-reads the observer really produced is checked against the number of
 * iterations, so a run that saw fewer re-reads than it wrote cannot publish a
 * number as if it had seen all of them.
 */
internal suspend fun measureChange(
    db: AppDatabase,
    read: Read,
    taskId: String,
    config: ReadOverheadConfig,
): ChangeMeasurement {
    val write = changeWriter(db, read, taskId, config.owner)

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
            // The collector has to open the Flow before the write, or the
            // re-read the write triggers would have no observer to reach.
            awaitEmission(emissions, read)
            // One mark for the whole iteration: it measures the write and
            // then the wait for the emission, and the difference between
            // the two is the wait alone.
            val mark = TimeSource.Monotonic.markNow()
            write()
            val written = mark.elapsedNow()
            awaitEmission(emissions, read)
            val total = mark.elapsedNow()
            observed += total
            latencies += total - written
            reEmissions++
        } finally {
            collector.cancel()
            scope.cancel()
            emissions.close()
        }
    }
    check(reEmissions == config.iterations) {
        "the observer of ${read.label} saw $reEmissions re-reads of ${config.iterations} — " +
            "the measurement of the observer is incomplete and cannot be published"
    }
    return ChangeMeasurement(
        read = read,
        writeAlone = nearestRank(alone, 0.5),
        writeAndObserve = nearestRank(observed, 0.5),
        observerLatency = nearestRank(latencies, 0.5),
        reEmissions = reEmissions,
    )
}

/**
 * Waits for one emission of the observer.
 *
 * The wait is bounded, and a wait that runs out is a failure of the run
 * rather than a sample that is quietly left out: a timeout here means the
 * observer did not see what it was supposed to see, and a benchmark that
 * publishes a number in that case is publishing a number about nothing.
 *
 * The timeout is asked for with `withTimeoutOrNull` and the missing emission
 * is turned into a `check` right after it: that way the run fails with a
 * message that says what happened, and no cancellation is swallowed on the
 * way out.
 */
private suspend fun awaitEmission(
    emissions: Channel<Unit>,
    read: Read,
) {
    val emission = withTimeoutOrNull(OBSERVER_TIMEOUT) { emissions.receive() }
    check(emission != null) {
        "the observer of ${read.label} did not emit within ${OBSERVER_TIMEOUT.inWholeSeconds} s — " +
            "the measurement is broken, not slow"
    }
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
 *
 * A probe task that carries no row of the relation has no write to make, and
 * that is a failure of the measurement rather than a zero to be published: the
 * probe tasks are picked **because** they carry rows.
 */
internal suspend fun changeWriter(
    db: AppDatabase,
    read: Read,
    taskId: String,
    owner: UserId,
): suspend () -> Unit =
    when (read) {
        Read.BlockingEdges, Read.TypedDependencies -> {
            val verb = if (read == Read.BlockingEdges) BLOCKING_VERB else null
            val edge =
                db.dependencyRow(taskId, verb)
                    ?: error("the probe task $taskId carries no row of ${read.relation} — there is nothing to write")
            val (depId, edgeVerb) = edge
            suspend { db.taskDao().upsertDependencyForUser(taskId, depId, edgeVerb, owner.value) }
        }

        Read.TagIds -> {
            val tagId =
                db.tagRow(taskId)
                    ?: error("the probe task $taskId carries no row of ${read.relation} — there is nothing to write")
            suspend { db.taskDao().upsertTagCrossRefForUser(taskId, tagId, owner.value) }
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
