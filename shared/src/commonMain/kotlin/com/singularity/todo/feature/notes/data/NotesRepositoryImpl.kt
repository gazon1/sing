@file:Suppress("TooManyFunctions")

package com.singularity.todo.feature.notes.data

import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.repository.assertCanWrite
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.notes.LinkSchemes
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteColor
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteKind
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.notes.toEntity
import com.singularity.todo.feature.notes.toLinksJson
import com.singularity.todo.feature.notes.toNote
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * Room-backed production [NotesRepository].
 */
class NotesRepositoryImpl(
    private val noteDao: NoteDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
    private val syncRepository: SyncRepository,
) : NotesRepository {

    // ─── GenericUserScopedRepository ───────────────────────────────────────────

    override fun observeAll(): Flow<List<Note>> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchAll(uid.value).map { list -> list.map { it.toNote() } }
    }

    override fun observe(id: NoteId): Flow<Note?> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchByIdForUser(id.value, uid.value).map { it?.toNote() }
    }

    override suspend fun get(id: NoteId): Note? {
        val uid = currentUser.scopedUserId.value
        return noteDao.getByIdForUser(id.value, uid.value)?.toNote()
    }

    override suspend fun create(item: Note): Result<Note> = runCatching {
        currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
        noteDao.upsert(item.toEntity())
        item.also { syncRepository.enqueue(it) }
    }

    override suspend fun update(item: Note): Result<Note> = runCatching {
        currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
        val toUpdate = item.copy(userId = currentUser.scopedUserId.value)
        noteDao.upsert(toUpdate.toEntity())
        toUpdate.also { syncRepository.enqueue(it) }
    }

    private suspend fun enqueueFresh(id: NoteId) {
        val row = noteDao.getByIdForUser(id.value, currentUser.scopedUserId.value.value) ?: return
        syncRepository.enqueue(row.toNote())
    }

    // ─── Remote apply (pull handler) ────────────────────────────────────────────

    override suspend fun upsert(note: Note): Note {
        noteDao.upsert(note.toEntity())
        return note
    }

    override suspend fun delete(id: NoteId): Result<Unit> = runCatching {
        val rows = noteDao.softDeleteForUser(
            id.value,
            clock.now().toEpochMilliseconds(),
            currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    // ─── SoftDeletable ────────────────────────────────────────────────────────

    override suspend fun restore(id: NoteId): Result<Unit> = runCatching {
        val rows = noteDao.restoreForUser(
            id.value,
            clock.now().toEpochMilliseconds(),
            currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    // ─── Domain methods ───────────────────────────────────────────────────────

    override fun watchPinned(): Flow<List<Note>> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchPinned(uid.value).map { list -> list.map { it.toNote() } }
    }

    override fun watchArchived(): Flow<List<Note>> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchArchived(uid.value).map { list -> list.map { it.toNote() } }
    }

    override fun watchRootNotes(): Flow<List<Note>> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchRootNotes(uid.value).map { list -> list.map { it.toNote() } }
    }

    override fun search(query: String): Flow<List<Note>> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchSearchByTitle(uid.value, query).map { list -> list.map { it.toNote() } }
    }

    override suspend fun createWithContent(
        id: NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<NoteId> = runCatching {
        val uid = currentUser.scopedUserId.value
        val now = clock.now().toEpochMilliseconds()
        noteDao.upsert(
            NoteEntity(
                id = id.value,
                userId = uid.value,
                title = title,
                bodyMarkdown = bodyMarkdown,
                bodyHtml = bodyHtml,
                kind = NoteKind.Plain,
                parentNoteId = null,
                isPinned = false,
                pinnedAt = null,
                color = null,
                sortOrder = 0,
                wordCount = bodyMarkdown.split(Regex("\\s+")).count { it.isNotBlank() },
                charCount = bodyMarkdown.length,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                archivedAt = null,
            ),
        )
        enqueueFresh(id)
        id
    }

    override suspend fun createNoteWithTitle(title: String): Result<NoteId> = runCatching {
        val uid = currentUser.scopedUserId.value
        val id = NoteId(com.singularity.todo.core.ids.nextId())
        val now = clock.now().toEpochMilliseconds()
        noteDao.upsert(
            NoteEntity(
                id = id.value,
                userId = uid.value,
                title = title,
                bodyMarkdown = null,
                bodyHtml = null,
                kind = NoteKind.Plain,
                parentNoteId = null,
                isPinned = false,
                pinnedAt = null,
                color = null,
                sortOrder = 0,
                wordCount = 0,
                charCount = 0,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                archivedAt = null,
            ),
        )
        enqueueFresh(id)
        id
    }

    override suspend fun updateContent(
        id: NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<Unit> = runCatching {
        val wordCount = bodyMarkdown.split(Regex("\\s+")).count { it.isNotBlank() }
        val rows = noteDao.updateContentForUser(
            id = id.value,
            title = title,
            markdown = bodyMarkdown,
            html = bodyHtml,
            wordCount = wordCount,
            charCount = bodyMarkdown.length,
            updatedAt = clock.now().toEpochMilliseconds(),
            userId = currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun archive(id: NoteId): Result<Unit> = runCatching {
        val rows = noteDao.archiveForUser(
            id.value,
            clock.now().toEpochMilliseconds(),
            currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun unarchive(id: NoteId): Result<Unit> = runCatching {
        val rows = noteDao.unarchiveForUser(
            id.value,
            clock.now().toEpochMilliseconds(),
            currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun setPinned(id: NoteId, pinned: Boolean): Result<Unit> = runCatching {
        val now = clock.now().toEpochMilliseconds()
        val rows = noteDao.setPinnedForUser(
            id = id.value,
            pinned = pinned,
            pinnedAt = if (pinned) now else null,
            ts = now,
            userId = currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun setColor(id: NoteId, color: NoteColor?): Result<Unit> = runCatching {
        val rows = noteDao.setColorForUser(
            id = id.value,
            color = color?.value,
            ts = clock.now().toEpochMilliseconds(),
            userId = currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun setSortOrder(id: NoteId, sortOrder: Int): Result<Unit> = runCatching {
        val rows = noteDao.setSortOrderForUser(
            id = id.value,
            sortOrder = sortOrder,
            ts = clock.now().toEpochMilliseconds(),
            userId = currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun setOutgoingLinks(id: NoteId, links: List<String>): Result<Unit> = runCatching {
        val rows = noteDao.setOutgoingLinksForUser(
            id = id.value,
            linksJson = links.toLinksJson(),
            updatedAt = clock.now().toEpochMilliseconds(),
            userId = currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    // ─── Templates and daily notes ────────────────────────────────────────────────

    override fun watchTemplates(): Flow<List<Note>> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchTemplates(uid.value).map { list -> list.map { it.toNote() } }
    }

    override fun watchDailyNotesInRange(from: String, to: String): Flow<List<Note>> =
        currentUser.observeForCurrentUser { uid ->
            noteDao.watchDailyNotesInRange(uid.value, from, to).map { list -> list.map { it.toNote() } }
        }

    override suspend fun getDailyNote(dateKey: String): Note? {
        val uid = currentUser.scopedUserId.value
        return noteDao.getDailyNote(uid.value, dateKey)?.toNote()
    }

    override suspend fun createFromTemplate(
        templateId: NoteId,
        targetTitle: String,
        targetDateKey: String?,
    ): Result<NoteId> = runCatching {
        val uid = currentUser.scopedUserId.value
        val template = noteDao.getByIdForUser(templateId.value, uid.value)
            ?: throw IllegalArgumentException("Template not found: $templateId")
        val now = clock.now().toEpochMilliseconds()
        val newId = NoteId(com.singularity.todo.core.ids.nextId())
        val finalTitle = targetDateKey?.let { "$it — $targetTitle" } ?: targetTitle
        noteDao.upsert(
            NoteEntity(
                id = newId.value,
                userId = uid.value,
                title = finalTitle,
                bodyMarkdown = template.bodyMarkdown,
                bodyHtml = template.bodyHtml,
                isFolder = false,
                kind = if (targetDateKey != null) NoteKind.Daily else NoteKind.Plain,
                parentNoteId = null,
                isPinned = false,
                pinnedAt = null,
                color = template.color,
                sortOrder = 0,
                wordCount = template.bodyMarkdown?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: 0,
                charCount = template.bodyMarkdown?.length ?: 0,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                archivedAt = null,
            ),
        )
        enqueueFresh(newId)
        newId
    }

    override suspend fun saveAsTemplate(id: NoteId): Result<Unit> = runCatching {
        val rows = noteDao.setKindForUser(
            id = id.value,
            kind = NoteKind.Template.name,
            ts = clock.now().toEpochMilliseconds(),
            userId = currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun getOrCreateDailyNote(dateKey: String, fromTemplateId: NoteId?): Result<NoteId> = runCatching {
        val uid = currentUser.scopedUserId.value
        val existing = noteDao.getDailyNote(uid.value, dateKey)
        if (existing != null) {
            return@runCatching NoteId.fromString(existing.id)
        }
        val now = clock.now().toEpochMilliseconds()
        val newId = NoteId(com.singularity.todo.core.ids.nextId())
        val template = fromTemplateId?.let { noteDao.getByIdForUser(it.value, uid.value) }
        noteDao.upsert(
            NoteEntity(
                id = newId.value,
                userId = uid.value,
                title = dateKey,
                bodyMarkdown = template?.bodyMarkdown,
                bodyHtml = template?.bodyHtml,
                isFolder = false,
                kind = NoteKind.Daily,
                parentNoteId = null,
                isPinned = false,
                pinnedAt = null,
                color = template?.color,
                sortOrder = 0,
                wordCount = template?.bodyMarkdown?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: 0,
                charCount = template?.bodyMarkdown?.length ?: 0,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                archivedAt = null,
            ),
        )
        enqueueFresh(newId)
        newId
    }

    // ─── Task-logbook ───────────────────────────────────────────────────────────────

    override fun watchForTask(taskId: TaskId): Flow<List<Note>> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchByTaskForUser(taskId.value, uid.value)
            .map { list -> list.map { it.toNote() } }
    }

    override suspend fun createForTask(
        taskId: TaskId,
        title: String,
        bodyMarkdown: String?,
        bodyHtml: String?,
    ): Result<NoteId> = runCatching {
        val uid = currentUser.scopedUserId.value
        val now = clock.now().toEpochMilliseconds()
        val id = NoteId(com.singularity.todo.core.ids.nextId())
        // Build outgoing_links: the task:// wikilink for backward compat with wikilink-based backlinks
        val taskWikilink = "${LinkSchemes.TASK_PREFIX}${taskId.value}"
        val links = listOf(taskWikilink)
        val wordCount = bodyMarkdown?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: 0
        noteDao.upsert(
            NoteEntity(
                id = id.value,
                userId = uid.value,
                title = title,
                bodyMarkdown = bodyMarkdown,
                bodyHtml = bodyHtml,
                kind = NoteKind.Plain,
                parentNoteId = null,
                isPinned = false,
                pinnedAt = null,
                color = null,
                sortOrder = 0,
                wordCount = wordCount,
                charCount = bodyMarkdown?.length ?: 0,
                outgoingLinks = links.toLinksJson(),
                taskId = taskId.value, // structural FK — the indexed column
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                archivedAt = null,
            ),
        )
        enqueueFresh(id)
        id
    }
}
