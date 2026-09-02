package com.singularity.todo.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.singularity.todo.core.sync.SyncOutboxEntity
import com.singularity.todo.core.sync.SyncOutboxDao

@Database(
    entities = [
        TaskEntity::class,
        TaskTagCrossRef::class,
        NoteEntity::class,
        ProjectEntity::class,
        TagEntity::class,
        SyncOutboxEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun noteDao(): NoteDao
    abstract fun projectDao(): ProjectDao
    abstract fun tagDao(): TagDao
    abstract fun syncOutboxDao(): SyncOutboxDao
}
