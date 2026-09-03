package com.singularity.todo.core.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.singularity.todo.core.backup.AndroidBackupCodec
import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.database.AppDatabase
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
 * - Room [AppDatabase] is created here and provides DAOs directly (no reflection)
 * - [SecureStoragePort] → [AndroidSecureStorage]
 * - [NotificationPort] → [AndroidNotificationPort]
 * - [FileSystem] → [AndroidFileSystem]
 * - [BackupCodec] → [AndroidBackupCodec]
 * - [androidx.datastore.core.DataStore] → application preferences DataStore
 */
actual fun platformModule(): Module = module {
    // ─── Room Database ────────────────────────────────────────────────────

    single<AppDatabase> {
        Room.databaseBuilder<AppDatabase>(name = "todo.db")
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    single { get<AppDatabase>().taskDao() }
    single { get<AppDatabase>().noteDao() }
    single { get<AppDatabase>().projectDao() }
    single { get<AppDatabase>().tagDao() }
    single { get<AppDatabase>().syncOutboxDao() }
    single { get<AppDatabase>().attachmentDao() }
    single { get<AppDatabase>().reminderDao() }

    // ─── DataStore ────────────────────────────────────────────────────────

    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create { get<android.content.Context>().filesDir.resolve("settings.preferences_pb") }
    }

    // ─── Platform Ports ─────────────────────────────────────────────────

    single<SecureStoragePort> { AndroidSecureStorage(get()) }

    single<NotificationPort> { AndroidNotificationPort(get()) }

    single<FileSystem> { AndroidFileSystem(get()) }

    single<BackupCodec> { AndroidBackupCodec() }
}
