package com.singularity.todo.core.backup

import com.singularity.todo.core.database.AgendaViewEntity
import com.singularity.todo.core.database.SavedSearchEntity
import kotlinx.serialization.Serializable

/**
 * The saved views of a payload: the agenda views, and the saved searches.
 */
// ─── AgendaViewDto (MR-1: agenda_views in backup) ─────────────────────────────

@Serializable
data class AgendaViewDto(
    val id: String,
    val name: String,
    val sectionsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

fun AgendaViewEntity.toDto(): AgendaViewDto =
    AgendaViewDto(
        id = id,
        name = name,
        sectionsJson = sectionsJson,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun AgendaViewDto.toEntity(userId: String): AgendaViewEntity =
    AgendaViewEntity(
        id = id,
        userId = userId,
        name = name,
        sectionsJson = sectionsJson,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
// ─── SavedSearchDto (MR-2) ───────────────────────────────────────────────────

@Serializable
data class SavedSearchDto(
    val id: String,
    val name: String,
    val queryString: String,
    val createdAt: Long,
    val updatedAt: Long,
)

fun SavedSearchEntity.toDto(): SavedSearchDto =
    SavedSearchDto(
        id = id,
        name = name,
        queryString = queryString,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun SavedSearchDto.toEntity(userId: String): SavedSearchEntity =
    SavedSearchEntity(
        id = id,
        userId = userId,
        name = name,
        queryString = queryString,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
