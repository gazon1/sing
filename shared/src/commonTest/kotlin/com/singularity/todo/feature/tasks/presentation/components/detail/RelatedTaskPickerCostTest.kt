package com.singularity.todo.feature.tasks.presentation.components.detail

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.search.IndexedRecord
import com.singularity.todo.core.search.SearchTextIndex
import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.DependencyValidator
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

/**
 * What a search costs, measured in ordering-graph reads.
 *
 * Every candidate a search offers is checked by [DependencyValidator.assertNoCycles],
 * and that check walks the candidate's ordering graph. The cache inside
 * [searchRelatedTaskCandidates] stops the *same* candidate being asked about twice;
 * it does not merge the walks of different candidates, so the cost of a search is the
 * sum of the walks it provokes.
 *
 * These are the measurements the plan asks for, turned into assertions: the numbers
 * are recorded here rather than estimated, and a change that turns the walk into
 * something unbounded fails instead of merely getting slower.
 */
@JUnitTag("fast")
class RelatedTaskPickerCostTest {
    private val uid = UserId("cost-user")
    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun currentUser(): ProfileAwareCurrentUser =
        FakeProfileAwareCurrentUser(authRepository = FakeAuthRepository(Session.Anonymous(uid)))

    @Test
    fun `thirty allowed candidates over a long chain cost one walk each`() =
        runTest {
            // Each candidate heads its own chain of `CHAIN` nodes. The walk from a
            // candidate reaches its chain's end and never reaches the current task,
            // so the whole chain is read — that is the worst case for one candidate.
            val candidates = (1..30).map { "t-c$it" }
            val dao = InMemoryTaskDao()
            candidates.forEach { head -> seedChain(dao, head, CHAIN) }
            val counting = CountingTaskDao(dao)

            val results = search(candidates, counting)

            assertEquals(30, results.size)
            assertEquals(
                30 * (CHAIN + 1),
                counting.orderingEdgeReads,
                "one walk per candidate, each reading the candidate and its whole chain",
            )
        }

    @Test
    fun `candidates the domain refuses still cost their walk`() =
        runTest {
            // Every candidate closes a cycle back to the current task, so each is
            // refused — and refusal only comes at the end of the walk. The cache keeps
            // it to one walk per candidate even though the fetch expands over them.
            val candidates = (1..30).map { "t-c$it" }
            val dao = InMemoryTaskDao()
            candidates.forEach { head -> seedChainToCurrent(dao, head, CHAIN) }
            val counting = CountingTaskDao(dao)

            val results = search(candidates, counting, initialFetchLimit = 10, maxFetchLimit = 500)

            assertTrue(results.isEmpty(), "every candidate would close a cycle")
            assertEquals(
                candidates.size * (CHAIN + 1),
                counting.orderingEdgeReads,
                "each refused candidate is walked once, to the task that refuses it",
            )
        }

    @Test
    fun `the walk stops at the current task instead of reading the rest of the graph`() =
        runTest {
            // A candidate whose chain passes through the current task: the walk must
            // stop there, not keep reading the nodes behind it.
            val dao = InMemoryTaskDao()
            dao.upsertDependencyForUser("t-c1", "t-cur", "BLOCKS", uid.value)
            (1..CHAIN).forEach { dao.upsertDependencyForUser("t-cur", "t-tail$it", "BLOCKS", uid.value) }
            val counting = CountingTaskDao(dao)

            val results = search(listOf("t-c1"), counting)

            assertTrue(results.isEmpty())
            assertEquals(1, counting.orderingEdgeReads, "the refusal is found on the first hop")
        }

    @Test
    fun `a branching graph is read once per node, not once per path`() =
        runTest {
            // A diamond repeated across the chain: two paths reach the same node, and
            // the visited set is what keeps that node from being read twice.
            val dao = InMemoryTaskDao()
            dao.upsertDependencyForUser("t-c1", "t-a", "BLOCKS", uid.value)
            dao.upsertDependencyForUser("t-c1", "t-b", "BLOCKS", uid.value)
            dao.upsertDependencyForUser("t-a", "t-join", "BLOCKS", uid.value)
            dao.upsertDependencyForUser("t-b", "t-join", "BLOCKS", uid.value)
            val counting = CountingTaskDao(dao)

            val results = search(listOf("t-c1"), counting)

            assertEquals(1, results.size)
            assertEquals(4, counting.orderingEdgeReads, "c1, a, b and join are each read once")
        }

    @Test
    fun `an already related task is excluded without being validated`() =
        runTest {
            // The exclusion is decided before the validator is asked, so a task the
            // picker already relates costs no graph walk at all — its chain is never
            // read even though the chain exists.
            val dao = InMemoryTaskDao()
            seedChain(dao, "t-rel", CHAIN)
            val counting = CountingTaskDao(dao)
            val index = listIndex(listOf(task("t-rel")))

            val results =
                searchRelatedTaskCandidates(
                    rawQuery = "Alpha",
                    taskId = TaskId("t-cur"),
                    existingRelationIds = setOf(TaskId("t-rel")),
                    searchIndex = index,
                    isValidCandidate = { id ->
                        DependencyValidatorImpl(counting, currentUser())
                            .assertNoCycles(TaskId("t-cur"), setOf(id))
                            .isSuccess
                    },
                )

            assertTrue(results.isEmpty())
            assertEquals(0, counting.orderingEdgeReads, "an excluded candidate must not be walked")
        }

    @Test
    fun `a candidate met again by the expanding fetch is walked once`() =
        runTest {
            // The index answers the same first row on every page and fills the rest
            // with tasks the picker already relates. Expansion therefore walks over
            // the same candidate four times — and the cache is what keeps that to one
            // walk, so the reads stay at the candidate plus its chain.
            val dao = InMemoryTaskDao()
            seedChain(dao, "t-shared", CHAIN)
            val counting = CountingTaskDao(dao)
            val excluded = (1..40).map { TaskId("t-ex$it") }
            val filler = excluded.map { task(it.value) }
            val shared = task("t-shared")
            val index =
                object : SearchTextIndex by listIndex(emptyList()) {
                    override suspend fun recentTaskTitles(
                        query: TextMatchQuery,
                        limit: Int,
                    ): List<Task> = (listOf(shared) + filler).take(limit)
                }

            val results =
                searchRelatedTaskCandidates(
                    rawQuery = "Alpha",
                    taskId = TaskId("t-cur"),
                    existingRelationIds = excluded.toSet(),
                    searchIndex = index,
                    isValidCandidate = { id ->
                        DependencyValidatorImpl(counting, currentUser())
                            .assertNoCycles(TaskId("t-cur"), setOf(id))
                            .isSuccess
                    },
                    initialFetchLimit = 10,
                    maxFetchLimit = 40,
                )

            assertEquals(listOf(shared.id), results.map { it.id })
            assertEquals(
                CHAIN + 1,
                counting.orderingEdgeReads,
                "four pages met the same candidate; it is walked once, not four times",
            )
        }

    private fun listIndex(tasks: List<Task>): SearchTextIndex =
        object : SearchTextIndex {
            override fun observeTasks(query: TextMatchQuery): Flow<List<IndexedRecord<Task>>> = emptyFlow()

            override fun observeNotes(query: TextMatchQuery): Flow<List<IndexedRecord<Note>>> = emptyFlow()

            override fun observeProjects(query: TextMatchQuery): Flow<List<IndexedRecord<Project>>> = emptyFlow()

            override fun observeTags(query: TextMatchQuery): Flow<List<IndexedRecord<Tag>>> = emptyFlow()

            override suspend fun recentTaskTitles(
                query: TextMatchQuery,
                limit: Int,
            ): List<Task> = tasks.take(limit)

            override suspend fun recentNoteTitles(
                query: TextMatchQuery,
                limit: Int,
            ): List<Note> = emptyList()
        }

    private suspend fun search(
        candidateIds: List<String>,
        dao: TaskDao,
        initialFetchLimit: Int = 50,
        maxFetchLimit: Int = 500,
    ): List<Task> {
        val tasks = candidateIds.map { task(it) }
        val index = listIndex(tasks)
        val validator: DependencyValidator = DependencyValidatorImpl(dao, currentUser())

        return searchRelatedTaskCandidates(
            rawQuery = "Alpha",
            taskId = TaskId("t-cur"),
            existingRelationIds = emptySet(),
            searchIndex = index,
            isValidCandidate = { id -> validator.assertNoCycles(TaskId("t-cur"), setOf(id)).isSuccess },
            initialFetchLimit = initialFetchLimit,
            maxFetchLimit = maxFetchLimit,
        )
    }

    /** [head] → n1 → n2 → … : a chain of [length] nodes under [head]. */
    private suspend fun seedChain(
        dao: InMemoryTaskDao,
        head: String,
        length: Int,
    ) {
        var from = head
        (1..length).forEach { step ->
            val to = "$from-n$step"
            dao.upsertDependencyForUser(from, to, "BLOCKS", uid.value)
            from = to
        }
    }

    /** [head] → n1 → … → the current task: a chain that ends in a cycle. */
    private suspend fun seedChainToCurrent(
        dao: InMemoryTaskDao,
        head: String,
        length: Int,
    ) {
        var from = head
        (1..length).forEach { step ->
            val to = "$from-n$step"
            dao.upsertDependencyForUser(from, to, "BLOCKS", uid.value)
            from = to
        }
        dao.upsertDependencyForUser(from, "t-cur", "BLOCKS", uid.value)
    }

    private fun task(id: String) =
        Task(
            id = TaskId(id),
            title = "Alpha $id",
            createdAt = now,
            updatedAt = now,
            userId = uid,
        )

    /** Counts ordering-graph reads so a walk can be priced. */
    private class CountingTaskDao(private val delegate: TaskDao) : TaskDao by delegate {
        var orderingEdgeReads: Int = 0
            private set

        override fun getOrderingEdgeIdsForUser(
            taskId: String,
            userId: String,
        ): Flow<List<String>> {
            orderingEdgeReads++
            return delegate.getOrderingEdgeIdsForUser(taskId, userId)
        }
    }

    private companion object {
        /** Nodes per candidate chain — long enough for the walk to be visible. */
        const val CHAIN = 10
    }
}
