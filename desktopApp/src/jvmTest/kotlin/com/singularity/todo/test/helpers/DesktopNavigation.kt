package com.singularity.todo.test.helpers

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

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

    /** The six destinations behind the drawer's second group. */
    val MENU_ENTRIES = listOf("Statistics", "Notes", "AI Chat", "Search", "Archive", "Settings")
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
fun DesktopComposeUiTest.openDrawer() {
    if (isDrawerOpen()) return
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
fun DesktopComposeUiTest.assertCurrentTab(label: String) {
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
fun DesktopComposeUiTest.goBack() {
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
fun DesktopComposeUiTest.awaitTag(tag: String): SemanticsNodeInteraction {
    val allTags = mutableListOf<String>()
    try {
        waitUntil(
            conditionDescription = "node with testTag '$tag' appears",
            timeoutMillis = TIMEOUT_MS,
        ) {
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
    } catch (_: Throwable) {
        // Tag-explainer fires on timeout
        val explanation = explainMissingTag(allTags.distinct().sorted(), tag)
        throw AssertionError(explanation)
    }
    return onNodeWithTag(tag)
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
): SemanticsNodeInteraction {
    try {
        waitUntil(
            conditionDescription = "node matching '${matcher.description}' appears",
            timeoutMillis = timeoutMs,
        ) {
            onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    } catch (_: Throwable) {
        throw AssertionError(
            "Node matching '${matcher.description}' not found or not unique after ${timeoutMs} ms",
        )
    }
    return onNode(matcher)
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
fun DesktopComposeUiTest.awaitTagGone(tag: String) {
    waitUntil(
        conditionDescription = "no node with testTag '$tag' remains",
        timeoutMillis = TIMEOUT_MS,
    ) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }
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
fun DesktopComposeUiTest.tapTab(label: String) {
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
