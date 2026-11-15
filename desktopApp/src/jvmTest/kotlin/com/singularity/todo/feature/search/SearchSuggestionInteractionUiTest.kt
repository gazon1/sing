@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.search.IndexedRecord
import com.singularity.todo.core.search.SearchMode
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
import com.singularity.todo.feature.search.presentation.nav.PreviewSearchNavigator
import com.singularity.todo.feature.search.presentation.nav.SearchNavigator
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagFocused
import com.singularity.todo.test.helpers.assertTagNotFocused
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickTagFirst
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.countNodesWithTagInAnyRoot
import com.singularity.todo.test.helpers.dragUpOnTag
import com.singularity.todo.test.helpers.longClickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

// The domain `Tag` shares its name with JUnit's annotation, and the annotation is
// the one this file spells the usual way: `@Tag("slow")` on the class.
private typealias TagRecord = com.singularity.todo.feature.tags.Tag

@Tag("slow")
@Tag("slow:compose")
class SearchSuggestionInteractionUiTest {
    private val now = Instant.fromEpochMilliseconds(0)

    private fun savedSearch(
        id: String,
        name: String,
        query: String,
    ) = SavedSearch(
        id = SavedSearchId.fromString(id),
        userId = UserId("user-a"),
        name = name,
        queryString = query,
        createdAt = now,
        updatedAt = now,
    )

    private val work = savedSearch("s-work", "Work", "tag:work")
    private val overdue = savedSearch("s-overdue", "Overdue", "is:overdue")
    private val searches: ImmutableList<SavedSearch> = persistentListOf(overdue, work)

    private fun task(title: String) =
        Task(
            id = TaskId("t-${title.lowercase().replace(' ', '-')}"),
            title = title,
            createdAt = now,
            updatedAt = now,
            userId = UserId("user-a"),
        )

    private fun note(title: String) =
        Note(
            id = NoteId("n-${title.lowercase().replace(' ', '-')}"),
            userId = UserId("user-a"),
            title = title,
            createdAt = now,
            updatedAt = now,
        )

    private fun project(name: String) =
        Project(
            id = ProjectId("p-${name.lowercase().replace(' ', '-')}"),
            name = name,
            color = 0,
            createdAt = now,
            updatedAt = now,
            userId = UserId("user-a"),
        )

    private fun tag(name: String) =
        TagRecord(
            id = TagId("g-${name.lowercase().replace(' ', '-')}"),
            name = name,
            color = 0,
            createdAt = now,
            updatedAt = now,
            userId = UserId("user-a"),
        )

    private fun resultsOf(vararg titles: String) =
        SearchResults(
            tasks = titles.map { IndexedRecord(task(it)) }.toImmutableList(),
            notes = persistentListOf(),
            projects = persistentListOf(),
            tags = persistentListOf(),
        )

    /** A navigator that remembers what the screen asked it to open. */
    private class RecordingSearchNavigator : SearchNavigator({}) {
        val openedTasks = mutableListOf<TaskId>()
        val openedNotes = mutableListOf<NoteId>()
        val openedProjects = mutableListOf<ProjectId>()
        val openedTags = mutableListOf<TagId>()

        override fun openTask(taskId: TaskId) {
            openedTasks += taskId
        }

        override fun openNote(noteId: NoteId) {
            openedNotes += noteId
        }

        override fun openProject(projectId: ProjectId) {
            openedProjects += projectId
        }

        override fun openTag(tagId: TagId) {
            openedTags += tagId
        }
    }

    @Test
    fun `selecting a suggestion loads it and dismisses suggestions`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("wo"),
                            savedSearches = searches,
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            clickTag(TestTags.SavedSearches.chip("Work"))
            waitForIdle()

            assertEquals(work.id, intents.filterIsInstance<SearchIntent.OnLoadSavedSearch>().single().id)
            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
        }

    @Test
    fun `outside tap dismisses suggestions without changing the query`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("zzzz"),
                            savedSearches = searches,
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.NO_MATCH)
            assertTagFocused(TestTags.SEARCH_INPUT)
            clickTag(TestTags.SEARCH_EMPTY_RESULTS)
            waitForIdle()
            awaitTagGone(TestTags.SavedSearches.NO_MATCH)
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
            // The tap takes the keyboard away from the field, and leaves the text alone.
            assertTagNotFocused(TestTags.SEARCH_INPUT)
            assertTagTextEquals(TestTags.SEARCH_INPUT, "zzzz")
            assertEquals(emptyList<SearchIntent>(), intents)
        }

    @Test
    fun `outside tap dismisses suggestions with empty query`() =
        runIsolatedComposeTest {
            setContent {
                SearchScreenContent(
                    state = SearchUiState(query = FieldOwnedText.empty(), savedSearches = searches),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            clickTag(TestTags.SEARCH_EMPTY_PLACEHOLDER)
            waitForIdle()

            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            awaitTagGone(TestTags.SavedSearches.chip("Overdue"))
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
        }

    @Test
    fun `opening filter dismisses suggestions without changing the query`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("work"),
                            savedSearches = searches,
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            clickContentDescription("Filter")
            waitForIdle()

            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            // The sheet is a window of its own on desktop, and its position depends on
            // the window the test mounts. The sheet having been opened is asked of the
            // node's presence in any root, not of where that window put it.
            assertTrue(countNodesWithTagInAnyRoot(TestTags.SearchFilter.APPLY_BUTTON) > 0)
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
            assertEquals(emptyList<SearchIntent>(), intents)
        }

    @Test
    fun `focusing the input restores dismissed suggestions`() =
        runIsolatedComposeTest {
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("work"),
                            savedSearches = searches,
                        ),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            clickTag(TestTags.SEARCH_MODE_CONTAINS)
            waitForIdle()
            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertTagNotFocused(TestTags.SEARCH_INPUT)

            clickTag(TestTags.SEARCH_INPUT)
            waitForIdle()

            assertTagFocused(TestTags.SEARCH_INPUT)
            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
        }

    @Test
    fun `switching mode dismisses suggestions without changing the query`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("work"),
                            savedSearches = searches,
                            mode = SearchMode.Word,
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            clickTag(TestTags.SEARCH_MODE_CONTAINS)
            waitForIdle()

            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertEquals(SearchMode.Contains, intents.filterIsInstance<SearchIntent.OnModeChange>().single().mode)
            assertEquals(
                emptyList<SearchIntent.OnQueryChange>(),
                intents.filterIsInstance<SearchIntent.OnQueryChange>(),
            )
        }

    @Test
    fun `clear restores suggestions after dismissal`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            // The smallest state the screen's own contract needs: what the ViewModel
            // does with `OnQueryChange` — write the text and drop the loaded search —
            // is done here, so the clear is a real transition rather than a new screen.
            var state by
                mutableStateOf(
                    SearchUiState(
                        query = FieldOwnedText.empty().seeded(work.queryString),
                        activeSavedSearchId = work.id,
                        savedSearches = searches,
                    ),
                )
            setContent {
                SearchScreenContent(
                    state = state,
                    onIntent = { intent ->
                        intents += intent
                        if (intent is SearchIntent.OnQueryChange) {
                            state =
                                state.copy(
                                    query = FieldOwnedText.empty().echoed(intent.query),
                                    activeSavedSearchId = null,
                                )
                        }
                    },
                    navigator = PreviewSearchNavigator(),
                )
            }

            clickTag(TestTags.SEARCH_MODE_CONTAINS)
            waitForIdle()
            awaitTagGone(TestTags.SavedSearches.chip("Work"))

            clickTag(TestTags.SEARCH_CLEAR_BUTTON)
            waitForIdle()

            assertEquals("", intents.filterIsInstance<SearchIntent.OnQueryChange>().last().query)
            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.chip("Overdue"))
        }

    @Test
    fun `loading a saved search with an empty query keeps suggestions dismissed`() =
        runIsolatedComposeTest {
            // A saved search may carry no query at all: saving the screen while the
            // field is empty stores exactly that. Selecting it empties the field, and
            // an empty field is also what a clear leaves behind — the selection must
            // not read as a clear and bring back the row it just dismissed.
            val empty = savedSearch("s-empty", "Work every time", "")
            var state by
                mutableStateOf(
                    SearchUiState(
                        query = FieldOwnedText.empty().seeded("work"),
                        savedSearches = persistentListOf(empty),
                    ),
                )
            setContent {
                SearchScreenContent(
                    state = state,
                    onIntent = { intent ->
                        if (intent is SearchIntent.OnLoadSavedSearch) {
                            state =
                                state.copy(
                                    query = FieldOwnedText.empty().seeded(empty.queryString),
                                    activeSavedSearchId = empty.id,
                                )
                        }
                    },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work every time"))
            clickTag(TestTags.SavedSearches.chip("Work every time"))
            waitForIdle()

            awaitTagGone(TestTags.SavedSearches.chip("Work every time"))
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
        }

    @Test
    fun `opening a menu keeps suggestions visible`() =
        runIsolatedComposeTest {
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("work"),
                            savedSearches = searches,
                        ),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            // The query narrows the row to the one search it matches, and the menu
            // belongs to that chip. Taking the field's focus away is not a dismissal
            // while the menu it opened is up.
            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            longClickTag(TestTags.SavedSearches.chip("Work"))
            waitForIdle()

            assertTrue(countNodesWithTagInAnyRoot(TestTags.SavedSearches.SEARCH_MENU_DELETE) > 0)
            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
        }

    @Test
    fun `save opens the dialog after the suggestions are dismissed`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("work"),
                            savedSearches = searches,
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            clickTag(TestTags.SEARCH_MODE_CONTAINS)
            waitForIdle()
            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)

            clickTag(TestTags.SavedSearches.SAVE_CHIP)
            waitForIdle()

            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.SAVE_DIALOG_CONFIRM)
            assertEquals(
                emptyList<SearchIntent.OnQueryChange>(),
                intents.filterIsInstance<SearchIntent.OnQueryChange>(),
            )
        }

    @Test
    fun `renaming from the menu does not change the query`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("work"),
                            savedSearches = searches,
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            longClickTag(TestTags.SavedSearches.chip("Work"))
            waitForIdle()
            clickTagFirst(TestTags.SavedSearches.SEARCH_MENU_RENAME_BUTTON)
            waitForIdle()

            assertTagDisplayed(TestTags.SavedSearches.RENAME_DIALOG_CONFIRM)
            assertEquals(
                emptyList<SearchIntent.OnQueryChange>(),
                intents.filterIsInstance<SearchIntent.OnQueryChange>(),
            )
        }

    @Test
    fun `deleting from the menu takes the chip off the row`() =
        runIsolatedComposeTest {
            var savedSearches by mutableStateOf(searches)
            setContent {
                SearchScreenContent(
                    state = SearchUiState(savedSearches = savedSearches),
                    onIntent = { intent ->
                        if (intent is SearchIntent.OnDeleteSavedSearch) {
                            savedSearches = savedSearches.filterNot { it.id == intent.id }.toImmutableList()
                        }
                    },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            longClickTag(TestTags.SavedSearches.chip("Work"))
            waitForIdle()
            clickTagFirst(TestTags.SavedSearches.SEARCH_MENU_DELETE)

            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.chip("Overdue"))
        }

    /**
     * A result is a control of its own, and the dismissal must not take its click.
     *
     * All four kinds are exercised because each draws its own `onClick`: a wiring
     * missed in one of them would open the record and leave the row over the answer
     * the person just opened. The project and the tag are the same card, but the
     * callback is a different one, so each is asked.
     */
    @Test
    fun `every kind of result navigates and dismisses the suggestions`() =
        runIsolatedComposeTest {
            val navigator = RecordingSearchNavigator()
            val task = task("Buy milk")
            val note = note("Note one")
            val project = project("Project one")
            val tag = tag("Tag one")
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("work"),
                            savedSearches = searches,
                            results =
                                SearchResults(
                                    tasks = persistentListOf(IndexedRecord(task)),
                                    notes = persistentListOf(IndexedRecord(note)),
                                    projects = persistentListOf(IndexedRecord(project)),
                                    tags = persistentListOf(IndexedRecord(tag)),
                                ),
                        ),
                    onIntent = {},
                    navigator = navigator,
                )
            }

            fun showSuggestionsAgain() {
                clickTag(TestTags.SEARCH_INPUT)
                waitForIdle()
                assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            clickTag(TestTags.taskItem("Buy milk"))
            waitForIdle()
            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertEquals(listOf(task.id), navigator.openedTasks)

            showSuggestionsAgain()
            clickText("Note one")
            waitForIdle()
            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertEquals(listOf(note.id), navigator.openedNotes)

            showSuggestionsAgain()
            clickText("Project one")
            waitForIdle()
            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertEquals(listOf(project.id), navigator.openedProjects)

            showSuggestionsAgain()
            clickText("Tag one")
            waitForIdle()
            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertEquals(listOf(tag.id), navigator.openedTags)
        }

    /**
     * A pin is a control inside a result card, not the card: the action runs and
     * the row stays. A results-wide tap handler that swallowed the button would
     * lose the pin.
     */
    @Test
    fun `pinning a result runs the action and keeps the suggestions`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            val task = task("Buy milk")
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("work"),
                            savedSearches = searches,
                            results =
                                SearchResults(
                                    tasks = persistentListOf(IndexedRecord(task)),
                                    notes = persistentListOf(),
                                    projects = persistentListOf(),
                                    tags = persistentListOf(),
                                ),
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            clickTag(TestTags.TASK_CARD_PIN_BUTTON)
            waitForIdle()

            assertEquals(
                listOf(task.id),
                intents.filterIsInstance<SearchIntent.OnTogglePin>().map { it.taskId },
            )
            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
        }

    @Test
    fun `dragging the results does not dismiss suggestions`() =
        runIsolatedComposeTest {
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("work"),
                            savedSearches = searches,
                            results = resultsOf("Buy milk", "Buy bread"),
                            isSearching = false,
                        ),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            dragUpOnTag(TestTags.taskItem("Buy milk"))
            waitForIdle()

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
        }

    @Test
    fun `editing a loaded saved search dispatches the query the field holds`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("tag:work"),
                            activeSavedSearchId = work.id,
                            savedSearches = searches,
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagTextEquals(TestTags.SEARCH_INPUT, "tag:work")
            typeIntoTag(TestTags.SEARCH_INPUT, "tag:work x")
            waitForIdle()

            assertEquals("tag:work x", intents.filterIsInstance<SearchIntent.OnQueryChange>().last().query)
        }
}
