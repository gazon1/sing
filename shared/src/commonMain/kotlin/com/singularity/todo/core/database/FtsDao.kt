package com.singularity.todo.core.database

import androidx.room3.Dao
import androidx.room3.Query
import com.singularity.todo.core.search.SearchLimits
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
 * nothing a person types can become syntax.
 *
 * The methods that take an expression are the *raw* surface; the ones that take
 * a [TextMatchQuery] are the *semantic* one, and they are what a caller uses. A
 * query with no searchable term matches nothing — and saying so is not the same
 * as matching everything.
 *
 * ## The row carries the position
 *
 * Every query here answers with a row *and* the position of the match inside
 * it, read from FTS4's `offsets()`. The index knows where the word is — it is
 * the only one that does — and a search that shows the word in the result has
 * to be told where it is.
 *
 * The position travels as the raw answer of `offsets()`, a string of integers.
 * Reading it — turning bytes into the characters of the text — needs the text
 * of the column, and only the reader has it. See [FtsOffsets].
 */
@Dao
interface FtsDao {

    /**
     * The tasks whose title or description match the index.
     */
    @Query(
        """
        SELECT t.*, offsets(tasks_fts) AS match_offsets FROM tasks t
        JOIN tasks_fts ON t.rowid = tasks_fts.docid
        WHERE tasks_fts MATCH :match
          AND t.user_id = :userId
          AND t.archived_at IS NULL
        ORDER BY t.due_date ASC, t.is_pinned DESC
        """,
    )
    fun watchTasksByMatch(userId: String, match: String): Flow<List<TaskMatchRow>>

    /**
     * The notes whose title match the index.
     */
    @Query(
        """
        SELECT n.*, offsets(notes_fts) AS match_offsets FROM notes n
        JOIN notes_fts ON n.rowid = notes_fts.docid
        WHERE notes_fts MATCH :match
          AND n.user_id = :userId
          AND n.archived_at IS NULL
          AND n.deleted_at IS NULL
        ORDER BY n.updated_at DESC
        LIMIT :limit
        """,
    )
    fun watchNotesByMatch(userId: String, match: String, limit: Int): Flow<List<NoteMatchRow>>

    /**
     * The projects whose name match the index.
     */
    @Query(
        """
        SELECT p.*, offsets(projects_fts) AS match_offsets FROM projects p
        JOIN projects_fts ON p.rowid = projects_fts.docid
        WHERE projects_fts MATCH :match
          AND p.user_id = :userId
          AND p.is_deleted = 0
        ORDER BY p.sort_order ASC, p.name ASC
        """,
    )
    fun watchProjectsByMatch(userId: String, match: String): Flow<List<ProjectMatchRow>>

    /**
     * The tags whose name match the index.
     */
    @Query(
        """
        SELECT t.*, offsets(tags_fts) AS match_offsets FROM tags t
        JOIN tags_fts ON t.rowid = tags_fts.docid
        WHERE tags_fts MATCH :match
          AND t.user_id = :userId
          AND t.deleted_at IS NULL
        ORDER BY t.sort_order ASC, t.name ASC
        """,
    )
    fun watchTagsByMatch(userId: String, match: String): Flow<List<TagMatchRow>>

    /**
     * The tasks whose title matches the index — the first twenty, most recently
     * updated first. Used to offer a title for a link.
     */
    @Query(
        """
        SELECT t.*, offsets(tasks_fts) AS match_offsets FROM tasks t
        JOIN tasks_fts ON t.rowid = tasks_fts.docid
        WHERE tasks_fts MATCH :match
          AND t.user_id = :userId
          AND t.archived_at IS NULL
        ORDER BY t.updated_at DESC
        LIMIT :limit
        """,
    )
    suspend fun searchTaskTitlesByMatch(userId: String, match: String, limit: Int): List<TaskMatchRow>

    /**
     * The notes whose title matches the index — the first twenty, most recently
     * updated first. Used to offer a title for a link.
     */
    @Query(
        """
        SELECT n.*, offsets(notes_fts) AS match_offsets FROM notes n
        JOIN notes_fts ON n.rowid = notes_fts.docid
        WHERE notes_fts MATCH :match
          AND n.user_id = :userId
          AND n.archived_at IS NULL
          AND n.deleted_at IS NULL
        ORDER BY n.updated_at DESC
        LIMIT :limit
        """,
    )
    suspend fun searchNoteTitlesByMatch(userId: String, match: String, limit: Int): List<NoteMatchRow>

    // ── The semantic surface ────────────────────────────────────────────────
    //
    // The methods above take an expression; the ones below take the query a
    // caller means and build the expression for it. A query with no searchable
    // term matches nothing — and saying so is not the same as matching
    // everything.

    /**
     * The tasks whose title or description match [query].
     */
    fun watchTasks(userId: String, query: TextMatchQuery): Flow<List<TaskMatchRow>> =
        FtsMatchQuery.expression(query)
            ?.let { match -> watchTasksByMatch(userId, match) }
            ?: flowOf(emptyList())

    /**
     * The notes whose title match [query].
     */
    fun watchNotes(userId: String, query: TextMatchQuery): Flow<List<NoteMatchRow>> =
        FtsMatchQuery.expression(query)
            ?.let { match -> watchNotesByMatch(userId, match, SearchLimits.NOTES) }
            ?: flowOf(emptyList())

    /**
     * The projects whose name match [query].
     */
    fun watchProjects(userId: String, query: TextMatchQuery): Flow<List<ProjectMatchRow>> =
        FtsMatchQuery.expression(query)
            ?.let { match -> watchProjectsByMatch(userId, match) }
            ?: flowOf(emptyList())

    /**
     * The tags whose name match [query].
     */
    fun watchTags(userId: String, query: TextMatchQuery): Flow<List<TagMatchRow>> =
        FtsMatchQuery.expression(query)
            ?.let { match -> watchTagsByMatch(userId, match) }
            ?: flowOf(emptyList())

    /**
     * The tasks whose title matches [query] — the first [limit], most recently
     * updated first.
     */
    suspend fun searchTaskTitles(userId: String, query: TextMatchQuery, limit: Int): List<TaskMatchRow> =
        FtsMatchQuery.expression(query)
            ?.let { match -> searchTaskTitlesByMatch(userId, match, limit) }
            ?: emptyList()

    /**
     * The notes whose title matches [query] — the first [limit], most recently
     * updated first.
     */
    suspend fun searchNoteTitles(userId: String, query: TextMatchQuery, limit: Int): List<NoteMatchRow> =
        FtsMatchQuery.expression(query)
            ?.let { match -> searchNoteTitlesByMatch(userId, match, limit) }
            ?: emptyList()
}
