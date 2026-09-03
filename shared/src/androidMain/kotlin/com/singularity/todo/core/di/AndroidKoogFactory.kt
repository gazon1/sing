package com.singularity.todo.core.di

import ai.koog.prompt.executor.model.PromptExecutor

/**
 * Android actual for [createKoogPromptExecutor].
 *
 * Koog's OpenAI client uses OkHttp which is JVM-only.
 * On Android, AI chat features are disabled — this stub returns a no-op executor.
 *
 * TODO(port): implement Android-specific Koog executor using OkHttp on Android,
 *             or route AI calls through the desktop JVM target via a local API.
 */
actual fun createKoogPromptExecutor(): PromptExecutor {
    error("Koog PromptExecutor is not available on Android. Use the desktop/JVM target for AI features.")
}
