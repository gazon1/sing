package com.singularity.todo.core.di

import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.contract.wipeIfNotRoomManaged
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.JvmFileRevealer
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.notifications.JvmNotificationPort
import com.singularity.todo.core.notifications.NotificationPort
import com.singularity.todo.core.security.JvmSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.feature.pomodoro.JvmPomodoroTimer
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.core.sync.work.NoopSyncWorkScheduler
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * JVM/desktop platform bindings — Room 3 (same stack as Android, no extra native deps).
 *
 * Database layout mirrors `PlatformModule.android.kt`: one [AppDatabase] singleton, all
 * DAOs wired through Koin.
 */
actual fun platformModule(): Module = module {
    // ─── Room Database ────────────────────────────────────────────────────

    single<AppDatabase> {
        val dbPath = System.getProperty("user.home") + "/.singularity-todo/singularity-todo.db"
        java.io.File(dbPath).parentFile?.mkdirs()
        // On the JVM the path may contain a pre-Room hand-rolled SQLite file
        // (user_version=0, wrong column set). Wipe it before Room opens the connection
        // so fallbackToDestructiveMigration can recreate the schema cleanly.
        wipeIfNotRoomManaged(dbPath)
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
    single { get<AppDatabase>().llmUsageDao() }
    single { get<AppDatabase>().profileDao() }
    single { get<AppDatabase>().agendaViewDao() }

    // ─── DataStore ──────────────────────────────────────────────────────

    single<androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>> {
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(System.getProperty("user.home") + "/.singularity-todo/settings.preferences_pb").also {
                it.parentFile?.mkdirs()
            }
        }
    }

    // ─── Platform Ports ────────────────────────────────────────────────

    single<SecureStoragePort> { JvmSecureStorage() }

    single<NotificationPort> { JvmNotificationPort() }

    single<FileSystem> { JvmFileSystem() }

    single<FileRevealer> { JvmFileRevealer() }

    single<BackupCodec> { JvmBackupCodec() }

    single<String> { System.getProperty("user.home") + "/.singularity-todo/backups" }

    // ─── Pomodoro Timer ─────────────────────────────────────────────────

    factory<PomodoroTimer> { JvmPomodoroTimer() }

    // ─── Sync WorkManager scheduler ────────────────────────────────────

    // JVM: no-op stub — sync is not supported on desktop.
    single<SyncWorkScheduler> { NoopSyncWorkScheduler() }
}
