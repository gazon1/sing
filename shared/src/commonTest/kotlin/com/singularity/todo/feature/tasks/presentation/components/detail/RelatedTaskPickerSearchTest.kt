package com.singularity.todo.feature.tasks.presentation.components.detail

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.search.IndexedRecord
import com.singularity.todo.core.search.SearchTextIndex
import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.InMemoryTaskDao
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
            val excluded = (1..130).map { TaskId("t-ex$it") }.toSet()
            val excludedTasks = excluded.map { task(it.value, "Alpha excluded", uid, now) }
            val wanted = task("t-want", "Alpha wanted", uid, now)
            // Pages of 30 and 60 are filled entirely with excluded rows, so only the
            // expanding fetch can reach the page that carries the wanted task.
            val index =
                object : SearchTextIndex by NoOpSearchTextIndex() {
                    override suspend fun recentTaskTitles(
                        query: TextMatchQuery,
                        limit: Int,
                    ): List<Task> =
                        if (limit < 120) {
                            excludedTasks.take(limit)
                        } else {
                            listOf(wanted) + excludedTasks.take(40) + current
                        }
                }

            val results =
                searchRelatedTaskCandidates(
                    rawQuery = "Alpha",
                    taskId = current.id,
                    existingRelationIds = excluded,
                    searchIndex = index,
                    isValidCandidate = { true },
                    resultLimit = 30,
                    initialFetchLimit = 10,
                    maxFetchLimit = 200,
                )

            assertTrue(results.any { it.id == wanted.id }, "expected wanted task after excluded results")
            assertTrue(results.none { it.id == current.id || it.id in excluded })
            assertTrue(results.size <= 30)
        }

    @Test
    fun `candidates the domain refuses are dropped and asked about once per search`() =
        runTest {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val uid = UserId("u1")
            val excluded = (1..40).map { TaskId("t-ex$it") }.toSet()
            val excludedTasks = excluded.map { task(it.value, "Alpha excluded", uid, now) }
            val rejected = task("t-cycle", "Alpha cycle", uid, now)
            val kept = task("t-keep", "Alpha keep", uid, now)
            val asked = mutableListOf<TaskId>()
            // The rejected candidate appears in every page, so a cache is the only
            // thing that keeps the validator from being asked about it twice.
            val index =
                object : NoOpSearchTextIndex() {
                    override suspend fun recentTaskTitles(
                        query: TextMatchQuery,
                        limit: Int,
                    ): List<Task> =
                        if (limit < 60) {
                            listOf(rejected) + excludedTasks.take(limit - 1)
                        } else {
                            listOf(rejected, kept) + excludedTasks.take(10)
                        }
                }

            val results =
                searchRelatedTaskCandidates(
                    rawQuery = "Alpha",
                    taskId = TaskId("t-cur"),
                    existingRelationIds = excluded,
                    searchIndex = index,
                    isValidCandidate = { id ->
                        asked += id
                        id != rejected.id
                    },
                    resultLimit = 30,
                    initialFetchLimit = 10,
                    maxFetchLimit = 200,
                )

            assertEquals(listOf(kept), results)
            assertEquals(1, asked.count { it == rejected.id }, "the rejected candidate must be validated once")
            assertEquals(1, asked.count { it == kept.id }, "the kept candidate must be validated once")
        }

    @Test
    fun `the real validator drops the candidate that closes a cycle and keeps the independent one`() =
        runTest {
            // The chain the picker has to respect: B → C → A. Offering B as a new
            // relation of A would close A → B → C → A, so the domain refuses it.
            // D heads no chain, so it stays.
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val dao = InMemoryTaskDao()
            dao.upsertDependencyForUser("t-b", "t-c", DependencyVerb.BLOCKS.name, uid.value)
            dao.upsertDependencyForUser("t-c", "t-a", DependencyVerb.BLOCKS.name, uid.value)
            val validator = DependencyValidatorImpl(dao, currentUser())

            val cycleCloser = task("t-b", "Alpha cycle closer", uid, now)
            val independent = task("t-d", "Alpha independent", uid, now)
            val index =
                object : NoOpSearchTextIndex() {
                    override suspend fun recentTaskTitles(
                        query: TextMatchQuery,
                        limit: Int,
                    ): List<Task> = listOf(cycleCloser, independent)
                }

            val results =
                searchRelatedTaskCandidates(
                    rawQuery = "Alpha",
                    taskId = TaskId("t-a"),
                    existingRelationIds = emptySet(),
                    searchIndex = index,
                    isValidCandidate = { id ->
                        validator.assertNoCycles(TaskId("t-a"), setOf(id)).isSuccess
                    },
                )

            assertEquals(
                listOf(independent.id),
                results.map { it.id },
                "the cycle-closing candidate is refused by the real validator, the other is kept",
            )
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
                    isValidCandidate = { true },
                )
            assertTrue(results.isEmpty())
            assertEquals(0, index.calls)
        }

    @Test
    fun `results keep the order the index returned`() =
        runTest {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val uid = UserId("u1")
            val ordered = (1..5).map { task("t-$it", "Alpha $it", uid, now) }
            val index =
                object : NoOpSearchTextIndex() {
                    override suspend fun recentTaskTitles(
                        query: TextMatchQuery,
                        limit: Int,
                    ): List<Task> = ordered
                }

            val results =
                searchRelatedTaskCandidates(
                    rawQuery = "Alpha",
                    taskId = TaskId("t-cur"),
                    existingRelationIds = emptySet(),
                    searchIndex = index,
                    isValidCandidate = { true },
                )

            assertEquals(ordered.map { it.id }, results.map { it.id })
        }

    @Test
    fun `a short page ends the search without fetching again`() =
        runTest {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val uid = UserId("u1")
            val limits = mutableListOf<Int>()
            val page = (1..5).map { task("t-$it", "Alpha $it", uid, now) }
            val index =
                object : NoOpSearchTextIndex() {
                    override suspend fun recentTaskTitles(
                        query: TextMatchQuery,
                        limit: Int,
                    ): List<Task> {
                        limits += limit
                        return page
                    }
                }

            val results =
                searchRelatedTaskCandidates(
                    rawQuery = "Alpha",
                    taskId = TaskId("t-cur"),
                    existingRelationIds = emptySet(),
                    searchIndex = index,
                    isValidCandidate = { true },
                    resultLimit = 30,
                    initialFetchLimit = 50,
                    maxFetchLimit = 500,
                )

            assertEquals(page.map { it.id }, results.map { it.id })
            assertEquals(listOf(50), limits, "a page shorter than the fetch limit is the last one")
        }

    @Test
    fun `the fetch stops at the maximum when every candidate is excluded`() =
        runTest {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val uid = UserId("u1")
            val limits = mutableListOf<Int>()
            val excluded = (1..200).map { TaskId("t-ex$it") }.toSet()
            val index =
                object : NoOpSearchTextIndex() {
                    override suspend fun recentTaskTitles(
                        query: TextMatchQuery,
                        limit: Int,
                    ): List<Task> {
                        limits += limit
                        return (1..limit).map { task("t-ex$it", "Alpha excluded", uid, now) }
                    }
                }

            val results =
                searchRelatedTaskCandidates(
                    rawQuery = "Alpha",
                    taskId = TaskId("t-cur"),
                    existingRelationIds = excluded,
                    searchIndex = index,
                    isValidCandidate = { true },
                    resultLimit = 30,
                    initialFetchLimit = 10,
                    maxFetchLimit = 40,
                )

            assertTrue(results.isEmpty())
            assertEquals(listOf(30, 40), limits, "the fetch doubles but never passes the cap")
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

    private fun currentUser(): ProfileAwareCurrentUser =
        FakeProfileAwareCurrentUser(authRepository = FakeAuthRepository(Session.Anonymous(uid)))

    private companion object {
        /** The identity the in-memory DAO is seeded under. */
        val uid: UserId = UserId("picker-search-user")
    }
}
