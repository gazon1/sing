package com.singularity.todo.feature.flows.desktop

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.goBack
import com.singularity.todo.test.helpers.openAllProjects
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The desktop half of the Compose frame-time baseline (#2975).
 *
 * The desktop has no frame metrics: there is no vsync, no deadline and
 * nothing that says "janky". What the desktop does have is the work a frame
 * costs — the recomposition and the layout of the tree the frame draws — and
 * the latency a reader waits for while that work happens. Both are measured
 * here, and both are reported as what they are: the desktop's own numbers,
 * not Android's.
 *
 * The screens are the ones the issue names: the agenda's timeline, the list
 * of projects, the detail of a project, the detail of a task and the
 * calendar. Each of them is measured the same way — the cost of entering the
 * screen and the cost of the gesture that is the screen's own — so the
 * numbers of two screens can be put side by side.
 *
 * The dataset is the point, and it is the dataset of the phone (#3090): the
 * same generator, the same seed and the same profile that the Android run
 * measured, restored through the app's own importer. A dataset the test
 * makes up is a dataset no other platform ever saw, and a number measured on
 * it describes the test rather than the app.
 *
 * The task the detail screen opens is the row of the timeline that holds the
 * most of what a task can hold — children, tags, dependencies, notes and
 * checklist items — because the first row of a sorted list is usually the
 * emptiest one, and a detail screen that shows a title and nothing else
 * measures nothing. It is the same rule the Android run uses, and the
 * relations of the task it opened travel into the report.
 *
 * The gesture is a controlled drag: a fixed distance, released without
 * velocity. A quick flick flings the list to its end, and a gesture that
 * lands somewhere different every time measures the fling, not the scroll.
 *
 * The run is a measurement, not a gate: it prints its numbers and asserts
 * that the interaction happened at all — the dataset is on the screen, the
 * screen opened, the gesture moved it, and the task it opened carries the
 * relations the issue asks for. A threshold would be a product requirement,
 * and there is none.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class DesktopFrameTimeBaselineTest {
    /** How many runs are measured, after the warm-up. */
    private val measuredRuns = 5

    /** How many runs are thrown away before the measurement starts. */
    private val warmupRuns = 2

    /** How many gestures one run makes. */
    private val gesturesPerRun = GESTURES_PER_RUN

    /** How far one drag travels, in pixels. */
    private val gestureDistance = GESTURE_DISTANCE

    /**
     * The project the run opens: the one with the most tasks of the dataset.
     *
     * The Android run measured the task list of that project, and the two
     * platforms have to look at the same project, or their numbers are the
     * numbers of two different projects.
     */
    private val projectWithMostTasks: String get() = dataset.projectWithMostTasks()

    @Test
    @DisplayName("#2975 the desktop baseline: the Upcoming timeline with the shared dataset")
    fun agenda_upcoming_scroll() = measure(agendaScenario())

    @Test
    @DisplayName("#2975 the desktop baseline: the list of projects")
    fun projects_list_scroll() = measure(projectsScenario())

    @Test
    @DisplayName("#2975 the desktop baseline: the detail of a project")
    fun project_detail_open() = measure(projectDetailScenario())

    @Test
    @DisplayName("#2975 the desktop baseline: the detail of a task")
    fun task_detail_open() = measure(taskDetailScenario())

    @Test
    @DisplayName("#2975 the desktop baseline: the calendar and its periods")
    fun calendar_periods() = measure(calendarScenario())

    /**
     * The statistics of the report are the rules the document quotes.
     *
     * The median of an even-sized sample is the mean of its two middle
     * values, and a percentile is the value at the nearest-rank position -
     * so every reported number is a number that was measured.
     */
    @Test
    @DisplayName("#2975 the desktop baseline: the statistics of the report")
    fun the_statistics_of_the_report() {
        val even = listOf(10L, 20L, 30L, 40L).map { it * 1_000_000L }
        check(medianMs(even) == 25.0) {
            "the median of an even sample is the mean of its two middle values, not ${medianMs(even)}"
        }
        val odd = listOf(10L, 20L, 30L).map { it * 1_000_000L }
        check(medianMs(odd) == 20.0) {
            "the median of an odd sample is its middle value, not ${medianMs(odd)}"
        }
        val hundred = (1..100).map { it * 1_000_000L }
        check(percentileMs(hundred, 0.95) == 95.0) {
            "the 95th percentile of a hundred samples is the 95th of them, not ${percentileMs(hundred, 0.95)}"
        }
        check(percentileMs(hundred, 0.5) == 50.0) {
            "the 50th percentile of a hundred samples is the 50th of them, not ${percentileMs(hundred, 0.5)}"
        }
        check(percentileMs(emptyList<Long>(), 0.95) == 0.0) {
            "a sample without values has no percentile"
        }
    }

    // ─── the scenarios ─────────────────────────────────────────────────────────

    /** The agenda's Upcoming timeline: the tasks of the shared dataset. */
    private fun agendaScenario() =
        Scenario(
            name = "agenda-upcoming-scroll",
            prepare = { tapTab("Today") },
            subject = { FrameTimeSubject(mapOf("screen" to "Upcoming")) },
            open = { tapTab("Upcoming") },
            awaitOpened = { awaitScreen("the timeline shows its rows") { taskRows().isNotEmpty() } },
            reset = { scrollToTopOf(TASK_ROW_PREFIX) },
            gesture = { dragHolding(TASK_ROW_PREFIX) },
            gestureKind = "drag",
            signature = { taskRows() },
        )

    /** The list of projects: the projects of the shared dataset. */
    private fun projectsScenario() =
        Scenario(
            name = "projects-list-scroll",
            prepare = {
                if (projectCards().isNotEmpty()) goBack()
                tapTab("Today")
            },
            subject = { FrameTimeSubject(mapOf("screen" to "All projects")) },
            open = { openAllProjects() },
            awaitOpened = { awaitScreen("the project list shows its cards") { projectCards().isNotEmpty() } },
            reset = {
                // Another scenario may have left a search behind - the list is
                // the whole list of the dataset, not the one project a search
                // found.
                clearProjectSearch()
                scrollToTopOf(PROJECT_CARD_PREFIX)
            },
            gesture = { dragHolding(PROJECT_CARD_PREFIX) },
            gestureKind = "drag",
            signature = { projectCards() },
        )

    /**
     * The detail of a project: the screen the reader opens a card for.
     *
     * The project is the one with the most tasks of the dataset - the same
     * project the Android run measured. The first card of the list is not it:
     * the list is sorted by name, and a project with a hundred tasks measures
     * something else than a project with ten.
     *
     * The desktop's project detail shows a preview of five tasks - the full
     * list of a project lives on a screen the desktop does not have - so the
     * interaction this screen is about is the opening of the card, not a
     * scroll of a list that is not there.
     */
    private fun projectDetailScenario() =
        Scenario(
            name = "project-detail-open",
            prepare = {
                if (tagExists(TestTags.PROJECT_DETAIL_QUICK_ADD)) goBack()
                if (!tagExists(TestTags.PROJECTS_SEARCH_INPUT)) openAllProjects()
                // The project with the most tasks is not on the first screen
                // of the list, so the list is searched for it. The search is
                // the setup of the run, not the interaction the run measures.
                clearProjectSearch()
                typeIntoTag(TestTags.PROJECTS_SEARCH_INPUT, projectWithMostTasks)
                awaitScreen("the list shows the project") {
                    projectCards().contains(TestTags.projectCard(projectWithMostTasks))
                }
            },
            subject = { data ->
                val count = data.projectTaskCounts.getValue(projectWithMostTasks)
                FrameTimeSubject(
                    report =
                        mapOf(
                            "screen" to "Project detail",
                            "project" to projectWithMostTasks,
                            "projectTasks" to count,
                        ),
                    projectName = projectWithMostTasks,
                    projectTaskCount = count,
                    projectTaskTags = data.taskTagsByProject.getValue(projectWithMostTasks),
                )
            },
            open = { subject ->
                val name = checkNotNull(subject.projectName) { "the project list showed no card to open" }
                awaitTag(TestTags.projectCard(name)).performClick()
            },
            awaitOpened = {
                awaitTag(TestTags.PROJECT_DETAIL_QUICK_ADD)
                // The tasks of the project are what the screen is about: a
                // detail screen that draws no task of the project measures an
                // empty screen.
                awaitScreen("the project shows its tasks") { taskRows().isNotEmpty() }
            },
            reset = {},
            gesture = {},
            gestureKind = "none",
            signature = { allTestTags() },
            verify = { subject -> verifyFrameTimeProject(subject) },
        )

    /** The detail of a task: the screen the reader opens a row for. */
    private fun taskDetailScenario() =
        Scenario(
            name = "task-detail-open",
            prepare = {
                if (tagExists(TestTags.TASK_EDITOR_TITLE_INPUT)) goBack()
                tapTab("Upcoming")
                // The timeline of ten thousand rows is not on the screen the
                // moment the tab is tapped: the rows arrive when the query
                // answers, and a scroll before that scrolls nothing.
                awaitScreen("the timeline shows its rows") { taskRows().isNotEmpty() }
                scrollToTopOf(TASK_ROW_PREFIX)
            },
            subject = { dataset -> frameTimeSubjectOfTheRichestRow(dataset) },
            open = { subject ->
                val title = checkNotNull(subject.task) { "no task was chosen to open" }.title
                awaitTag(TestTags.taskItem(title)).performClick()
            },
            awaitOpened = { awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT) },
            reset = {},
            gesture = {},
            gestureKind = "none",
            signature = { allTestTags() },
            verify = { subject -> verifyFrameTimeRelations(subject) },
        )

    /** The calendar: the screen, and the move from one period to the next. */
    private fun calendarScenario() =
        Scenario(
            name = "calendar-periods",
            prepare = { tapTab("Today") },
            subject = { FrameTimeSubject(mapOf("screen" to "Calendar")) },
            open = { tapTab("Calendar") },
            awaitOpened = { awaitScreen("the calendar shows its month") { monthTitle().isNotEmpty() } },
            reset = {},
            gesture = {
                // The month the calendar shows comes from the database: the
                // click asks for the next month and the title changes when the
                // query answers. A click that is not waited for measures a
                // click that has not drawn anything yet - so the wait is part
                // of the gesture, and the gesture is what it costs to move
                // from one period to the next.
                awaitScreen("the calendar offers the next month") {
                    onAllNodes(hasContentDescription("Next")).fetchSemanticsNodes().isNotEmpty()
                }
                val month = monthTitle()
                onAllNodes(hasContentDescription("Next")).onFirst().performClick()
                awaitScreen("the calendar shows the next month") {
                    val shown = monthTitle()
                    shown.isNotBlank() && shown != month
                }
            },
            gestureKind = "click",
            signature = { listOf(monthTitle()) },
        )

    // ─── the measurement ──────────────────────────────────────────────────────

    /**
     * One scenario, measured.
     *
     * Every run opens the screen and makes the screen's gesture, and the run
     * fails if either of the two did not happen: a screen that did not open
     * and a gesture that moved nothing are not measurements, they are zeros
     * with a plausible shape.
     */
    private fun measure(scenario: Scenario) =
        runDesktopAppTest(
            clock = FakeClock(DATASET_NOW),
            overrides = frameTimeDatasetModule(dataset.database),
        ) {
            val opens = mutableListOf<Long>()
            val gestures = mutableListOf<Long>()
            var subject = FrameTimeSubject(emptyMap())

            repeat(warmupRuns + measuredRuns) { run ->
                scenario.prepare(this)

                subject = scenario.subject(this, dataset)

                val openedAt = System.nanoTime()
                scenario.open(this, subject)
                scenario.awaitOpened(this)
                waitForIdle()
                val openCost = System.nanoTime() - openedAt

                scenario.reset(this)

                val runGestures = mutableListOf<Long>()
                if (scenario.gestureKind != "none") {
                    repeat(gesturesPerRun) { gesture ->
                        val before = scenario.signature(this)
                        val started = System.nanoTime()
                        scenario.gesture(this)
                        waitForIdle()
                        runGestures.add(System.nanoTime() - started)

                        // Every gesture has to have moved the screen. A gesture
                        // that did not move it measures a gesture that did
                        // nothing, and a median built of such gestures
                        // describes nothing: the run fails instead.
                        check(scenario.signature(this) != before) {
                            "gesture ${gesture + 1} of $gesturesPerRun of '${scenario.name}' moved " +
                                "nothing: the screen shows the same rows before and after"
                        }
                    }
                }

                if (run >= warmupRuns) {
                    opens.add(openCost)
                    gestures.addAll(runGestures)
                }
            }

            // What the screen shows of the task the run opened is checked after
            // the measurement: the check is about the dataset, not about the
            // cost of the screen, and it must not be timed.
            scenario.verify(this, subject)

            checkReportInvariants(scenario, opens, gestures)

            val measurements = mutableListOf<Map<String, Any>>()
            measurements.add(
                measurement(
                    name = "open",
                    kind = "navigation",
                    samples = opens,
                ),
            )
            if (scenario.gestureKind != "none") {
                measurements.add(
                    measurement(
                        name = "gesture",
                        kind = scenario.gestureKind,
                        samples = gestures,
                        gesturesPerRun = gesturesPerRun,
                        distancePx = if (scenario.gestureKind == "drag") gestureDistance.toInt() else null,
                    ),
                )
            }

            val report =
                buildJson(
                    mapOf(
                        "target" to "jvm-desktop",
                        "scenario" to scenario.name,
                        "dataset" to dataset.report(),
                        "subject" to subject.report,
                        "runs" to measuredRuns,
                        "warmupRuns" to warmupRuns,
                        "percentileConvention" to
                            "nearest-rank; the median of an even-sized sample is the mean of its two middle values",
                        "hostLoadAverage" to hostLoadAverage(),
                        "measurements" to measurements,
                    ),
                )
            println("FRAME_TIME_BASELINE $report")
            // The report is written by the test that measured: the copy the
            // document publishes is this file, not a number someone typed.
            // The path is taken from the root of the repository, not from the
            // working directory of the test, which is the module directory.
            val outDir = File(repoRoot(), "build/perf/frame-time-desktop")
            outDir.mkdirs()
            File(outDir, "frame-time-baseline-${scenario.name}.json").writeText(report)
        }

    /** One measurement of the report: its samples and the numbers of them. */
    private fun measurement(
        name: String,
        kind: String,
        samples: List<Long>,
        gesturesPerRun: Int? = null,
        distancePx: Int? = null,
    ): Map<String, Any> {
        val fields = mutableMapOf<String, Any>()
        fields["name"] = name
        fields["kind"] = kind
        if (gesturesPerRun != null) fields["gesturesPerRun"] = gesturesPerRun
        if (distancePx != null) fields["distancePx"] = distancePx
        fields["samples"] = samples.size
        fields["medianMs"] = medianMs(samples)
        fields["p95Ms"] = percentileMs(samples, 0.95)
        fields["samplesMs"] = samples.map { it / 1_000_000.0 }
        return fields
    }

    /**
     * The numbers of the report have to be the numbers of the run.
     *
     * The warm-up runs are thrown away and are not in the report, every
     * gesture of a measured run is in it, and the percentiles are values that
     * were measured - not numbers the report made up.
     */
    private fun checkReportInvariants(
        scenario: Scenario,
        opens: List<Long>,
        gestures: List<Long>,
    ) {
        check(opens.size == measuredRuns) {
            "the report holds ${opens.size} opens for $measuredRuns measured runs: the warm-up runs are in it"
        }
        check(gestures.size == measuredRuns * gesturesPerRun || scenario.gestureKind == "none") {
            "the report holds ${gestures.size} gestures for $measuredRuns runs of $gesturesPerRun gestures"
        }
        check(opens.all { it > 0 }) {
            "an open of the screen took no time"
        }
        val samples = gestures.map { it / 1_000_000.0 }
        if (samples.isNotEmpty()) {
            val median = medianMs(gestures)
            val p95 = percentileMs(gestures, 0.95)
            check(median >= samples.min() && median <= samples.max()) {
                "the median $median is not between the smallest (${samples.min()}) " +
                    "and the largest (${samples.max()}) sample"
            }
            check(p95 >= median) {
                "the 95th percentile $p95 is below the median $median"
            }
            check(samples.any { it == p95 }) {
                "the 95th percentile $p95 is not a value that was measured"
            }
        }
    }

    /**
     * One screen, measured the same way as every other screen.
     *
     * [prepare] brings the app to the screen the measurement starts from and
     * is not timed; [subject] says what the run is about to open and is not
     * timed either; [open] is the navigation into the screen and
     * [awaitOpened] waits until the screen is really there - both are timed,
     * because the cost of a screen is the cost of it appearing; [gesture] is
     * the interaction the screen is about.
     */
    private class Scenario(
        val name: String,
        val prepare: DesktopComposeUiTest.() -> Unit,
        val subject: DesktopComposeUiTest.(FrameTimeDataset) -> FrameTimeSubject,
        val open: DesktopComposeUiTest.(FrameTimeSubject) -> Unit,
        val awaitOpened: DesktopComposeUiTest.() -> Unit,
        val reset: DesktopComposeUiTest.() -> Unit,
        val gesture: DesktopComposeUiTest.() -> Unit,
        val gestureKind: String,
        val signature: DesktopComposeUiTest.() -> List<String>,
        val verify: DesktopComposeUiTest.(FrameTimeSubject) -> Unit = {},
    )

    // ─── the numbers ──────────────────────────────────────────────────────────

    /**
     * The load of the host that measured.
     *
     * The desktop numbers move with the load of the machine they were taken
     * on, so the report carries the load: a number without the state of the
     * host cannot be compared with another number.
     */
    private fun hostLoadAverage(): Double {
        val load = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
        return if (load < 0) -1.0 else load
    }

    /**
     * The median of the samples.
     *
     * The middle value of an odd-sized sample, the mean of the two middle
     * values of an even-sized one — the same rule the Android script uses,
     * so the two platforms' numbers are quoted by one convention.
     */
    private fun medianMs(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        val median =
            if (sorted.size % 2 == 1) {
                sorted[middle].toDouble()
            } else {
                (sorted[middle - 1] + sorted[middle]).toDouble() / 2.0
            }
        return median / 1_000_000.0
    }

    /**
     * The p-th percentile by the nearest-rank rule.
     *
     * The p-th percentile is the value at rank `ceil(p * n)` of the sorted
     * values, so every reported percentile is a value that was actually
     * measured.
     */
    private fun percentileMs(
        values: List<Long>,
        percentile: Double,
    ): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val rank = maxOf(1, minOf(sorted.size, ceil(percentile * sorted.size).toInt()))
        return sorted[rank - 1] / 1_000_000.0
    }

    private fun buildJson(fields: Map<String, Any?>): String =
        fields.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"$key\": ${renderValue(value)}"
        }

    private fun renderValue(value: Any?): String =
        when (value) {
            null -> {
                "null"
            }

            is String -> {
                "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }

            is Double -> {
                // The root locale, so the decimal separator is a dot in every
                // locale: a comma would make the report unreadable as JSON.
                "%.3f".format(Locale.ROOT, value)
            }

            is Int -> {
                value.toString()
            }

            is Long -> {
                value.toString()
            }

            is List<*> -> {
                value.joinToString(prefix = "[", postfix = "]") { renderValue(it) }
            }

            is Map<*, *> -> {
                value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
                    "\"$key\": ${renderValue(item)}"
                }
            }

            else -> {
                value.toString()
            }
        }

    private companion object {
        /**
         * The dataset of the class, restored once before the first test.
         *
         * The restore of ten thousand rows is the preparation of the screen,
         * not a measurement, and it takes longer than the minute that
         * kotlinx-coroutines-test gives a test body. So it happens once, here,
         * and every test of the class measures the same rows the app restored.
         */
        private lateinit var dataset: FrameTimeDataset

        @JvmStatic
        @BeforeAll
        fun restoreTheDataset() {
            runTest(timeout = 10.minutes) {
                dataset = restoreFrameTimeDataset()
            }
        }

        /**
         * The database of the run is closed when the class is done.
         *
         * It is a real SQLite database with a connection pool of its own, so
         * it is closed rather than left to the end of the JVM.
         */
        @JvmStatic
        @AfterAll
        fun closeTheDatabase() {
            if (::dataset.isInitialized) {
                dataset.database.close()
            }
        }
    }
}
