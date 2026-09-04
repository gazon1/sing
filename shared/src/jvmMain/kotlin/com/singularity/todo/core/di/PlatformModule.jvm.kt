package com.singularity.todo.core.di

import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.notifications.JvmNotificationPort
import com.singularity.todo.core.notifications.NotificationPort
import com.singularity.todo.core.security.JvmSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.feature.notes.NotesStore
import com.singularity.todo.feature.notes.RoomNotesStore
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * JVM/desktop platform bindings — Room 3 (same stack as Android, no extra native deps).
 *
 * Database layout mirrors `PlatformModule.android.kt`: one [AppDatabase] singleton, all
 * DAOs wired through Koin, [NotesStore] resolved to the common [RoomNotesStore] (no
 * platform-specific `JdbcNotesStore` — that class is gone).
 */
actual fun platformModule(): Module = module {
    // ─── Room Database ────────────────────────────────────────────────────

    single<AppDatabase> {
        val dbPath = System.getProperty("user.home") + "/.singularity-todo/singularity-todo.db"
        java.io.File(dbPath).parentFile?.mkdirs()
        AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    single { get<AppDatabase>().taskDao() }
    single { get<AppDatabase>().noteDao() }
    single { get<AppDatabase>().projectDao() }
    single { get<AppDatabase>().tagDao() }
    single { get<AppDatabase>().syncOutboxDao() }
    single { get<AppDatabase>().attachmentDao() }
    single { get<AppDatabase>().reminderDao() }

    // NotesStore — same Room-backed implementation on both platforms.
    // The previous JdbcNotesStore (raw JDBC + 500ms polling) was replaced to keep the
    // schema in lock-step with Android and to gain real invalidation-driven Flow.
    single<NotesStore> { RoomNotesStore(get(), get()) }

    // ─── DataStore ──────────────────────────────────────────────────────

    single<androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>> {
        object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            private val prefs = androidx.datastore.preferences.core.emptyPreferences()
            override val data: kotlinx.coroutines.flow.Flow<androidx.datastore.preferences.core.Preferences> =
                kotlinx.coroutines.flow.flowOf(prefs)
            override suspend fun updateData(
                transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences
            ): androidx.datastore.preferences.core.Preferences = transform(prefs)
        }
    }

    // ─── Platform Ports ────────────────────────────────────────────────

    single<SecureStoragePort> { JvmSecureStorage() }

    single<NotificationPort> { JvmNotificationPort() }

    single<FileSystem> { JvmFileSystem() }

    single<BackupCodec> { JvmBackupCodec() }
}