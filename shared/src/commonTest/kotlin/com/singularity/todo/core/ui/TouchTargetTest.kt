package com.singularity.todo.core.ui

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Нижняя граница общего минимума площади касания.
 *
 * `TouchTarget.MinSize` — число, от которого считает площадь касания каждый
 * контрол приложения. Поэтому граница закреплена здесь, в пакете, который токен
 * объявляет, а не в фиче, которая его читает: если пакет фичи переедут,
 * переименуют или удалят, общий минимум не потеряет проверку молча.
 *
 * Проверка настоящая: это утверждение о значении, а не равенство двух имён
 * одного и того же числа. Минимум ниже 48 dp (минимум Material) её валит.
 */
@Tag("fast")
class TouchTargetTest {
    @Test
    fun `shared touch target minimum is not below the Material minimum`() {
        assertTrue(
            TouchTarget.MinSize >= 48.dp,
            "TouchTarget.MinSize = ${TouchTarget.MinSize}, минимум Material — 48 dp",
        )
    }
}
