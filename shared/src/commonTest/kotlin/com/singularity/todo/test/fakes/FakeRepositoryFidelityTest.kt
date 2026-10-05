@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test.fakes

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteKind
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A fake is a test double: it must reproduce the *production* contract, not a
 * nicer one. Several fakes did not, and the divergence was not cosmetic — it
 * made the tests blind to the exact invariants the write-layer work enforces.
 *
 * `FakeProjectsRepository.observeProject` / `observeByParent` / `changes` /
 * `findByIdempotencyKey` filtered by nothing, so they returned another user's
 * project and any cross-user isolation assertion passed vacuously. This file
 * pins that behaviour so a future edit cannot quietly reopen the hole.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.jupiter.api.Tag("fast")
class FakeRepositoryFidelityTest {

    private val alice = UserId("alice")
    private val bob = UserId("bob")

    /**
     * The instant every fixture below is stamped with.
     *
     * These builders used to read the wall clock, which needed a file-level
     * `@file:Suppress("NoDirectClockSystem")` — the same blanket switch this
     * repository has been removing. A fixture's `createdAt` is setup, not a
     * measurement: nothing here asserts that an entity was created *now*, so
     * reading the clock bought nothing and cost determinism. It matches
     * [FakeClock]'s default epoch, so a fixture timestamp and a timestamp the
     * fakes stamp are directly comparable.
     */
    private val fixtureNow = Instant.fromEpochMilliseconds(0)

    private fun project(id: String, uid: UserId, name: String = "P", parentId: ProjectId? = null) = Project(
        id = ProjectId(id),
        name = name,
        color = 0,
        parentId = parentId,
        createdAt = fixtureNow,
        updatedAt = fixtureNow,
        userId = uid,
    )

    private fun task(id: String, uid: UserId) = Task(
        id = TaskId(id),
        title = "T",
        userId = uid,
        createdAt = fixtureNow,
        updatedAt = fixtureNow,
    )

    private fun tag(id: String, uid: UserId) = Tag(
        id = TagId(id),
        name = "Tag",
        color = 0,
        createdAt = fixtureNow,
        updatedAt = fixtureNow,
        userId = uid,
    )

    private fun note(id: String, uid: UserId, title: String, body: String? = null) = Note(
        id = NoteId(id),
        userId = uid,
        title = title,
        bodyMarkdown = body,
        kind = NoteKind.Plain,
        createdAt = fixtureNow,
        updatedAt = fixtureNow,
    )

    // ─── Projects ─────────────────────────────────────────────────────────────

    @Test
    fun `observeProject does not leak another user's project`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val repo = FakeProjectsRepository(
            currentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope),
        )
        repo.seed(project("p1", bob, "Bob's project"))
        advanceUntilIdle()

        assertNull(
            repo.observeProject(ProjectId("p1")).first(),
            "alice must not observe bob's project",
        )
    }

    @Test
    fun `changes does not leak another user's project`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val repo = FakeProjectsRepository(
            currentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope),
        )
        repo.seed(project("p1", bob))
        advanceUntilIdle()

        assertNull(repo.changes(ProjectId("p1")).first(), "changes must be user-scoped")
    }

    @Test
    fun `observeByParent does not leak another user's children`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val repo = FakeProjectsRepository(
            currentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope),
        )
        val root = project("root", alice)
        repo.seed(root, project("child", bob, parentId = root.id))
        advanceUntilIdle()

        assertTrue(
            repo.observeByParent(root.id).first().isEmpty(),
            "children of another user must not appear",
        )
    }

    @Test
    fun `findByIdempotencyKey is scoped to the current user`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val repo = FakeProjectsRepository(
            currentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope),
        )
        repo.seed(project("p1", bob).copy(idempotencyKey = "shared-key"))
        advanceUntilIdle()

        assertNull(
            repo.findByIdempotencyKey("shared-key"),
            "a key collision must not resolve across users",
        )
    }

    @Test
    fun `setParent does not mutate another user's project`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val repo = FakeProjectsRepository(
            currentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope),
        )
        repo.seed(project("p1", bob), project("root", alice))
        advanceUntilIdle()

        repo.setParent(ProjectId("p1"), ProjectId("root"), fixtureNow.toEpochMilliseconds())

        assertNull(
            repo.observe(ProjectId("p1")).first()?.parentId,
            "alice must not reparent bob's project",
        )
    }

    // ─── Tags ─────────────────────────────────────────────────────────────────

    @Test
    fun `tag observe and get are user-scoped`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val repo = FakeTagsRepository(
            currentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope),
        )
        repo.seed(tag("t1", bob))
        advanceUntilIdle()

        assertNull(repo.observe(TagId("t1")).first(), "observe must be user-scoped")
        assertNull(repo.get(TagId("t1")), "get must be user-scoped")
    }

    @Test
    fun `tag delete stamps the clock the fake was given`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val stampedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val repo = FakeTagsRepository(
            currentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope),
            clock = FakeClock(stampedAt),
        )
        repo.seed(tag("t1", alice))
        advanceUntilIdle()

        assertTrue(repo.delete(TagId("t1")).isSuccess)
        val deletedAt = repo.observe(TagId("t1")).first()?.deletedAt
        assertNotNull(deletedAt, "the tag should be soft-deleted, not removed")
        // This used to assert `deletedAt > 1_000ms` — "a real timestamp, not the
        // default" — which is what a wall-clock read produced and what no test
        // should depend on. The sentinel could only distinguish "stamped" from
        // "left at the default"; it could not catch the fake stamping from the
        // wrong source. Naming the instant catches both: an unstamped entity is
        // still epoch zero, and one stamped from anywhere else is a different
        // value.
        assertEquals(
            stampedAt,
            deletedAt,
            "the fake must stamp exactly the clock it was given, not a different one",
        )
    }

    @Test
    fun `tag delete on a foreign tag fails instead of silently succeeding`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val repo = FakeTagsRepository(
            currentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope),
        )
        repo.seed(tag("t1", bob))
        advanceUntilIdle()

        assertTrue(repo.delete(TagId("t1")).isFailure, "deleting another user's tag must fail")
    }

    // ─── Tasks ────────────────────────────────────────────────────────────────

    @Test
    fun `task delete soft-deletes, so the row survives`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val repo = FakeTaskRepository(explicitCurrentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope))
        repo.seed(task("t1", alice))
        advanceUntilIdle()

        assertTrue(repo.delete(TaskId("t1")).isSuccess)
        val stored = repo.observeAll().first().firstOrNull { it.id.value == "t1" }
        assertNotNull(stored, "production soft-deletes; a hard delete loses the row")
    }

    @Test
    fun `task update rejects an unknown id, like production read-before-write`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val repo = FakeTaskRepository(explicitCurrentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope))
        advanceUntilIdle()

        assertTrue(
            repo.update(task("missing", alice)).isFailure,
            "production throws for a non-existent task; the fake accepted it",
        )
    }

    @Test
    fun `task update rejects another user's task`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val repo = FakeTaskRepository(explicitCurrentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope))
        repo.seed(task("t1", bob))
        advanceUntilIdle()

        assertTrue(repo.update(task("t1", bob).copy(title = "hijacked")).isFailure)
    }

    @Test
    fun `task get and exists do not leak another user's task`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val repo = FakeTaskRepository(explicitCurrentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope))
        repo.seed(task("t1", bob))
        advanceUntilIdle()

        assertNull(repo.get(TaskId("t1")), "get must not return another user's task")
        assertTrue(!repo.exists(TaskId("t1")), "exists must not report a foreign task")
    }

    @Test
    fun `task getTagIds is scoped to the owning user`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val repo = FakeTaskRepository(explicitCurrentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope))
        repo.seed(task("t1", bob).copy(tags = listOf(TagId("secret"))))
        advanceUntilIdle()

        assertTrue(
            repo.getTagIds(TaskId("t1")).first().isEmpty(),
            "tag ids of another user's task must not be observable",
        )
    }

    // ─── Notes ────────────────────────────────────────────────────────────────

    @Test
    fun `note search matches title only, as production does`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val repo = FakeNotesRepository(
            currentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope),
        )
        repo.seed(note("n1", alice, title = "Groceries"))
        repo.seed(note("n2", alice, title = "Unrelated", body = "buy milk and groceries"))
        advanceUntilIdle()

        val hits = repo.search("groceries").first()
        assertEquals(
            listOf("n1"),
            hits.map { it.id.value },
            "production searches title only; body matches are a product question, not fake behaviour",
        )
    }
}
