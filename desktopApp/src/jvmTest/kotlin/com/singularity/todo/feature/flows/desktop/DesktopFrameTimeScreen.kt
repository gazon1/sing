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

/*
 * The screen the frame-time baseline drives.
 *
 * The helpers live next to each other and not inside the test, because the
 * test itself is a measurement and the measurement is not a screen: the
 * helpers are what the screen is made of.
 */

/** The prefix of the tag of a row of the timeline. */
internal const val TASK_ROW_PREFIX = "task_item_"

/** The prefix of the tag of a card of the project list. */
internal const val PROJECT_CARD_PREFIX = "project_card_"

/** How long a screen has to appear before the run is declared failed. */
internal const val SCREEN_TIMEOUT_MS = 15_000L

/** How many gestures one run makes. */
internal const val GESTURES_PER_RUN = 10

/** How far one drag travels, in pixels. */
internal const val GESTURE_DISTANCE = 400f

// ─── the screen ───────────────────────────────────────────────────────────

/** The rows of the timeline, by their tags. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.taskRows(): List<String> = tagsWithPrefix(TASK_ROW_PREFIX)

/** The cards of the project list, by their tags. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.projectCards(): List<String> = tagsWithPrefix(PROJECT_CARD_PREFIX)

/** Every test tag on the screen, for a failure that has to explain itself. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.allTestTags(): List<String> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag))
        .fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        .distinct()

/** The tags on the screen that start with [prefix]. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.tagsWithPrefix(prefix: String): List<String> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        .filter { it.startsWith(prefix) }
        .distinct()

/** Whether a node with the tag is on the screen. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.tagExists(tag: String): Boolean =
    onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

/**
 * The text of the node with the tag.
 *
 * The label of a row on the desktop is folded into the clickable card, so the
 * text lives in the card's merged node - in the unmerged tree the card holds
 * no text of its own, only a child that holds it.
 */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.textOf(tag: String): String =
    onAllNodes(hasTestTag(tag))
        .fetchSemanticsNodes()
        .mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        }.firstOrNull { it.isNotBlank() }
        .orEmpty()

/**
 * Waits until the screen is really there.
 *
 * The navigation into a screen is asynchronous: the click that opens it
 * returns before the screen is drawn. A check that runs right after the
 * click reads a screen that has not arrived yet, and the run fails on a
 * race that has nothing to do with the measurement.
 */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.awaitScreen(
    description: String,
    condition: () -> Boolean,
) {
    waitUntil(conditionDescription = description, timeoutMillis = SCREEN_TIMEOUT_MS) { condition() }
}

/** The month the calendar shows. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.monthTitle(): String =
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
internal fun DesktopComposeUiTest.scrollableHolding(prefix: String): Int {
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

/**
 * Empties the search of the project list.
 *
 * The search belongs to the list, not to the run: a scenario that searched for
 * one project leaves the list showing that one project, and the list the next
 * scenario measures is not the list of the dataset any more. The field clears
 * itself - the list draws a clear button as soon as the field holds something
 * - so the button is what the run presses.
 */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.clearProjectSearch() {
    if (!tagExists(TestTags.PROJECTS_SEARCH_INPUT)) return
    val clear = onAllNodes(hasContentDescription("Clear search"), useUnmergedTree = true).fetchSemanticsNodes()
    if (clear.isEmpty()) return
    onAllNodes(hasContentDescription("Clear search")).onFirst().performClick()
    waitForIdle()
}

/** The window the app is drawn in. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.mainRoot(): SemanticsNode =
    onAllNodes(isRoot()).fetchSemanticsNodes().maxByOrNull { it.size.width * it.size.height }
        ?: error("the app has no window")

/** Whether a row sits inside a node's bounds. */
internal fun SemanticsNode.inside(node: SemanticsNode): Boolean =
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
internal fun DesktopComposeUiTest.dragHolding(prefix: String) {
    val index = scrollableHolding(prefix)
    onAllNodes(hasScrollAction(), useUnmergedTree = true)[index].performTouchInput {
        val middle = center
        down(Offset(middle.x, middle.y))
        moveBy(Offset(0f, -GESTURE_DISTANCE))
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
internal fun DesktopComposeUiTest.scrollToTopOf(prefix: String) {
    val index = scrollableHolding(prefix)
    val list = onAllNodes(hasScrollAction(), useUnmergedTree = true)[index]
    try {
        list.performScrollToIndex(0)
    } catch (_: Throwable) {
        // A list that does not publish its index is dragged back by hand.
        repeat(GESTURES_PER_RUN) {
            list.performTouchInput {
                val middle = center
                down(Offset(middle.x, middle.y))
                moveBy(Offset(0f, GESTURE_DISTANCE))
                advanceEventTime(500)
                up()
            }
        }
    }
    waitForIdle()
}
