@file:Suppress("NoRunBlocking")

package com.singularity.todo.feature.search.performance

import androidx.sqlite.SQLiteStatement
import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.loggerConfigInit
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.backup.BackupImporter
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.backup.importOptions
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.FtsMatchQuery
import com.singularity.todo.core.files.JvmFileSourceFactory
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.search.FreeTextRule
import com.singularity.todo.core.search.SearchMode
import com.singularity.todo.core.search.TextMatchPlan
import com.singularity.todo.test.fakes.FakeTasksSettingsRepository
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * FTS task-title search ordering cost, measured on a restored synthetic dataset.
 *
 * ## Why this exists
 *
 * `FtsDao.searchTaskTitles` joined `tasks_fts` to `tasks` and ended with
 * `ORDER BY t.updated_at DESC LIMIT :limit`. On a broad match the FTS hit set
 * can be most of the table; the question is whether SQLite pays a material sort
 * for that hit set at supported database sizes.
 *
 * This harness isolates that cost. It does not measure the Related Tasks picker,
 * the search screen, or any in-memory rule evaluation — only the SQL path.
 *
 * The `current` variant is the statement the DAO ran before the change this
 * measurement decided, and `two_phase_tiebreak` is the statement it runs now.
 * Both are measured here as they are, so that the difference between them is a
 * difference between two statements and not between two versions of a report.
 *
 * ## What is measured
 *
 * A ladder of statements, each one the previous one plus one thing, so that the
 * price of each step is the difference between two rows of the table:
 *
 * - `fts_match_count` — `count(*)` over the FTS table alone: the matching cost,
 *   with no join and no rows materialized.
 * - `match_count` — the same count over the join with the ownership filter: the
 *   cost of finding the rows that belong to the profile.
 * - `match_limit_no_order` — the joined rows with the production `LIMIT`, but
 *   without the ordering.
 * - `match_order` — the same statement with `ORDER BY updated_at DESC`: the
 *   production ordering, on a plan the harness controls.
 * - `current_ids` — the production statement with the id alone: the sort, and
 *   nothing else.
 * - `current_no_offsets` — the production statement with the match offsets
 *   replaced by a constant.
 * - `current_offsets_only` — the production statement with the task row
 *   replaced by the id.
 * - `current` — the production statement verbatim: the whole task row and the
 *   match offsets of every matched task, carried through the sort.
 *
 * ## The join order has to be pinned, or the comparison measures the wrong thing
 *
 * The production statement ends with an `ORDER BY`, and SQLite answers it by
 * scanning the FTS table first and looking each row up by its `rowid` — the
 * shape that matches most rows first. Take the `ORDER BY` away and the planner
 * is free again, and it chooses the *other* shape: it scans the tasks of the
 * profile by the `user_id` index and evaluates the FTS match once per task. That
 * is a different query, not the same query without a sort — it answers the same
 * question, but it evaluates the match N times instead of once.
 *
 * So `match_limit_no_order` and `match_order` are stated with a `CROSS JOIN`,
 * which pins the join order (`tasks_fts` outer, `tasks` inner) and leaves the
 * only difference between them the sort. `current` is kept verbatim, and the
 * plan of `current_no_order` — the production statement without its `ORDER BY`,
 * recorded as a plan and never executed — shows the shape the planner picks when
 * it is allowed to.
 *
 * ## The candidates
 *
 * The ladder prices the current path. Three more variants price the ways the
 * cost could be taken away, so that the conclusion can name a smallest
 * improvement instead of only a problem:
 *
 * - `two_phase` — order by the ids alone and read the rows of the survivors in
 *   a second statement. The sorter carries an id instead of a whole task row,
 *   and the rows of the survivors are read once, not once per matched row.
 *   Measured against the schema as it is.
 * - `two_phase_tiebreak` — the same two-phase, with the tiebreaker the DAO
 *   adds to the order. This is the statement the DAO runs now, measured as it
 *   ships; the difference between it and `two_phase` is the price of the
 *   tiebreaker.
 * - `subquery_candidate` — the same idea in one statement: the ordering moves
 *   into a subquery that sorts the ids alone, and the outer query reads the
 *   rows of the survivors. The columns and the order are the ones of the
 *   production statement.
 * - `two_phase_offsets` — the two-phase that keeps the match offsets: the rows
 *   of the survivors are read by their id and the index is joined again to get
 *   the offsets back. FTS4 can report the position of a match only while it is
 *   answering a `MATCH`, so this candidate has to go through the whole match
 *   set a second time — and the measurement says what that costs.
 * - `current_with_index` — the production statement, verbatim, on a schema that
 *   carries `INDEX tasks(user_id, updated_at DESC)`. It says whether the index
 *   alone is enough, or whether the statement has to be rewritten as well.
 * - `index_candidate` — the production statement rewritten so that the FTS hit
 *   set arrives as a subquery (`t.rowid IN (SELECT docid FROM tasks_fts WHERE
 *   tasks_fts MATCH ?)`), on the same schema with the index. It says whether
 *   the index plus the rewrite removes the sort.
 *
 * The index is created in the measurement database only. It is not a proposal
 * for the production schema: it is the cheapest way to find out what the
 * planner does with it.
 *
 * ## What is asserted
 *
 * Nothing about wall-clock time. The harness asserts that the counts agree where
 * they must (the FTS rows and the joined rows are the same rows), that every
 * measured variant returns rows, and that the two pinned variants really did get
 * the same plan apart from the sort. Latencies are recorded, not asserted.
 *
 * ## Datasets
 *
 * Deterministic datasets from #3090 (`:import-cli generate`). The same dataset
 * is measured in three pictures, and the pictures differ in one thing only — the
 * `updated_at` values:
 *
 * - as restored — every task carries the same `updated_at`: the state the
 *   restore path produces.
 * - dataset order — `updated_at = created_at`, the values of a working
 *   database, which grow along the rowid order of the table.
 * - newest first — `updated_at = -created_at`, the mirror of the dataset order:
 *   the timestamps fall along the rowid order of the table.
 *
 * The third picture is a probe. The sorter only has to keep the rows that can
 * still be among the first `LIMIT` of the order; a row that is already beaten
 * by `LIMIT` better rows is dropped before it is ever written to the sorter.
 * When the timestamps grow along the scan order, every scanned row is a better
 * candidate than the last one, and every matched row has to be written to the
 * sorter. When they fall along the scan order, the first rows are already the
 * best ones and everything after them is dropped at once. The two pictures
 * differ in the timestamps and in nothing else, so the difference between them
 * prices the sort and nothing else.
 *
 * ## How to run
 *
 * ```
 * ./gw :import-cli:run --args="generate --profile 10k --seed 4242 --output $PWD/build/perf/10k.zip"
 * ./gw :shared:ftsOrderingPerf -Pperf.archive=$PWD/build/perf/10k.zip
 * ```
 */
fun main(args: Array<String>) {
    val config = FtsPerfConfig.parse(args)
    val report = runBlocking { measure(config) }
    println(report)
}

private data class FtsPerfConfig(val archive: String, val iterations: Int, val warmup: Int, val owner: UserId) {
    companion object {
        fun parse(args: Array<String>): FtsPerfConfig {
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
            return FtsPerfConfig(
                archive = archive,
                iterations = (values["iterations"] ?: "10").toInt(),
                warmup = (values["warmup"] ?: "3").toInt(),
                owner = UserId(values["owner"] ?: "perf-owner"),
            )
        }
    }
}

private enum class RecencyMode(val label: String) {
    AsRestored("as restored (equal updated_at — restore state)"),
    DatasetOrder("dataset order (updated_at = created_at — working state)"),
    NewestFirst("newest first (updated_at = -created_at — sort-order probe)"),
}

private data class Scenario(val name: String, val rawQuery: String, val limit: Int, val expectBroad: Boolean)

/**
 * When a variant is measured.
 *
 * The baseline variants are measured on the schema as the application ships it.
 * The candidate variants are measured either on the same schema (`two_phase`)
 * or on a schema that carries an extra index (`current_with_index`,
 * `index_candidate`) — and the index is created after the baseline is measured,
 * so the two never share a database state.
 */
private enum class Phase(val label: String) {
    Baseline("baseline (schema as shipped)"),
    Candidate("candidate"),
}

/**
 * The statements the ladder is made of.
 *
 * [parameters] is how many placeholders the statement has: the FTS count has
 * one, the joined count two, the statements that fetch rows three, and the
 * rewritten candidate four (the match expression is used twice — once in the
 * subquery, once for the offsets). A statement is bound by index and SQLite
 * refuses a binding past its last placeholder, so the count of the statement is
 * part of its identity here.
 *
 * [timed] says whether the statement is executed and priced. `current_no_order`
 * is not: it is the production statement without its ordering, which the planner
 * answers with a shape that costs one FTS evaluation per task — a shape that
 * has to be seen to be believed, and that must not be paid for on every run.
 */
private enum class Variant(
    val label: String,
    val sql: String,
    val parameters: Int,
    val timed: Boolean,
    val phase: Phase,
    val planPhase: Phase,
) {
    FtsMatchCount("fts_match_count", FTS_COUNT_SQL, 1, true, Phase.Baseline, Phase.Baseline),
    MatchCount("match_count", MATCH_COUNT_SQL, 2, true, Phase.Baseline, Phase.Baseline),
    MatchLimitNoOrder("match_limit_no_order", MATCH_NO_ORDER_SQL, 3, true, Phase.Baseline, Phase.Baseline),
    MatchOrder("match_order", MATCH_ORDER_SQL, 3, true, Phase.Baseline, Phase.Baseline),
    CurrentIds("current_ids", CURRENT_IDS_SQL, 3, true, Phase.Baseline, Phase.Baseline),
    CurrentNoOffsets("current_no_offsets", CURRENT_NO_OFFSETS_SQL, 3, true, Phase.Baseline, Phase.Baseline),
    CurrentOffsetsOnly("current_offsets_only", CURRENT_OFFSETS_ONLY_SQL, 3, true, Phase.Baseline, Phase.Baseline),
    Current("current", CURRENT_SQL, 3, true, Phase.Baseline, Phase.Baseline),
    CurrentNoOrder("current_no_order", CURRENT_NO_ORDER_SQL, 3, false, Phase.Baseline, Phase.Baseline),
    TwoPhase("two_phase", TWO_PHASE_IDS_SQL, 3, true, Phase.Baseline, Phase.Baseline),
    TwoPhaseTiebreak("two_phase_tiebreak", TWO_PHASE_TIEBREAK_IDS_SQL, 3, true, Phase.Baseline, Phase.Baseline),
    TwoPhaseOffsets("two_phase_offsets", TWO_PHASE_IDS_SQL, 3, true, Phase.Baseline, Phase.Baseline),
    SubqueryCandidate("subquery_candidate", SUBQUERY_CANDIDATE_SQL, 6, true, Phase.Baseline, Phase.Baseline),
    CurrentWithIndex("current_with_index", CURRENT_SQL, 3, true, Phase.Candidate, Phase.Candidate),
    IndexCandidate("index_candidate", INDEX_CANDIDATE_SQL, 4, true, Phase.Candidate, Phase.Candidate),
}

private data class VariantResult(
    val scenario: String,
    val variant: String,
    val p50: Duration,
    val p95: Duration,
    val rows: Int,
    val matchCount: Int,
)

private data class PlanRecord(val scenario: String, val variant: String, val steps: List<String>) {
    val usesTempBTree: Boolean = steps.any { "USE TEMP B-TREE FOR ORDER BY" in it.uppercase(Locale.ROOT) }
}

private data class ModeRun(
    val mode: RecencyMode,
    val taskCount: Int,
    val plans: List<PlanRecord>,
    val measurements: List<VariantResult>,
)

private const val DEFAULT_LIMIT = 20

private suspend fun measure(config: FtsPerfConfig): String {
    val archive = File(config.archive)
    check(archive.isFile) { "no dataset to measure: ${archive.absolutePath}" }
    val runs = RecencyMode.entries.map { mode -> measureMode(mode, archive, config) }
    return render(archive, config, runs)
}

private suspend fun measureMode(
    mode: RecencyMode,
    archive: File,
    config: FtsPerfConfig,
): ModeRun {
    val fixture = DatabaseTestFixture.onDisk()
    try {
        val db = fixture.database("perf")
        restore(db, fixture, archive.absolutePath, config.owner)
        if (mode != RecencyMode.AsRestored) {
            db.restoreRecencyOrder(mode)
        }
        val taskCount = db.scalarInt("SELECT count(*) FROM tasks WHERE user_id = ?", config.owner.value)
        val scenarios = buildScenarios(db, config.owner)
        val plans = mutableListOf<PlanRecord>()
        val measurements = mutableListOf<VariantResult>()

        // The baseline: the schema as the application ships it.
        val matches =
            scenarios.associateWith { scenario ->
                ftsMatchExpression(scenario.rawQuery)
                    ?: error("scenario '${scenario.name}' produced no FTS expression for '${scenario.rawQuery}'")
            }
        val matchCounts = mutableMapOf<String, Int>()
        for (scenario in scenarios) {
            val match = matches.getValue(scenario)
            val matchCount = db.countValue(MATCH_COUNT_SQL, match, config.owner.value, scenario.limit, 2)
            check(matchCount > 0) {
                "scenario '${scenario.name}' matched nothing — it would not measure FTS ordering"
            }
            if (scenario.expectBroad) {
                check(matchCount >= taskCount / 2) {
                    "scenario '${scenario.name}' expected a broad match but matched $matchCount of $taskCount"
                }
            }
            matchCounts[scenario.name] = matchCount
            plans += planRecords(scenario, match, config.owner.value, db, Phase.Baseline)
            checkSamePlan(plans.filter { it.scenario == scenario.name })
        }
        measurements += measureJobs(jobs(scenarios, matches, matchCounts, Phase.Baseline), config, db)

        // The candidates. The index is created here, after the baseline is
        // measured, so that the baseline and the candidates never share a
        // database state.
        db.createUserUpdatedIndex()
        for (scenario in scenarios) {
            plans += planRecords(scenario, matches.getValue(scenario), config.owner.value, db, Phase.Candidate)
        }
        measurements += measureJobs(jobs(scenarios, matches, matchCounts, Phase.Candidate), config, db)
        return ModeRun(mode, taskCount, plans, measurements)
    } finally {
        fixture.close()
    }
}

private suspend fun buildScenarios(
    db: AppDatabase,
    owner: UserId,
): List<Scenario> {
    val rareTitle =
        db.scalarText(
            "SELECT title FROM tasks WHERE user_id = ? ORDER BY created_at DESC LIMIT 1 OFFSET 5",
            owner.value,
        )
    val rareNumber = Regex("\\d+").find(rareTitle)?.value ?: error("generated task title has no number: $rareTitle")
    return listOf(
        Scenario(name = "rare-number", rawQuery = rareNumber, limit = DEFAULT_LIMIT, expectBroad = false),
        Scenario(name = "frequent-task", rawQuery = "Task", limit = DEFAULT_LIMIT, expectBroad = true),
        Scenario(name = "frequent-synthetic", rawQuery = "Synthetic", limit = DEFAULT_LIMIT, expectBroad = true),
    )
}

private suspend fun ftsMatchExpression(rawQuery: String): String? {
    val rule = FreeTextRule.singleTerm(rawQuery)
    return when (rule.plan(SearchMode.Word)) {
        is TextMatchPlan.Narrowed -> FtsMatchQuery.expression(rule.indexQuery(SearchMode.Word))
        TextMatchPlan.BroadScan -> null
    }
}

private suspend fun planRecords(
    scenario: Scenario,
    match: String,
    userId: String,
    db: AppDatabase,
    phase: Phase,
): List<PlanRecord> =
    Variant.entries
        .filter { it.planPhase == phase }
        .map { variant ->
            val steps =
                when (variant) {
                    // The two-phase candidates are two statements, and both of
                    // them have to be on the record.
                    Variant.TwoPhase, Variant.TwoPhaseTiebreak -> {
                        listOf("order the ids:") +
                            db.queryPlan(variant, match, userId, scenario.limit) +
                            listOf("read the rows:") +
                            db.planOf("EXPLAIN QUERY PLAN ${rowsByIdsSql(scenario.limit)}") { statement ->
                                statement.bindText(1, "id")
                            }
                    }

                    Variant.TwoPhaseOffsets -> {
                        listOf("order the ids:") +
                            db.queryPlan(variant, match, userId, scenario.limit) +
                            listOf("read the rows with the offsets:") +
                            db.planOf("EXPLAIN QUERY PLAN ${rowsWithOffsetsSql(scenario.limit)}") { statement ->
                                statement.bindText(1, match)
                                statement.bindText(2, userId)
                                statement.bindText(3, "id")
                            }
                    }

                    else -> {
                        db.queryPlan(variant, match, userId, scenario.limit)
                    }
                }
            PlanRecord(scenario.name, variant.label, steps)
        }

/**
 * The jobs of one phase: every variant, in every scenario.
 *
 * The match expression of a scenario is computed once and shared by the jobs of
 * that scenario, so every variant of a scenario runs against the same
 * expression.
 */
private fun jobs(
    scenarios: List<Scenario>,
    matches: Map<Scenario, String>,
    matchCounts: Map<String, Int>,
    phase: Phase,
): List<Job> =
    scenarios.flatMap { scenario ->
        Variant.entries
            .filter { it.timed && it.phase == phase }
            .map { variant ->
                Job(scenario, variant, matches.getValue(scenario), matchCounts.getValue(scenario.name))
            }
    }

/**
 * The two pinned statements have to differ by the sort and by nothing else.
 *
 * `match_limit_no_order` and `match_order` are the same statement with and
 * without the ordering; if their plans were not the same, the difference
 * between their latencies would price a plan change and not a sort — and the
 * whole measurement would answer a different question than the one it claims.
 * The plan of the ordered statement carries one extra step — the temporary
 * B-tree the sort uses — and the rest of the steps have to match.
 */
private fun checkSamePlan(plans: List<PlanRecord>) {
    val unordered = plans.first { it.variant == Variant.MatchLimitNoOrder.label }
    val ordered = plans.first { it.variant == Variant.MatchOrder.label }
    val sortStep = ordered.steps.filter { "USE TEMP B-TREE" in it.uppercase(Locale.ROOT) }
    val orderedWithoutSort = ordered.steps.filterNot { "USE TEMP B-TREE" in it.uppercase(Locale.ROOT) }
    check(unordered.steps == orderedWithoutSort) {
        "the pinned variants did not get the same plan: " +
            "no order ${unordered.steps}, order ${ordered.steps}"
    }
    check(sortStep.size == 1) {
        "the ordered variant did not sort through a temporary B-tree: ${ordered.steps}"
    }
}

/**
 * Prices every job, with the jobs interleaved.
 *
 * The machine is shared. A sweep that runs all the iterations of one variant
 * and only then moves to the next one measures the machine as much as it
 * measures the query: a neighbour's build that lands in the middle of the
 * sweep lands entirely on whichever variants happened to run then, and the
 * comparison between the variants stops being a comparison.
 *
 * So the rounds are interleaved: every round runs every job once, and the
 * order rotates by one job per round. A machine that gets busy in the middle
 * of the sweep slows every job down the same way, and the difference between
 * two variants is still the difference between two variants.
 */
private suspend fun measureJobs(
    jobs: List<Job>,
    config: FtsPerfConfig,
    db: AppDatabase,
): List<VariantResult> {
    val samples = jobs.associateWith { mutableListOf<Duration>() }.toMutableMap()
    val rows = mutableMapOf<Job, Int>()

    // The warm-up runs every job once per round, so that every job starts from
    // the same kind of state: the pages of the table are in the cache.
    repeat(config.warmup) {
        jobs.forEach { job ->
            rows[job] = runVariant(job.variant, job.match, config.owner.value, job.scenario.limit, db)
        }
    }

    repeat(config.iterations) { round ->
        val offset = round % jobs.size
        val ordered = jobs.drop(offset) + jobs.take(offset)
        ordered.forEach { job ->
            val mark = TimeSource.Monotonic.markNow()
            val returned = runVariant(job.variant, job.match, config.owner.value, job.scenario.limit, db)
            samples.getValue(job) += mark.elapsedNow()
            rows[job] = returned
        }
    }

    return jobs.map { job ->
        val durations = samples.getValue(job)
        val returned = rows.getValue(job)
        checkJob(job, returned)
        VariantResult(
            scenario = job.scenario.name,
            variant = job.variant.label,
            p50 = percentile(durations, 0.5),
            p95 = percentile(durations, 0.95),
            rows = returned,
            matchCount = job.matchCount,
        )
    }
}

/**
 * Checks that a job measured what it was for.
 *
 * The counts are checked against the match count the scenario was built with,
 * and the row statements are checked for the rows they promise. The latencies
 * are recorded, never asserted: a clock measures the machine.
 */
private fun checkJob(
    job: Job,
    rows: Int,
) {
    when (job.variant) {
        Variant.FtsMatchCount -> {
            check(rows >= job.matchCount) {
                "the FTS index holds $rows row(s), the joined count is ${job.matchCount} — the index cannot be smaller"
            }
        }

        Variant.MatchCount -> {
            check(rows == job.matchCount) {
                "count variant disagreed: $rows != ${job.matchCount}"
            }
        }

        else -> {
            check(rows > 0) { "variant ${job.variant.label} for ${job.scenario.name} returned no rows" }
            check(rows <= job.scenario.limit) {
                "variant ${job.variant.label} returned $rows > limit ${job.scenario.limit}"
            }
        }
    }
}

/** One measurement job: a variant, in a scenario, against one match expression. */
private data class Job(val scenario: Scenario, val variant: Variant, val match: String, val matchCount: Int)

private suspend fun runVariant(
    variant: Variant,
    match: String,
    userId: String,
    limit: Int,
    db: AppDatabase,
): Int =
    when (variant) {
        Variant.FtsMatchCount, Variant.MatchCount -> {
            db.countValue(variant.sql, match, userId, limit, variant.parameters)
        }

        Variant.TwoPhase -> {
            db.runTwoPhase(TWO_PHASE_IDS_SQL, match, userId, limit)
        }

        Variant.TwoPhaseTiebreak -> {
            db.runTwoPhase(TWO_PHASE_TIEBREAK_IDS_SQL, match, userId, limit)
        }

        Variant.TwoPhaseOffsets -> {
            db.runTwoPhaseWithOffsets(match, userId, limit)
        }

        else -> {
            db.rowCount(variant.sql, match, userId, limit, variant.parameters)
        }
    }

/**
 * The single number a counting statement answers.
 *
 * The statement is bound by index, and SQLite refuses a binding past its last
 * placeholder — so the count of the placeholders decides what is bound.
 */
private suspend fun AppDatabase.countValue(
    sql: String,
    match: String,
    userId: String,
    limit: Int,
    parameters: Int,
): Int =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            bindVariant(statement, parameters, match, userId, limit)
            check(statement.step()) { "count query returned no row" }
            statement.getLong(0).toInt()
        }
    }

/** How many rows a statement returns. */
private suspend fun AppDatabase.rowCount(
    sql: String,
    match: String,
    userId: String,
    limit: Int,
    parameters: Int,
): Int =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            bindVariant(statement, parameters, match, userId, limit)
            var count = 0
            while (statement.step()) count++
            count
        }
    }

/**
 * The plan of a statement, one line per step.
 */
private suspend fun AppDatabase.queryPlan(
    variant: Variant,
    match: String,
    userId: String,
    limit: Int,
): List<String> =
    planOf("EXPLAIN QUERY PLAN ${variant.sql}") { statement ->
        bindVariant(statement, variant.parameters, match, userId, limit)
    }

/**
 * The plan of an arbitrary statement, one line per step.
 */
private suspend fun AppDatabase.planOf(
    sql: String,
    bind: (SQLiteStatement) -> Unit,
): List<String> =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            bind(statement)
            buildList {
                while (statement.step()) {
                    add(statement.getText(3))
                }
            }
        }
    }

/**
 * Binds the placeholders of a statement, in order.
 *
 * The statements differ in how many placeholders they have and in what they
 * stand for; the count decides what is bound, and the rewritten candidate uses
 * the match expression twice.
 */
private fun bindVariant(
    statement: SQLiteStatement,
    parameters: Int,
    match: String,
    userId: String,
    limit: Int,
) {
    statement.bindText(1, match)
    when (parameters) {
        // The rewritten candidate: the match expression in the subquery and in
        // the offsets of the outer query.
        4 -> {
            statement.bindText(2, match)
            statement.bindText(3, userId)
            statement.bindLong(4, limit.toLong())
        }

        // The single-statement candidate: the subquery that orders the ids and
        // the outer query that reads their rows.
        6 -> {
            statement.bindText(2, userId)
            statement.bindLong(3, limit.toLong())
            statement.bindText(4, match)
            statement.bindText(5, userId)
            statement.bindLong(6, limit.toLong())
        }

        else -> {
            if (parameters > 1) statement.bindText(2, userId)
            if (parameters > 2) statement.bindLong(3, limit.toLong())
        }
    }
}

/**
 * The two-phase candidate: order by the ids, then read the rows of the
 * survivors.
 *
 * The first statement sorts ids alone, so the sorter carries an id per matched
 * row instead of a whole task row with its match offsets. The second statement
 * reads the rows of the survivors by their id — at most `LIMIT` of them.
 *
 * The second statement does not join the index again, and it does not ask for
 * the match offsets. The picker never reads them: `SearchTextIndex
 * .recentTaskTitles` maps every row to its task and drops the offsets, so the
 * offsets are carried through the sorter for nothing. That is why the two-phase
 * candidate is the cheapest of the candidates and also the one that changes the
 * contract of the DAO.
 */
private suspend fun AppDatabase.runTwoPhase(
    sql: String,
    match: String,
    userId: String,
    limit: Int,
): Int {
    val ids =
        useConnection(isReadOnly = true) { transactor ->
            transactor.usePrepared(sql) { statement ->
                statement.bindText(1, match)
                statement.bindText(2, userId)
                statement.bindLong(3, limit.toLong())
                buildList {
                    while (statement.step()) {
                        add(statement.getText(0))
                    }
                }
            }
        }
    if (ids.isEmpty()) return 0
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(rowsByIdsSql(ids.size)) { statement ->
            ids.forEachIndexed { index, id -> statement.bindText(index + 1, id) }
            var count = 0
            while (statement.step()) count++
            count
        }
    }
    return ids.size
}

/** The second statement of the two-phase candidate, with room for the ids. */
private fun rowsByIdsSql(ids: Int): String =
    """
    SELECT t.* FROM tasks t
    WHERE t.id IN (${List(ids) { "?" }.joinToString(", ")})
    """

/**
 * The second statement of the two-phase candidate that keeps the match offsets.
 *
 * The rows are read by their id, and the index is joined again to get the
 * offsets back — the offsets are what the title door used to return, so this is
 * the candidate that does not change the contract of the door.
 */
private fun rowsWithOffsetsSql(ids: Int): String =
    """
    SELECT t.*, offsets(tasks_fts) AS match_offsets FROM tasks t
    JOIN tasks_fts ON t.rowid = tasks_fts.docid
    WHERE tasks_fts MATCH ?
      AND t.user_id = ?
      AND t.archived_at IS NULL
      AND t.id IN (${List(ids) { "?" }.joinToString(", ")})
    """

/**
 * The two-phase candidate that keeps the match offsets.
 *
 * The first statement is the same as the plain two-phase candidate: it sorts
 * the ids alone. The second statement reads the rows of the survivors by their
 * id and asks the index for their offsets again.
 */
private suspend fun AppDatabase.runTwoPhaseWithOffsets(
    match: String,
    userId: String,
    limit: Int,
): Int {
    val ids =
        useConnection(isReadOnly = true) { transactor ->
            transactor.usePrepared(TWO_PHASE_IDS_SQL) { statement ->
                statement.bindText(1, match)
                statement.bindText(2, userId)
                statement.bindLong(3, limit.toLong())
                buildList {
                    while (statement.step()) {
                        add(statement.getText(0))
                    }
                }
            }
        }
    if (ids.isEmpty()) return 0
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(rowsWithOffsetsSql(ids.size)) { statement ->
            statement.bindText(1, match)
            statement.bindText(2, userId)
            ids.forEachIndexed { index, id -> statement.bindText(index + 3, id) }
            var count = 0
            while (statement.step()) count++
            count
        }
    }
    return ids.size
}

/**
 * Adds the index the candidates are measured against.
 *
 * The index is created in the measurement database only: it exists to find out
 * what the planner does with such an index, and a proposal for the production
 * schema needs a migration and a decision of its own.
 */
private suspend fun AppDatabase.createUserUpdatedIndex() {
    useConnection(isReadOnly = false) { transactor ->
        transactor.usePrepared(
            "CREATE INDEX IF NOT EXISTS idx_tasks_user_updated_at ON tasks(user_id, updated_at DESC)",
        ) { statement ->
            statement.step()
        }
    }
}

private suspend fun AppDatabase.scalarInt(
    sql: String,
    arg: String,
): Int =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            statement.bindText(1, arg)
            check(statement.step()) { "query returned no row: $sql" }
            statement.getLong(0).toInt()
        }
    }

private suspend fun AppDatabase.scalarText(
    sql: String,
    arg: String,
): String =
    useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(sql) { statement ->
            statement.bindText(1, arg)
            check(statement.step()) { "query returned no row: $sql" }
            statement.getText(0)
        }
    }

/**
 * Puts the dataset's own order back into `updated_at`.
 *
 * The update is followed by a compaction of the table and a rebuild of the
 * full-text index, and both are there for the same reason: without them the two
 * pictures would differ in more than the timestamps.
 *
 * An update of every task rewrites every row, and the rewritten table is
 * fragmented — the rows a rowid lookup has to read now sit in pages that were
 * written twice. `tasks_fts` is an external-content FTS4 table, and Room keeps
 * it in step with triggers on `tasks`, so the same update leaves the index
 * carrying the deleted entries of the rewrite as well.
 *
 * `VACUUM` puts the table back into the shape a fresh restore leaves it in, and
 * the rebuild puts the index back into the shape a fresh restore leaves it in.
 * The pictures then differ in the timestamps and in nothing else, and a
 * difference between them can be attributed to the timestamps.
 */
private suspend fun AppDatabase.restoreRecencyOrder(mode: RecencyMode) {
    val assignment =
        when (mode) {
            RecencyMode.AsRestored -> "updated_at = created_at"
            RecencyMode.DatasetOrder -> "updated_at = created_at"
            RecencyMode.NewestFirst -> "updated_at = -created_at"
        }
    useConnection(isReadOnly = false) { transactor ->
        transactor.usePrepared("UPDATE tasks SET $assignment WHERE user_id IS NOT NULL") { statement ->
            statement.step()
        }
        transactor.usePrepared("VACUUM") { statement ->
            statement.step()
        }
        transactor.usePrepared("INSERT INTO tasks_fts(tasks_fts) VALUES('rebuild')") { statement ->
            statement.step()
        }
    }
}

private fun percentile(
    values: List<Duration>,
    fraction: Double,
): Duration {
    check(values.isNotEmpty()) { "a percentile of an empty sample is not a number" }
    val sorted = values.sorted()
    val rank = ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

private fun millis(duration: Duration): String =
    String.format(Locale.ROOT, "%.3f", duration.inWholeMicroseconds / 1000.0)

private suspend fun restore(
    db: AppDatabase,
    fixture: DatabaseTestFixture,
    archivePath: String,
    owner: UserId,
) {
    val fs = JvmFileSystem()
    val importer =
        BackupImporter(
            log = Logger(loggerConfigInit(CommonWriter()), "fts-ordering-perf"),
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
            codec = JvmBackupCodec(),
            clock = Clock.System,
            createFileSource = JvmFileSourceFactory(),
            tasksSettings = FakeTasksSettingsRepository(),
            database = db,
        )
    val restored =
        importer.import(
            importOptions {
                sourcePath = archivePath
                targetUserId = owner
            },
        )
    check(restored.isSuccess) { "restore failed: ${restored.exceptionOrNull()}" }
}

private fun render(
    archive: File,
    config: FtsPerfConfig,
    runs: List<ModeRun>,
): String =
    buildString {
        appendHeader(archive, config)
        for (run in runs) {
            appendRun(run)
        }
    }

private fun StringBuilder.appendHeader(
    archive: File,
    config: FtsPerfConfig,
) {
    appendLine("## FTS task-title ordering — measurement")
    appendLine()
    appendLine("- dataset: `${archive.absolutePath}` (${archive.length() / 1024} KiB)")
    appendLine("- iterations: ${config.iterations}, warmup: ${config.warmup}, owner: ${config.owner.value}")
    appendLine("- the rounds are interleaved: every round runs every variant once, in a rotating order, so a")
    appendLine("  machine that gets busy in the middle of the sweep slows every variant down the same way.")
    appendLine(
        "- query shape under test: `tasks_fts MATCH ? JOIN tasks ON rowid WHERE user_id=? AND archived_at IS NULL ORDER BY updated_at DESC LIMIT ?`",
    )
    appendLine("- the statement the DAO runs now: the same join and the same order, but over the ids")
    appendLine("  alone (`ORDER BY t.updated_at DESC, t.rowid DESC`), and then the rows of the survivors")
    appendLine("  are read by their ids.")
    appendLine()
    appendLine("Variants, each one the previous one plus one thing:")
    appendLine()
    appendLine("- `fts_match_count` — the FTS table alone: the matching cost.")
    appendLine("- `match_count` — the same count over the join with the ownership filter.")
    appendLine("- `match_limit_no_order` — the joined rows with the production LIMIT, no ordering.")
    appendLine("- `match_order` — the same statement with `ORDER BY updated_at DESC`.")
    appendLine("- `current_ids` — the production statement with the id alone.")
    appendLine("- `current_no_offsets` — the production statement with the offsets replaced by a constant.")
    appendLine("- `current_offsets_only` — the production statement with the task row replaced by the id.")
    appendLine("- `current` — the production statement verbatim: the whole task row and the match offsets.")
    appendLine()
    appendLine("`current` is the statement the DAO ran before the change this measurement decided,")
    appendLine("and `two_phase_tiebreak` is the statement it runs now. Both are measured as they are,")
    appendLine("so the difference between them is a difference between two statements.")
    appendLine()
    appendLine("`match_limit_no_order` and `match_order` are stated with a CROSS JOIN, which pins the")
    appendLine("join order, so the difference between them prices the sort and nothing else.")
    appendLine("`current_no_order` is the production statement without its ORDER BY: it is recorded as a")
    appendLine("plan only, and never executed — the shape the planner picks when it is allowed to.")
    appendLine()
    appendLine("Candidates, measured after the baseline:")
    appendLine()
    appendLine("- `two_phase` — order by the ids, then read the rows of the survivors.")
    appendLine("- `two_phase_tiebreak` — the same, with the tiebreaker the DAO adds to the order.")
    appendLine("- `two_phase_offsets` — the same, with the match offsets read back from the index.")
    appendLine("- `subquery_candidate` — the same in one statement: the ordering in a subquery.")
    appendLine(
        "- `current_with_index` — the production statement on a schema with `INDEX tasks(user_id, updated_at DESC)`.",
    )
    appendLine(
        "- `index_candidate` — the production statement rewritten with the FTS hit set as a subquery, on the same schema.",
    )
}

private fun StringBuilder.appendRun(run: ModeRun) {
    appendLine()
    appendLine("### ${run.mode.label}")
    appendLine()
    appendLine("- tasks: ${run.taskCount}")
    appendLine()
    appendLine("| scenario | variant | p50 ms | p95 ms | rows | match count | temp B-tree |")
    appendLine("|---|---|---:|---:|---:|---:|---|")
    for (m in run.measurements) {
        val plan = run.plans.firstOrNull { it.scenario == m.scenario && it.variant == m.variant }
        appendLine(
            "| ${m.scenario} | ${m.variant} | ${millis(m.p50)} | ${millis(m.p95)} | " +
                "${m.rows} | ${m.matchCount} | ${if (plan?.usesTempBTree == true) "yes" else "no"} |",
        )
    }
    appendLine()
    appendLine("Cost per variant (p50, milliseconds):")
    appendLine()
    for (phase in Phase.entries) {
        val timed = Variant.entries.filter { it.timed && it.phase == phase }
        if (timed.isEmpty()) continue
        appendLine("Phase: ${phase.label}")
        appendLine()
        appendLine("| scenario | " + timed.joinToString(" | ") { it.label } + " |")
        appendLine("|---|" + timed.joinToString("|") { "---:" } + "|")
        for (scenario in run.measurements.map { it.scenario }.distinct()) {
            val cells =
                timed.map { variant ->
                    millis(
                        run.measurements.first { it.scenario == scenario && it.variant == variant.label }.p50,
                    )
                }
            appendLine("| $scenario | " + cells.joinToString(" | ") + " |")
        }
        appendLine()
    }
    appendLine("Query plans:")
    for (plan in run.plans) {
        appendLine()
        appendLine("- `${plan.scenario}` / `${plan.variant}` tempBTree=${plan.usesTempBTree}")
        plan.steps.forEach { appendLine("  - $it") }
    }
}

// The FTS table alone: what the term matches, without the join and without
// materializing a row.
private const val FTS_COUNT_SQL = """
    SELECT count(*) FROM tasks_fts
    WHERE tasks_fts MATCH ?
"""

// The join with the ownership filter, on the join order the production
// statement uses: the FTS table outer, the content table inner by rowid.
private const val MATCH_COUNT_SQL = """
    SELECT count(*) FROM tasks_fts
    CROSS JOIN tasks t ON t.rowid = tasks_fts.docid
    WHERE tasks_fts MATCH ?
      AND t.user_id = ?
      AND t.archived_at IS NULL
"""

private const val MATCH_NO_ORDER_SQL = """
    SELECT t.id FROM tasks_fts
    CROSS JOIN tasks t ON t.rowid = tasks_fts.docid
    WHERE tasks_fts MATCH ?
      AND t.user_id = ?
      AND t.archived_at IS NULL
    LIMIT ?
"""

private const val MATCH_ORDER_SQL = """
    SELECT t.id FROM tasks_fts
    CROSS JOIN tasks t ON t.rowid = tasks_fts.docid
    WHERE tasks_fts MATCH ?
      AND t.user_id = ?
      AND t.archived_at IS NULL
    ORDER BY t.updated_at DESC
    LIMIT ?
"""

// The production query shape, copied deliberately: this harness measures the
// SQL shape, not a DAO object graph. If production changes, this harness should
// be updated in the same change.
//
// The production statement selects every column of the task and the match
// offsets of the row. Both are carried through the sort — the sorter holds the
// whole row of every matched task until the ordering is decided — so the
// statement is measured exactly as the DAO runs it.
private const val CURRENT_SQL = """
    SELECT t.*, offsets(tasks_fts) AS match_offsets FROM tasks t
    JOIN tasks_fts ON t.rowid = tasks_fts.docid
    WHERE tasks_fts MATCH ?
      AND t.user_id = ?
      AND t.archived_at IS NULL
    ORDER BY t.updated_at DESC
    LIMIT ?
"""

// The same statement with the id alone, to tell the cost of carrying the whole
// row and its offsets through the sort from the cost of the sort itself.
private const val CURRENT_IDS_SQL = """
    SELECT t.id FROM tasks t
    JOIN tasks_fts ON t.rowid = tasks_fts.docid
    WHERE tasks_fts MATCH ?
      AND t.user_id = ?
      AND t.archived_at IS NULL
    ORDER BY t.updated_at DESC
    LIMIT ?
"""

// The same statement without its ordering. Recorded as a plan only: the shape
// the planner picks for it evaluates the FTS match once per task of the
// profile, and that price is not worth paying on every measurement run.
private const val CURRENT_NO_ORDER_SQL = """
    SELECT t.*, offsets(tasks_fts) AS match_offsets FROM tasks t
    JOIN tasks_fts ON t.rowid = tasks_fts.docid
    WHERE tasks_fts MATCH ?
      AND t.user_id = ?
      AND t.archived_at IS NULL
    LIMIT ?
"""

// The production statement with the match offsets replaced by a constant: the
// task row and the sort, without the cost of locating the matched terms in the
// text of every matched task.
private const val CURRENT_NO_OFFSETS_SQL = """
    SELECT t.*, NULL AS match_offsets FROM tasks t
    JOIN tasks_fts ON t.rowid = tasks_fts.docid
    WHERE tasks_fts MATCH ?
      AND t.user_id = ?
      AND t.archived_at IS NULL
    ORDER BY t.updated_at DESC
    LIMIT ?
"""

// The production statement with the task row replaced by the id: the match
// offsets and the sort, without the columns of the task.
private const val CURRENT_OFFSETS_ONLY_SQL = """
    SELECT t.id, offsets(tasks_fts) AS match_offsets FROM tasks t
    JOIN tasks_fts ON t.rowid = tasks_fts.docid
    WHERE tasks_fts MATCH ?
      AND t.user_id = ?
      AND t.archived_at IS NULL
    ORDER BY t.updated_at DESC
    LIMIT ?
"""

// The first statement of the two-phase candidate: the production order, over
// the ids alone.
private const val TWO_PHASE_IDS_SQL = """
    SELECT t.id FROM tasks t
    JOIN tasks_fts ON t.rowid = tasks_fts.docid
    WHERE tasks_fts MATCH ?
      AND t.user_id = ?
      AND t.archived_at IS NULL
    ORDER BY t.updated_at DESC
    LIMIT ?
"""

// The same statement with the tiebreaker the DAO adds: among the tasks updated
// at the same moment, the most recently inserted one comes first.
//
// This is the statement the DAO runs now, and it is measured as it ships. The
// difference between it and `TWO_PHASE_IDS_SQL` is the price of the tiebreaker —
// a second sort key, and a rule where the order used to be the order of the
// bytes of the rows.
private const val TWO_PHASE_TIEBREAK_IDS_SQL = """
    SELECT t.id FROM tasks t
    JOIN tasks_fts ON t.rowid = tasks_fts.docid
    WHERE tasks_fts MATCH ?
      AND t.user_id = ?
      AND t.archived_at IS NULL
    ORDER BY t.updated_at DESC, t.rowid DESC
    LIMIT ?
"""

// The production statement with the FTS hit set arriving as a subquery, so that
// the planner is free to drive the query from the tasks table and its index.
private const val INDEX_CANDIDATE_SQL = """
    SELECT t.*, offsets(tasks_fts) AS match_offsets FROM tasks t
    JOIN tasks_fts ON t.rowid = tasks_fts.docid
    WHERE tasks_fts MATCH ?
      AND t.rowid IN (SELECT docid FROM tasks_fts WHERE tasks_fts MATCH ?)
      AND t.user_id = ?
      AND t.archived_at IS NULL
    ORDER BY t.updated_at DESC
    LIMIT ?
"""

// The production statement in one piece, with the ordering moved into a
// subquery: the subquery sorts the ids alone, the outer query reads the rows
// of the survivors. The contract is the contract of the production statement —
// the same columns, the same order — and the sorter carries an id per matched
// row instead of a whole task row.
private const val SUBQUERY_CANDIDATE_SQL = """
    SELECT t.*, offsets(tasks_fts) AS match_offsets FROM tasks t
    JOIN tasks_fts ON t.rowid = tasks_fts.docid
    JOIN (
        SELECT o.id AS id FROM tasks o
        JOIN tasks_fts ON o.rowid = tasks_fts.docid
        WHERE tasks_fts MATCH ?
          AND o.user_id = ?
          AND o.archived_at IS NULL
        ORDER BY o.updated_at DESC
        LIMIT ?
    ) s ON s.id = t.id
    WHERE tasks_fts MATCH ?
      AND t.user_id = ?
      AND t.archived_at IS NULL
    ORDER BY t.updated_at DESC
    LIMIT ?
"""
