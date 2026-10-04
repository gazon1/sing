package com.singularity.todo

import android.app.Application
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.log.debugInfo
import com.singularity.todo.core.log.flushLogs
import com.singularity.todo.core.log.initLogging
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.calendar_sync.sync.CalendarSyncOrchestrator
import com.singularity.todo.feature.gate.gateModule
import com.singularity.todo.feature.profile.ProfileBootstrapper
import com.singularity.todo.update.AppUpdateGate
import com.singularity.todo.update.AppUpdatePrefs
import com.singularity.todo.update.DirectUrlUpdateStore
import com.singularity.todo.update.GooglePlayUpdateStore
import com.singularity.todo.update.RuStoreUpdateStore
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import ru.ok.tracer.HasTracerConfiguration
import ru.ok.tracer.TracerConfiguration
import ru.ok.tracer.crash.report.CrashFreeConfiguration
import ru.ok.tracer.crash.report.CrashReportConfiguration

private const val PLAY_STORE_URI = "market://details?id=com.singularity.todo"

/**
 * Koin module for app-update store bindings.
 * Must be included after [gateModule] so that [RemoteConfigPort] is available.
 */
private fun appUpdateModule() = module {
    // AppUpdateManager — only needed for Google Play
    single { AppUpdateManagerFactory.create(get<android.content.Context>()) }

    // Shared preferences for cooldown tracking
    single { AppUpdatePrefs.create(get()) }

    // Update store implementations
    single { GooglePlayUpdateStore(get()) }
    single { RuStoreUpdateStore(get()) }
    single { DirectUrlUpdateStore(get(), "https://play.google.com/store/apps/details?id=com.singularity.todo") }

    // Coordinator
    single {
        AppUpdateGate(
            googlePlayStore = get(),
            ruStore = get(),
            directUrlStore = get(),
            remoteConfigPort = get(),
            prefs = get(),
        )
    }
}

/**
 * Application class — the canonical place to start Koin.
 * Called exactly once per process lifetime, before any Activity or Service.
 * No guard needed unlike when startKoin lives in Activity.onCreate().
 */
class SingularityApp : Application(), HasTracerConfiguration {

    /**
     * AppTracer plugin configuration.
     *
     * Read by the SDK exactly once per process, after `attachBaseContext` and **before**
     * [onCreate]. Nothing here may touch state that [onCreate] initialises — the context is
     * available, Koin and logging are not.
     *
     * `setSendAnr` and `setExperimentalNonFatalRateLimitEnabled` are stated explicitly
     * rather than left implicit: both are the vendor's recommended settings, and a
     * reviewer should be able to see that a deliberate choice was made rather than a
     * default inherited. Crash-free is left at its default (enabled) because its only
     * meaningful switch is `setEnabled`, and a disabled crash-free metric is worse than
     * none — it would read as a healthy number.
     */
    override val tracerConfiguration: List<TracerConfiguration>
        get() = listOf(
            CrashReportConfiguration.build {
                setSendAnr(true)
                setExperimentalNonFatalRateLimitEnabled(true)
            },
            CrashFreeConfiguration.build { /* defaults: enabled */ },
        )

    override fun onCreate() {
        super.onCreate()
        val version = appVersion().name
        initLogging(
            isDebug = BuildConfig.DEBUG,
            version = version,
            logDirectory = filesDir.absolutePath.toPath() / "logs",
        )
        startKoin {
            androidContext(this@SingularityApp)
            modules(
                listOf(platformModule(), coreLoggingModule()) +
                    domainModule() +
                    listOf(gateModule(PLAY_STORE_URI), appUpdateModule()),
            )
        }
        // Device/build context for every subsequent crash report. debugInfo() had no callers
        // and the unwired-surface audit cannot see it (that script only matches
        // uppercase-initial names), so it was dead code exactly when it became useful.
        getKoin().get<CrashReportingPort>().addBreadcrumb(debugInfo(version, BuildConfig.DEBUG))
        // Installed after startKoin so the Koin graph is available to the handler if it
        // needs to report — and after the SDK, which initializes in the
        // attachBaseContext..onCreate window, so `previous` is AppTracer's own handler.
        installCrashLogTailFlush()
        // Start the calendar sync orchestrator — launches the debounced collector coroutine.
        // Safe to call multiple times; subsequent calls are no-ops after the first.
        getKoin().get<CalendarSyncOrchestrator>().start()
        // Seed the default 'Personal' profile on first launch (idempotent). The
        // profile switcher and the saved-view copy-to-profile picker list
        // ProfileRepository rows; without a seeded row both start empty on a
        // fresh install because nothing else writes to the profiles table.
        createBackgroundScope().launch {
            getKoin().get<ProfileBootstrapper>().run()
        }
    }

    /**
     * Drains the Kermit file writer when the process is dying from an uncaught exception.
     *
     * `FileLogWriter` writes are already flushed to the sink individually; what is still
     * pending is the single-threaded write queue, which the OS will never drain. This
     * reclaims those last few lines — the operations immediately before the crash, which
     * are the ones worth reading.
     *
     * The previously installed handler is always invoked. Android initializes a process as
     * ContentProvider.onCreate → attachBaseContext → onCreate, and AppTracer reads its
     * configuration in the attachBaseContext..onCreate window, so it installs first and
     * `previous` is its handler; delegating is what lets the crash actually be uploaded.
     * Ordering is confirmed on-device rather than assumed.
     */
    private fun installCrashLogTailFlush() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            flushLogs()
            previous?.uncaughtException(thread, error)
        }
    }
}
