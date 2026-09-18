package com.singularity.todo.core.backup

import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackupDtoTest {

    @Test
    fun taskDtoToEntityRoundtripPreservesFields() {
        val now = System.currentTimeMillis()
        val entity = TaskEntity(
            id = "task-1",
            title = "Test Task",
            description = "Description",
            priority = TaskPriority.High,
            kind = TaskKind.Task,
            projectId = "proj-1",
            dueDate = "2025-01-15",
            dueTime = "14:00",
            completedAt = null,
            someday = false,
            archivedAt = null,
            isPinned = true,
            createdAt = now,
            updatedAt = now,
            userId = "user-1",
            sync = SyncColumns(
                serverVersion = 5L,
                syncStatus = "SYNCED",
                syncError = "err",
                lastSyncedAt = now,
                deviceId = "device",
                hlc = "hlc-val",
            ),
        )

        val dto = entity.toDto()
        assertEquals("task-1", dto.id)
        assertEquals("Test Task", dto.title)
        assertEquals("Description", dto.description)
        assertEquals("High", dto.priority)
        assertEquals("Task", dto.kind)

        // Sync-only fields should be absent from DTO
        // The dto should have no serverVersion, syncStatus, hlc fields

        val restored = dto.toEntity("user-1")
        assertEquals(entity.id, restored.id)
        assertEquals(entity.title, restored.title)
        assertEquals(entity.userId, restored.userId)
        // Sync metadata defaults
        assertEquals(0L, restored.sync.serverVersion)
        assertEquals("LOCAL_ONLY", restored.sync.syncStatus)
        assertNull(restored.sync.syncError)
    }

    @Test
    fun noteDtoToEntityRoundtripPreservesFields() {
        val now = System.currentTimeMillis()
        val entity = NoteEntity(
            id = "note-1",
            userId = "user-1",
            title = "Note Title",
            bodyMarkdown = "# Hello",
            bodyHtml = "<h1>Hello</h1>",
            isFolder = false,
            parentNoteId = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            archivedAt = null,
            sync = SyncColumns(),
        )

        val dto = entity.toDto()
        val restored = dto.toEntity("user-1")

        assertEquals(entity.id, restored.id)
        assertEquals(entity.title, restored.title)
        assertEquals(entity.bodyMarkdown, restored.bodyMarkdown)
        assertEquals("user-1", restored.userId)
    }

    @Test
    fun projectDtoToEntityRoundtripPreservesFields() {
        val now = System.currentTimeMillis()
        val entity = ProjectEntity(
            id = "proj-1",
            userId = "user-1",
            name = "My Project",
            color = 0xFF5500,
            icon = "📁",
            description = "A project",
            createdAt = now,
            updatedAt = now,
            isDefault = false,
            dueDate = null,
            team = null,
            isDeleted = false,
            deletedAt = null,
            parentId = null,
            sortOrder = 0,
            idempotencyKey = null,
            externalId = null,
            sync = SyncColumns(),
        )

        val dto = entity.toDto()
        val restored = dto.toEntity("user-1")

        assertEquals(entity.id, restored.id)
        assertEquals(entity.name, restored.name)
        assertEquals(entity.color, restored.color)
    }
}
