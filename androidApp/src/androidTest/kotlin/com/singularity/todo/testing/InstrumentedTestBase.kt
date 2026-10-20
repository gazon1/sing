package com.singularity.todo.testing

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.singularity.todo.ui.FailureBundleRule
import com.singularity.todo.ui.StepRecorder
import com.singularity.todo.ui.awaitUiDevice
import com.singularity.todo.ui.clearStepRecorder
import com.singularity.todo.ui.setStepRecorder
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * The wiring every instrumented test gets: the failure bundle and the step recorder.
 *
 * ## Why a base class and not a rule
 *
 * Both halves of this wiring used to be per-class boilerplate — a `@get:Rule`
 * field, a `@Before` that installed a recorder, an `@After` that cleared it —
 * copied into every UI test class. That is exactly the shape a new class forgets:
 * a test class that does not copy the block runs without a bundle and without a
 * step log, and it fails silently in the sense that matters — the failure is
 * reported, but the evidence that would explain it was never collected.
 *
 * A rule cannot carry the whole of it. The bundle rule needs a device handle,
 * and the device handle is not available while rules are constructed — it is
 * available when the test runs. So the rule takes a *lazy* provider, and the
 * provider has to be wired to something. A base class owns both the lazy
 * provider and the recorder, and the rule becomes a field of it.
 *
 * ## Why the device is not awaited here
 *
 * Two of the instrumented classes do not drive the UI at all: the secure-storage
 * recovery test and the AppTracer test. A base class that awaited a device in
 * its `@Before` would make both of them wait for an accessibility service they
 * never use — a wait that can take seconds on a loaded runner and buys them
 * nothing.
 *
 * So the device is resolved **on first use**, not here: a test that asks for
 * [device] gets it, and a test that never asks for it never waits for one. The
 * bundle asks for it too, but only when a test has already failed, and even
 * then it takes what is already there rather than waiting: a failure bundle
 * that made the failing test slower would be a poor trade.
 *
 * @see com.singularity.todo.ui.FailureBundleRule
 * @see com.singularity.todo.ui.StepRecorder
 */
abstract class InstrumentedTestBase {

    /**
     * The recorder of the test that is running.
     *
     * It is a field of the test instance, and JUnit4 builds a new instance per
     * test method — so the recorder is per test, and the next test in the same
     * class cannot inherit this one's steps.
     *
     * The rule is given this instance directly rather than reading the
     * thread-local at failure time: the `@After` below clears the thread-local
     * *before* the rule runs, so a rule that read the thread-local would find
     * nothing and write an empty `steps.txt`.
     */
    private val recorder = StepRecorder()

    /**
     * Captures the failure bundle when a test in this class fails.
     *
     * It is a rule and not a `try`/`catch` in the base class because a rule
     * sees the failure of the test *and* of the `@Before`/`@After` around it,
     * and because a test that forgot the catch is a test that fails without a
     * bundle.
     */
    @get:Rule
    val diagnostics = FailureBundleRule(device = { deviceOrNull() }, steps = { recorder })

    /**
     * The device the test drives.
     *
     * Obtained on first use: the accessibility service is not bound when the
     * instrumentation starts, so the handle has to be waited for — but only by
     * the tests that actually drive the UI.
     */
    protected val device: UiDevice
        get() = deviceHandle ?: awaitUiDevice().also { deviceHandle = it }

    /** The device handle, once a test has asked for one. */
    private var deviceHandle: UiDevice? = null

    /**
     * The step log of the test that is running, as `steps.txt` would carry it.
     *
     * Exposed so a test can assert on what was recorded — a recorder that is
     * never read is a recorder nobody notices going quiet.
     */
    protected fun recordedSteps(): String = recorder.format()

    /**
     * Installs the recorder before the test body runs.
     *
     * A `@Before` of the base class runs before the `@Before` of the subclass,
     * so a subclass's own setup is recorded too.
     */
    @Before
    fun installStepRecorder() {
        setStepRecorder(recorder)
    }

    /**
     * Removes the recorder after the test body runs.
     *
     * A `@After` of the base class runs *after* the `@After` of the subclass, so
     * the recorder is still there for the subclass's teardown.
     */
    @After
    fun removeStepRecorder() {
        clearStepRecorder()
    }

    /**
     * The device for the failure bundle, without waiting for one.
     *
     * The handle a test already bound is used as it is. When there is none —
     * the test never drove the UI — the bundle asks for one directly, without
     * the retry loop [awaitUiDevice] uses: a bundle that waits for a device
     * adds its wait to the runtime of a test that has already failed.
     */
    private fun deviceOrNull(): UiDevice? =
        deviceHandle
            ?: runCatching {
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            }.getOrNull()
}
