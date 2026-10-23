
package com.singularity.todo.feature.ai.tools

import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The write tools must not report a change they did not make.
 *
 * ## What this is a control for
 *
 * The defect this class was written for was a copy-paste convention across a
 * package: each of the seven tools called its repository's `create`/`update` and
 * discarded the `Result`, then returned an output DTO asserting success. On a
 * failed write the model was handed `{"noteId": "<an id naming nothing>", ...}` —
 * identical to the success payload — and its next action was built on a
 * fabricated fact.
 *
 * Six of the seven no longer write at all: they store a *proposal*, and the
 * write happens later, when the user confirms it. The defect has moved with
 * them. A proposal that was not stored must not be reported as stored, so the
 * failing call is now the save of the proposal — and the assertion is the same
 * one, on the same shape: the tool says it did not do it.
 *
 * ## Two shapes of "it did not happen"
 *
 * A tool that only proposes answers with a payload: `proposalCreated` is false
 * and the error says why. That is now the shape of every tool in this package —
 * `create_tag` was the last one that wrote, and it proposes like the rest, so the
 * "it still writes, so it throws" shape is gone from the family.
 *
 * ## Why the assertion is on the answer and not only on the exception
 *
 * Asserting the throw alone would pass a tool that reported a success payload
 * on the way out. Every test here therefore checks the answer too: the flag says
 * the change did not happen, and the user's data is untouched.
 */
@Tag("fast")
class WriteToolsReportTheWriteTheyDidNotPerformTest {

    private val clock: Clock = Clock.System
    private val userId = UserId("test-user")
    private val boom = IllegalStateException("the write did not happen")
    private val profileAwareUser = FakeProfileAwareCurrentUser(
        authRepository = FakeAuthRepository(initialSession = Session.Anonymous(userId)),
    )

    // ─── Create ────────────────────────────────────────────────────────────────

    @Test
    fun `CreateNoteTool does not report a proposal it failed to store`() = runTest {
        val repo = FakeProposalRepository(clock)
        repo.saveOverride = Result.failure(boom)
        val notes = FakeNotesRepository(currentUser = profileAwareUser)

        val output = ToolOutputJson.decodeFromString(
            CreateNoteOutput.serializer(),
            CreateNoteTool(repo, profileAwareUser, clock).execute(CreateNoteInput(title = "Ghost")),
        )

        assertFalse(output.proposalCreated, "the tool says it did not propose")
        assertNotNull(output.error, "the tool says why")
        assertTrue(notes.notes.isEmpty(), "nothing was created")
    }

    @Test
    fun `CreateProjectTool does not report a proposal it failed to store`() = runTest {
        val repo = FakeProposalRepository(clock)
        repo.saveOverride = Result.failure(boom)
        val projects = FakeProjectsRepository(currentUser = profileAwareUser)

        val output = ToolOutputJson.decodeFromString(
            CreateProjectOutput.serializer(),
            CreateProjectTool(repo, profileAwareUser, clock).execute(CreateProjectInput(name = "Ghost")),
        )

        assertFalse(output.proposalCreated, "the tool says it did not propose")
        assertNotNull(output.error, "the tool says why")
        assertTrue(projects.observeAll().first().isEmpty(), "nothing was created")
    }

    @Test
    fun `CreateTagTool does not report a proposal it failed to store`() = runTest {
        val repo = FakeProposalRepository(clock)
        repo.saveOverride = Result.failure(boom)
        val tags = FakeTagsRepository(currentUser = profileAwareUser)

        val output = ToolOutputJson.decodeFromString(
            CreateTagOutput.serializer(),
            CreateTagTool(repo, profileAwareUser, clock).execute(CreateTagInput(name = "Ghost")),
        )

        assertFalse(output.proposalCreated, "the tool says it did not propose")
        assertNotNull(output.error, "the tool says why")
        assertTrue(tags.observeAll().first().isEmpty(), "nothing was created")
    }

    @Test
    fun `CreateTaskTool does not report a proposal it failed to store`() = runTest {
        val repo = FakeProposalRepository(clock)
        repo.saveOverride = Result.failure(boom)
        val tasks = FakeTaskRepository(explicitCurrentUser = profileAwareUser)

        val output = ToolOutputJson.decodeFromString(
            CreateTaskOutput.serializer(),
            CreateTaskTool(repo, profileAwareUser, clock).execute(CreateTaskInput(title = "Ghost")),
        )

        assertFalse(output.proposalCreated, "the tool says it did not propose")
        assertNotNull(output.error, "the tool says why")
        assertTrue(tasks.tasks.value.isEmpty(), "nothing was created")
    }

    // ─── Update ────────────────────────────────────────────────────────────────

    @Test
    fun `UpdateNoteTool does not report a proposal it failed to store`() = runTest {
        val repo = FakeNotesRepository(currentUser = profileAwareUser)
        val existing = note()
        repo.seed(existing)
        val proposals = FakeProposalRepository(clock)
        proposals.saveOverride = Result.failure(boom)

        val output = ToolOutputJson.decodeFromString(
            UpdateNoteOutput.serializer(),
            UpdateNoteTool(proposals, repo, profileAwareUser, clock)
                .execute(UpdateNoteInput(noteId = existing.id.value, title = "Renamed")),
        )

        assertFalse(output.proposalCreated, "the tool says it did not propose")
        assertNotNull(output.error, "the tool says why")
        assertEquals("Existing", repo.notes[existing.id.value]?.title)
    }

    @Test
    fun `UpdateProjectTool does not report a proposal it failed to store`() = runTest {
        val repo = FakeProjectsRepository(currentUser = profileAwareUser)
        val existing = project()
        repo.seed(existing)
        val proposals = FakeProposalRepository(clock)
        proposals.saveOverride = Result.failure(boom)

        val output = ToolOutputJson.decodeFromString(
            UpdateProjectOutput.serializer(),
            UpdateProjectTool(proposals, repo, profileAwareUser, clock)
                .execute(UpdateProjectInput(projectId = existing.id.value, name = "Renamed")),
        )

        assertFalse(output.proposalCreated, "the tool says it did not propose")
        assertNotNull(output.error, "the tool says why")
        assertEquals("Existing", repo.get(existing.id)?.name)
    }

    @Test
    fun `UpdateTaskTool does not report a proposal it failed to store`() = runTest {
        val repo = FakeTaskRepository(explicitCurrentUser = profileAwareUser)
        val existing = task()
        repo.seed(existing)
        val proposals = FakeProposalRepository(clock)
        proposals.saveOverride = Result.failure(boom)

        val output = ToolOutputJson.decodeFromString(
            UpdateTaskOutput.serializer(),
            UpdateTaskTool(proposals, repo, profileAwareUser, clock)
                .execute(UpdateTaskInput(taskId = existing.id.value, title = "Renamed")),
        )

        assertFalse(output.proposalCreated, "the tool says it did not propose")
        assertNotNull(output.error, "the tool says why")
        assertEquals("Existing", repo.tasks.value[existing.id.value]?.title)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun note() = Note(
        id = NoteId.generate(),
        userId = userId,
        title = "Existing",
        createdAt = clock.now(),
        updatedAt = clock.now(),
    )

    private fun project() = Project(
        id = ProjectId.generate(),
        name = "Existing",
        color = 0,
        createdAt = clock.now(),
        updatedAt = clock.now(),
        userId = userId,
    )

    private fun task() = Task(
        id = TaskId.generate(),
        title = "Existing",
        priority = TaskPriority.Medium,
        kind = TaskKind.Task,
        tags = emptyList(),
        createdAt = clock.now(),
        updatedAt = clock.now(),
        userId = userId,
    )
}
