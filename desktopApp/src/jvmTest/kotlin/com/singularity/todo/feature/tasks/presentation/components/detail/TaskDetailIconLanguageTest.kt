@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.countNodes
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTagAt
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Task Detail draws one icon language, and this test holds it to that.
 *
 * The checklist and the subtasks are the two sections that used to disagree: one
 * of them carried a literal emoji in its header, the other a Material glyph, and
 * the two were not even the same size. The emoji is gone, and what replaced it
 * has to stay one language — the same size for every section's header icon, in
 * both themes, and a header that is an *icon* rather than a glyph smuggled into
 * a text label.
 *
 * The size is the part that is easy to break and hard to see: the sections are
 * drawn by different components, so a section that grows an icon of its own is
 * invisible in the source and obvious on the screen.
 */
@Tag("fast")
class TaskDetailIconLanguageTest {
    @Test
    fun `every section header icon is drawn at the same size in both themes`() {
        listOf(ThemeMode.Light, ThemeMode.Dark).forEach { mode ->
            val sizes = iconSizesAt(mode)

            assertEquals(
                2,
                sizes.size,
                "$mode: expected the checklist and the subtasks section to draw a header icon each, " +
                    "got ${sizes.size}",
            )
            sizes.forEach { size ->
                assertEquals(
                    TaskSpacing.iconSize,
                    size.width,
                    "$mode: a section header icon is ${size.width} wide, not ${TaskSpacing.iconSize}",
                )
                assertEquals(
                    TaskSpacing.iconSize,
                    size.height,
                    "$mode: a section header icon is ${size.height} tall, not ${TaskSpacing.iconSize}",
                )
            }
        }
    }

    @Test
    fun `the section headers are icons and plain labels, not glyphs inside the label`() {
        listOf(ThemeMode.Light, ThemeMode.Dark).forEach { mode ->
            runIsolatedComposeTest(width = 360, height = 900) {
                setContent {
                    SingularityTheme(themeMode = mode) {
                        DetailSections()
                    }
                }

                // The label says exactly what the section is. A glyph smuggled into
                // the label — the emoji this replaced — would show up here as text
                // the section does not own.
                awaitText("Checklist").assertTextEquals("Checklist")
                awaitText("Subtasks").assertTextEquals("Subtasks")
            }
        }
    }

    /**
     * The measured size of every section header icon on the screen.
     */
    private fun iconSizesAt(mode: ThemeMode): List<Size> {
        val sizes = mutableListOf<Size>()
        runIsolatedComposeTest(width = 360, height = 900) {
            setContent {
                SingularityTheme(themeMode = mode) {
                    DetailSections()
                }
            }

            val count = countNodes(TestTags.TASK_DETAIL_SECTION_ICON, useUnmergedTree = true)
            repeat(count) { index ->
                val bounds =
                    unclippedBoundsOfTagAt(
                        TestTags.TASK_DETAIL_SECTION_ICON,
                        index,
                        useUnmergedTree = true,
                    )
                sizes += Size(bounds.width, bounds.height)
            }
        }
        return sizes
    }

    @Composable
    private fun DetailSections() {
        Column {
            TaskDetailChecklistSection(
                checklist = persistentListOf(),
                onToggle = {},
                onDelete = {},
                onAddItem = {},
            )
            SubtasksSection(
                subtasks = persistentListOf(),
                onToggle = {},
                onDelete = {},
                onOpen = {},
                onAddSubtask = {},
            )
        }
    }

    private data class Size(val width: Dp, val height: Dp)
}
