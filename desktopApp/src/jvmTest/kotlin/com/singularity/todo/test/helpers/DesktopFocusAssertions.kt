package com.singularity.todo.test.helpers

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused

/**
 * Waits for the node with [tag] to hold the focus, and fails with the timeout if
 * it never does.
 *
 * A field that asks for the focus when its screen appears and one that does not
 * look the same until somebody types: the difference is the `Focused` semantics,
 * and this is the assertion that reads it. A screen that stopped asking fails
 * here rather than silently costing the user a tap on the field.
 *
 * The wait is a poll, not a one-shot read: a focus request is applied by the
 * composition a frame after the node appears, so reading it straight after
 * [awaitTag] would race the very thing being asserted.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagFocused(tag: String) {
    awaitTag(tag)
    waitUntil(
        conditionDescription = "node with testTag '$tag' holds the focus",
        timeoutMillis = TIMEOUT_MS,
    ) {
        onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
    }
}

/**
 * Waits for the node with [tag] to stop holding the focus.
 *
 * The counterpart of [assertTagFocused]: a screen that cleared the focus says so
 * in the field's `Focused` semantics, and the wait is a poll because clearing the
 * focus is applied by the composition a frame after it is asked for.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagNotFocused(tag: String) {
    awaitTag(tag)
    waitUntil(
        conditionDescription = "node with testTag '$tag' no longer holds the focus",
        timeoutMillis = TIMEOUT_MS,
    ) {
        onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isEmpty()
    }
}
