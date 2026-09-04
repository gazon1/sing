package com.singularity.todo.core.di

import com.singularity.todo.feature.projects.ProjectsViewModel
import com.singularity.todo.feature.tasks.TasksViewModel
import org.koin.dsl.module

/**
 * Android stub for [aiToolsModule].
 *
 * AI features (Koog agent, GenUI) are JVM-only on desktop.
 * On Android, AI buttons are hidden and features are disabled.
 * But [TasksViewModel] and [ProjectsViewModel] are still needed on Android —
 * they have nullable AI dependencies (ImproveNoteUseCase?, ProjectReviewUseCase?)
 * so they work without AI.
 */
actual fun aiToolsModule() = module {
    // TasksViewModel and ProjectsViewModel are required on Android.
    // AI use cases (last 5 / 1 args) are passed as null since Koog is JVM-only.
    // The VMs handle null AI deps gracefully (AI buttons show "AI not available").
    factory {
        TasksViewModel(
            taskRepo = get(),
            createTask = get(),
            updateTask = get(),
            settingsRepository = get(),
            refineTask = null,
            generateDescription = null,
            generateChecklist = null,
            decomposeTask = null,
            pickTime = null,
        )
    }
    factory {
        ProjectsViewModel(
            projectRepo = get(),
            createProject = get(),
            settingsRepository = get(),
            taskRepository = get(),
            projectReview = null,
        )
    }
}
