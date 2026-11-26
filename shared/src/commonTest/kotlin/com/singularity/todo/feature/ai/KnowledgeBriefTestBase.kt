@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.ai.tools.CreateNoteOutput
import com.singularity.todo.feature.ai.tools.GetKnowledgeBriefOutput
import com.singularity.todo.feature.ai.tools.GetKnowledgeBriefTool
import com.singularity.todo.feature.ai.tools.UpdateNoteOutput
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
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
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
abstract class KnowledgeBriefTestBase {
    protected val clock = FakeClock(Instant.parse("2026-03-10T09:00:00Z"))
    protected val user: UserId = TestUsers.DEFAULT

    protected val proposals = FakeProposalRepository(clock)
    protected val notes = FakeNotesRepository()
    protected val tasks = FakeTaskRepository()
    protected val tags = FakeTagsRepository()
    protected val checklist = FakeChecklistRepository()
    protected val timeTracking = FakeTimeTrackingRepository(clock)
    protected val projects = FakeProjectsRepository(FakeProfileAwareCurrentUser(user))
    protected val deleteProject = DeleteProjectUseCase(projects, tasks)

    protected val currentUser = FakeProfileAwareCurrentUser(user)

    protected val apply =
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

    protected val readTool = GetKnowledgeBriefTool(notes, projects, tasks)

    // ── Fixtures ────────────────────────────────────────────────────────────────

    protected fun project(name: String) =
        Project(
            id = ProjectId.generate(),
            userId = user,
            name = name,
            color = 0,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )

    protected fun task(title: String) =
        Task(
            id = TaskId.generate(),
            userId = user,
            title = title,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )

    /**
     * A note, with the provenance section a brief carries by default.
     *
     * The section is what makes a note a brief — see `KnowledgeArticle` — so the
     * fixtures that stand for briefs carry it and a test that wants an ordinary logbook
     * note says so with [withSources] = false.
     */
    protected fun note(
        title: String,
        html: String,
        projectId: ProjectId? = null,
        taskId: TaskId? = null,
        withSources: Boolean = true,
    ) = Note(
        id = NoteId.generate(),
        userId = user,
        title = title,
        bodyHtml = if (withSources) "$html<h2>Sources</h2><p>https://example.com</p>" else html,
        projectId = projectId,
        taskId = taskId,
        createdAt = clock.now(),
        updatedAt = clock.now(),
    )

    /** The note the proposal brought into being, after the user confirmed it. */
    protected suspend fun appliedNoteId(proposalId: String): String {
        val itemId = singleItemId(proposalId)
        val target = proposals.getItem(itemId)?.targetId.orEmpty()
        val result = apply.confirm(itemId, user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")
        return target
    }

    protected suspend fun singleItemId(proposalId: String) =
        requireNotNull(
            proposals
                .watchProposal(ProposalId(proposalId))
                .first()
                ?.items
                ?.singleOrNull(),
        ).id

    protected fun decodeCreate(raw: String): CreateNoteOutput =
        Json.decodeFromString(CreateNoteOutput.serializer(), raw)

    protected fun decodeUpdate(raw: String): UpdateNoteOutput =
        Json.decodeFromString(UpdateNoteOutput.serializer(), raw)

    protected fun decodeBrief(raw: String): GetKnowledgeBriefOutput =
        Json.decodeFromString(GetKnowledgeBriefOutput.serializer(), raw)
}
