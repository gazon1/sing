@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.projects.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.LocalScreenActions
import com.singularity.todo.core.ui.components.ScreenActionsHolder
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.components.ProjectDetailActions
import com.singularity.todo.feature.projects.presentation.model.ProjectDetailUi
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiState
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The project detail's bar, on the publishing side.
 *
 * The project detail lives inside the projects nested graph, and the Android
 * shell cannot see inside it: the shell's own name for the screen on screen is
 * the name of the destination that opened the graph, so the bar said "Projects"
 * while the screen showed a project. The screen publishes its title instead,
 * and the shell draws it.
 *
 * The publication is only half of it. The back arrow has to be the screen's
 * own, because the shell's arrow only knows the outer stack: pressed from the
 * project detail it left the whole projects graph and went back to the tab
 * before it. The screen's arrow pops the nested graph instead, and the screen
 * is the only one that knows how.
 *
 * So the bar is mounted here with a holder provided through
 * [LocalScreenActions], and what it published is read the way the shell reads
 * it: the flag that tells the shell to draw the published title, the title
 * itself, and the navigation icon — which is then pressed, to prove it is the
 * screen's back action and not some other icon.
 */
@Tag("fast")
class ProjectDetailTopBarShellPublishTest {
    @Test
    fun `the project detail publishes its name and its back action to the shell bar`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            var backPresses = 0
            val state = mutableStateOf(contentState("Project Alpha"))

            setContent {
                CompositionLocalProvider(LocalScreenActions provides holder) {
                    MaterialTheme {
                        Column {
                            ProjectDetailTopBar(
                                state = state,
                                actions = ProjectDetailActions { },
                                onBack = { backPresses++ },
                            )
                            PublishedShellBar(holder)
                        }
                    }
                }
            }
            waitForIdle()

            assertTrue(
                holder.override,
                "the detail takes the shell bar over only by saying so: without the " +
                    "override the shell draws the destination's own name",
            )
            assertNotNull(
                holder.title,
                "the detail published no title: the shell bar would show the " +
                    "destination's name instead of the project on screen",
            )
            assertNotNull(
                holder.navigationIcon,
                "the detail has a back action but published no navigation icon: the " +
                    "shell would keep its own arrow, which only knows the outer stack " +
                    "and would leave the whole projects graph",
            )
            assertTagTextEquals(
                TestTags.SHELL_BAR_TITLE,
                "Project Alpha",
            )

            // The published icon is the detail's own back button: the tag is the
            // bar's, and pressing it reaches the action the screen handed in.
            clickTag(TestTags.TOP_BAR_BACK_BUTTON)
            assertEquals(
                1,
                backPresses,
                "the published navigation icon is not the detail's back action",
            )

            // The name follows the project. The shell draws the published title
            // in the shell's own composition, so the title has to be read there,
            // from the state: a title captured at publication keeps the name the
            // project had when the bar published it.
            state.value = contentState("Project Beta")
            waitForIdle()
            assertTagTextEquals(
                TestTags.SHELL_BAR_TITLE,
                "Project Beta",
            )
        }

    private fun contentState(name: String): ProjectDetailUiState.Content =
        ProjectDetailUiState.Content(
            ui =
                ProjectDetailUi(
                    project =
                        Project(
                            id = ProjectId("p1"),
                            name = name,
                            color = 0xFF2196F3.toInt(),
                            createdAt = Instant.fromEpochMilliseconds(0),
                            updatedAt = Instant.fromEpochMilliseconds(0),
                            userId = UserId.anonymous,
                        ),
                    tasks = emptyList(),
                    totalCount = 0,
                    completedCount = 0,
                    childProjects = persistentListOf(),
                    parent = null,
                ),
            hideCompleted = false,
            parentOptions = persistentListOf(),
            availableTasks = persistentListOf(),
            tagGroups = persistentListOf(),
        )
}

/**
 * What the detail published, drawn the way the shell draws it.
 *
 * The shell renders the published title and navigation icon in its own bar, so
 * the test renders them in one place of its own. The icon goes inside a
 * [Surface]: an icon drawn outside a surface has no content colour to inherit,
 * and the composition fails on it.
 */
@Composable
private fun PublishedShellBar(holder: ScreenActionsHolder) {
    val title = holder.title
    if (title != null) {
        title()
    }
    val navigationIcon = holder.navigationIcon
    if (navigationIcon != null) {
        Surface {
            navigationIcon()
        }
    }
}
