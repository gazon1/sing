package com.singularity.todo.core.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.backup.AndroidBackupCodec
import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.sync.AndroidSyncScheduler
import com.singularity.todo.core.sync.RemoteConfigDao
import com.singularity.todo.core.sync.SyncScheduler
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.files.AndroidFileRevealer
import com.singularity.todo.core.files.AndroidFileSystem
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.notifications.AndroidNotificationPort
import com.singularity.todo.core.notifications.AndroidNotifier
import com.singularity.todo.core.notifications.NotificationPort
import com.singularity.todo.core.security.AndroidSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsDataStoreMigration
import com.singularity.todo.feature.pomodoro.AndroidPomodoroTimer
import com.singularity.todo.feature.pomodoro.PomodoroAlarmScheduler
import com.singularity.todo.feature.reminders.AlarmManagerReminderScheduler
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.settings.AiApiKeyMigration
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
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
    single { get<AppDatabase>().remoteConfigDao() }
    single { get<AppDatabase>().attachmentDao() }
    single { get<AppDatabase>().reminderDao() }
    single { get<AppDatabase>().checklistDao() }
    single { get<AppDatabase>().llmUsageDao() }
    single { get<AppDatabase>().profileDao() }
    single { get<AppDatabase>().agendaViewDao() }

    // ─── DataStore (split: user settings + state) ─────────────────────────

    // user_settings.preferences_pb — all mutable user-facing settings
    val userSettingsDs: DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            get<android.content.Context>().filesDir.resolve("user_settings.preferences_pb")
        }

    // state.preferences_pb — read-only flags (schema version, migration timestamps)
    val stateDs: DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            get<android.content.Context>().filesDir.resolve("state.preferences_pb")
        }

    // Legacy migration source — points to the old flat-key file.
    // Will be empty after migration; DataStore itself never writes back to it.
    val settingsLegacyDs: DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            get<android.content.Context>().filesDir.resolve("settings.preferences_pb")
        }

    // One-shot migration: v0 flat-key settings → v1 split + namespaced.
    // Also migrates the legacy AI API key from DataStore → SecureStorage.
    // Idempotent: skips if state.preferences_pb already has settings_schema_version.
    koinBridge {
        SettingsDataStoreMigration(settingsLegacyDs, userSettingsDs, stateDs).run()
        AiApiKeyMigration.run(settingsLegacyDs, get<SecureStoragePort>())
    }

    // Named DataStore bindings — used by SettingsRepository and migration.
    single(qualifier = named("user_settings")) { userSettingsDs }
    single(qualifier = named("state")) { stateDs }
    single(qualifier = named("settings")) { settingsLegacyDs }

    // Primary DataStore<Preferences> binding — what SettingsRepository consumes.
    single<DataStore<Preferences>> { userSettingsDs }

    // ─── Platform Ports ─────────────────────────────────────────────────

    single<SecureStoragePort> { AndroidSecureStorage(get()) }

    single<NotificationPort> { AndroidNotificationPort(get()) }

    single<FileSystem> { AndroidFileSystem(get()) }

    single<FileRevealer> { AndroidFileRevealer(get()) }

    single<BackupCodec> { AndroidBackupCodec() }

    single<String> { get<android.content.Context>().filesDir.absolutePath + "/backups" }

    // ─── Notifications ─────────────────────────────────────────────────

    // AndroidNotifier handles notification posting (channel, launch intent).
    // AlarmReceiver receives alarm broadcasts and calls notifier.post().
    single { AndroidNotifier(get()) }

    // ─── Reminder Scheduler ────────────────────────────────────────────

    single<ReminderScheduler> {
        AlarmManagerReminderScheduler(get(), get(), get())
    }

    // ─── Sync Scheduler ─────────────────────────────────────────────────

    single<SyncScheduler> { AndroidSyncScheduler(get()) }

    // ─── Pomodoro Timer ─────────────────────────────────────────────────

    single { PomodoroAlarmScheduler(get()) }

    // Use viewModel so AndroidPomodoroTimer (a ViewModel) is scoped correctly.
    // koinInject<PomodoroTimer>() in entry composables gets the scoped instance.
    // Constructor: (clock, taskRepository, alarmScheduler, config)
    viewModel { AndroidPomodoroTimer(get(), get(), get(), get()) }
}
