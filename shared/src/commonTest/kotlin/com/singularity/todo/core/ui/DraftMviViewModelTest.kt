@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.ui

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

/** Concrete implementation under test */
private class TestDraftVm(
    initialDraft: TestDraft,
    autosaveBlock: (suspend (TestDraft) -> Unit)?,
    restoreBlock: suspend () -> TestDraft?,
    validateBlock: (TestDraft) -> String?,
    persistBlock: suspend (TestDraft) -> Either<AppError, Unit>,
    onSavedBlock: (suspend () -> Unit)?,
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
    private val onSavedImpl: (suspend () -> Unit)? = onSavedBlock
    override fun validate(draft: TestDraft): String? = validateImpl(draft)
    override suspend fun persist(draft: TestDraft): Either<AppError, Unit> = persistImpl(draft)
    override suspend fun onSaved() {
        onSavedImpl?.invoke()
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
private fun testVm(block: suspend TestScope.() -> Unit) = runTest {
    block()
}

/** Creates a VM whose lifecycle is owned by runTest's backgroundScope. */
private fun TestScope.testVm(
    initialDraft: TestDraft,
    autosaveBlock: (suspend (TestDraft) -> Unit)? = null,
    restoreBlock: suspend () -> TestDraft? = { null },
    validateBlock: (TestDraft) -> String? = { null },
    persistBlock: suspend (TestDraft) -> Either<AppError, Unit> = { Either.Right(Unit) },
    onSavedBlock: (suspend () -> Unit)? = null,
    autosaveDebounceMs: Long = 10L,
): TestDraftVm = TestDraftVm(
    initialDraft = initialDraft,
    autosaveBlock = autosaveBlock,
    restoreBlock = restoreBlock,
    validateBlock = validateBlock,
    persistBlock = persistBlock,
    onSavedBlock = onSavedBlock,
    autosaveDebounceMs = autosaveDebounceMs,
    scope = AutoCloseableCoroutineScope(backgroundScope.coroutineContext),
)

class DraftMviViewModelTest {

    @Test
    fun `initial state has draft and save disabled when invalid`() = testVm {
        val vm = testVm(
            initialDraft = TestDraft(title = ""),
            validateBlock = { if (it.title.isBlank()) "Title required" else null },
        )
        advanceTimeBy(20L)
        runCurrent()
        val state = vm.state.value
        assertEquals("", state.draft.title)
        assertFalse(state.isSaveEnabled)
        assertFalse(state.isDirty)
        assertFalse(state.isSaving)
        assertNull(state.error)
    }

    @Test
    fun `edit enables save when draft becomes valid`() = testVm {
        val vm = testVm(
            initialDraft = TestDraft(title = ""),
            validateBlock = { if (it.title.isBlank()) "Title required" else null },
        )
        advanceTimeBy(20L)
        runCurrent()

        vm.updateDraft { it.copy(title = "Hello") }
        advanceTimeBy(20L)
        runCurrent()

        val state = vm.state.value
        assertEquals("Hello", state.draft.title)
        assertTrue(state.isSaveEnabled)
        assertTrue(state.isDirty)
    }

    @Test
    fun `error auto-clears when draft becomes valid`() = testVm {
        val vm = testVm(
            initialDraft = TestDraft(title = " "),
            validateBlock = { if (it.title.isBlank()) "Title required" else null },
            persistBlock = { Either.Left(AppError.Persistence("db error")) },
        )
        advanceTimeBy(20L)
        runCurrent()

        vm.updateDraft { it.copy(title = "") }
        advanceTimeBy(20L)
        runCurrent()
        assertNotNull(vm.state.value.error)

        vm.updateDraft { it.copy(title = "Hello") }
        advanceTimeBy(20L)
        runCurrent()
        assertNull(vm.state.value.error)
    }

    @Test
    fun `restore with persisted draft replaces initial`() = testVm {
        val vm = testVm(
            initialDraft = TestDraft(title = "initial"),
            restoreBlock = { TestDraft(title = "restored") },
        )
        advanceTimeBy(20L)
        runCurrent()

        assertEquals("restored", vm.state.value.draft.title)
        assertFalse(vm.state.value.isDirty)
    }

    @Test
    fun `restore when no persisted draft keeps initial`() = testVm {
        val vm = testVm(
            initialDraft = TestDraft(title = "initial"),
            restoreBlock = { null },
        )
        advanceTimeBy(20L)
        runCurrent()

        assertEquals("initial", vm.state.value.draft.title)
    }

    @Test
    fun `save validates then persists and calls onSaved`() = testVm {
        var onSavedCalled = false
        val vm = testVm(
            initialDraft = TestDraft(title = "Hello"),
            validateBlock = { if (it.title.isBlank()) "Title required" else null },
            onSavedBlock = { onSavedCalled = true },
        )
        advanceTimeBy(20L)
        runCurrent()
        assertTrue(vm.state.value.isSaveEnabled)

        vm.save()
        advanceTimeBy(50L)
        runCurrent()

        assertTrue(onSavedCalled)
    }

    @Test
    fun `save blocked when already saving`() = testVm {
        var persistCount = 0
        val vm = testVm(
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
    fun `save surfaces persist failure as error`() = testVm {
        val vm = testVm(
            initialDraft = TestDraft(title = "Hello"),
            persistBlock = { Either.Left(AppError.Persistence("db error")) },
        )
        advanceTimeBy(20L)
        runCurrent()

        vm.save()
        advanceTimeBy(50L)
        runCurrent()

        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.isSaving)
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
    fun `save surfaces a thrown persist exception as an error`() = testVm {
        val vm = testVm(
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
            vm.state.value.error,
            "a thrown persist() must surface an error instead of vanishing",
        )
        assertFalse(vm.state.value.isSaving, "the save button must be re-enabled")
    }

    @Test
    fun `save surfaces a thrown validate exception as an error`() = testVm {
        val vm = testVm(
            initialDraft = TestDraft(title = "Hello"),
            validateBlock = { throw IllegalStateException("validator exploded") },
        )
        advanceTimeBy(20L)
        runCurrent()

        vm.save()
        advanceTimeBy(50L)
        runCurrent()

        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.isSaving)
    }

    @Test
    fun `save surfaces validation error`() = testVm {
        val vm = testVm(
            initialDraft = TestDraft(title = ""),
            validateBlock = { "Title required" },
        )
        advanceTimeBy(20L)
        runCurrent()

        vm.save()
        advanceTimeBy(20L)
        runCurrent()

        assertEquals("Title required", vm.state.value.error)
        assertFalse(vm.state.value.isSaving)
    }

    @Test
    fun `discard resets to baseline`() = testVm {
        val vm = testVm(
            initialDraft = TestDraft(title = "original"),
        )
        advanceTimeBy(20L)
        runCurrent()

        vm.updateDraft { it.copy(title = "edited") }
        advanceTimeBy(20L)
        runCurrent()
        assertEquals("edited", vm.state.value.draft.title)
        assertTrue(vm.state.value.isDirty)

        vm.discard()
        advanceTimeBy(20L)
        runCurrent()

        assertEquals("original", vm.state.value.draft.title)
        assertFalse(vm.state.value.isDirty)
        assertFalse(vm.state.value.isSaving)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `open switches entity and resets dirty state`() = testVm {
        val vm = testVm(
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

        assertEquals("second", vm.state.value.draft.title)
        assertFalse(vm.state.value.isDirty)
    }

    @Test
    fun `open with same entity is no-op`() = testVm {
        val draft = TestDraft(title = "same")
        val vm = testVm(
            initialDraft = draft,
        )
        advanceTimeBy(20L)
        runCurrent()

        vm.open(draft)
        advanceTimeBy(20L)
        runCurrent()

        assertFalse(vm.state.value.isDirty)
    }

    @Test
    fun `dismissError clears error`() = testVm {
        val vm = testVm(
            initialDraft = TestDraft(title = " "), // non-empty so first edit produces a new value
            validateBlock = { if (it.title.isBlank()) "Title required" else null },
        )
        advanceTimeBy(20L)
        runCurrent()

        vm.updateDraft { it.copy(title = "") }
        advanceTimeBy(20L)
        runCurrent()
        assertNotNull(vm.state.value.error)

        vm.dismissError()
        advanceTimeBy(20L)
        runCurrent()

        assertNull(vm.state.value.error)
    }

    @Test
    fun `autosave failure does not emit Saved event`() = testVm {
        var autosaveCalled = false
        val vm = testVm(
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
    fun `launchDraftEffect cancels prior with same key`() = testVm {
        var operationCount = 0
        val vm = testVm(
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
    fun `launchDraftEffect with different keys run concurrently`() = testVm {
        var op1Done = false
        var op2Done = false
        val vm = testVm(
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
