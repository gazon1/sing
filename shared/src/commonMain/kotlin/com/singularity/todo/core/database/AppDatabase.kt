package com.singularity.todo.core.database

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.ColumnTypeConverters
import com.singularity.todo.core.attachments.AttachmentConverters
import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.AttachmentEntity
import com.singularity.todo.core.sync.SyncOutboxDao
import com.singularity.todo.core.sync.SyncOutboxEntity

/**
 * Room database for Android.
 * Entity and DAO classes are defined in commonMain and are visible here.
 */
@Database(
    entities = [
        TaskEntity::class,
        TaskTagCrossRef::class,
        NoteEntity::class,
        ProjectEntity::class,
        TagEntity::class,
        SyncOutboxEntity::class,
        AttachmentEntity::class,
        TaskReminderEntity::class
    ],
    version = 4,
    exportSchema = true
)
@ConstructedBy(AppDatabaseCtor::class)
@ColumnTypeConverters(AttachmentConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun noteDao(): NoteDao
    abstract fun projectDao(): ProjectDao
    abstract fun tagDao(): TagDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun reminderDao(): ReminderDao
}
