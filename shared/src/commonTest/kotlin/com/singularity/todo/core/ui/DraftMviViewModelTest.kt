@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.test.helpers.VmScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.singularity.todo.test.helpers.testVm as harnessTestVm

private val testLogger = Logger.withTag("DraftMviViewModelTest")

/** Store key for the clear-through-lifecycle helper below. */
private const val DRAFT_MVI_LIFECYCLE_HARNESS_KEY = "draft_mvi_lifecycle_harness"

/** Minimal draft for testing */
@Immutable
private data class TestDraft(val title: String = "")

/**
 * Overrides for the base class's extension points, grouped so the test VM keeps
 * a parameter list detekt accepts.
 */
private class TestHooks(
    val onSaved: (suspend () -> Unit)? = null,
    val onAutosaveError: ((Throwable) -> Unit)? = null,
    val clearAutosave: (suspend () -> Unit)? = null,
)

/** Concrete implementation under test */
private class TestDraftVm(
    initialDraft: TestDraft,
    autosaveBlock: (suspend (TestDraft) -> Unit)?,
    restoreBlock: suspend () -> TestDraft?,
    validateBlock: (TestDraft) -> String?,
    persistBlock: suspend (TestDraft) -> Either<AppError, Unit>,
    private val hooks: TestHooks,
    autosaveDebounceMs: Long = 10L,
    scope: AutoCloseableCoroutineScope,
) : DraftMviViewModel<TestDraft, TestIntent, TestEvent>(
        initialDraft = initialDraft,
        autosave = autosaveBlock ?: {},
        restore = restoreBlock,
        logger = testLogger,
        autosaveDebounceMs = autosaveDebounceMs,
        scope = scope,
    ) {
    private val validateImpl: (TestDraft) -> String? = validateBlock
    private val persistImpl: suspend (TestDraft) -> Either<AppError, Unit> = persistBlock

    override fun validate(draft: TestDraft): String? = validateImpl(draft)

    override suspend fun persist(draft: TestDraft): Either<AppError, Unit> = persistImpl(draft)

    override suspend fun onSaved() {
        // The test VM plays the role of a subclass that reports a finished save
        // (TaskCreateViewModel emits `Saved` here) — so a test can tell an
        // explicit save apart from a silent autosave.
        emit(TestEvent.Saved)
        hooks.onSaved?.invoke()
    }

    override fun onAutosaveError(e: Throwable) {
        hooks.onAutosaveError?.invoke(e)
    }

    override suspend fun clearAutosave() {
        hooks.clearAutosave?.invoke()
    }

    override fun onIntent(intent: TestIntent) { /* no-op for tests */ }
}

/**
 * Clears a ViewModel through the public lifecycle API.
 *
 * `ViewModel.clear()` is `internal` in lifecycle 2.11, so the public teardown path an
 * owner actually drives is the store: `ViewModelStore.clear()` resolves the stored
 * ViewModels and runs their clear — cancelling the `addCloseable` scope — before dropping
 * them. Routing the clear through the store is what makes the teardown tests exercise the
 * real contract rather than poking at a protected member directly.
 */
private fun clearThroughLifecycle(vm: ViewModel) =
    ViewModelStore().apply {
        put(DRAFT_MVI_LIFECYCLE_HARNESS_KEY, vm)
        clear()
    }

private sealed interface TestIntent : MviIntent {
    data class EditTitle(val title: String) : TestIntent

    data object Save : TestIntent

    data object Discard : TestIntent
}

private sealed interface TestEvent : MviEvent {
    data object Saved : TestEvent

    data class Error(val message: String) : TestEvent
}

/**
 * Test helper that uses runTest's backgroundScope as the VM's lifecycle owner.
 * runTest cancels backgroundScope when the test body completes, which terminates
 * the VM's init coroutines (restore collector, autosave loop) cleanly.
 *
 * The VM's scope is a child of that background scope's job — via the shared `testScope`
 * helper — so closing the VM (clearThroughLifecycle) cancels the VM's subtree, not the test's
 * own scope. The shared ownership contract lives on the helper in core/coroutines/testScope.
 */
private fun testVm(block: suspend TestScope.() -> Unit) =
    runTest {
        block()
    }

/**
 * Creates a VM whose lifecycle is owned by runTest's backgroundScope.
 *
 * The scope comes from the shared harness ([harnessTestVm]) with
 * [VmScope.Background]: the autosave collector never ends, so it belongs in the
 * background scope — the same arrangement the repository's other ViewModel
 * tests use.
 *
 * [scope] replaces that scope for the test that drives the ViewModel through
 * its own teardown: the harness builds the very same scope
 * (`testScope(backgroundScope)`), so handing it in changes nothing about the
 * ownership — it only says so at the call site.
 */
private fun TestScope.testVm(
    initialDraft: TestDraft,
    autosaveBlock: (suspend (TestDraft) -> Unit)? = null,
    restoreBlock: suspend () -> TestDraft? = { null },
    validateBlock: (TestDraft) -> String? = { null },
    persistBlock: suspend (TestDraft) -> Either<AppError, Unit> = { Either.Right(Unit) },
    hooks: TestHooks = TestHooks(),
    autosaveDebounceMs: Long = 10L,
    scope: AutoCloseableCoroutineScope? = null,
): TestDraftVm =
    harnessTestVm(
        stateAccessor = { it.stateFlow },
        vmScope = VmScope.Background,
    ) { harnessScope ->
        TestDraftVm(
            initialDraft = initialDraft,
            autosaveBlock = autosaveBlock,
            restoreBlock = restoreBlock,
            validateBlock = validateBlock,
            persistBlock = persistBlock,
            hooks = hooks,
            autosaveDebounceMs = autosaveDebounceMs,
            scope = scope ?: harnessScope,
        )
    }.vm

/**
 * Subscribes to the VM's one-shot events and collects them into a list.
 *
 * The subscription has to be live before the event under test is emitted, so the
 * helper flushes the dispatcher once before returning.
 */
private fun TestScope.collectEvents(vm: TestDraftVm): MutableList<TestEvent> {
    val events = mutableListOf<TestEvent>()
    backgroundScope.launch { vm.events.toList(events) }
    runCurrent()
    return events
}

/**
 * The behavior matrix of the draft editor — one row per guarantee the editor makes,
 * and the test that holds it.
 *
 * The base class owns the contract; the subclasses (TaskCreate, NoteEditor) only
 * override the extension points, and their own rows live next to them:
 * `TaskCreateDraftBehaviorTest`, `TaskCreateTagScopeTest` and `NoteEditorTest`. The
 * rows about the restore live in `DraftMviRestoreTest`, the rows about the async
 * effects in `DraftMviEffectTest`, and the validation row in
 * `DraftMviSaveValidationTest` — all three in this same file.
 *
 * | Row                          | Test                                                       |
 * |------------------------------|------------------------------------------------------------|
 * | initial draft                | `initial state has draft and save disabled when invalid`    |
 * | restore replaces the initial | `DraftMviRestoreTest.restore with persisted draft replaces initial` |
 * | no persisted draft           | `DraftMviRestoreTest.restore when no persisted draft keeps initial` |
 * | restore settles first        | `DraftMviRestoreTest.autosave does not start before the restore settles` |
 * | seed-if-empty restore        | `DraftMviRestoreTest.restore does not overwrite a draft the user edited while it was loading` |
 * | restore-time edit is dropped | `DraftMviRestoreTest.an edit made while the restore was in flight is not persisted afterwards` |
 * | live validation              | `edit enables save when draft becomes valid`               |
 * | debounced autosave           | `a burst of edits autosaves once with the latest value`    |
 * | autosave ≠ checkpoint        | `a successful autosave does not move the save checkpoint`  |
 * | autosave failure             | `a failed autosave reports the failure and keeps the draft dirty` |
 * | autosave stays silent        | `autosave failure does not emit Saved event`               |
 * | explicit save                | `save validates then persists and calls onSaved`           |
 * | explicit save, invalid draft | `DraftMviSaveValidationTest.save does not persist a draft that validation rejects` |
 * | save moves the checkpoint    | `a successful save moves the checkpoint`                   |
 * | save → subclass callback     | `a subclass that edits the draft in onSaved leaves it clean` |
 * | failed save                  | `save surfaces persist failure as error`                   |
 * | save clears the autosave     | `a successful save clears the autosave store and a failed one does not` |
 * | discard → last checkpoint    | `discard resets to baseline`                               |
 * | entity switch                | `switching entities autosaves the new draft, not the old one` |
 * | entity switch, in flight     | `an autosave in flight when the entity switches is superseded by the write of the new entity` |
 * | duplicate save               | `save blocked when already saving`                         |
 * | keyed effect                 | `DraftMviEffectTest.launchDraftEffect cancels prior with same key` |
 * | keyed effects, other keys    | `DraftMviEffectTest.launchDraftEffect with different keys run concurrently` |
 *
 * The subclass rows of the same matrix:
 *
 * | Row                          | Test                                                       |
 * |------------------------------|------------------------------------------------------------|
 * | TaskCreate: save → navigate  | `TaskCreateDraftBehaviorTest.a successful save emits the Saved event` |
 * | TaskCreate: save clears the draft | `TaskCreateDraftBehaviorTest.a successful save clears the persisted draft` |
 * | TaskCreate: discard clears the draft | `TaskCreateDraftBehaviorTest.discard clears the persisted draft` |
 * | TaskCreate: pending autosave after the save | `TaskCreateDraftBehaviorTest.a pending autosave rewrites the draft after the save` |
 * | TaskCreate: pending autosave after the discard | `TaskCreateDraftBehaviorTest.a pending autosave rewrites the draft after the discard` |
 * | TaskCreate: scoped restore   | `TaskCreateDraftBehaviorTest.a draft is restored only into the scope it was saved in` |
 * | TaskCreate: restore into scope | `TaskCreateDraftBehaviorTest.a draft is restored into the scope it was saved in` |
 * | TaskCreate: tag-scoped restore | `TaskCreateTagScopeTest.a draft is restored only into the tag scope it was saved in` |
 * | TaskCreate: restore into tag scope | `TaskCreateTagScopeTest.a draft is restored into the tag scope it was saved in` |
 * | TaskCreate: failed autosave  | `TaskCreateDraftBehaviorTest.a failed autosave leaves the editor usable` |
 * | NoteEditor: autosave failure | `NoteEditorTest.a failed autosave tells the user`          |
 * | NoteEditor: save is a checkpoint | `NoteEditorTest.after an explicit save, closing the editor keeps the saved text` |
 * | NoteEditor: new note is created once | `NoteEditorTest.an explicit save creates a new note exactly once` |
 */
@Tag("fast")
class DraftMviViewModelTest {
    @Test
    fun `initial state has draft and save disabled when invalid`() =
        testVm {
            val vm =
                testVm(
                    initialDraft = TestDraft(title = ""),
                    validateBlock = { if (it.title.isBlank()) "Title required" else null },
                )
            advanceTimeBy(20L)
            runCurrent()
            val state = vm.stateFlow.value
            assertEquals("", state.draft.title)
            assertFalse(state.isSaveEnabled)
            assertFalse(state.isDirty)
            assertFalse(state.isSaving)
            assertNull(state.error)
        }

    @Test
    fun `edit enables save when draft becomes valid`() =
        testVm {
            val vm =
                testVm(
                    initialDraft = TestDraft(title = ""),
                    validateBlock = { if (it.title.isBlank()) "Title required" else null },
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.updateDraft { it.copy(title = "Hello") }
            advanceTimeBy(20L)
            runCurrent()

            val state = vm.stateFlow.value
            assertEquals("Hello", state.draft.title)
            assertTrue(state.isSaveEnabled)
            assertTrue(state.isDirty)
        }

    @Test
    fun `error auto-clears when draft becomes valid`() =
        testVm {
            val vm =
                testVm(
                    initialDraft = TestDraft(title = " "),
                    validateBlock = { if (it.title.isBlank()) "Title required" else null },
                    persistBlock = { Either.Left(AppError.Persistence("db error")) },
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.updateDraft { it.copy(title = "") }
            advanceTimeBy(20L)
            runCurrent()
            assertNotNull(vm.stateFlow.value.error)

            vm.updateDraft { it.copy(title = "Hello") }
            advanceTimeBy(20L)
            runCurrent()
            assertNull(vm.stateFlow.value.error)
        }

    @Test
    fun `save validates then persists and calls onSaved`() =
        testVm {
            val persisted = mutableListOf<TestDraft>()
            var onSavedCalled = false
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                    validateBlock = { if (it.title.isBlank()) "Title required" else null },
                    persistBlock = { draft ->
                        persisted += draft
                        Either.Right(Unit)
                    },
                    hooks = TestHooks(onSaved = { onSavedCalled = true }),
                )
            advanceTimeBy(20L)
            runCurrent()
            assertTrue(vm.stateFlow.value.isSaveEnabled)

            vm.updateDraft { it.copy(title = "Hello there") }
            vm.save()
            advanceTimeBy(50L)
            runCurrent()

            assertEquals(
                listOf(TestDraft(title = "Hello there")),
                persisted.toList(),
                "persist is called once, with the draft the editor holds right now",
            )
            assertTrue(onSavedCalled, "onSaved runs after the persist succeeded")
        }

    @Test
    fun `save blocked when already saving`() =
        testVm {
            var persistCount = 0
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                    persistBlock = {
                        persistCount++
                        advanceTimeBy(100L)
                        runCurrent()
                        Either.Right(Unit)
                    },
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.save()
            vm.save()
            vm.save()
            advanceTimeBy(200L)
            runCurrent()

            assertEquals(1, persistCount)
        }

    @Test
    fun `save surfaces persist failure as error`() =
        testVm {
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                    persistBlock = { Either.Left(AppError.Persistence("db error")) },
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.updateDraft { it.copy(title = "typed") }
            vm.save()
            advanceTimeBy(50L)
            runCurrent()

            assertNotNull(vm.stateFlow.value.error)
            assertFalse(vm.stateFlow.value.isSaving)
            // A refused save is not a checkpoint: the text the user typed is still
            // theirs, and the editor still knows it differs from what is stored.
            assertEquals("typed", vm.stateFlow.value.draft.title, "a refused save must not lose the user's text")
            assertTrue(vm.stateFlow.value.isDirty)
        }

    @Test
    fun `a burst of edits autosaves once with the latest value`() =
        testVm {
            val saved = mutableListOf<TestDraft>()
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "initial"),
                    autosaveBlock = { saved += it },
                    autosaveDebounceMs = 50L,
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.updateDraft { it.copy(title = "a") }
            runCurrent()
            advanceTimeBy(10L)
            vm.updateDraft { it.copy(title = "ab") }
            runCurrent()
            advanceTimeBy(10L)
            vm.updateDraft { it.copy(title = "abc") }
            runCurrent()
            advanceTimeBy(200L)
            runCurrent()

            assertEquals(
                listOf(TestDraft(title = "abc")),
                saved,
                "the debounce window collapses the burst into one write of the latest value",
            )
        }

    /**
     * Autosave is a safety net, not a checkpoint. Only an explicit save moves the
     * checkpoint — otherwise `discard` would revert to a value the user never
     * chose to keep, and `isDirty` would lie about what is stored.
     */
    @Test
    fun `a successful autosave does not move the save checkpoint`() =
        testVm {
            var autosaveCount = 0
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "original"),
                    autosaveBlock = { autosaveCount++ },
                    autosaveDebounceMs = 10L,
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.updateDraft { it.copy(title = "edited") }
            advanceTimeBy(50L)
            runCurrent()
            assertEquals(1, autosaveCount, "the autosave ran")

            assertTrue(
                vm.stateFlow.value.isDirty,
                "an autosaved draft is still dirty — only an explicit save is a checkpoint",
            )
            vm.discard()
            assertEquals(
                "original",
                vm.stateFlow.value.draft.title,
                "discard reverts to the last explicit checkpoint, not to the autosaved value",
            )
        }

    @Test
    fun `a failed autosave reports the failure and keeps the draft dirty`() =
        testVm {
            val failures = mutableListOf<Throwable>()
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "original"),
                    autosaveBlock = { throw IllegalStateException("store is down") },
                    hooks = TestHooks(onAutosaveError = { failures += it }),
                    autosaveDebounceMs = 10L,
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.updateDraft { it.copy(title = "edited") }
            advanceTimeBy(50L)
            runCurrent()

            assertEquals(1, failures.size, "a failed autosave has to reach the editor")
            assertTrue(vm.stateFlow.value.isDirty, "a failed autosave is not a checkpoint")
            vm.discard()
            assertEquals("original", vm.stateFlow.value.draft.title)
        }

    @Test
    fun `a successful save moves the checkpoint`() =
        testVm {
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "original"),
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.updateDraft { it.copy(title = "saved") }
            vm.save()
            advanceTimeBy(50L)
            runCurrent()

            assertFalse(vm.stateFlow.value.isDirty, "a saved draft is not dirty")
            vm.updateDraft { it.copy(title = "written after the save") }
            vm.discard()
            assertEquals(
                "saved",
                vm.stateFlow.value.draft.title,
                "discard reverts to the last explicit save, not to the initial draft",
            )
        }

    /**
     * A subclass may adjust the draft in `onSaved` — NoteEditor clears its own
     * dirty flag there. The checkpoint is taken again afterwards, so the saved
     * draft does not read as dirty and the editor does not offer to discard text
     * it has just written.
     */
    @Test
    fun `a subclass that edits the draft in onSaved leaves it clean`() =
        testVm {
            lateinit var vm: TestDraftVm
            vm =
                testVm(
                    initialDraft = TestDraft(title = "original"),
                    hooks = TestHooks(onSaved = { vm.updateDraft { it.copy(title = "saved-and-normalized") } }),
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.updateDraft { it.copy(title = "typed") }
            vm.save()
            advanceTimeBy(50L)
            runCurrent()

            assertEquals("saved-and-normalized", vm.stateFlow.value.draft.title)
            assertFalse(vm.stateFlow.value.isDirty, "the checkpoint is taken again after onSaved")
        }

    @Test
    fun `a successful save clears the autosave store and a failed one does not`() =
        testVm {
            var cleared = 0
            var refuse = true
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                    persistBlock = {
                        if (refuse) Either.Left(AppError.Persistence("db error")) else Either.Right(Unit)
                    },
                    hooks = TestHooks(clearAutosave = { cleared++ }),
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.save()
            advanceTimeBy(50L)
            runCurrent()
            assertEquals(0, cleared, "a refused save must not drop the stored draft")

            refuse = false
            vm.save()
            advanceTimeBy(50L)
            runCurrent()
            assertEquals(1, cleared, "a successful save drops the stored draft")
        }

    /**
     * Switching to another entity must not leave the old entity's draft behind
     * in the autosave store.
     */
    @Test
    fun `switching entities autosaves the new draft, not the old one`() =
        testVm {
            val saved = mutableListOf<TestDraft>()
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "first"),
                    autosaveBlock = { saved += it },
                    autosaveDebounceMs = 10L,
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.updateDraft { it.copy(title = "first-edited") }
            runCurrent()
            vm.open(TestDraft(title = "second"))
            advanceTimeBy(50L)
            runCurrent()

            assertEquals(
                listOf(TestDraft(title = "second")),
                saved,
                "the pending autosave must not write the entity the editor left",
            )
        }

    /**
     * The second half of the entity-switch guarantee — for an autosave that is
     * already *running* when the editor switches.
     *
     * The kdoc of `open()` promises that the in-flight autosave of the old
     * entity is cancelled. The implementation does not cancel it: the write
     * that is already under way runs to the end, and the new entity's write
     * follows it. This test pins that behaviour as it is — the old write
     * lands, the new one lands after it, and the store ends up on the entity
     * the editor is showing.
     *
     * It pins the behaviour, not the promise: if the cancellation is ever
     * implemented, this test has to change with it. The discrepancy is
     * recorded in issue #3229.
     */
    @Test
    fun `an autosave in flight when the entity switches is superseded by the write of the new entity`() =
        testVm {
            val saved = mutableListOf<TestDraft>()
            val oldWriteStarted = CompletableDeferred<Unit>()
            val releaseOldWrite = CompletableDeferred<Unit>()
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "first"),
                    autosaveBlock = { draft ->
                        oldWriteStarted.complete(Unit)
                        releaseOldWrite.await()
                        saved += draft
                    },
                    autosaveDebounceMs = 10L,
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.updateDraft { it.copy(title = "first-edited") }
            advanceTimeBy(50L)
            runCurrent()
            oldWriteStarted.await()

            vm.open(TestDraft(title = "second"))
            releaseOldWrite.complete(Unit)
            advanceTimeBy(200L)
            runCurrent()

            assertEquals(
                listOf(TestDraft(title = "first-edited"), TestDraft(title = "second")),
                saved.toList(),
                "the running write of the old entity lands, and the new entity's write follows it",
            )
        }

    /**
     * Regression guard for the silent-failure mode this test class could not
     * previously express: `persist` is a `suspend` function that an
     * implementation may satisfy by *throwing* rather than by returning
     * `Either.Left`.
     *
     * `save()` had `try { ... } finally { ... }` with no `catch`, so such a throw
     * cancelled the save coroutine, the `finally` re-enabled the button, and the
     * user saw a save that silently did nothing — no error state, no snackbar,
     * no log. It was found while driving the real desktop editor through Compose
     * UI tests, where the only symptom was a task that never appeared.
     */
    @Test
    fun `save surfaces a thrown persist exception as an error`() =
        testVm {
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                    persistBlock = { throw IllegalStateException("driver exploded") },
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.save()
            advanceTimeBy(50L)
            runCurrent()

            // The failure has to be visible. Before the fix this was null and the
            // coroutine was simply gone.
            assertNotNull(
                vm.stateFlow.value.error,
                "a thrown persist() must surface an error instead of vanishing",
            )
            assertFalse(vm.stateFlow.value.isSaving, "the save button must be re-enabled")
        }

    @Test
    fun `save surfaces a thrown validate exception as an error`() =
        testVm {
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                    validateBlock = { throw IllegalStateException("validator exploded") },
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.save()
            advanceTimeBy(50L)
            runCurrent()

            assertNotNull(vm.stateFlow.value.error)
            assertFalse(vm.stateFlow.value.isSaving)
        }

    @Test
    fun `save surfaces validation error`() =
        testVm {
            var persistCalls = 0
            val vm =
                testVm(
                    initialDraft = TestDraft(title = ""),
                    validateBlock = { "Title required" },
                    persistBlock = {
                        persistCalls++
                        Either.Right(Unit)
                    },
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.save()
            advanceTimeBy(20L)
            runCurrent()

            assertEquals("Title required", vm.stateFlow.value.error)
            assertFalse(vm.stateFlow.value.isSaving)
            assertEquals(0, persistCalls, "a draft rejected by validation is never persisted")
        }

    @Test
    fun `discard resets to baseline`() =
        testVm {
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "original"),
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.updateDraft { it.copy(title = "edited") }
            advanceTimeBy(20L)
            runCurrent()
            assertEquals("edited", vm.stateFlow.value.draft.title)
            assertTrue(vm.stateFlow.value.isDirty)

            vm.discard()
            advanceTimeBy(20L)
            runCurrent()

            assertEquals("original", vm.stateFlow.value.draft.title)
            assertFalse(vm.stateFlow.value.isDirty)
            assertFalse(vm.stateFlow.value.isSaving)
            assertNull(vm.stateFlow.value.error)
        }

    @Test
    fun `open switches entity and resets dirty state`() =
        testVm {
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "first"),
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.updateDraft { it.copy(title = "first-edited") }
            advanceTimeBy(20L)
            runCurrent()

            vm.open(TestDraft(title = "second"))
            advanceTimeBy(20L)
            runCurrent()

            assertEquals("second", vm.stateFlow.value.draft.title)
            assertFalse(vm.stateFlow.value.isDirty)
        }

    @Test
    fun `open with same entity is no-op`() =
        testVm {
            val draft = TestDraft(title = "same")
            val vm =
                testVm(
                    initialDraft = draft,
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.open(draft)
            advanceTimeBy(20L)
            runCurrent()

            assertFalse(vm.stateFlow.value.isDirty)
        }

    @Test
    fun `dismissError clears error`() =
        testVm {
            val vm =
                testVm(
                    initialDraft = TestDraft(title = " "), // non-empty so first edit produces a new value
                    validateBlock = { if (it.title.isBlank()) "Title required" else null },
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.updateDraft { it.copy(title = "") }
            advanceTimeBy(20L)
            runCurrent()
            assertNotNull(vm.stateFlow.value.error)

            vm.dismissError()
            advanceTimeBy(20L)
            runCurrent()

            assertNull(vm.stateFlow.value.error)
        }

    /**
     * The autosave is silent: it never reports a save, not even when it fails.
     * A "Saved" event per keystroke is the spam this contract exists to prevent.
     */
    @Test
    fun `autosave failure does not emit Saved event`() =
        testVm {
            val failures = mutableListOf<Throwable>()
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                    autosaveBlock = { throw IllegalStateException("store is down") },
                    hooks = TestHooks(onAutosaveError = { failures += it }),
                    autosaveDebounceMs = 10L,
                )
            val events = collectEvents(vm)
            advanceTimeBy(20L)
            runCurrent()
            assertTrue(events.isEmpty(), "an idle editor reports nothing")

            vm.updateDraft { it.copy(title = "edited") }
            advanceTimeBy(50L)
            runCurrent()

            assertEquals(1, failures.size, "the failed autosave reaches the editor")
            assertEquals("store is down", failures.single().message, "the editor learns what went wrong")
            assertTrue(events.isEmpty(), "a failed autosave is silent — it never reports a save")

            // The contrast that makes the assertion above meaningful: the same
            // editor does report an explicit save.
            vm.save()
            advanceTimeBy(50L)
            runCurrent()

            assertEquals(listOf(TestEvent.Saved), events.toList(), "only an explicit save reports a save")
        }

    /**
     * The draft VM's teardown has to reach the work its init block launched on the
     * injected scope (#3228): the restore collector and the debounced autosave loop are
     * children of that scope, so clearing the ViewModel has to stop them.
     */
    @Test
    fun `clearing the draft viewmodel stops the autosave loop`() =
        runTest {
            var autosaveCalled = 0
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                    autosaveBlock = { autosaveCalled++ },
                    validateBlock = { null },
                    persistBlock = { Either.Right(Unit) },
                    // The shared helper hangs the VM's job off the background scope's job, so
                    // closing (via clearThroughLifecycle) cancels the autosave loop, not the test.
                    scope = testScope(backgroundScope),
                )
            advanceTimeBy(20L)
            runCurrent()
            assertEquals(0, autosaveCalled, "no autosave before any edit")

            vm.updateDraft { it.copy(title = "edited") }
            advanceTimeBy(50L)
            runCurrent()
            assertEquals(1, autosaveCalled, "the debounce fired once after the edit")

            clearThroughLifecycle(vm)
            vm.updateDraft { it.copy(title = "edited-again") }
            advanceTimeBy(200L)
            runCurrent()

            assertEquals(1, autosaveCalled, "the autosave loop must be dead after the ViewModel was cleared")
        }
}

/**
 * The restore guarantees of the draft editor, in their own class.
 *
 * The restore is the one part of the editor with a race in it: the store
 * answers late, and the user types meanwhile. The tests of that race belong
 * together, and they live apart from `DraftMviViewModelTest` so that the
 * matrix they belong to stays readable.
 */
@Tag("fast")
class DraftMviRestoreTest {
    @Test
    fun `restore with persisted draft replaces initial`() =
        testVm {
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "initial"),
                    restoreBlock = { TestDraft(title = "restored") },
                )
            advanceTimeBy(20L)
            runCurrent()

            assertEquals("restored", vm.stateFlow.value.draft.title)
            assertFalse(vm.stateFlow.value.isDirty)
        }

    @Test
    fun `restore when no persisted draft keeps initial`() =
        testVm {
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "initial"),
                    restoreBlock = { null },
                )
            advanceTimeBy(20L)
            runCurrent()

            assertEquals("initial", vm.stateFlow.value.draft.title)
        }

    /**
     * The restore runs to completion before the autosave loop starts. A restore
     * that raced the first keystroke could lose the draft the user was already
     * writing — or write the restored draft over it.
     *
     * The edit below happens while the restore is still in flight, and it is
     * what makes the assertion mean something: the autosave collector drops the
     * first value it sees, so a collector that started too early would have had
     * nothing to write until the user typed. The typed draft is that something.
     */
    @Test
    fun `autosave does not start before the restore settles`() =
        testVm {
            val restoreGate = CompletableDeferred<TestDraft?>()
            var autosaved = false
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "initial"),
                    restoreBlock = { restoreGate.await() },
                    autosaveBlock = { autosaved = true },
                    autosaveDebounceMs = 10L,
                )
            advanceTimeBy(50L)
            runCurrent()
            assertFalse(restoreGate.isCompleted, "the restore has not answered yet")
            assertFalse(autosaved, "no autosave may run while the restore is in flight")

            // The user types while the store is still answering.
            vm.updateDraft { it.copy(title = "typed") }
            advanceTimeBy(50L)
            runCurrent()
            assertFalse(
                autosaved,
                "the edit must not be written while the restore is in flight — the autosave loop starts only after it",
            )

            restoreGate.complete(TestDraft(title = "restored"))
            advanceTimeBy(20L)
            runCurrent()
            assertTrue(restoreGate.isCompleted, "the restore has answered")
            assertFalse(autosaved, "the current draft is the collector's first value — it is dropped, not written")
            assertEquals(
                "typed",
                vm.stateFlow.value.draft.title,
                "a restore must not overwrite what the user typed while it was loading",
            )

            // The collector is live now: an edit autosaves.
            vm.updateDraft { it.copy(title = "typed-again") }
            advanceTimeBy(50L)
            runCurrent()
            assertTrue(autosaved)
        }

    /**
     * Restore is seed-if-empty: it fills an empty draft, it does not overwrite
     * one the user has already started writing.
     */
    @Test
    fun `restore does not overwrite a draft the user edited while it was loading`() =
        testVm {
            val restoreGate = CompletableDeferred<TestDraft?>()
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "initial"),
                    restoreBlock = { restoreGate.await() },
                )
            advanceTimeBy(20L)
            runCurrent()

            // The user types before the store answers.
            vm.updateDraft { it.copy(title = "typed") }
            restoreGate.complete(TestDraft(title = "restored"))
            advanceTimeBy(20L)
            runCurrent()

            assertEquals(
                "typed",
                vm.stateFlow.value.draft.title,
                "a restore must not overwrite what the user already typed",
            )
        }

    /**
     * An edit made while the restore was in flight is not persisted afterwards.
     *
     * The autosave collector starts only after the restore settles, and it
     * drops the first value it sees. The value it sees first is the edit the
     * user made while the store was still answering — so that edit is the one
     * that is dropped. The editor keeps it, and nothing writes it: the draft
     * stays dirty until an explicit save persists it.
     *
     * The test pins the behavior as it is, not as it ought to be. The gap is
     * reported to the parent issue; the matrix must not promise an autosave
     * the editor does not perform.
     */
    @Test
    fun `an edit made while the restore was in flight is not persisted afterwards`() =
        testVm {
            val restoreGate = CompletableDeferred<TestDraft?>()
            val saved = mutableListOf<TestDraft>()
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "initial"),
                    restoreBlock = { restoreGate.await() },
                    autosaveBlock = { saved += it },
                )
            advanceTimeBy(20L)
            runCurrent()

            // The user types before the store answers.
            vm.updateDraft { it.copy(title = "typed") }
            restoreGate.complete(TestDraft(title = "restored"))
            advanceTimeBy(20L)
            runCurrent()

            // No further edit — only the debounce window passes.
            advanceTimeBy(200L)
            runCurrent()

            assertEquals("typed", vm.stateFlow.value.draft.title, "the editor keeps the edit")
            assertTrue(
                saved.isEmpty(),
                "the edit made during the restore is not written — the collector dropped it as its first value",
            )
            assertTrue(vm.stateFlow.value.isDirty, "the dropped edit leaves the draft unsaved")
        }
}

/**
 * The async-effect rows of the same behavior matrix: `launchDraftEffect` keys
 * its running operations, and a new operation with the same key replaces the
 * one that is already running.
 *
 * The rows live in their own class because the matrix of the draft lifecycle
 * itself does not fit into one file-sized class any more.
 */
@Tag("fast")
class DraftMviEffectTest {
    /**
     * Re-tapping an effect with the same key cancels the operation that is
     * already running. The first operation has to be *running* — an operation
     * cancelled before it started would prove nothing.
     */
    @Test
    fun `launchDraftEffect cancels prior with same key`() =
        testVm {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            var firstFinished = false
            var secondFinished = false
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.launchDraftEffect(
                key = "op",
                operation = {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    firstFinished = true
                    "result1"
                },
                onResult = { _, _ -> null },
            )
            firstStarted.await()

            vm.launchDraftEffect(
                key = "op",
                operation = {
                    secondFinished = true
                    "result2"
                },
                onResult = { _, _ -> null },
            )
            releaseFirst.complete(Unit)
            advanceTimeBy(200L)
            runCurrent()

            assertTrue(firstStarted.isCompleted, "the first operation really started")
            assertFalse(firstFinished, "the running operation with the same key is cancelled")
            assertTrue(secondFinished, "the newest operation runs")
        }

    /**
     * Effects with different keys are not serialized against each other.
     *
     * The proof is the overlap, not the completion: the first operation is held
     * open, and the second one has to finish while the first is still running.
     * A test that only waits for both to end would also pass if the editor
     * ran every effect one after the other.
     */
    @Test
    fun `launchDraftEffect with different keys run concurrently`() =
        testVm {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            var firstFinished = false
            var secondFinished = false
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.launchDraftEffect(
                key = "op1",
                operation = {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    firstFinished = true
                    "r1"
                },
                onResult = { _, _ -> null },
            )
            firstStarted.await()

            vm.launchDraftEffect(
                key = "op2",
                operation = {
                    secondFinished = true
                    "r2"
                },
                onResult = { _, _ -> null },
            )
            advanceTimeBy(50L)
            runCurrent()

            assertTrue(secondFinished, "the operation with another key runs while the first one is still running")
            assertFalse(firstFinished, "the first operation is still held open")

            releaseFirst.complete(Unit)
            advanceTimeBy(50L)
            runCurrent()

            assertTrue(firstFinished, "the held operation finishes once it is released")
        }
}

/**
 * The validation row of the behavior matrix: an explicit save must not
 * persist a draft the editor considers invalid.
 *
 * The row lives in its own class because the matrix of the draft lifecycle
 * itself does not fit into one file-sized class any more.
 */
@Tag("fast")
class DraftMviSaveValidationTest {
    /**
     * The live-validation test proves the *button* is disabled for an invalid
     * draft. This one proves the *save path* refuses it: another caller can
     * ask for a save without asking the button, and the draft must not reach
     * the store.
     */
    @Test
    fun `save does not persist a draft that validation rejects`() =
        testVm {
            val persisted = mutableListOf<TestDraft>()
            var onSavedCalled = false
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                    validateBlock = { if (it.title.isBlank()) "Title required" else null },
                    persistBlock = { draft ->
                        persisted += draft
                        Either.Right(Unit)
                    },
                    hooks = TestHooks(onSaved = { onSavedCalled = true }),
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.updateDraft { it.copy(title = "") }
            vm.save()
            advanceTimeBy(50L)
            runCurrent()

            assertTrue(persisted.isEmpty(), "an invalid draft is never persisted")
            assertFalse(onSavedCalled, "a refused save reports no save")
            assertEquals("Title required", vm.stateFlow.value.error, "the editor says why the save was refused")
            assertFalse(vm.stateFlow.value.isSaveEnabled, "an invalid draft cannot be saved")
            assertFalse(vm.stateFlow.value.isSaving, "the editor is not left in the saving state")
        }
}
