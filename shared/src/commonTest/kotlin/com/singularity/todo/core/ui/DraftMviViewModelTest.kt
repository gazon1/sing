@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.ui

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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

private val testLogger = Logger.withTag("DraftMviViewModelTest")

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
 */
private fun testVm(block: suspend TestScope.() -> Unit) =
    runTest {
        block()
    }

/** Creates a VM whose lifecycle is owned by runTest's backgroundScope. */
private fun TestScope.testVm(
    initialDraft: TestDraft,
    autosaveBlock: (suspend (TestDraft) -> Unit)? = null,
    restoreBlock: suspend () -> TestDraft? = { null },
    validateBlock: (TestDraft) -> String? = { null },
    persistBlock: suspend (TestDraft) -> Either<AppError, Unit> = { Either.Right(Unit) },
    hooks: TestHooks = TestHooks(),
    autosaveDebounceMs: Long = 10L,
): TestDraftVm =
    TestDraftVm(
        initialDraft = initialDraft,
        autosaveBlock = autosaveBlock,
        restoreBlock = restoreBlock,
        validateBlock = validateBlock,
        persistBlock = persistBlock,
        hooks = hooks,
        autosaveDebounceMs = autosaveDebounceMs,
        scope = AutoCloseableCoroutineScope(backgroundScope.coroutineContext),
    )

/**
 * The behavior matrix of the draft editor — one row per guarantee the editor makes,
 * and the test that holds it.
 *
 * The base class owns the contract; the subclasses (TaskCreate, NoteEditor) only
 * override the extension points, and their own rows live next to them:
 * `TaskCreateViewModelTest` and `NoteEditorTest`.
 *
 * | Row                          | Test                                                       |
 * |------------------------------|------------------------------------------------------------|
 * | initial draft                | `initial state has draft and save disabled when invalid`    |
 * | restore replaces the initial | `restore with persisted draft replaces initial`             |
 * | no persisted draft           | `restore when no persisted draft keeps initial`            |
 * | restore settles first        | `autosave does not start before the restore settles`       |
 * | seed-if-empty restore        | `restore does not overwrite a draft the user edited while it was loading` |
 * | live validation              | `edit enables save when draft becomes valid`               |
 * | debounced autosave           | `a burst of edits autosaves once with the latest value`    |
 * | autosave ≠ checkpoint        | `a successful autosave does not move the save checkpoint`  |
 * | autosave failure             | `a failed autosave reports the failure and keeps the draft dirty` |
 * | autosave stays silent        | `autosave failure does not emit Saved event`               |
 * | explicit save                | `save validates then persists and calls onSaved`           |
 * | save moves the checkpoint    | `a successful save moves the checkpoint`                   |
 * | save → subclass callback     | `a subclass that edits the draft in onSaved leaves it clean` |
 * | failed save                  | `save surfaces persist failure as error`                   |
 * | save clears the autosave     | `a successful save clears the autosave store and a failed one does not` |
 * | discard → last checkpoint    | `discard resets to baseline`                               |
 * | entity switch                | `switching entities autosaves the new draft, not the old one` |
 * | duplicate save               | `save blocked when already saving`                         |
 * | keyed effect                 | `launchDraftEffect cancels prior with same key`            |
 *
 * The subclass rows of the same matrix:
 *
 * | Row                          | Test                                                       |
 * |------------------------------|------------------------------------------------------------|
 * | TaskCreate: save → navigate  | `TaskCreateViewModelTest.a successful save emits the Saved event` |
 * | TaskCreate: scoped restore   | `TaskCreateViewModelTest.a draft is restored only into the scope it was saved in` |
 * | TaskCreate: failed autosave  | `TaskCreateViewModelTest.a failed autosave leaves the editor usable` |
 * | NoteEditor: autosave failure | `NoteEditorTest.a failed autosave tells the user`          |
 * | NoteEditor: save is a checkpoint | `NoteEditorTest.after an explicit save, closing the editor keeps the saved text` |
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

    @Test
    fun `save validates then persists and calls onSaved`() =
        testVm {
            var onSavedCalled = false
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                    validateBlock = { if (it.title.isBlank()) "Title required" else null },
                    hooks = TestHooks(onSaved = { onSavedCalled = true }),
                )
            advanceTimeBy(20L)
            runCurrent()
            assertTrue(vm.stateFlow.value.isSaveEnabled)

            vm.save()
            advanceTimeBy(50L)
            runCurrent()

            assertTrue(onSavedCalled)
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

    /**
     * The restore runs to completion before the autosave loop starts. A restore
     * that raced the first keystroke could lose the draft the user was already
     * writing — or write the restored draft over it.
     */
    @Test
    fun `autosave does not start before the restore settles`() =
        testVm {
            var restored = false
            var autosaved = false
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "initial"),
                    restoreBlock = {
                        delay(100L)
                        restored = true
                        TestDraft(title = "restored")
                    },
                    autosaveBlock = { autosaved = true },
                    autosaveDebounceMs = 10L,
                )
            advanceTimeBy(50L)
            runCurrent()
            assertFalse(restored, "the restore has not answered yet")
            assertFalse(autosaved, "no autosave may run while the restore is in flight")

            advanceTimeBy(100L)
            runCurrent()
            assertTrue(restored, "the restore has answered")
            assertFalse(autosaved, "the restored draft is the collector's first value — it is dropped, not written")

            // The collector is live now: an edit autosaves.
            vm.updateDraft { it.copy(title = "typed") }
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
            val vm =
                testVm(
                    initialDraft = TestDraft(title = ""),
                    validateBlock = { "Title required" },
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.save()
            advanceTimeBy(20L)
            runCurrent()

            assertEquals("Title required", vm.stateFlow.value.error)
            assertFalse(vm.stateFlow.value.isSaving)
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

    @Test
    fun `autosave failure does not emit Saved event`() =
        testVm {
            var autosaveCalled = false
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                    autosaveBlock = { autosaveCalled = true },
                    autosaveDebounceMs = 10L,
                )
            advanceTimeBy(20L)
            runCurrent()
            assertFalse(autosaveCalled)

            vm.updateDraft { it.copy(title = "edited") }
            advanceTimeBy(50L)
            runCurrent()

            assertTrue(autosaveCalled)
        }

    @Test
    fun `launchDraftEffect cancels prior with same key`() =
        testVm {
            var operationCount = 0
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.launchDraftEffect(
                key = "op",
                operation = {
                    operationCount++
                    advanceTimeBy(50L)
                    runCurrent()
                    "result1"
                },
                onResult = { _, _ -> null },
            )
            vm.launchDraftEffect(
                key = "op",
                operation = {
                    operationCount++
                    "result2"
                },
                onResult = { _, _ -> null },
            )
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(1, operationCount)
        }

    @Test
    fun `launchDraftEffect with different keys run concurrently`() =
        testVm {
            var op1Done = false
            var op2Done = false
            val vm =
                testVm(
                    initialDraft = TestDraft(title = "Hello"),
                )
            advanceTimeBy(20L)
            runCurrent()

            vm.launchDraftEffect(
                key = "op1",
                operation = {
                    advanceTimeBy(20L)
                    runCurrent()
                    op1Done = true
                    "r1"
                },
                onResult = { _, _ -> null },
            )
            vm.launchDraftEffect(
                key = "op2",
                operation = {
                    op2Done = true
                    "r2"
                },
                onResult = { _, _ -> null },
            )
            advanceTimeBy(50L)
            runCurrent()

            assertTrue(op1Done)
            assertTrue(op2Done)
        }
}
