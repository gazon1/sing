package com.singularity.todo.core.database

import androidx.room3.Dao
import androidx.room3.Query
import com.singularity.todo.core.search.TextMatchQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The full-text index — one read model over the four searchable kinds.
 *
 * ## Why the index has its own DAO
 *
 * The index is a read model, not a table of its own: every row it returns is a
 * row of `tasks`, `notes`, `projects` or `tags`. The queries that read it
 * therefore live here rather than in the four entity DAOs, for two reasons:
 *
 * - the entity DAOs are about rows — inserting, updating, archiving — and a
 *   full-text lookup is a different concern that happens to read the same rows;
 * - the index is one thing. Four DAOs with a full-text method each would be
 *   four places to keep the same join, the same tokenizer and the same
 *   `docid` rule in step.
 *
 * ## The join
 *
 * The `docid` of an index is the implicit `rowid` of the content table — the
 * id of a task is a UUID and an FTS `docid` is an integer, so the queries join
 * on `rowid`. See [TaskFtsEntity] for the whole pattern.
 *
 * ## The expression
 *
 * The `match` parameter is an FTS4 `MATCH` expression, and it is built by
 * [FtsMatchQuery.expression] — which makes it out of tokens only, so that
 * nothing a person types can become syntax. The methods that take a
 * [TextMatchQuery] build it and fall back to "no rows" when the query has no
 * searchable term.
 */
@Dao
interface FtsDao {

    /**
     * The tasks whose title or description match the index.
     */
    @Query(
        """
        SELECT t.* FROM tasks t
        JOIN tasks_fts ON t.rowid = tasks_fts.docid
        WHERE tasks_fts MATCH :match
          AND t.user_id = :userId
          AND t.archived_at IS NULL
        ORDER BY t.due_date ASC, t.is_pinned DESC
        """,
    )
    fun watchTasksByMatch(userId: String, match: String): Flow<List<TaskEntity>>

    /**
     * The notes whose title match the index.
     */
    @Query(
        """
        SELECT n.* FROM notes n
        JOIN notes_fts ON n.rowid = notes_fts.docid
        WHERE notes_fts MATCH :match
          AND n.user_id = :userId
          AND n.archived_at IS NULL
          AND n.deleted_at IS NULL
        ORDER BY n.updated_at DESC
        LIMIT 20
        """,
    )
    fun watchNotesByMatch(userId: String, match: String): Flow<List<NoteEntity>>

    /**
     * The projects whose name match the index.
     */
    @Query(
        """
        SELECT p.* FROM projects p
        JOIN projects_fts ON p.rowid = projects_fts.docid
        WHERE projects_fts MATCH :match
          AND p.user_id = :userId
          AND p.is_deleted = 0
        ORDER BY p.sort_order ASC, p.name ASC
        """,
    )
    fun watchProjectsByMatch(userId: String, match: String): Flow<List<ProjectEntity>>

    /**
     * The tags whose name match the index.
     */
    @Query(
        """
        SELECT t.* FROM tags t
        JOIN tags_fts ON t.rowid = tags_fts.docid
        WHERE tags_fts MATCH :match
          AND t.user_id = :userId
          AND t.deleted_at IS NULL
        ORDER BY t.sort_order ASC, t.name ASC
        """,
    )
    fun watchTagsByMatch(userId: String, match: String): Flow<List<TagEntity>>

    /**
     * The tasks whose title matches the index — the first twenty, most recently
     * updated first. Used to offer a title for a link.
     */
    @Query(
        """
        SELECT t.* FROM tasks t
        JOIN tasks_fts ON t.rowid = tasks_fts.docid
        WHERE tasks_fts MATCH :match
          AND t.user_id = :userId
          AND t.archived_at IS NULL
        ORDER BY t.updated_at DESC
        LIMIT :limit
        """,
    )
    suspend fun searchTaskTitlesByMatch(userId: String, match: String, limit: Int): List<TaskEntity>

    /**
     * The notes whose title matches the index — the first twenty, most recently
     * updated first. Used to offer a title for a link.
     */
    @Query(
        """
        SELECT n.* FROM notes n
        JOIN notes_fts ON n.rowid = notes_fts.docid
        WHERE notes_fts MATCH :match
          AND n.user_id = :userId
          AND n.archived_at IS NULL
          AND n.deleted_at IS NULL
        ORDER BY n.updated_at DESC
        LIMIT :limit
        """,
    )
    suspend fun searchNoteTitlesByMatch(userId: String, match: String, limit: Int): List<NoteEntity>

    // ── The semantic surface ────────────────────────────────────────────────
    //
    // The methods above take an expression; the ones below take the query a
    // caller means and build the expression for it. A query with no searchable
    // term matches nothing — and saying so is not the same as matching
    // everything.

    /**
     * The tasks whose title or description match [query].
     */
    fun watchTasks(userId: String, query: TextMatchQuery): Flow<List<TaskEntity>> =
        FtsMatchQuery.expression(query)
            ?.let { match -> watchTasksByMatch(userId, match) }
            ?: flowOf(emptyList())

    /**
     * The notes whose title match [query].
     */
    fun watchNotes(userId: String, query: TextMatchQuery): Flow<List<NoteEntity>> =
        FtsMatchQuery.expression(query)
            ?.let { match -> watchNotesByMatch(userId, match) }
            ?: flowOf(emptyList())

    /**
     * The projects whose name match [query].
     */
    fun watchProjects(userId: String, query: TextMatchQuery): Flow<List<ProjectEntity>> =
        FtsMatchQuery.expression(query)
            ?.let { match -> watchProjectsByMatch(userId, match) }
            ?: flowOf(emptyList())

    /**
     * The tags whose name match [query].
     */
    fun watchTags(userId: String, query: TextMatchQuery): Flow<List<TagEntity>> =
        FtsMatchQuery.expression(query)
            ?.let { match -> watchTagsByMatch(userId, match) }
            ?: flowOf(emptyList())
}
