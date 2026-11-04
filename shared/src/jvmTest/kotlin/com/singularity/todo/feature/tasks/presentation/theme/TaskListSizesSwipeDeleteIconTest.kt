package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Видимый размер иконки удаления в свайп-строке — его собственный токен.
 *
 * ## Что этот тест охраняет
 *
 * Размер иконки раньше считался как `TouchTarget.MinSize - TaskListSpacing.Md` —
 * минимум площади касания минус шаг отступа. Это две разные величины, которые
 * просто рядом стояли: когда общий минимум подняли с 44 до 48 dp, иконка выросла
 * на 4 dp сама собой, а не по решению. Теперь у видимого размера есть имя
 * (`TaskListSizes.SwipeDeleteIcon`), и он не зависит от минимума.
 *
 * Минимум изменить нельзя — это `val`, и его величина закреплена в core UI.
 * Поэтому проверяется не значение, а **форма** объявления: строка, где объявлен
 * токен, обязана называть величину и не обязана ссылаться на минимум. Вернётся
 * ли производная — тест увидит.
 */
@Tag("fast")
class TaskListSizesSwipeDeleteIconTest {
    @Test
    fun `the swipe delete icon has the stated visible size`() {
        assertEquals(
            36.dp,
            TaskListSizes.SwipeDeleteIcon,
            "Видимый размер иконки удаления — 36 dp.",
        )
    }

    @Test
    fun `the swipe delete icon size is declared, not derived from the touch target`() {
        val declaration = declarationOf("TaskListTokens.kt", "SwipeDeleteIcon")

        assertTrue(
            "36.dp" in declaration,
            "Токен видимого размера объявляет величину: $declaration",
        )
        assertFalse(
            "MinSize" in declaration || "MinTouchTarget" in declaration,
            "Видимый размер не выводится из минимума площади касания: $declaration",
        )
    }

    @Test
    fun `the swipe row takes the icon size from the token`() {
        val source = readSharedUiSource("feature/tasks/presentation/components/list/SwipeableTaskRow.kt")

        assertTrue(
            ".size(TaskListSizes.SwipeDeleteIcon)" in source,
            "Свайп-строка задаёт размер иконки через TaskListSizes.SwipeDeleteIcon.",
        )
        assertFalse(
            "MinSize" in source || "MinTouchTarget" in source,
            "Свайп-строка не читает минимум площади касания: видимый размер иконки — " +
                "собственный токен, а минимум управляет областью нажатия.",
        )
    }

    /** Строка объявления токена — без её KDoc, где минимум упоминается как причина. */
    private fun declarationOf(fileName: String, tokenName: String): String {
        val line =
            readSharedUiSource("feature/tasks/presentation/theme/$fileName")
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("val $tokenName ") }
                ?: error("Токен $tokenName не объявлен в $fileName")
        return line
    }

    private fun readSharedUiSource(relativePath: String): String =
        File(repoRoot(), "shared-ui/src/commonMain/kotlin/com/singularity/todo/$relativePath").readText()

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("could not find the repository root from ${System.getProperty("user.dir")}")
}
