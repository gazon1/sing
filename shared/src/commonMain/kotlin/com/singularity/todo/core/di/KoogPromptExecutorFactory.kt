package com.singularity.todo.core.di

import ai.koog.prompt.executor.model.PromptExecutor

/**
 * Creates a platform-specific Koog [PromptExecutor].
 *
 * - JVM: [createJvmKoogPromptExecutor] in `jvmMain`
 * - Android: stub returning a no-op executor (AI features disabled on Android in this build)
 *
 * The API key is resolved at runtime inside [com.singularity.todo.feature.ai.KoogAgentService]
 * via [SecureStoragePort], not at executor creation time.
 */
expect fun createKoogPromptExecutor(): PromptExecutor
