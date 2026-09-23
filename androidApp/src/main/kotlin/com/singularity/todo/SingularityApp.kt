package com.singularity.todo

import android.app.Application
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.log.initLogging
import com.singularity.todo.feature.calendar_sync.sync.CalendarSyncOrchestrator
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Application class — the canonical place to start Koin.
 * Called exactly once per process lifetime, before any Activity or Service.
 * No guard needed unlike when startKoin lives in Activity.onCreate().
 */
class SingularityApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initLogging(BuildConfig.DEBUG, version = "0.1.0")
        startKoin {
            androidContext(this@SingularityApp)
            modules(
                platformModule(),
                coreLoggingModule(),
                *domainModule().toTypedArray(),
            )
        }
        // Start the calendar sync orchestrator — launches the debounced collector coroutine.
        // Safe to call multiple times; subsequent calls are no-ops after the first.
        getKoin().get<CalendarSyncOrchestrator>().start()
    }
}
