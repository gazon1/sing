package com.singularity.todo.core.seed

import com.singularity.todo.core.links.InternalLinkSchemes
import com.singularity.todo.core.links.InternalLinks
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The keys a seed carries beyond a title.
 *
 * A task that names a priority, a project and a description; a note that
 * carries text as well as a link. These are the keys the demo dataset is
 * written with, and the rules they have to keep: a key that cannot be
 * honoured fails the seed instead of writing something else, a note's text
 * does not depend on the task it links to, and a re-seed leaves nothing of the
 * previous run behind.
 *
 * They live in their own class because the seed's other rules — the ones about
 * replacing rather than appending — are already a class of their own, and one
 * class of this size is a finding detekt has to be told about.
 */
@Tag("fast")
class SeedWriterContractTest {
    private val currentUser = FakeProfileAwareCurrentUser()
    private val tasks: TaskRepository = FakeTaskRepository(explicitCurrentUser = currentUser)
    private val notes: NotesRepository = FakeNotesRepository(currentUser = currentUser)
    private val projects: ProjectsRepository = FakeProjectsRepository(currentUser = currentUser)
    private val writer = SeedWriter(tasks, notes, projects, currentUser)

    private val stamp = Instant.fromEpochMilliseconds(1_767_225_600_000)

    @Test
    fun `a task seed that names a priority writes it`() =
        runTest {
            writer.writeTask(
                TaskSeed(title = "Urgent work", stamp = stamp, priority = TaskPriority.High),
            )

            assertEquals(TaskPriority.High, liveTasks("Urgent work").single().priority)
        }

    @Test
    fun `a task seed that names a project puts the task in it`() =
        runTest {
            val project =
                writer.writeProject(
                    ProjectSeed(name = "Work", color = 0xFF2196F3.toInt(), stamp = stamp),
                )

            writer.writeTask(
                TaskSeed(title = "Report", stamp = stamp, projectName = "Work"),
            )

            assertEquals(project.id, liveTasks("Report").single().projectId)
        }

    @Test
    fun `a task seed that names a project that is not there fails and writes nothing`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                writer.writeTask(TaskSeed(title = "Report", stamp = stamp, projectName = "Nowhere"))
            }

            assertEquals(emptyList(), liveTasks("Report"))
        }

    @Test
    fun `a note seed that names a body writes it`() =
        runTest {
            writer.writeNote(NoteSeed(title = "Outline", body = "Three points to cover."))

            val note = notes.observeAll().first().single { it.title == "Outline" }
            assertEquals("Three points to cover.", note.bodyMarkdown)
            assertTrue(note.bodyHtml?.contains("Three points to cover.") == true)
        }

    @Test
    fun `a note seed that has both a body and a link writes the text and the link`() =
        runTest {
            val task = writer.writeTask(TaskSeed(title = "Link target", stamp = stamp))

            val result =
                writer.writeNoteLinkingToTask(
                    NoteSeed(title = "Link source", body = "Three points to cover."),
                    "Link target",
                )

            val note = notes.observeAll().first().single { it.id == result.id }
            val url = InternalLinks.urlFor(InternalLinkSchemes.TASK, task.id.value)
            assertTrue(note.bodyMarkdown.orEmpty().startsWith("Three points to cover."))
            assertTrue(note.bodyHtml.orEmpty().contains("href=\"$url\""))
        }

    @Test
    fun `a note seed keeps its body when the link target does not exist`() =
        runTest {
            val result =
                writer.writeNoteLinkingToTask(
                    NoteSeed(title = "Link source", body = "Three points to cover."),
                    "Nowhere",
                )

            assertEquals("Nowhere", result.missingLinkTarget)
            val note = notes.observeAll().first().single { it.id == result.id }
            assertEquals(
                "Three points to cover.",
                note.bodyMarkdown,
                "a missing link target is not a reason to drop the note's text",
            )
            assertTrue(note.bodyHtml.orEmpty().contains("Three points to cover."))
        }

    @Test
    fun `a note seed writes a multiline body as text and not as one paragraph`() =
        runTest {
            val result =
                writer.writeNoteLinkingToTask(
                    NoteSeed(title = "Link source", body = "First line.\nSecond line."),
                    "Nowhere",
                )

            val note = notes.observeAll().first().single { it.id == result.id }
            assertTrue(
                note.bodyMarkdown.orEmpty().contains("\n"),
                "the line break of the body has to survive the seed",
            )
            assertTrue(note.bodyHtml.orEmpty().contains("First line."))
            assertTrue(note.bodyHtml.orEmpty().contains("Second line."))
        }

    @Test
    fun `a re-seed does not leave the previous run's link behind`() =
        runTest {
            writer.writeTask(TaskSeed(title = "Link target", stamp = stamp))
            val first =
                writer.writeNoteLinkingToTask(
                    NoteSeed(title = "Link source", body = "Three points to cover."),
                    "Link target",
                )
            val firstLink = notes.observeAll().first().single { it.id == first.id }
            assertEquals(1, firstLink.outgoingLinks.size)

            // The target is gone in this run — the note must not keep pointing at it.
            writer.writeTask(TaskSeed(title = "Link target", stamp = stamp))
            tasks.softDelete(liveTasks("Link target").single().id)
            val second =
                writer.writeNoteLinkingToTask(
                    NoteSeed(title = "Link source", body = "Three points to cover."),
                    "Link target",
                )

            val note = notes.observeAll().first().single { it.id == second.id }
            assertEquals(emptyList(), note.outgoingLinks)
            assertEquals("Three points to cover.", note.bodyMarkdown)
        }

    @Test
    fun `a bulk seed puts every task in the project it names`() =
        runTest {
            val project =
                writer.writeProject(
                    ProjectSeed(name = "Work", color = 0xFF2196F3.toInt(), stamp = stamp),
                )

            writer.createTasks(
                (1..3).map { TaskSeed(title = "Bulk$it", stamp = stamp, projectName = "Work") },
            )

            (1..3).forEach { index ->
                assertEquals(
                    project.id,
                    liveTasks("Bulk$index").single().projectId,
                    "Bulk$index must sit in the project the seed named",
                )
            }
        }

    @Test
    fun `a bulk seed that names a project that is not there fails and writes nothing`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                writer.createTasks(
                    (1..3).map { TaskSeed(title = "Bulk$it", stamp = stamp, projectName = "Nowhere") },
                )
            }

            assertEquals(emptyList(), liveTasks("Bulk1"))
            assertEquals(emptyList(), liveTasks("Bulk2"))
            assertEquals(emptyList(), liveTasks("Bulk3"))
        }

    @Test
    fun `a bulk seed mixes projects and rows without a project`() =
        runTest {
            val project =
                writer.writeProject(
                    ProjectSeed(name = "Work", color = 0xFF2196F3.toInt(), stamp = stamp),
                )

            writer.createTasks(
                listOf(
                    TaskSeed(title = "Bulk1", stamp = stamp, projectName = "Work"),
                    TaskSeed(title = "Bulk2", stamp = stamp),
                ),
            )

            assertEquals(project.id, liveTasks("Bulk1").single().projectId)
            assertNull(liveTasks("Bulk2").single().projectId)
        }

    /** The rows the task list shows: the ones that are neither trashed nor completed. */
    private suspend fun liveTasks(title: String) =
        tasks.observeAll().first().filter { it.title == title && !it.isTrashed }
}
