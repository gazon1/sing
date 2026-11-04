package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TouchTarget

/**
 * Per-widget geometry of the task surfaces — the size a control is drawn at,
 * not the gap between controls.
 *
 * The spacing and shape scales that used to live here moved to `core/ui/theme`:
 * this file carried a second spacing scale (2 / 4 / 8 / 12 / 16 / 20 / 24) beside
 * `TaskSpacing`'s (8 / 12 / 16 / 24), so `xl` meant 24 dp in the editor and 20 dp
 * in the list. Both are now the theme's one scale, read as
 * `MaterialTheme.spacing`, and corner radii come from `MaterialTheme.shapes`.
 *
 * What remains is genuinely per-widget: a checkbox is 22 dp wherever it is
 * drawn, and that number is not a step on anybody's grid. [Icon] and [IconLarge]
 * are the two icon sizes `TaskSpacing.iconSize` / `iconSizeLarge` used to carry —
 * they are widget geometry, not gaps, so they landed here rather than on the
 * spacing scale, and every former reader now names them. The two elevation
 * tokens this object also carried were read by nothing and are gone; elevation
 * is `MaterialTheme.elevation` now.
 */
object TaskListSizes {
    val Checkbox = 22.dp
    val CheckIcon = 13.dp
    val Icon = 22.dp
    val IconLarge = 26.dp
    val PriorityStar = 16.dp
    val MetaIcon = 13.dp
    val DividerThickness = 1.dp

    /**
     * Устаревший псевдоним общего минимума площади касания.
     *
     * Источник истины — [TouchTarget.MinSize] в пакете core UI: минимум общий для
     * всего приложения, и читать его должны все фичи, а не только список задач.
     * Значение здесь — ровно 48 dp, как и у общего токена.
     *
     * **Почему псевдоним остаётся, а не удалён:** имя токена списка задач называет
     * смысловую величину для его контролов, а соответствие псевдонима общему токену
     * закреплено тестом `TaskListSizesTouchTargetTest` — если кто-то отвяжет
     * псевдоним от общего токена, тест это увидит. В производственном коде
     * читателей не осталось: все контролы, включая контролы списка задач, читают
     * [TouchTarget.MinSize] напрямую; единственный читатель псевдонима — тест,
     * который закрепляет соответствие.
     *
     * @deprecated Общий минимум площади касания — `core.ui.TouchTarget.MinSize`.
     */
    @Deprecated(
        message = "Общий минимум площади касания — core.ui.TouchTarget.MinSize.",
        replaceWith = ReplaceWith(
            expression = "TouchTarget.MinSize",
            import = "com.singularity.todo.core.ui.TouchTarget",
        ),
    )
    val MinTouchTarget = TouchTarget.MinSize
}
