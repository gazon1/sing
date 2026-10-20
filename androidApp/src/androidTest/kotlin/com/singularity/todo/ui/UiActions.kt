package com.singularity.todo.ui

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/*
 * Actions that record themselves.
 *
 * ## The gap this file closes
 *
 * The step log used to record waits only: it said where the test *was*, and
 * not what it *did*. A failure in the middle of the task-creation flow read
 * "waited for task_editor_title_input" and left the reader to guess whether
 * the FAB had been tapped, whether the title had been typed, and whether the
 * save button had been pressed.
 *
 * An action is the part of a test that actually causes damage, so it is the
 * part worth writing down. Every helper here records the element it touched,
 * the action it performed, and whether the action worked — in the same line
 * format the desktop recorder uses (`desktopApp/src/jvmTest/.../helpers/
 * StepRecorder.kt`), because a reader who has learned one format should not
 * have to learn it twice.
 *
 * ```
 * +0.00s  awaitTag(nav_tab_today)                  OK    1.35s
 * +1.35s  tap(tasks_fab)                            OK    0.12s
 * +1.47s  awaitTag(task_editor_title_input)         OK    0.08s
 * +1.55s  typeText(task_editor_title_input)         OK    0.05s
 * +2.10s  tap(task_editor_save)                     OK    0.09s
 * ```
 *
 * ## Why the helpers are extensions and not calls to remember
 *
 * A recorder that every test has to opt into is a recorder that half the
 * tests forget. So the calls read like the waits do — `device.tapTag(...)`
 * next to `device.awaitTag(...)` — and the recording is not a separate thing
 * the test does, it is what the call *is*.
 */

/**
 * Waits for the element with the test-tag [tag] and taps it.
 *
 * The wait and the tap are two recorded steps: a test that failed on a missing
 * element and a test that failed on an element that did not react are
 * different failures, and one line each says which it was.
 *
 * @param tag the test tag, which reaches UiAutomator as a resource id.
 * @param timeoutMs how long the element is given to appear.
 * @return the element that was tapped.
 * @throws AssertionError when the element does not appear.
 */
internal fun UiDevice.tapTag(
    tag: String,
    timeoutMs: Long = TAB_TIMEOUT_MS,
): UiObject2 {
    val element = awaitTag(tag, "the $tag element", timeoutMs)
    element.tap(tag)
    return element
}

/**
 * Waits for the field with the test-tag [tag] and types [text] into it.
 *
 * @param tag the test tag of the field, which reaches UiAutomator as a resource id.
 * @param text the text to type.
 * @param timeoutMs how long the field is given to appear.
 * @return the field that was typed into.
 * @throws AssertionError when the field does not appear.
 */
internal fun UiDevice.typeInto(
    tag: String,
    text: String,
    timeoutMs: Long = TAB_TIMEOUT_MS,
): UiObject2 {
    val field = awaitTag(tag, "the $tag field", timeoutMs)
    field.typeText(text, "$tag=\"$text\"")
    return field
}

/**
 * Presses the system back button.
 *
 * Not named `pressBack`: `UiDevice` already has that member, and a member
 * wins over an extension — the call would reach the member and record
 * nothing.
 *
 * @return whether the device handled the press.
 */
internal fun UiDevice.tapBack(): Boolean = step("pressBack", null) { pressBack() }

/**
 * Waits for the element with the test-tag [tag].
 *
 * @param tag the test tag, which reaches UiAutomator as a resource id.
 * @param what the element in words, for the failure message.
 * @param timeoutMs how long the element is given to appear.
 * @return the element.
 * @throws AssertionError when the element does not appear.
 */
internal fun UiDevice.awaitTag(
    tag: String,
    what: String,
    timeoutMs: Long = SHELL_TIMEOUT_MS,
): UiObject2 = step("awaitTag", tag) { findWithDeadline(By.res(tag), what, timeoutMs) }

/**
 * Waits for the element with the test-tag [tag] and returns null when it does
 * not arrive.
 *
 * The difference from [awaitTag] is what the step log says: a wait that timed
 * out is a failed step even though nothing threw, so it is recorded as one
 * instead of as a successful wait for nothing.
 *
 * @param tag the test tag, which reaches UiAutomator as a resource id.
 * @param timeoutMs how long the element is given to appear.
 * @return the element, or null when it did not arrive in time.
 */
internal fun UiDevice.awaitTagOrNull(
    tag: String,
    timeoutMs: Long = TAB_TIMEOUT_MS,
): UiObject2? = stepOrFail("awaitTag", tag) { wait(Until.findObject(By.res(tag)), timeoutMs) }

/**
 * Taps this element and records the tap.
 *
 * `UiObject2.click` returns nothing in UiAutomator 2.3, so neither does this:
 * what the tap did is in the screen, and what it *was* is in the step log.
 *
 * @param label what to write in the step log; the element's own resource name
 *   when it is not given.
 */
internal fun UiObject2.tap(label: String? = null) {
    step("tap", label ?: elementLabel()) { click() }
}

/**
 * Types [text] into this element and records the typing.
 *
 * @param text the text to type.
 * @param label what to write in the step log; the element's own resource name
 *   when it is not given.
 */
internal fun UiObject2.typeText(
    text: String,
    label: String? = null,
) {
    step("typeText", label ?: elementLabel()) { setText(text) }
}

/**
 * What to write in the step log for an element nobody named.
 *
 * The resource name is the test tag, which is what the test itself talks
 * about; the text is the fallback for an element that has no tag, and
 * "unknown" is the last resort — an unnamed line in a log is worse than a
 * vague one, because it cannot be looked up.
 */
private fun UiObject2.elementLabel(): String = resourceName ?: text ?: "unknown element"
