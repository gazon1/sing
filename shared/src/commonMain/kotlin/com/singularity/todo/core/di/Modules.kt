package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.appearance.di.appearanceSettingsModule
import com.singularity.todo.core.settings.settingsContributorsModule
import com.singularity.todo.feature.agenda.agendaModule
import com.singularity.todo.feature.ai.di.aiSettingsModule
import com.singularity.todo.feature.calendar_sync.di.calendarSyncModule
import com.singularity.todo.core.work.backgroundWorkModule
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.profileModule
import com.singularity.todo.feature.proposals.proposalModule
import com.singularity.todo.feature.genui.di.genuiModule
import com.singularity.todo.feature.nav.deeplink.deepLinkModule
import com.singularity.todo.feature.whatsnew.di.whatsNewModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Logging module — Koin + Kermit integration.
 */
fun coreLoggingModule(): Module = module {
    factory { Logger.withTag("App") }
}

/**
 * Returns all domain-level bindings as a flat list of KOIN [Module]s.
 *
 * IMPORTANT: each call to this function returns a *list* so that every module
 * is passed directly to `modules()` at the root Koin scope. Using `includes()`
 * inside a `module {}` block creates child scopes whose bindings are NOT visible
 * to sibling modules at the parent level (Koin 4 scope isolation).
 *
 * @param fixedProfileId the profile every process built from this list works as.
 *   Null — the ordinary case — means the process uses the application's stored active
 *   profile. A non-null value means the process was told which profile to serve, and it
 *   must not open the store that holds the active one: the store takes a file lock, and
 *   the application is holding it.
 */
fun domainModule(fixedProfileId: ProfileId? = null): List<Module> = buildList {
    add(tasksModule())
    add(projectsModule())
    add(notesModule())
    add(tagsModule())
    add(searchModule())
    add(calendarModule())
    add(agendaModule())
    add(deepLinkModule())
    add(proposalModule())
    add(calendarSyncModule())
    add(backgroundWorkModule())
    // Profile bindings. Added to this list, NOT included: an included module creates a
    // child scope whose bindings the sibling modules above cannot see. The first attempt
    // at this arrangement included it, and the profile bindings vanished for every module
    // that needed them.
    add(profileModule(fixedProfileId))
    add(coreModule())
    add(syncModule())
    add(aiToolsModule())
    add(genuiModule())
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
