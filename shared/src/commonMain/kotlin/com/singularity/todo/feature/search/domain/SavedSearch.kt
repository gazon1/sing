package com.singularity.todo.feature.search.domain
import com.singularity.todo.core.ids.UserId
import kotlin.time.Instant

/**
 * A saved search — a named, user-created search query.
 *
 * Stored with a raw [queryString] so the user's exact input is preserved
 * (including any syntax the UI-level [SimpleFilterMapper][com.singularity.todo.feature.search.query.SimpleFilterMapper]
 * cannot express). This avoids round-trip loss when the user saves a complex query.
 *
 * @param id unique within a user profile (UUID)
 * @param userId profile-scoped identifier
 * @param name user-facing display label (editable)
 * @param queryString the raw query string as entered by the user
 * @param createdAt creation timestamp
 * @param updatedAt last-modified timestamp
 */
data class SavedSearch(
    val id: SavedSearchId,
    val userId: UserId,
    val name: String,
    val queryString: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
