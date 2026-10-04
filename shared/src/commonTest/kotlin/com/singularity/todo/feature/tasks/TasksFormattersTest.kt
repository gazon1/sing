package com.singularity.todo.feature.tasks

import com.singularity.todo.feature.tasks.domain.model.AiActionResult
import com.singularity.todo.feature.tasks.domain.model.formatAiResult
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("fast")
class TasksFormattersTest {

    @Test
    fun refineTitleFormatsWithPrefix() {
        val result = formatAiResult(AiActionResult.RefineTitle(newTitle = "Buy milk"))
        assertEquals("Refined title: Buy milk", result)
    }

    @Test
    fun generateDescriptionFormatsWithPrefix() {
        val result = formatAiResult(AiActionResult.GenerateDescription(description = "Fresh groceries"))
        assertEquals("Description: Fresh groceries", result)
    }

    @Test
    fun generateChecklistFormatsAsBulletedList() {
        val result = formatAiResult(AiActionResult.GenerateChecklist(steps = listOf("step one", "step two")))
        assertEquals("Checklist:\n- step one\n- step two", result)
    }

    @Test
    fun decomposeTaskFormatsSubTasksAsBulletedList() {
        val result = formatAiResult(AiActionResult.DecomposeTask(subTasks = listOf("a", "b", "c")))
        assertEquals("Sub-tasks:\n- a\n- b\n- c", result)
    }

    @Test
    fun pickTimeFormatsWithPrefix() {
        val result = formatAiResult(AiActionResult.PickTime(suggestedTime = "10:00"))
        assertEquals("Suggested time: 10:00", result)
    }

    @Test
    fun errorFormatsWithPrefix() {
        val result = formatAiResult(AiActionResult.Error(message = "boom"))
        assertEquals("Error: boom", result)
    }
}
