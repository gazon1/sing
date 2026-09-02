package com.singularity.todo.core.di

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.settings.SettingsRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

@OptIn(org.koin.core.annotation.KoinExperimentalAPI::class)
fun sharedModule(database: AppDatabase, settingsRepository: SettingsRepository): Module = module {
    // Database
    single<AppDatabase> { database }
    single { database.taskDao() }
    single { database.noteDao() }
    single { database.projectDao() }
    single { database.tagDao() }

    // Settings
    single<SettingsRepository> { settingsRepository }
}
