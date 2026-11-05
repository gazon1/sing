package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Видимый размер иконки удаления остаётся собственной величиной свайп-строки.
 *
 * Почему он не выводится из минимума площади касания — в KDoc самого токена
 * ([TaskListSizes.SwipeDeleteIcon]). Здесь проверяется, что так и осталось:
 * значение — 36 dp, объявление называет число, а строка читает токен. Минимум
 * изменить нельзя (это `val`), поэтому независимость проверяется формой
 * объявления, а не подменой его значения.
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
            literalDp.matches(declaration),
            "Токен видимого размера объявляет величину, а не выражение: $declaration",
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

    /** `val <имя> = <число>.dp` — величина, записанная числом, а не выражением. */
    private val literalDp = Regex("""^val \w+\s*(?::\s*\w+\s*)?=\s*\d+(?:\.\d+)?\.dp$""")

    /** Строка объявления токена — без её KDoc, где минимум упоминается как причина. */
    private fun declarationOf(fileName: String, tokenName: String): String =
        readSharedUiSource("feature/tasks/presentation/theme/$fileName")
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("val $tokenName ") || it.startsWith("val $tokenName:") }
            ?: error("Токен $tokenName не объявлен в $fileName")

    private fun readSharedUiSource(relativePath: String): String =
        File(repoRoot(), "shared/src/commonMain/kotlin/com/singularity/todo/$relativePath").readText()

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("could not find the repository root from ${System.getProperty("user.dir")}")
}
