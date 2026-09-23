package com.singularity.todo.core.database

import androidx.room3.AutoMigration
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.singularity.todo.core.attachments.AttachmentConverters
import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.AttachmentEntity
import com.singularity.todo.core.config.RemoteConfigCacheDao
import com.singularity.todo.core.config.RemoteConfigCacheEntity
import com.singularity.todo.core.sync.RemoteConfigDao
import com.singularity.todo.core.sync.RemoteConfigEntity
import com.singularity.todo.core.sync.SyncOutboxDao
import com.singularity.todo.core.sync.SyncOutboxEntity
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapDao
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapEntity

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
        RemoteConfigEntity::class,
        AttachmentEntity::class,
        TaskReminderEntity::class,
        ChecklistItemEntity::class,
        LlmUsageEntity::class,
        ProfileEntity::class,
        AgendaViewEntity::class,
        CalendarSyncTaskMapEntity::class,
        SavedSearchEntity::class,
        RemoteConfigCacheEntity::class,
    ],
    version = 19,
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
    abstract fun remoteConfigDao(): RemoteConfigDao
    abstract fun remoteConfigCacheDao(): RemoteConfigCacheDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun reminderDao(): ReminderDao
    abstract fun checklistDao(): ChecklistDao
    abstract fun llmUsageDao(): LlmUsageDao
    abstract fun profileDao(): ProfileDao
    abstract fun agendaViewDao(): AgendaViewDao
    abstract fun calendarSyncTaskMapDao(): CalendarSyncTaskMapDao
    abstract fun savedSearchDao(): SavedSearchDao
}
