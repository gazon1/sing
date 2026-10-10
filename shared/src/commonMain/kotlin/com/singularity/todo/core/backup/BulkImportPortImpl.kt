package com.singularity.todo.core.backup

import co.touchlab.kermit.Logger
import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationDao
import com.singularity.todo.core.database.AgendaViewDao
import com.singularity.todo.core.database.ChecklistDao
import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.ProjectInheritedTagGroupDao
import com.singularity.todo.core.database.ProjectReminderDao
import com.singularity.todo.core.database.ReminderDao
import com.singularity.todo.core.database.SavedSearchDao
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagGroupDao
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.timetracking.data.TimeEntryDao
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/**
 * Implementation of [BulkImportPort] for JVM and Android.
 *
 * All entities are written via DAO upsert with an explicit [UserId], bypassing the
 * ambient-profile guard in [com.singularity.todo.core.repository.assertCanWrite].
 * The DAO's `WHERE user_id = :userId` clause enforces isolation at the storage layer.
 *
 * Sync enqueue is intentionally omitted — the next normal sync cycle detects the restored
 * rows via their `updatedAt = now` timestamps and pushes them through the shadow mechanism.
 * Enqueueing every entity individually would be O(n) sync outbox writes for no benefit,
 * since the restored data is already authoritative on-device.
 */
class BulkImportPortImpl(
    private val log: Logger,
    private val taskDao: TaskDao,
    private val noteDao: NoteDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val agendaViewDao: AgendaViewDao,
    private val attachmentDao: AttachmentDao,
    private val annotationDao: AttachmentAnnotationDao,
    private val reminderDao: ReminderDao,
    private val projectReminderDao: ProjectReminderDao,
    private val checklistDao: ChecklistDao,
    private val tagGroupDao: TagGroupDao,
    private val projectTagGroupDao: ProjectInheritedTagGroupDao,
    private val savedSearchDao: SavedSearchDao,
    private val timeEntryDao: TimeEntryDao,
    private val attachmentStorage: AttachmentStorage,
    private val clock: Clock,
) : BulkImportPort {

    override suspend fun restore(
        payload: BackupPayload,
        manifest: BackupManifest,
        targetUserId: UserId,
        attachmentData: Map<String, ByteArray>,
        overwriteExisting: Boolean,
    ): Result<RestoreResult> = runCatchingCancellable {
        val now = clock.now().toEpochMilliseconds()
        val uid = targetUserId.value

        // ── Core entities ────────────────────────────────────────────────────────

        for (task in payload.tasks) {
            taskDao.upsert(
                task.toEntity(uid).copy(updatedAt = now, createdAt = task.createdAt),
            )
        }
        for (note in payload.notes) {
            noteDao.upsert(
                note.toEntity(uid).copy(updatedAt = now, createdAt = note.createdAt),
            )
        }
        for (project in payload.projects) {
            projectDao.upsert(
                project.toEntity(uid).copy(updatedAt = now, createdAt = project.createdAt),
            )
        }
        for (tag in payload.tags) {
            tagDao.upsert(
                tag.toEntity(uid).copy(updatedAt = now, createdAt = tag.createdAt),
            )
        }

        // ── Cross-refs (no user_id column — scoped through owning entity) ───────────

        for (taskTag in payload.taskTags) {
            taskDao.upsertTagCrossRef(taskTag.toEntity())
        }
        for (dep in payload.taskDependencies) {
            taskDao.upsertDependency(dep.toEntity())
        }

        // ── Agenda views ─────────────────────────────────────────────────────────

        for (view in payload.agendaViews) {
            agendaViewDao.upsert(
                view.toEntity(uid).copy(updatedAt = now, createdAt = view.createdAt),
            )
        }

        // ── MR-2 entity types ───────────────────────────────────────────────────

        for (reminder in payload.taskReminders) {
            reminderDao.upsert(
                reminder.toEntity(uid).copy(updatedAt = now, createdAt = reminder.createdAt),
            )
        }
        for (reminder in payload.projectReminders) {
            projectReminderDao.upsert(
                reminder.toEntity(uid).copy(updatedAt = now, createdAt = reminder.createdAt),
            )
        }
        for (item in payload.checklistItems) {
            checklistDao.upsert(item.toEntity().copy(updatedAt = now))
        }
        for (group in payload.tagGroups) {
            tagGroupDao.upsert(
                group.toEntity(uid).copy(updatedAt = now, createdAt = group.createdAt),
            )
        }
        for (ref in payload.projectTagGroups) {
            projectTagGroupDao.insertForUser(ref.projectId, ref.tagGroupId, uid)
        }
        for (search in payload.savedSearches) {
            savedSearchDao.upsert(
                search.toEntity(uid).copy(updatedAt = now, createdAt = search.createdAt),
            )
        }
        for (entry in payload.timeEntries) {
            timeEntryDao.upsert(
                entry.toEntity(uid).copy(updatedAt = now, createdAt = entry.createdAt),
            )
        }

        // ── Annotations ─────────────────────────────────────────────────────────

        for (annotation in payload.attachmentAnnotations) {
            annotationDao.upsert(
                annotation.toEntity(uid).copy(updatedAt = now, createdAt = annotation.createdAt),
            )
        }

        // ── Attachments (file + row) ─────────────────────────────────────────────

        var restoredCount = 0
        val missingIds = mutableListOf<String>()

        for (att in payload.attachments) {
            val entryName = "${att.id}.dat"
            val bytes = attachmentData[entryName]
            if (bytes != null) {
                try {
                    val ext = att.mimeType?.substringAfterLast('/') ?: ""
                    val localPath = attachmentStorage
                        .saveBytes(att.taskId, att.id, bytes, ext)
                        .getOrThrow()
                    attachmentDao.upsert(
                        att.toEntity(uid).copy(
                            localPath = localPath,
                            updatedAt = now,
                            createdAt = att.createdAt,
                        ),
                    )
                    restoredCount++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(e) { "Attachment restore failed [id=${att.id}]" }
                    missingIds.add(att.id)
                }
            } else {
                // Row is still inserted when bytes are absent — marks what was there,
                // via null localPath rather than omission. Skipping would orphan annotations.
                attachmentDao.upsert(
                    att.toEntity(uid).copy(localPath = null, updatedAt = now),
                )
                missingIds.add(att.id)
            }
        }

        RestoreResult(
            manifest = manifest,
            entityCounts = manifest.entityCounts,
            restoredAttachmentCount = restoredCount,
            missingAttachmentIds = missingIds,
        )
    }
}
