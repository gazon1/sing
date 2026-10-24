package com.singularity.todo.core.sync

import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.data.ProjectsRepositoryImpl
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import com.singularity.todo.test.fakes.TestUsers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Tag

/**
 * The project keeps what the document does not say — the row and the inheritance.
 *
 * ## The defect this is for
 *
 * The project was the one type that was left out of the rule, and it was left out
 * for a reason: its inheritance does not live on the row. `ProjectEntity` has no
 * column for `inheritedTagGroupIds` — the groups live in a cross-reference table —
 * and the project is written through a path of its own. So the document was
 * decoded on its own and written whole, and the write always replaced the table
 * with what the document carried. A document that carried no key carried an empty
 * set, and the project's inherited groups went away on this device — silently,
 * because the row was written and the pull reported success.
 *
 * The row had the same defect as the other types had: a document that carried no
 * key for a field wrote the model's default over it.
 *
 * ## Why the production repository is under test
 *
 * The inheritance lives in a table, and the table is the thing the defect was
 * about. A fake that keeps the inheritance in the project itself could not fail
 * where production fails, so the inheritance is asserted against the table of the
 * real repository, over the in-memory database.
 *
 * ## What these tests are and are not
 *
 * The tests that carry a missing key are the defect tests, and each of them
 * fails when the mechanism it covers is removed: the two row tests fail
 * without the merge, and the inheritance test fails without the claim. The
 * tests that carry a claim are guards — the
 * claim has to be honoured as well, or the fix would be "the inheritance never
 * changes any more".
 */
@Tag("fast")
class ProjectDocumentWriteTest {

    private val created = Instant.fromEpochMilliseconds(1_000)

    private fun project(
        id: String = "p1",
        name: String = "Work",
        description: String? = "the description",
        inherited: Set<TagGroupId> = emptySet(),
        owner: UserId = TestUsers.DEFAULT,
    ) = Project(
        id = ProjectId.fromString(id),
        userId = owner,
        name = name,
        color = 0xFF00FF00.toInt(),
        description = description,
        createdAt = created,
        updatedAt = created,
        inheritedTagGroupIds = inherited,
    )

    /** The row as the database holds it — without the inheritance, which is a table of its own. */
    private fun row(
        id: String = "p1",
        name: String = "Work",
        description: String? = "the description",
        owner: UserId = TestUsers.DEFAULT,
    ) = ProjectEntity(
        id = id,
        userId = owner.value,
        name = name,
        color = 0xFF00FF00.toInt(),
        icon = null,
        description = description,
        createdAt = 1_000_000_000L,
        updatedAt = 1_000_000_000L,
        dueDate = null,
        team = null,
        isDeleted = false,
        deletedAt = null,
        parentId = null,
        sortOrder = 0,
        idempotencyKey = null,
        externalId = null,
    )

    private fun repository(db: FakeAppDatabase): ProjectsRepository = ProjectsRepositoryImpl(
        projectDao = db.projectDao(),
        inheritedTagGroupDao = db.projectInheritedTagGroupDao(),
        clock = FakeClock(),
        currentUser = FakeProfileAwareCurrentUser(),
        syncRepository = FakeSyncRepository(),
        unitOfWork = FakeUnitOfWork(),
    )

    // ── the row ───────────────────────────────────────────────────────────

    @Test
    fun `a project document that carries no description leaves the description alone`() = runTest {
        val db = FakeAppDatabase()
        db.projectDao().upsert(row())
        val repo = repository(db)

        fakeSyncDocumentWriter(projects = repo).upsert(
            DocType.Project,
            JsonObject(project().toJson() - "description"),
        )

        assertEquals(
            "the description",
            db.projectDao().getByIdForUser("p1", TestUsers.DEFAULT.value)?.description,
            "the description of the project is still the description of the project",
        )
    }

    @Test
    fun `a project document that omits the name is a partial write`() = runTest {
        // The name is a field the model cannot do without, so a document without
        // it cannot be decoded on its own: the pull drops it, and nothing the
        // document said is applied. Read against the row that is already here,
        // the document is a partial write — the name of the row stays, and the
        // rest of the document is applied.
        val db = FakeAppDatabase()
        db.projectDao().upsert(row())
        val repo = repository(db)

        fakeSyncDocumentWriter(projects = repo).upsert(
            DocType.Project,
            JsonObject(project(description = "from the server").toJson() - "name"),
        )

        val stored = db.projectDao().getByIdForUser("p1", TestUsers.DEFAULT.value)
        assertEquals(
            "Work",
            stored?.name,
            "the name of the project is still the name of the project",
        )
        assertEquals(
            "from the server",
            stored?.description,
            "the rest of the document is applied — a dropped document applies nothing",
        )
    }

    // ── the inheritance ───────────────────────────────────────────────────

    @Test
    fun `a project document that says nothing about the inheritance leaves the groups alone`() = runTest {
        // The groups live in a table, and the table is what the write replaces.
        // A document that says nothing about the inheritance says nothing — it
        // does not say "the project inherits nothing", and the groups the
        // project has are not a claim either: the row cannot see them.
        val db = FakeAppDatabase()
        db.projectDao().upsert(row())
        db.projectInheritedTagGroupDao().insertForUser("p1", "g1", TestUsers.DEFAULT.value)
        db.projectInheritedTagGroupDao().insertForUser("p1", "g2", TestUsers.DEFAULT.value)
        val repo = repository(db)

        fakeSyncDocumentWriter(projects = repo).upsert(
            DocType.Project,
            JsonObject(project().toJson() - "inheritedTagGroupIds"),
        )

        assertEquals(
            listOf("g1", "g2"),
            db.projectInheritedTagGroupDao().getByProject("p1", TestUsers.DEFAULT.value).sorted(),
            "the groups the project inherits are still the groups it inherits",
        )
    }

    @Test
    fun `a project document that claims no inheritance takes the groups away`() = runTest {
        // The other side of the claim: an empty set is a claim, and it is taken
        // as one. A fix that made the table untouchable would pass the test
        // above and fail this one.
        val db = FakeAppDatabase()
        db.projectDao().upsert(row())
        db.projectInheritedTagGroupDao().insertForUser("p1", "g1", TestUsers.DEFAULT.value)
        val repo = repository(db)

        fakeSyncDocumentWriter(projects = repo).upsert(
            DocType.Project,
            JsonObject(project(inherited = emptySet()).toJson()),
        )

        assertEquals(
            emptyList(),
            db.projectInheritedTagGroupDao().getByProject("p1", TestUsers.DEFAULT.value),
            "a document that claims the project inherits nothing takes the groups away",
        )
    }

    @Test
    fun `a project document that claims other groups replaces the set`() = runTest {
        val db = FakeAppDatabase()
        db.projectDao().upsert(row())
        db.projectInheritedTagGroupDao().insertForUser("p1", "g1", TestUsers.DEFAULT.value)
        val repo = repository(db)

        fakeSyncDocumentWriter(projects = repo).upsert(
            DocType.Project,
            JsonObject(project(inherited = setOf(TagGroupId("g9"))).toJson()),
        )

        assertEquals(
            listOf("g9"),
            db.projectInheritedTagGroupDao().getByProject("p1", TestUsers.DEFAULT.value),
            "the groups of the document are the groups of the project",
        )
    }

    // ── the row of another profile ────────────────────────────────────────

    @Test
    fun `a project document of another profile is not filled from this profile's row`() = runTest {
        // A document of another profile can name a row this device already
        // holds: the row is keyed by the id alone. The foreign document says
        // nothing about the description, and the row says "the description" —
        // filling the foreign document from this profile's row would write this
        // profile's data into a document that says nothing about it.
        val repo = FakeProjectsRepository()
        repo.add(project())

        fakeSyncDocumentWriter(projects = repo).upsert(
            DocType.Project,
            JsonObject(project(owner = UserId("bob")).toJson() - "description"),
        )

        assertNull(
            repo.store["p1"]?.description,
            "the row of another profile is not filled from this profile's row",
        )
    }
}
