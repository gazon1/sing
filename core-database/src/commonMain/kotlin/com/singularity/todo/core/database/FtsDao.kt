package com.singularity.todo.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
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
 * Most queries here answer with a row *and* the position of the match inside
 * it, read from FTS4's `offsets()`. The index knows where the word is — it is
 * the only one that does — and a search that shows the word in the result has
 * to be told where it is.
 *
 * The position travels as the raw answer of `offsets()`, a string of integers.
 * Reading it — turning bytes into the characters of the text — needs the text
 * of the column, and only the reader has it. See [FtsOffsets].
 *
 * ## One door answers without the position
 *
 * The door that offers a title for a link — [searchTaskTitles] — answers with
 * the tasks alone. The position of the match inside a title is not among the
 * things its readers read, and asking for it is not free: FTS4 can report the
 * position only while it is answering a `MATCH`, so a door that carries the
 * position through the ordering has to carry the whole matched row through it
 * as well. The measurement that decided this is in
 * `docs/architecture/fts-ordering-performance.md`.
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
    fun watchTasksByMatch(
        userId: String,
        match: String,
    ): Flow<List<TaskMatchRow>>

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
    fun watchNotesByMatch(
        userId: String,
        match: String,
        limit: Int,
    ): Flow<List<NoteMatchRow>>

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
    fun watchProjectsByMatch(
        userId: String,
        match: String,
    ): Flow<List<ProjectMatchRow>>

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
    fun watchTagsByMatch(
        userId: String,
        match: String,
    ): Flow<List<TagMatchRow>>

    /**
     * The ids of the tasks whose title matches the index — the first [limit], most
     * recently updated first.
     *
     * The ids alone, and not the tasks: the ordering of the result is decided
     * over every row the term matches, and a sorter that carries a whole task
     * row — and the position of the match inside it — through that ordering
     * pays for the row of every matched task, not only for the ones that
     * survive the limit. The rows of the survivors are read afterwards, by
     * their ids — [searchTasksByIds].
     *
     * The position of the match inside the title is not read here at all: the
     * callers of this door offer a title for a link, and the position is not
     * among the things they read. Asking the index for it is not free either —
     * FTS4 can only report it while it is answering a `MATCH`, so a door that
     * carries the position through the ordering carries the whole matched row
     * through it as well. The measurement that decided this is in
     * `docs/architecture/fts-ordering-performance.md`.
     *
     * The ordering carries a tiebreaker on purpose. Two tasks updated at the
     * same moment have no order between them, and the order SQLite's sorter
     * happened to give them was not a rule anybody could rely on — it was the
     * order of the bytes of the rows. The tiebreaker makes it a rule: among the
     * tasks updated at the same moment, the most recently inserted one comes
     * first.
     */
    @Query(
        """
        SELECT t.id FROM tasks t
        JOIN tasks_fts ON t.rowid = tasks_fts.docid
        WHERE tasks_fts MATCH :match
          AND t.user_id = :userId
          AND t.archived_at IS NULL
        ORDER BY t.updated_at DESC, t.rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun searchTaskTitleIdsByMatch(
        userId: String,
        match: String,
        limit: Int,
    ): List<String>

    /**
     * The tasks with the given ids.
     *
     * The order of the answer is not promised: a query with `IN` does not keep
     * the order of the list it was given. The caller that needs an order keeps
     * it itself.
     *
     * The ownership and the archive check are stated again: the ids were picked
     * a moment earlier, and a task that was archived in between must not be
     * offered.
     *
     * The check is written as a correlated one — a lookup of the same row by
     * its id — and not as a condition on the row itself. The flat form reads
     * the same rows and answers the same question, but the planner answers it
     * with `index_tasks_user_id`: it walks the rows of the user and checks the
     * id of every one of them. That is a scan of the user's tasks to read
     * twenty of them, and it grows with the database. In the correlated form
     * the primary key is the only condition the planner can find the rows by,
     * so it reads exactly the rows it was asked for — while the ownership and
     * the archive state are still checked in the database, on every row,
     * before the row is handed out.
     */
    @Query(
        """
        SELECT t.* FROM tasks t
        WHERE t.id IN (:ids)
          AND EXISTS (
            SELECT 1 FROM tasks o
            WHERE o.id = t.id
              AND o.user_id = :userId
              AND o.archived_at IS NULL
          )
        """,
    )
    suspend fun searchTasksByIds(
        userId: String,
        ids: List<String>,
    ): List<TaskEntity>

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
    suspend fun searchNoteTitlesByMatch(
        userId: String,
        match: String,
        limit: Int,
    ): List<NoteMatchRow>

    // ── The semantic surface ────────────────────────────────────────────────
    //
    // The methods above take an expression; the ones below take the query a
    // caller means and build the expression for it. A query with no searchable
    // term matches nothing — and saying so is not the same as matching
    // everything.

    /**
     * The tasks whose title or description match [query].
     */
    fun watchTasks(
        userId: String,
        query: TextMatchQuery,
    ): Flow<List<TaskMatchRow>> =
        FtsMatchQuery
            .expression(query)
            ?.let { match -> watchTasksByMatch(userId, match) }
            ?: flowOf(emptyList())

    /**
     * The notes whose title match [query].
     */
    fun watchNotes(
        userId: String,
        query: TextMatchQuery,
    ): Flow<List<NoteMatchRow>> =
        FtsMatchQuery
            .expression(query)
            ?.let { match -> watchNotesByMatch(userId, match, SearchLimits.NOTES) }
            ?: flowOf(emptyList())

    /**
     * The projects whose name match [query].
     */
    fun watchProjects(
        userId: String,
        query: TextMatchQuery,
    ): Flow<List<ProjectMatchRow>> =
        FtsMatchQuery
            .expression(query)
            ?.let { match -> watchProjectsByMatch(userId, match) }
            ?: flowOf(emptyList())

    /**
     * The tags whose name match [query].
     */
    fun watchTags(
        userId: String,
        query: TextMatchQuery,
    ): Flow<List<TagMatchRow>> =
        FtsMatchQuery
            .expression(query)
            ?.let { match -> watchTagsByMatch(userId, match) }
            ?: flowOf(emptyList())

    /**
     * The tasks whose title matches [query] — the first [limit], most recently
     * updated first.
     *
     * The door reads in two steps: first the ids of the matched tasks, in
     * order, then the rows of the ones that survived the limit. The sorter of
     * the first step carries an id — not a row, and not the position of the
     * match — and the second step reads the rows by their id.
     *
     * Both steps are one transaction, and that is what makes them one answer.
     * Two reads a moment apart are two answers: a task whose title was edited
     * in between could be offered even though it no longer matches, and a row
     * could be read in a state the order no longer describes. Inside a
     * transaction both reads see the same snapshot, so the offered rows are
     * exactly the rows the order was decided over.
     */
    @Transaction
    suspend fun searchTaskTitles(
        userId: String,
        query: TextMatchQuery,
        limit: Int,
    ): List<TaskEntity> {
        val match = FtsMatchQuery.expression(query) ?: return emptyList()
        val ids = searchTaskTitleIdsByMatch(userId, match, limit)
        if (ids.isEmpty()) return emptyList()
        val tasks = searchTasksByIds(userId, ids).associateBy { it.id }
        return ids.mapNotNull { tasks[it] }
    }

    /**
     * The notes whose title matches [query] — the first [limit], most recently
     * updated first.
     */
    suspend fun searchNoteTitles(
        userId: String,
        query: TextMatchQuery,
        limit: Int,
    ): List<NoteMatchRow> =
        FtsMatchQuery
            .expression(query)
            ?.let { match -> searchNoteTitlesByMatch(userId, match, limit) }
            ?: emptyList()
}
