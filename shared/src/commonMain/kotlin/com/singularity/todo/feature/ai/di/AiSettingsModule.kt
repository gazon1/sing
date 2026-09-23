package com.singularity.todo.feature.ai.di

import com.singularity.todo.core.llm.TextGenPort
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.ai.AiContributor
import com.singularity.todo.feature.ai.AiSettingsContributor
import com.singularity.todo.feature.ai.data.AiSettingsStore
import org.koin.dsl.module

/**
 * DI module for AI settings.
 *
 * Registers:
 * - [AiSettingsStore] — reads/writes AI settings (including secret API key)
 * - [AiContributor] — contributed to [com.singularity.todo.feature.settings.SettingsViewModel]
 *   via `filterIsInstance<AiContributor>()`
 *
 * Add this module to [com.singularity.todo.core.di.domainModule] to enable the
 * AI Provider settings section.
 *
 * Note: [TextGenPort] is registered separately by [aiToolsModule].
 * This module depends on it being already available.
 */
fun aiSettingsModule() = module {
    single { AiSettingsStore(get<SecureStoragePort>(), get<SettingsRepository>(), get<TextGenPort>()) }
    single<AiContributor> { AiSettingsContributor(get()) }
}
