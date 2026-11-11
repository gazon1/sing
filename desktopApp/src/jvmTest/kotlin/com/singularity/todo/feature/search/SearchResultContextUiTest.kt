@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.search.IndexedRecord
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.search.presentation.nav.PreviewSearchNavigator
import com.singularity.todo.feature.search.query.SimpleFilter
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What the search screen says when it has nothing, and what a result row says
 * about its project.
 *
 * The no-results state is the one place where the search can go wrong in a way
 * the user cannot undo: the answer is empty and the screen says so, and the way
 * out has to be there. Two ways out — the query that matched nothing, and the
 * filters that narrowed it to nothing — and they are different questions, so the
 * state offers a different action for each.
 *
 * The clear-filters action has to reach the *executed* query, not just the
 * displayed filter. That is proved in the ViewModel test; here the screen is
 * held to sending the filter without its structured constraints.
 *
 * The result row names the project the task belongs to — and says nothing at
 * all about a project when the task belongs to none.
 */
@Tag("slow")
@Tag("slow:compose")
class SearchResultContextUiTest {
    private val now = Instant.fromEpochMilliseconds(0)

    private fun noResultsState(activeFilter: SimpleFilter?) =
        SearchUiState(
            query = FieldOwnedText.empty().seeded("milk"),
            activeFilter = activeFilter,
        )

    @Test
    fun `a no-match without filters offers only the clear-query action`() =
        runIsolatedComposeTest {
            setContent {
                SearchScreenContent(
                    state = noResultsState(activeFilter = null),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SEARCH_EMPTY_RESULTS)
            assertTextDisplayed("Nothing matched this search.")
            assertTagDisplayed(TestTags.SEARCH_CLEAR_QUERY_BUTTON)
            // There is no filter to take away, so there is no action to take it away with.
            assertTagNotExists(TestTags.SEARCH_CLEAR_FILTERS_BUTTON)
        }

    @Test
    fun `a no-match with structured filters offers the clear-filters action`() =
        runIsolatedComposeTest {
            setContent {
                SearchScreenContent(
                    state =
                        noResultsState(
                            activeFilter =
                                SimpleFilter(
                                    priorities = setOf(TaskPriority.High),
                                    freeTextTerms = listOf("milk"),
                                ),
                        ),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SEARCH_EMPTY_RESULTS)
            assertTextDisplayed("Nothing matched with these filters.")
            assertTagDisplayed(TestTags.SEARCH_CLEAR_FILTERS_BUTTON)
            assertTagDisplayed(TestTags.SEARCH_CLEAR_QUERY_BUTTON)
        }

    @Test
    fun `the clear-filters action sends the filter without its structured constraints`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        noResultsState(
                            activeFilter =
                                SimpleFilter(
                                    priorities = setOf(TaskPriority.High),
                                    freeTextTerms = listOf("milk"),
                                ),
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            clickTag(TestTags.SEARCH_CLEAR_FILTERS_BUTTON)
            waitForIdle()

            val intent = intents.single()
            assertIs<SearchIntent.OnApplyFilter>(intent)
            // The structured constraints are gone; the free text is not.
            assertTrue(intent.filter.priorities.isEmpty())
            assertEquals(listOf("milk"), intent.filter.freeTextTerms)
        }

    @Test
    fun `the clear-query action clears the field`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state = noResultsState(activeFilter = null),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            clickTag(TestTags.SEARCH_CLEAR_QUERY_BUTTON)
            waitForIdle()

            // The field's own clear, which is what the trailing button does too.
            val intent = intents.single()
            assertIs<SearchIntent.OnQueryChange>(intent)
            assertEquals("", intent.query)
        }

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
