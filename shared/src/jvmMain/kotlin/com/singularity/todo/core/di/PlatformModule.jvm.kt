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
import com.singularity.todo.core.settings.SettingsDataStoreMigration
import com.singularity.todo.feature.pomodoro.JvmPomodoroTimer
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.feature.reminders.JvmReminderScheduler
import com.singularity.todo.feature.reminders.ReminderScheduler
import org.koin.core.module.Module
import org.koin.core.qualifier.named
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

    // ─── DataStore (split: user settings + state) ─────────────────────────

    // user_settings.preferences_pb — all mutable user-facing settings
    val userSettingsDs: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> =
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(System.getProperty("user.home") + "/.singularity-todo/user_settings.preferences_pb").also {
                it.parentFile?.mkdirs()
            }
        }

    // state.preferences_pb — read-only flags (schema version, migration timestamps)
    val stateDs: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> =
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(System.getProperty("user.home") + "/.singularity-todo/state.preferences_pb").also {
                it.parentFile?.mkdirs()
            }
        }

    // Legacy migration source — points to the old flat-key file.
    // Will be empty after migration; DataStore itself never writes back to it.
    val settingsLegacyDs: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> =
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(System.getProperty("user.home") + "/.singularity-todo/settings.preferences_pb").also {
                it.parentFile?.mkdirs()
            }
        }

    // One-shot migration: v0 flat-key settings → v1 split + namespaced.
    // Idempotent: skips if state.preferences_pb already has settings_schema_version.
    koinBridge {
        SettingsDataStoreMigration(settingsLegacyDs, userSettingsDs, stateDs).run()
    }

    // Named DataStore bindings — used by SettingsRepository and migration.
    single(qualifier = named("user_settings")) { userSettingsDs }
    single(qualifier = named("state")) { stateDs }
    single(qualifier = named("settings")) { settingsLegacyDs }

    // Primary DataStore<Preferences> binding — what SettingsRepository consumes.
    single<androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>> { userSettingsDs }

    // ─── Platform Ports ────────────────────────────────────────────────

    single<SecureStoragePort> { JvmSecureStorage() }

    single<NotificationPort> { JvmNotificationPort() }

    single<FileSystem> { JvmFileSystem() }

    single<FileRevealer> { JvmFileRevealer() }

    single<BackupCodec> { JvmBackupCodec() }

    single<String> { System.getProperty("user.home") + "/.singularity-todo/backups" }

    // ─── Pomodoro Timer ─────────────────────────────────────────────────

    factory<PomodoroTimer> { JvmPomodoroTimer() }

    // ─── Reminder Scheduler ────────────────────────────────────────────

    single<ReminderScheduler> { JvmReminderScheduler() }
}
