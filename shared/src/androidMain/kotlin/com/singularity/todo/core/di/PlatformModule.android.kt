package com.singularity.todo.core.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.backup.AndroidBackupCodec
import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.files.AndroidFileSystem
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.notifications.AndroidNotificationPort
import com.singularity.todo.core.notifications.NotificationPort
import com.singularity.todo.core.security.AndroidSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android platform bindings.
 *
 * - Room [AppDatabase] is created via [AppDatabaseFactory] (single seam for Room).
 * - [SecureStoragePort] → [AndroidSecureStorage]
 * - [NotificationPort] → [AndroidNotificationPort]
 * - [FileSystem] → [AndroidFileSystem]
 * - [BackupCodec] → [AndroidBackupCodec]
 * - [androidx.datastore.core.DataStore] → application preferences DataStore
 */
actual fun platformModule(): Module = module {
    // ─── Room Database ────────────────────────────────────────────────────

    single<AppDatabase> {
        // Room 3 KMP resolves `name` as a relative file path, which on Android
        // lands in `/` (read-only) and triggers EROFS when it tries to create
        // `todo.db.lck`. Pass the absolute path under the app's databases dir.
        val dbPath = get<android.content.Context>().getDatabasePath("todo.db").absolutePath
        AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    single { get<AppDatabase>().taskDao() }
    single { get<AppDatabase>().noteDao() }
    single { get<AppDatabase>().projectDao() }
    single { get<AppDatabase>().tagDao() }
    single { get<AppDatabase>().syncOutboxDao() }
    single { get<AppDatabase>().attachmentDao() }
    single { get<AppDatabase>().reminderDao() }
    single { get<AppDatabase>().checklistDao() }

    // ─── DataStore ────────────────────────────────────────────────────────

    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create { get<android.content.Context>().filesDir.resolve("settings.preferences_pb") }
    }

    // ─── Platform Ports ─────────────────────────────────────────────────

    single<SecureStoragePort> { AndroidSecureStorage(get()) }

    single<NotificationPort> { AndroidNotificationPort(get()) }

    single<FileSystem> { AndroidFileSystem(get()) }

    single<BackupCodec> { AndroidBackupCodec() }

    // NotesStore: Room-backed on Android (separate from NotesRepository
    // because NotesViewModel directly injects NotesStore for editor operations).
    single<com.singularity.todo.feature.notes.NotesStore> {
        com.singularity.todo.feature.notes.RoomNotesStore(get(), get())
    }
}
