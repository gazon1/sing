package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.core.Koin
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
 * The dataset is the point. A timeline of a dozen rows is a warm-up, not a
 * screen; the rows a reader scrolls past are what the frame cost is made
 * of. So the run seeds a populated timeline — a thousand tasks spread over
 * the coming weeks — and measures the interaction on it.
 *
 * The gesture is a controlled drag: a fixed distance, released without
 * velocity. A quick flick flings the list to its end, and a gesture that
 * lands somewhere different every time measures the fling, not the scroll.
 *
 * The run is a measurement, not a gate: it prints its numbers and asserts
 * that the interaction happened at all — the dataset is on the screen, and
 * the scroll moved it. A threshold would be a product requirement, and
 * there is none.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class AgendaFrameTimeBaselineTest {
    private val today: LocalDate = LocalDate(2026, 10, 14)

    /** How many tasks the timeline holds. */
    private val taskCount = 1_000

    /** How many runs are measured, after the warm-up. */
    private val measuredRuns = 5

    /** How many runs are thrown away before the measurement starts. */
    private val warmupRuns = 2

    /** How many drags one run makes. */
    private val dragsPerRun = 10

    /** How far one drag travels, in pixels. */
    private val dragDistance = 400f

    /** What the drag aimed at, for a failure that has to explain itself. */
    private var scrollTarget: String = ""

    @Test
    @DisplayName("FRAME-TIME desktop baseline: the Upcoming timeline with a populated dataset")
    fun upcoming_timeline_frame_cost() =
        runDesktopAppTest(
            clock = FakeClock(Instant.parse("2026-10-14T10:00:00Z")),
        ) { koin ->
            seedTimeline(koin)

            val opens = mutableListOf<Long>()
            val drags = mutableListOf<Long>()
            var composedRows = 0

            repeat(warmupRuns + measuredRuns) { run ->
                // The switch is timed as the reader does it: the drawer opens,
                // the tab is chosen, the screen settles. The tab is left first,
                // because a tab that is already open costs nothing to open —
                // a number that would flatter the screen it claims to measure.
                tapTab("Today")
                val opened = System.nanoTime()
                tapTab("Upcoming")
                val openCost = System.nanoTime() - opened

                // Every run starts at the top of the timeline, so every run
                // drags the same distance over the same rows.
                scrollTimelineToTop()

                val rowsBefore = taskRowTags().toSet()
                check(rowsBefore.isNotEmpty()) {
                    "the seeded timeline is not on the screen: no task row is visible"
                }
                println(
                    "FRAME_TIME_PROGRESS run=${run + 1}/${warmupRuns + measuredRuns} " +
                        "openMs=$openCost rows=${rowsBefore.size}",
                )

                // The per-frame work of the desktop: a gesture, the
                // recomposition it causes and the layout of the tree it
                // redraws — measured as one step, because that is the unit a
                // reader feels.
                val runDrags = mutableListOf<Long>()
                repeat(dragsPerRun) {
                    val started = System.nanoTime()
                    dragTimeline()
                    waitForIdle()
                    runDrags.add(System.nanoTime() - started)
                }

                val rowsAfter = taskRowTags().toSet()
                check(rowsAfter != rowsBefore) {
                    "the scroll moved nothing: the same rows are on the screen; " +
                        "target=$scrollTarget rows=$rowsBefore"
                }

                if (run >= warmupRuns) {
                    opens.add(openCost)
                    drags.addAll(runDrags)
                    composedRows = taskRowCount()
                }
            }

            println(
                "FRAME_TIME_BASELINE " +
                    buildJson(
                        mapOf(
                            "target" to "jvm-desktop",
                            "scenario" to "agenda-upcoming-scroll",
                            "tasks" to taskCount,
                            "runs" to measuredRuns,
                            "warmupRuns" to warmupRuns,
                            "dragsPerRun" to dragsPerRun,
                            "dragDistancePx" to dragDistance.toInt(),
                            "openTimelineMedianMs" to medianMs(opens),
                            "openTimelineSamples" to opens.size,
                            "scrollMedianMs" to medianMs(drags),
                            "scrollP95Ms" to percentileMs(drags, 0.95),
                            "scrollSamples" to drags.size,
                            "composedRows" to composedRows,
                        ),
                    ),
            )
        }

    /** The tags of the task rows the screen holds. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.taskRowTags(): List<String> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag))
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .filter { it.startsWith("task_item_") }

    /** Every test tag on the screen, for a failure that has to explain itself. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.allTestTags(): List<String> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag))
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .distinct()

    /** How many task rows the screen holds in its tree. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.taskRowCount(): Int =
        taskRowTags().size

    /**
     * The scrollable that holds the rows of the timeline.
     *
     * The shell has panes of its own that scroll too, and a drag in one of
     * those moves the shell, not the timeline. The list is found by the rows
     * it holds: it is the scrollable on the screen that holds most of them.
     *
     * The unmerged tree is the one that tells the truth about geometry: a
     * merged list node carries the bounds of everything inside it, and a
     * gesture aimed at the middle of that is a gesture in the void.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.timelineListIndex(): Int {
        val rows = taskRowNodes()
        val scrollables = onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes()
        val root = onRoot().fetchSemanticsNode()
        // A pane that is scrolled off to the side holds the rows in its
        // geometry and not on the display, and a gesture aimed at it lands in
        // the void.
        val onScreen =
            scrollables.withIndex().filter { (_, node) ->
                node.positionInRoot.x >= 0 &&
                    node.positionInRoot.y >= 0 &&
                    node.positionInRoot.x + node.size.width <= root.size.width &&
                    node.positionInRoot.y + node.size.height <= root.size.height
            }
        val index =
            onScreen
                .map { (index, node) -> index to rows.count { row -> row.inside(node) } }
                .filter { (_, inside) -> inside > 0 }
                .maxByOrNull { (_, inside) -> inside }
                ?.first
                ?: error("no scrollable node holds the rows of the timeline; tags=${allTestTags().take(40)}")
        scrollTarget = "listIndex=$index"
        return index
    }

    /** The task rows, with their geometry, from the unmerged tree. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.taskRowNodes(): List<SemanticsNode> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("task_item_") == true }

    /** Whether a row sits inside a node's bounds. */
    private fun SemanticsNode.inside(node: SemanticsNode): Boolean =
        positionInRoot.x >= node.positionInRoot.x &&
            positionInRoot.y >= node.positionInRoot.y &&
            positionInRoot.x <= node.positionInRoot.x + node.size.width &&
            positionInRoot.y <= node.positionInRoot.y + node.size.height

    /**
     * Drags the timeline down by a fixed distance.
     *
     * The drag is released without velocity: the finger pauses before it
     * lifts, so the list does not fling to its end. A fling lands
     * somewhere different every time, and a run that measures the fling
     * measures noise.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.dragTimeline() {
        val index = timelineListIndex()
        onAllNodes(hasScrollAction(), useUnmergedTree = true)[index].performTouchInput {
            val middle = center
            down(Offset(middle.x, middle.y))
            moveBy(Offset(0f, -dragDistance))
            advanceEventTime(500)
            up()
        }
    }

    /**
     * Puts the timeline back at its top.
     *
     * The list keeps its place when the tab is left and entered again, so
     * without this the second run would drag through rows the first run had
     * already passed.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.scrollTimelineToTop() {
        val index = timelineListIndex()
        val list = onAllNodes(hasScrollAction(), useUnmergedTree = true)[index]
        try {
            list.performScrollToIndex(0)
        } catch (_: Throwable) {
            // A list that does not publish its index is dragged back by hand.
            repeat(dragsPerRun) {
                list.performTouchInput {
                    val middle = center
                    down(Offset(middle.x, middle.y))
                    moveBy(Offset(0f, dragDistance))
                    advanceEventTime(500)
                    up()
                }
            }
        }
        waitForIdle()
    }

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
    private suspend fun seedTimeline(koin: Koin) {
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        val repository = koin.get<TaskRepository>()
        val tasks =
            (1..taskCount).map { index ->
                val createdAt = Instant.fromEpochMilliseconds(index * 1_000L)
                testTask(
                    id = TaskId("task-timeline-$index"),
                    title = "Timeline task $index",
                    dueDate = today.plus(index % 30 + 1, DateTimeUnit.DAY),
                    userId = userId,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                )
            }
        val started = System.nanoTime()
        repository.createAll(tasks).getOrThrow()
        println("FRAME_TIME_PROGRESS seeded=${tasks.size} in ${(System.nanoTime() - started) / 1_000_000}ms")
    }

    private fun medianMs(values: List<Long>): Double =
        if (values.isEmpty()) 0.0 else values.sorted()[values.size / 2] / 1_000_000.0

    private fun percentileMs(values: List<Long>, percentile: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * percentile).toInt()
        return sorted[index] / 1_000_000.0
    }

    private fun buildJson(fields: Map<String, Any>): String =
        fields.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            val rendered =
                when (value) {
                    is String -> "\"$value\""
                    is Double -> "%.3f".format(value)
                    else -> value.toString()
                }
            "\"$key\": $rendered"
        }
}
