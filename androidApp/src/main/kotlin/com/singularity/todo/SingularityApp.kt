package com.singularity.todo

import android.app.Application
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.log.initLogging
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.calendar_sync.sync.CalendarSyncOrchestrator
import com.singularity.todo.feature.gate.gateModule
import com.singularity.todo.update.AppUpdateGate
import com.singularity.todo.update.AppUpdatePrefs
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

private const val PLAY_STORE_URI = "market://details?id=com.singularity.todo"

/**
 * Koin module for Play In-App Update bindings.
 * Must be included after [gateModule] so that [RemoteConfigPort] is available.
 */
private fun appUpdateModule() = module {
    single { AppUpdateManagerFactory.create(get<android.content.Context>()) }
    single { AppUpdatePrefs.create(get()) }
    single { AppUpdateGate(get(), get(), get()) }
}

/**
 * Application class — the canonical place to start Koin.
 * Called exactly once per process lifetime, before any Activity or Service.
 * No guard needed unlike when startKoin lives in Activity.onCreate().
 */
class SingularityApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initLogging(BuildConfig.DEBUG, version = appVersion().name)
        startKoin {
            androidContext(this@SingularityApp)
            modules(
                platformModule(),
                coreLoggingModule(),
                *domainModule().toTypedArray(),
                gateModule(PLAY_STORE_URI),
                appUpdateModule(),
            )
        }
        // Start the calendar sync orchestrator — launches the debounced collector coroutine.
        // Safe to call multiple times; subsequent calls are no-ops after the first.
        getKoin().get<CalendarSyncOrchestrator>().start()
    }
}
