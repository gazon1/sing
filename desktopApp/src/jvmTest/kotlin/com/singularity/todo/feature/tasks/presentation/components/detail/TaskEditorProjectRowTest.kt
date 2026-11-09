package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.text.OwnedText
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.state.TaskEditorSheet
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

@Tag("fast")
@OptIn(ExperimentalTestApi::class)
class TaskEditorProjectRowTest {
    @Test
    fun `shows project name, not raw id`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val projectId = ProjectId.fromString("P-1eea7616-fb58-41e1-8ea5-2ab8d1819c81")
            setContent {
                TaskEditorContent(
                    model =
                        TaskEditorModel(
                            project = projectId,
                            projectName = "Roadmap",
                            projectColor = 0xFF6A4C93.toInt(),
                        ),
                    titleSource = OwnedText(text = "", revision = 0),
                    descriptionSource = OwnedText(text = "", revision = 0),
                    onTitleChange = {},
                    onDescriptionChange = {},
                    callbacks = callbacksWithProject { },
                    now = NOW,
                    sheets = rememberDialogState<TaskEditorSheet>(),
                )
            }

            assertTextDisplayed("Roadmap")
            assertTextNotExists(projectId.value)
        }

    @Test
    fun `shows safe fallback when selected project is unavailable`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val projectId = ProjectId.fromString("P-deleted-project")
            setContent {
                TaskEditorContent(
                    model =
                        TaskEditorModel(
                            project = projectId,
                            projectName = null,
                            projectColor = null,
                        ),
                    titleSource = OwnedText(text = "", revision = 0),
                    descriptionSource = OwnedText(text = "", revision = 0),
                    onTitleChange = {},
                    onDescriptionChange = {},
                    callbacks = callbacksWithProject { },
                    now = NOW,
                    sheets = rememberDialogState<TaskEditorSheet>(),
                )
            }

            assertTextDisplayed("Project unavailable")
            assertTextNotExists(projectId.value)
        }

    private fun callbacksWithProject(onProject: (ProjectId?) -> Unit) =
        TaskEditorCallbacks(
            onBack = {},
            onCheckToggle = null,
            startDate = null,
            project =
                RowCallbacks(
                    onChange = onProject,
                    onClick = { },
                    onClear = { onProject(null) },
                ),
            tags = null,
            recurrence = null,
            pin = null,
            dependencies = null,
            checklist = null,
            attachments = null,
            estimate = null,
            onTimeEntryAdd = null,
            onTimeEntrySave = null,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-14T12:00:00Z")
    }
}
