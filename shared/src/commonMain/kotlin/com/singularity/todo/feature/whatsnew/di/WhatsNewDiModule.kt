package com.singularity.todo.feature.whatsnew.di

import com.singularity.todo.feature.whatsnew.presentation.WhatsNewPrefs
import org.koin.dsl.module

/**
 * DI module for the WhatsNew feature.
 *
 * Registers [WhatsNewPrefs] backed by the per-platform DataStore<Preferences>.
 */
fun whatsNewModule() = module {
    single { WhatsNewPrefs.create(get()) }
}
