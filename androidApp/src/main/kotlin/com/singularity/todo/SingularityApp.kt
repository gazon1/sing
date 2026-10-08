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
import com.singularity.todo.core.observability.crashReportingFailureHandler
import com.singularity.todo.core.version.appVersion
import org.koin.core.module.Module
import com.singularity.todo.feature.calendar_sync.sync.CalendarSyncOrchestrator
import com.singularity.todo.feature.calendar_sync.work.GOOGLE_SYNC_INTERVAL_MINUTES
import com.singularity.todo.feature.calendar_sync.work.GoogleSyncPeriodicTrigger
import com.singularity.todo.feature.gate.gateModule
import com.singularity.todo.core.work.BackgroundWorkBootstrapper
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
import kotlin.time.Duration.Companion.minutes

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
 *
 * ## No vendor crash reporting here, on purpose
 *
 * This class used to implement the proprietary `HasTracerConfiguration` and carry
 * AppTracer's settings, which put a non-OSI SDK in a module published under Apache-2.0.
 * The pro build gets `ProSingularityApp` instead — a subclass in
 * `androidApp/src/pro/kotlin`, compiled only under `-PwithPro=true` and named through the
 * `appClass` manifest placeholder. The free build names this class, which carries no vendor
 * types in its signature at all.
 *
 * `open` is the price of that split, and it is worth stating: a class in an Apache-2.0
 * module that exists only to be subclassed by the pro build is a seam where the licence
 * boundary can quietly erode. This is the only one.
 */
open class SingularityApp : Application() {

    /**
     * Extra Koin modules contributed by the build configuration.
     *
     * Empty in the free build. `ProSingularityApp` overrides it to return the
     * `pro` catalogue's observability module, which re-binds `CrashReportingPort` to
     * the vendor-backed implementation. Modules listed here are loaded **last**, so a
     * binding here wins over `platformModule()`.
     *
     * The return type is Koin's `Module`, which is Apache-2.0 — so this hook adds a
     * capability to the free build without adding a dependency to it. That distinction
     * is the whole reason the extension point lives here rather than as a hard
     * reference to `proObservabilityModule()`: a direct call would not compile without
     * `:pro` on the classpath, and the free build has to stand alone.
     *
     * When `TEST_AUTH_BYPASS` is set, this also loads [com.singularity.todo.test.stubs.testAuthModule],
     * which overrides `AuthRepository`, `AuthGateway`, and `SupabaseClientProvider` with
     * in-memory stubs that produce a signed-in session immediately — no Supabase server
     * or network call is needed. This is for E2E smoke tests only.
     */
    open fun extraKoinModules(): List<Module> {
        if (BuildConfig.TEST_AUTH_BYPASS) {
            return listOf(com.singularity.todo.test.stubs.testAuthModule())
        }
        return emptyList()
    }

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
        // Configuration-specific modules are loaded *after* `startKoin`, not inside the
        // `modules { }` call, and that placement is deliberate.
        //
        // Adding a variable to the `modules { }` argument makes the whole set
        // dynamically computed, and the Koin compiler plugin then drops to runtime-only
        // graph verification (KOIN-W003) for this entry point. That warning is already
        // present here — it predates this hook, verified on unmodified HEAD — so this
        // change does not introduce it. Feeding one more variable into the same call
        // would still be making a known-weak spot weaker, and `loadModules(allowOverride
        // = true)` says exactly the same thing with a narrower blast radius: only these
        // modules can be overridden, not the platform graph.
        if (extraKoinModules().isNotEmpty()) {
            getKoin().loadModules(extraKoinModules(), allowOverride = true)
        }
        // Device/build context for every subsequent crash report. debugInfo() had no callers
        // and the unwired-surface audit cannot see it (that script only matches
        // uppercase-initial names), so it was dead code exactly when it became useful.
        getKoin().get<CrashReportingPort>().addBreadcrumb(debugInfo(version, BuildConfig.DEBUG))
        // No install step: every background scope composes its own failure handler from the
        // CrashReportingPort it is constructed with, so there is nothing to wire up here and
        // nothing for a later call to overwrite. The Koin-resolved port reaches the scopes
        // that need it through the bindings that build them.
        // Installed after startKoin so the Koin graph is available to the handler if it
        // needs to report — and after the SDK, which initializes in the
        // attachBaseContext..onCreate window, so `previous` is AppTracer's own handler.
        installCrashLogTailFlush()
        // Start the calendar sync orchestrator — launches the debounced collector coroutine.
        // Safe to call multiple times; subsequent calls are no-ops after the first.
        getKoin().get<CalendarSyncOrchestrator>().start()
        // Arm Google Calendar sync: periodic WorkManager work running GoogleSyncWorker.
        // Started here for the same reason the orchestrator above is — a background driver
        // that nothing calls is a feature that never runs. `start` is idempotent (unique
        // work, KEEP), so there is no "have I started" flag to own, and the worker
        // re-reads `isConfigured` on every run, so an account connected after launch is
        // picked up without a restart.
        getKoin().get<GoogleSyncPeriodicTrigger>()
            .start(GOOGLE_SYNC_INTERVAL_MINUTES.minutes)
        // Seed the default 'Personal' profile on first launch (idempotent). The
        // profile switcher and the saved-view copy-to-profile picker list
        // ProfileRepository rows; without a seeded row both start empty on a
        // fresh install because nothing else writes to the profiles table.
        createBackgroundScope(
            crashReportingFailureHandler(getKoin().get()),
        ).launch {
            getKoin().get<ProfileBootstrapper>().run()
            // Arm the maintenance jobs (llm_usage retention). Separate from the profile
            // bootstrap above rather than folded into it: they are unrelated concerns that
            // happen to share a launch hook, and merging them would make the profile
            // seeding harder to read than either part is on its own.
            getKoin().get<BackgroundWorkBootstrapper>().run()
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
