package com.singularity.todo.core.di

import com.singularity.todo.feature.ai.FakeTextGen
import com.singularity.todo.feature.ai.TextGenPort
import com.singularity.todo.feature.projects.ProjectsViewModel
import com.singularity.todo.feature.tasks.TasksViewModel
import org.koin.dsl.module

/**
 * Android stub for [aiToolsModule].
 *
 * AI features (Koog agent, OpenAI) are JVM-only on desktop.
 * On Android, ChatScreen uses [TextGenPort] which is bound to [FakeTextGen] —
 * AI buttons still work but return "(AI not available)".
 *
 * TasksViewModel and ProjectsViewModel are still required on Android.
 * Their AI dependencies (RefineTaskUseCase, etc.) are passed as null since
 * Koog is JVM-only. VMs handle null AI deps gracefully.
 */
actual fun aiToolsModule() = module {
    // TextGenPort → FakeTextGen on Android (Koog is JVM-only)
    single<TextGenPort> { FakeTextGen() }

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

    factory { com.singularity.todo.feature.ai.chat.ChatViewModel(get()) }
}
