// A measurement harness is a program, not a test: it measures a dataset that
// lives outside the test run, and a test that needed one would either skip
// itself or fail for a reason that has nothing to do with the claim.
@file:Suppress("NoRunBlocking")

package com.singularity.todo.feature.tasks.performance

import androidx.sqlite.SQLiteStatement
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The user-scoped reads of one task's rows, measured on a restored synthetic dataset.
 *
 * ## What is measured
 *
 * Four DAO reads, each in three forms:
 *
 * - **dao** — the read the app makes: `TaskDao.getBlockingEdgeIdsForUser`,
 *   `TaskDao.blockingTasksForUser`, `TaskDao.observeTypedDependenciesForUser`
 *   and `TaskDao.getTagIdsForUser`.
 * - **sql** — the same SQL straight at the database. What the query costs without
 *   the call around it.
 * - **candidate** — the same question asked as a primary-key join on `tasks.id`,
 *   the shape the ordering-edge read already uses (#3153). The experiment: the
 *   production SQL is not changed by this program.
 *
 * The blocking-tasks read carries two candidates: **candidate-join** — the
 * plain join, where the owner filter sits in the join clause — and
 * **candidate-pk**, where the scope is resolved through the primary key only.
 *
 * Every read is priced on two groups of tasks: the tasks that carry rows and the
 * task that carries the most of them.
 *
 * ## What is asserted
 *
 * The rows, not the clock. A clock measures the machine and the machine is shared;
 * a row count does not move with it. The harness asserts that the candidate form
 * returns exactly the rows the current form returns — for every task it reads, and
 * over the whole relation of the profile, so that a row only an unprobed task
 * carries cannot pass unnoticed — and that the scope holds: a foreign profile sees
 * nothing, and a task that does not exist has nothing to show. A disagreement fails
 * the run with a non-zero exit status. It records the query plans of both forms.
 * The latencies are recorded, not asserted.
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
 *
 * A dataset that does not fit a backup archive is generated as a dataset
 * directory (`--format dir`) and measured the same way:
 *
 * ```
 * ./gw :import-cli:run --args="generate --profile 100k --seed 4242 --tags 1 --format dir --output build/perf/100k-tags"
 * ./gw :shared:dependencyQueryPerf -Pperf.dataset=$PWD/build/perf/100k-tags
 * ```
 */
fun main(args: Array<String>) {
    // One program, one dataset pipeline, one set of arguments — two
    // experiments. The second one (#3196) asks a different question of the
    // database, not a different question of the dataset, so it runs through
    // the same entry point and the same task.
    when (perfArgs(args)["experiment"] ?: "sql-forms") {
        "sql-forms" -> {
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

        "read-overhead" -> {
            readOverheadMain(args)
        }

        else -> {
            error(
                "unknown experiment: ${perfArgs(args)["experiment"]} — " +
                    "the harness runs \"sql-forms\" and \"read-overhead\"",
            )
        }
    }
}

/** What one run produced: the report, and the row mismatch when there was one. */
private data class RunOutcome(val report: String, val mismatch: String?)

/** What to measure, and how often. */
private data class DependencyPerfConfig(
    val dataset: PerfDataset,
    val iterations: Int,
    val warmup: Int,
    val owner: UserId,
) {
    companion object {
        fun parse(args: Array<String>): DependencyPerfConfig {
            val values = perfArgs(args)
            return DependencyPerfConfig(
                dataset = PerfDataset.parse(values),
                iterations = (values["iterations"] ?: "10").toInt(),
                warmup = (values["warmup"] ?: "3").toInt(),
                owner = UserId(values["owner"] ?: "perf-owner"),
            )
        }
    }
}

/** The ways a read is asked. */
private enum class Form(val label: String) {
    Dao("dao"),
    Sql("sql"),
    Candidate("candidate"),
    CandidateJoin("candidate-join"),
    CandidatePk("candidate-pk"),
}

/**
 * The four reads under measurement.
 *
 * [bindsVerb] says whether the statement carries the edge kind as a parameter:
 * the two reads that answer about edges ask for one kind, the others do not.
 *
 * [forms] are the ways each read is priced. The blocking-tasks read has two
 * candidates: the plain join on `tasks`, and the join that resolves the scope
 * through the primary key only.
 */
private enum class Query(val label: String, val relation: Relation, val bindsVerb: Boolean, val forms: List<Form>) {
    Blocking(
        "getBlockingEdgeIdsForUser",
        Relation("task_dependencies", "edges"),
        true,
        listOf(Form.Dao, Form.Sql, Form.Candidate),
    ),
    BlockingTasks(
        "blockingTasksForUser",
        Relation("task_dependencies", "edges"),
        true,
        listOf(Form.Dao, Form.Sql, Form.CandidateJoin, Form.CandidatePk),
    ),
    Typed(
        "observeTypedDependenciesForUser",
        Relation("task_dependencies", "edges"),
        false,
        listOf(Form.Dao, Form.Sql, Form.Candidate),
    ),
    TagIds("getTagIdsForUser", Relation("task_tags", "tags"), false, listOf(Form.Dao, Form.Sql, Form.Candidate)),
}

/**
 * A read over a relation table: the table itself, and what its rows are called.
 *
 * The probe groups are asked per relation, not per read: the tasks that carry
 * edges are not the tasks that carry tags.
 */
private data class Relation(val table: String, val noun: String)

/**
 * The SQL the DAO runs today.
 *
 * The blocking-tasks read numbers its parameters (`?1` the task, `?2` the kind
 * of edge, `?3` the owner) because its owner filter sits in the join clause,
 * ahead of the task in the text, while the other reads take their parameters
 * in the order they are written.
 */
private fun Query.currentSql(): String =
    when (this) {
        Query.Blocking -> {
            """
            SELECT depends_on_task_id FROM task_dependencies
            WHERE task_id = ? AND verb = ?
            AND task_id IN (SELECT id FROM tasks WHERE user_id = ?)
            """.trimIndent()
        }

        Query.BlockingTasks -> {
            """
            SELECT b.* FROM task_dependencies d
            JOIN tasks b ON b.id = d.depends_on_task_id AND b.user_id = ?3
            WHERE d.task_id = ?1 AND d.verb = ?2
              AND d.task_id IN (SELECT id FROM tasks WHERE user_id = ?3)
              AND b.completed_at IS NULL AND b.archived_at IS NULL
            """.trimIndent()
        }

        Query.Typed -> {
            """
            SELECT td.task_id, td.depends_on_task_id, td.verb FROM task_dependencies td
            WHERE td.task_id = ?
            AND td.task_id IN (SELECT id FROM tasks WHERE user_id = ?)
            """.trimIndent()
        }

        Query.TagIds -> {
            """
            SELECT tag_id FROM task_tags
            WHERE task_id = ?
            AND task_id IN (SELECT id FROM tasks WHERE user_id = ?)
            """.trimIndent()
        }
    }

/**
 * The same question asked as a join on `tasks.id`.
 *
 * For the blocking-tasks read this is the plain join: the owner filter sits in
 * the join clause, so SQLite may drive the read from the owner's whole task
 * list. It is priced as [Form.CandidateJoin] — the middle step, not the answer.
 */
private fun Query.candidateJoinSql(): String =
    when (this) {
        Query.BlockingTasks -> {
            """
            SELECT b.* FROM task_dependencies d
            JOIN tasks b ON b.id = d.depends_on_task_id AND b.user_id = ?3
            JOIN tasks t ON t.id = d.task_id AND t.user_id = ?3
            WHERE d.task_id = ?1 AND d.verb = ?2
              AND b.completed_at IS NULL AND b.archived_at IS NULL
            """.trimIndent()
        }

        else -> {
            error("$label has no plain-join candidate")
        }
    }

/**
 * The same question asked with the scope resolved through the primary key only.
 *
 * The blocking-tasks read takes both of its `tasks` rows by primary key: the
 * task the edge hangs on, and the blocker. The owner filter on the blocker
 * stays out of the join clause — an owner filter there gives SQLite the
 * option of driving the read from the owner's whole task list — and becomes an
 * `EXISTS` over the primary key.
 */
private fun Query.candidatePkSql(): String =
    when (this) {
        Query.BlockingTasks -> {
            """
            SELECT b.* FROM task_dependencies d
            JOIN tasks t ON t.id = d.task_id AND t.user_id = ?3
            JOIN tasks b ON b.id = d.depends_on_task_id
            WHERE d.task_id = ?1 AND d.verb = ?2
              AND EXISTS (SELECT 1 FROM tasks ob WHERE ob.id = b.id AND ob.user_id = ?3)
              AND b.completed_at IS NULL AND b.archived_at IS NULL
            """.trimIndent()
        }

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

        Query.TagIds -> {
            """
            SELECT tt.tag_id FROM task_tags tt
            JOIN tasks t ON t.id = tt.task_id
            WHERE tt.task_id = ? AND t.user_id = ?
            """.trimIndent()
        }
    }

/**
 * The SQL of one form of a read.
 *
 * [Form.Dao] has no SQL of its own: it is the read the app makes, and it is
 * priced through the DAO.
 */
private fun Query.sqlFor(form: Form): String =
    when (form) {
        Form.Dao -> error("$label is priced through the DAO, not through SQL")
        Form.Sql -> currentSql()
        Form.Candidate -> candidatePkSql()
        Form.CandidateJoin -> candidateJoinSql()
        Form.CandidatePk -> candidatePkSql()
    }

/** The forms of a read that answer the same question as the current one. */
private fun Query.candidateForms(): List<Form> =
    forms.filter { it == Form.Candidate || it == Form.CandidateJoin || it == Form.CandidatePk }

/**
 * The current form asked over the whole relation of one profile.
 *
 * The per-task forms above answer one task; these answer every row the profile
 * owns. They are what the whole-relation comparison below compares.
 *
 * The scope is spelled as an `EXISTS` over the task's primary key rather than
 * as `task_id IN (SELECT id FROM tasks WHERE user_id = :userId)`. The two ask
 * the same question — does a task with this id belong to this profile — but the
 * list spelling answers it once per row of the relation, which on a 50k-task
 * profile means a scan of the user's task list for every row of the relation,
 * and a comparison that takes minutes instead of a second. Only the spelling
 * of the scope changes here; the per-task form above, which is the form whose
 * latency the measurement is about, keeps the list.
 */
private fun Query.currentScopeSql(): String =
    when (this) {
        Query.Blocking -> {
            """
            SELECT d.task_id, d.depends_on_task_id FROM task_dependencies d
            WHERE d.verb = 'BLOCKS'
            AND EXISTS (SELECT 1 FROM tasks t WHERE t.id = d.task_id AND t.user_id = ?1)
            """.trimIndent()
        }

        Query.BlockingTasks -> {
            """
            SELECT d.task_id, d.depends_on_task_id FROM task_dependencies d
            JOIN tasks b ON b.id = d.depends_on_task_id AND b.user_id = ?1
            WHERE d.verb = 'BLOCKS'
              AND EXISTS (SELECT 1 FROM tasks t WHERE t.id = d.task_id AND t.user_id = ?1)
              AND b.completed_at IS NULL AND b.archived_at IS NULL
            """.trimIndent()
        }

        Query.Typed -> {
            """
            SELECT td.task_id, td.depends_on_task_id, td.verb FROM task_dependencies td
            WHERE EXISTS (SELECT 1 FROM tasks t WHERE t.id = td.task_id AND t.user_id = ?1)
            """.trimIndent()
        }

        Query.TagIds -> {
            """
            SELECT tt.task_id, tt.tag_id FROM task_tags tt
            WHERE EXISTS (SELECT 1 FROM tasks t WHERE t.id = tt.task_id AND t.user_id = ?1)
            """.trimIndent()
        }
    }

/** The candidate form asked over the whole relation of one profile. */
private fun Query.candidateScopeSql(form: Form): String =
    when (this) {
        Query.Blocking -> {
            """
            SELECT d.task_id, d.depends_on_task_id FROM task_dependencies d
            JOIN tasks t ON t.id = d.task_id
            WHERE d.verb = 'BLOCKS' AND t.user_id = ?1
            """.trimIndent()
        }

        Query.BlockingTasks -> {
            when (form) {
                Form.CandidateJoin -> {
                    """
                    SELECT d.task_id, d.depends_on_task_id FROM task_dependencies d
                    JOIN tasks b ON b.id = d.depends_on_task_id AND b.user_id = ?1
                    JOIN tasks t ON t.id = d.task_id AND t.user_id = ?1
                    WHERE d.verb = 'BLOCKS'
                      AND b.completed_at IS NULL AND b.archived_at IS NULL
                    """.trimIndent()
                }

                else -> {
                    """
                    SELECT d.task_id, d.depends_on_task_id FROM task_dependencies d
                    JOIN tasks t ON t.id = d.task_id AND t.user_id = ?1
                    JOIN tasks b ON b.id = d.depends_on_task_id
                    WHERE d.verb = 'BLOCKS'
                      AND EXISTS (SELECT 1 FROM tasks ob WHERE ob.id = b.id AND ob.user_id = ?1)
                      AND b.completed_at IS NULL AND b.archived_at IS NULL
                    """.trimIndent()
                }
            }
        }

        Query.Typed -> {
            """
            SELECT td.task_id, td.depends_on_task_id, td.verb FROM task_dependencies td
            JOIN tasks t ON t.id = td.task_id
            WHERE t.user_id = ?1
            """.trimIndent()
        }

        Query.TagIds -> {
            """
            SELECT tt.task_id, tt.tag_id FROM task_tags tt
            JOIN tasks t ON t.id = tt.task_id
            WHERE t.user_id = ?1
            """.trimIndent()
        }
    }

/** The verb the blocking read is asked for. */
private const val BLOCKING_VERB = "BLOCKS"

/** A profile that owns nothing in the restored dataset. */
private val FOREIGN_USER = UserId("perf-foreign-user")

/** A task of the foreign profile. */
private const val FOREIGN_TASK = "perf-foreign-task"

/** A second task of the foreign profile — the one it is blocked by. */
private const val FOREIGN_BLOCKED_TASK = "perf-foreign-blocked-task"

/** A task that is not in the restored dataset. */
private const val UNKNOWN_TASK = "perf-unknown-task"

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
    val dataset = config.dataset
    check(dataset.file.exists()) { "no dataset to measure: ${dataset.file.absolutePath}" }
    val fixture = DatabaseTestFixture.onDisk()
    try {
        val db = fixture.database("perf")
        restore(db, fixture, dataset, config.owner)
        val tasks = db.scalarInt("SELECT count(*) FROM tasks")
        val edges = db.scalarInt("SELECT count(*) FROM task_dependencies")
        val tags = db.scalarInt("SELECT count(*) FROM task_tags")
        check(tasks > 0) { "the dataset holds no tasks — there is nothing to read" }

        val measurements = mutableListOf<ReadMeasurement>()
        val groups = mutableMapOf<Query, List<ProbeGroup>>()
        for (query in Query.entries) {
            val queryGroups = probeGroups(db, query.relation)
            groups[query] = queryGroups
            for (group in queryGroups) {
                for (form in query.forms) {
                    measurements += measureOne(db, query, group, form, config)
                }
            }
        }
        val plans = plans(db, config.owner)
        val mismatch =
            equivalence(db, groups, config.owner)
                ?: relationEquivalence(db, config.owner)
                ?: isolation(db, groups, config.owner)
        val report = render(dataset, tasks, edges, tags, groups, measurements, plans, mismatch)
        return RunOutcome(report = report, mismatch = mismatch)
    } finally {
        fixture.close()
    }
}

/**
 * The tasks a read is priced on.
 *
 * The first group holds the tasks that carry rows of the relation — the reads
 * that return rows. The second holds the task that carries the most of them: the
 * read that returns the most rows. A relation without rows has no first group,
 * and the second group falls back to the tasks themselves: the read still costs
 * what it costs when it answers nothing.
 */
private suspend fun probeGroups(
    db: AppDatabase,
    relation: Relation,
): List<ProbeGroup> {
    val withRows = db.texts("SELECT DISTINCT task_id FROM ${relation.table} ORDER BY task_id LIMIT 10")
    val hub = db.texts("SELECT task_id FROM ${relation.table} GROUP BY task_id ORDER BY count(*) DESC LIMIT 1")
    val fallback = db.texts("SELECT id FROM tasks ORDER BY id LIMIT 10")
    return buildList {
        if (withRows.isNotEmpty()) {
            add(ProbeGroup("with ${relation.noun}", withRows))
        }
        val hubId = hub.firstOrNull()
        if (hubId != null) {
            add(ProbeGroup("most ${relation.noun}", listOf(hubId)))
        } else if (fallback.isNotEmpty()) {
            add(ProbeGroup("no ${relation.noun}", fallback))
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
        p50 = nearestRank(durations, 0.5),
        p95 = nearestRank(durations, 0.95),
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
        else -> db.rowsOf(query, query.sqlFor(form), taskId, owner)
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
        Query.BlockingTasks -> dao.blockingTasksForUser(taskId, owner.value, BLOCKING_VERB).size
        Query.Typed -> dao.observeTypedDependenciesForUser(taskId, owner.value).first().size
        Query.TagIds -> dao.getTagIdsForUser(taskId, owner.value).first().size
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
    groups: Map<Query, List<ProbeGroup>>,
    owner: UserId,
): String? {
    for (query in Query.entries) {
        for (group in groups[query].orEmpty()) {
            for (taskId in group.taskIds) {
                val current = db.rowTexts(query, query.currentSql(), taskId, owner).sorted()
                for (form in query.candidateForms()) {
                    val candidate = db.rowTexts(query, query.sqlFor(form), taskId, owner).sorted()
                    if (current != candidate) {
                        return "${query.label}: $taskId — " +
                            "current ${current.size} row(s), ${form.label} ${candidate.size} row(s)"
                    }
                }
            }
        }
    }
    return null
}

/**
 * The two forms asked over the whole relation, not over probed tasks.
 *
 * The per-task comparison above proves the two forms answer the same question
 * for the tasks it reads. This one proves it for every row the profile owns: the
 * two forms are asked over the whole relation, and neither may hold a row the
 * other does not — neither as a set, nor in number. A profile that owns nothing
 * must see nothing in either form.
 *
 * It costs four statements per read, so it is affordable on every dataset.
 */
private suspend fun relationEquivalence(
    db: AppDatabase,
    owner: UserId,
): String? {
    for (query in Query.entries) {
        val current = query.currentScopeSql()
        val candidates = query.candidateForms().map { it.label to query.candidateScopeSql(it) }
        for ((label, candidate) in candidates) {
            val mismatch = relationMismatch(db, owner, query, current, label, candidate)
            if (mismatch != null) {
                return mismatch
            }
        }
        val leak = foreignRelationRows(db, query, listOf("current" to current) + candidates)
        if (leak != null) {
            return leak
        }
    }
    return null
}

/** How the current form and one candidate form differ over the whole relation. */
private suspend fun relationMismatch(
    db: AppDatabase,
    owner: UserId,
    query: Query,
    current: String,
    label: String,
    candidate: String,
): String? {
    val currentRows = db.countOf("SELECT count(*) FROM ($current)", owner.value)
    val candidateRows = db.countOf("SELECT count(*) FROM ($candidate)", owner.value)
    if (currentRows != candidateRows) {
        return "${query.label}: the current form holds $currentRows row(s) of the relation, " +
            "the $label form $candidateRows"
    }
    val onlyCurrent = db.countOf("SELECT count(*) FROM ($current EXCEPT $candidate)", owner.value)
    if (onlyCurrent != 0) {
        return "${query.label}: $onlyCurrent row(s) of the relation are in the current form " +
            "and not in the $label form"
    }
    val onlyCandidate = db.countOf("SELECT count(*) FROM ($candidate EXCEPT $current)", owner.value)
    if (onlyCandidate != 0) {
        return "${query.label}: $onlyCandidate row(s) of the relation are in the $label form " +
            "and not in the current"
    }
    return null
}

/** The rows of the relation a foreign profile would see, if it saw any. */
private suspend fun foreignRelationRows(
    db: AppDatabase,
    query: Query,
    forms: List<Pair<String, String>>,
): String? {
    for ((form, sql) in forms) {
        val foreign = db.countOf("SELECT count(*) FROM ($sql)", FOREIGN_USER.value)
        if (foreign != 0) {
            return "${query.label}: the $form form shows $foreign row(s) of the relation to a foreign profile"
        }
    }
    return null
}

/**
 * The scope the rows have to respect.
 *
 * Two rules, on the first task each read touches: a foreign profile sees
 * nothing, and a task that does not exist has nothing to show. The tag read has
 * a third: the owner sees exactly the tag rows of its own task.
 *
 * The restored dataset holds a single profile, so the owner rule is checked
 * against a second profile the harness adds itself — two tasks of a foreign
 * profile and the edges that hang on them. Without them "the owner sees only
 * its own blocking tasks" would hold for a read that had dropped the owner
 * filter: there would be no foreign blocker to drop.
 */
private suspend fun isolation(
    db: AppDatabase,
    groups: Map<Query, List<ProbeGroup>>,
    owner: UserId,
): String? {
    val blocking = Query.BlockingTasks
    val probedTask =
        groups[blocking]
            .orEmpty()
            .firstOrNull()
            ?.taskIds
            ?.firstOrNull()
    val ownerRowsBefore = probedTask?.let { db.rowTexts(blocking, blocking.currentSql(), it, owner).size }
    addForeignProfile(db, probedTask)
    for (query in Query.entries) {
        val taskId =
            groups[query]
                .orEmpty()
                .firstOrNull()
                ?.taskIds
                ?.firstOrNull() ?: continue
        for ((form, sql) in query.formsOf()) {
            val foreign = db.rowTexts(query, sql, taskId, FOREIGN_USER)
            if (foreign.isNotEmpty()) {
                return "${query.label}: the $form form shows ${foreign.size} row(s) of $taskId to a foreign profile"
            }
            val unknown = db.rowTexts(query, sql, UNKNOWN_TASK, owner)
            if (unknown.isNotEmpty()) {
                return "${query.label}: the $form form shows ${unknown.size} row(s) of a task that does not exist"
            }
        }
        if (query == Query.TagIds) {
            val own = db.rowTexts(query, query.currentSql(), taskId, owner).size
            val stored = db.countOf("SELECT count(*) FROM task_tags WHERE task_id = ?", taskId)
            if (own != stored) {
                return "${query.label}: the owner sees $own row(s) of $taskId, the table holds $stored"
            }
        }
    }
    return ownerScope(db, blocking, probedTask, ownerRowsBefore, owner)
}

/**
 * The SQL of every form of a read, with the name the report calls it by.
 *
 * The DAO form has no SQL of its own — it is priced through the DAO — so it is
 * left out here.
 */
private fun Query.formsOf(): List<Pair<String, String>> =
    forms
        .filter { it != Form.Dao }
        .map { it.label to sqlFor(it) }

/**
 * The owner sees only its own blocking tasks.
 *
 * The foreign profile's task hangs on the owner's task as a blocker: the owner
 * must not see it, and the foreign profile must see exactly its own.
 */
private suspend fun ownerScope(
    db: AppDatabase,
    query: Query,
    probedTask: String?,
    ownerRowsBefore: Int?,
    owner: UserId,
): String? {
    if (probedTask == null || ownerRowsBefore == null) return null
    for ((form, sql) in query.formsOf()) {
        val after = db.rowTexts(query, sql, probedTask, owner).size
        if (after != ownerRowsBefore) {
            return "${query.label}: the $form form shows the owner ${after - ownerRowsBefore} row(s) more " +
                "once a foreign blocker was added to $probedTask"
        }
    }
    for ((form, sql) in query.formsOf()) {
        val own = db.rowTexts(query, sql, FOREIGN_TASK, FOREIGN_USER).size
        if (own != 1) {
            return "$query.label: the $form form shows the foreign profile $own row(s) of its own task, one is expected"
        }
    }
    return null
}

/**
 * A second profile in the restored dataset.
 *
 * Two tasks of a foreign profile, one edge between them and one edge from the
 * owner's task to the foreign one — the edge the owner must not see.
 */
private suspend fun addForeignProfile(
    db: AppDatabase,
    ownerTask: String?,
) {
    val dao = db.taskDao()
    dao.upsert(foreignTask(FOREIGN_TASK))
    dao.upsert(foreignTask(FOREIGN_BLOCKED_TASK))
    dao.upsertDependency(
        TaskDependencyCrossRef(taskId = FOREIGN_TASK, dependsOnTaskId = FOREIGN_BLOCKED_TASK, verb = BLOCKING_VERB),
    )
    if (ownerTask != null) {
        dao.upsertDependency(
            TaskDependencyCrossRef(taskId = ownerTask, dependsOnTaskId = FOREIGN_TASK, verb = BLOCKING_VERB),
        )
    }
}

/** A task of the foreign profile. */
private fun foreignTask(id: String): TaskEntity =
    TaskEntity(
        id = id,
        title = "Foreign task",
        description = "",
        projectId = null,
        dueDate = null,
        dueTime = null,
        startDate = null,
        startTime = null,
        endDate = null,
        endTime = null,
        accentColor = null,
        emoji = null,
        completedAt = null,
        archivedAt = null,
        userId = FOREIGN_USER.value,
        createdAt = 0,
        updatedAt = 0,
    )

/** The plans SQLite chose for the current and the candidate form of each query. */
private suspend fun plans(
    db: AppDatabase,
    owner: UserId,
): List<Plan> =
    Query.entries.flatMap { query ->
        query.forms
            .filter { it != Form.Dao }
            .map { form -> Plan("${query.label} — ${form.label}", db.plan(query, query.sqlFor(form), owner)) }
    }

/**
 * The rows of a statement, as text — one string per row.
 *
 * The projection is read off the statement itself: the blocking-tasks read
 * selects the whole task row, and the number of columns it has is the number
 * the statement carries.
 */
private suspend fun AppDatabase.rowTexts(
    query: Query,
    sql: String,
    taskId: String,
    owner: UserId,
): List<String> =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            query.bind(statement, taskId, owner)
            val columns = statement.getColumnCount()
            buildList {
                while (statement.step()) {
                    add(
                        (0 until columns)
                            .map { index -> if (statement.isNull(index)) "" else statement.getText(index) }
                            .joinToString("|"),
                    )
                }
            }
        }
    }

/**
 * The parameters of a statement, in the order they are bound.
 *
 * The owner comes last: the blocking-tasks read numbers its parameters
 * explicitly, and the other reads take them in the order they are written.
 */
private fun Query.bind(
    statement: SQLiteStatement,
    taskId: String,
    owner: UserId,
) {
    statement.bindText(1, taskId)
    if (bindsVerb) {
        statement.bindText(2, BLOCKING_VERB)
        statement.bindText(3, owner.value)
    } else {
        statement.bindText(2, owner.value)
    }
}

/** How many rows a statement returns. */
private suspend fun AppDatabase.rowsOf(
    query: Query,
    sql: String,
    taskId: String,
    owner: UserId,
): Int = rowTexts(query, sql, taskId, owner).size

/** The single number a counting statement returns, with one text parameter. */
private suspend fun AppDatabase.countOf(
    sql: String,
    value: String,
): Int =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            statement.bindText(1, value)
            check(statement.step()) { "the query has to answer: $sql" }
            statement.getLong(0).toInt()
        }
    }

/** The plan of a statement, one line per step. */
private suspend fun AppDatabase.plan(
    query: Query,
    sql: String,
    owner: UserId,
): List<String> =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared("EXPLAIN QUERY PLAN $sql") { statement ->
            query.bind(statement, "t", owner)
            buildList {
                while (statement.step()) {
                    add(statement.getText(3))
                }
            }
        }
    }

/** The report. */
private fun render(
    dataset: PerfDataset,
    tasks: Int,
    edges: Int,
    tags: Int,
    groups: Map<Query, List<ProbeGroup>>,
    measurements: List<ReadMeasurement>,
    plans: List<Plan>,
    mismatch: String?,
): String =
    buildString {
        appendLine("## User-scoped reads — measurement")
        appendLine()
        appendLine(
            "- dataset: `${dataset.file.absolutePath}` " +
                "(${dataset.sizeBytes() / 1024} KiB, ${dataset.form.name.lowercase()})",
        )
        appendLine("- tasks: $tasks, dependency edges: $edges, task-tag rows: $tags")
        for (query in Query.entries) {
            val queryGroups = groups[query].orEmpty()
            appendLine(
                "- groups for ${query.label}: ${queryGroups.joinToString(
                    ", ",
                ) { "${it.name} (${it.taskIds.size} task(s))" }}",
            )
        }
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
        appendLine("### Row equivalence and scope")
        appendLine()
        appendLine(
            mismatch
                ?: "every candidate form returned exactly the rows of the current form on every task read and " +
                "over the whole relation, a foreign profile saw nothing, a task that does not exist showed nothing, " +
                "and the owner saw only its own blocking tasks",
        )
        if (mismatch != null) {
            appendLine()
            appendLine("THE ROWS DISAGREE — this run failed.")
        }
    }
