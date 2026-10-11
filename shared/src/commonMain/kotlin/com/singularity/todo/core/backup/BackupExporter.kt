package com.singularity.todo.core.backup

import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationDao
import com.singularity.todo.core.database.AgendaViewDao
import com.singularity.todo.core.database.ChecklistDao
import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.ProfileDao
import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.ProjectInheritedTagGroupDao
import com.singularity.todo.core.database.SavedSearchDao
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagGroupDao
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.timetracking.data.TimeEntryDao
import com.singularity.todo.core.database.ProjectReminderDao
import com.singularity.todo.core.database.ReminderDao
import kotlin.time.Clock
import com.singularity.todo.core.error.runCatchingCancellable

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
) {
    private val json = StableJson

    suspend fun export(options: ExportOptions): Result<BackupResult> = runCatchingCancellable {
        val now = clock.now().toEpochMilliseconds()

        // 1. Read all entities for user
        val tasks = taskDao.listAllForUser(options.userId.value)
        val notes = noteDao.listAllForUser(options.userId.value)
        val projects = projectDao.listAllForUser(options.userId.value)
        val tags = tagDao.listAllForUser(options.userId.value)
        val attachments = attachmentDao.listAllForUser(options.userId.value)
        val annotations = annotationDao.listAllForUser(options.userId.value)
        val taskDeps = taskDao.listAllDependenciesForUser(options.userId.value)
        val taskTagRefs = taskDao.listAllTagsForUser(options.userId.value)
        val agendaViews = agendaViewDao.listAllForUser(options.userId.value)
        // MR-2: 8 new entity types
        val taskReminders = reminderDao.listAllForUser(options.userId.value)
        val projectReminders = projectReminderDao.listAllForUser(options.userId.value)
        val checklistItems = checklistDao.listAllForUser(options.userId.value)
        val tagGroups = tagGroupDao.listAllForUser(options.userId.value)
        val projectTagGroups = projectTagGroupDao.listAllForUser(options.userId.value)
        val savedSearches = savedSearchDao.listAllForUser(options.userId.value)
        val timeEntries = timeEntryDao.listAllForUser(options.userId.value)
        val profiles = profileDao.listAll()

        // 2. Map to DTOs
        val payload = BackupPayload(
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
        val payloadBytes = json.encodeToString(BackupPayload.serializer(), payload)
            .encodeToByteArray()

        // 4. Build manifest
        val counts = EntityCounts(
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
        val manifest = BackupDomain.buildManifest(
            appVersion = options.appVersion,
            nowEpochMillis = now,
            userId = options.userId,
            payloadBytes = payloadBytes,
            counts = counts,
        )
        val manifestBytes = json.encodeToString(BackupManifest.serializer(), manifest)
            .encodeToByteArray()

        // 5. Read attachment files
        val attachmentEntries = mutableListOf<Pair<String, ByteArray>>()

        if (options.includeAttachments) {
            for (att in attachments) {
                val path = att.localPath
                if (path != null) {
                    attachmentEntries.add("${att.id}.dat" to fs.readBytes(path))
                }
            }
        }

        // 6. Write zip
        codec.export(manifestBytes, payloadBytes, attachmentEntries, options.destPath, fs)
            .getOrThrow()

        val byteSize = fs.readBytes(options.destPath).size.toLong()

        BackupResult(
            manifest = manifest,
            destPath = options.destPath,
            byteSize = byteSize,
        )
    }
}
