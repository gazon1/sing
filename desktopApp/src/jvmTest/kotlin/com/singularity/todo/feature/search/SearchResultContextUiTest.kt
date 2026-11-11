@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.search.IndexedRecord
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.search.presentation.nav.PreviewSearchNavigator
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * What a search result row says about its project.
 *
 * The row names the project the task belongs to — and says nothing at all
 * about a project when the task belongs to none.
 */
@Tag("slow")
@Tag("slow:compose")
class SearchResultContextUiTest {
    private val now = Instant.fromEpochMilliseconds(0)

    @Test
    fun `a result row names the project the task belongs to`() =
        runIsolatedComposeTest {
            val project =
                Project(
                    id = ProjectId.fromString("p-1"),
                    name = "Kitchen renovation",
                    color = 0xFF3366FF.toInt(),
                    createdAt = now,
                    updatedAt = now,
                    userId = UserId("user-a"),
                )
            val task =
                Task(
                    id = TaskId("t-1"),
                    title = "Buy milk",
                    projectId = project.id,
                    createdAt = now,
                    updatedAt = now,
                    userId = UserId("user-a"),
                )
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("milk"),
                            results =
                                SearchResults(
                                    tasks = persistentListOf(IndexedRecord(task)),
                                    notes = persistentListOf(),
                                    projects = persistentListOf(),
                                    tags = persistentListOf(),
                                ),
                            projectsById = persistentMapOf(project.id to project),
                        ),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTextDisplayed("Kitchen renovation")
        }

    @Test
    fun `a result row of an unassigned task names no project`() =
        runIsolatedComposeTest {
            val task =
                Task(
                    id = TaskId("t-1"),
                    title = "Buy milk",
                    createdAt = now,
                    updatedAt = now,
                    userId = UserId("user-a"),
                )
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("milk"),
                            results =
                                SearchResults(
                                    tasks = persistentListOf(IndexedRecord(task)),
                                    notes = persistentListOf(),
                                    projects = persistentListOf(),
                                    tags = persistentListOf(),
                                ),
                        ),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTextDisplayed("Buy milk")
        }

    @Test
    fun `a long project name does not push the title out of the row`() =
        runIsolatedComposeTest {
            val project =
                Project(
                    id = ProjectId.fromString("p-long"),
                    name = "The renovation of the kitchen and the bathroom and the hallway",
                    color = 0xFF3366FF.toInt(),
                    createdAt = now,
                    updatedAt = now,
                    userId = UserId("user-a"),
                )
            val task =
                Task(
                    id = TaskId("t-1"),
                    title = "Buy milk",
                    projectId = project.id,
                    createdAt = now,
                    updatedAt = now,
                    userId = UserId("user-a"),
                )
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("milk"),
                            results =
                                SearchResults(
                                    tasks = persistentListOf(IndexedRecord(task)),
                                    notes = persistentListOf(),
                                    projects = persistentListOf(),
                                    tags = persistentListOf(),
                                ),
                            projectsById = persistentMapOf(project.id to project),
                        ),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            // The title keeps the first word: the badge is secondary metadata and
            // it is the badge that is shortened, not the title.
            assertTextDisplayed("Buy milk")
        }
}
