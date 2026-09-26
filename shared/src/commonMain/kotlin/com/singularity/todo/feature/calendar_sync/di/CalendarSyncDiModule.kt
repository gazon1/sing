package com.singularity.todo.feature.calendar_sync.di

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncViewModel
import com.singularity.todo.feature.calendar_sync.sync.CalendarSyncOrchestrator
import com.singularity.todo.feature.calendar_sync.sync.DirtyHashProvider
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Calendar sync feature DI module.
 *
 * Registers [CalendarSyncViewModel], [CalendarSyncOrchestrator], and [DirtyHashProvider].
 *
 * The [CalendarSyncOrchestrator] is a singleton that must be started once
 * at app startup via [CalendarSyncOrchestrator.start]. On Android, call:
 *
 * ```
 * val orchestrator: CalendarSyncOrchestrator by koinLazy()
 * orchestrator.start()
 * ```
 *
 * in [com.singularity.todo.SingularityApp.onCreate] after `startKoin`.
 *
 * The [CalendarSyncRepository] binding is registered by the platform module BEFORE this
 * module is loaded (platform modules are registered before feature modules in Modules.kt):
 * - Android: [CalendarSyncSettingsRepository][com.singularity.todo.feature.calendar_sync.data.CalendarSyncSettingsRepository]
 * - JVM: [NoopCalendarSyncRepository][com.singularity.todo.feature.calendar_sync.data.NoopCalendarSyncRepository]
 *
 * [CalendarAppQueries] is also registered by the platform module:
 * - Android: [AndroidCalendarAppQueries][com.singularity.todo.feature.calendar_sync.data.AndroidCalendarAppQueries]
 * - JVM: [JvmCalendarAppQueries][com.singularity.todo.feature.calendar_sync.data.JvmCalendarAppQueries]
 */
fun calendarSyncModule(): Module = module {
    // DirtyHashProvider — depends only on TaskRepository + CalendarSyncRepository
    single { DirtyHashProvider(get(), get()) }

    // CalendarSyncOrchestrator — singleton. Call .start() once at app startup.
    single { CalendarSyncOrchestrator(get(), createBackgroundScope(), get()) }

    // ViewModel bound to navigation lifecycle — cancelled when the screen leaves the back stack.
    // 6-arg canonical ctor: syncRepo, calendarProvider, scheduler, appQueries, orchestrator, scope
    // (scope = AutoCloseableCoroutineScope for lifecycle-aware cancellation).
    // viewModel (not factory): the injected AutoCloseableCoroutineScope must be closed
    // when the VM is cleared — a factory registration would leak it.
    viewModel<CalendarSyncViewModel> {
        CalendarSyncViewModel(get(), get(), get(), get(), get(), AutoCloseableCoroutineScope())
    }
}
