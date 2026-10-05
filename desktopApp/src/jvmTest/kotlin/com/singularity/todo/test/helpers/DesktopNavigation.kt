package com.singularity.todo.test.helpers

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick

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
    // Await before clicking. `onNodeWithContentDescription` fetches once and
    // throws if the node is not composed *yet*; the hamburger is the first thing
    // the shell draws, so under load it has sometimes not drawn when this runs.
    // The failure reads as "Failed to inject mouse input ... could not find any
    // node that satisfies ContentDescription = 'Menu'", which blames the input
    // injection and the drawer and never mentions timing — measured on
    // `AuthFirstRunScenarioTest` in a 12-minute run, passing in isolation.
    awaitContentDescription(DesktopShell.HAMBURGER).performClick()
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
