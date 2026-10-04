package com.singularity.todo.core.di

import android.content.Context
import android.os.VibratorManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.backup.AndroidBackupCodec
import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.files.AndroidFileRevealer
import com.singularity.todo.core.files.AndroidFileSourceFactory
import com.singularity.todo.core.files.AndroidFileSharePort
import com.singularity.todo.core.files.AndroidFileSystem
import com.singularity.todo.core.files.AndroidSharePort
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.FileSharePort
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.SharePort
import com.singularity.todo.core.log.LogBundleExporter
import com.singularity.todo.core.observability.AndroidCrashReportingPort
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.notifications.AndroidNotificationPort
import com.singularity.todo.core.notifications.AndroidNotifier
import com.singularity.todo.core.notifications.NotificationPort
import com.singularity.todo.core.platform.haptics.AndroidHaptic
import com.singularity.todo.core.platform.haptics.Haptic
import com.singularity.todo.core.security.AndroidSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsDataStoreMigration
import com.singularity.todo.core.sync.AndroidSyncPeriodicTrigger
import com.singularity.todo.core.sync.SyncPeriodicTrigger
import com.singularity.todo.core.sync.work.AndroidSyncWorkScheduler
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import com.singularity.todo.feature.calendar_sync.data.AndroidCalendarAppQueries
import com.singularity.todo.feature.calendar_sync.data.AndroidCalendarProvider
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncSettingsRepositoryImpl
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.work.AndroidCalendarSyncWorkScheduler
import com.singularity.todo.feature.calendar_sync.work.CalendarSyncWorkScheduler
import com.singularity.todo.feature.pomodoro.AndroidPomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.AndroidPomodoroTimer
import com.singularity.todo.feature.pomodoro.PomodoroAlarmScheduler
import com.singularity.todo.feature.pomodoro.PomodoroConfig
import com.singularity.todo.feature.pomodoro.PomodoroScheduler
import com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.AlarmManagerReminderScheduler
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.settings.AiApiKeyMigration
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
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
 *
 * The [android.content.Context] is registered in Koin via `androidContext()` in
 * [SingularityApp.onCreate], so `get<Context>()` works inside `single { }` factory
 * bodies. The `PreferenceDataStoreFactory.create { }` lambda takes a `() -> File`
 * (NOT a Koin scope) — we close over a `Context` captured at binding time.
 *
 * Each DataStore is registered TWICE: once with a qualifier (`"user_settings"`,
 * `"state"`, etc.) and once as the un-qualified `DataStore<Preferences>` that
 * `coreModule.DataStoreSettingsRepository.get()` looks up. Because Koin's
 * `single` factory body is a closure, both registrations use the same
 * builder code — and the un-qualified binding delegates to the named one
 * to keep them as the *same* DataStore instance.
 */
actual fun platformModule(): Module = module {
    // ─── Room Database ────────────────────────────────────────────────────

    single<AppDatabase> {
        // Room 3 KMP resolves `name` as a relative file path, which on Android
        // lands in `/` (read-only) and triggers EROFS when it tries to create
        // `todo.db.lck`. Pass the absolute path under the app's databases dir.
        val context = get<Context>()
        val dbPath = context.getDatabasePath("todo.db").absolutePath
        AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    single { get<AppDatabase>().taskDao() }
    single { get<AppDatabase>().noteDao() }
    single { get<AppDatabase>().projectDao() }
    single { get<AppDatabase>().tagDao() }
    single { get<AppDatabase>().tagGroupDao() }
    single { get<AppDatabase>().projectInheritedTagGroupDao() }
    single { get<AppDatabase>().syncOutboxDao() }
    single { get<AppDatabase>().remoteConfigDao() }
    single { get<AppDatabase>().remoteConfigCacheDao() }
    single { get<AppDatabase>().attachmentDao() }
    single { get<AppDatabase>().reminderDao() }
    single { get<AppDatabase>().projectReminderDao() }
    single { get<AppDatabase>().checklistDao() }
    single { get<AppDatabase>().llmUsageDao() }
    single { get<AppDatabase>().profileDao() }
    single { get<AppDatabase>().agendaViewDao() }
    single { get<AppDatabase>().calendarSyncTaskMapDao() }
    single { get<AppDatabase>().savedSearchDao() }
    single { get<AppDatabase>().timeEntryDao() }
    single { get<AppDatabase>().proposalDao() }
    single { get<AppDatabase>().proposalItemDao() }

    // ─── Platform Ports (registered early — needed by koinBridge migrations) ────

    single<SecureStoragePort> { AndroidSecureStorage(get()) }

    // ─── DataStore (split: user settings + state) ─────────────────────────

    // user_settings.preferences_pb — all mutable user-facing settings
    single<DataStore<Preferences>>(qualifier = named("user_settings")) {
        val context = get<Context>()
        PreferenceDataStoreFactory.create {
            context.filesDir.resolve("user_settings.preferences_pb")
        }
    }

    // state.preferences_pb — read-only flags (schema version, migration timestamps)
    single<DataStore<Preferences>>(qualifier = named("state")) {
        val context = get<Context>()
        PreferenceDataStoreFactory.create {
            context.filesDir.resolve("state.preferences_pb")
        }
    }

    // Legacy migration source — points to the old flat-key file.
    // Will be empty after migration; DataStore itself never writes back to it.
    single<DataStore<Preferences>>(qualifier = named("settings")) {
        val context = get<Context>()
        PreferenceDataStoreFactory.create {
            context.filesDir.resolve("settings.preferences_pb")
        }
    }

    // Primary DataStore<Preferences> binding — what SettingsRepository consumes.
    // Delegates to the named `user_settings` binding so we keep one instance.
    single<DataStore<Preferences>> {
        get<DataStore<Preferences>>(qualifier = named("user_settings"))
    }

    // One-shot migration: v0 flat-key settings → v1 split + namespaced.
    // Bound as a single so we can access `get<SecureStoragePort>()` inside Koin scope.
    single {
        val secureStorage = get<SecureStoragePort>()
        val legacyDs = get<DataStore<Preferences>>(qualifier = named("settings"))
        val userSettingsDs = get<DataStore<Preferences>>(qualifier = named("user_settings"))
        val stateDs = get<DataStore<Preferences>>(qualifier = named("state"))
        val migration = SettingsDataStoreMigration(
            legacyDataStore = legacyDs,
            userSettingsDataStore = userSettingsDs,
            stateDataStore = stateDs,
        )
        migration.runBlockingForStartup()
        koinBridge { AiApiKeyMigration.run(legacyDs, secureStorage) }
        Unit
    }

    // Separate DataStore for calendar sync settings (isolates calendar feature from main settings)
    single<DataStore<Preferences>>(qualifier = named("calendar_sync")) {
        val context = get<Context>()
        PreferenceDataStoreFactory.create {
            context.filesDir.resolve("calendar_sync.preferences_pb")
        }
    }

    // ─── Platform Ports ─────────────────────────────────────────────────

    single<NotificationPort> { AndroidNotificationPort(get()) }

    single<FileSystem> { AndroidFileSystem(get()) }

    single<FileRevealer> { AndroidFileRevealer(get()) }

    single<SharePort> { AndroidSharePort(get()) }

    single<FileSharePort> { AndroidFileSharePort(get()) }

    single<FileSourceFactory> { AndroidFileSourceFactory(get()) }

    single<BackupCodec> { AndroidBackupCodec() }

    single<CrashReportingPort> { AndroidCrashReportingPort(get()) }

    single<String> { get<Context>().filesDir.absolutePath + "/backups" }

    single<String> { get<Context>().filesDir.absolutePath + "/logs" }

    single { LogBundleExporter(get(), get(), get()) }

    // ─── Notifications ─────────────────────────────────────────────────

    // AndroidNotifier handles notification posting (channel, launch intent).
    // AlarmReceiver receives alarm broadcasts and calls notifier.post().
    single { AndroidNotifier(get()) }

    // ─── Reminder Scheduler ────────────────────────────────────────────

    single<ReminderScheduler> {
        AlarmManagerReminderScheduler(get(), get(), get())
    }

    // ─── Sync Scheduler ─────────────────────────────────────────────────

    // Periodic sync on Android: WorkManager periodic work.
    single<SyncPeriodicTrigger> { AndroidSyncPeriodicTrigger(get()) }

    // WorkManager scheduler (reacts to auth session changes, survives process death)
    single<SyncWorkScheduler> { AndroidSyncWorkScheduler(get()) }

    // ─── Pomodoro Timer ─────────────────────────────────────────────────

    single { PomodoroConfig() }
    single<PomodoroScheduler> { PomodoroAlarmScheduler(get()) }
    // Bound to the interface, not the concrete class: registering the implementation
    // type made every koinInject<PomodoroTaskListProvider>() fail with
    // NoDefinitionFoundException, which crashed the Pomodoro tab on Android while
    // Desktop worked. The JVM module binds the interface — mirror that.
    single<PomodoroTaskListProvider> { AndroidPomodoroTaskListProvider(get(), createBackgroundScope()) }
    // AndroidPomodoroTimer no longer extends ViewModel — use factory so each injection
    // point gets its own instance with the CoroutineScope from coreModule.
    single<CoroutineScope> { createBackgroundScope() }
    factory<PomodoroTimer> { AndroidPomodoroTimer(get(), get(), get(), get(), get(), get(), get()) }

    // ─── Calendar Sync ────────────────────────────────────────────────

    // Calendar sync settings repository (separate DataStore for isolation)
    single<CalendarSyncRepository> {
        CalendarSyncSettingsRepositoryImpl(get(qualifier = named("calendar_sync")))
    }

    // Calendar app picker — queries PackageManager for installed calendar apps
    single<com.singularity.todo.feature.calendar_sync.domain.port.CalendarAppQueries> {
        AndroidCalendarAppQueries(get())
    }

    // Android calendar provider (ContentResolver-backed).
    // accountNameProvider is a lambda so it re-samples scopedUserId on every call (profile-switch safe).
    // syncRepo is read at each operation to get the current target app package.
    single<CalendarProviderPort> {
        AndroidCalendarProvider(
            context = get(),
            accountNameProvider = { get<ProfileAwareCurrentUser>().scopedUserId.value.value },
            syncRepo = get(),
        )
    }

    // WorkManager scheduler for calendar sync
    single<CalendarSyncWorkScheduler> {
        AndroidCalendarSyncWorkScheduler(get())
    }

    // ─── Haptics ─────────────────────────────────────────────────────────
    @Suppress("NewApi") // VibratorManager requires API 31
    single<Haptic> {
        val context = get<Context>()
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        AndroidHaptic.of(vibratorManager)
    }
}
