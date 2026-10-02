package com.singularity.todo

import android.app.Application
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.log.initLogging
import okio.Path.Companion.toPath
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.calendar_sync.sync.CalendarSyncOrchestrator
import com.singularity.todo.feature.gate.gateModule
import com.singularity.todo.update.AppUpdateGate
import com.singularity.todo.update.AppUpdatePrefs
import com.singularity.todo.update.DirectUrlUpdateStore
import com.singularity.todo.update.GooglePlayUpdateStore
import com.singularity.todo.update.RuStoreUpdateStore
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

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
class SingularityApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initLogging(
            isDebug = BuildConfig.DEBUG,
            version = appVersion().name,
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
        // Start the calendar sync orchestrator — launches the debounced collector coroutine.
        // Safe to call multiple times; subsequent calls are no-ops after the first.
        getKoin().get<CalendarSyncOrchestrator>().start()
    }
}
