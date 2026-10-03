@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the `runTest` scheduling semantics that cost a full debugging session to
 * establish — see `2026-09-30-testscope-background-work-semantics`.
 *
 * The rule, verified experimentally: **`advanceUntilIdle()` drains the task
 * queue only while foreground work is pending.** Coroutines launched on
 * `backgroundScope` execute only as a side effect of that pump — or under an
 * explicit `runCurrent()`. When the foreground is idle, `advanceUntilIdle()`
 * returns immediately and background work stays queued forever.
 *
 * The practical consequence for ViewModel tests: a VM whose collector lives on
 * `backgroundScope` never emits its first state under `advanceUntilIdle()` —
 * every assertion reads `Loading`, and rejection tests keep passing against an
 * implementation that does nothing. Working shapes:
 *
 * - **Foreground** (what `MviViewModelTest.VmUnderTest` and `TagRenameTest`
 *   use): build the VM scope from `testScope.coroutineContext` under a child
 *   `Job`, cancel it at the end.
 * - **Background + suspension** (what `DraftMviViewModelTest` uses): keep the
 *   VM on `backgroundScope` and drive the test with `runCurrent()` or a helper
 *   that suspends until the state matches — while the body is suspended,
 *   `runTest` pumps the background work.
 *
 * If this test fails after a kotlinx-coroutines upgrade, the scheduling
 * contract changed: re-audit every ViewModel test that touches
 * `backgroundScope` or asserts on VM state before re-enabling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestScopeSemanticsTest {

    @Test
    fun `advanceUntilIdle does not run background work when the foreground is idle`() = runTest {
        val hits = mutableListOf<String>()
        backgroundScope.launch { hits += "bg" }

        advanceUntilIdle()

        assertEquals(emptyList(), hits, "advanceUntilIdle must not pump an idle foreground's background work")
    }

    @Test
    fun `background work runs alongside pending foreground work`() = runTest {
        val hits = mutableListOf<String>()
        backgroundScope.launch { hits += "bg" }

        launch { hits += "fg" }
        advanceUntilIdle()

        assertEquals(listOf("bg", "fg"), hits)
    }

    @Test
    fun `runCurrent runs queued background work unconditionally`() = runTest {
        val hits = mutableListOf<String>()
        backgroundScope.launch { hits += "bg" }

        runCurrent()

        assertEquals(listOf("bg"), hits)
    }

    @Test
    fun `foreground work is always advanced by advanceUntilIdle`() = runTest {
        val hits = mutableListOf<String>()
        launch { hits += "fg" }

        advanceUntilIdle()

        assertEquals(listOf("fg"), hits)
    }
}
