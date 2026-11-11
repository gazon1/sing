package com.singularity.todo.feature.tasks.presentation.components.detail

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.search.IndexedRecord
import com.singularity.todo.core.search.SearchTextIndex
import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.junit.jupiter.api.Tag as JUnitTag

@JUnitTag("fast")
class RelatedTaskPickerSearchTest {
    @Test
    fun `search candidates exclude current and existing relations and expand bounded fetch`() =
        runTest {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val uid = UserId("u1")
            val current = task("t-cur", "Alpha current", uid, now)
            val excluded = setOf(TaskId("t-ex1"), TaskId("t-ex2"))
            val wanted = task("t-want", "Alpha wanted", uid, now)
            val firstPage = (1..50).map { i -> task("t-$i", "Alpha noise $i", uid, now) }
            val index =
                object : SearchTextIndex by NoOpSearchTextIndex() {
                    override suspend fun recentTaskTitles(
                        query: TextMatchQuery,
                        limit: Int,
                    ): List<Task> =
                        when (limit) {
                            10 -> {
                                firstPage.take(10)
                            }

                            20 -> {
                                firstPage.take(20) + listOf(task("t-ex1", "Alpha related", uid, now))
                            }

                            40 -> {
                                firstPage.take(38) + listOf(task("t-ex2", "Alpha related 2", uid, now), current)
                            }

                            80 -> {
                                firstPage +
                                    listOf(
                                        task("t-ex1", "Alpha related", uid, now),
                                        task("t-ex2", "Alpha related 2", uid, now),
                                        current,
                                        wanted,
                                    )
                            }

                            else -> {
                                firstPage
                            }
                        }
                }

            val results =
                searchRelatedTaskCandidates(
                    rawQuery = "Alpha",
                    taskId = current.id,
                    existingRelationIds = excluded,
                    searchIndex = index,
                    resultLimit = 30,
                    initialFetchLimit = 10,
                    maxFetchLimit = 200,
                )

            assertTrue(results.any { it.id == wanted.id }, "expected wanted task after excluded results")
            assertTrue(results.none { it.id == current.id || it.id in excluded })
            assertTrue(results.size <= 30)
        }

    @Test
    fun `blank query returns empty without searching`() =
        runTest {
            val index = CountingSearchTextIndex()
            val results =
                searchRelatedTaskCandidates(
                    rawQuery = "   ",
                    taskId = TaskId("t-cur"),
                    existingRelationIds = emptySet(),
                    searchIndex = index,
                )
            assertTrue(results.isEmpty())
            assertEquals(0, index.calls)
        }

    private open class NoOpSearchTextIndex : SearchTextIndex {
        override fun observeTasks(query: TextMatchQuery): Flow<List<IndexedRecord<Task>>> = emptyFlow()

        override fun observeNotes(query: TextMatchQuery): Flow<List<IndexedRecord<Note>>> = emptyFlow()

        override fun observeProjects(query: TextMatchQuery): Flow<List<IndexedRecord<Project>>> = emptyFlow()

        override fun observeTags(query: TextMatchQuery): Flow<List<IndexedRecord<Tag>>> = emptyFlow()

        override suspend fun recentTaskTitles(
            query: TextMatchQuery,
            limit: Int,
        ): List<Task> = emptyList()

        override suspend fun recentNoteTitles(
            query: TextMatchQuery,
            limit: Int,
        ): List<Note> = emptyList()
    }

    private class CountingSearchTextIndex : NoOpSearchTextIndex() {
        var calls: Int = 0
            private set

        override suspend fun recentTaskTitles(
            query: TextMatchQuery,
            limit: Int,
        ): List<Task> {
            calls++
            return emptyList()
        }
    }

    private fun task(
        id: String,
        title: String,
        userId: UserId,
        at: Instant,
    ) = Task(
        id = TaskId(id),
        title = title,
        createdAt = at,
        updatedAt = at,
        userId = userId,
    )
}
