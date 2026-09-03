package com.singularity.todo.core.di

/**
 * Creates a platform-specific [PromptExecutorPort].
 *
 * - JVM: real Koog executor via [createJvmKoogPromptExecutor]
 * - Android: stub returning a sentinel (AI features disabled on Android)
 *
 * The API key is resolved at runtime inside [com.singularity.todo.feature.ai.KoogAgentService]
 * via [SecureStoragePort], not at executor creation time.
 */
expect fun createKoogPromptExecutor(): PromptExecutorPort
