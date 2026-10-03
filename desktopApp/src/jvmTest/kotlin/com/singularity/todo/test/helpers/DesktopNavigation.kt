package com.singularity.todo.test.helpers

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement

/** Generous upper bound; real transitions settle in well under a second. */
internal const val TIMEOUT_MS = 5_000L

/**
 * Content descriptions of the desktop shell's own controls.
 *
 * The drawer items and the FAB carry **no** `testTag`, unlike their Android
 * counterparts (`TestTags.navTab(...)` on the bottom bar, `TestTags.TASKS_FAB` on
 * the extended FAB). They do carry a `contentDescription` equal to the label the
 * user sees, which is what these helpers select on. Verified against the
 * semantics dump in `DesktopAppBootTest` — re-run it if a selector here stops
 * matching.
 */
object DesktopShell {
    const val HAMBURGER = "Menu"
    const val BACK = "Back"
    const val FAB_ADD_TASK = "Add task"
    const val FAB_ADD_PROJECT = "Add project"

    /** The six top-level tabs, in drawer order. */
    val TABS = listOf("Inbox", "Today", "Upcoming", "Plans", "Pomodoro", "Calendar")

    /** The seven destinations behind the drawer's second group. */
    val MENU_ENTRIES = listOf("Statistics", "Notes", "AI Chat", "Search", "Archive", "Profiles", "Settings")
}

/**
 * A drawer entry, matched by the label the user reads plus the `Role.Tab`
 * semantics the `NavigationDrawerItem` applies.
 *
 * The role qualifier matters because several entries share a content description
 * with the screen that just navigated there — the top bar title is also
 * `Text = '[Today]'` — and without the role the selector can match the wrong node.
 */
private fun drawerEntry(label: String): SemanticsMatcher =
    hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)

/** True once the drawer sheet has slid in — its first entry is on-screen. */
@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.isDrawerOpen(): Boolean =
    onAllNodes(drawerEntry(DesktopShell.TABS.first()))
        .fetchSemanticsNodes()
        .any { it.boundsInRoot.right > 0 }

/**
 * Opens the navigation drawer and returns once its entries are on screen.
 *
 * Idempotent: a blind click on the hamburger would *close* an already-open
 * drawer, so the current state is checked first. The wait is not cosmetic —
 * while closed the sheet sits at negative x-coordinates, and clicking an
 * off-screen entry does not reach it.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.openDrawer() = step("openDrawer") {
    if (isDrawerOpen()) return@step
    onNodeWithContentDescription(DesktopShell.HAMBURGER).performClick()
    waitUntil(
        conditionDescription = "drawer entry '${DesktopShell.TABS.first()}' slides into view",
        timeoutMillis = TIMEOUT_MS,
    ) { isDrawerOpen() }
}

/**
 * Asserts that [label] is the drawer's currently selected destination.
 *
 * The top-bar title is the obvious way to check this and is a trap: on a fresh
 * database the boot screen matches the title text three times over — the agenda
 * section header, the top-bar title, and the (off-screen) drawer entry — so
 * `onNodeWithText(title)` fails on ambiguity. The drawer entry's `Selected`
 * semantics is unambiguous and is exactly what the shell itself uses to
 * highlight the current tab.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertCurrentTab(label: String) = step("assertCurrentTab", label) {
    onNode(drawerEntry(label))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
}

/**
 * Pops the shell's navigation stack by clicking its back button.
 *
 * The shell's leading top-bar control is a hamburger or a back arrow depending on
 * `canGoBack` — its contentDescription is [DesktopShell.HAMBURGER] or
 * [DesktopShell.BACK] respectively. So after pushing a screen (a task editor, a
 * project detail) the drawer cannot be opened from that control until the push is
 * popped, and [openDrawer] would fail to find the hamburger.
 *
 * Saving a task or a note does **not** pop: the editor stays open with its save
 * button still on screen, so a flow has to leave explicitly before it can change
 * tab.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.goBack() = step("goBack") {
    onNodeWithContentDescription(DesktopShell.BACK).performClick()
    waitUntil(
        conditionDescription = "shell returns to a drawer-openable tab",
        timeoutMillis = TIMEOUT_MS,
    ) {
        onAllNodes(hasContentDescription(DesktopShell.HAMBURGER))
            .fetchSemanticsNodes()
            .isNotEmpty()
    }
    waitForIdle()
}

/**
 * Waits until a node with [tag] exists, then returns a handle to it.
 *
 * Saving is asynchronous — the editor writes through a repository scope and the
 * list re-emits from a Room flow — so a click issued straight after a save
 * button lands before the row is in the tree and fails with "could not find any
 * node". Use this instead of a bare `onNodeWithTag` after any write.
 *
 * On timeout, the error message includes a listing of the nearest available tags
 * via [explainMissingTag], so the failure is actionable without consulting the
 * full semantics dump.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitTag(tag: String): SemanticsNodeInteraction = step("awaitTag", tag) {
    val allTags = mutableListOf<String>()
    var pollCount = 0
    val startTime = System.currentTimeMillis()
    try {
        waitUntil(
            conditionDescription = "node with testTag '$tag' appears",
            timeoutMillis = TIMEOUT_MS,
        ) {
            pollCount++
            val found = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            if (!found) {
                // Collect all nodes on every poll cycle so the list is fresh when
                // the timeout fires.
                onAllNodesWithTag("*").fetchSemanticsNodes().forEach { node ->
                    TAG_PATTERN.findAll(node.toString()).forEach { match ->
                        allTags.add(match.value.removePrefix("testTag="))
                    }
                }
            }
            found
        }
    } catch (e: Throwable) {
        // Tag-explainer fires on timeout
        val explanation = explainMissingTag(allTags.distinct().sorted(), tag)
        val elapsed = System.currentTimeMillis() - startTime
        val msg = buildString {
            appendLine(explanation)
            appendLine("Poll count: $pollCount. Elapsed: ${elapsed}ms. Timeout: ${TIMEOUT_MS}ms.")
            if (e.message != null) appendLine("Last exception: ${e::class.simpleName}: ${e.message}")
        }
        throw AssertionError(msg).also { it.addSuppressed(e) }
    }
    onNodeWithTag(tag)
}

/**
 * Waits until at least one node with [tag] is actually on screen, then returns.
 *
 * For a node that exists in exactly one copy, `awaitTag` is enough. This is for
 * pagers and lists that compose the same tag several times, some of them off
 * screen: HorizontalPager keeps the neighbouring pages composed, so
 * `onAllNodesWithTag(tag)[0]` is composition order, not what the user sees, and
 * the ordering can flip with the calendar date (a month-edge day pads into the
 * neighbouring page). "Some matching node is visible" is the assertion the test
 * means; an index is an implementation detail.
 *
 * Visibility is a non-empty intersection of the node's bounds with the root's —
 * the same thing `assertIsDisplayed` checks, applied per node instead of to one
 * indexed pick.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitAnyDisplayed(tag: String) = step("awaitAnyDisplayed", tag) {
    val rootBounds = onRoot(useUnmergedTree = false).fetchSemanticsNode().boundsInRoot
    waitUntil(
        conditionDescription = "some node with testTag '$tag' is on screen",
        timeoutMillis = TIMEOUT_MS,
    ) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().any { node ->
            val b = node.boundsInRoot
            b.width > 0f && b.height > 0f &&
                b.left < rootBounds.right && b.right > rootBounds.left &&
                b.top < rootBounds.bottom && b.bottom > rootBounds.top
        }
    }
}

/**
 * Waits until a node matching [matcher] exists, then returns a handle to it.
 *
 * Use this overload when the selector is not a testTag (e.g. `hasContentDescription(...)`,
 * `hasText(...)`, `hasAnyAncestor(...)`). For testTag-based selectors prefer the
 * string overload — it provides better error messages via [explainMissingTag].
 *
 * @param matcher The semantics matcher to wait for.
 * @param timeoutMs Overrides the default [TIMEOUT_MS]. Pass [TIMEOUT_MS] to use
 *                  the shared constant.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitTag(
    matcher: SemanticsMatcher,
    timeoutMs: Long = TIMEOUT_MS,
): SemanticsNodeInteraction = step("awaitTag(matcher)", matcher.description) {
    try {
        waitUntil(
            conditionDescription = "node matching '${matcher.description}' appears",
            timeoutMillis = timeoutMs,
        ) {
            onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    } catch (e: Throwable) {
        throw AssertionError(
            "Node matching '${matcher.description}' not found or not unique after ${timeoutMs} ms",
        ).also { it.addSuppressed(e) }
    }
    onNode(matcher)
}

/**
 * Waits until no node with [tag] exists, then returns — the counterpart to
 * [awaitTag] for asserting that something is *gone*.
 *
 * `assertDoesNotExist` checks once, right after Compose's auto-sync, which is
 * not the same as "it is gone": navigation commits asynchronously (the outgoing
 * screen stays composed until the incoming one's state resolves), so a one-shot
 * check races the transition and fails intermittently under machine load. Use
 * this whenever the thing being asserted absent is disappearing *because of*
 * something the test just did.
 *
 * Do not use it to assert absence of something that was never there — that is a
 * plain `assertDoesNotExist` and needs no waiting.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitTagGone(tag: String) = step("awaitTagGone", tag) {
    waitUntil(
        conditionDescription = "no node with testTag '$tag' remains",
        timeoutMillis = TIMEOUT_MS,
    ) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }
}

/**
 * Waits for a node with [text] to appear, then returns a handle to it.
 * Uses `hasText()` semantics matcher — matches any node whose semantics text
 * includes the given string.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitText(text: String) = step("awaitText", text) {
    waitUntil(
        conditionDescription = "node with text '$text' appears",
        timeoutMillis = TIMEOUT_MS,
    ) {
        onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithText(text, useUnmergedTree = true)
}

/**
 * Asserts that a node with [text] is displayed. Use after [awaitText] or standalone
 * when the node is expected to already exist.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTextDisplayed(text: String) {
    onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
}

/**
 * Waits for a node with contentDescription [desc] to appear, then returns a handle.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitContentDescription(desc: String) = step("awaitContentDescription", desc) {
    waitUntil(
        conditionDescription = "node with contentDescription '$desc' appears",
        timeoutMillis = TIMEOUT_MS,
    ) {
        onAllNodes(hasContentDescription(desc)).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithContentDescription(desc)
}

/**
 * Clicks a node with contentDescription [desc]. Waits for it to appear first.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickContentDescription(desc: String) {
    awaitContentDescription(desc).performClick()
}

/**
 * Clicks a node with text [text]. Waits for it to appear first.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickText(text: String) {
    awaitText(text).performClick()
}

/**
 * Returns the count of nodes with [tag] in the current semantics tree.
 * Does not wait — captures the count at call time.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.countNodes(tag: String): Int =
    onAllNodesWithTag(tag).fetchSemanticsNodes().size

/**
 * Clicks a checkbox with [testTag]. The node must already be in the tree.
 * Use when the test has already waited for the checkbox to appear.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickCheckbox(testTag: String) {
    onNodeWithTag(testTag).performClick()
}

/**
 * Waits until the checkbox with [testTag] reports ToggleableState.On.
 * Use after clickCheckbox to assert the state change landed.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitCheckboxChecked(testTag: String) = step("awaitCheckboxChecked", testTag) {
    waitUntil(
        conditionDescription = "checkbox '$testTag' is checked",
        timeoutMillis = TIMEOUT_MS,
    ) {
        onAllNodesWithTag(testTag)
            .fetchSemanticsNodes()
            .any { node ->
                val state = node.config.getOrNull(SemanticsProperties.ToggleableState)
                state == androidx.compose.ui.state.ToggleableState.On
            }
    }
}

/**
 * Waits for a node with [tag] and clicks it — the click-and-wait counterpart
 * of [awaitTag] for interactions.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickTag(tag: String) {
    awaitTag(tag).performClick()
}

/**
 * Waits for a node with [tag] and asserts it is displayed.
 * The wait matters: the node may still be composing when the test reaches it.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagDisplayed(tag: String) {
    awaitTag(tag).assertIsDisplayed()
}

/**
 * One-shot assertion that no node with [tag] exists. Use for things that were
 * never in the tree; for things that just disappeared, use [awaitTagGone].
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagNotExists(tag: String) {
    onNodeWithTag(tag).assertDoesNotExist()
}

/**
 * One-shot assertion that no node with [text] exists. Same rule as
 * [assertTagNotExists]: only for nodes that were never there.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTextNotExists(text: String) {
    onNodeWithText(text).assertDoesNotExist()
}

/**
 * Waits for the input with [tag], then replaces its text with [value].
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.typeIntoTag(tag: String, value: String) {
    awaitTag(tag).performTextReplacement(value)
}

/**
 * Waits for the input with [tag], clears it, then types [value].
 * Use for fields that arrive with seeded content.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clearAndTypeIntoTag(tag: String, value: String) {
    awaitTag(tag).performTextClearance()
    onNodeWithTag(tag).performTextInput(value)
}

/**
 * Waits for a node with [desc] content description and asserts it is displayed.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertContentDescriptionDisplayed(desc: String) {
    awaitContentDescription(desc).assertIsDisplayed()
}

/**
 * One-shot assertion that no node with contentDescription [desc] exists.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertContentDescriptionNotExists(desc: String) {
    onNodeWithContentDescription(desc).assertDoesNotExist()
}

/**
 * Waits for the node with [tag] and asserts it is enabled.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagEnabled(tag: String) {
    awaitTag(tag).assertIsEnabled()
}

/**
 * Waits for the node with [tag] and asserts it is NOT enabled.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagNotEnabled(tag: String) {
    awaitTag(tag).assertIsNotEnabled()
}

/**
 * Waits for the node with [tag] and asserts its text equals [expected].
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagTextEquals(tag: String, expected: String) {
    awaitTag(tag).assertTextEquals(expected)
}

/**
 * One-shot assertion that a node with [tag] exists (displayed or not).
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagExists(tag: String) {
    onNodeWithTag(tag).assertExists()
}

private val TAG_PATTERN = Regex("""testTag=[^\s,\]]+""")

/**
 * Opens the drawer (if still closed), activates [label], and waits for the sheet
 * to close again.
 *
 * [label] is matched against the drawer's content description, so it is the same
 * string the user reads: a tab from [DesktopShell.TABS] or a destination from
 * [DesktopShell.MENU_ENTRIES].
 *
 * Do not assert on the drawer entry afterwards to prove the navigation happened —
 * the sheet is off-screen again by then, so `assertIsDisplayed` would fail on a
 * correct app. Assert on content the destination itself renders.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.tapTab(label: String) = step("tapTab", label) {
    openDrawer()
    onNode(drawerEntry(label)).performClick()
    waitUntil(
        conditionDescription = "drawer closes after selecting '$label'",
        timeoutMillis = TIMEOUT_MS,
    ) { !isDrawerOpen() }
    waitForIdle()
}

/**
 * Produces a human-readable explanation of why a `testTag` was not found in the
 * current semantics tree, and what tags are available.
 *
 * Used to enrich [AssertionError] messages from [awaitTag] so that a missing tag
 * failure names the nearest available alternative rather than just the one that
 * was not found.
 *
 * Pure: no Compose state, no I/O, fully deterministic.
 *
 * @param availableTags All `testTag` values present in the current semantics tree.
 * @param wantedTag     The tag the test was looking for.
 */
fun explainMissingTag(availableTags: List<String>, wantedTag: String): String {
    val nearby = availableTags
        .filter { it.contains(wantedTag.take(4), ignoreCase = true) }
        .take(3)
    return buildString {
        appendLine("Tag '$wantedTag' is not in the semantics tree.")
        if (nearby.isNotEmpty()) {
            appendLine("Nearby tags: ${nearby.joinToString { "'$it'" }}")
        }
        appendLine("All available tags (${availableTags.size} total):")
        availableTags.take(20).forEach { appendLine("  $it") }
        if (availableTags.size > 20) appendLine("  ... and ${availableTags.size - 20} more")
    }
}
