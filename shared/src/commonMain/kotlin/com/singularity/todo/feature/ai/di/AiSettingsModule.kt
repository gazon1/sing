package com.singularity.todo.feature.ai.di

import com.singularity.todo.core.llm.TextGenPort
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.feature.ai.AiSettingsContributor
import com.singularity.todo.feature.ai.data.AiSettingsStore
import org.koin.dsl.module

/**
 * DI module for AI settings.
 *
 * Registers:
 * - [AiSettingsStore] — reads/writes AI settings (including secret API key)
 * - [AiSettingsContributor] — contributed to [com.singularity.todo.feature.settings.SettingsViewModel]
 *   via `getAll<SettingsContributor>()`
 *
 * Add this module to [com.singularity.todo.core.di.domainModule] to enable the
 * AI Provider settings section.
 *
 * Note: [TextGenPort] is registered separately by [com.singularity.todo.core.di.aiToolsCoreModule].
 * This module depends on it being already available.
 */
fun aiSettingsModule() = module {
    single { AiSettingsStore(get<SecureStoragePort>(), get<SettingsRepository>(), get<TextGenPort>()) }
    single<SettingsContributor<SettingsSection.Ai, SettingsIntent.Ai>> { AiSettingsContributor(get()) }
}
