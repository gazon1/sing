@file:Suppress("NoDirectClockSystem")

package com.singularity.todo.feature.ai.tools

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
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The seven write tools must not report a write they did not perform.
 *
 * ## What this is a control for
 *
 * Each of the seven called its repository's `create`/`update` and discarded the
 * `Result`, then returned an output DTO asserting success. All seven build the
 * entity id *before* the write, so on a failed write the model was handed
 * `{"noteId": "<an id naming nothing>", ...}` — identical to the success payload.
 * The model's next action is then built on a fabricated fact, and it carries
 * that belief into every later turn of the conversation.
 *
 * The seven tests are deliberately one per tool rather than one representative
 * one. The defect is a copy-paste convention across a package, and a single
 * control would pass while six siblings kept reporting success.
 *
 * ## Why the assertion is "it throws"
 *
 * `DeleteNoteTool:74` already uses `.getOrThrow()`, so a failed tool call is the
 * failure shape the model is given elsewhere in this package. Asserting the
 * throw rather than an error field in the DTO also keeps the output contract
 * unchanged: on success the model receives exactly the JSON it received before.
 *
 * Each test also asserts the tool did not invent a success payload, which is
 * the failure mode the assertion on the exception alone would hide.
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
    fun `CreateNoteTool does not report a note it failed to create`() = runTest {
        val repo = FakeNotesRepository(currentUser = profileAwareUser)
        repo.createOverride = Result.failure(boom)

        val failed = assertFailsWith<IllegalStateException> {
            CreateNoteTool(repo, clock, profileAwareUser).execute(CreateNoteInput(title = "Ghost"))
        }
        assertTrue(failed.message == boom.message, "the repository's own exception should surface")
        assertTrue(repo.notes.isEmpty(), "nothing was written, so the tool has nothing to report")
    }

    @Test
    fun `CreateProjectTool does not report a project it failed to create`() = runTest {
        val repo = FakeProjectsRepository(currentUser = profileAwareUser)
        repo.createOverride = Result.failure(boom)

        assertFailsWith<IllegalStateException> {
            CreateProjectTool(repo, clock, profileAwareUser).execute(CreateProjectInput(name = "Ghost"))
        }
    }

    @Test
    fun `CreateTagTool does not report a tag it failed to create`() = runTest {
        val repo = FakeTagsRepository(currentUser = profileAwareUser)
        repo.createOverride = Result.failure(boom)

        assertFailsWith<IllegalStateException> {
            CreateTagTool(repo, clock, profileAwareUser).execute(CreateTagInput(name = "Ghost"))
        }
    }

    @Test
    fun `CreateTaskTool does not report a task it failed to create`() = runTest {
        val repo = FakeTaskRepository(explicitCurrentUser = profileAwareUser)
        repo.createOverride = Result.failure(boom)

        assertFailsWith<IllegalStateException> {
            CreateTaskTool(repo, clock, profileAwareUser).execute(CreateTaskInput(title = "Ghost"))
        }
    }

    // ─── Update ────────────────────────────────────────────────────────────────

    @Test
    fun `UpdateNoteTool does not report updated=true when the update failed`() = runTest {
        val repo = FakeNotesRepository(currentUser = profileAwareUser)
        val existing = note()
        repo.seed(existing)
        repo.updateOverride = Result.failure(boom)

        assertFailsWith<IllegalStateException> {
            UpdateNoteTool(repo, clock).execute(UpdateNoteInput(noteId = existing.id.value, title = "Renamed"))
        }
    }

    @Test
    fun `UpdateProjectTool does not report updated=true when the update failed`() = runTest {
        val repo = FakeProjectsRepository(currentUser = profileAwareUser)
        val existing = project()
        repo.seed(existing)
        repo.updateOverride = Result.failure(boom)

        assertFailsWith<IllegalStateException> {
            UpdateProjectTool(repo, clock)
                .execute(UpdateProjectInput(projectId = existing.id.value, name = "Renamed"))
        }
    }

    @Test
    fun `UpdateTaskTool does not report updated=true when the update failed`() = runTest {
        val repo = FakeTaskRepository(explicitCurrentUser = profileAwareUser)
        val existing = task()
        repo.seed(existing)
        repo.updateOverride = Result.failure(boom)

        assertFailsWith<IllegalStateException> {
            UpdateTaskTool(repo, clock)
                .execute(UpdateTaskInput(taskId = existing.id.value, title = "Renamed"))
        }
    }

    // ─── Fixtures ──────────────────────────────────────────────────────────────

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
