package com.singularity.todo.core.di

import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsRepository

/**
 * Creates a platform-specific [PromptExecutorPort].
 *
 * Suspend: the API key, base URL, provider and model are resolved from
 * [SecureStoragePort] and [SettingsRepository] via
 * [com.singularity.todo.feature.ai.OpenAiConfig.resolve]. The DI layer is
 * expected to bridge with `runBlocking` — the call happens once at
 * Koin-graph construction and the result is cached.
 */
expect suspend fun createKoogPromptExecutor(
    secureStorage: SecureStoragePort,
    settings: SettingsRepository,
): PromptExecutorPort