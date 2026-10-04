package com.singularity.todo.feature.tasks.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.list.priorityColor
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@Tag("fast")
class PriorityColorTest {

    @Test
    fun `None priority has a defined tertiary color`() {
        // None maps to PriorityNone = TextTertiary = Color(0xFF5C6270), not Color.Unspecified
        assertEquals(Color(0xFF5C6270), priorityColor(TaskPriority.None))
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
        assertEquals(0xFF5B8DEF.toInt(), priorityColor(TaskPriority.Low).toArgb())
        assertEquals(0xFFF5A623.toInt(), priorityColor(TaskPriority.Medium).toArgb())
        assertEquals(0xFFE5484D.toInt(), priorityColor(TaskPriority.High).toArgb())
        assertEquals(0xFFFF6B6B.toInt(), priorityColor(TaskPriority.Urgent).toArgb())
    }
}
