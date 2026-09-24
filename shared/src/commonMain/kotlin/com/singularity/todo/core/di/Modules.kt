package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.log.LoggerHolder
import com.singularity.todo.feature.agenda.agendaModule
import com.singularity.todo.feature.ai.di.aiSettingsModule
import com.singularity.todo.feature.calendar_sync.di.calendarSyncModule
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.profile.ProfileRepository
import com.singularity.todo.feature.profile.ProfileRepositoryImpl
import com.singularity.todo.core.appearance.di.appearanceSettingsModule
import com.singularity.todo.core.settings.settingsContributorsModule
import com.singularity.todo.feature.whatsnew.di.whatsNewModule
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Logging module — Kermit + KOIN integration.
 */
fun coreLoggingModule(): Module = module {
    factory { Logger.withTag("App") }
    singleOf(::LoggerHolder)
}

/**
 * Returns all domain-level bindings as a flat list of KOIN [Module]s.
 *
 * IMPORTANT: each call to this function returns a *list* so that every module
 * is passed directly to `modules()` at the root Koin scope. Using `includes()`
 * inside a `module {}` block creates child scopes whose bindings are NOT visible
 * to sibling modules at the parent level (Koin 4 scope isolation).
 *
 * Profile bindings (ProfileRepository, ProfileAwareCurrentUser) are inlined here
 * as direct `single {}` calls rather than via a separate `profileModule()` to
 * ensure they are registered at root scope.
 */
fun domainModule(): List<Module> = buildList {
    add(tasksModule())
    add(projectsModule())
    add(notesModule())
    add(tagsModule())
    add(calendarModule())
    add(agendaModule())
    add(calendarSyncModule())
    // Profile bindings — inlined here (NOT via profileModule()) so they land at root scope.
    // profileModule() wrapped its bindings in module {} which created a child scope.
    add(
        module {
        single<ProfileRepository> { ProfileRepositoryImpl(get(), get(), get(), createBackgroundScope()) }
        single { ProfileAwareCurrentUser(get(), get(), createBackgroundScope()) }
        factory { com.singularity.todo.feature.profile.ProfileBootstrapper(get()) }
    }
    )
    add(coreModule())
    add(aiToolsModule())
    add(aiSettingsModule())
    add(settingsContributorsModule())
    add(appearanceSettingsModule())
    add(whatsNewModule())
}

/**
 * AI tools, GenUI, and AI use cases — platform-specific.
 * JVM: real Koog executor + all AI tools.
 * Android: stub (AI features disabled).
 */
expect fun aiToolsModule(): Module
