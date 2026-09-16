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
)
