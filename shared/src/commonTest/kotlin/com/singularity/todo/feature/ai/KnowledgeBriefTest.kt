@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.ai.tools.CreateNoteInput
import com.singularity.todo.feature.ai.tools.CreateNoteOutput
import com.singularity.todo.feature.ai.tools.CreateNoteTool
import com.singularity.todo.feature.ai.tools.GetKnowledgeBriefInput
import com.singularity.todo.feature.ai.tools.GetKnowledgeBriefOutput
import com.singularity.todo.feature.ai.tools.GetKnowledgeBriefTool
import com.singularity.todo.feature.ai.tools.UpdateNoteInput
import com.singularity.todo.feature.ai.tools.UpdateNoteOutput
import com.singularity.todo.feature.ai.tools.UpdateNoteTool
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteKind
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ProposalDispatch
import com.singularity.todo.feature.proposals.domain.usecase.ProposalPlanner
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A knowledge brief is a note attached to a project or a task, written through the
 * proposal pipeline.
 *
 * ## What these tests are about
 *
 * The issue asks for a brief that *reuses* the note model — no parallel document
 * system — and that cannot destroy the approved brief. Those two claims are what is
 * pinned here, at the level a user experiences them:
 *
 * - the attachment is the `project_id` / `task_id` column, so the brief is a note and
 *   the note is findable by the same read the app already has;
 * - the proposal path holds the change until the user confirms it, so a refresh is a
 *   *candidate*, not a write;
 * - a change proposed against a note that has since moved is refused, so the model's
 *   summary of an older note cannot overwrite the user's edit;
 * - a note that is not there is never replaced by an empty brief — "no brief yet" and
 *   "no such project" are different answers.
 */
@Tag("fast")
class KnowledgeBriefTest : KnowledgeBriefTestBase() {
    // ── Attaching a note to a project or a task ────────────────────────────────

    @Test
    fun `create_note attaches the proposed note to a project`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)

            val output =
                decodeCreate(
                    CreateNoteTool(proposals, currentUser, clock)
                        .execute(CreateNoteInput(title = "Brief", projectId = project.id.value)),
                )
            assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")

            // Nothing is attached until the user says yes: the proposal holds the id the
            // note *will* have, and the note does not exist.
            assertTrue(notes.notes.isEmpty(), "nothing was created: ${notes.notes.keys}")

            val noteId = appliedNoteId(output.proposalId)
            assertEquals(
                project.id,
                notes.notes[noteId]?.projectId,
                "the confirmed note is attached to the project",
            )
        }

    @Test
    fun `create_note attaches the proposed note to a task`() =
        runTest {
            val task = task("Draft the brief")
            tasks.seed(task)

            val output =
                decodeCreate(
                    CreateNoteTool(proposals, currentUser, clock)
                        .execute(CreateNoteInput(title = "Notes", taskId = task.id.value)),
                )
            assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")

            val noteId = appliedNoteId(output.proposalId)
            assertEquals(task.id, notes.notes[noteId]?.taskId, "the confirmed note is attached to the task")
            assertNull(notes.notes[noteId]?.projectId, "a task note is not also a project note")
        }

    @Test
    fun `create_note refuses to attach a note to both owners`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val task = task("Draft")
            tasks.seed(task)

            val output =
                decodeCreate(
                    CreateNoteTool(proposals, currentUser, clock)
                        .execute(
                            CreateNoteInput(
                                title = "Brief",
                                projectId = project.id.value,
                                taskId = task.id.value,
                            ),
                        ),
                )

            assertFalse(output.proposalCreated, "no proposal was made: ${output.error}")
            assertNotNull(output.error, "the model is told why nothing was proposed")
            assertTrue(
                proposals.watchWaitingProposals().first().isEmpty(),
                "no proposal was stored for a call that names two owners",
            )
        }

    @Test
    fun `a brief attached to a project that does not exist cannot be applied`() =
        runTest {
            val output =
                decodeCreate(
                    CreateNoteTool(proposals, currentUser, clock)
                        .execute(CreateNoteInput(title = "Brief", projectId = "no-such-project")),
                )
            assertTrue(output.proposalCreated, "the tool proposes: ${output.error}")

            // The id is refused before the claim, so the user's "yes" is answered with
            // the reason instead of a foreign-key failure after the decision is spent.
            val result = apply.confirm(singleItemId(output.proposalId), user)
            assertTrue(result.isFailure, "the confirmation was refused")
            assertTrue(notes.notes.isEmpty(), "nothing was written: ${notes.notes.keys}")
        }

    // ── Reading the brief ──────────────────────────────────────────────────────

    @Test
    fun `the brief of a project is the note attached to it, as markdown`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            notes.seed(
                note(
                    title = "Knowledge brief",
                    html = "<p><b>Summary</b></p>",
                    projectId = project.id,
                ),
            )

            val output = decodeBrief(readTool.execute(GetKnowledgeBriefInput(projectId = project.id.value)))

            assertTrue(output.found, "the brief was found: ${output.reason}")
            assertEquals("project", output.owner, "the owner is stated")
            assertEquals("Knowledge brief", output.title, "the note's title is the brief's title")
            assertEquals(1, output.attachedNotesSeen, "one attached note was read")
            assertNotNull(output.bodyMarkdown, "the body came back as markdown")
            assertTrue(
                output.bodyMarkdown.contains("Summary"),
                "the markdown carries the note's text: ${output.bodyMarkdown}",
            )
            assertNotNull(output.updatedAt, "the version the caller passes back is stated")
        }

    @Test
    fun `a project with no attached note has no brief, and says so`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)

            val output = decodeBrief(readTool.execute(GetKnowledgeBriefInput(projectId = project.id.value)))

            assertFalse(output.found, "there is no brief")
            assertNull(output.noteId, "no note is named")
            assertEquals(0, output.attachedNotesSeen, "nothing is attached")
            assertNotNull(output.reason, "the answer says why: absence is not an error")
        }

    @Test
    fun `an unknown project is not an empty brief`() =
        runTest {
            val output = decodeBrief(readTool.execute(GetKnowledgeBriefInput(projectId = "no-such-project")))

            assertFalse(output.found, "there is no brief")
            assertNotNull(output.reason, "the answer names the missing project")
            assertTrue(
                output.reason!!.contains("no project"),
                "the reason is about the project, not about a brief: ${output.reason}",
            )
        }

    @Test
    fun `a task brief is read from the task's own notes`() =
        runTest {
            val task = task("Draft the brief")
            tasks.seed(task)
            notes.seed(note(title = "Task brief", html = "<p>facts</p>", taskId = task.id))

            val output = decodeBrief(readTool.execute(GetKnowledgeBriefInput(taskId = task.id.value)))

            assertTrue(output.found, "the brief was found: ${output.reason}")
            assertEquals("task", output.owner, "the owner is stated")
            assertEquals("Task brief", output.title, "the task's note is the brief")
        }

    @Test
    fun `a note attached to another project is not this project's brief`() =
        runTest {
            val kitchen = project("Kitchen")
            val garden = project("Garden")
            projects.seed(kitchen)
            projects.seed(garden)
            notes.seed(note(title = "Garden brief", html = "<p>soil</p>", projectId = garden.id))

            val output = decodeBrief(readTool.execute(GetKnowledgeBriefInput(projectId = kitchen.id.value)))

            assertFalse(output.found, "the kitchen has no brief of its own")
            assertEquals(0, output.attachedNotesSeen, "the garden's note is not the kitchen's")
        }

    // ── Refresh: the candidate, and the refusal ────────────────────────────────

    @Test
    fun `create_note refuses to attach a folder`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)

            val output =
                decodeCreate(
                    CreateNoteTool(proposals, currentUser, clock)
                        .execute(
                            CreateNoteInput(
                                title = "Sources",
                                isFolder = true,
                                projectId = project.id.value,
                            ),
                        ),
                )

            assertFalse(output.proposalCreated, "no proposal was made: ${output.error}")
            assertNotNull(output.error, "the model is told a folder is not a brief")
        }

    @Test
    fun `a failed extraction keeps the last approved brief`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)

            // The provider answered nothing, so the model has nothing to propose. The
            // brief it did not replace is still the one a reader gets.
            val output = decodeBrief(readTool.execute(GetKnowledgeBriefInput(projectId = project.id.value)))

            assertTrue(output.found, "the last approved brief is still readable: ${output.reason}")
            assertEquals(brief.id.value, output.noteId, "the approved note is the brief")
        }

    @Test
    fun `an ordinary task logbook note is not a knowledge brief`() =
        runTest {
            val task = task("Draft the brief")
            tasks.seed(task)
            // What the logbook writes: a plain note attached to the task, no provenance.
            notes.seed(
                note(
                    title = "Meeting notes",
                    html = "<p>talked about the draft</p>",
                    taskId = task.id,
                    withSources = false,
                ),
            )

            val output = decodeBrief(readTool.execute(GetKnowledgeBriefInput(taskId = task.id.value)))

            assertFalse(output.found, "an ordinary logbook note is not a brief: ${output.noteId}")
            assertNotNull(output.reason, "the answer says there is no brief yet")
        }

    // ── The catalogue and the round trip ───────────────────────────────────────

    @Test
    fun `the brief reader is declared read-only`() {
        val entry = McpToolCatalog.catalog.single { it.name == GetKnowledgeBriefTool.NAME }
        assertEquals(WriteBehavior.READ_ONLY, entry.writeBehavior, "reading a brief writes nothing")
        assertEquals("notes", entry.domain, "a brief is a note, so it belongs to the notes domain")
    }

}
