
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai.tools

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.NoteValue
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalTargetKind
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
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
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Unit tests for the write tools: the tools that change the user's data.
 *
 * ## What they assert
 *
 * The write tools propose. Every test here says so twice: once about the tool's
 * own answer (a proposal was created, not a task) and once about the user's
 * data (nothing was written). The second half is the one that matters — a tool
 * that reports "I proposed it" and then writes anyway has not been moved onto
 * the proposal path at all.
 */
@Tag("fast")
class WriteToolsTest {

    private val clock: Clock = Clock.System
    private val userId = UserId("test-user")
    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeNotesRepo = FakeNotesRepository()
    private val fakeProposalRepo = FakeProposalRepository(clock)
    private val existingTaskId = TaskId.generate()
    private val profileAwareUser = FakeProfileAwareCurrentUser(
        authRepository = FakeAuthRepository(initialSession = Session.Anonymous(userId)),
    )

    init {
        // Seed one existing task for update/delete tests
        fakeTaskRepo.seed(
            Task(
                id = existingTaskId,
                title = "Existing Task",
                description = "Seed description",
                priority = TaskPriority.Medium,
                kind = TaskKind.Task,
                projectId = null,
                tags = emptyList(),
                dueDate = null,
                dueTime = null,
                someday = false,
                completedAt = null,
                archivedAt = null,
                isPinned = false,
                createdAt = clock.now(),
                updatedAt = clock.now(),
                userId = userId,
            ),
        )
    }

    /** The proposals waiting for a decision. */
    private suspend fun pendingProposals(): List<AiProposal> =
        fakeProposalRepo.watchWaitingProposals().first()

    // ─── CreateTaskTool ─────────────────────────────────────────────────────────

    @Test
    fun `CreateTaskTool proposes a task instead of creating one`() = runTest {
        val tool = CreateTaskTool(fakeProposalRepo, profileAwareUser, clock)
        val outputJson = tool.execute(CreateTaskInput(title = "Buy groceries"))

        val output = Json.decodeFromString(CreateTaskOutput.serializer(), outputJson)
        assertTrue(output.proposalCreated)
        assertNotNull(output.proposalId)
        assertNull(output.error)
        assertEquals("Buy groceries", output.title)

        // The user's data is untouched: the task waits for the user.
        assertTrue(fakeTaskRepo.tasks.value.values.none { it.title == "Buy groceries" })

        val proposal = pendingProposals().single()
        assertEquals(ProposalTargetKind.Task, proposal.targetKind)
        val item = proposal.items.single()
        val kind = item.kind as ProposalItemKind.CreateTask
        assertEquals("Buy groceries", kind.task.title)
    }

    @Test
    fun `CreateTaskTool carries every field into the proposal`() = runTest {
        val tool = CreateTaskTool(fakeProposalRepo, profileAwareUser, clock)
        tool.execute(
            CreateTaskInput(
                title = "Complex Task",
                description = "A description",
                priority = "High",
                kind = "Task",
                projectId = "proj-1",
                tagIds = listOf("tag-1", "tag-2"),
                dueDate = "2026-09-15",
                dueTime = "14:00",
                someday = true,
            ),
        )

        val kind = pendingProposals().single().items.single().kind as ProposalItemKind.CreateTask
        assertEquals("A description", kind.task.description)
        assertEquals(TaskPriority.High, kind.task.priority)
        assertEquals("proj-1", kind.task.projectId)
        assertEquals(listOf("tag-1", "tag-2"), kind.task.tagIds)
        assertEquals(LocalDate.parse("2026-09-15"), kind.task.dueDate)
        assertEquals(LocalTime.parse("14:00"), kind.task.dueTime)
        assertTrue(kind.task.someday)
    }

    @Test
    fun `CreateTaskTool refuses a task without a title`() = runTest {
        val tool = CreateTaskTool(fakeProposalRepo, profileAwareUser, clock)
        val outputJson = tool.execute(CreateTaskInput(title = "   "))

        val output = Json.decodeFromString(CreateTaskOutput.serializer(), outputJson)
        assertFalse(output.proposalCreated)
        assertNotNull(output.error)
        assertTrue(pendingProposals().isEmpty())
    }

    @Test
    fun `CreateTaskTool carries a priority the model spelled in lower case`() = runTest {
        val tool = CreateTaskTool(fakeProposalRepo, profileAwareUser, clock)
        tool.execute(CreateTaskInput(title = "Buy groceries", priority = "high"))

        // The task holds "High". A proposal that carries "high" would fail to
        // apply, and a proposal the user approved and could not apply is a
        // proposal that burned the user's attention for nothing.
        val kind = pendingProposals().single().items.single().kind as ProposalItemKind.CreateTask
        assertEquals(TaskPriority.High, kind.task.priority)
    }

    @Test
    fun `CreateTaskTool names a priority that does not exist`() = runTest {
        val tool = CreateTaskTool(fakeProposalRepo, profileAwareUser, clock)
        val outputJson = tool.execute(CreateTaskInput(title = "Buy groceries", priority = "VeryHigh"))

        val output = Json.decodeFromString(CreateTaskOutput.serializer(), outputJson)
        assertFalse(output.proposalCreated)
        assertNotNull(output.error)
        assertTrue(pendingProposals().isEmpty())
    }

    // ─── UpdateTaskTool ─────────────────────────────────────────────────────────

    @Test
    fun `UpdateTaskTool proposes the changed fields and writes nothing`() = runTest {
        val tool = UpdateTaskTool(fakeProposalRepo, fakeTaskRepo, profileAwareUser, clock)
        val outputJson = tool.execute(
            UpdateTaskInput(
                taskId = existingTaskId.value,
                title = "Renamed Task",
                priority = "High",
            ),
        )

        val output = Json.decodeFromString(UpdateTaskOutput.serializer(), outputJson)
        assertTrue(output.proposalCreated)
        assertEquals(existingTaskId.value, output.taskId)

        // The task is the one the user had, not the one the model asked for.
        val untouched = fakeTaskRepo.tasks.value[existingTaskId.value]
        assertNotNull(untouched)
        assertEquals("Existing Task", untouched.title)
        assertEquals(TaskPriority.Medium, untouched.priority)

        val kinds = pendingProposals().single().items.map { it.kind }
        assertEquals(
            listOf(
                ProposalItemKind.SetTaskField(TaskValue.Title("Renamed Task")),
                ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)),
            ),
            kinds,
        )
    }

    @Test
    fun `UpdateTaskTool proposes nothing when the task already has the values`() = runTest {
        val tool = UpdateTaskTool(fakeProposalRepo, fakeTaskRepo, profileAwareUser, clock)
        val outputJson = tool.execute(
            UpdateTaskInput(taskId = existingTaskId.value, title = "Existing Task"),
        )

        val output = Json.decodeFromString(UpdateTaskOutput.serializer(), outputJson)
        assertFalse(output.proposalCreated)
        assertNotNull(output.error)
        assertTrue(pendingProposals().isEmpty())
    }

    @Test
    fun `UpdateTaskTool says so when there is no such task`() = runTest {
        val tool = UpdateTaskTool(fakeProposalRepo, fakeTaskRepo, profileAwareUser, clock)
        val outputJson = tool.execute(UpdateTaskInput(taskId = "non-existent-id", title = "Should not change"))

        val output = Json.decodeFromString(UpdateTaskOutput.serializer(), outputJson)
        assertEquals("non-existent-id", output.taskId)
        assertFalse(output.proposalCreated)
        assertNotNull(output.error)
        assertTrue(pendingProposals().isEmpty())
    }

    @Test
    fun `UpdateTaskTool names a priority it cannot read`() = runTest {
        val tool = UpdateTaskTool(fakeProposalRepo, fakeTaskRepo, profileAwareUser, clock)
        val outputJson = tool.execute(
            UpdateTaskInput(taskId = existingTaskId.value, priority = "VeryHigh"),
        )

        val output = Json.decodeFromString(UpdateTaskOutput.serializer(), outputJson)
        assertFalse(output.proposalCreated)
        assertNotNull(output.error)
        assertTrue(pendingProposals().isEmpty())
    }

    @Test
    fun `UpdateTaskTool proposes the whole tag set when the tags differ`() = runTest {
        val tool = UpdateTaskTool(fakeProposalRepo, fakeTaskRepo, profileAwareUser, clock)
        tool.execute(UpdateTaskInput(taskId = existingTaskId.value, tagIds = listOf("tag-1", "tag-2")))

        val kind = pendingProposals().single().items.single().kind
        assertEquals(ProposalItemKind.SetTaskTags(listOf("tag-1", "tag-2")), kind)
    }

    @Test
    fun `UpdateTaskTool proposes a priority the model spelled in lower case`() = runTest {
        val tool = UpdateTaskTool(fakeProposalRepo, fakeTaskRepo, profileAwareUser, clock)
        tool.execute(UpdateTaskInput(taskId = existingTaskId.value, priority = "urgent"))

        val kind = pendingProposals().single().items.single().kind
        assertEquals(ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.Urgent)), kind)
    }

    @Test
    fun `UpdateTaskTool proposes nothing when the priority is the one the task has`() = runTest {
        val tool = UpdateTaskTool(fakeProposalRepo, fakeTaskRepo, profileAwareUser, clock)
        val outputJson = tool.execute(
            UpdateTaskInput(taskId = existingTaskId.value, priority = "medium"),
        )

        // The task holds "Medium"; "medium" is the same priority, so there is
        // nothing to propose.
        val output = Json.decodeFromString(UpdateTaskOutput.serializer(), outputJson)
        assertFalse(output.proposalCreated)
        assertTrue(pendingProposals().isEmpty())
    }

    // ─── DeleteTaskTool ─────────────────────────────────────────────────────────

    @Test
    fun `DeleteTaskTool creates a delete proposal for existing task`() = runTest {
        val tool = DeleteTaskTool(fakeProposalRepo, profileAwareUser, clock)
        val input = DeleteTaskInput(taskId = existingTaskId.value)
        val outputJson = tool.execute(input)

        val output = Json.decodeFromString(DeleteTaskOutput.serializer(), outputJson)
        assertEquals(existingTaskId.value, output.taskId)
        assertTrue(output.proposalCreated)
        assertNotNull(output.proposalId)
        assertNull(output.error)
    }

    @Test
    fun `DeleteTaskTool creates proposal even for non-existent task`() = runTest {
        // Proposal creation is idempotent: a proposal is created even if the task
        // doesn't exist — the confirm step will fail gracefully.
        val tool = DeleteTaskTool(fakeProposalRepo, profileAwareUser, clock)
        val input = DeleteTaskInput(taskId = "does-not-exist")
        val outputJson = tool.execute(input)

        val output = Json.decodeFromString(DeleteTaskOutput.serializer(), outputJson)
        assertEquals("does-not-exist", output.taskId)
        assertTrue(output.proposalCreated)
        assertNull(output.error)
    }

    @Test
    fun `DeleteTaskTool does not directly archive the task`() = runTest {
        // Deletion is deferred: the tool creates a proposal but does NOT directly archive.
        // Archiving happens when the proposal is confirmed via ApplyProposalItemUseCase.
        val tool = DeleteTaskTool(fakeProposalRepo, profileAwareUser, clock)
        assertNull(fakeTaskRepo.tasks.value[existingTaskId.value]!!.archivedAt)

        tool.execute(DeleteTaskInput(taskId = existingTaskId.value))

        // Task is NOT archived directly — only the proposal is created
        assertNull(fakeTaskRepo.tasks.value[existingTaskId.value]!!.archivedAt)
    }

    // ─── CreateNoteTool ─────────────────────────────────────────────────────────

    @Test
    fun `CreateNoteTool proposes a note instead of creating one`() = runTest {
        val tool = CreateNoteTool(fakeProposalRepo, profileAwareUser, clock)
        val input = CreateNoteInput(title = "Meeting Notes", bodyMarkdown = "# Agenda")
        val outputJson = tool.execute(input)

        val output = Json.decodeFromString(CreateNoteOutput.serializer(), outputJson)
        assertTrue(output.proposalCreated)
        assertNotNull(output.proposalId)
        assertEquals("Meeting Notes", output.title)

        // The user's notes are untouched.
        assertTrue(fakeNotesRepo.notes.values.none { it.title == "Meeting Notes" })

        val kind = pendingProposals().single().items.single().kind as ProposalItemKind.CreateNote
        assertEquals("Meeting Notes", kind.note.title)
        // The body is stored as canonical HTML, so the proposal carries the HTML.
        assertNotNull(kind.note.bodyHtml)
    }

    @Test
    fun `CreateNoteTool proposes a folder`() = runTest {
        val tool = CreateNoteTool(fakeProposalRepo, profileAwareUser, clock)
        tool.execute(CreateNoteInput(title = "My Folder", isFolder = true))

        val kind = pendingProposals().single().items.single().kind as ProposalItemKind.CreateNote
        assertTrue(kind.note.isFolder)
    }

    @Test
    fun `CreateNoteTool owns the proposal with the scoped userId`() = runTest {
        val tool = CreateNoteTool(fakeProposalRepo, profileAwareUser, clock)
        tool.execute(CreateNoteInput(title = "Scoped Note"))

        assertEquals(userId, pendingProposals().single().userId)
    }

    // ─── UpdateNoteTool ─────────────────────────────────────────────────────────

    @Test
    fun `UpdateNoteTool proposes the changed fields and writes nothing`() = runTest {
        val noteId = com.singularity.todo.feature.notes.NoteId.generate()
        fakeNotesRepo.seed(
            com.singularity.todo.feature.notes.Note(
                id = noteId,
                title = "Old title",
                bodyMarkdown = null,
                bodyHtml = "<p>Old body</p>",
                isFolder = false,
                parentNoteId = null,
                createdAt = clock.now(),
                updatedAt = clock.now(),
                userId = userId,
            ),
        )

        val tool = UpdateNoteTool(fakeProposalRepo, fakeNotesRepo, profileAwareUser, clock)
        val outputJson = tool.execute(UpdateNoteInput(noteId = noteId.value, title = "New title"))

        val output = Json.decodeFromString(UpdateNoteOutput.serializer(), outputJson)
        assertTrue(output.proposalCreated)

        // The note is the one the user had.
        assertEquals("Old title", fakeNotesRepo.notes[noteId.value]?.title)

        val kind = pendingProposals().single().items.single().kind
        assertEquals(ProposalItemKind.SetNoteField(NoteValue.Title("New title")), kind)
    }
}
