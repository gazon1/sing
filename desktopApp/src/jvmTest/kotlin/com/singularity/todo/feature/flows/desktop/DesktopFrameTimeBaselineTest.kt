package com.singularity.todo.feature.flows.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectDocument
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.goBack
import com.singularity.todo.test.helpers.openAllProjects
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.core.Koin
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.time.Instant

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
 * The dataset is the point. A timeline of a dozen rows is a warm-up, not a
 * screen; the rows a reader scrolls past are what the frame cost is made
 * of. So the run seeds a populated list — a thousand tasks over the coming
 * weeks, a hundred projects — and measures the interaction on it.
 *
 * The gesture is a controlled drag: a fixed distance, released without
 * velocity. A quick flick flings the list to its end, and a gesture that
 * lands somewhere different every time measures the fling, not the scroll.
 *
 * The run is a measurement, not a gate: it prints its numbers and asserts
 * that the interaction happened at all — the dataset is on the screen, the
 * screen opened, and the gesture moved it. A threshold would be a product
 * requirement, and there is none.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class DesktopFrameTimeBaselineTest {
    private val today: LocalDate = LocalDate(2026, 10, 14)

    /** How many runs are measured, after the warm-up. */
    private val measuredRuns = 5

    /** How many runs are thrown away before the measurement starts. */
    private val warmupRuns = 2

    /** How many gestures one run makes. */
    private val gesturesPerRun = 10

    /** How far one drag travels, in pixels. */
    private val gestureDistance = 400f

    /** The project whose tasks are measured. */
    private val projectName = "Aaa frame project"

    @Test
    @DisplayName("FRAME-TIME desktop baseline: the Upcoming timeline with a populated dataset")
    fun agenda_upcoming_scroll() = measure(agendaScenario())

    @Test
    @DisplayName("FRAME-TIME desktop baseline: the list of projects")
    fun projects_list_scroll() = measure(projectsScenario())

    @Test
    @DisplayName("FRAME-TIME desktop baseline: the detail of a project")
    fun project_detail_open() = measure(projectDetailScenario())

    @Test
    @DisplayName("FRAME-TIME desktop baseline: the detail of a task")
    fun task_detail_open() = measure(taskDetailScenario())

    @Test
    @DisplayName("FRAME-TIME desktop baseline: the calendar and its periods")
    fun calendar_periods() = measure(calendarScenario())

    /**
     * The statistics of the report are the rules the document quotes.
     *
     * The median of an even-sized sample is the mean of its two middle
     * values, and a percentile is the value at the nearest-rank position -
     * so every reported number is a number that was measured.
     */
    @Test
    @DisplayName("FRAME-TIME desktop baseline: the statistics of the report")
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

    /** The agenda's Upcoming timeline: a thousand tasks over the coming weeks. */
    private fun agendaScenario() =
        Scenario(
            name = "agenda-upcoming-scroll",
            seedDescription = mapOf("tasks" to 1_000),
            seed = { koin -> seedTasks(koin, 1_000, null) },
            prepare = { tapTab("Today") },
            open = { tapTab("Upcoming") },
            awaitOpened = { awaitScreen("the timeline shows its rows") { taskRows().isNotEmpty() } },
            reset = { scrollToTopOf(TASK_ROW_PREFIX) },
            gesture = { dragHolding(TASK_ROW_PREFIX) },
            gestureKind = "drag",
            signature = { taskRows() },
        )

    /** The list of projects: two hundred of them, the way a profile has them. */
    private fun projectsScenario() =
        Scenario(
            name = "projects-list-scroll",
            seedDescription = mapOf("projects" to 200),
            seed = { koin -> seedProjects(koin, 200) },
            prepare = {
                if (projectCards().isNotEmpty()) goBack()
                tapTab("Today")
            },
            open = { openAllProjects() },
            awaitOpened = { awaitScreen("the project list shows its cards") { projectCards().isNotEmpty() } },
            reset = { scrollToTopOf(PROJECT_CARD_PREFIX) },
            gesture = { dragHolding(PROJECT_CARD_PREFIX) },
            gestureKind = "drag",
            signature = { projectCards() },
        )

    /**
     * The detail of a project: the screen the reader opens a card for.
     *
     * The desktop's project detail shows a preview of five tasks - the full
     * list of a project lives on a screen the desktop does not have - so the
     * interaction this screen is about is the opening of the card, not a
     * scroll of a list that is not there.
     */
    private fun projectDetailScenario() =
        Scenario(
            name = "project-detail-open",
            seedDescription = mapOf("projects" to 1, "tasks" to 200),
            seed = { koin ->
                seedProject(koin)
                seedTasks(koin, 200, ProjectId.fromString(PROJECT_ID))
            },
            prepare = {
                if (tagExists(TestTags.PROJECT_DETAIL_QUICK_ADD)) goBack()
                if (projectCards().isEmpty()) openAllProjects()
            },
            open = { awaitTag(TestTags.projectCard(projectName)).performClick() },
            awaitOpened = { awaitTag(TestTags.PROJECT_DETAIL_QUICK_ADD) },
            reset = {},
            gesture = {},
            gestureKind = "none",
            signature = { allTestTags() },
        )

    /** The detail of a task: the screen the reader opens a row for. */
    private fun taskDetailScenario() =
        Scenario(
            name = "task-detail-open",
            seedDescription = mapOf("tasks" to 100),
            seed = { koin -> seedTasks(koin, 100, null) },
            prepare = {
                if (tagExists(TestTags.TASK_EDITOR_TITLE_INPUT)) goBack()
                tapTab("Upcoming")
                scrollToTopOf(TASK_ROW_PREFIX)
            },
            open = { awaitTag(TestTags.taskItem(firstTaskTitle)).performClick() },
            awaitOpened = { awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT) },
            reset = {},
            gesture = {},
            gestureKind = "none",
            signature = { allTestTags() },
        )

    /** The calendar: the screen, and the move from one period to the next. */
    private fun calendarScenario() =
        Scenario(
            name = "calendar-periods",
            seedDescription = mapOf("tasks" to 200),
            seed = { koin -> seedTasks(koin, 200, null) },
            prepare = { tapTab("Today") },
            open = { tapTab("Calendar") },
            awaitOpened = { awaitScreen("the calendar shows its month") { monthTitle().isNotEmpty() } },
            reset = {},
            gesture = {
                onAllNodes(hasContentDescription("Next")).onFirst().performClick()
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
        runDesktopAppTest(clock = FakeClock(Instant.parse("2026-10-14T10:00:00Z"))) { koin ->
            scenario.seed(koin)

            val opens = mutableListOf<Long>()
            val gestures = mutableListOf<Long>()

            repeat(warmupRuns + measuredRuns) { run ->
                scenario.prepare(this)

                val openedAt = System.nanoTime()
                scenario.open(this)
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
                        "seed" to scenario.seedDescription,
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
     * is not timed; [open] is the navigation into the screen and [awaitOpened]
     * waits until the screen is really there — both are timed, because the
     * cost of a screen is the cost of it appearing; [gesture] is the
     * interaction the screen is about.
     */
    private class Scenario(
        val name: String,
        val seedDescription: Map<String, Int>,
        val seed: suspend (Koin) -> Unit,
        val prepare: DesktopComposeUiTest.() -> Unit,
        val open: DesktopComposeUiTest.() -> Unit,
        val awaitOpened: DesktopComposeUiTest.() -> Unit,
        val reset: DesktopComposeUiTest.() -> Unit,
        val gesture: DesktopComposeUiTest.() -> Unit,
        val gestureKind: String,
        val signature: DesktopComposeUiTest.() -> List<String>,
    )

    // ─── the dataset ──────────────────────────────────────────────────────────

    /**
     * A thousand tasks over the coming weeks.
     *
     * The dates are spread over thirty days, so the timeline holds thirty
     * day-sections with rows under them: the screen a reader scrolls is a
     * long one, and the sections are part of what has to be laid out.
     *
     * The tasks go in through the repository, the way the app writes them:
     * a row written straight into the database is a row the agenda does
     * not necessarily see.
     *
     * They go in as one batch. A row per transaction is a thousand
     * invalidations, and the screen the measurement watches recomposes a
     * thousand times while the dataset is still being written — the
     * measurement would then report the cost of the seeding, not the cost
     * of the scroll.
     *
     * Every task gets its own creation moment: thirty tasks share a due
     * date, and a list that sorts rows by anything but a key of its own
     * shuffles them on every recomposition. A list that shuffles while it is
     * measured measures noise.
     */
    private suspend fun seedTasks(
        koin: Koin,
        count: Int,
        projectId: ProjectId?,
    ) {
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        val repository = koin.get<TaskRepository>()
        val tasks =
            (1..count).map { index ->
                val createdAt = Instant.fromEpochMilliseconds(index * 1_000L)
                testTask(
                    id = TaskId("frame-time-task-$index"),
                    title = "$TASK_TITLE_PREFIX $index",
                    projectId = projectId,
                    dueDate = today.plus(index % 30 + 1, DateTimeUnit.DAY),
                    userId = userId,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                )
            }
        val started = System.nanoTime()
        repository.createAll(tasks).getOrThrow()
        println(
            "FRAME_TIME_PROGRESS seeded=${tasks.size} " +
                "in ${(System.nanoTime() - started) / 1_000_000}ms",
        )
    }

    /** Two hundred projects — the list a profile with many projects holds. */
    private suspend fun seedProjects(
        koin: Koin,
        count: Int,
    ) {
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        val repository = koin.get<ProjectsRepository>()
        val epoch = Instant.fromEpochMilliseconds(0)
        repeat(count) { index ->
            val number = (index + 1).toString().padStart(3, '0')
            repository.upsert(
                ProjectDocument(
                    Project(
                        id = ProjectId.fromString("frame-time-project-$number"),
                        name = "Frame project $number",
                        color = 0xFF2196F3.toInt(),
                        createdAt = epoch,
                        updatedAt = epoch,
                        userId = userId,
                    ),
                    emptySet(),
                ),
            )
        }
        println("FRAME_TIME_PROGRESS seeded=$count projects")
    }

    /** The one project whose tasks the project scenario measures. */
    private suspend fun seedProject(koin: Koin) {
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        val epoch = Instant.fromEpochMilliseconds(0)
        koin.get<ProjectsRepository>().upsert(
            ProjectDocument(
                Project(
                    id = ProjectId.fromString(PROJECT_ID),
                    name = projectName,
                    color = 0xFF3F51B5.toInt(),
                    createdAt = epoch,
                    updatedAt = epoch,
                    userId = userId,
                ),
                emptySet(),
            ),
        )
    }

    /** The title of the first task of the dataset — the row the reader opens. */
    private val firstTaskTitle: String get() = "$TASK_TITLE_PREFIX 1"

    // ─── the screen ───────────────────────────────────────────────────────────

    /** The rows of the timeline, by their tags. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.taskRows(): List<String> = tagsWithPrefix(TASK_ROW_PREFIX)

    /** The cards of the project list, by their tags. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.projectCards(): List<String> = tagsWithPrefix(PROJECT_CARD_PREFIX)

    /** Every test tag on the screen, for a failure that has to explain itself. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.allTestTags(): List<String> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag))
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .distinct()

    /** The tags on the screen that start with [prefix]. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.tagsWithPrefix(prefix: String): List<String> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .filter { it.startsWith(prefix) }
            .distinct()

    /** Whether a node with the tag is on the screen. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.tagExists(tag: String): Boolean =
        onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits until the screen is really there.
     *
     * The navigation into a screen is asynchronous: the click that opens it
     * returns before the screen is drawn. A check that runs right after the
     * click reads a screen that has not arrived yet, and the run fails on a
     * race that has nothing to do with the measurement.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.awaitScreen(
        description: String,
        condition: () -> Boolean,
    ) {
        waitUntil(conditionDescription = description, timeoutMillis = SCREEN_TIMEOUT_MS) { condition() }
    }

    /** The month the calendar shows. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.monthTitle(): String =
        onAllNodes(hasTestTag(TestTags.CALENDAR_MONTH_TITLE), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.Text)
            ?.joinToString(" ") { it.text }
            .orEmpty()

    /**
     * The scrollable that holds the rows with the given tags.
     *
     * The shell has panes of its own that scroll too, and a drag in one of
     * those moves the shell, not the list. The list is found by the rows it
     * holds: it is the scrollable on the screen that holds most of them.
     *
     * The unmerged tree is the one that tells the truth about geometry: a
     * merged list node carries the bounds of everything inside it, and a
     * gesture aimed at the middle of that is a gesture in the void.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.scrollableHolding(prefix: String): Int {
        val rows =
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .filter { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }
        val scrollables = onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes()
        val root = mainRoot()
        // A pane that is scrolled off to the side holds the rows in its
        // geometry and not on the display, and a gesture aimed at it lands in
        // the void.
        val index =
            scrollables
                .withIndex()
                .filter { (_, node) ->
                    node.positionInRoot.x >= 0 &&
                        node.positionInRoot.y >= 0 &&
                        node.positionInRoot.x + node.size.width <= root.size.width &&
                        node.positionInRoot.y + node.size.height <= root.size.height
                }.map { (index, node) -> index to rows.count { row -> row.inside(node) } }
                .filter { (_, inside) -> inside > 0 }
                .maxByOrNull { (_, inside) -> inside }
                ?.first
                ?: error("no scrollable node holds the '$prefix' rows; tags=${allTestTags().take(40)}")
        return index
    }

    /** The window the app is drawn in. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.mainRoot(): SemanticsNode =
        onAllNodes(isRoot()).fetchSemanticsNodes().maxByOrNull { it.size.width * it.size.height }
            ?: error("the app has no window")

    /** Whether a row sits inside a node's bounds. */
    private fun SemanticsNode.inside(node: SemanticsNode): Boolean =
        positionInRoot.x >= node.positionInRoot.x &&
            positionInRoot.y >= node.positionInRoot.y &&
            positionInRoot.x <= node.positionInRoot.x + node.size.width &&
            positionInRoot.y <= node.positionInRoot.y + node.size.height

    /**
     * Drags the list of the given rows down by a fixed distance.
     *
     * The drag is released without velocity: the finger pauses before it
     * lifts, so the list does not fling to its end. A fling lands
     * somewhere different every time, and a run that measures the fling
     * measures noise.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.dragHolding(prefix: String) {
        val index = scrollableHolding(prefix)
        onAllNodes(hasScrollAction(), useUnmergedTree = true)[index].performTouchInput {
            val middle = center
            down(Offset(middle.x, middle.y))
            moveBy(Offset(0f, -gestureDistance))
            advanceEventTime(500)
            up()
        }
    }

    /**
     * Puts the list back at its top.
     *
     * The list keeps its place when the screen is left and entered again, so
     * without this the second run would drag through rows the first run had
     * already passed.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.scrollToTopOf(prefix: String) {
        val index = scrollableHolding(prefix)
        val list = onAllNodes(hasScrollAction(), useUnmergedTree = true)[index]
        try {
            list.performScrollToIndex(0)
        } catch (_: Throwable) {
            // A list that does not publish its index is dragged back by hand.
            repeat(gesturesPerRun) {
                list.performTouchInput {
                    val middle = center
                    down(Offset(middle.x, middle.y))
                    moveBy(Offset(0f, gestureDistance))
                    advanceEventTime(500)
                    up()
                }
            }
        }
        waitForIdle()
    }

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

    /**
     * The root of the repository.
     *
     * The working directory of the test is the directory of the module, so a
     * relative path would put the report into the build directory of the
     * module - and `./gw clean` would take it away.
     */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        return dir ?: error("the repository root is not above ${System.getProperty("user.dir")}")
    }

    private fun buildJson(fields: Map<String, Any>): String =
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
                "%.3f".format(value)
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
        const val TASK_ROW_PREFIX = "task_item_"
        const val PROJECT_CARD_PREFIX = "project_card_"
        const val PROJECT_ID = "frame-time-project"
        const val TASK_TITLE_PREFIX = "Frame time task"

        /** How long a screen has to appear before the run is declared failed. */
        const val SCREEN_TIMEOUT_MS = 15_000L
    }
}
