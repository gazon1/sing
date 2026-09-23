package com.singularity.todo.core.appearance.di

import com.singularity.todo.core.appearance.AppearanceSettingsContributor
import com.singularity.todo.core.appearance.AppearanceSettingsStore
import com.singularity.todo.core.appearance.AppearanceSettingsRepository
import com.singularity.todo.core.appearance.DataStoreAppearanceSettingsRepository
import com.singularity.todo.core.settings.SettingsContributor
import org.koin.dsl.module

/**
 * DI module for appearance settings (core/appearance).
 *
 * Provides [AppearanceSettingsRepository] and registers [AppearanceSettingsContributor]
 * as a [SettingsContributor].
 */
fun appearanceSettingsModule(): org.koin.core.module.Module = module {
    // Repository — backed by the shared DataStore
    single<AppearanceSettingsRepository> { DataStoreAppearanceSettingsRepository(get()) }

    // Store
    single { AppearanceSettingsStore(get<AppearanceSettingsRepository>()) }

    // Contributor — registered as SettingsContributor so SettingsViewModel can discover it
    single<SettingsContributor<*, *>> { AppearanceSettingsContributor(get()) }
}
