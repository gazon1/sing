package com.singularity.todo.test.helpers

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement

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
 * Waits for the input with [tag], clears it, then types [value].
 * Use for fields that arrive with seeded content.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clearAndTypeIntoTag(tag: String, value: String) {
    awaitTag(tag).performTextClearance()
    onNodeWithTag(tag).performTextInput(value)
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

/** [clickTagScrolled] for a node that only has to be *present*, not pressed. */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.scrollToTag(tag: String) {
    awaitTag(tag).performScrollTo()
}
