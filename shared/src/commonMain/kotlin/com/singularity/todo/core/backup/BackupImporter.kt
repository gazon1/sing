package com.singularity.todo.core.backup

import co.touchlab.kermit.Logger
import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationDao
import com.singularity.todo.core.database.AgendaViewDao
import com.singularity.todo.core.database.ChecklistDao
import com.singularity.todo.core.database.ChecklistItemEntity
import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.ProfileDao
import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.ProjectInheritedTagGroupDao
import com.singularity.todo.core.database.ProjectReminderDao
import com.singularity.todo.core.database.ReminderDao
import com.singularity.todo.core.database.SavedSearchDao
import com.singularity.todo.core.database.SavedSearchEntity
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagGroupDao
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.tasks.TasksSettingsRepository
import com.singularity.todo.feature.timetracking.data.TimeEntryDao
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

/**
 * Reads an archive back.
 *
 * The repository's half of the contract with the importer: it asks for an archive at a
 * path and gets a result. The port exists so the repository can be tested without the
 * importer's twenty-odd database dependencies — a repository that cannot be tested is a
 * repository whose decisions nobody checks.
 */
interface ArchiveImporter {

    /** Restores the archive at [options]'s source path. */
    suspend fun import(options: ImportOptions): Result<RestoreResult>
}

class BackupImporter(
    private val log: Logger,
    private val taskDao: TaskDao,
    private val noteDao: NoteDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val reminderDao: ReminderDao,
    private val projectReminderDao: ProjectReminderDao,
    private val checklistDao: ChecklistDao,
    private val tagGroupDao: TagGroupDao,
    private val projectTagGroupDao: ProjectInheritedTagGroupDao,
    private val savedSearchDao: SavedSearchDao,
    private val timeEntryDao: TimeEntryDao,
    private val profileDao: ProfileDao,
    private val agendaViewDao: AgendaViewDao,
    private val attachmentDao: AttachmentDao,
    private val annotationDao: AttachmentAnnotationDao,
    private val attachmentStorage: AttachmentStorage,
    private val codec: BackupCodec,
    private val clock: Clock,
    private val createFileSource: FileSourceFactory,
    private val tasksSettings: TasksSettingsRepository,
) : ArchiveImporter {
    private val json = StableJson

    /**
     * Restores the archive, and the changes that follow it.
     *
     * ## What a restore is, now
     *
     * An archive may be a **chain**: a base and the changes that follow it. The
     * base is imported first and every change is applied after it, in order —
     * through the **same** code path the base goes through, because a change is
     * a set of whole rows, and applying a set of whole rows is what the base is.
     *
     * The result counts the **merged** state, not the base's: a restore that
     * reports the base's numbers says less than the account that was restored.
     */
    override suspend fun import(options: ImportOptions): Result<RestoreResult> =
        runCatchingCancellable {
            val now = clock.now().toEpochMilliseconds()

            // 1. Read zip — use FileSource so content:// URIs from SAF work on Android
            val read = codec.importFromSource(createFileSource(options.sourcePath)).getOrThrow()

            // 2. Validate manifest
            val manifest: BackupManifest =
                json.decodeFromString(
                    BackupManifest.serializer(),
                    read.manifestBytes.decodeToString(),
                )
            BackupDomain.validateManifest(manifest, read.payloadBytes).getOrThrow()

            // 3. Decode payload
            val payload: BackupPayload =
                json.decodeFromString(
                    BackupPayload.serializer(),
                    read.payloadBytes.decodeToString(),
                )

            // 4. Apply migrations if needed
            val migrated = migrate(payload, read.payloadBytes)

            // 5. The base, and the changes that follow it.
            //
            // The base goes in first and the changes go in after it, in order.
            // Both go in through the same path: a change carries whole rows, and
            // whole rows are what the base carries too.
            var merged = migrated
            val outcome = applyPayload(migrated, options.targetUserId, now, read.attachments)
            for (change in options.entries) {
                val delta = json.decodeFromString(BackupDelta.serializer(), change.decodeToString())
                val changePayload = migrate(delta.payload, json.encodeToString(delta.payload).encodeToByteArray())
                applyPayload(changePayload, options.targetUserId, now, emptyMap())
                merged = merge(merged, changePayload)
            }

            // A restored archive can contain rows from before the series model: a
            // rule with no series id. The launch repair is gated by a per-profile
            // marker, and the profile it is restoring into may already carry that
            // marker — a backup restored after the upgrade would otherwise leave
            // the legacy rows unvisited forever. The marker is cleared here, so
            // the next launch repairs the restored rows.
            val scope = options.targetUserId.value
            runCatchingCancellable { tasksSettings.setRecurringSeriesRepairVersion(scope, 0) }
                .onFailure { error ->
                    // The rows are already committed. A settings write that failed
                    // must not turn a completed restore into a failed one — the
                    // archive would be deleted by the caller — so the marker reset
                    // is best-effort: a profile that keeps its marker pays one
                    // skipped repair, not a lost restore.
                    log.w(error) { "The recurring-series repair marker was not cleared for $scope" }
                }

            RestoreResult(
                manifest = manifest,
                entityCounts = BackupDomain.countsOf(merged),
                restoredAttachmentCount = outcome.restored,
                missingAttachmentIds = outcome.missing,
            )
        }

    /**
     * The payload as this build understands it.
     *
     * A payload written by an older build is migrated before it is applied; a
     * payload of this build's own schema is left alone.
     */
    private fun migrate(payload: BackupPayload, payloadBytes: ByteArray): BackupPayload =
        if (payload.schemaVersion < BackupFormat.SCHEMA_VERSION) {
            val jsonObj = json.parseToJsonElement(payloadBytes.decodeToString()).jsonObject
            val migrated = BackupMigrations.migrate(jsonObj, payload.schemaVersion)
            val migratedBytes = json.encodeToString(migrated).encodeToByteArray()
            json.decodeFromString(BackupPayload.serializer(), migratedBytes.decodeToString())
        } else {
            payload
        }

    /**
     * The rows of [payload], written into the database.
     *
     * ## The one path
     *
     * The base of a chain and every change in it come through here. That is
     * deliberate: a restore that applies the base one way and the changes
     * another way is a restore with two sets of rules, and the change is the
     * part nobody tested.
     *
     * LAYER EXCEPTION — this writes DAOs directly instead of going through
     * repositories, and that is deliberate rather than an oversight.
     *
     * Every user-scoped repository resolves its target from the *ambient*
     * profile (`ProfileAwareCurrentUser.scopedUserId`) and its writes are
     * constrained to that user: `assertCanWrite` rejects a foreign `userId`,
     * and the `*ForUser` DAO queries filter on it. An import, by contrast,
     * targets an arbitrary `options.targetUserId` — restoring a backup on
     * behalf of another account is the whole point of the feature. Routing
     * this through the repositories would either be rejected by the guard or
     * would require temporarily switching the active profile, which is a
     * user-visible side effect for the duration of the import.
     *
     * It is also why the two unscoped `@Upsert` cross-ref methods in TaskDao
     * are retained: `upsertTagCrossRef` and `upsertDependency` have no
     * user-scoped counterpart because only this path needs them.
     *
     * Tracked as known debt in docs/decisions/2026-09-27-write-layer-soundness.md
     * (ledger #2). The proper resolution is a dedicated bulk-import port that
     * takes an explicit userId, not a pass through the ambient-scoped
     * repositories.
     */
    private suspend fun applyPayload(
        payload: BackupPayload,
        targetUserId: UserId,
        now: Long,
        attachmentBytes: Map<String, ByteArray>,
    ): AttachmentOutcome {
        applyRows(payload, targetUserId, now)
        applyLinks(payload)
        applyViews(payload, targetUserId, now)
        applyGroups(payload, targetUserId, now)
        applySavedSearches(payload.savedSearches, targetUserId, now)
        applyReminders(payload, targetUserId, now)
        applyChecklistItems(payload.checklistItems, now)
        applyTimeEntries(payload.timeEntries, targetUserId, now)
        val attachments = applyAttachments(payload.attachments, targetUserId, now, attachmentBytes)

        // The removals go last, and they go last for the same reason the merge
        // puts them after the upserts: everything this payload carries is written
        // before anything is taken out. The link tables have no `user_id` of their
        // own, so their statements reach their row through the parent that owns
        // it — and a parent this payload has just written is alive, while one this
        // payload is about to remove would take the statement's guard with it and
        // leave the link behind.
        applyRemovals(payload.removals, targetUserId)

        return attachments
    }

    /**
     * The rows the payload says are gone, taken out of the database.
     *
     * ## Why the restore deletes rows
     *
     * Everywhere else a restore is additive: the rows the archive holds are
     * written, and the rows the account already had are left alone. A removal is
     * the one statement that cannot be additive — the row has to go, or the
     * account keeps a task the person deleted on the other device.
     *
     * ## One kind, one statement
     *
     * Every kind is taken out of the table it belongs to, under the profile being
     * restored, by the statement that knows that table. There is no generic
     * "delete a row" here: a removal names a key, and only the table's own
     * statement knows what the key means.
     *
     * ## The keys
     *
     * A removal travels as a key — the key of the row that is not there any more
     * — so the link tables' keys, which are the pair of ids they join, are taken
     * apart again here.
     *
     * ## The order: the children go first
     *
     * The link tables carry no `user_id` of their own: a row of `task_tags`
     * belongs to whoever owns the task it joins, and the statement that removes
     * it reaches its row through that task. So a removal of a task and a removal
     * of one of its links cannot be taken out in any order but one — the link
     * first, while the task is still there. The other order leaves the link
     * behind, pointing at a task that is not there any more, and nothing ever
     * comes to clean it up: the tables have no foreign key to cascade.
     */
    private suspend fun applyRemovals(
        removals: Map<BackupEntityKind, List<String>>,
        userId: UserId,
    ) {
        // The links first: they are the rows that need their parent alive.
        removals[BackupEntityKind.TASK_TAGS]?.forEach { key ->
            val (taskId, tagId) = key.linkParts()
            taskDao.removeTagRefForUser(taskId, tagId, userId.value)
        }
        removals[BackupEntityKind.TASK_DEPENDENCIES]?.forEach { key ->
            val (taskId, dependsOnTaskId) = key.linkParts()
            taskDao.removeDependencyForUser(taskId, dependsOnTaskId, userId.value)
        }
        removals[BackupEntityKind.CHECKLIST_ITEMS]?.forEach { checklistDao.deleteForUser(it, userId.value) }
        removals[BackupEntityKind.PROJECT_TAG_GROUPS]?.forEach { key ->
            val (projectId, tagGroupId) = key.linkParts()
            projectTagGroupDao.delete(projectId, tagGroupId, userId.value)
        }

        // Then the rows themselves, the ones that hold no link of their own.
        removals[BackupEntityKind.ATTACHMENT_ANNOTATIONS]?.forEach {
            annotationDao.deleteForUser(it, userId.value)
        }
        removals[BackupEntityKind.ATTACHMENTS]?.forEach { attachmentDao.deleteForUser(it, userId.value) }
        removals[BackupEntityKind.AGENDA_VIEWS]?.forEach { agendaViewDao.delete(userId.value, it) }
        removals[BackupEntityKind.SAVED_SEARCHES]?.forEach { savedSearchDao.delete(userId.value, it) }
        removals[BackupEntityKind.TASK_REMINDERS]?.forEach { reminderDao.delete(it, userId.value) }
        removals[BackupEntityKind.PROJECT_REMINDERS]?.forEach {
            projectReminderDao.delete(it, userId.value)
        }
        removals[BackupEntityKind.TIME_ENTRIES]?.forEach { timeEntryDao.delete(it, userId.value) }

        // The rows the links hang off, last.
        removals[BackupEntityKind.TAGS]?.forEach { tagDao.deleteForUser(it, userId.value) }
        removals[BackupEntityKind.TAG_GROUPS]?.forEach { tagGroupDao.deleteForUser(it, userId.value) }
        removals[BackupEntityKind.TASKS]?.forEach { taskDao.deleteForUser(it, userId.value) }
        removals[BackupEntityKind.NOTES]?.forEach { noteDao.deleteForUser(it, userId.value) }
        removals[BackupEntityKind.PROJECTS]?.forEach { projectDao.deleteForUser(it, userId.value) }

        // A profile belongs to the account it was made in, so the restore may
        // only take out the profile of the account it is restoring — the same
        // scope every other removal is under.
        removals[BackupEntityKind.PROFILES]?.forEach { profileDao.deleteForUser(it, userId.value) }
    }

    /** The rows that carry the account itself: tasks, notes, projects and tags. */
    private suspend fun applyRows(payload: BackupPayload, userId: UserId, now: Long) {
        for (task in payload.tasks) {
            taskDao.upsert(
                task.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = task.createdAt,
                ),
            )
        }
        for (note in payload.notes) {
            noteDao.upsert(
                note.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = note.createdAt,
                ),
            )
        }
        for (project in payload.projects) {
            projectDao.upsert(
                project.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = project.createdAt,
                ),
            )
        }
        for (tag in payload.tags) {
            tagDao.upsert(
                tag.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = tag.createdAt,
                ),
            )
        }
    }

    /** The link tables: the pairs that join one row to another. */
    private suspend fun applyLinks(payload: BackupPayload) {
        for (taskTag in payload.taskTags) {
            taskDao.upsertTagCrossRef(taskTag.toEntity())
        }
        for (dep in payload.taskDependencies) {
            taskDao.upsertDependency(dep.toEntity())
        }
    }

    /**
     * The saved views and the annotations.
     *
     * The annotations land after the rows they reference, though nothing enforces the order:
     * the table has no foreign key (see AttachmentAnnotationEntity), and adding one
     * would block restoring a note whose file is not in the archive.
     */
    private suspend fun applyViews(payload: BackupPayload, userId: UserId, now: Long) {
        for (view in payload.agendaViews) {
            agendaViewDao.upsert(
                view.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = view.createdAt,
                ),
            )
        }
        for (annotation in payload.attachmentAnnotations) {
            annotationDao.upsert(
                annotation.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = annotation.createdAt,
                ),
            )
        }
    }

    /**
     * The profiles and the groups.
     *
     * The profiles are restored with their original userId intact (not stamped
     * with the target's). The tag groups land before the tags that reference
     * them, and the project's link to a group lands after the groups.
     */
    private suspend fun applyGroups(payload: BackupPayload, userId: UserId, now: Long) {
        for (profile in payload.profiles) {
            profileDao.upsert(profile.toEntity())
        }
        for (tagGroup in payload.tagGroups) {
            tagGroupDao.upsert(
                tagGroup.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = tagGroup.createdAt,
                ),
            )
        }
        for (ptg in payload.projectTagGroups) {
            projectTagGroupDao.insert(ptg.toEntity())
        }
    }

    /** Saved searches are standalone, with no cross-entity references. */
    private suspend fun applySavedSearches(searches: List<SavedSearchDto>, userId: UserId, now: Long) {
        for (search in searches) {
            savedSearchDao.upsert(
                SavedSearchEntity(
                    id = search.id,
                    userId = userId.value,
                    name = search.name,
                    queryString = search.queryString,
                    createdAt = search.createdAt,
                    updatedAt = now,
                ),
            )
        }
    }

    /** Task reminders and project reminders have no cross-entity foreign keys. */
    private suspend fun applyReminders(payload: BackupPayload, userId: UserId, now: Long) {
        for (reminder in payload.taskReminders) {
            reminderDao.upsert(
                reminder.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = reminder.createdAt,
                ),
            )
        }
        for (reminder in payload.projectReminders) {
            projectReminderDao.upsert(
                reminder.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = reminder.createdAt,
                ),
            )
        }
    }

    /** Checklist items reference the restoring user's tasks, so upsert is safe. */
    private suspend fun applyChecklistItems(items: List<ChecklistItemDto>, now: Long) {
        for (item in items) {
            checklistDao.upsert(
                ChecklistItemEntity(
                    id = item.id,
                    taskId = item.taskId,
                    title = item.title,
                    isCompleted = item.isCompleted,
                    sortOrder = item.sortOrder,
                    createdAt = item.createdAt,
                    updatedAt = now,
                    checkedBy = item.checkedBy,
                    checkedAt = item.checkedAt,
                    rowVersion = item.rowVersion,
                ),
            )
        }
    }

    /** Time entries reference the restoring user's tasks. */
    private suspend fun applyTimeEntries(entries: List<TimeEntryDto>, userId: UserId, now: Long) {
        for (entry in entries) {
            timeEntryDao.upsert(
                entry.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = entry.createdAt,
                ),
            )
        }
    }

    /**
     * The attachment files, written next to their rows.
     *
     * A change carries no bytes — the file is in the snapshot, not in the entry
     * — so a change's payload holds no attachments at all, and this writes
     * nothing for it.
     */
    private suspend fun applyAttachments(
        attachments: List<AttachmentDto>,
        userId: UserId,
        now: Long,
        attachmentBytes: Map<String, ByteArray>,
    ): AttachmentOutcome {
        var restoredCount = 0
        val missingIds = mutableListOf<String>()

        for (att in attachments) {
            val entryName = "${att.id}.dat"
            val bytes = attachmentBytes[entryName]
            if (bytes != null) {
                try {
                    val ext = att.mimeType?.substringAfterLast('/') ?: ""
                    // Both halves matter, and neither subsumes the other.
                    //
                    // The row, not just the bytes: until it was inserted the importer used
                    // the attachment DTO only to find the entry in the zip, so a restore
                    // put every file on disk with nothing pointing at it — and every
                    // annotation restored below at a file the database had no record of.
                    //
                    // `getOrThrow()` because the try/catch below is written against a
                    // throwing contract: `saveBytes` returns a Result, so without the
                    // unwrap a failed write fell through to `restoredCount++` and the catch
                    // was never entered. The restore report then counted an attachment that
                    // was not on disk.
                    //
                    // Order matters: the write has to follow the copy, because `localPath`
                    // comes back from `saveBytes`.
                    val localPath =
                        attachmentStorage
                            .saveBytes(att.taskId, att.id, bytes, ext)
                            .getOrThrow()
                    attachmentDao.upsert(
                        att.toEntity(userId.value).copy(
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
                // The row is still inserted when the bytes are absent: the attachment exists
                // as a record of what was there, marked by a null `localPath` rather than by
                // its absence, and dropping it would also orphan the annotations written
                // against it.
                attachmentDao.upsert(
                    att.toEntity(userId.value).copy(localPath = null, updatedAt = now),
                )
                missingIds.add(att.id)
            }
        }

        return AttachmentOutcome(restored = restoredCount, missing = missingIds)
    }

    /** What the attachment files did: how many landed, and which did not. */
    private class AttachmentOutcome(val restored: Int, val missing: List<String>)
}
