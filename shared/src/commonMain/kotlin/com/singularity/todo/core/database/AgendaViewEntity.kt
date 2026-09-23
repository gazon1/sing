package com.singularity.todo.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/**
 * Persisted saved agenda view.
 *
 * Uses a composite primary key `(user_id, id)` for per-profile isolation.
 * The full agenda definition (sections, selectors, etc.) is stored as a single
 * JSON blob in [sectionsJson] — SQL-level filterability is not required.
 *
 * @param id unique within a user profile (UUID)
 * @param userId profile-scoped identifier
 * @param name user-facing display label (editable)
 * @param sectionsJson [com.singularity.todo.feature.agenda.domain.model.AgendaDefinition]
 *        serialized via [com.singularity.todo.core.json.StableJson]
 * @param createdAt epoch millis
 * @param updatedAt epoch millis
 */
@Entity(
    tableName = "agenda_views",
    primaryKeys = ["id", "user_id"],
)
data class AgendaViewEntity(
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id", index = true)
    val userId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "sections_json")
    val sectionsJson: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
