package com.singularity.todo.ui

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.singularity.todo.BuildConfig
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import org.koin.core.context.GlobalContext
import java.io.File

/*
 * Waiting for the app's shell, and saying what was on screen when it did not arrive.
 *
 * ## The 8-second budget was the bug
 *
 * Every UI class used to wait like this:
 *
 * ```kotlin
 * device.wait(Until.findObject(By.res("nav_tab_today")), 8_000)
 * ```
 *
 * Two things make that a bad way to wait for a screen:
 *
 * 1. **The budget is wall-clock, but the wait is not.** `UiDevice.wait` spends part
 *    of it inside `waitForIdle` before the first lookup even happens, so an
 *    8-second budget can cover a fraction of that in attempts.
 * 2. **8 seconds is not enough for a cold start on a loaded CI runner.** The Maestro
 *    helper that reaches the same screen already knows this —
 *    `Maestro/helpers/launch-clean.yaml` waits 30 s and says why: *"8 s is not always
 *    enough on a loaded CI runner; 20 s covers cold-start variance including migration
 *    deferred-startup on first launch"*. The instrumented tests kept the old 8 s and
 *    then failed on exactly the runner the Maestro flows had already learned to wait for.
 *
 * The wait here is therefore a deadline loop with its own clock, and the failure says
 * what was on the screen instead of the shell.
 */

/** The bottom-navigation item that opens the Today tab — the shell's first assertion. */
internal const val TODAY_TAB = "nav_tab_today"

/**
 * How long the shell is given to appear.
 *
 * 30 s, the same budget the Maestro helper uses for the same screen, and for the
 * same reason: a cold start on a loaded CI runner is slow, and a test that fails
 * because the runner was busy has measured the runner, not the app.
 */
internal const val SHELL_TIMEOUT_MS = 30_000L

/** How long to wait between lookups. */
private const val POLL_INTERVAL_MS = 250L

/**
 * How long the device handle is given to appear.
 *
 * The accessibility service is enabled as the instrumentation starts, and the
 * first `UiDevice` of a process can be asked for before that binding exists.
 */
internal const val UI_DEVICE_TIMEOUT_MS = 15_000L

/**
 * The device handle, waited for.
 *
 * `UiDevice.getInstance` needs the accessibility service to be bound, and that
 * binding is not there yet when the instrumentation's *first* test asks for it:
 * the service comes up as the test run starts. The first call in a process
 * therefore fails with "UiAutomation not connected", and the failure reads as a
 * broken test rather than as a service that had not come up yet — and it takes
 * the whole UI suite with it, because every later class asks for the same
 * handle in the same broken state.
 *
 * @param timeoutMs how long the handle is given to appear.
 */
internal fun awaitUiDevice(timeoutMs: Long = UI_DEVICE_TIMEOUT_MS): UiDevice {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        try {
            return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        } catch (e: IllegalStateException) {
            if (SystemClock.uptimeMillis() >= deadline) throw e
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
    }
}

/**
 * How long an element the shell already renders is given to appear.
 *
 * The shell is the slow one — it waits for a cold start. A tab that the shell
 * has already drawn is there as soon as the shell is, so this budget only has
 * to cover a tap and the recomposition that follows it.
 */
internal const val TAB_TIMEOUT_MS = 10_000L

/**
 * Waits until the app's shell is on screen and returns the Today tab.
 *
 * @param timeoutMs how long the shell is given to appear.
 * @throws AssertionError when the shell does not appear — the message carries the
 *   screen that was there instead, so the failure names a cause rather than a symptom.
 */
internal fun UiDevice.awaitShell(timeoutMs: Long = SHELL_TIMEOUT_MS): UiObject2 =
    step("awaitShell", TODAY_TAB) {
        awaitObject(By.res(TODAY_TAB), "the app shell (bottom navigation)", timeoutMs, TODAY_TAB)
    }

/**
 * Waits until an object with [by] is on screen and returns it.
 *
 * The deadline is compared **after** every attempt, on purpose. An attempt can
 * block for seconds — `findObject` waits for the app to be idle first — and a
 * budget that was spent inside the first attempt is a budget with no attempts
 * left in it. Checking the deadline afterwards means a slow attempt costs one
 * attempt and not the whole wait, which is the difference between "the app was
 * busy" and "the app was never there".
 *
 * @param by how to find the object.
 * @param what what the object is, in words — it goes into the failure message.
 * @param timeoutMs how long the object is given to appear.
 * @param element the element the step log names, when the caller knows it; the
 *   step log falls back to [what] otherwise.
 * @throws AssertionError when the object does not appear.
 */
internal fun UiDevice.awaitObject(
    by: BySelector,
    what: String,
    timeoutMs: Long = SHELL_TIMEOUT_MS,
    element: String? = null,
): UiObject2 = step("awaitObject", element ?: what) { findWithDeadline(by, what, timeoutMs) }

/**
 * The wait itself, without the step recording.
 *
 * The recording lives in the caller so that the lambda the step helper takes
 * returns the object and not the loop — a `while (true)` as the last expression
 * of a lambda infers `Unit`, and the wait would stop being typed as the object
 * it returns.
 *
 * @param by how to find the object.
 * @param what what the object is, in words — it goes into the failure message.
 * @param timeoutMs how long the object is given to appear.
 * @throws AssertionError when the object does not appear.
 */
internal fun UiDevice.findWithDeadline(
    by: BySelector,
    what: String,
    timeoutMs: Long,
): UiObject2 {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        findObject(by)?.let { return it }
        if (SystemClock.uptimeMillis() >= deadline) {
            throw AssertionError(
                "Expected $what to be on screen within ${timeoutMs}ms, and it never was." +
                    "\n" + diagnostics(),
            )
        }
        SystemClock.sleep(POLL_INTERVAL_MS)
    }
}

/**
 * What was on screen, and what the app thought its state was.
 *
 * The three facts that answer "why is the shell not there":
 *
 * - the accessibility hierarchy — a sign-in screen, a version gate, or a blank window
 *   each look different, and the message says which;
 * - the session — `SignedOut` means the auth bypass did not take effect, `Loading`
 *   means the app is still restoring;
 * - `BuildConfig.TEST_AUTH_BYPASS` — whether the build under test was assembled with
 *   the bypass at all.
 */
internal fun UiDevice.diagnostics(): String = buildString {
    appendLine("auth bypass (BuildConfig.TEST_AUTH_BYPASS): ${BuildConfig.TEST_AUTH_BYPASS}")
    appendLine("session: ${sessionDescription()}")
    appendLine("--- screen ---")
    appendLine(screenDescription())
}

/**
 * The session the app is in, read from the app's own Koin graph.
 *
 * The instrumentation runs in the app's process, so this is the same graph the UI
 * reads. `Loading` means the app has not decided yet, `SignedOut` means it decided
 * to show the sign-in screen, and either of those is a different failure from "the
 * shell was slow".
 */
internal fun sessionDescription(): String =
    runCatching {
        val repository = GlobalContext.getOrNull()?.get<AuthRepository>()
            ?: return "no AuthRepository in the Koin graph"
        when (val session = repository.currentSession.value) {
            Session.Loading -> "Loading"
            Session.SignedOut -> "SignedOut"
            is Session.SignedIn -> "SignedIn(${session.userId})"
            is Session.Anonymous -> "Anonymous(${session.userId})"
        }
    }.getOrElse { "unavailable: ${it.message}" }

/**
 * The visible part of the accessibility tree, one line per node.
 *
 * The raw dump is a wall of XML; what a reader needs is the list of things that were
 * on screen, with the test tags that address them.
 */
private fun UiDevice.screenDescription(): String {
    val dump = runCatching { dumpHierarchy() }.getOrElse { "could not be dumped: ${it.message}" }
    if (dump.isBlank()) return "(the hierarchy was empty)"
    return dump
        .lineSequence()
        .map { line -> line.trim() }
        .filter { line -> line.isNotEmpty() }
        .take(MAX_DUMP_LINES)
        .joinToString("\n")
}

/** How many lines of the screen listing a failure message carries. */
private const val MAX_DUMP_LINES = 40

/**
 * The accessibility hierarchy of the current screen, one node per line.
 *
 * `UiDevice.dumpWindowHierarchy` writes the tree without needing the app to be idle,
 * which is what a failing wait needs: the app is busy by definition.
 */
internal fun UiDevice.dumpHierarchy(): String {
    val target =
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "ui-dump.xml",
        )
    target.delete()
    dumpWindowHierarchy(target)
    val xml = target.readText()
    target.delete()
    return summarize(xml)
}

/**
 * Turns a hierarchy dump into one readable line per node.
 *
 * The dump is the accessibility tree, and every node that carries a test tag
 * surfaces as a resource id — the same ids the tests address. Printing them is
 * what makes the failure answerable without opening the artifact.
 */
private fun summarize(xml: String): String {
    val nodes = mutableListOf<String>()
    NODE.findAll(xml).forEach { match ->
        val attributes = match.value
        val id = attribute(attributes, "resource-id")
        val text = attribute(attributes, "text")
        val description = attribute(attributes, "content-desc")
        val clickable = attribute(attributes, "clickable")
        val label = id.ifEmpty { text }.ifEmpty { description }
        if (label.isNotEmpty()) {
            nodes += "$label" +
                (if (text.isNotEmpty() && text != id) " (\"$text\")" else "") +
                (if (clickable == "true") " [clickable]" else "")
        }
    }
    return nodes.joinToString("\n")
}

/** A node element of the hierarchy dump, with its attributes. */
private val NODE = Regex("<node\\b[^>]*>")

/** The value of [name] inside an attribute list. */
private fun attribute(attributes: String, name: String): String {
    val match = Regex("$name=\"([^\"]*)\"").find(attributes) ?: return ""
    return match.groupValues[1]
}
