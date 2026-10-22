@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.projects

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.projects.data.ProjectsRepositoryImpl
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.data.toEntity
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Closing a project, against the real database.
 *
 * Two properties matter and neither is visible in the fake:
 *
 * 1. **The close is a narrow write.** The project may have been renamed on another
 *    device since this one read it, and a row rewrite would put the old name back.
 * 2. **A closed project leaves the list but stays readable.** It is not a deleted
 *    project — it is a project whose work is finished.
 */
@Tag("slow")
class ProjectCloseRepositoryTest {

    private val alice = UserId("alice")
    private val now = kotlin.time.Instant.fromEpochMilliseconds(1_000)

    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-project-close-").toFile() }
    private val tempDir: File by tempDirLazy

    private val dbLazy = lazy {
        AppDatabaseFactory.build(
            driver = createSqlDriver(),
            dbPath = File(tempDir, "projects.db").absolutePath,
        )
    }
    private val db: AppDatabase by dbLazy

    @AfterTest
    fun tearDown() {
        if (dbLazy.isInitialized()) db.close()
        if (tempDirLazy.isInitialized()) tempDir.deleteRecursively()
    }

    private fun project(id: String = "p1", name: String = "Project") = Project(
        id = ProjectId(id),
        name = name,
        color = 0xFF5500,
        createdAt = now,
        updatedAt = now,
        userId = alice,
    )

    private fun repository() = run {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val currentUser = FakeProfileAwareCurrentUser(auth)
        ProjectsRepositoryImpl(
            projectDao = db.projectDao(),
            inheritedTagGroupDao = db.projectInheritedTagGroupDao(),
            clock = kotlin.time.Clock.System,
            currentUser = currentUser,
            syncRepository = FakeSyncRepository(),
            unitOfWork = FakeUnitOfWork(),
        )
    }

    @Test
    fun `closing a project stamps the closed timestamp`() = runTest {
        val repo = repository()
        repo.create(project())
        advanceUntilIdle()

        repo.close(ProjectId("p1"), 5_000L).getOrThrow()
        advanceUntilIdle()

        val closed = repo.observeProject(ProjectId("p1")).first()
        assertNotNull(closed, "a closed project stays readable")
        assertEquals(5_000L, closed.closedAt?.toEpochMilliseconds(), "the close must be stamped")
    }

    @Test
    fun `a closed project leaves the project list`() = runTest {
        val repo = repository()
        repo.create(project())
        repo.create(project("p2", "Other"))
        advanceUntilIdle()

        repo.close(ProjectId("p1"), 5_000L).getOrThrow()
        advanceUntilIdle()

        val listed = repo.observeAll().first()
        assertTrue(listed.none { it.id == ProjectId("p1") }, "a closed project must leave the list")
        assertTrue(listed.any { it.id == ProjectId("p2") }, "the other project must stay")
    }

    @Test
    fun `the close leaves the rest of the row alone`() = runTest {
        val repo = repository()
        repo.create(project())
        advanceUntilIdle()

        // The project is renamed elsewhere between this device's read and its
        // close — the classic lost-update the narrow UPDATE exists to avoid.
        db.projectDao().upsert(
            project().copy(
                name = "Renamed elsewhere",
                updatedAt = kotlin.time.Instant.fromEpochMilliseconds(4_000),
            ).toEntity(),
        )

        repo.close(ProjectId("p1"), 5_000L).getOrThrow()
        advanceUntilIdle()

        val closed = repo.observeProject(ProjectId("p1")).first()
        assertEquals("Renamed elsewhere", closed?.name, "the close must not put the old name back")
        assertEquals(5_000L, closed?.closedAt?.toEpochMilliseconds())
    }

    @Test
    fun `closing a project that does not exist fails`() = runTest {
        val repo = repository()

        val result = repo.close(ProjectId("missing"), 5_000L)

        assertTrue(result.isFailure, "there is no row to close")
    }
}
