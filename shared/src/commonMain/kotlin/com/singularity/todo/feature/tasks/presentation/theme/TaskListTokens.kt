package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TouchTarget

/**
 * Design tokens экрана списка задач — геометрия, spacing, размеры.
 *
 * Шкалы (spacing, sizes) идут с шагом 4.dp — стандарт Material 8-pt grid,
 * кратный 2х, чтобы значения не "плавали" на разных плотностях экрана.
 *
 * Раньше здесь же был объект `TaskListColors` — пятнадцать фиксированных
 * значений в тёмной палитре. Он не мог реагировать на тему, поэтому
 * пользователь в светлом режиме (дефолт приложения) видел тёмный список
 * задач, а выбор акцента на этом экране не работал вовсе: акцент был
 * записан литералом. Цвета теперь берутся из темы — прямые роли из
 * `MaterialTheme.colorScheme` (см. [TaskDerivedColors]), а те, что не должны
 * зависеть от темы, — из [TaskSemanticColors].
 */
object TaskListShapes {
    val CardRadius = RoundedCornerShape(16.dp)
    val ChipRadius = RoundedCornerShape(12.dp)
    val FabRadius = RoundedCornerShape(16.dp)
    val IconContainerRadius = RoundedCornerShape(14.dp)
    val CheckboxSize = 22.dp
}

/** Отступы в UI — одна шкала, чтобы визуальный ритм не "плавал" между компонентами. */
object TaskListSpacing {
    val None = 0.dp
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 24.dp
}

/** Семантические размеры (не "отступы", а "размер виджета"). */
object TaskListSizes {
    val Checkbox = 22.dp
    val CheckIcon = 13.dp
    val PriorityStar = 16.dp
    val MetaIcon = 13.dp

    /**
     * Видимый размер иконки удаления в свайп-строке.
     *
     * Это **видимый** размер, а не размер площади касания, поэтому он не выводится
     * из общего минимума [TouchTarget.MinSize]: минимум касания относится к области
     * нажатия, и его изменение не должно двигать глиф. Раньше размер считался как
     * `TouchTarget.MinSize - TaskListSpacing.Md`, и когда минимум подняли с 44 до
     * 48 dp, иконка выросла на 4 dp сама собой — не по решению, а по следствию.
     *
     * Сколько именно занимает область нажатия, решает общий минимум в
     * `core/ui/TouchTarget.kt` — он и должен быть единственным, кто им управляет.
     */
    val SwipeDeleteIcon = 36.dp

    val DividerThickness = 1.dp
    val CardElevation = 1.dp
    val CardElevationHover = 4.dp

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
        replaceWith =
            ReplaceWith(
                expression = "TouchTarget.MinSize",
                imports = arrayOf("com.singularity.todo.core.ui.TouchTarget"),
            ),
    )
    val MinTouchTarget = TouchTarget.MinSize
}
