package com.singularity.todo.feature.tasks

import kotlin.test.Test
import kotlin.test.assertEquals

class TasksFormattersTest {

    @Test
    fun `refine title formats with prefix`() {
        val result = formatAiResult(AiActionResult.RefineTitle(newTitle = "Buy milk"))
        assertEquals("Refined title: Buy milk", result)
    }

    @Test
    fun `generate description formats with prefix`() {
        val result = formatAiResult(AiActionResult.GenerateDescription(description = "Fresh groceries"))
        assertEquals("Description: Fresh groceries", result)
    }

    @Test
    fun `generate checklist formats as bulleted list`() {
        val result = formatAiResult(AiActionResult.GenerateChecklist(steps = listOf("step one", "step two")))
        assertEquals("Checklist:\n- step one\n- step two", result)
    }

    @Test
    fun `decompose task formats sub-tasks as bulleted list`() {
        val result = formatAiResult(AiActionResult.DecomposeTask(subTasks = listOf("a", "b", "c")))
        assertEquals("Sub-tasks:\n- a\n- b\n- c", result)
    }

    @Test
    fun `pick time formats with prefix`() {
        val result = formatAiResult(AiActionResult.PickTime(suggestedTime = "10:00"))
        assertEquals("Suggested time: 10:00", result)
    }

    @Test
    fun `error formats with prefix`() {
        val result = formatAiResult(AiActionResult.Error(message = "boom"))
        assertEquals("Error: boom", result)
    }
}
