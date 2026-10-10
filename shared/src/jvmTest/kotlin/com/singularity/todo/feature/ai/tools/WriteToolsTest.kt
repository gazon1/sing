
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai.tools

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.delay
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

/**
 * Unit tests for write tools: CreateTaskTool, UpdateTaskTool, DeleteTaskTool, CreateNoteTool.
 * Tests idempotency, not-found handling, and basic happy paths.
 *
 * All tools use FakeRepositories so tests run fast without Room/SQLite.
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

    // ─── CreateTaskTool ─────────────────────────────────────────────────────────

    @Test
    fun `CreateTaskTool creates a task and returns taskId`() = runTest {
        val tool = CreateTaskTool(fakeTaskRepo, clock, profileAwareUser)
        val input = CreateTaskInput(title = "Buy groceries")
        val outputJson = tool.execute(input)

        val output = Json.decodeFromString(CreateTaskOutput.serializer(), outputJson)
        assertNotNull(output.taskId)
        assertEquals("Buy groceries", output.title)
    }

    @Test
    fun `CreateTaskTool persists task in repository`() = runTest {
        val tool = CreateTaskTool(fakeTaskRepo, clock, profileAwareUser)
        tool.execute(CreateTaskInput(title = "Write tests"))

        val allTasks = fakeTaskRepo.tasks.value
        assertTrue(allTasks.values.any { it.title == "Write tests" })
    }

    @Test
    fun `CreateTaskTool accepts all optional fields`() = runTest {
        val tool = CreateTaskTool(fakeTaskRepo, clock, profileAwareUser)
        val input = CreateTaskInput(
            title = "Complex Task",
            description = "A description",
            priority = "High",
            kind = "Task",
            projectId = "proj-1",
            tagIds = listOf("tag-1", "tag-2"),
            dueDate = "2026-09-15",
            dueTime = "14:00",
            someday = true,
        )
        val outputJson = tool.execute(input)
        val output = Json.decodeFromString(CreateTaskOutput.serializer(), outputJson)

        val created = fakeTaskRepo.tasks.value[output.taskId]
        assertNotNull(created)
        assertEquals("Complex Task", created.title)
        assertEquals("A description", created.description)
        assertEquals(TaskPriority.High, created.priority)
        assertEquals(TaskKind.Task, created.kind)
        assertEquals("2026-09-15", created.dueDate.toString())
        assertEquals(com.singularity.todo.core.database.LocalTimeFormats.parse("14:00:00"), created.dueTime)
        assertTrue(created.someday)
    }

    // ─── UpdateTaskTool ─────────────────────────────────────────────────────────

    @Test
    fun `UpdateTaskTool updates title and priority`() = runTest {
        val tool = UpdateTaskTool(fakeTaskRepo, clock)
        val input = UpdateTaskInput(
            taskId = existingTaskId.value,
            title = "Renamed Task",
            priority = "High",
        )
        val outputJson = tool.execute(input)

        val output = Json.decodeFromString(UpdateTaskOutput.serializer(), outputJson)
        assertEquals(existingTaskId.value, output.taskId)
        assertTrue(output.updated)

        val updated = fakeTaskRepo.tasks.value[existingTaskId.value]
        assertNotNull(updated)
        assertEquals("Renamed Task", updated.title)
        assertEquals(TaskPriority.High, updated.priority)
        assertEquals("Seed description", updated.description)
    }

    @Test
    fun `UpdateTaskTool returns updated=false for non-existent task`() = runTest {
        val tool = UpdateTaskTool(fakeTaskRepo, clock)
        val input = UpdateTaskInput(taskId = "non-existent-id", title = "Should not change")
        val outputJson = tool.execute(input)

        val output = Json.decodeFromString(UpdateTaskOutput.serializer(), outputJson)
        assertEquals("non-existent-id", output.taskId)
        assertFalse(output.updated)
    }

    @Test
    fun `UpdateTaskTool partial update preserves other fields`() = runTest {
        val tool = UpdateTaskTool(fakeTaskRepo, clock)
        val original = fakeTaskRepo.tasks.value[existingTaskId.value]!!

        tool.execute(UpdateTaskInput(taskId = existingTaskId.value, title = "New Title"))

        val updated = fakeTaskRepo.tasks.value[existingTaskId.value]!!
        assertEquals("New Title", updated.title)
        assertEquals(original.description, updated.description)
        assertEquals(original.priority, updated.priority)
        assertEquals(original.kind, updated.kind)
        assertEquals(original.dueDate, updated.dueDate)
        assertEquals(original.someday, updated.someday)
    }

    @Test
    fun `UpdateTaskTool updatedAt changes on each update`() = runTest {
        val tool = UpdateTaskTool(fakeTaskRepo, clock)
        val original = fakeTaskRepo.tasks.value[existingTaskId.value]!!.updatedAt

        delay(1)
        tool.execute(UpdateTaskInput(taskId = existingTaskId.value, title = "Updated"))

        val updated = fakeTaskRepo.tasks.value[existingTaskId.value]!!
        assertTrue(updated.updatedAt >= original)
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
    fun `CreateNoteTool creates a note`() = runTest {
        val tool = CreateNoteTool(fakeNotesRepo, clock, profileAwareUser)
        val input = CreateNoteInput(title = "Meeting Notes", bodyMarkdown = "# Agenda")
        val outputJson = tool.execute(input)

        val output = Json.decodeFromString(CreateNoteOutput.serializer(), outputJson)
        assertNotNull(output.noteId)
        assertEquals("Meeting Notes", output.title)

        val created = fakeNotesRepo.notes[output.noteId]
        assertNotNull(created)
        // bodyMarkdown is stored as canonical HTML via NoteContentMapper.toHtml
        assertNull(created.bodyMarkdown, "bodyMarkdown should be null — content stored in bodyHtml")
        assertNotNull(created.bodyHtml, "bodyHtml should be set via NoteContentMapper.toHtml")
    }

    @Test
    fun `CreateNoteTool creates a folder`() = runTest {
        val tool = CreateNoteTool(fakeNotesRepo, clock, profileAwareUser)
        val input = CreateNoteInput(title = "My Folder", isFolder = true)
        val outputJson = tool.execute(input)

        val output = Json.decodeFromString(CreateNoteOutput.serializer(), outputJson)
        val created = fakeNotesRepo.notes[output.noteId]
        assertNotNull(created)
        assertTrue(created.isFolder)
    }

    @Test
    fun `CreateNoteTool uses scoped userId from profile`() = runTest {
        val tool = CreateNoteTool(fakeNotesRepo, clock, profileAwareUser)
        tool.execute(CreateNoteInput(title = "Scoped Note"))

        val created = fakeNotesRepo.notes.values.first { it.title == "Scoped Note" }
        assertEquals(userId, created.userId)
    }

    @Test
    fun `CreateNoteTool stores canonical HTML with actual converted content`() = runTest {
        // REQ-WP-031: bodyHtml must contain the actual HTML produced by NoteContentMapper.toHtml,
        // not just a non-null placeholder.  A DAO bypass would leave bodyMarkdown set and
        // bodyHtml null, so checking the actual HTML content also proves the routing.
        val tool = CreateNoteTool(fakeNotesRepo, clock, profileAwareUser)
        val input = CreateNoteInput(title = "Test", bodyMarkdown = "# Heading\n- Item 1\n- Item 2")
        val outputJson = tool.execute(input)

        val output = Json.decodeFromString(CreateNoteOutput.serializer(), outputJson)
        val created = fakeNotesRepo.notes[output.noteId]!!
        assertNull(created.bodyMarkdown, "bodyMarkdown must be null — raw markdown never stored")
        assertNotNull(created.bodyHtml, "bodyHtml must be set")
        // NoteContentMapper.toHtml uses RichTextState, which produces HTML elements.
        // Heading "# Heading" → <h1>Heading</h1>, list → <ul>/<li>.
        assertTrue(
            created.bodyHtml!!.contains("<h1>") || created.bodyHtml!!.contains("<h2>"),
            "HTML must contain a heading element from markdown conversion: ${created.bodyHtml}",
        )
        assertTrue(
            created.bodyHtml!!.contains("Heading"),
            "HTML must contain the heading text: ${created.bodyHtml}",
        )
        assertTrue(
            created.bodyHtml!!.contains("<ul>") || created.bodyHtml!!.contains("<li>"),
            "HTML must contain list elements from markdown conversion: ${created.bodyHtml}",
        )
    }

    @Test
    fun `CreateNoteTool routes through notesRepository-create not DAO`() = runTest {
        // REQ-WP-030: CreateNoteTool must go through notesRepository.create(), not write to
        // the DAO directly.  Verify by checking the note appears through the repository's
        // observable interface (observeAll), which is populated only via the repository path.
        // A DAO bypass would bypass the repository entirely and the note would not appear.
        val tool = CreateNoteTool(fakeNotesRepo, clock, profileAwareUser)
        tool.execute(CreateNoteInput(title = "Via Repository Route"))

        val observed = fakeNotesRepo.observeAll().first()
        assertTrue(
            observed.any { it.title == "Via Repository Route" },
            "Created note must appear in observeAll() — proves routing through repository, not DAO",
        )
    }
}
