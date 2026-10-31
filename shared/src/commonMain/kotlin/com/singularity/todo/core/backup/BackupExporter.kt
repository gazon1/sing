package com.singularity.todo.core.backup

import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationDao
import com.singularity.todo.core.database.AgendaViewDao
import com.singularity.todo.core.database.ChecklistDao
import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.ProfileDao
import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.ProjectInheritedTagGroupDao
import com.singularity.todo.core.database.ProjectReminderDao
import com.singularity.todo.core.database.ReminderDao
import com.singularity.todo.core.database.SavedSearchDao
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagGroupDao
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.timetracking.data.TimeEntryDao
import kotlin.time.Clock

/**
 * Writes an archive.
 *
 * The repository's half of the contract with the exporter: it asks for an archive at a
 * path and gets a result. The port exists so the repository can be tested without the
 * exporter's twenty-odd database dependencies — a repository that cannot be tested is a
 * repository whose decisions nobody checks.
 */
interface ArchiveExporter {

    /** Writes the archive described by [options]. */
    suspend fun export(options: ExportOptions): Result<BackupResult>
}

class BackupExporter(
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
    private val attachmentDao: AttachmentDao,
    private val annotationDao: AttachmentAnnotationDao,
    private val agendaViewDao: AgendaViewDao,
    private val codec: BackupCodec,
    private val clock: Clock,
    private val fs: FileSystem,
    /**
     * Where the backup keeps its own bookkeeping.
     *
     * The export writes the change that follows the snapshot, and the bookkeeping
     * is what tells it what the last export's rows looked like — so the next
     * export can say what changed. It is the backup's own state and nobody
     * else's: see [BackupCursorStore].
     */
    private val cursors: BackupCursorStore,
    private val backupDir: String,
) : ArchiveExporter {
    private val json = StableJson

    override suspend fun export(options: ExportOptions): Result<BackupResult> =
        runCatchingCancellable {
            val now = clock.now().toEpochMilliseconds()
            val userId = options.userId ?: throw IllegalStateException("userId required")

            // 1. Read all entities for user
            val tasks = taskDao.listAllForUser(userId.value)
            val notes = noteDao.listAllForUser(userId.value)
            val projects = projectDao.listAllForUser(userId.value)
            val tags = tagDao.listAllForUser(userId.value)
            val attachments = attachmentDao.listAllForUser(userId.value)
            val annotations = annotationDao.listAllForUser(userId.value)
            val taskDeps = taskDao.listAllDependenciesForUser(userId.value)
            val taskTagRefs = taskDao.listAllTagsForUser(userId.value)
            val agendaViews = agendaViewDao.listAllForUser(userId.value)
            // MR-2: 8 new entity types
            val taskReminders = reminderDao.listAllForUser(userId.value)
            val projectReminders = projectReminderDao.listAllForUser(userId.value)
            val checklistItems = checklistDao.listAllForUser(userId.value)
            val tagGroups = tagGroupDao.listAllForUser(userId.value)
            val projectTagGroups = projectTagGroupDao.listAllForUser(userId.value)
            val savedSearches = savedSearchDao.listAllForUser(userId.value)
            val timeEntries = timeEntryDao.listAllForUser(userId.value)
            val ownerId = (options.ownerId ?: options.ownerUserId)?.value
            val profiles = if (ownerId != null) profileDao.listOwnedBy(ownerId) else profileDao.listAll()
            // 2. Map to DTOs
            val payload =
                BackupPayload(
                    schemaVersion = BackupFormat.SCHEMA_VERSION,
                    tasks = tasks.map { it.toDto() },
                    notes = notes.map { it.toDto() },
                    projects = projects.map { it.toDto() },
                    tags = tags.map { it.toDto() },
                    attachments = attachments.map { it.toDto() },
                    attachmentAnnotations = annotations.map { it.toDto() },
                    taskTags = taskTagRefs.map { it.toDto() },
                    taskDependencies = taskDeps.map { it.toDto() },
                    agendaViews = agendaViews.map { it.toDto() },
                    taskReminders = taskReminders.map { it.toDto() },
                    projectReminders = projectReminders.map { it.toDto() },
                    checklistItems = checklistItems.map { it.toDto() },
                    tagGroups = tagGroups.map { it.toDto() },
                    projectTagGroups = projectTagGroups.map { it.toDto() },
                    savedSearches = savedSearches.map { it.toDto() },
                    timeEntries = timeEntries.map { it.toDto() },
                    profiles = profiles.map { it.toDto() },
                )

            // 3. Serialize payload
            val payloadBytes =
                json
                    .encodeToString(BackupPayload.serializer(), payload)
                    .encodeToByteArray()

            // 4. Build manifest
            val counts =
                EntityCounts(
                    tasks = tasks.size,
                    notes = notes.size,
                    projects = projects.size,
                    tags = tags.size,
                    attachments = attachments.size,
                    attachmentAnnotations = annotations.size,
                    taskTags = taskTagRefs.size,
                    taskDependencies = taskDeps.size,
                    agendaViews = agendaViews.size,
                    taskReminders = taskReminders.size,
                    projectReminders = projectReminders.size,
                    checklistItems = checklistItems.size,
                    tagGroups = tagGroups.size,
                    projectTagGroups = projectTagGroups.size,
                    savedSearches = savedSearches.size,
                    timeEntries = timeEntries.size,
                    profiles = profiles.size,
                )
            val manifest =
                BackupDomain.buildManifest(
                    appVersion = options.appVersion,
                    nowEpochMillis = now,
                    userId = userId,
                    payloadBytes = payloadBytes,
                    counts = counts,
                )
            val manifestBytes =
                json
                    .encodeToString(BackupManifest.serializer(), manifest)
                    .encodeToByteArray()

            // 5. Point at the attachment files
            //
            // Paths, not bytes: the codec copies each file into the zip in pieces,
            // so an attachment is never whole in memory here — reading it with
            // `fs.readBytes` would be the one place the account's largest files
            // are held at once.
            val attachmentEntries = mutableListOf<Pair<String, String>>()

            if (options.includeAttachments) {
                for (att in attachments) {
                    val path = att.localPath
                    if (path != null) {
                        attachmentEntries.add("${att.id}.dat" to path)
                    }
                }
            }

            // 6. Write zip
            codec
                .export(manifestBytes, payloadBytes, attachmentEntries, options.destPath, fs)
                .getOrThrow()

            // The archive's size is asked of the filesystem, not read back out of
            // it: `fs.readBytes` here would load the whole archive into memory a
            // second time, which is the cost the streaming encoder exists to avoid.
            val byteSize = fs.stat(options.destPath)?.sizeBytes
                ?: throw BackupStorageError.FileNotFound(options.destPath)

            // The change that follows this snapshot.
            //
            // It is written after the snapshot, never before: the bookkeeping says
            // what the last export's rows looked like, and it may only say that
            // once the snapshot the change follows is on the disk. An export that
            // failed half way through leaves the chain where it was, and the next
            // export carries the change again — which costs a few rows and is
            // wrong in no direction.
            if (isAppResolvedDestination(options.destPath)) {
                writeChange(options, payload, now)
            }

            BackupResult(
                manifest = manifest,
                destPath = options.destPath,
                byteSize = byteSize,
            )
        }

    /**
     * Writes the change that follows the snapshot, and records where the backup
     * stands.
     *
     * ## What it writes
     *
     * The rows that changed — the new ones and the ones whose content changed —
     * together with the rows that are gone, as a [BackupDelta] beside the
     * snapshot. When the change cannot be carried that way, **nothing** is written
     * and the chain starts again from this snapshot: an attachment carries bytes
     * that an entry does not.
     *
     * ## When the chain starts again
     *
     * A chain that only grows is a restore that gets slower every time it runs,
     * so the chain is bounded — by the number of changes and by their size — and
     * the export that passes the bound writes a fresh base instead. The old chain
     * is the previous archive: it is still on the device, and it is still a
     * backup.
     */
    private suspend fun writeChange(
        options: ExportOptions,
        payload: BackupPayload,
        now: Long,
    ) {
        val state = cursors.load(options.userId!!)
        val index = BackupRows.index(payload)
        val previous = state.rows.takeIf { it.isNotEmpty() }

        when (val plan = BackupDeltaPlanner.plan(previous, index, payload)) {
            is BackupDeltaPlan.FreshBase -> restartChain(options, state, index, now)

            is BackupDeltaPlan.Entry -> {
                if (plan.payload.hasNoChanges()) {
                    // Nothing changed: there is no change to write, and an empty
                    // entry would only make the chain longer than the account.
                    cursors.save(
                        options.userId!!,
                        state.copy(cursor = now, rows = index),
                    )
                } else {
                    writeEntry(options, state, index, now, plan.payload)
                }
            }
        }
    }

    /**
     * The change is written beside the snapshot, and the chain grows by one.
     *
     * When the chain has passed its bound, the change is taken back off the
     * disk and the snapshot becomes the base of a new chain: a chain that only
     * grows is a restore that gets slower every time it runs.
     */
    private suspend fun writeEntry(
        options: ExportOptions,
        state: BackupCursorState,
        index: BackupRowIndex,
        now: Long,
        payload: BackupPayload,
    ) {
        val delta =
            BackupDelta(
                schemaVersion = BackupFormat.SCHEMA_VERSION,
                cursor = now,
                payload = payload,
            )
        val bytes = json.encodeToString(BackupDelta.serializer(), delta).encodeToByteArray()
        val deltaPath = "${options.destPath}$DELTA_SUFFIX"
        fs.writeBytes(deltaPath, bytes)

        val entries = state.entries + BackupChainEntry(
            archivePath = options.destPath,
            deltaPath = deltaPath,
            byteSize = bytes.size.toLong(),
        )
        val tooLong =
            entries.size > ArchiveFormat.MAX_CHAIN_ENTRIES ||
                entries.sumOf { it.byteSize } > ArchiveFormat.MAX_CHAIN_BYTES
        if (tooLong) {
            // The chain has passed its bound: this snapshot is the new
            // base, and the change just written is not part of any chain.
            fs.delete(deltaPath)
            restartChain(options, state, index, now)
        } else {
            cursors.save(
                options.userId!!,
                state.copy(
                    cursor = now,
                    baseArchivePath = state.baseArchivePath ?: options.destPath,
                    entries = entries,
                    rows = index,
                ),
            )
        }
    }

    /**
     * The chain starts again from this snapshot.
     *
     * The changes the old chain carried are dropped: they described a chain that
     * no longer exists, and leaving them on the disk would make the backup
     * directory hold files that belong to nothing.
     */
    private suspend fun restartChain(
        options: ExportOptions,
        state: BackupCursorState,
        index: BackupRowIndex,
        now: Long,
    ) {
        state.entries.forEach { fs.delete(it.deltaPath) }
        cursors.save(
            options.userId!!,
            state.copy(
                cursor = now,
                baseArchivePath = options.destPath,
                entries = emptyList(),
                rows = index,
            ),
        )
    }

    private fun isAppResolvedDestination(destPath: String): Boolean {
        val dir = backupDir.trimEnd('/', '\\')
        return destPath == dir || destPath.startsWith("$dir/") || destPath.startsWith("$dir\\")
    }

    private companion object {
        /** The suffix of the file the change is written to, beside the snapshot. */
        const val DELTA_SUFFIX = ".delta"
    }
}
