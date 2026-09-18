package com.singularity.todo.core.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupPayload(
    val schemaVersion: Int,
    val tasks: List<TaskDto> = emptyList(),
    val notes: List<NoteDto> = emptyList(),
    val projects: List<ProjectDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val attachments: List<AttachmentDto> = emptyList(),
    val taskTags: List<TaskTagDto> = emptyList(),
    /** MR-1: task dependency edges (task_id → depends_on_task_id). */
    val taskDependencies: List<TaskDependencyDto> = emptyList(),
)
