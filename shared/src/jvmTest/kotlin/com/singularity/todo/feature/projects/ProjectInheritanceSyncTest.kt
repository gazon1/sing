@file:Suppress("NoDirectClockSystem")

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.projects

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.projects.data.ProjectsRepositoryImpl
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.data.TagGroupRepositoryImpl
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Inheritance is part of the project document, so every project patch has to carry it.
 *
 * ## Why this test exists rather than a test on the writer
 *
 * The defect is not "the writer forgot to enqueue". It is that `inheritedTagGroupIds`
 * lives in a join table while `ProjectEntity` has no column for it, so `toProject()`
 * returns `emptySet()` for a project that inherits groups — and the field is
 * `@Serializable`, so that empty set is what gets pushed.
 *
 * That makes the failure mode quiet and wrong in the same direction: a patch is sent,
 * sync looks healthy, and the server applies "this project inherits nothing" and wipes
 * the inheritance on every other device. Enqueueing correctly is not enough; enqueueing
 * a *true* statement is the requirement, and only the document can be asserted on.
 *
 * Three paths are covered, because the join table can change without the project row
 * changing, and both directions have to agree:
 *
 * 1. the inheritance write — `setInheritedForProject`;
 * 2. an unrelated project update — the one that would silently erase inheritance;
 * 3. the pull — `upsert`, where the column does not exist and the join rows have to be
 *    written from the decoded document.
 *
 * ## The controls
 *
 * Every assertion is on the enqueued document, not on "an enqueue happened". A test that
 * only counted enqueues would pass on the broken code, because the broken code enqueues
 * — it just enqueues the wrong thing.
 */
@Tag("fast")
class ProjectInheritanceSyncTest {

    private val userId = UserId("u1")
    private val epoch = Instant.fromEpochMilliseconds(1_000_000_000L)

    private fun projectRow(id: String, updatedAt: Long = 1_000_000_000L) = ProjectEntity(
        id = id,
        userId = userId.value,
        name = id,
        color = 0,
        icon = null,
        description = null,
        createdAt = 1_000_000_000L,
        updatedAt = updatedAt,
        dueDate = null,
        team = null,
        isDeleted = false,
        deletedAt = null,
        parentId = null,
        sortOrder = 0,
        idempotencyKey = null,
        externalId = null,
    )

    private fun projects(db: FakeAppDatabase, sync: FakeSyncRepository) = ProjectsRepositoryImpl(
        projectDao = db.projectDao(),
        inheritedTagGroupDao = db.projectInheritedTagGroupDao(),
        clock = Clock.System,
        currentUser = FakeProfileAwareCurrentUser(
            authRepository = FakeAuthRepository(Session.Anonymous(userId)),
        ),
        syncRepository = sync,
        unitOfWork = FakeUnitOfWork(),
    )

    private fun tagGroups(db: FakeAppDatabase, sync: FakeSyncRepository) = TagGroupRepositoryImpl(
        tagGroupDao = db.tagGroupDao(),
        inheritedTagGroupDao = db.projectInheritedTagGroupDao(),
        projectDao = db.projectDao(),
        tagDao = db.tagDao(),
        clock = Clock.System,
        currentUser = FakeProfileAwareCurrentUser(
            authRepository = FakeAuthRepository(Session.Anonymous(userId)),
        ),
        syncRepository = sync,
        unitOfWork = FakeUnitOfWork(),
    )

    private fun FakeSyncRepository.lastProject(): Project? = enqueuedEntities.last() as? Project

    // ── 1. the inheritance write pushes what it just wrote ──────────────────────

    @Test
    fun `setting inheritance pushes a project carrying the new set`() = runTest {
        val db = FakeAppDatabase()
        db.projectDao().upsert(projectRow("p1"))
        val sync = FakeSyncRepository()

        tagGroups(db, sync).setInheritedForProject(
            ProjectId.fromString("p1"),
            setOf(TagGroupId.fromString("g1"), TagGroupId.fromString("g2")),
        ).getOrThrow()

        assertEquals(
            setOf(TagGroupId.fromString("g1"), TagGroupId.fromString("g2")),
            sync.lastProject()?.inheritedTagGroupIds,
            "the pushed document must carry the inheritance that was just written — an " +
                "empty set here would make the server erase it",
        )
    }

    @Test
    fun `setting inheritance pushes the project, not a tag group`() = runTest {
        val db = FakeAppDatabase()
        db.projectDao().upsert(projectRow("p1"))
        val sync = FakeSyncRepository()

        tagGroups(db, sync).setInheritedForProject(
            ProjectId.fromString("p1"),
            setOf(TagGroupId.fromString("g1")),
        ).getOrThrow()

        assertEquals(
            ProjectId.fromString("p1").value,
            sync.lastProject()?.syncId,
            "inheritance is a field of the project document; the join table has no DocType",
        )
    }

    @Test
    fun `setting inheritance stamps the project so the change can be ordered`() = runTest {
        val db = FakeAppDatabase()
        db.projectDao().upsert(projectRow("p1", updatedAt = 1_000L))
        val sync = FakeSyncRepository()

        tagGroups(db, sync).setInheritedForProject(
            ProjectId.fromString("p1"),
            setOf(TagGroupId.fromString("g1")),
        ).getOrThrow()

        val after = db.projectDao().getByIdForUser("p1", userId.value)?.updatedAt
        assertTrue(
            after != null && after > 1_000L,
            "updated_at must move: two inheritance states sharing one updated_at cannot be " +
                "ordered by the server's last-write-wins (was $after)",
        )
    }

    @Test
    fun `clearing inheritance pushes the empty set rather than omitting it`() = runTest {
        val db = FakeAppDatabase()
        db.projectDao().upsert(projectRow("p1"))
        db.projectInheritedTagGroupDao().insertForUser("p1", "g1", userId.value)
        val sync = FakeSyncRepository()

        tagGroups(db, sync).setInheritedForProject(
            ProjectId.fromString("p1"),
            emptySet(),
        ).getOrThrow()

        assertEquals(
            emptySet(),
            sync.lastProject()?.inheritedTagGroupIds,
            "clearing has to reach the server too, or the groups come back on the next pull",
        )
    }

    // ── 2. an unrelated project update must not erase it ────────────────────────

    @Test
    fun `a narrow project update still pushes the inheritance`() = runTest {
        val db = FakeAppDatabase()
        db.projectDao().upsert(projectRow("p1"))
        db.projectInheritedTagGroupDao().insertForUser("p1", "g1", userId.value)
        db.projectInheritedTagGroupDao().insertForUser("p1", "g2", userId.value)
        val sync = FakeSyncRepository()

        // A narrow update touches one column of `projects` and nothing about
        // inheritance. The patch still has to carry the join state, because the server
        // replaces the whole document: leaving it out here is the same as asserting the
        // project inherits nothing.
        projects(db, sync).setSortOrder(ProjectId.fromString("p1"), 7, 1_234L)

        assertEquals(
            setOf(TagGroupId.fromString("g1"), TagGroupId.fromString("g2")),
            sync.lastProject()?.inheritedTagGroupIds,
            "a narrow project update must not drop inheritance on the server",
        )
    }

    @Test
    fun `deleting a project still pushes the inheritance it had`() = runTest {
        val db = FakeAppDatabase()
        db.projectDao().upsert(projectRow("p1"))
        db.projectInheritedTagGroupDao().insertForUser("p1", "g1", userId.value)
        val sync = FakeSyncRepository()

        projects(db, sync).delete(ProjectId.fromString("p1")).getOrThrow()

        assertEquals(
            setOf(TagGroupId.fromString("g1")),
            sync.lastProject()?.inheritedTagGroupIds,
            "the delete patch is a state patch, and it replaces the document server-side",
        )
    }

    // ── 3. the pull has to land in the join table ───────────────────────────────

    @Test
    fun `applying a project writes its inheritance rows`() = runTest {
        val db = FakeAppDatabase()
        val incoming = Project(
            id = ProjectId.fromString("p1"),
            userId = userId,
            name = "from server",
            color = 0,
            createdAt = epoch,
            updatedAt = epoch,
            inheritedTagGroupIds = setOf(TagGroupId.fromString("g1"), TagGroupId.fromString("g9")),
        )

        projects(db, FakeSyncRepository()).upsert(incoming)

        assertEquals(
            listOf("g1", "g9"),
            db.projectInheritedTagGroupDao().getByProject("p1", userId.value).sorted(),
            "inheritedTagGroupIds is in the document but has no column on ProjectEntity, so " +
                "an apply that writes only the row drops it",
        )
    }

    @Test
    fun `applying a project replaces its inheritance rather than adding to it`() = runTest {
        val db = FakeAppDatabase()
        db.projectInheritedTagGroupDao().insertForUser("p1", "stale", userId.value)
        val incoming = Project(
            id = ProjectId.fromString("p1"),
            userId = userId,
            name = "from server",
            color = 0,
            createdAt = epoch,
            updatedAt = epoch,
            inheritedTagGroupIds = setOf(TagGroupId.fromString("g1")),
        )

        projects(db, FakeSyncRepository()).upsert(incoming)

        assertEquals(
            listOf("g1"),
            db.projectInheritedTagGroupDao().getByProject("p1", userId.value),
            "a group the server dropped must not survive locally",
        )
    }
}
