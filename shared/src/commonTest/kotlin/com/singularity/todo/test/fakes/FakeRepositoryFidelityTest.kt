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
import kotlin.time.Clock

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
class FakeRepositoryFidelityTest {

    private val alice = UserId("alice")
    private val bob = UserId("bob")

    private fun project(id: String, uid: UserId, name: String = "P", parentId: ProjectId? = null) = Project(
        id = ProjectId(id),
        name = name,
        color = 0,
        parentId = parentId,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
        userId = uid,
    )

    private fun task(id: String, uid: UserId) = Task(
        id = TaskId(id),
        title = "T",
        userId = uid,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun tag(id: String, uid: UserId) = Tag(
        id = TagId(id),
        name = "Tag",
        color = 0,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
        userId = uid,
    )

    private fun note(id: String, uid: UserId, title: String, body: String? = null) = Note(
        id = NoteId(id),
        userId = uid,
        title = title,
        bodyMarkdown = body,
        kind = NoteKind.Plain,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
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

        repo.setParent(ProjectId("p1"), ProjectId("root"), Clock.System.now().toEpochMilliseconds())

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
    fun `tag delete stamps the real clock, not epoch zero`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val repo = FakeTagsRepository(
            currentUser = FakeProfileAwareCurrentUser(auth, scope = backgroundScope),
        )
        repo.seed(tag("t1", alice))
        advanceUntilIdle()

        assertTrue(repo.delete(TagId("t1")).isSuccess)
        val deletedAt = repo.observe(TagId("t1")).first()?.deletedAt
        assertNotNull(deletedAt, "the tag should be soft-deleted, not removed")
        assertTrue(
            deletedAt > kotlin.time.Instant.fromEpochMilliseconds(1_000),
            "deletedAt must be a real timestamp, was $deletedAt",
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
