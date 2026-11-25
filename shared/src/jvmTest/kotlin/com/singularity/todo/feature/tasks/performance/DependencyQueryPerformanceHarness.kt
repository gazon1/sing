// A measurement harness is a program, not a test: it measures a dataset that
// lives outside the test run, and a test that needed one would either skip
// itself or fail for a reason that has nothing to do with the claim.
@file:Suppress("NoRunBlocking")

package com.singularity.todo.feature.tasks.performance

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The remaining user-scoped dependency reads, measured on a restored synthetic dataset.
 *
 * ## What is measured
 *
 * Two DAO reads, each in three forms:
 *
 * - **dao** — the read the app makes: `TaskDao.getBlockingEdgeIdsForUser` and
 *   `TaskDao.observeTypedDependenciesForUser`.
 * - **sql** — the same SQL straight at the database. What the query costs without
 *   the call around it.
 * - **candidate** — the same question asked as a primary-key join on `tasks.id`,
 *   the shape the ordering-edge read already uses (#3153). The experiment: the
 *   production SQL is not changed by this program.
 *
 * Every read is priced on two groups of tasks: the tasks that carry edges and the
 * task that carries the most of them.
 *
 * ## What is asserted
 *
 * The rows, not the clock. A clock measures the machine and the machine is shared;
 * a row count does not move with it. The harness asserts that the candidate form
 * returns exactly the rows the current form returns, for every task it reads: a
 * disagreement fails the run with a non-zero exit status. It records the query
 * plans of both forms. The latencies are recorded, not asserted.
 *
 * ## How to run it
 *
 * The dataset comes from the shared generator of #3090, and the harness only reads
 * what the generator wrote:
 *
 * ```
 * ./gw :import-cli:run --args="generate --profile 100k --seed 4242 --output build/perf/100k.zip"
 * ./gw :shared:dependencyQueryPerf -Pperf.archive=$PWD/build/perf/100k.zip
 * ```
 */
fun main(args: Array<String>) {
    val config = DependencyPerfConfig.parse(args)
    val outcome = runBlocking { measure(config) }
    println(outcome.report)
    // A mismatch is a failed run, not a line in the report: the report is
    // printed first so the operator sees what was measured, and the exit code
    // tells the caller that the candidate did not answer the same question.
    check(outcome.mismatch == null) {
        "row equivalence failed — the candidate form does not answer the current one: ${outcome.mismatch}"
    }
}

/** What one run produced: the report, and the row mismatch when there was one. */
private data class RunOutcome(val report: String, val mismatch: String?)

/** What to measure, and how often. */
private data class DependencyPerfConfig(val archive: String, val iterations: Int, val warmup: Int, val owner: UserId) {
    companion object {
        fun parse(args: Array<String>): DependencyPerfConfig {
            val values =
                args
                    .mapNotNull { arg ->
                        if (!arg.startsWith("--")) return@mapNotNull null
                        val key = arg.removePrefix("--").substringBefore('=')
                        val value = arg.substringAfter('=', "")
                        if (value.isEmpty()) null else key to value
                    }.toMap()
            val archive =
                values["archive"]
                    ?: error("no dataset to measure — pass --archive=<path to a generated backup zip>")
            check(archive.isNotBlank()) {
                "no dataset to measure — pass --archive=<path to a generated backup zip>"
            }
            return DependencyPerfConfig(
                archive = archive,
                iterations = (values["iterations"] ?: "10").toInt(),
                warmup = (values["warmup"] ?: "3").toInt(),
                owner = UserId(values["owner"] ?: "perf-owner"),
            )
        }
    }
}

/** The two reads under measurement. */
private enum class Query(val label: String, val columns: Int) {
    Blocking("getBlockingEdgeIdsForUser", 1),
    Typed("observeTypedDependenciesForUser", 3),
}

/** The three forms a read is priced in. */
private enum class Form(val label: String) {
    Dao("dao"),
    Sql("sql"),
    Candidate("candidate"),
}

/** The SQL the DAO runs today. */
private fun Query.currentSql(): String =
    when (this) {
        Query.Blocking -> {
            """
            SELECT depends_on_task_id FROM task_dependencies
            WHERE task_id = ? AND verb = ?
            AND task_id IN (SELECT id FROM tasks WHERE user_id = ?)
            """.trimIndent()
        }

        Query.Typed -> {
            """
            SELECT td.task_id, td.depends_on_task_id, td.verb FROM task_dependencies td
            WHERE td.task_id = ?
            AND td.task_id IN (SELECT id FROM tasks WHERE user_id = ?)
            """.trimIndent()
        }
    }

/** The same question asked as a primary-key join on `tasks.id`. */
private fun Query.candidateSql(): String =
    when (this) {
        Query.Blocking -> {
            """
            SELECT d.depends_on_task_id FROM task_dependencies d
            JOIN tasks t ON t.id = d.task_id
            WHERE d.task_id = ? AND d.verb = ? AND t.user_id = ?
            """.trimIndent()
        }

        Query.Typed -> {
            """
            SELECT td.task_id, td.depends_on_task_id, td.verb FROM task_dependencies td
            JOIN tasks t ON t.id = td.task_id
            WHERE td.task_id = ? AND t.user_id = ?
            """.trimIndent()
        }
    }

/** The verb the blocking read is asked for. */
private const val BLOCKING_VERB = "BLOCKS"

/** One group of tasks a read is priced on. */
private data class ProbeGroup(val name: String, val taskIds: List<String>)

/** One priced read. */
private data class ReadMeasurement(
    val query: Query,
    val group: String,
    val form: Form,
    val p50: Duration,
    val p95: Duration,
    val rows: Int,
    val samples: Int,
)

/** The plan SQLite chose for a statement. */
private data class Plan(val label: String, val steps: List<String>)

private suspend fun measure(config: DependencyPerfConfig): RunOutcome {
    val archive = File(config.archive)
    check(archive.isFile) { "no dataset to measure: ${archive.absolutePath}" }
    val fixture = DatabaseTestFixture.onDisk()
    try {
        val db = fixture.database("perf")
        restore(db, fixture, archive.absolutePath, config.owner)
        val tasks = db.scalarInt("SELECT count(*) FROM tasks")
        val edges = db.scalarInt("SELECT count(*) FROM task_dependencies")
        val groups = probeGroups(db)
        check(groups.isNotEmpty()) { "the dataset holds no tasks — there is nothing to read" }

        val measurements = mutableListOf<ReadMeasurement>()
        for (query in Query.entries) {
            for (group in groups) {
                for (form in Form.entries) {
                    measurements += measureOne(db, query, group, form, config)
                }
            }
        }
        val plans = plans(db, config.owner)
        val mismatch = equivalence(db, groups, config.owner)
        val report = render(archive, tasks, edges, groups, measurements, plans, mismatch)
        return RunOutcome(report = report, mismatch = mismatch)
    } finally {
        fixture.close()
    }
}

/**
 * The tasks a read is priced on.
 *
 * The first group holds the tasks that carry edges — the reads that return rows.
 * The second holds the task that carries the most of them: the read that returns
 * the most rows. A dataset without edges has no first group, and the second group
 * falls back to the tasks themselves: the read still costs what it costs when it
 * answers nothing.
 */
private suspend fun probeGroups(db: AppDatabase): List<ProbeGroup> {
    val withEdges = db.texts("SELECT DISTINCT task_id FROM task_dependencies ORDER BY task_id LIMIT 10")
    val hub = db.texts("SELECT task_id FROM task_dependencies GROUP BY task_id ORDER BY count(*) DESC LIMIT 1")
    val fallback = db.texts("SELECT id FROM tasks ORDER BY id LIMIT 10")
    return buildList {
        if (withEdges.isNotEmpty()) {
            add(ProbeGroup("with edges", withEdges))
        }
        val hubId = hub.firstOrNull()
        if (hubId != null) {
            add(ProbeGroup("most edges", listOf(hubId)))
        } else if (fallback.isNotEmpty()) {
            add(ProbeGroup("no edges", fallback))
        }
    }
}

/** Prices one read in one form. */
private suspend fun measureOne(
    db: AppDatabase,
    query: Query,
    group: ProbeGroup,
    form: Form,
    config: DependencyPerfConfig,
): ReadMeasurement {
    for (index in 0 until config.warmup) {
        for (taskId in group.taskIds) {
            readRows(query, taskId, config.owner, form, db)
        }
    }
    val durations = mutableListOf<Duration>()
    var rows = 0
    for (index in 0 until config.iterations) {
        for (taskId in group.taskIds) {
            val mark = TimeSource.Monotonic.markNow()
            rows = readRows(query, taskId, config.owner, form, db)
            durations += mark.elapsedNow()
        }
    }
    return ReadMeasurement(
        query = query,
        group = group.name,
        form = form,
        p50 = percentile(durations, 0.5),
        p95 = percentile(durations, 0.95),
        rows = rows,
        samples = durations.size,
    )
}

/** One read, in one form. */
private suspend fun readRows(
    query: Query,
    taskId: String,
    owner: UserId,
    form: Form,
    db: AppDatabase,
): Int =
    when (form) {
        Form.Dao -> daoRows(db.taskDao(), query, taskId, owner)
        Form.Sql -> db.rowsOf(query.currentSql(), taskId, owner, query.columns)
        Form.Candidate -> db.rowsOf(query.candidateSql(), taskId, owner, query.columns)
    }

/** The read through the DAO the app calls. */
private suspend fun daoRows(
    dao: TaskDao,
    query: Query,
    taskId: String,
    owner: UserId,
): Int =
    when (query) {
        Query.Blocking -> dao.getBlockingEdgeIdsForUser(taskId, owner.value, BLOCKING_VERB).first().size
        Query.Typed -> dao.observeTypedDependenciesForUser(taskId, owner.value).first().size
    }

/**
 * The rows the current form and the candidate form return, compared.
 *
 * The candidate form is only allowed to replace the current one if it answers the
 * same question — so the harness compares the two on every task it reads, and
 * names the first task where they disagree. A disagreement fails the run: the
 * report is printed and the program exits with a non-zero status, so a scripted
 * measurement cannot pass on a candidate that answers a different question.
 */
private suspend fun equivalence(
    db: AppDatabase,
    groups: List<ProbeGroup>,
    owner: UserId,
): String? {
    for (query in Query.entries) {
        for (group in groups) {
            for (taskId in group.taskIds) {
                val current = db.rowTexts(query.currentSql(), taskId, owner, query.columns).sorted()
                val candidate = db.rowTexts(query.candidateSql(), taskId, owner, query.columns).sorted()
                if (current != candidate) {
                    return "${query.label}: $taskId — " +
                        "current ${current.size} row(s), candidate ${candidate.size} row(s)"
                }
            }
        }
    }
    return null
}

/** The plans SQLite chose for the current and the candidate form of each query. */
private suspend fun plans(
    db: AppDatabase,
    owner: UserId,
): List<Plan> =
    Query.entries.flatMap { query ->
        listOf(
            Plan("${query.label} — current", db.plan(query.currentSql(), owner)),
            Plan("${query.label} — candidate", db.plan(query.candidateSql(), owner)),
        )
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

/** The rows of a statement, as text — one string per row. */
private suspend fun AppDatabase.rowTexts(
    sql: String,
    taskId: String,
    owner: UserId,
    columns: Int,
): List<String> =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            if (sql.contains("verb = ?")) {
                statement.bindText(1, taskId)
                statement.bindText(2, BLOCKING_VERB)
                statement.bindText(3, owner.value)
            } else {
                statement.bindText(1, taskId)
                statement.bindText(2, owner.value)
            }
            buildList {
                while (statement.step()) {
                    add(
                        (0 until columns)
                            .map { statement.getText(it) }
                            .joinToString("|"),
                    )
                }
            }
        }
    }

/** How many rows a statement returns. */
private suspend fun AppDatabase.rowsOf(
    sql: String,
    taskId: String,
    owner: UserId,
    columns: Int,
): Int = rowTexts(sql, taskId, owner, columns).size

/** The plan of a statement, one line per step. */
private suspend fun AppDatabase.plan(
    sql: String,
    owner: UserId,
): List<String> =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared("EXPLAIN QUERY PLAN $sql") { statement ->
            if (sql.contains("verb = ?")) {
                statement.bindText(1, "t")
                statement.bindText(2, BLOCKING_VERB)
                statement.bindText(3, owner.value)
            } else {
                statement.bindText(1, "t")
                statement.bindText(2, owner.value)
            }
            buildList {
                while (statement.step()) {
                    add(statement.getText(3))
                }
            }
        }
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

/** The single number of a query. */
private suspend fun AppDatabase.scalarInt(sql: String): Int =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            check(statement.step()) { "the query has to answer: $sql" }
            statement.getLong(0).toInt()
        }
    }

/** The report. */
private fun render(
    archive: File,
    tasks: Int,
    edges: Int,
    groups: List<ProbeGroup>,
    measurements: List<ReadMeasurement>,
    plans: List<Plan>,
    mismatch: String?,
): String =
    buildString {
        appendLine("## User-scoped dependency reads — measurement")
        appendLine()
        appendLine("- dataset: `${archive.absolutePath}` (${archive.length() / 1024} KiB)")
        appendLine("- tasks: $tasks, dependency edges: $edges")
        appendLine("- groups: ${groups.joinToString(", ") { "${it.name} (${it.taskIds.size} task(s))" }}")
        appendLine()
        appendLine("| query | group | form | p50 ms | p95 ms | rows | samples |")
        appendLine("|---|---|---|---|---|---|---|")
        for (m in measurements) {
            appendLine(
                "| ${m.query.label} | ${m.group} | ${m.form.label} | ${millis(m.p50)} | ${millis(m.p95)} | " +
                    "${m.rows} | ${m.samples} |",
            )
        }
        appendLine()
        appendLine("### Query plans")
        appendLine()
        for (plan in plans) {
            appendLine("- ${plan.label}")
            for (step in plan.steps) {
                appendLine("  - $step")
            }
        }
        appendLine()
        appendLine("### Row equivalence")
        appendLine()
        appendLine(
            mismatch
                ?: "the candidate form returned exactly the rows of the current form on every task read",
        )
        if (mismatch != null) {
            appendLine()
            appendLine("THE ROWS DISAGREE — this run failed.")
        }
    }

/** A duration in milliseconds, with two decimals. */
private fun millis(duration: Duration): String =
    String.format(Locale.ROOT, "%.2f", duration.inWholeMicroseconds / 1000.0)
