package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TouchTarget
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Нижняя граница площади касания контролов списка задач.
 *
 * Значение было поднято с `44.dp` до `48.dp` (минимум Material) в #1706.
 * Ничего не закрепляло число, поэтому его можно было сдвинуть молча —
 * тест делает границу проверяемой.
 */
@Tag("fast")
class TaskListSizesTouchTargetTest {

    @Test
    fun `minimum touch target of the task list is not below the Material minimum`() {
        assertTrue(
            TaskListSizes.MinTouchTarget >= 48.dp,
            "TaskListSizes.MinTouchTarget = ${TaskListSizes.MinTouchTarget}, " +
                "минимум Material — 48 dp",
        )
    }

    @Test
    fun `task list minimum touch target follows the shared touch target token`() {
        assertEquals(TouchTarget.MinSize, TaskListSizes.MinTouchTarget)
    }

    @Test
    fun `shared touch target token is not below the Material minimum`() {
        assertTrue(
            TouchTarget.MinSize >= 48.dp,
            "TouchTarget.MinSize = ${TouchTarget.MinSize}, минимум Material — 48 dp",
        )
    }
}
