package com.singularity.todo.core.seed

import com.singularity.todo.core.links.InternalLinkSchemes
import com.singularity.todo.core.links.InternalLinks
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The seed replaces; it does not append.
 *
 * A flow asserts on a row it seeded, and it addresses that row by name — the test
 * tag of a task row is the slug of its title. A seed that appended would leave the
 * flow with two candidates and no way to say which one it meant, so the "exactly
 * one row" assumption every flow makes would be false.
 *
 * These tests hold that rule: the same title seeded twice leaves one row, and the
 * second seed rewrites that row rather than adding a second one.
 */
@Tag("fast")
class SeedWriterTest {

    private val currentUser = FakeProfileAwareCurrentUser()
    private val tasks: TaskRepository = FakeTaskRepository(explicitCurrentUser = currentUser)
    private val notes: NotesRepository = FakeNotesRepository(currentUser = currentUser)
    private val projects: ProjectsRepository = FakeProjectsRepository(currentUser = currentUser)
    private val writer = SeedWriter(tasks, notes, projects, currentUser)

    private val stamp = Instant.fromEpochMilliseconds(1_767_225_600_000)

    @Test
    fun `seeding the same title twice leaves exactly one task`() = runTest {
        val first = writer.writeTask(TaskSeed(title = "PinnedTask2026", stamp = stamp))
        val second = writer.writeTask(TaskSeed(title = "PinnedTask2026", stamp = stamp))

        assertEquals(SeedOutcome.Created, first.outcome)
        assertEquals(SeedOutcome.Updated, second.outcome)
        assertEquals(1, liveTasks("PinnedTask2026").size)
    }

    @Test
    fun `the second seed rewrites the row instead of adding one`() = runTest {
        val first = writer.writeTask(
            TaskSeed(
                title = "PinnedTask2026",
                dueDate = LocalDate(2026, 10, 21),
                stamp = stamp,
            ),
        )
        val second = writer.writeTask(
            TaskSeed(
                title = "PinnedTask2026",
                dueDate = LocalDate(2026, 10, 22),
                stamp = stamp,
            ),
        )

        // Same row, rewritten: the id is the fixture's identity, and it survives
        // the re-seed — that is what makes the name in a failure message enough.
        assertEquals(first.id, second.id)
        assertEquals(LocalDate(2026, 10, 22), liveTasks("PinnedTask2026").single().dueDate)
    }

    @Test
    fun `a task an earlier run completed and trashed is seeded back into the live set`() = runTest {
        val first = writer.writeTask(TaskSeed(title = "PinnedTask2026", stamp = stamp))
        tasks.complete(first.id).getOrThrow()
        tasks.softDelete(first.id).getOrThrow()

        val second = writer.writeTask(TaskSeed(title = "PinnedTask2026", stamp = stamp))

        assertEquals(first.id, second.id)
        val live = liveTasks("PinnedTask2026")
        assertEquals(1, live.size)
        assertNull(live.single().completedAt)
    }

    @Test
    fun `a seeded dependency is set on the task`() = runTest {
        writer.writeTask(TaskSeed(title = "Blocker", stamp = stamp))
        val blocked = writer.writeTask(
            TaskSeed(
                title = "Blocked Task",
                stamp = stamp,
                dependsOnTitle = "Blocker",
            ),
        )

        assertNull(blocked.missingDependency)
        val blocker = liveTasks("Blocker").single()
        assertEquals(setOf(blocker.id), tasks.observe(blocked.id).first()?.blockedBy)
    }

    @Test
    fun `a dependency that does not exist is reported rather than silently dropped`() = runTest {
        val blocked = writer.writeTask(
            TaskSeed(
                title = "Blocked Task",
                stamp = stamp,
                dependsOnTitle = "Blocker",
            ),
        )

        assertEquals("Blocker", blocked.missingDependency)
        assertEquals(emptySet(), tasks.observe(blocked.id).first()?.blockedBy)
    }

    @Test
    fun `seeding the same note title twice leaves exactly one note`() = runTest {
        val first = writer.writeNote(NoteSeed(title = "Meeting notes"))
        val second = writer.writeNote(NoteSeed(title = "Meeting notes"))

        assertEquals(SeedOutcome.Created, first.outcome)
        assertEquals(SeedOutcome.Updated, second.outcome)
        assertEquals(1, notes.observeAll().first().count { it.title == "Meeting notes" })
    }

    @Test
    fun `seeding the same project name twice leaves exactly one project`() = runTest {
        val first = writer.writeProject(ProjectSeed(name = "Work", color = 0xFF2196F3.toInt(), stamp = stamp))
        val second = writer.writeProject(ProjectSeed(name = "Work", color = 0xFF2196F3.toInt(), stamp = stamp))

        assertEquals(SeedOutcome.Created, first.outcome)
        assertEquals(SeedOutcome.Updated, second.outcome)
        assertEquals(1, projects.observeAll().first().count { it.name == "Work" })
    }

    @Test
    fun `a project seed that names an id owns it`() = runTest {
        val result = writer.writeProject(
            ProjectSeed(
                name = "Work",
                color = 0xFF2196F3.toInt(),
                stamp = stamp,
                id = ProjectId.fromString("fixed01"),
            ),
        )

        assertEquals(ProjectId.fromString("fixed01"), result.id)
        assertEquals(ProjectId.fromString("fixed01"), liveProjects("Work").single().id)
    }

    @Test
    fun `a project seed that names the id of a project renames it`() = runTest {
        writer.writeProject(
            ProjectSeed(
                name = "Work",
                color = 0xFF2196F3.toInt(),
                stamp = stamp,
                id = ProjectId.fromString("fixed01"),
            ),
        )

        val renamed = writer.writeProject(
            ProjectSeed(
                name = "Play",
                color = 0xFF2196F3.toInt(),
                stamp = stamp,
                id = ProjectId.fromString("fixed01"),
            ),
        )

        assertEquals(SeedOutcome.Updated, renamed.outcome)
        assertEquals(ProjectId.fromString("fixed01"), renamed.id)
        // The row is the same row: a rename changes the copy, not the row.
        assertEquals(listOf(ProjectId.fromString("fixed01")), liveProjects("Play").map { it.id })
        assertEquals(emptyList(), liveProjects("Work"))
    }

    @Test
    fun `a task seed that names an id owns it`() = runTest {
        val result = writer.writeTask(
            TaskSeed(title = "Link target", stamp = stamp, id = TaskId.fromString("fixed01")),
        )

        assertEquals(TaskId.fromString("fixed01"), result.id)
        assertEquals(TaskId.fromString("fixed01"), liveTasks("Link target").single().id)
    }

    @Test
    fun `a task seed that names an id replaces the row of the same title under another id`() = runTest {
        val first = writer.writeTask(TaskSeed(title = "Link target", stamp = stamp))
        val second = writer.writeTask(
            TaskSeed(title = "Link target", stamp = stamp, id = TaskId.fromString("fixed01")),
        )

        assertEquals(TaskId.fromString("fixed01"), second.id)
        // The row the seed replaced leaves the live set: two live rows with one name
        // is a state no flow can assert on.
        assertEquals(listOf(TaskId.fromString("fixed01")), liveTasks("Link target").map { it.id })
    }

    @Test
    fun `a note seed that links to a task writes the link the grammar defines`() = runTest {
        val task = writer.writeTask(TaskSeed(title = "Link target", stamp = stamp))

        val result = writer.writeNoteLinkingToTask(NoteSeed(title = "Link source"), "Link target")

        assertNull(result.missingLinkTarget)
        val note = notes.observeAll().first().single { it.id == result.id }
        val url = InternalLinks.urlFor(InternalLinkSchemes.TASK, task.id.value)
        assertEquals(listOf(url), note.outgoingLinks)
        assertTrue(
            note.bodyHtml.orEmpty().contains("href=\"$url\""),
            "the body must carry the link the grammar defines; it carried ${note.bodyHtml}",
        )
    }

    @Test
    fun `a note seed whose link target does not exist says so`() = runTest {
        val result = writer.writeNoteLinkingToTask(NoteSeed(title = "Link source"), "Nowhere")

        assertEquals("Nowhere", result.missingLinkTarget)
        val note = notes.observeAll().first().single { it.id == result.id }
        assertEquals(emptyList(), note.outgoingLinks)
    }

    /** The rows the task list shows: the ones that are neither trashed nor completed. */
    private suspend fun liveTasks(title: String) =
        tasks.observeAll().first().filter { it.title == title && !it.isTrashed }

    /** The rows the projects list shows: the ones that are not deleted. */
    private suspend fun liveProjects(name: String) =
        projects.observeAll().first().filter { it.name == name && !it.isDeleted }
}
