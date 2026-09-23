package com.singularity.todo.feature.calendar_sync.di

import com.singularity.todo.feature.calendar_sync.domain.repository.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncViewModel
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Calendar sync feature DI module.
 *
 * Registers [CalendarSyncViewModel].
 *
 * The [CalendarSyncRepository] binding is registered by the platform module BEFORE this
 * module is loaded (platform modules are registered before feature modules in Modules.kt):
 * - Android: [CalendarSyncSettingsRepository][com.singularity.todo.feature.calendar_sync.data.CalendarSyncSettingsRepository]
 * - JVM: [NoopCalendarSyncRepository][com.singularity.todo.feature.calendar_sync.data.NoopCalendarSyncRepository]
 *
 * Since Koin resolves `get<T>()` against already-registered bindings, the `get()`
 * call below resolves to the platform's singleton without a cycle.
 */
fun calendarSyncModule(): Module = module {
    single<CalendarSyncRepository> { get<CalendarSyncRepository>() }
    single { CalendarSyncViewModel(get(), get(), get()) }
}
