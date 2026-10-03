@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.InMemoryTaskDao
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [DependencyValidatorImpl].
 *
 * Verifies BFS cycle detection across all five [DependencyVerb] values,
 * user-isolation of the analysis, and [assertNoCycles] self-loop guard.
 */
class DependencyValidatorTest {

    private val userA = UserId("user-a")
    private val userB = UserId("user-b")

    private fun currentUser(uid: UserId = userA): ProfileAwareCurrentUser =
        FakeProfileAwareCurrentUser(authRepository = FakeAuthRepository(Session.Anonymous(uid)))

    private fun dao(vararg entries: Triple<String, String, String>): InMemoryTaskDao {
        val dao = InMemoryTaskDao()
        runBlocking {
            entries.forEach { (owner, dep, verb) ->
                dao.upsertDependencyForUser(owner, dep, verb, userA.value)
            }
        }
        return dao
    }

    private fun daoWithUserB(vararg entries: Triple<String, String, String>): InMemoryTaskDao {
        val dao = InMemoryTaskDao()
        runBlocking {
            entries.forEach { (owner, dep, verb) ->
                dao.upsertDependencyForUser(owner, dep, verb, userB.value)
            }
        }
        return dao
    }

    private fun sut(dao: InMemoryTaskDao, currentUser: ProfileAwareCurrentUser = currentUser()) =
        DependencyValidatorImpl(dao, currentUser)

    // ── assertNoCycles ──────────────────────────────────────────────────────────

    @Test
    fun `assertNoCycles passes when task does not depend on itself`() = runTest {
        val result = sut(InMemoryTaskDao()).assertNoCycles(TaskId("t1"), setOf(TaskId("t2")))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `assertNoCycles fails when task depends on itself`() = runTest {
        val result = sut(InMemoryTaskDao()).assertNoCycles(TaskId("t1"), setOf(TaskId("t1")))
        assertTrue(result.isFailure)
        assertEquals(
            "Task cannot depend on itself: t1",
            result.exceptionOrNull()?.message,
        )
    }

    // ── analyzeDependencies — no cycle ────────────────────────────────────────

    @Test
    fun `analyzeDependencies returns no cycle for isolated task`() = runTest {
        val analysis = sut(InMemoryTaskDao()).analyzeDependencies(TaskId("t1"))
        assertFalse(analysis.containsCycle)
        assertTrue(analysis.blockers.isEmpty())
    }

    @Test
    fun `analyzeDependencies returns no cycle for acyclic chain`() = runTest {
        val deps = dao(
            Triple("t-a", "t-b", "BLOCKS"),
            Triple("t-b", "t-c", "BLOCKS"),
        )
        val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
        assertFalse(analysis.containsCycle)
        assertTrue(analysis.blockers.isEmpty())
    }

    @Test
    fun `analyzeDependencies handles diamond without cycle`() = runTest {
        val deps = dao(
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
    fun `analyzeDependencies detects self-loop`() = runTest {
        val deps = dao(Triple("t-a", "t-a", "BLOCKS"))
        val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
        assertTrue(analysis.containsCycle)
        assertTrue(analysis.blockers.isNotEmpty())
    }

    @Test
    fun `analyzeDependencies detects three-node cycle`() = runTest {
        val deps = dao(
            Triple("t-a", "t-b", "BLOCKS"),
            Triple("t-b", "t-c", "BLOCKS"),
            Triple("t-c", "t-a", "BLOCKS"),
        )
        val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
        assertTrue(analysis.containsCycle)
        assertTrue(analysis.blockers.isNotEmpty())
    }

    @Test
    fun `analyzeDependencies returns correct verb for blocking edge`() = runTest {
        val deps = dao(
            Triple("t-a", "t-b", "FOLLOWS_UP"),
            Triple("t-b", "t-a", "BLOCKS"),
        )
        val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
        assertTrue(analysis.containsCycle)
        val bToA = analysis.blockers.find {
            it.ownerTaskId == TaskId("t-b") && it.dependencyTaskId == TaskId("t-a")
        }
        assertTrue(bToA != null, "Expected t-b -> t-a edge in cycle blockers")
        assertEquals(DependencyVerb.BLOCKS, bToA.verb)
    }

    // ── User isolation ────────────────────────────────────────────────────────

    @Test
    fun `analyzeDependencies is scoped to current user`() = runTest {
        // user-b has a cycle: A->B->A; user-a has no cycle
        val daoWithCycle = daoWithUserB(
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
    fun `analyzeDependencies works with FOLLOWS_UP verb in cycle`() = runTest {
        val deps = dao(
            Triple("t-a", "t-b", "FOLLOWS_UP"),
            Triple("t-b", "t-a", "FOLLOWS_UP"),
        )
        val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
        assertTrue(analysis.containsCycle)
        assertEquals(DependencyVerb.FOLLOWS_UP, analysis.blockers.first().verb)
    }

    @Test
    fun `analyzeDependencies works with DUPLICATES verb in cycle`() = runTest {
        val deps = dao(
            Triple("t-a", "t-b", "DUPLICATES"),
            Triple("t-b", "t-a", "DUPLICATES"),
        )
        val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
        assertTrue(analysis.containsCycle)
        assertEquals(DependencyVerb.DUPLICATES, analysis.blockers.first().verb)
    }

    @Test
    fun `analyzeDependencies works with FIXES verb in cycle`() = runTest {
        val deps = dao(
            Triple("t-a", "t-b", "FIXES"),
            Triple("t-b", "t-a", "FIXES"),
        )
        val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
        assertTrue(analysis.containsCycle)
        assertEquals(DependencyVerb.FIXES, analysis.blockers.first().verb)
    }

    @Test
    fun `analyzeDependencies works with SUPERSEDES verb in cycle`() = runTest {
        val deps = dao(
            Triple("t-a", "t-b", "SUPERSEDES"),
            Triple("t-b", "t-a", "SUPERSEDES"),
        )
        val analysis = sut(deps).analyzeDependencies(TaskId("t-a"))
        assertTrue(analysis.containsCycle)
        assertEquals(DependencyVerb.SUPERSEDES, analysis.blockers.first().verb)
    }
}
