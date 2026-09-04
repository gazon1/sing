package com.singularity.todo.core.di

import org.koin.dsl.module

/**
 * Android stub for [aiToolsModule].
 *
 * AI features (Koog agent, GenUI) are JVM-only on desktop.
 * On Android, AI buttons are hidden and features are disabled.
 * The [TasksViewModel] and [ProjectsViewModel] (which depend on AI use cases)
 * are registered here with null AI dependencies so the app doesn't crash,
 * but AI functionality is unreachable on Android.
 */
actual fun aiToolsModule() = module {
    // No AI tools on Android — Koog is JVM-only
    // TasksViewModel and ProjectsViewModel receive null ImproveNoteUseCase / ProjectReviewUseCase
    // via nullable constructor parameters, so they work without AI
}
