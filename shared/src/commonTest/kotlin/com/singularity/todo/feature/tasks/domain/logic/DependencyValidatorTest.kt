@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.InMemoryTaskDao
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [DependencyValidatorImpl].
 *
 * Verifies BFS cycle detection across all five [DependencyVerb] values,
 * user-isolation of the analysis, and [assertNoCycles] self-loop guard.
 */
@Tag("fast")
@Epic("Tasks")
@Feature("Domain logic")
class DependencyValidatorTest {
    private val userA = UserId("user-a")
    private val userB = UserId("user-b")

    private fun currentUser(uid: UserId = userA): ProfileAwareCurrentUser =
        FakeProfileAwareCurrentUser(authRepository = FakeAuthRepository(Session.Anonymous(uid)))

    private suspend fun dao(vararg entries: Triple<String, String, String>): InMemoryTaskDao {
        val dao = InMemoryTaskDao()
        entries.forEach { (owner, dep, verb) ->
            dao.upsertDependencyForUser(owner, dep, verb, userA.value)
        }
        return dao
    }

    private suspend fun daoWithUserB(vararg entries: Triple<String, String, String>): InMemoryTaskDao {
        val dao = InMemoryTaskDao()
        entries.forEach { (owner, dep, verb) ->
            dao.upsertDependencyForUser(owner, dep, verb, userB.value)
        }
        return dao
    }

    private fun sut(
        dao: TaskDao,
        currentUser: ProfileAwareCurrentUser = currentUser(),
    ) = DependencyValidatorImpl(dao, currentUser)

    // ── assertNoCycles ──────────────────────────────────────────────────────────

    @Test
    fun `assertNoCycles passes when task does not depend on itself`() =
        runTest {
            val result = sut(InMemoryTaskDao()).assertNoCycles(TaskId("t1"), setOf(TaskId("t2")))
            assertTrue(result.isSuccess)
        }

    @Test
    fun `assertNoCycles fails when task depends on itself`() =
        runTest {
            val result = sut(InMemoryTaskDao()).assertNoCycles(TaskId("t1"), setOf(TaskId("t1")))
            assertTrue(result.isFailure)
            assertEquals(
                "Task cannot depend on itself: t1",
                result.exceptionOrNull()?.message,
            )
        }

    @Test
    fun `assertNoCycles fails when a new dependency can reach the task`() =
        runTest {
            // t-a → t-c would close t-c → t-b → t-a → t-c.
            val deps =
                dao(
                    Triple("t-c", "t-b", "BLOCKS"),
                    Triple("t-b", "t-a", "BLOCKS"),
                )
            val result = sut(deps).assertNoCycles(TaskId("t-a"), setOf(TaskId("t-c")))
            assertTrue(result.isFailure)
        }

    @Test
    fun `assertNoCycles passes when a new dependency cannot reach the task`() =
        runTest {
            val deps = dao(Triple("t-a", "t-b", "BLOCKS"))
            val result = sut(deps).assertNoCycles(TaskId("t-a"), setOf(TaskId("t-c")))
            assertTrue(result.isSuccess)
        }

    @Test
    fun `assertNoCycles passes for an empty set of new dependencies`() =
        runTest {
            val deps = dao(Triple("t-a", "t-b", "BLOCKS"))
            val result = sut(deps).assertNoCycles(TaskId("t-a"), emptySet())
            assertTrue(result.isSuccess)
        }

    @Test
    fun `assertNoCycles fails when the new dependency closes a two-node cycle`() =
        runTest {
            // t-b → t-a already exists, so t-a → t-b would make the pair circular.
            val deps = dao(Triple("t-b", "t-a", "BLOCKS"))
            val result = sut(deps).assertNoCycles(TaskId("t-a"), setOf(TaskId("t-b")))
            assertTrue(result.isFailure)
        }

    @Test
    fun `assertNoCycles fails when the cycle closes through a FOLLOWS_UP edge`() =
        runTest {
            // The ordering graph is every verb: a follow-up still orders the work.
            val deps = dao(Triple("t-b", "t-a", "FOLLOWS_UP"))
            val result = sut(deps).assertNoCycles(TaskId("t-a"), setOf(TaskId("t-b")))
            assertTrue(result.isFailure)
        }

    @Test
    fun `assertNoCycles fails when the cycle closes through mixed edge types`() =
        runTest {
            // t-b --FOLLOWS_UP--> t-c --BLOCKS--> t-a. Adding t-a --BLOCKS--> t-b
            // closes a cycle whose path uses two different verbs, so a validator
            // that only walked BLOCKS edges would let it through.
            val deps =
                dao(
                    Triple("t-b", "t-c", "FOLLOWS_UP"),
                    Triple("t-c", "t-a", "BLOCKS"),
                )
            val result = sut(deps).assertNoCycles(TaskId("t-a"), setOf(TaskId("t-b")))
            assertTrue(result.isFailure)
        }

    @Test
    fun `assertNoCycles stops walking once the task is reached`() =
        runTest {
            val delegate = InMemoryTaskDao()
            delegate.upsertDependencyForUser("t-b", "t-a", "BLOCKS", userA.value)
            (1..50).forEach { delegate.upsertDependencyForUser("t-b", "t-x$it", "BLOCKS", userA.value) }
            val counting = CountingTaskDao(delegate)

            val result = sut(counting).assertNoCycles(TaskId("t-a"), setOf(TaskId("t-b")))

            assertTrue(result.isFailure)
            assertEquals(
                1,
                counting.orderingEdgeReads,
                "the walk must stop at the target instead of reading every reachable node's edges",
            )
        }

    @Test
    fun `assertNoCycles propagates a read failure instead of reporting success`() =
        runTest {
            val deps = dao(Triple("t-b", "t-a", "BLOCKS"))
            val failing = FailingEdgeReadTaskDao(deps)

            assertFailsWith<IllegalStateException> {
                sut(failing).assertNoCycles(TaskId("t-a"), setOf(TaskId("t-b")))
            }
        }

    // ── analyzeDependencies — no cycle ────────────────────────────────────────

    @Test
    fun `analyzeDependencies returns no cycle for isolated task`() =
        runTest {
            val analysis = sut(InMemoryTaskDao()).analyzeDependencies(TaskId("t1"))
            assertFalse(analysis.containsCycle)
            assertTrue(analysis.cycleEdges.isEmpty())
        }

    @Test
    fun `analyzeDependencies returns no cycle for acyclic chain`() =
        runTest {
            val deps =
                dao(
                    Triple("t-a", "t-b", "BLOCKS"),
                    Triple("t-b", "t-c", "BLOCKS"),
                )
            val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
            assertFalse(analysis.containsCycle)
            assertTrue(analysis.cycleEdges.isEmpty())
        }

    @Test
    fun `analyzeDependencies handles diamond without cycle`() =
        runTest {
            val deps =
                dao(
                    Triple("t-a", "t-b", "BLOCKS"),
                    Triple("t-a", "t-c", "BLOCKS"),
                    Triple("t-b", "t-d", "BLOCKS"),
                    Triple("t-c", "t-d", "BLOCKS"),
                )
            val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
            assertFalse(analysis.containsCycle)
        }

    // ── analyzeDependencies — cycle detection ──────────────────────────────────

    @Test
    fun `analyzeDependencies detects self-loop`() =
        runTest {
            val deps = dao(Triple("t-a", "t-a", "BLOCKS"))
            val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
            assertTrue(analysis.containsCycle)
            assertTrue(analysis.cycleEdges.isNotEmpty())
        }

    @Test
    fun `analyzeDependencies detects three-node cycle`() =
        runTest {
            val deps =
                dao(
                    Triple("t-a", "t-b", "BLOCKS"),
                    Triple("t-b", "t-c", "BLOCKS"),
                    Triple("t-c", "t-a", "BLOCKS"),
                )
            val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
            assertTrue(analysis.containsCycle)
            assertTrue(analysis.cycleEdges.isNotEmpty())
        }

    @Test
    fun `analyzeDependencies returns correct verb for blocking edge`() =
        runTest {
            val deps =
                dao(
                    Triple("t-a", "t-b", "FOLLOWS_UP"),
                    Triple("t-b", "t-a", "BLOCKS"),
                )
            val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
            assertTrue(analysis.containsCycle)
            val bToA =
                analysis.cycleEdges.find {
                    it.ownerTaskId == TaskId("t-b") && it.dependencyTaskId == TaskId("t-a")
                }
            assertTrue(bToA != null, "Expected t-b -> t-a edge in cycle blockers")
            assertEquals(DependencyVerb.BLOCKS, bToA.verb)
        }

    // ── User isolation ────────────────────────────────────────────────────────

    @Test
    fun `analyzeDependencies is scoped to current user`() =
        runTest {
            // user-b has a cycle: A->B->A; user-a has no cycle
            val daoWithCycle =
                daoWithUserB(
                    Triple("t-a", "t-b", "BLOCKS"),
                    Triple("t-b", "t-a", "BLOCKS"),
                )

            // User A — no cycle
            val analysisA = sut(daoWithCycle, currentUser(userA)).analyzeDependencies(TaskId("t-a"))
            assertFalse(analysisA.containsCycle)

            // User B — cycle exists
            val analysisB = sut(daoWithCycle, currentUser(userB)).analyzeDependencies(TaskId("t-a"))
            assertTrue(analysisB.containsCycle)
        }

    // ── All five DependencyVerb values ────────────────────────────────────────

    @Test
    fun `analyzeDependencies works with FOLLOWS_UP verb in cycle`() =
        runTest {
            val deps =
                dao(
                    Triple("t-a", "t-b", "FOLLOWS_UP"),
                    Triple("t-b", "t-a", "FOLLOWS_UP"),
                )
            val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
            assertTrue(analysis.containsCycle)
            assertEquals(DependencyVerb.FOLLOWS_UP, analysis.cycleEdges.first().verb)
        }

    @Test
    fun `analyzeDependencies works with DUPLICATES verb in cycle`() =
        runTest {
            val deps =
                dao(
                    Triple("t-a", "t-b", "DUPLICATES"),
                    Triple("t-b", "t-a", "DUPLICATES"),
                )
            val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
            assertTrue(analysis.containsCycle)
            assertEquals(DependencyVerb.DUPLICATES, analysis.cycleEdges.first().verb)
        }

    @Test
    fun `analyzeDependencies works with FIXES verb in cycle`() =
        runTest {
            val deps =
                dao(
                    Triple("t-a", "t-b", "FIXES"),
                    Triple("t-b", "t-a", "FIXES"),
                )
            val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
            assertTrue(analysis.containsCycle)
            assertEquals(DependencyVerb.FIXES, analysis.cycleEdges.first().verb)
        }

    @Test
    fun `analyzeDependencies works with SUPERSEDES verb in cycle`() =
        runTest {
            val deps =
                dao(
                    Triple("t-a", "t-b", "SUPERSEDES"),
                    Triple("t-b", "t-a", "SUPERSEDES"),
                )
            val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
            assertTrue(analysis.containsCycle)
            assertEquals(DependencyVerb.SUPERSEDES, analysis.cycleEdges.first().verb)
        }

    /** Counts ordering-graph reads so a test can see where the walk stopped. */
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

    /** Fails every ordering-graph read, standing in for a database that cannot answer. */
    private class FailingEdgeReadTaskDao(private val delegate: TaskDao) : TaskDao by delegate {
        override fun getOrderingEdgeIdsForUser(
            taskId: String,
            userId: String,
        ): Flow<List<String>> = throw IllegalStateException("database unavailable")
    }
}
