package com.singularity.todo.core.di

import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsRepository

/**
 * Creates a platform-specific [PromptExecutorPort].
 *
 * The API key, base URL, provider and model are resolved from
 * [SecureStoragePort] and [SettingsRepository] via
 * [com.singularity.todo.feature.ai.OpenAiConfig.resolve] inside the actual.
 * Implementations are responsible for reading those values; this expect
 * function exists only to hide Koog types behind a multiplatform port.
 */
expect fun createKoogPromptExecutor(
    secureStorage: SecureStoragePort,
    settings: SettingsRepository,
): PromptExecutorPort