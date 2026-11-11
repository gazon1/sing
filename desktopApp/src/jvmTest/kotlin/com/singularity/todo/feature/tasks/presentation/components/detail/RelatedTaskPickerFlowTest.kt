package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.search.IndexedRecord
import com.singularity.todo.core.search.SearchTextIndex
import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeSearchTextIndex
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.awaitAnyDisplayed
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.junit.jupiter.api.Tag as JUnitTag

@OptIn(ExperimentalTestApi::class)
@JUnitTag("fast")
class RelatedTaskPickerFlowTest {
    @Test
    fun `related tasks section shows fallback for unavailable relation and allows remove only when present`() =
        runIsolatedComposeTest {
            val removed = mutableListOf<TaskId>()
            val opened = mutableListOf<TaskId>()
            setContent {
                RelatedTasksSection(
                    items =
                        persistentListOf(
                            RelatedTaskItemUi(
                                id = TaskId("missing"),
                                title = "Unavailable task",
                                verb = DependencyVerb.BLOCKS,
                                projectName = null,
                                canOpen = false,
                            ),
                        ),
                    onAdd = {},
                    onRemove = { removed += it },
                    onOpen = { opened += it },
                )
            }

            awaitAnyDisplayed(TestTags.relatedTaskItem("missing"))
            assertTagDisplayed(TestTags.relatedTaskRemove("missing"))
            onNodeWithTag(TestTags.relatedTaskItem("missing"), useUnmergedTree = true)
                .assertTextContains("Unavailable task", substring = true)

            clickTag(TestTags.relatedTaskRemove("missing"))
            waitForIdle()
            assertEquals(listOf(TaskId("missing")), removed)

            clickTag(TestTags.relatedTaskItem("missing"))
            waitForIdle()
            assertTrue(opened.isEmpty(), "missing relation should not be opened")
        }

    @Test
    fun `picker adds one task then reopen excludes it and can add another`() =
        runIsolatedComposeTest {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val uid = UserId("u1")
            val taskRepo =
                FakeTaskRepository().apply {
                    add(task("t-cur", "Current", uid, now))
                    add(task("t-a", "Alpha one", uid, now, projectId = ProjectId("p1")))
                    add(task("t-b", "Alpha two", uid, now, projectId = ProjectId("p1")))
                }
            val projectRepo =
                FakeProjectsRepository().apply {
                    seed(
                        Project(
                            id = ProjectId("p1"),
                            name = "Project X",
                            color = 0,
                            createdAt = now,
                            updatedAt = now,
                            userId = uid,
                        ),
                    )
                }
            val index = FakeSearchTextIndex(taskRepo, FakeNotesRepository(), projectRepo, FakeTagsRepository())
            val added = mutableListOf<Pair<TaskId, DependencyVerb>>()

            mainClock.autoAdvance = false
            setContent {
                PickerHarness(
                    taskId = TaskId("t-cur"),
                    searchIndex = index,
                    projectsRepo = projectRepo,
                    onAdded = { id, verb -> added += id to verb },
                )
            }

            clickTag("open_picker")
            awaitAnyDisplayed(TestTags.RELATED_TASK_PICKER_SEARCH)
            typeIntoTag(TestTags.RELATED_TASK_PICKER_SEARCH, "Alpha")
            mainClock.advanceTimeBy(350)
            waitForIdle()

            awaitAnyDisplayed(TestTags.relatedTaskCandidate("t-a"))
            awaitAnyDisplayed(TestTags.relatedTaskCandidate("t-b"))
            onNodeWithTag(TestTags.relatedTaskCandidate("t-a"), useUnmergedTree = true)
                .assertTextContains("Project X", substring = true)

            clickTag(TestTags.relatedTaskCandidate("t-a"))
            clickTag(TestTags.RELATED_TASK_PICKER_ADD)
            waitForIdle()
            assertEquals(listOf(TaskId("t-a") to DependencyVerb.BLOCKS), added)

            clickTag("open_picker")
            awaitAnyDisplayed(TestTags.RELATED_TASK_PICKER_SEARCH)
            typeIntoTag(TestTags.RELATED_TASK_PICKER_SEARCH, "Alpha")
            mainClock.advanceTimeBy(350)
            waitForIdle()

            awaitAnyDisplayed(TestTags.relatedTaskCandidate("t-b"))
            assertTagNotExists(TestTags.relatedTaskCandidate("t-a"))
            clickTag(TestTags.relatedTaskCandidate("t-b"))
            clickTag(TestTags.RELATED_TASK_PICKER_ADD)
            waitForIdle()
            assertEquals(
                listOf(
                    TaskId("t-a") to DependencyVerb.BLOCKS,
                    TaskId("t-b") to DependencyVerb.BLOCKS,
                ),
                added,
            )
        }

    @Test
    fun `picker handles idle no results clear and error states`() =
        runIsolatedComposeTest {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val uid = UserId("u1")
            val taskRepo =
                FakeTaskRepository().apply {
                    add(task("t-cur", "Current", uid, now))
                    add(task("t-cand", "Alpha candidate", uid, now))
                }
            val projectRepo = FakeProjectsRepository()
            val okIndex = FakeSearchTextIndex(taskRepo, FakeNotesRepository(), projectRepo, FakeTagsRepository())
            val failingIndex = ThrowingSearchIndex()

            mainClock.autoAdvance = false
            setContent {
                var mode by remember { mutableStateOf("ok") }
                Column {
                    TextButton(onClick = { mode = "ok" }, modifier = Modifier.testTag("mode_ok")) { Text("ok") }
                    TextButton(onClick = { mode = "fail" }, modifier = Modifier.testTag("mode_fail")) { Text("fail") }
                    RelatedTaskPickerSheet(
                        taskId = TaskId("t-cur"),
                        existingRelationIds = persistentSetOf(),
                        onAdd = { _, _ -> },
                        onDismiss = {},
                        searchIndex = if (mode == "ok") okIndex else failingIndex,
                        projectsRepo = projectRepo,
                    )
                }
            }

            awaitAnyDisplayed(TestTags.RELATED_TASK_PICKER_SEARCH)
            awaitText("Type to find a task to relate")

            typeIntoTag(TestTags.RELATED_TASK_PICKER_SEARCH, "zzznomatch")
            mainClock.advanceTimeBy(350)
            waitForIdle()
            awaitText("No matching tasks")

            clickTag(TestTags.RELATED_TASK_PICKER_CLEAR)
            mainClock.advanceTimeBy(350)
            waitForIdle()
            awaitText("Type to find a task to relate")

            clickTag("mode_fail")
            waitForIdle()
            typeIntoTag(TestTags.RELATED_TASK_PICKER_SEARCH, "Alpha")
            mainClock.advanceTimeBy(350)
            waitForIdle()
            awaitText("boom")
            awaitText("Edit or clear the query to try again.")
        }

    @Composable
    private fun PickerHarness(
        taskId: TaskId,
        searchIndex: SearchTextIndex,
        projectsRepo: FakeProjectsRepository,
        onAdded: (TaskId, DependencyVerb) -> Unit,
    ) {
        var show by remember { mutableStateOf(false) }
        var existing by remember { mutableStateOf<ImmutableSet<TaskId>>(persistentSetOf()) }
        Column {
            TextButton(onClick = { show = true }, modifier = Modifier.testTag("open_picker")) {
                Text("open")
            }
            if (show) {
                RelatedTaskPickerSheet(
                    taskId = taskId,
                    existingRelationIds = existing,
                    onAdd = { id, verb ->
                        onAdded(id, verb)
                        existing = (existing + id).toImmutableSet()
                        show = false
                    },
                    onDismiss = { show = false },
                    searchIndex = searchIndex,
                    projectsRepo = projectsRepo,
                )
            }
        }
    }

    private class ThrowingSearchIndex : SearchTextIndex {
        override fun observeTasks(query: TextMatchQuery): Flow<List<IndexedRecord<Task>>> = emptyFlow()

        override fun observeNotes(query: TextMatchQuery): Flow<List<IndexedRecord<Note>>> = emptyFlow()

        override fun observeProjects(query: TextMatchQuery): Flow<List<IndexedRecord<Project>>> = emptyFlow()

        override fun observeTags(query: TextMatchQuery): Flow<List<IndexedRecord<Tag>>> = emptyFlow()

        override suspend fun recentTaskTitles(
            query: TextMatchQuery,
            limit: Int,
        ): List<Task> = error("boom")

        override suspend fun recentNoteTitles(
            query: TextMatchQuery,
            limit: Int,
        ): List<Note> = emptyList()
    }

    private fun task(
        id: String,
        title: String,
        userId: UserId,
        at: Instant,
        projectId: ProjectId? = null,
    ) = Task(
        id = TaskId(id),
        title = title,
        createdAt = at,
        updatedAt = at,
        userId = userId,
        projectId = projectId,
    )
}
