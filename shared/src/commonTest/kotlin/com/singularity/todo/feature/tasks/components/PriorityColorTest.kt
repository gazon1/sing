package com.singularity.todo.feature.tasks.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.singularity.todo.feature.tasks.TaskPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PriorityColorTest {

    @Test
    fun `None priority is unspecified`() {
        assertEquals(Color.Unspecified, priorityColor(TaskPriority.None))
    }

    @Test
    fun `every non-None priority has a color`() {
        TaskPriority.entries
            .filter { it != TaskPriority.None }
            .forEach { priority ->
                assertNotEquals(Color.Unspecified, priorityColor(priority), "Priority $priority must have a color")
            }
    }

    @Test
    fun `specific priority colors are stable`() {
        // Encoded ARGB values — change intentionally if palette changes.
        assertEquals(0xFF4CAF50.toInt(), priorityColor(TaskPriority.Low).toArgb())
        assertEquals(0xFFFF9800.toInt(), priorityColor(TaskPriority.Medium).toArgb())
        assertEquals(0xFFF44336.toInt(), priorityColor(TaskPriority.High).toArgb())
        assertEquals(0xFFE91E63.toInt(), priorityColor(TaskPriority.Urgent).toArgb())
    }
}
