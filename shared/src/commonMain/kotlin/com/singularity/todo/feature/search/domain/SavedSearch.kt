package com.singularity.todo.feature.search.domain
import com.singularity.todo.core.ids.UserId
import kotlin.time.Instant

/**
 * A saved search — a named, user-created search query.
 *
 * Stored with a raw [queryString] so the query survives a round trip (including
 * any syntax the UI-level [SimpleFilterMapper][com.singularity.todo.feature.search.query.SimpleFilterMapper]
 * cannot express). The string is the query the screen was running when the
 * search was saved: the text the user typed, or — after a filter has been
 * applied — that text written back from the filter. See
 * `docs/decisions/2026-11-12-a-saved-search-carries-the-query-that-runs.md`.
 *
 * @param id unique within a user profile (UUID)
 * @param userId profile-scoped identifier
 * @param name user-facing display label (editable)
 * @param queryString the query text of the search
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
