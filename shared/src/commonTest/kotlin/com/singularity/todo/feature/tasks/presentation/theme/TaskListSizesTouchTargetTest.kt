package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TouchTarget
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Токен списка задач остаётся привязан к общему минимуму.
 *
 * `TaskListSizes.MinTouchTarget` — устаревший псевдоним `TouchTarget.MinSize`.
 * Он сохранён, чтобы у списка задач оставалось имя для величины, которую читают его
 * контролы, а этот тест не даёт псевдониму стать вторым, отдельно уходящим
 * объявлением. Нижняя граница самого общего токена проверяется в пакете core UI
 * (`TouchTargetTest`) — здесь живёт только сторона списка задач.
 */
@Tag("fast")
@Suppress("DEPRECATION")
class TaskListSizesTouchTargetTest {
    @Test
    fun `task list minimum touch target follows the shared touch target token`() {
        assertEquals(TouchTarget.MinSize, TaskListSizes.MinTouchTarget)
    }

    @Test
    fun `task list minimum touch target is not below the Material minimum`() {
        assertTrue(
            TaskListSizes.MinTouchTarget >= 48.dp,
            "TaskListSizes.MinTouchTarget = ${TaskListSizes.MinTouchTarget}, " +
                "минимум Material — 48 dp",
        )
    }
}
