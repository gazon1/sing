package com.singularity.todo.testing

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.TaskDraftKeys
import com.singularity.todo.ui.FailureBundleRule
import com.singularity.todo.ui.StepRecorder
import com.singularity.todo.ui.TAB_TIMEOUT_MS
import com.singularity.todo.ui.awaitTag
import com.singularity.todo.ui.awaitUiDevice
import com.singularity.todo.ui.clearStepRecorder
import com.singularity.todo.ui.setStepRecorder
import com.singularity.todo.ui.tapTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.koin.core.context.GlobalContext

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
     * Opens the task editor on an empty draft.
     *
     * The task editor restores the draft it was last given, and the draft
     * outlives a run on the device: an editor opened on a device that still
     * carries the state of an earlier run opens with that text already in the
     * title field. A test that asserts "the editor opens empty" has to make
     * that true first — otherwise it measures the state of the device rather
     * than the behaviour of the editor.
     *
     * The drafts are cleared through the app's own store, read from the app's
     * Koin graph: the instrumentation runs in the app's process, so this is
     * the store the editor restores from, under the same user scope.
     *
     * Only the editor's drafts are cleared — the key the create editor
     * autosaves to, and the prefill keys the agenda and the calendar write
     * for it. The store is deliberately **not** emptied: it shares its storage
     * with the app's settings, so clearing the whole namespace would take the
     * device's other state with it. Clearing the editor's drafts is not the
     * same thing as wiping the device.
     *
     * The app must be running when this is called — the store is read from the
     * app's dependency graph, and there is no graph before the app has
     * started.
     *
     * @return the title input of the editor that was opened.
     */
    protected fun openTaskEditorWithAnEmptyDraft(): UiObject2 {
        clearTaskEditorDrafts()
        device.tapTag(TestTags.TASKS_FAB)
        return device.awaitTag(
            TestTags.TASK_EDITOR_TITLE_INPUT,
            "the task editor's title input",
            TAB_TIMEOUT_MS,
        )
    }

    /**
     * Empties the drafts the task editor restores from.
     *
     * The prefixes are the bare keys of the app's draft namespaces, and they
     * are relative on purpose: the store that resolves in the graph is either
     * the user-scoped wrapper or the bare DataStore store, and the wrapper
     * prepends the user's prefix on the way in. A relative prefix is the only
     * form that names the editor's drafts under both.
     */
    @Suppress("NoRunBlocking") // Instrumented test: the draft store is a suspend API.
    private fun clearTaskEditorDrafts() {
        runBlocking {
            val store = GlobalContext.get().get<DraftStore>()
            EDITOR_DRAFT_KEY_PREFIXES.forEach { prefix -> store.clearPrefix(prefix) }
        }
    }

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

/**
 * The draft keys the task create editor restores from.
 *
 * Four keys, and every one of them a draft the *task editor* consumes: the
 * key it autosaves to, and the three the agenda and the calendar write for it
 * when a task is started inside a section, on several dates, or from the
 * agenda's own date picker.
 *
 * `agendaSection("")` is the prefix every section key starts with — the
 * section id is the tail of it.
 */
private val EDITOR_DRAFT_KEY_PREFIXES =
    listOf(
        TaskDraftKeys.TASK_CREATE,
        TaskDraftKeys.CALENDAR_MULTI_DATE,
        TaskDraftKeys.AGENDA_MULTI_DATE,
        TaskDraftKeys.agendaSection(""),
    )
