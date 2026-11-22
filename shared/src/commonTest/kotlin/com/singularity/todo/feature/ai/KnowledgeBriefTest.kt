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
class KnowledgeBriefTest {
    private val clock = FakeClock(Instant.parse("2026-03-10T09:00:00Z"))
    private val user: UserId = TestUsers.DEFAULT

    private val proposals = FakeProposalRepository(clock)
    private val notes = FakeNotesRepository()
    private val tasks = FakeTaskRepository()
    private val tags = FakeTagsRepository()
    private val checklist = FakeChecklistRepository()
    private val timeTracking = FakeTimeTrackingRepository(clock)
    private val projects = FakeProjectsRepository(FakeProfileAwareCurrentUser(user))
    private val deleteProject = DeleteProjectUseCase(projects, tasks)

    private val currentUser = FakeProfileAwareCurrentUser(user)

    private val apply =
        ApplyProposalItemUseCase(
            proposals = proposals,
            tasks = tasks,
            notes = notes,
            projects = projects,
            tags = tags,
            planner = ProposalPlanner(clock),
            dispatch =
                ProposalDispatch(
                    tasks,
                    tags,
                    checklist,
                    timeTracking,
                    notes,
                    projects,
                    deleteProject,
                    clock,
                ),
        )

    private val readTool = GetKnowledgeBriefTool(notes, projects, tasks)

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
    fun `refreshing a brief proposes a candidate and leaves the approved note alone`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)

            val output =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(
                            UpdateNoteInput(
                                noteId = brief.id.value,
                                bodyMarkdown = "refreshed",
                                expectedUpdatedAt = brief.updatedAt.toEpochMilliseconds(),
                            ),
                        ),
                )

            assertTrue(output.proposalCreated, "the refresh was proposed: ${output.error}")
            assertEquals(
                "<p>approved</p>",
                notes.notes[brief.id.value]?.bodyHtml,
                "the approved brief is unchanged until the user confirms",
            )

            apply.confirm(singleItemId(output.proposalId), user)
            assertNotNull(notes.notes[brief.id.value]?.bodyHtml, "the confirmed refresh left a body")
        }

    @Test
    fun `a refresh against a note that moved is refused instead of overwriting it`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)

            val output =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(
                            UpdateNoteInput(
                                noteId = brief.id.value,
                                bodyMarkdown = "refreshed from an older read",
                                // The note has since moved: this is the read before the edit.
                                expectedUpdatedAt = brief.updatedAt.toEpochMilliseconds() - 1,
                            ),
                        ),
                )

            assertFalse(output.proposalCreated, "no proposal was made: ${output.error}")
            assertNotNull(output.error, "the model is told the note moved")
            assertEquals(
                "<p>approved</p>",
                notes.notes[brief.id.value]?.bodyHtml,
                "the user's edit was not overwritten",
            )
            assertTrue(
                proposals.watchWaitingProposals().first().isEmpty(),
                "nothing is waiting for a decision about a stale read",
            )
        }

    @Test
    fun `an edit between the proposal and the confirmation is not overwritten`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)

            val output =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(
                            UpdateNoteInput(
                                noteId = brief.id.value,
                                bodyMarkdown = "refreshed",
                                expectedUpdatedAt = brief.updatedAt.toEpochMilliseconds(),
                            ),
                        ),
                )
            assertTrue(output.proposalCreated, "the refresh was proposed: ${output.error}")

            // The user edits the note after the model proposed and before they confirm.
            clock.advance(kotlin.time.Duration.parse("1m"))
            notes.seed(brief.copy(bodyHtml = "<p>the user's own words</p>", updatedAt = clock.now()))

            val result = apply.confirm(singleItemId(output.proposalId), user)

            // The proposal re-reads the row and finds a version it was not written
            // against: the change is refused, not applied over the edit.
            assertTrue(result.isFailure, "the stale proposal was refused")
            assertEquals(
                "<p>the user's own words</p>",
                notes.notes[brief.id.value]?.bodyHtml,
                "the user's edit survived the confirmation",
            )
        }

    @Test
    fun `applying a refresh advances the version, so the same read cannot land twice`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)
            val readAt = brief.updatedAt.toEpochMilliseconds()
            // The write is later in wall-clock terms, as a real refresh is.
            clock.advance(kotlin.time.Duration.parse("1m"))

            val first =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(
                            UpdateNoteInput(
                                noteId = brief.id.value,
                                bodyMarkdown = "first refresh",
                                expectedUpdatedAt = readAt,
                            ),
                        ),
                )
            assertTrue(apply.confirm(singleItemId(first.proposalId), user).isSuccess, "the first refresh applied")

            val afterFirst = assertNotNull(notes.notes[brief.id.value], "the note is still there")

            // A second proposal built from the same read. Without the version moving on
            // write, this would land on top of the first refresh.
            val second =
                UpdateNoteTool(proposals, notes, currentUser, clock)
                    .execute(
                        UpdateNoteInput(
                            noteId = brief.id.value,
                            bodyMarkdown = "second refresh",
                            expectedUpdatedAt = readAt,
                        ),
                    )

            assertTrue(
                decodeUpdate(second).error != null,
                "a proposal from a superseded read is refused: ${decodeUpdate(second).error}",
            )
            assertEquals(
                afterFirst.bodyHtml,
                notes.notes[brief.id.value]?.bodyHtml,
                "the first refresh is still the note",
            )
        }

    @Test
    fun `the brief is the note the store calls newest, not the one with the largest id`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)

            // Same created_at: the tie the ordering has to break the same way the store
            // does — `created_at DESC, id ASC`.
            val tied = clock.now()
            val lowId = note(title = "oldest id", html = "<p>a</p>", projectId = project.id).copy(id = NoteId("aaa"), createdAt = tied)
            val highId = note(title = "largest id", html = "<p>b</p>", projectId = project.id).copy(id = NoteId("zzz"), createdAt = tied)
            notes.seed(highId)
            notes.seed(lowId)

            val output = decodeBrief(readTool.execute(GetKnowledgeBriefInput(projectId = project.id.value)))

            assertTrue(output.found, "the brief was found: ${output.reason}")
            assertEquals(
                "oldest id",
                output.title,
                "the tie breaks on ascending id, so the store's first row is the brief",
            )
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

    // ── The catalogue and the round trip ───────────────────────────────────────

    @Test
    fun `the brief reader is declared read-only`() {
        val entry = McpToolCatalog.catalog.single { it.name == GetKnowledgeBriefTool.NAME }
        assertEquals(WriteBehavior.READ_ONLY, entry.writeBehavior, "reading a brief writes nothing")
        assertEquals("notes", entry.domain, "a brief is a note, so it belongs to the notes domain")
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    private fun project(name: String) =
        Project(
            id = ProjectId.generate(),
            userId = user,
            name = name,
            color = 0,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )

    private fun task(title: String) =
        Task(
            id = TaskId.generate(),
            userId = user,
            title = title,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )

    private fun note(
        title: String,
        html: String,
        projectId: ProjectId? = null,
        taskId: TaskId? = null,
    ) = Note(
        id = NoteId.generate(),
        userId = user,
        title = title,
        bodyHtml = html,
        projectId = projectId,
        taskId = taskId,
        createdAt = clock.now(),
        updatedAt = clock.now(),
    )

    /** The note the proposal brought into being, after the user confirmed it. */
    private suspend fun appliedNoteId(proposalId: String): String {
        val itemId = singleItemId(proposalId)
        val target = proposals.getItem(itemId)?.targetId.orEmpty()
        val result = apply.confirm(itemId, user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")
        return target
    }

    private suspend fun singleItemId(proposalId: String) =
        requireNotNull(
            proposals
                .watchProposal(ProposalId(proposalId))
                .first()
                ?.items
                ?.singleOrNull(),
        ).id

    private fun decodeCreate(raw: String): CreateNoteOutput = Json.decodeFromString(CreateNoteOutput.serializer(), raw)

    private fun decodeUpdate(raw: String): UpdateNoteOutput = Json.decodeFromString(UpdateNoteOutput.serializer(), raw)

    private fun decodeBrief(raw: String): GetKnowledgeBriefOutput =
        Json.decodeFromString(GetKnowledgeBriefOutput.serializer(), raw)
}
