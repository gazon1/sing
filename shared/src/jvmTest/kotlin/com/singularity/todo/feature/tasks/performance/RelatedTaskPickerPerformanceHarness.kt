// A measurement harness is a program, not a test: it measures a dataset that
// lives outside the test run, and a test that needed one would either skip
// itself or fail for a reason that has nothing to do with the claim.
@file:Suppress("NoRunBlocking")

package com.singularity.todo.feature.tasks.performance

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.loggerConfigInit
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.backup.BackupImporter
import com.singularity.todo.core.backup.DatasetDirectoryBackupCodec
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.backup.importOptions
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.FtsDao
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.files.JvmFileSourceFactory
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.feature.search.data.SearchTextIndexImpl
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.components.detail.RELATED_TASK_PICKER_FETCH_LIMIT
import com.singularity.todo.feature.tasks.presentation.components.detail.RELATED_TASK_PICKER_MAX_FETCH_LIMIT
import com.singularity.todo.feature.tasks.presentation.components.detail.RELATED_TASK_PICKER_RESULT_LIMIT
import com.singularity.todo.feature.tasks.presentation.components.detail.relatedTaskSearchQuery
import com.singularity.todo.feature.tasks.presentation.components.detail.searchRelatedTaskCandidates
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTasksSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The Related Tasks picker, measured on a restored synthetic dataset.
 *
 * ## What is measured
 *
 * The picker is the one place where a search and a graph walk happen together:
 * it asks the full-text index for candidates, and then asks the dependency
 * validator whether each candidate may be related. The index is bounded by a
 * fetch limit; the validator is bounded by the graph.
 *
 * Four variants per scenario:
 *
 * - **fts** — one index read. What the candidate search costs on its own.
 * - **validator** — the same candidates the picker asks about, walked by the
 *   validator alone. What the cycle check costs on its own.
 * - **full** — the picker's own path: `searchRelatedTaskCandidates` with the
 *   real validator.
 * - **baseline** — the same path with the cycle check replaced by "always
 *   allowed". What the picker would cost without cycle validation.
 *
 * ## What is asserted
 *
 * The counts, not the clock. A clock measures the machine and the machine is
 * shared; a row count does not move with it. The harness asserts that the
 * fetch expansion stays bounded (at most five index reads, never past the
 * fetch cap) and that the picker never offers more rows than its result limit.
 * The latencies are recorded, not asserted.
 *
 * ## How to run it
 *
 * The dataset comes from the shared generator of #3090, and the harness only
 * reads what the generator wrote:
 *
 * ```
 * ./gw :import-cli:run --args="generate --profile 10k --seed 4242 --output build/perf/10k.zip"
 * ./gw :shared:relatedTaskPickerPerf -Pperf.archive=$PWD/build/perf/10k.zip
 * ```
 *
 * A dataset that does not fit a backup archive is generated as a dataset
 * directory (`--format dir`) and measured the same way:
 *
 * ```
 * ./gw :import-cli:run --args="generate --profile 10k --seed 4242 --format dir --output build/perf/10k-dir"
 * ./gw :shared:relatedTaskPickerPerf -Pperf.dataset=$PWD/build/perf/10k-dir
 * ```
 */
fun main(args: Array<String>) {
    val config = PerfConfig.parse(args)
    val report = runBlocking { measure(config) }
    println(report)
}

/** What to measure, and how often. */
private data class PerfConfig(val dataset: PerfDataset, val iterations: Int, val warmup: Int, val owner: UserId) {
    companion object {
        fun parse(args: Array<String>): PerfConfig {
            val values = perfArgs(args)
            return PerfConfig(
                dataset = PerfDataset.parse(values),
                iterations = (values["iterations"] ?: "10").toInt(),
                warmup = (values["warmup"] ?: "3").toInt(),
                owner = UserId(values["owner"] ?: "perf-owner"),
            )
        }
    }
}

/** The database calls the measured path makes. */
private class Counts {
    var edgeReads = 0
    var ftsSearches = 0
    val ftsLimits = mutableListOf<Int>()

    fun reset() {
        edgeReads = 0
        ftsSearches = 0
        ftsLimits.clear()
    }
}

/** The task DAO, counting the reads the validator's walk makes. */
private class CountingTaskDao(private val delegate: TaskDao, private val counts: Counts) : TaskDao by delegate {
    override fun getOrderingEdgeIdsForUser(
        taskId: String,
        userId: String,
    ): Flow<List<String>> {
        counts.edgeReads++
        return delegate.getOrderingEdgeIdsForUser(taskId, userId)
    }
}

/** The index DAO, counting the reads and remembering the limits they asked for. */
private class CountingFtsDao(private val delegate: FtsDao, private val counts: Counts) : FtsDao by delegate {
    override suspend fun searchTaskTitles(
        userId: String,
        query: TextMatchQuery,
        limit: Int,
    ): List<TaskEntity> {
        counts.ftsSearches++
        counts.ftsLimits += limit
        return delegate.searchTaskTitles(userId, query, limit)
    }
}

/** What one run of a variant produced. */
private data class Outcome(val results: Int, val candidates: Int)

/** One measured variant. */
private data class Measurement(
    val scenario: String,
    val variant: String,
    val p50: Duration,
    val p95: Duration,
    val ftsSearches: Int,
    val edgeReads: Int,
    val candidates: Int,
    val results: Int,
    val fetchLimits: List<Int>,
)

/** A task of the restored dataset. */
private data class Row(val id: String, val title: String) {
    /** The number in a generated title — the term a search for that task uses. */
    val number: String = Regex("\\d+").find(title)?.value ?: title
}

/** What the restored dataset holds. */
private data class DatasetStats(
    val tasks: Int,
    val edges: Int,
    val newest: Row,
    val secondNewest: Row,
    /**
     * The task with the largest transitive closure among the recent ones — the
     * dense graph the `dense-graph` scenario walks.
     *
     * The closure is what the validator's BFS traverses, so it is the property
     * the cost of the check depends on. The number of *direct* dependencies of
     * a task is a different property, and the harness does not measure it: a
     * task with many direct dependencies and a small closure is cheap to walk.
     */
    val hub: Row,
    val hubClosure: Int,
)

/**
 * One search the picker can be asked for.
 *
 * [expectsCandidates] and [expectsExpansion] carry what the scenario is for:
 * without them a search that returns nothing would still pass every bound
 * below and report excellent numbers for a workload it never measured.
 */
private data class Scenario(
    val name: String,
    val rawQuery: String,
    val taskId: TaskId,
    val existing: Set<TaskId>,
    val expectsCandidates: Boolean,
    val expectsExpansion: Boolean,
)

private suspend fun measure(config: PerfConfig): String {
    val dataset = config.dataset
    check(dataset.file.exists()) { "no dataset to measure: ${dataset.file.absolutePath}" }
    val runs = RecencyMode.entries.map { mode -> measureMode(mode, dataset, config) }
    return render(dataset, runs)
}

/**
 * One picture of the restored dataset.
 *
 * The dataset is restored again for every picture: the two differ in the
 * `updated_at` column, and a restored database cannot be restored twice.
 */
private data class ModeRun(
    val mode: RecencyMode,
    val stats: DatasetStats,
    val measurements: List<Measurement>,
    val probe: ReadCost,
)

/**
 * The two pictures of a restored dataset the harness measures.
 *
 * A restore writes `updated_at = now` for every row — see the import path in
 * `BackupImporter`. The picker's candidates come out of an
 * `ORDER BY updated_at DESC`, so on restored data that order says nothing: every
 * row looks equally fresh, and the candidates are whatever the write order
 * leaves behind. The dataset carries its own order in `created_at`.
 *
 * Both pictures are measured, because the picker's cost depends on which
 * candidates the order hands it, and only one of the two is what a restore
 * produces. Reporting one of them alone would claim a load the app never has.
 */
private enum class RecencyMode(val label: String) {
    /** The state the restore path leaves: every row carries the same `updated_at`. */
    AsRestored("as restored — every row carries the same `updated_at`"),

    /** The dataset's own order, put back into `updated_at`. */
    DatasetOrder("dataset order put back into `updated_at`"),
}

private suspend fun measureMode(
    mode: RecencyMode,
    dataset: PerfDataset,
    config: PerfConfig,
): ModeRun {
    val fixture = DatabaseTestFixture.onDisk()
    try {
        val db = fixture.database("perf")
        restore(db, fixture, dataset, config.owner)
        if (mode == RecencyMode.DatasetOrder) {
            db.restoreRecencyOrder()
        }

        val counts = Counts()
        val taskDao = CountingTaskDao(db.taskDao(), counts)
        val currentUser = FakeProfileAwareCurrentUser(initialUserId = config.owner)
        val index = SearchTextIndexImpl(CountingFtsDao(db.ftsDao(), counts), taskDao, currentUser)
        val validator = DependencyValidatorImpl(taskDao, currentUser)

        val stats = readStats(db, config.owner)
        val scenarios = buildScenarios(stats, index)
        val measurements = mutableListOf<Measurement>()
        for (scenario in scenarios) {
            measurements += measureScenario(scenario, index, validator, counts, config)
        }
        val probe = probeReadCost(db, taskDao, config.owner)
        return ModeRun(mode, stats, measurements, probe)
    } finally {
        fixture.close()
    }
}

/**
 * Restores the dataset through the app's own restore path.
 *
 * The dataset in, the rows out: [BackupImporter] validates the manifest, migrates
 * the payload and applies the rows exactly as it does for a backup the user
 * picked. Only the reader differs — an archive is read by the archive codec, a
 * dataset directory by [DatasetDirectoryBackupCodec], which does not apply the
 * archive budget, because a dataset directory is not a backup (#3197).
 */
internal suspend fun restore(
    db: AppDatabase,
    fixture: DatabaseTestFixture,
    dataset: PerfDataset,
    owner: UserId,
) {
    val fs = JvmFileSystem()
    val codec: BackupCodec =
        when (dataset.form) {
            PerfDataset.Form.Archive -> JvmBackupCodec()
            PerfDataset.Form.Directory -> DatasetDirectoryBackupCodec(dataset.file)
        }
    val importer =
        BackupImporter(
            log = Logger(loggerConfigInit(CommonWriter()), "related-task-picker-perf"),
            taskDao = db.taskDao(),
            noteDao = db.noteDao(),
            projectDao = db.projectDao(),
            tagDao = db.tagDao(),
            reminderDao = db.reminderDao(),
            projectReminderDao = db.projectReminderDao(),
            checklistDao = db.checklistDao(),
            tagGroupDao = db.tagGroupDao(),
            projectTagGroupDao = db.projectInheritedTagGroupDao(),
            savedSearchDao = db.savedSearchDao(),
            timeEntryDao = db.timeEntryDao(),
            profileDao = db.profileDao(),
            agendaViewDao = db.agendaViewDao(),
            attachmentDao = db.attachmentDao(),
            annotationDao = db.annotationDao(),
            attachmentStorage = AttachmentStorage(fs, File(fixture.directory(), "attachments").absolutePath),
            codec = codec,
            clock = Clock.System,
            createFileSource = JvmFileSourceFactory(),
            tasksSettings = FakeTasksSettingsRepository(),
            database = db,
        )
    val restored =
        importer.import(
            importOptions {
                sourcePath = dataset.path
                targetUserId = owner
            },
        )
    check(restored.isSuccess) { "the restore failed: ${restored.exceptionOrNull()}" }
}

/**
 * Reads what the restored dataset holds, and where its dense part is.
 *
 * The order is the dataset's own: `created_at` grows with the task index, and
 * the restore keeps it. The picker, on the other hand, orders by `updated_at` —
 * see [RecencyMode].
 */
private suspend fun readStats(
    db: AppDatabase,
    owner: UserId,
): DatasetStats {
    val recent =
        db.rows(
            "SELECT id, title FROM tasks WHERE user_id = ? ORDER BY created_at DESC LIMIT 30",
            owner.value,
        )
    check(recent.size >= 2) { "the dataset holds ${recent.size} tasks — too few to measure" }
    val edges = db.scalarInt("SELECT count(*) FROM task_dependencies")
    var hub = recent[1]
    var hubClosure = db.closureSize(hub.id)
    for (row in recent.drop(1)) {
        val closure = db.closureSize(row.id)
        if (closure > hubClosure) {
            hub = row
            hubClosure = closure
        }
    }
    return DatasetStats(
        tasks = db.scalarInt("SELECT count(*) FROM tasks WHERE user_id = ?", owner.value),
        edges = edges,
        newest = recent.first(),
        secondNewest = recent[1],
        hub = hub,
        hubClosure = hubClosure,
    )
}

/**
 * Gives the restored rows back their recency order.
 *
 * A restore writes `updated_at = now` for every row — every restored task looks
 * like it was touched in the same instant. The picker orders its candidates by
 * `updated_at`, so on restored data that order says nothing: the rows come back
 * in whatever order SQLite hands them out for a tie, not in the order they were
 * written and not in the order they were touched.
 *
 * The dataset carries its own order in `created_at` (the generator grows it
 * with the task index, and the restore keeps it), so one statement puts the
 * recency order back.
 *
 * This is *not* the state a restore produces — it is the state a working
 * database is in once its rows have been touched one by one. The harness
 * measures both pictures (see [RecencyMode]): the one the restore leaves, and
 * this one. The flattening itself is a fact about the restore, and the report
 * says so.
 */
private suspend fun AppDatabase.restoreRecencyOrder() {
    useConnection(isReadOnly = false) { transactor ->
        transactor.usePrepared("UPDATE tasks SET updated_at = created_at") { statement ->
            statement.step()
        }
    }
}

/**
 * The searches the picker is measured with.
 *
 * The task being related is always the newest task: nothing depends on it, so
 * the validator's walk cannot find it early and has to walk the whole closure
 * of every candidate — the honest cost of the check.
 *
 * The relations of the third scenario are taken from the rows the index really
 * returns, not from the table: the point of that scenario is to make the fetch
 * expand, and it only expands when the relations are among the rows the first
 * fetch brings.
 */
private suspend fun buildScenarios(
    stats: DatasetStats,
    index: SearchTextIndexImpl,
): List<Scenario> {
    val taskId = TaskId(stats.newest.id)
    val frequent = relatedTaskSearchQuery("task") ?: error("the picker would not search for 'task'")
    val batch = index.recentTaskTitles(frequent, RELATED_TASK_PICKER_FETCH_LIMIT)
    val relations = batch.take(25).map { it.id }.toSet()
    return listOf(
        Scenario(
            name = "rare-term",
            rawQuery = stats.secondNewest.number,
            taskId = taskId,
            existing = emptySet(),
            expectsCandidates = true,
            expectsExpansion = false,
        ),
        Scenario(
            name = "frequent-term",
            rawQuery = "task",
            taskId = taskId,
            existing = emptySet(),
            expectsCandidates = true,
            expectsExpansion = false,
        ),
        Scenario(
            name = "frequent-term-with-relations",
            rawQuery = "task",
            taskId = taskId,
            existing = relations,
            expectsCandidates = true,
            expectsExpansion = true,
        ),
        Scenario(
            name = "dense-graph",
            rawQuery = stats.hub.number,
            taskId = taskId,
            existing = emptySet(),
            expectsCandidates = true,
            expectsExpansion = false,
        ),
    )
}

/** Measures the four variants of one scenario. */
private suspend fun measureScenario(
    scenario: Scenario,
    index: SearchTextIndexImpl,
    validator: DependencyValidatorImpl,
    counts: Counts,
    config: PerfConfig,
): List<Measurement> {
    val isValid: suspend (TaskId) -> Boolean = { id ->
        validator.assertNoCycles(scenario.taskId, setOf(id)).isSuccess
    }
    val asked = collectCandidates(scenario, index, isValid, counts)

    val measurements = mutableListOf<Measurement>()
    for ((name, block) in variantsOf(scenario, index, validator, asked, isValid)) {
        measurements += timeVariant(scenario.name, name, block, counts, config)
    }
    return measurements
}

/**
 * The candidates the picker asks the validator about.
 *
 * The list is collected once, before the variants run, so the validator-only
 * variant walks the same candidates the full variant walks. The bounds of one
 * search are asserted here, where the search happens.
 */
private suspend fun collectCandidates(
    scenario: Scenario,
    index: SearchTextIndexImpl,
    isValid: suspend (TaskId) -> Boolean,
    counts: Counts,
): List<TaskId> {
    // The picker would answer nothing for a query it cannot build — the
    // scenario would measure an empty search and report excellent numbers.
    relatedTaskSearchQuery(scenario.rawQuery)
        ?: error("the picker would not search for '${scenario.rawQuery}'")

    counts.reset()
    val asked = mutableListOf<TaskId>()
    val probe =
        searchRelatedTaskCandidates(
            rawQuery = scenario.rawQuery,
            taskId = scenario.taskId,
            existingRelationIds = scenario.existing,
            searchIndex = index,
            isValidCandidate = { id ->
                asked += id
                isValid(id)
            },
        )
    check(counts.ftsSearches <= MAX_FETCH_STEPS) {
        "one search made ${counts.ftsSearches} index reads; the expansion is bounded at $MAX_FETCH_STEPS"
    }
    check(counts.ftsLimits.all { it <= RELATED_TASK_PICKER_MAX_FETCH_LIMIT }) {
        "the fetch went past the cap: ${counts.ftsLimits}"
    }
    check(probe.size <= RELATED_TASK_PICKER_RESULT_LIMIT) {
        "the picker offered ${probe.size} rows; its limit is $RELATED_TASK_PICKER_RESULT_LIMIT"
    }
    if (scenario.expectsCandidates) {
        check(asked.isNotEmpty()) {
            "the scenario '${scenario.name}' matched nothing — its numbers would measure an empty search"
        }
    }
    if (scenario.expectsExpansion) {
        check(counts.ftsSearches >= 2) {
            "the scenario '${scenario.name}' did not expand: ${counts.ftsSearches} index read(s)"
        }
    }
    return asked
}

/**
 * The four variants of one scenario.
 *
 * They differ only in what they do with a candidate: the FTS variant reads the
 * index alone, the validator variant walks the candidates that were collected
 * beforehand, the full variant runs the picker as the app runs it, and the
 * baseline variant runs it without the cycle check.
 */
private fun variantsOf(
    scenario: Scenario,
    index: SearchTextIndexImpl,
    validator: DependencyValidatorImpl,
    asked: List<TaskId>,
    isValid: suspend (TaskId) -> Boolean,
): List<Pair<String, suspend () -> Outcome>> {
    val query =
        relatedTaskSearchQuery(scenario.rawQuery)
            ?: error("the picker would not search for '${scenario.rawQuery}'")
    val ftsVariant: suspend () -> Outcome = {
        Outcome(index.recentTaskTitles(query, RELATED_TASK_PICKER_FETCH_LIMIT).size, 0)
    }
    val validatorVariant: suspend () -> Outcome = {
        var checked = 0
        for (id in asked) {
            validator.assertNoCycles(scenario.taskId, setOf(id))
            checked++
        }
        Outcome(0, checked)
    }
    val fullVariant: suspend () -> Outcome = {
        var checked = 0
        val rows =
            searchRelatedTaskCandidates(
                rawQuery = scenario.rawQuery,
                taskId = scenario.taskId,
                existingRelationIds = scenario.existing,
                searchIndex = index,
                isValidCandidate = { id ->
                    checked++
                    isValid(id)
                },
            )
        Outcome(rows.size, checked)
    }
    val baselineVariant: suspend () -> Outcome = {
        val rows =
            searchRelatedTaskCandidates(
                rawQuery = scenario.rawQuery,
                taskId = scenario.taskId,
                existingRelationIds = scenario.existing,
                searchIndex = index,
                isValidCandidate = { true },
            )
        Outcome(rows.size, 0)
    }

    return listOf(
        "fts" to ftsVariant,
        "validator" to validatorVariant,
        "full" to fullVariant,
        "baseline" to baselineVariant,
    )
}

/** Runs one variant: a warmup, then the measured iterations. */
private suspend fun timeVariant(
    scenario: String,
    variant: String,
    block: suspend () -> Outcome,
    counts: Counts,
    config: PerfConfig,
): Measurement {
    for (index in 0 until config.warmup) {
        block()
    }
    val durations = mutableListOf<Duration>()
    var outcome = Outcome(0, 0)
    for (index in 0 until config.iterations) {
        counts.reset()
        val mark = TimeSource.Monotonic.markNow()
        outcome = block()
        durations += mark.elapsedNow()
    }
    return Measurement(
        scenario = scenario,
        variant = variant,
        p50 = nearestRank(durations, 0.5),
        p95 = nearestRank(durations, 0.95),
        ftsSearches = counts.ftsSearches,
        edgeReads = counts.edgeReads,
        candidates = outcome.candidates,
        results = outcome.results,
        fetchLimits = counts.ftsLimits.toList(),
    )
}

/** The rows of a query, two columns of text. */
private suspend fun AppDatabase.rows(
    sql: String,
    arg: String?,
): List<Row> =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            if (arg != null) statement.bindText(1, arg)
            buildList {
                while (statement.step()) {
                    add(Row(statement.getText(0), statement.getText(1)))
                }
            }
        }
    }

/** How many tasks the given task can reach by following dependencies. */
private suspend fun AppDatabase.closureSize(taskId: String): Int =
    scalarInt(
        """
        WITH RECURSIVE reach(node) AS (
            SELECT depends_on_task_id FROM task_dependencies WHERE task_id = ?
            UNION
            SELECT d.depends_on_task_id FROM reach r JOIN task_dependencies d ON d.task_id = r.node
        )
        SELECT count(*) FROM reach
        """.trimIndent(),
        taskId,
    )

/** What one ordering-edge read costs, and where that cost goes. */
private data class ReadCost(val daoMillis: Double, val sqlMillis: Double, val reads: Int)

/**
 * Prices one ordering-edge read twice: once through the DAO the validator
 * calls, once as the same SQL straight at the database.
 *
 * The two numbers answer the question the report has to answer: when a walk
 * over many nodes is slow, is the SQL slow, or is the call around it?
 */
private suspend fun probeReadCost(
    db: AppDatabase,
    taskDao: TaskDao,
    owner: UserId,
): ReadCost {
    val ids =
        db
            .rows(
                "SELECT task_id, '' FROM task_dependencies LIMIT 100",
                null,
            ).map { it.id }
    if (ids.isEmpty()) return ReadCost(0.0, 0.0, 0)
    val rounds = 3

    val daoMark = TimeSource.Monotonic.markNow()
    repeat(rounds) {
        for (id in ids) {
            taskDao.getOrderingEdgeIdsForUser(id, owner.value).first()
        }
    }
    val daoMillis = daoMark.elapsedNow().inWholeMicroseconds / 1000.0 / (rounds * ids.size)

    val sqlMark = TimeSource.Monotonic.markNow()
    repeat(rounds) {
        for (id in ids) {
            db.readOrderingEdges(id, owner.value)
        }
    }
    val sqlMillis = sqlMark.elapsedNow().inWholeMicroseconds / 1000.0 / (rounds * ids.size)
    return ReadCost(daoMillis, sqlMillis, ids.size)
}

/** The same read the DAO makes, straight at the database. */
private suspend fun AppDatabase.readOrderingEdges(
    taskId: String,
    userId: String,
): Int =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(
            """
            SELECT d.depends_on_task_id FROM task_dependencies d
            JOIN tasks t ON t.id = d.task_id
            WHERE d.task_id = ? AND t.user_id = ?
            """.trimIndent(),
        ) { statement ->
            statement.bindText(1, taskId)
            statement.bindText(2, userId)
            var count = 0
            while (statement.step()) count++
            count
        }
    }

/** The report. */
private fun render(
    dataset: PerfDataset,
    runs: List<ModeRun>,
): String =
    buildString {
        appendLine("## Related Tasks picker — measurement")
        appendLine()
        appendLine(
            "- dataset: `${dataset.file.absolutePath}` " +
                "(${dataset.sizeBytes() / 1024} KiB, ${dataset.form.name.lowercase()})",
        )
        val stats = runs.first().stats
        appendLine("- tasks: ${stats.tasks}, dependency edges: ${stats.edges}")
        appendLine("- dense task: ${stats.hub.title} (closure ${stats.hubClosure} tasks)")
        for (run in runs) {
            appendLine()
            appendLine("### ${run.mode.label}")
            appendLine()
            appendLine(
                "- one ordering-edge read: ${"%.2f".format(run.probe.daoMillis)} ms through the DAO, " +
                    "${"%.2f".format(run.probe.sqlMillis)} ms as raw SQL (${run.probe.reads} ids)",
            )
            appendLine()
            appendLine(
                "| scenario | variant | p50 ms | p95 ms | fts calls | edge reads | candidates | results | fetch limits |",
            )
            appendLine("|---|---|---|---|---|---|---|---|---|")
            for (m in run.measurements) {
                appendLine(
                    "| ${m.scenario} | ${m.variant} | ${millis(m.p50)} | ${millis(m.p95)} | " +
                        "${m.ftsSearches} | ${m.edgeReads} | ${m.candidates} | ${m.results} | ${m.fetchLimits} |",
                )
            }
        }
    }

/** How many index reads the picker's fetch expansion may make: 50, 100, 200, 400, 500. */
private const val MAX_FETCH_STEPS = 5
