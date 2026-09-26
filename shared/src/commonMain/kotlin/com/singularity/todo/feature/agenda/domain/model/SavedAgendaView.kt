package com.singularity.todo.feature.agenda.domain.model

import com.singularity.todo.core.ids.UserId

import com.singularity.todo.feature.agenda.SavedAgendaViewId
import kotlin.time.Instant

/**
 * A saved agenda view — persisted user-created agenda configuration.
 *
 * Stored as an all-in-blob: the full [AgendaDefinition] is serialized as JSON
 * in [sectionsJson] and decoded on read. The [name] is the only user-editable field.
 * This class is NOT @Serializable — [sectionsJson] carries the pre-serialized JSON string.
 *
 * @param id unique within a user profile (UUID)
 * @param userId profile-scoped identifier
 * @param name user-facing display label
 * @param sectionsJson [AgendaDefinition] encoded via [com.singularity.todo.core.serialization.StableJson]
 * @param createdAt creation timestamp
 * @param updatedAt last-modified timestamp
 */
data class SavedAgendaView(
    val id: SavedAgendaViewId,
    val userId: UserId,
    val name: String,
    val sectionsJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
