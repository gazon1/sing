package com.singularity.todo.feature.tasks.presentation.state

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rules in [FirstRunResolver] are all about precedence between cheap answers, so
 * every case here is one of those orderings rather than a rendering concern.
 */
@Tag("fast")
class FirstRunResolverTest {

    @Test
    fun `new empty task offers scaffolding`() {
        val result = FirstRunResolver.resolve(
            hasBody = false,
            checklistCount = 0,
            completedSubtaskCount = 0,
            ageMs = 1_000L,
        )
        assertEquals(FirstRun.Offer, result)
    }

    @Test
    fun `body text wins over the age window`() {
        val result = FirstRunResolver.resolve(
            hasBody = true,
            checklistCount = 0,
            completedSubtaskCount = 0,
            ageMs = 1_000L,
        )
        assertEquals(FirstRun.Established, result)
    }

    @Test
    fun `whitespace-only body does not count as content`() {
        val result = FirstRunResolver.resolve(
            hasBody = false, // caller passes !isNullOrBlank()
            checklistCount = 0,
            completedSubtaskCount = 0,
            ageMs = 1_000L,
        )
        assertEquals(FirstRun.Offer, result)
    }

    @Test
    fun `any checklist item wins over the age window`() {
        val result = FirstRunResolver.resolve(
            hasBody = false,
            checklistCount = 1,
            completedSubtaskCount = 0,
            ageMs = 1_000L,
        )
        assertEquals(FirstRun.Established, result)
    }

    @Test
    fun `a completed subtask wins, an open one does not`() {
        val completed = FirstRunResolver.resolve(
            hasBody = false,
            checklistCount = 0,
            completedSubtaskCount = 1,
            ageMs = 1_000L,
        )
        assertEquals(FirstRun.Established, completed)

        val open = FirstRunResolver.resolve(
            hasBody = false,
            checklistCount = 0,
            completedSubtaskCount = 0,
            ageMs = 1_000L,
        )
        assertEquals(FirstRun.Offer, open)
    }

    @Test
    fun `a failed load resolves to established rather than hanging`() {
        val result = FirstRunResolver.resolve(
            hasBody = false,
            checklistCount = 0,
            completedSubtaskCount = 0,
            ageMs = 1_000L,
            loadFailed = true,
        )
        assertEquals(FirstRun.Established, result)
    }

    @Test
    fun `a task older than the window is established even when empty`() {
        val result = FirstRunResolver.resolve(
            hasBody = false,
            checklistCount = 0,
            completedSubtaskCount = 0,
            ageMs = FirstRunResolver.NEW_TASK_WINDOW_MS + 1,
        )
        assertEquals(FirstRun.Established, result)
    }

    @Test
    fun `the age boundary itself still offers`() {
        val result = FirstRunResolver.resolve(
            hasBody = false,
            checklistCount = 0,
            completedSubtaskCount = 0,
            ageMs = FirstRunResolver.NEW_TASK_WINDOW_MS - 1,
        )
        assertEquals(FirstRun.Offer, result)
    }
}
