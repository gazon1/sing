package com.singularity.todo.feature.ai.tools

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * No tool writes the user's data. Every tool that changes something proposes it,
 * and the write happens when the user confirms the proposal.
 *
 * ## What this replaces
 *
 * The claim used to live in a file — `config/docs/ai-direct-writes.txt` — that
 * listed the tools writing directly, with the reason each one did. It was a
 * registry somebody had to remember to update, and the gate that read it could
 * only prove that the registry and the code agreed with each other.
 *
 * A test is a better home for the same claim. It runs the tools against fakes and
 * asserts on what the user's data looks like afterwards: a tool that writes fails
 * the test, and no list has to be kept in step with the code.
 *
 * ## Why there is a source scan as well
 *
 * The behavioural tests name the tools they exercise. A *new* tool that writes
 * would not be in that list, so the scan closes the gap: no tool's source may
 * call a write on a repository of the user's own data. The proposal repository is
 * the one exception — saving a proposal is how a tool proposes, not how it writes.
 */
@Tag("fast")
class WriteToolsOnlyProposeTest {

    private val clock: Clock = Clock.System
    private val userId = UserId("test-user")
    private val profileAwareUser = FakeProfileAwareCurrentUser(
        authRepository = FakeAuthRepository(initialSession = Session.Anonymous(userId)),
    )

    private val proposals = FakeProposalRepository(clock)
    private val tasks = FakeTaskRepository(explicitCurrentUser = profileAwareUser)
    private val notes = FakeNotesRepository(currentUser = profileAwareUser)
    private val projects = FakeProjectsRepository(currentUser = profileAwareUser)
    private val tags = FakeTagsRepository(currentUser = profileAwareUser)

    // ─── The behavioural half ───────────────────────────────────────────────────

    @Test
    fun `CreateTaskTool proposes a task instead of creating one`() = runTest {
        CreateTaskTool(proposals, profileAwareUser, clock)
            .execute(CreateTaskInput(title = "Buy groceries"))

        assertTrue(tasks.tasks.value.isEmpty(), "the task was not created")
        assertEquals(1, pendingProposals().size, "the change was proposed")
    }

    @Test
    fun `UpdateTaskTool proposes a change instead of making one`() = runTest {
        val existing = task()
        tasks.seed(existing)

        UpdateTaskTool(proposals, tasks, profileAwareUser, clock)
            .execute(UpdateTaskInput(taskId = existing.id.value, title = "Renamed"))

        assertEquals("Existing", tasks.tasks.value[existing.id.value]?.title, "the task was not renamed")
        assertEquals(1, pendingProposals().size, "the change was proposed")
    }

    @Test
    fun `CreateProjectTool proposes a project instead of creating one`() = runTest {
        CreateProjectTool(proposals, profileAwareUser, clock)
            .execute(CreateProjectInput(name = "Kitchen"))

        assertTrue(projects.observeAll().first().isEmpty(), "the project was not created")
        assertEquals(1, pendingProposals().size, "the change was proposed")
    }

    @Test
    fun `UpdateProjectTool proposes a change instead of making one`() = runTest {
        val existing = project()
        projects.seed(existing)

        UpdateProjectTool(proposals, projects, profileAwareUser, clock)
            .execute(UpdateProjectInput(projectId = existing.id.value, name = "Renamed"))

        assertEquals("Existing", projects.get(existing.id)?.name, "the project was not renamed")
        assertEquals(1, pendingProposals().size, "the change was proposed")
    }

    @Test
    fun `CreateNoteTool proposes a note instead of creating one`() = runTest {
        CreateNoteTool(proposals, profileAwareUser, clock)
            .execute(CreateNoteInput(title = "Agenda"))

        assertTrue(notes.notes.isEmpty(), "the note was not created")
        assertEquals(1, pendingProposals().size, "the change was proposed")
    }

    @Test
    fun `UpdateNoteTool proposes a change instead of making one`() = runTest {
        val existing = note()
        notes.seed(existing)

        UpdateNoteTool(proposals, notes, profileAwareUser, clock)
            .execute(UpdateNoteInput(noteId = existing.id.value, title = "Renamed"))

        assertEquals("Existing", notes.notes[existing.id.value]?.title, "the note was not renamed")
        assertEquals(1, pendingProposals().size, "the change was proposed")
    }

    @Test
    fun `CreateTagTool proposes a tag instead of creating one`() = runTest {
        CreateTagTool(proposals, profileAwareUser, clock)
            .execute(CreateTagInput(name = "chore"))

        assertTrue(tags.observeAll().first().isEmpty(), "the tag was not created")
        assertEquals(1, pendingProposals().size, "the change was proposed")
    }

    // ─── The structural half ────────────────────────────────────────────────────

    @Test
    fun `no tool source writes the user's data directly`() {
        val offences = mutableListOf<String>()

        Files.list(toolsDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".kt") }.forEach { file ->
                val text = Files.readString(file)
                repositoryWrite.findAll(text).forEach { match ->
                    val receiver = match.groupValues[1]
                    if (receiver.lowercase() !in PROPOSAL_RECEIVERS) {
                        offences += "${file.fileName}: $receiver.${match.groupValues[2]}"
                    }
                }
            }
        }

        assertTrue(
            offences.isEmpty(),
            "these tools write the user's data instead of proposing it: ${offences.joinToString(", ")}. " +
                "A proposal is a change nobody has agreed to yet; the write happens when the user confirms it.",
        )
    }

    // ─── Fixtures ──────────────────────────────────────────────────────────────

    private suspend fun pendingProposals(): List<AiProposal> =
        proposals.watchProposalsByStatus(ProposalStatus.Pending).first()

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

    private companion object {
        /**
         * A call on a repository. The receiver is captured so the proposal
         * repository can be told apart from the user's own data.
         */
        val repositoryWrite = Regex("""\b(\w*[Rr]epository)\.(create|update|delete|upsert|save)\(""")

        /** The proposal repository, by the names it is given in the tools. */
        val PROPOSAL_RECEIVERS = setOf("proposals", "proposalrepository")

        /**
         * Where the tools live.
         *
         * The sources are inside this module, so they are a compile input of the
         * test task: change a tool and this test runs again.
         */
        val toolsDir: Path = run {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                val candidate = dir.resolve("src/commonMain/kotlin/com/singularity/todo/feature/ai/tools")
                if (Files.isDirectory(candidate)) return@run candidate
                dir = dir.parent
            }
            error("the tools directory was not found above ${Path.of("").toAbsolutePath()}")
        }
    }
}
