package com.singularity.todo.test.helpers

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.singularity.todo.core.ui.TestTags

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
 * Clicks a checkbox with [testTag]. The node must already be in the tree.
 * Use when the test has already waited for the checkbox to appear.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickCheckbox(testTag: String) {
    onNodeWithTag(testTag).performClick()
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
 * Waits for the input with [tag], then replaces its text with [value].
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.typeIntoTag(tag: String, value: String) {
    awaitTag(tag).performTextReplacement(value)
}

/**
 * Waits for the input with [tag], then clears its text.
 *
 * The counterpart of [typeIntoTag] for a field that has to end up empty.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clearTag(tag: String) {
    awaitTag(tag).performTextClearance()
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
 * Waits for the input with [tag], then fires its IME action.
 *
 * A field that submits on the keyboard's "Done" key is driven through this, not through
 * a click on a button that does not exist on that screen.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.submitTag(tag: String) {
    awaitTag(tag).performImeAction()
}

/**
 * Scrolls [tag] into view, then clicks it.
 *
 * `clickTag` asserts the node is already displayed, which is wrong for anything below the
 * fold in a `verticalScroll` screen — and it fails with "not displayed" while the node is
 * present and perfectly clickable. Settings → Calendar is such a screen: the Google panel
 * stacks account, calendar picker, import and sync sections, so the "Sync now" control sits
 * below the viewport on the default 1024x768 test window.
 *
 * [performScrollTo] needs a scrollable ancestor, which these screens have; where it does
 * not, it throws naming that, rather than silently clicking the wrong node.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickTagScrolled(tag: String) {
    awaitTag(tag).performScrollTo().performClick()
}

/**
 * Clicks a sheet's close button.
 *
 * A `ModalBottomSheet` on desktop is composed in two windows — the one it animates into
 * and the one it animates out of — so its buttons are in the tree twice and a
 * single-node selector refuses to choose. Both copies are the same button, and either
 * of them dismisses the sheet.
 *
 * The tag is the sheet's own, because the calendar panel's close button has the same
 * content description and is composed behind the editor.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickSheetClose() {
    val buttons = onAllNodes(hasTestTag(TestTags.EDITOR_SHEET_CLOSE), useUnmergedTree = true)
    check(buttons.fetchSemanticsNodes().isNotEmpty()) { "no sheet close button in the tree" }
    buttons.onFirst().performClick()
}

/** [clickTagScrolled] for a node that only has to be *present*, not pressed. */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.scrollToTag(tag: String) {
    awaitTag(tag).performScrollTo()
}

/**
 * [clickTagScrolled] for a node identified by content description.
 *
 * The navigation drawer is a scrollable column whose last entry (Settings) sits below
 * the fold of the 1024x768 test window. Such a node is *composed*, so `awaitContentDescription`
 * finds it and a plain `performClick` succeeds without error — but the click lands on a
 * coordinate outside the window and the navigation it names never happens. That failure
 * is silent: the next assertion fails somewhere else entirely. Anything addressed by
 * what the user reads must go through here.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickContentDescriptionScrolled(desc: String) {
    awaitContentDescription(desc).performScrollTo().performClick()
}
