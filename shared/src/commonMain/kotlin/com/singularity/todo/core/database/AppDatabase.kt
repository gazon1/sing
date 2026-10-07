package com.singularity.todo.core.database

import androidx.room3.AutoMigration
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.singularity.todo.core.attachments.AttachmentConverters
import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.AttachmentEntity
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationDao
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationEntity
import com.singularity.todo.core.config.RemoteConfigCacheDao
import com.singularity.todo.core.config.RemoteConfigCacheEntity
import com.singularity.todo.core.sync.RemoteConfigDao
import com.singularity.todo.core.sync.RemoteConfigEntity
import com.singularity.todo.core.sync.SyncDeadLetterDao
import com.singularity.todo.core.sync.SyncDeadLetterEntity
import com.singularity.todo.core.sync.SyncOutboxDao
import com.singularity.todo.core.sync.SyncStateDao
import com.singularity.todo.core.sync.SyncShadowDao
import com.singularity.todo.core.sync.SyncShadowEntity
import com.singularity.todo.core.sync.SyncStateEntity
import com.singularity.todo.core.sync.SyncOutboxEntity
import com.singularity.todo.feature.calendar_sync.data.CalendarImportEventDao
import com.singularity.todo.feature.calendar_sync.data.CalendarImportEventEntity
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncStateDao
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncStateEntity
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapDao
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapEntity
import com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowDao
import com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowEntity
import com.singularity.todo.feature.proposals.data.AiProposalEntity
import com.singularity.todo.feature.proposals.data.ProposalDao
import com.singularity.todo.feature.proposals.data.ProposalItemDao
import com.singularity.todo.feature.proposals.data.ProposalItemEntity
import com.singularity.todo.feature.timetracking.data.TimeEntryDao
import com.singularity.todo.feature.timetracking.data.TimeEntryEntity

/**
 * Current schema version.
 *
 * Named, and referenced by the `@Database(version = …)` annotation, because two
 * migration tests asserted the literal "33" as "the version the upgrade chain ends
 * at". Every new migration broke both, and the fix — bumping a number in two test
 * files that have nothing to do with the change — is exactly the kind of edit that
 * gets made carelessly under time pressure, or not made at all.
 *
 * The tests now read this. A migration that forgot to bump the annotation still
 * fails them, which is the behaviour worth keeping.
 */
const val SCHEMA_VERSION = 42

/**
 * Room database for Android.
 * Entity and DAO classes are defined in commonMain and are visible here.
 */
@Database(
    entities = [
        TaskEntity::class,
        TaskTagCrossRef::class,
        TaskDependencyCrossRef::class,
        NoteEntity::class,
        ProjectEntity::class,
        TagEntity::class,
        SyncOutboxEntity::class,
        SyncDeadLetterEntity::class,
        SyncStateEntity::class,
        RemoteConfigEntity::class,
        AttachmentEntity::class,
        AttachmentAnnotationEntity::class,
        TaskReminderEntity::class,
        ProjectReminderEntity::class,
        ChecklistItemEntity::class,
        LlmUsageEntity::class,
        ProfileEntity::class,
        AgendaViewEntity::class,
        CalendarSyncTaskMapEntity::class,
        SavedSearchEntity::class,
        RemoteConfigCacheEntity::class,
        TagGroupEntity::class,
        ProjectInheritedTagGroupCrossRef::class,
        TimeEntryEntity::class,
        AiProposalEntity::class,
        ProposalItemEntity::class,
        SyncShadowEntity::class,
        CalendarSyncStateEntity::class,
        GoogleEventShadowEntity::class,
        CalendarImportEventEntity::class,
    ],
    version = SCHEMA_VERSION,
    autoMigrations = [
        AutoMigration(from = 5, to = 6, spec = Migration5To6::class),
        AutoMigration(from = 6, to = 7, spec = Migration6To7::class),
        AutoMigration(from = 7, to = 8, spec = Migration7To8::class),
        AutoMigration(from = 8, to = 9, spec = Migration8To9::class),
        AutoMigration(from = 9, to = 10, spec = Migration9To10::class),
        AutoMigration(from = 10, to = 11, spec = Migration10To11::class),
        AutoMigration(from = 11, to = 12, spec = Migration11To12::class),
        AutoMigration(from = 12, to = 13, spec = Migration12To13::class),
        AutoMigration(from = 13, to = 14, spec = Migration13To14::class),
        AutoMigration(from = 14, to = 15, spec = Migration14To15::class),
        AutoMigration(from = 15, to = 16, spec = Migration15To16::class),
        AutoMigration(from = 16, to = 17, spec = Migration16To17::class),
        AutoMigration(from = 17, to = 18, spec = Migration17To18::class),
        AutoMigration(from = 18, to = 19, spec = Migration18To19::class),
        AutoMigration(from = 19, to = 20, spec = Migration19To20::class),
        AutoMigration(from = 20, to = 21, spec = Migration20To21::class),
        AutoMigration(from = 21, to = 22, spec = Migration21To22::class),
        AutoMigration(from = 22, to = 23, spec = Migration22To23::class),
        AutoMigration(from = 23, to = 24, spec = Migration23To24::class),
        AutoMigration(from = 24, to = 25, spec = Migration24To25::class),
        AutoMigration(from = 25, to = 26, spec = Migration25To26::class),
        AutoMigration(from = 26, to = 27, spec = Migration26To27::class),
        AutoMigration(from = 27, to = 28, spec = Migration27To28::class),
        AutoMigration(from = 28, to = 29, spec = Migration28To29::class),
        AutoMigration(from = 29, to = 30, spec = Migration29To30::class),
        AutoMigration(from = 30, to = 31, spec = Migration30To31::class),
        AutoMigration(from = 32, to = 33, spec = Migration32To33::class),
        AutoMigration(from = 33, to = 34, spec = Migration33To34::class),
        AutoMigration(from = 34, to = 35, spec = Migration34To35::class),
        AutoMigration(from = 35, to = 36, spec = Migration35To36::class),
        AutoMigration(from = 36, to = 37, spec = Migration36To37::class),
        // 37 -> 38 is manual and registered in AppDatabaseFactory instead: it clears
        // the two queue tables, and an AutoMigrationSpec can only describe a schema
        // change. See Migration37To38 for why the rows go.
        // 38 -> 39 is manual for the same reason, and likewise registered there:
        // it adds profiles.user_id. See Migration38To39.
        AutoMigration(from = 39, to = 40, spec = Migration39To40::class),
        AutoMigration(from = 40, to = 41, spec = Migration40To41::class),
        // 41 -> 42 adds attachment_annotations. Room derives the table from the entity.
        AutoMigration(from = 41, to = 42, spec = Migration41To42::class),
    ],
    exportSchema = true,
)
@ColumnTypeConverters(AttachmentConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun noteDao(): NoteDao
    abstract fun projectDao(): ProjectDao
    abstract fun tagDao(): TagDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun syncDeadLetterDao(): SyncDeadLetterDao
    abstract fun syncStateDao(): SyncStateDao

    abstract fun syncShadowDao(): SyncShadowDao

    // Google Calendar sync. Separate from calendarSyncTaskMapDao, which stays the
    // device-calendar path; see the two-port ADR for why they do not share a table.
    abstract fun calendarSyncStateDao(): CalendarSyncStateDao
    abstract fun googleEventShadowDao(): GoogleEventShadowDao
    abstract fun calendarImportEventDao(): CalendarImportEventDao
    abstract fun remoteConfigDao(): RemoteConfigDao
    abstract fun remoteConfigCacheDao(): RemoteConfigCacheDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun annotationDao(): AttachmentAnnotationDao
    abstract fun reminderDao(): ReminderDao
    abstract fun projectReminderDao(): ProjectReminderDao
    abstract fun checklistDao(): ChecklistDao
    abstract fun llmUsageDao(): LlmUsageDao
    abstract fun profileDao(): ProfileDao
    abstract fun agendaViewDao(): AgendaViewDao
    abstract fun calendarSyncTaskMapDao(): CalendarSyncTaskMapDao
    abstract fun savedSearchDao(): SavedSearchDao
    abstract fun tagGroupDao(): TagGroupDao
    abstract fun projectInheritedTagGroupDao(): ProjectInheritedTagGroupDao
    abstract fun timeEntryDao(): TimeEntryDao
    abstract fun proposalDao(): ProposalDao
    abstract fun proposalItemDao(): ProposalItemDao
    abstract fun ownerEraseDao(): OwnerEraseDao
    abstract fun ownerScopeDao(): OwnerScopeDao
}
