@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.InMemoryTaskDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two projections of the dependency graph, and the difference between them.
 *
 * The same table holds every relation between tasks, and the *kind* of the
 * relation decides what it means. Two of them are read, and they answer two
 * different questions:
 *
 * - the **ordering graph** — every edge. It is the shape of "what comes before
 *   what", and it is where a cycle is looked for. A relation that does not make
 *   a task wait still orders the work.
 * - the **blocking graph** — the `BLOCKS` edges. They are the ones that make a
 *   task wait, and the only ones that refuse a close.
 *
 * The two are not the same graph, and the difference is not an accident: two
 * tasks that "follow up on" each other are in a cycle of order, and neither of
 * them is waiting for the other. A reader that mixed the two would either
 * report a task as blocked when nothing holds it, or miss a cycle.
 *
 * These are the projections the DAO offers, so the test is on the DAO: the
 * names are the contract, and a name that does not say which graph it reads is
 * how the two got confused in the first place.
 */
@Tag("fast")
class DependencyGraphProjectionsTest {

    private val user = UserId("graph-user")

    private suspend fun dao(vararg entries: Triple<String, String, String>): InMemoryTaskDao {
        val dao = InMemoryTaskDao()
        entries.forEach { (owner, dep, verb) ->
            dao.upsertDependencyForUser(owner, dep, verb, user.value)
        }
        return dao
    }

    private fun currentUser() = FakeProfileAwareCurrentUser(
        authRepository = FakeAuthRepository(Session.Anonymous(user)),
    )

    @Test
    fun `the ordering graph holds every edge`() = runTest {
        val deps = dao(
            Triple("t-a", "t-b", DependencyVerb.BLOCKS.name),
            Triple("t-a", "t-c", DependencyVerb.FOLLOWS_UP.name),
        )

        val ordering = deps.getOrderingEdgeIdsForUser("t-a", user.value).first()

        assertEquals(setOf("t-b", "t-c"), ordering.toSet(), "the order of the work is every edge")
    }

    @Test
    fun `the blocking graph holds the blocking edges only`() = runTest {
        val deps = dao(
            Triple("t-a", "t-b", DependencyVerb.BLOCKS.name),
            Triple("t-a", "t-c", DependencyVerb.FOLLOWS_UP.name),
        )

        val blocking = deps.getBlockingEdgeIdsForUser("t-a", user.value, DependencyVerb.BLOCKS.name).first()

        assertEquals(listOf("t-b"), blocking, "only a block makes a task wait")
    }

    @Test
    fun `a relation that only follows up is in the cycle and not in the blockers`() = runTest {
        val deps = dao(
            Triple("t-a", "t-b", DependencyVerb.FOLLOWS_UP.name),
            Triple("t-b", "t-a", DependencyVerb.FOLLOWS_UP.name),
        )

        // The ordering graph: two tasks that follow up on each other are a cycle.
        val analysis = DependencyValidatorImpl(deps, currentUser())
            .analyzeDependencies(TaskId("t-a"))
        assertTrue(analysis.containsCycle, "the order of the work has a cycle here")
        assertEquals(DependencyVerb.FOLLOWS_UP, analysis.cycleEdges.first().verb)

        // The blocking graph: neither of them waits for the other.
        val blocking = deps.getBlockingEdgeIdsForUser("t-a", user.value, DependencyVerb.BLOCKS.name).first()
        assertTrue(blocking.isEmpty(), "a follow-up is not a block")
    }
}
