package com.singularity.todo.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL ORDER BY due_date ASC, is_pinned DESC")
    fun watchActive(userId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun watchById(id: String): Flow<TaskEntity?>

    /**
     * Scoped read. [watchById] / [getById] filter by id alone, so a repository
     * using them can return another user's task; these are the user-scoped
     * counterparts every repository read path should use.
     */
    @Query("SELECT * FROM tasks WHERE id = :id AND user_id = :userId")
    fun watchByIdForUser(id: String, userId: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NOT NULL ORDER BY archived_at DESC")
    fun watchTrash(userId: String): Flow<List<TaskEntity>>

    /**
     * Suspend counterpart of [watchTrash], for one-shot reads such as computing
     * which rows a bulk archive just changed (so they can be pushed to sync).
     */
    @Query("SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NOT NULL ORDER BY archived_at DESC")
    suspend fun getTrashForUser(userId: String): List<TaskEntity>

    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND someday = 1 ORDER BY created_at DESC",
    )
    fun watchSomeday(userId: String): Flow<List<TaskEntity>>

    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND due_date = :date ORDER BY is_pinned DESC, due_time ASC",
    )
    fun watchByDate(userId: String, date: String): Flow<List<TaskEntity>>

    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND due_date > :today AND due_date <= :endDate ORDER BY due_date ASC, is_pinned DESC",
    )
    fun watchUpcoming(userId: String, today: String, endDate: String): Flow<List<TaskEntity>>

    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND project_id = :projectId ORDER BY is_pinned DESC, due_date ASC",
    )
    fun watchByProject(userId: String, projectId: String): Flow<List<TaskEntity>>

    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND due_date >= :from AND due_date <= :to ORDER BY due_date ASC, is_pinned DESC",
    )
    fun watchByDateRange(userId: String, from: String, to: String): Flow<List<TaskEntity>>

    @Query(
        "SELECT DISTINCT t.* FROM tasks t INNER JOIN task_tags tt ON t.id = tt.task_id WHERE t.user_id = :userId AND t.archived_at IS NULL AND tt.tag_id = :tagId ORDER BY t.due_date ASC, t.is_pinned DESC",
    )
    fun watchByTag(userId: String, tagId: String): Flow<List<TaskEntity>>

    /**
     * Returns tasks tagged with **any** of the given [tagIds].
     * Uses Room's `IN (:list)` binding — pass `List<String>`, not `Set`.
     */
    @Query(
        "SELECT DISTINCT t.* FROM tasks t INNER JOIN task_tags tt ON t.id = tt.task_id WHERE t.user_id = :userId AND t.archived_at IS NULL AND tt.tag_id IN (:tagIds) ORDER BY t.due_date ASC, t.is_pinned DESC",
    )
    fun watchByAnyTag(userId: String, tagIds: List<String>): Flow<List<TaskEntity>>

    /**
     * Returns tasks tagged with **all** of the given [tagIds].
     * Groups by task id and requires exactly [size] distinct tag matches (one row per tag per task via the INNER JOIN).
     * Uses Room's `IN (:list)` binding — pass `List<String>`, not `Set`.
     */
    @Query(
        "SELECT t.* FROM tasks t INNER JOIN task_tags tt ON t.id = tt.task_id WHERE t.user_id = :userId AND t.archived_at IS NULL AND tt.tag_id IN (:tagIds) GROUP BY t.id HAVING COUNT(DISTINCT tt.tag_id) = :size ORDER BY t.due_date ASC, t.is_pinned DESC",
    )
    fun watchByAllTags(userId: String, tagIds: List<String>, size: Int): Flow<List<TaskEntity>>

    /**
     * Returns tasks whose priority is in the given [priorities] set.
     * Uses Room's `IN (:list)` binding — pass `List<String>`, not `Set`.
     */
    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND priority IN (:priorities) ORDER BY due_date ASC, is_pinned DESC",
    )
    fun watchByPriorities(userId: String, priorities: List<String>): Flow<List<TaskEntity>>

    /**
     * Returns tasks whose title contains [pattern] (case-insensitive substring match).
     * Uses SQLite `LIKE` with `'%' || :pattern || '%'` — no regular expression needed.
     * [pattern] is passed as a raw string and bound via Room's parameter binding.
     */
    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND lower(title) LIKE lower('%' || :pattern || '%') ORDER BY due_date ASC, is_pinned DESC",
    )
    fun watchByRegexp(userId: String, pattern: String): Flow<List<TaskEntity>>

    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND is_pinned = 1 ORDER BY is_pinned DESC, due_date ASC",
    )
    fun watchPinned(userId: String): Flow<List<TaskEntity>>

    @Query("UPDATE tasks SET is_pinned = :pinned, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun setPinnedForUser(id: String, pinned: Boolean, ts: Long, userId: String): Int

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    /** Scoped counterpart of [getById] — see [watchByIdForUser]. */
    @Query("SELECT * FROM tasks WHERE id = :id AND user_id = :userId")
    suspend fun getByIdForUser(id: String, userId: String): TaskEntity?

    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND (title LIKE '%' || :q || '%' OR description LIKE '%' || :q || '%') ORDER BY due_date ASC, is_pinned DESC",
    )
    fun watchSearchResults(userId: String, q: String): Flow<List<TaskEntity>>

    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND title LIKE '%' || :q || '%' ORDER BY updated_at DESC LIMIT 20",
    )
    suspend fun searchTitles(userId: String, q: String): List<TaskEntity>

    @Upsert
    suspend fun upsert(task: TaskEntity)

    // ── Ownership-scoped mutations ────────────────────────────────────────────
    //
    // Every mutation below takes a `userId` and returns the affected row count.
    // That `Int` is the enforcement signal: `0` means "no such row for this user"
    // and callers must treat the write as failed. This is the layer that cannot
    // be bypassed — `assertCanWrite` only covers methods that carry an entity,
    // and id-only methods have no userId to check.
    //
    // See docs/decisions/2026-09-27-write-layer-soundness.md.

    @Query("UPDATE tasks SET archived_at = :ts, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int

    @Query("UPDATE tasks SET archived_at = NULL, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun restoreForUser(id: String, ts: Long, userId: String): Int

    @Query("UPDATE tasks SET completed_at = :ts, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun markCompleteForUser(id: String, ts: Long, userId: String): Int

    @Query("UPDATE tasks SET completed_at = NULL, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun markIncompleteForUser(id: String, ts: Long, userId: String): Int

    /**
     * Inserts a tag cross-ref only when the owning task belongs to [userId].
     *
     * Returns `Unit` because Room only permits `Unit`/`Long` from INSERT
     * queries, and a rejected insert reports `-1` rather than a row count. The
     * `WHERE EXISTS` clause is the actual enforcement: for a foreign task
     * nothing is written, silently. Callers that need to surface that failure
     * must check task ownership first.
     *
     * The unscoped [upsertTagCrossRef] is kept solely for the backup-restore
     * path, which imports rows belonging to arbitrary users.
     */
    @Query(
        """
        INSERT OR REPLACE INTO task_tags (task_id, tag_id)
        SELECT :taskId, :tagId
        WHERE EXISTS (SELECT 1 FROM tasks WHERE id = :taskId AND user_id = :userId)
        """,
    )
    suspend fun upsertTagCrossRefForUser(taskId: String, tagId: String, userId: String)

    /** Unscoped variant — backup-restore path only. See [upsertTagCrossRefForUser]. */
    @Upsert
    suspend fun upsertTagCrossRef(ref: TaskTagCrossRef)

    @Query(
        """
        DELETE FROM task_tags
        WHERE task_id = :taskId AND tag_id = :tagId
        AND EXISTS (SELECT 1 FROM tasks WHERE id = :taskId AND user_id = :userId)
        """,
    )
    suspend fun removeTagRefForUser(taskId: String, tagId: String, userId: String): Int

    @Query("SELECT tag_id FROM task_tags WHERE task_id = :taskId")
    fun getTagIdsForTask(taskId: String): Flow<List<String>>

    /** Scoped to the owning task's user; the cross-ref table has no `user_id`. */
    @Query(
        """
        SELECT tag_id FROM task_tags
        WHERE task_id = :taskId
        AND task_id IN (SELECT id FROM tasks WHERE user_id = :userId)
        """,
    )
    fun getTagIdsForUser(taskId: String, userId: String): Flow<List<String>>

    // ── Task dependencies ─────────────────────────────────────────────────────

    @Query("SELECT depends_on_task_id FROM task_dependencies WHERE task_id = :taskId")
    fun getDependencyIdsForTask(taskId: String): Flow<List<String>>

    /** Scoped to the owning task's user; the cross-ref table has no `user_id`. */
    @Query(
        """
        SELECT depends_on_task_id FROM task_dependencies
        WHERE task_id = :taskId
        AND task_id IN (SELECT id FROM tasks WHERE user_id = :userId)
        """,
    )
    fun getDependencyIdsForUser(taskId: String, userId: String): Flow<List<String>>

    @Query("SELECT task_id FROM task_dependencies WHERE depends_on_task_id = :taskId")
    fun getBlockingTaskIdsForTask(taskId: String): Flow<List<String>>

    /**
     * Scoped variant of [getBlockingTaskIdsForTask] — returns only task_ids owned by [userId].
     */
    @Query(
        """
        SELECT td.task_id FROM task_dependencies td
        WHERE td.depends_on_task_id = :taskId
        AND td.task_id IN (SELECT id FROM tasks WHERE user_id = :userId)
        """,
    )
    fun getBlockingTaskIdsForUser(taskId: String, userId: String): Flow<List<String>>

    /**
     * Inserts a dependency cross-ref only when the owning task belongs to [userId].
     * Returns `Unit` — see [upsertTagCrossRefForUser] for why the rejected case
     * cannot report a count. The unscoped [upsertDependency] is kept solely for
     * the backup-restore path.
     *
     * Uses `verb = 'BLOCKS'` as the default for backward compatibility.
     */
    @Query(
        """
        INSERT OR REPLACE INTO task_dependencies (task_id, depends_on_task_id, verb)
        SELECT :taskId, :depId, :verb
        WHERE EXISTS (SELECT 1 FROM tasks WHERE id = :taskId AND user_id = :userId)
        """,
    )
    suspend fun upsertDependencyForUser(taskId: String, depId: String, verb: String, userId: String)

    /** Unscoped variant — backup-restore path only. See [upsertDependencyForUser]. */
    @Upsert
    suspend fun upsertDependency(ref: TaskDependencyCrossRef)

    @Query(
        """
        DELETE FROM task_dependencies
        WHERE task_id = :taskId AND depends_on_task_id = :depId
        AND EXISTS (SELECT 1 FROM tasks WHERE id = :taskId AND user_id = :userId)
        """,
    )
    suspend fun removeDependencyForUser(taskId: String, depId: String, userId: String): Int

    /**
     * Removes a specific verb edge between [taskId] and [depId].
     */
    @Query(
        """
        DELETE FROM task_dependencies
        WHERE task_id = :taskId AND depends_on_task_id = :depId AND verb = :verb
        AND EXISTS (SELECT 1 FROM tasks WHERE id = :taskId AND user_id = :userId)
        """,
    )
    suspend fun removeDependencyForVerb(taskId: String, depId: String, verb: String, userId: String): Int

    /**
     * Returns all dependency edges for [taskId] including their verb, scoped to [userId].
     */
    @Query(
        """
        SELECT td.task_id, td.depends_on_task_id, td.verb FROM task_dependencies td
        WHERE td.task_id = :taskId
        AND td.task_id IN (SELECT id FROM tasks WHERE user_id = :userId)
        """,
    )
    fun observeTypedDependenciesForUser(taskId: String, userId: String): Flow<List<TaskDependencyCrossRef>>

    @Query(
        """
        DELETE FROM task_dependencies
        WHERE task_id = :taskId
        AND EXISTS (SELECT 1 FROM tasks WHERE id = :taskId AND user_id = :userId)
        """,
    )
    suspend fun clearDependenciesForUser(taskId: String, userId: String): Int

    // ── Outgoing links ─────────────────────────────────────────────────────────

    @Query("UPDATE tasks SET outgoing_links = :linksJson, updated_at = :updatedAt WHERE id = :id AND user_id = :userId")
    suspend fun setOutgoingLinksForUser(id: String, linksJson: String, updatedAt: Long, userId: String): Int

    /**
     * Returns tasks that link TO [taskId] via `task://<id>` URL scheme, for the current user.
     * Uses a LIKE substring match on the JSON-encoded `outgoing_links` column.
     */
    @Query(
        """
        SELECT * FROM tasks
        WHERE user_id = :userId
        AND archived_at IS NULL
        AND outgoing_links LIKE '%task://' || :taskId || '%'
        LIMIT 20
        """,
    )
    suspend fun getBacklinkTasks(taskId: String, userId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE user_id = :userId")
    suspend fun listAllForUser(userId: String): List<TaskEntity>

    // ── Owner-scoped erase ──────────────────────────────────────────────────────
    //
    // See `OwnerEraseDao` — the deletes live there, grouped by operation rather than
    // scattered across the eight DAOs that own the tables. They have an order that is
    // load-bearing, and an order is easier to keep correct in one place.
    //
    // See ADR 2026-10-06-a-profile-is-owned-and-an-erase-resolves-its-ids-first.

    // ── Backup symmetry (restore symmetry for export) ──────────────────────────

    @Query("SELECT * FROM task_dependencies WHERE task_id IN (SELECT id FROM tasks WHERE user_id = :userId)")
    suspend fun listAllDependenciesForUser(userId: String): List<TaskDependencyCrossRef>

    @Query("SELECT * FROM task_tags WHERE task_id IN (SELECT id FROM tasks WHERE user_id = :userId)")
    suspend fun listAllTagsForUser(userId: String): List<TaskTagCrossRef>

    /**
     * Archives completed tasks **for one user only**.
     *
     * The previous unscoped form swept every user's completed tasks in a single
     * UPDATE, which is a cross-user write. Kept user-scoped by construction.
     */
    @Query(
        """
        UPDATE tasks SET archived_at = :ts, updated_at = :ts
        WHERE user_id = :userId AND completed_at IS NOT NULL AND archived_at IS NULL
        """,
    )
    suspend fun archiveCompletedForUser(ts: Long, userId: String): Int

    /**
     * Non-suspend Flow of all tag cross-references for the given [userId].
     * Room emits a new collection whenever any row changes.
     */
    @Query("SELECT * FROM task_tags WHERE task_id IN (SELECT id FROM tasks WHERE user_id = :userId)")
    fun observeTagCrossRefs(userId: String): Flow<List<TaskTagCrossRef>>

    /**
     * Non-suspend Flow of all dependency cross-references for the given [userId].
     * Room emits a new collection whenever any row changes.
     */
    @Query("SELECT * FROM task_dependencies WHERE task_id IN (SELECT id FROM tasks WHERE user_id = :userId)")
    fun observeDependencyCrossRefs(userId: String): Flow<List<TaskDependencyCrossRef>>
}

@Dao
interface NoteDao {
    @Query(
        "SELECT * FROM notes WHERE user_id = :userId AND archived_at IS NULL AND deleted_at IS NULL ORDER BY is_pinned DESC, sort_order ASC, updated_at DESC",
    )
    fun watchAll(userId: String): Flow<List<NoteEntity>>

    @Query(
        "SELECT * FROM notes WHERE user_id = :userId AND is_pinned = 1 AND archived_at IS NULL AND deleted_at IS NULL ORDER BY pinned_at DESC",
    )
    fun watchPinned(userId: String): Flow<List<NoteEntity>>

    @Query(
        "SELECT * FROM notes WHERE user_id = :userId AND archived_at IS NOT NULL AND deleted_at IS NULL ORDER BY archived_at DESC",
    )
    fun watchArchived(userId: String): Flow<List<NoteEntity>>

    @Query(
        "SELECT * FROM notes WHERE user_id = :userId AND parent_note_id IS NULL AND is_folder = 0 AND archived_at IS NULL AND deleted_at IS NULL ORDER BY sort_order ASC, updated_at DESC",
    )
    fun watchRootNotes(userId: String): Flow<List<NoteEntity>>

    /**
     * Observe direct child notes of a folder, scoped to [userId].
     *
     * The caller is responsible for ensuring [parentId] itself belongs to [userId];
     * this query only filters the returned children.
     */
    @Query(
        "SELECT * FROM notes WHERE parent_note_id = :parentId AND user_id = :userId AND archived_at IS NULL AND deleted_at IS NULL ORDER BY sort_order ASC, title ASC",
    )
    fun watchChildrenForUser(parentId: String, userId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id AND user_id = :userId")
    fun watchByIdForUser(id: String, userId: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id AND user_id = :userId")
    suspend fun getByIdForUser(id: String, userId: String): NoteEntity?

    @Query(
        "SELECT * FROM notes WHERE user_id = :userId AND archived_at IS NULL AND deleted_at IS NULL AND title LIKE '%' || :q || '%' ORDER BY updated_at DESC LIMIT 20",
    )
    fun watchSearchByTitle(userId: String, q: String): Flow<List<NoteEntity>>

    @Query(
        "SELECT * FROM notes WHERE user_id = :userId AND archived_at IS NULL AND deleted_at IS NULL AND title LIKE '%' || :q || '%' ORDER BY updated_at DESC LIMIT 20",
    )
    suspend fun searchByTitle(userId: String, q: String): List<NoteEntity>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    // ── Ownership-scoped mutations ────────────────────────────────────────────
    //
    // Same contract as TaskDao: every mutation takes a `userId` and returns the
    // affected row count, so `0` is a failed write rather than a silent success.
    // See docs/decisions/2026-09-27-write-layer-soundness.md.

    /** Atomic update — does NOT require a prior read. */
    @Query(
        "UPDATE notes SET title = :title, body_markdown = :markdown, body_html = :html, word_count = :wordCount, char_count = :charCount, updated_at = :updatedAt WHERE id = :id AND user_id = :userId",
    )
    suspend fun updateContentForUser(
        id: String,
        title: String,
        markdown: String,
        html: String,
        wordCount: Int,
        charCount: Int,
        updatedAt: Long,
        userId: String,
    ): Int

    @Query(
        "UPDATE notes SET is_pinned = :pinned, pinned_at = :pinnedAt, updated_at = :ts WHERE id = :id AND user_id = :userId",
    )
    suspend fun setPinnedForUser(id: String, pinned: Boolean, pinnedAt: Long?, ts: Long, userId: String): Int

    @Query("UPDATE notes SET archived_at = :ts, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun archiveForUser(id: String, ts: Long, userId: String): Int

    @Query("UPDATE notes SET archived_at = NULL, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun unarchiveForUser(id: String, ts: Long, userId: String): Int

    @Query("UPDATE notes SET color = :color, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun setColorForUser(id: String, color: Int?, ts: Long, userId: String): Int

    @Query("UPDATE notes SET sort_order = :sortOrder, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun setSortOrderForUser(id: String, sortOrder: Int, ts: Long, userId: String): Int

    @Query("UPDATE notes SET deleted_at = :ts, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int

    @Query("UPDATE notes SET deleted_at = NULL, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun restoreForUser(id: String, ts: Long, userId: String): Int

    @Query("SELECT * FROM notes WHERE user_id = :userId")
    suspend fun listAllForUser(userId: String): List<NoteEntity>

    @Query("UPDATE notes SET outgoing_links = :linksJson, updated_at = :updatedAt WHERE id = :id AND user_id = :userId")
    suspend fun setOutgoingLinksForUser(id: String, linksJson: String, updatedAt: Long, userId: String): Int

    /** Notes that link TO the given noteId via note:// URL scheme, for the current user only. */
    @Query(
        """
        SELECT * FROM notes
        WHERE user_id = :userId
        AND deleted_at IS NULL
        AND outgoing_links LIKE '%note://' || :noteId || '%'
        LIMIT 20
    """,
    )
    suspend fun getBacklinkNotes(noteId: String, userId: String): List<NoteEntity>

    /**
     * Returns notes attached to the given taskId (notes.task_id = :taskId), for the current user.
     * Uses the indexed [task_id] column for O(log n) lookups.
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE task_id = :taskId
          AND user_id = :userId
          AND deleted_at IS NULL
        ORDER BY created_at DESC
        LIMIT 50
        """,
    )
    fun watchByTaskForUser(taskId: String, userId: String): Flow<List<NoteEntity>>

    /**
     * Returns notes that link TO the given taskId via `task://<id>` URL scheme, for the current user.
     * [watchByTaskForUser] should be preferred for new code — this method is kept for backward
     * compatibility with existing wikilink-only notes.
     *
     * @deprecated Use [watchByTaskForUser] which uses the indexed task_id column.
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE user_id = :userId
        AND deleted_at IS NULL
        AND outgoing_links LIKE '%task://' || :taskId || '%'
        LIMIT 20
        """,
    )
    suspend fun getNotesLinkingToTask(taskId: String, userId: String): List<NoteEntity>

    // ── Templates and daily notes ───────────────────────────────────────────────

    /**
     * All templates (kind = TEMPLATE) for the user, ordered by title.
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE user_id = :userId
        AND kind = 'Template'
        AND deleted_at IS NULL
        ORDER BY title ASC
        """,
    )
    fun watchTemplates(userId: String): Flow<List<NoteEntity>>

    /**
     * Daily note for a specific date.
     * Daily notes are identified by kind = DAILY and title matching the ISO date string.
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE user_id = :userId
        AND kind = 'Daily'
        AND title = :dateKey
        AND deleted_at IS NULL
        LIMIT 1
        """,
    )
    suspend fun getDailyNote(userId: String, dateKey: String): NoteEntity?

    /**
     * Daily notes for a month (for calendar navigation).
     * Matches notes where title is a date string between from..to.
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE user_id = :userId
        AND kind = 'Daily'
        AND title >= :from
        AND title <= :to
        AND deleted_at IS NULL
        ORDER BY title ASC
        """,
    )
    fun watchDailyNotesInRange(userId: String, from: String, to: String): Flow<List<NoteEntity>>

    /**
     * Update the kind of a note.
     */
    @Query("UPDATE notes SET kind = :kind, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun setKindForUser(id: String, kind: String, ts: Long, userId: String): Int
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE user_id = :userId AND is_deleted = 0 ORDER BY sort_order ASC, name ASC")
    fun watchAll(userId: String): Flow<List<ProjectEntity>>

    // ─── UserId-scoped reads (Phase 2.8 fix) ──────────────────────────────────
    @Query("SELECT * FROM projects WHERE id = :id AND user_id = :userId")
    fun watchByIdForUser(id: String, userId: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id AND user_id = :userId")
    suspend fun getByIdForUser(id: String, userId: String): ProjectEntity?

    @Query(
        "SELECT * FROM projects WHERE parent_id = :parentId AND user_id = :userId AND is_deleted = 0 ORDER BY sort_order ASC, name ASC",
    )
    fun watchByParentForUser(parentId: String, userId: String): Flow<List<ProjectEntity>>

    @Query(
        """
        SELECT p.*,
               COUNT(t.id) AS total_count,
               SUM(CASE WHEN t.completed_at IS NOT NULL THEN 1 ELSE 0 END) AS completed_count
        FROM projects p
        LEFT JOIN tasks t ON t.project_id = p.id AND t.archived_at IS NULL
        WHERE p.user_id = :userId AND p.is_deleted = 0
        GROUP BY p.id
        ORDER BY p.sort_order ASC, p.name ASC
    """,
    )
    fun watchAllWithCounts(userId: String): Flow<List<ProjectWithCountRow>>

    @Query("UPDATE projects SET parent_id = :parentId, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun setParentForUser(id: String, parentId: String?, ts: Long, userId: String): Int

    @Query("UPDATE projects SET sort_order = :sortOrder, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun setSortOrderForUser(id: String, sortOrder: Int, ts: Long, userId: String): Int

    @Query(
        "UPDATE projects SET is_deleted = 0, deleted_at = NULL, updated_at = :ts WHERE id = :id AND user_id = :userId",
    )
    suspend fun restoreForUser(id: String, ts: Long, userId: String): Int

    @Query("SELECT * FROM projects WHERE idempotency_key = :key AND user_id = :userId LIMIT 1")
    suspend fun findByIdempotencyKeyForUser(key: String, userId: String): ProjectEntity?

    /**
     * Bumps `updated_at` without touching any column of the caller's choosing.
     *
     * For the changes that live outside `projects` but belong to the project
     * document — currently only its inherited tag groups, which are a join table.
     * Every other narrow update here stamps `updated_at` as part of its SET clause;
     * this is the same statement for a change whose columns are not on this table.
     *
     * Without it, two different inheritance states share one `updated_at`, and the
     * server's last-write-wins on that field cannot order them.
     */
    @Query("UPDATE projects SET updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun touchUpdatedAtForUser(id: String, ts: Long, userId: String): Int

    /** Case-insensitive lookup by name, used to resolve project names in query conditions. */
    @Query("SELECT * FROM projects WHERE user_id = :userId AND is_deleted = 0 AND lower(name) = lower(:name) LIMIT 1")
    suspend fun findByNameForUser(userId: String, name: String): ProjectEntity?

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Query(
        "UPDATE projects SET is_deleted = 1, deleted_at = :ts, updated_at = :ts WHERE id = :id AND user_id = :userId",
    )
    suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int

    @Query("SELECT * FROM projects WHERE user_id = :userId")
    suspend fun listAllForUser(userId: String): List<ProjectEntity>
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags WHERE user_id = :userId AND deleted_at IS NULL ORDER BY sort_order ASC, name ASC")
    fun watchAll(userId: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id")
    fun watchById(id: String): Flow<TagEntity?>

    @Query("SELECT * FROM tags WHERE id = :id AND user_id = :userId AND deleted_at IS NULL")
    fun watchByIdForUser(id: String, userId: String): Flow<TagEntity?>

    @Query("SELECT * FROM tags WHERE id = :id AND user_id = :userId AND deleted_at IS NULL")
    suspend fun getByIdForUser(id: String, userId: String): TagEntity?

    /** Case-insensitive lookup by name, used to resolve tag names in query conditions. */
    @Query("SELECT * FROM tags WHERE user_id = :userId AND deleted_at IS NULL AND lower(name) = lower(:name) LIMIT 1")
    suspend fun findByNameForUser(userId: String, name: String): TagEntity?

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Query("UPDATE tags SET deleted_at = :ts, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int

    @Query("SELECT * FROM tags WHERE user_id = :userId")
    suspend fun listAllForUser(userId: String): List<TagEntity>

    /**
     * Detaches every live tag from a group being soft-deleted.
     *
     * Without this the member tags keep a `group_id` pointing at a row the user can
     * no longer see, and the tag renders as a member of a group that does not exist.
     * Scoped to the owner; returns the number of tags released.
     */
    @Query(
        """
        UPDATE tags SET group_id = NULL, updated_at = :ts
        WHERE group_id = :groupId AND user_id = :userId AND deleted_at IS NULL
        """,
    )
    suspend fun clearGroupForUser(groupId: String, ts: Long, userId: String): Int

    /**
     * Live members of [groupId] for [userId], read before [clearGroupForUser] so the
     * release can be pushed to sync — the tag rows changed, so the server must see it.
     */
    @Query("SELECT * FROM tags WHERE group_id = :groupId AND user_id = :userId AND deleted_at IS NULL")
    suspend fun listByGroupForUser(groupId: String, userId: String): List<TagEntity>
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM task_reminders WHERE user_id = :userId ORDER BY fire_at ASC")
    fun watchAll(userId: String): Flow<List<TaskReminderEntity>>

    /**
     * Every reminder, across every profile.
     *
     * The only query in this table with **no** `user_id` filter, and the only deliberate
     * hole in profile isolation it has. It exists for one caller: re-arming the OS alarms
     * at boot or launch, which is a device-wide operation and not a per-profile one.
     *
     * The alternative was wrong in a way that is hard to see. Arming from `watchAll`
     * (profile-scoped) means a reminder for a profile that is not active is never armed at
     * all — it fires only when its owner switches to that profile and relaunches the app.
     * The user set a reminder, the row exists, and nothing reminds them.
     *
     * Reads only. Nothing writes through this, and `ReminderRepository` exposes it under a
     * name that says what it is — see `CrossProfileReadRegistry`.
     */
    @Query("SELECT * FROM task_reminders ORDER BY fire_at ASC")
    fun watchAllProfiles(): Flow<List<TaskReminderEntity>>

    @Query("SELECT * FROM task_reminders WHERE task_id = :taskId AND user_id = :userId ORDER BY fire_at ASC")
    fun watchByTask(taskId: String, userId: String): Flow<List<TaskReminderEntity>>

    @Query("SELECT * FROM task_reminders WHERE fire_at <= :now AND user_id = :userId ORDER BY fire_at ASC")
    fun getDueBefore(now: Long, userId: String): Flow<List<TaskReminderEntity>>

    @Query(
        "SELECT * FROM task_reminders WHERE fire_at <= :now AND user_id = :userId ORDER BY fire_at DESC LIMIT :limit",
    )
    fun getRecentDueBefore(now: Long, userId: String, limit: Int): Flow<List<TaskReminderEntity>>

    @Upsert
    suspend fun upsert(reminder: TaskReminderEntity)

    @Query("DELETE FROM task_reminders WHERE id = :id AND user_id = :userId")
    suspend fun delete(id: String, userId: String)

    @Query("DELETE FROM task_reminders WHERE task_id = :taskId AND user_id = :userId")
    suspend fun deleteByTask(taskId: String, userId: String)

    @Query("SELECT * FROM task_reminders WHERE id = :id AND user_id = :userId")
    suspend fun getById(id: String, userId: String): TaskReminderEntity?

    @Query("SELECT * FROM task_reminders WHERE id = :id AND user_id = :userId")
    fun watchByIdForUser(id: String, userId: String): Flow<TaskReminderEntity?>

    @Query("SELECT DISTINCT task_id FROM task_reminders WHERE recurring_pattern IS NOT NULL AND user_id = :userId")
    fun watchRecurringTaskIds(userId: String): Flow<List<String>>

    @Query(
        "UPDATE task_reminders SET last_fired_at = :lastFiredAt, updated_at = :updatedAt WHERE id = :id AND user_id = :userId",
    )
    suspend fun setLastFiredAt(id: String, userId: String, lastFiredAt: Long, updatedAt: Long)
}

@Dao
interface ProjectReminderDao {
    @Query("SELECT * FROM project_reminders WHERE user_id = :userId ORDER BY fire_at ASC")
    fun watchAll(userId: String): Flow<List<ProjectReminderEntity>>

    @Query("SELECT * FROM project_reminders WHERE project_id = :projectId AND user_id = :userId ORDER BY fire_at ASC")
    fun watchByProject(projectId: String, userId: String): Flow<List<ProjectReminderEntity>>

    @Query("SELECT * FROM project_reminders WHERE fire_at <= :now AND user_id = :userId ORDER BY fire_at ASC")
    fun getDueBefore(now: Long, userId: String): Flow<List<ProjectReminderEntity>>

    @Query(
        "SELECT * FROM project_reminders WHERE fire_at <= :now AND user_id = :userId ORDER BY fire_at DESC LIMIT :limit",
    )
    fun getRecentDueBefore(now: Long, userId: String, limit: Int): Flow<List<ProjectReminderEntity>>

    @Upsert
    suspend fun upsert(reminder: ProjectReminderEntity)

    @Query("DELETE FROM project_reminders WHERE id = :id AND user_id = :userId")
    suspend fun delete(id: String, userId: String)

    @Query("DELETE FROM project_reminders WHERE project_id = :projectId AND user_id = :userId")
    suspend fun deleteByProject(projectId: String, userId: String)

    @Query("SELECT * FROM project_reminders WHERE id = :id AND user_id = :userId")
    suspend fun getById(id: String, userId: String): ProjectReminderEntity?

    @Query("SELECT * FROM project_reminders WHERE id = :id AND user_id = :userId")
    fun watchByIdForUser(id: String, userId: String): Flow<ProjectReminderEntity?>

    @Query(
        "UPDATE project_reminders SET last_fired_at = :lastFiredAt, updated_at = :updatedAt WHERE id = :id AND user_id = :userId",
    )
    suspend fun setLastFiredAt(id: String, userId: String, lastFiredAt: Long, updatedAt: Long)
}

@Dao
interface ChecklistDao {
    @Query("SELECT * FROM checklist_items WHERE task_id = :taskId ORDER BY sort_order ASC")
    fun watchByTask(taskId: String): Flow<List<ChecklistItemEntity>>

    @Upsert
    suspend fun upsert(item: ChecklistItemEntity)

    /**
     * Toggles the completion status of a checklist item, scoped to the user.
     *
     * Writes `checked_by` (the actor), `checked_at` (epoch millis), and increments
     * `row_version` on every toggle. Uses a targeted UPDATE rather than
     * read-reconstruct-write to preserve any additional columns.
     *
     * @return the number of rows updated (0 if the item was not found or already had
     * the target state).
     */
    @Query(
        """
        UPDATE checklist_items
        SET is_completed = :isCompleted,
            updated_at = :updatedAt,
            checked_by = :actor,
            checked_at = :checkedAt,
            row_version = row_version + 1
        WHERE id = :itemId
          AND task_id IN (SELECT id FROM tasks WHERE user_id = :userId)
        """,
    )
    suspend fun toggleItem(
        itemId: String,
        isCompleted: Boolean,
        updatedAt: Long,
        checkedAt: Long,
        actor: String,
        userId: String,
    ): Int

    @Query(
        """
        UPDATE checklist_items
        SET is_completed = :isCompleted, updated_at = :updatedAt
        WHERE id = :itemId
          AND task_id IN (SELECT id FROM tasks WHERE user_id = :userId)
        """,
    )
    suspend fun updateCompletionStatus(itemId: String, isCompleted: Boolean, updatedAt: Long, userId: String): Int

    @Query(
        """
        DELETE FROM checklist_items
        WHERE id = :id
        AND task_id IN (SELECT id FROM tasks WHERE user_id = :userId)
        """,
    )
    suspend fun deleteForUser(id: String, userId: String): Int

    @Query(
        """
        DELETE FROM checklist_items
        WHERE task_id = :taskId
        AND EXISTS (SELECT 1 FROM tasks WHERE id = :taskId AND user_id = :userId)
        """,
    )
    suspend fun deleteByTaskForUser(taskId: String, userId: String): Int
}

@Dao
interface LlmUsageDao {
    @Upsert
    suspend fun upsert(entity: LlmUsageEntity)

    @Query("SELECT * FROM llm_usage WHERE profile_id = :profileId ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(profileId: String, limit: Int): Flow<List<LlmUsageEntity>>

    @Query(
        """
        SELECT date(created_at / 1000, 'unixepoch') as date,
               SUM(total_tokens) as totalTokens,
               SUM(cost_usd_micros) as totalCostMicros,
               COUNT(*) as callCount
        FROM llm_usage
        WHERE profile_id = :profileId
          AND created_at >= :sinceEpochMs
        GROUP BY date(created_at / 1000, 'unixepoch')
        ORDER BY date DESC
    """,
    )
    fun observeByDay(profileId: String, sinceEpochMs: Long): Flow<List<DailyUsageRow>>

    @Query(
        """
        SELECT tool_name as toolName,
               SUM(total_tokens) as totalTokens,
               SUM(cost_usd_micros) as totalCostMicros,
               COUNT(*) as callCount
        FROM llm_usage
        WHERE profile_id = :profileId
        GROUP BY tool_name
        ORDER BY SUM(total_tokens) DESC
    """,
    )
    fun observeByTool(profileId: String): Flow<List<ToolUsageRow>>

    @Query(
        """
        SELECT model_id as modelId,
               SUM(total_tokens) as totalTokens,
               SUM(cost_usd_micros) as totalCostMicros,
               COUNT(*) as callCount
        FROM llm_usage
        WHERE profile_id = :profileId
        GROUP BY model_id
        ORDER BY SUM(total_tokens) DESC
    """,
    )
    fun observeByModel(profileId: String): Flow<List<ModelUsageRow>>

    @Query("DELETE FROM llm_usage WHERE created_at < :cutoffEpochMs")
    suspend fun pruneOlderThan(cutoffEpochMs: Long)
}

/** Row type returned by observeByDay */
data class DailyUsageRow(val date: String, val totalTokens: Long, val totalCostMicros: Long?, val callCount: Long)

/** Row type returned by observeByTool */
data class ToolUsageRow(val toolName: String, val totalTokens: Long, val totalCostMicros: Long?, val callCount: Long)

/** Row type returned by observeByModel */
data class ModelUsageRow(val modelId: String, val totalTokens: Long, val totalCostMicros: Long?, val callCount: Long)

// ─── Profile DAO ───────────────────────────────────────────────────────────────

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY created_at ASC")
    fun all(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE is_default = 1 LIMIT 1")
    suspend fun getDefault(): ProfileEntity?

    @Query("SELECT name FROM profiles")
    suspend fun allNames(): List<String>

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    /**
     * Profiles owned by [userId], oldest first.
     *
     * The read half of an owner-scoped erase. It is a separate call from the deletes on
     * purpose: resolution is reversible and deletable is not, so the ids are resolved,
     * checked, and only then acted on.
     *
     * Profiles with a NULL `user_id` are excluded — they belong to nobody, so nobody's
     * erase takes them. See `Migration38To39` for why that state is normal rather than
     * a gap to be filled in.
     */
    @Query("SELECT * FROM profiles WHERE user_id = :userId ORDER BY created_at ASC")
    suspend fun listOwnedBy(userId: String): List<ProfileEntity>

    /**
     * Claims every unowned profile for [userId].
     *
     * Used when an account signs in over data created before it existed: the rows are
     * the user's, and the profile they live in has no owner yet. Claims NULL-owner rows
     * only — a profile already owned by someone else is never taken, so this cannot
     * reassign an account's profile to whoever signs in next.
     */
    @Query("UPDATE profiles SET user_id = :userId WHERE user_id IS NULL")
    suspend fun claimUnowned(userId: String): Int
}

// ─── Tag Group DAO ─────────────────────────────────────────────────────────────

@Dao
interface TagGroupDao {
    @Query("SELECT * FROM tag_groups WHERE user_id = :userId ORDER BY name ASC")
    fun watchAll(userId: String): Flow<List<TagGroupEntity>>

    @Query("SELECT * FROM tag_groups WHERE id = :id")
    fun watchById(id: String): Flow<TagGroupEntity?>

    @Query("SELECT * FROM tag_groups WHERE id = :id AND user_id = :userId")
    fun watchByIdForUser(id: String, userId: String): Flow<TagGroupEntity?>

    @Query("SELECT * FROM tag_groups WHERE id = :id AND user_id = :userId")
    suspend fun getByIdForUser(id: String, userId: String): TagGroupEntity?

    @Upsert
    suspend fun upsert(entity: TagGroupEntity)

    @Query("UPDATE tag_groups SET deleted_at = :ts, updated_at = :ts WHERE id = :id AND user_id = :userId")
    suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int
}

// ─── Project ↔ Tag Group Join DAO ─────────────────────────────────────────────

/**
 * DAO for the [ProjectInheritedTagGroupCrossRef] join table.
 * Stores which tag groups a project inherits tags from.
 */
@Dao
interface ProjectInheritedTagGroupDao {
    /**
     * Returns all tag group IDs inherited by a project, scoped to its owner.
     */
    @Query(
        """
        SELECT tag_group_id FROM project_tag_groups
        WHERE project_id = :projectId
        AND project_id IN (SELECT id FROM projects WHERE user_id = :userId)
        """,
    )
    fun watchByProject(projectId: String, userId: String): Flow<List<String>>

    /**
     * The one-shot counterpart of [watchByProject], for callers that need the set
     * to *build* something rather than to display it.
     *
     * `watchByProject` returns a Flow, and a Flow cannot be consumed inside the
     * `unitOfWork.write { }` that produced the change — collecting there would
     * suspend a transaction open on the same database. The push path needs the
     * value now: it is about to serialise the project into a patch, and a patch
     * built without this set says the project inherits nothing.
     */
    @Query(
        """
        SELECT tag_group_id FROM project_tag_groups
        WHERE project_id = :projectId
        AND project_id IN (SELECT id FROM projects WHERE user_id = :userId)
        """,
    )
    suspend fun getByProject(projectId: String, userId: String): List<String>

    /**
     * Replaces the entire set of inherited tag groups for a project.
     * Scoped to the owner; returns the number of rows removed.
     */
    @Query(
        """
        DELETE FROM project_tag_groups
        WHERE project_id = :projectId
        AND project_id IN (SELECT id FROM projects WHERE user_id = :userId)
        """,
    )
    suspend fun deleteAllForUser(projectId: String, userId: String): Int

    /**
     * Inserts one inheritance row, scoped to the owning project.
     *
     * Returns `Unit` because Room only permits `Unit`/`Long` from INSERT queries
     * and reports `-1` for a rejected one; the `EXISTS` clause is the enforcement.
     * The caller validates up-front that the project is owned, and reports the
     * failure itself when nothing was written.
     */
    @Query(
        """
        INSERT OR REPLACE INTO project_tag_groups (project_id, tag_group_id)
        SELECT :projectId, :tagGroupId
        WHERE EXISTS (SELECT 1 FROM projects WHERE id = :projectId AND user_id = :userId)
        """,
    )
    suspend fun insertForUser(projectId: String, tagGroupId: String, userId: String)

    /**
     * Whether [projectId] exists and belongs to [userId].
     * Whether [projectId] exists and belongs to [userId].
     *
     * Lives here rather than in the repository so callers do not have to take a
     * dependency on [ProjectDao] purely to assert ownership of a project they are
     * only touching through this join table.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM projects WHERE id = :projectId AND user_id = :userId)")
    suspend fun isProjectOwnedBy(projectId: String, userId: String): Boolean

    /**
     * Drops every inheritance row for a tag group being deleted, across all of
     * [userId]'s projects. Scoped through `projects.user_id` because the join table
     * itself carries no owner column.
     *
     * Without this the group id stays in `project_tag_groups` after the group is
     * soft-deleted, and [watchByProject] keeps returning it — the project then
     * resolves tags from a group the user deleted.
     */
    @Query(
        """
        DELETE FROM project_tag_groups
        WHERE tag_group_id = :tagGroupId
        AND project_id IN (SELECT id FROM projects WHERE user_id = :userId)
        """,
    )
    suspend fun deleteByGroupForUser(tagGroupId: String, userId: String): Int
}
