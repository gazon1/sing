package com.singularity.todo.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

/**
 * Persisted saved search.
 *
 * Uses a composite primary key `(id, user_id)` for per-profile isolation.
 * The [queryString] is stored as-is (raw user input), not parsed — avoids
 * round-trip loss for complex queries that SimpleFilter cannot represent.
 *
 * @param id unique within a user profile (UUID)
 * @param userId profile-scoped identifier
 * @param name user-facing display label (editable)
 * @param queryString the raw query string as entered by the user
 * @param createdAt epoch millis
 * @param updatedAt epoch millis
 */
@Entity(
    tableName = "saved_searches",
    primaryKeys = ["id", "user_id"],
    indices = [Index(value = ["user_id", "name"])],
)
data class SavedSearchEntity(
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id", index = true)
    val userId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "query_string")
    val queryString: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
