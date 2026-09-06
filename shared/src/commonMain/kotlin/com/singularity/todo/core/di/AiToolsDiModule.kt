package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.ai.TextGenPort
import com.singularity.todo.feature.ai.KoogAgentService
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import com.singularity.todo.feature.ai.use_cases.SmartRewriteUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.ClusterTasksUseCase
import com.singularity.todo.feature.ai.use_cases.ClusterNotesUseCase
import com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase
import com.singularity.todo.feature.ai.use_cases.ProjectReviewUseCase
import com.singularity.todo.feature.ai.tools.RefineTaskTool
import com.singularity.todo.feature.ai.tools.SmartRewriteTool
import com.singularity.todo.feature.ai.tools.GenerateDescriptionTool
import com.singularity.todo.feature.ai.tools.DecomposeTaskTool
import com.singularity.todo.feature.ai.tools.GenerateChecklistTool
import com.singularity.todo.feature.ai.tools.PickTimeTool
import com.singularity.todo.feature.ai.tools.ClusterTasksTool
import com.singularity.todo.feature.ai.tools.ClusterNotesTool
import com.singularity.todo.feature.ai.tools.ProjectReviewTool
import com.singularity.todo.feature.ai.tools.WeeklyPlanTool
import com.singularity.todo.feature.ai.tools.ImproveNoteTool
import com.singularity.todo.feature.ai.tools.GetNoteTool
import com.singularity.todo.feature.ai.tools.GetProjectTool
import com.singularity.todo.feature.ai.tools.GetTaskTool
import com.singularity.todo.feature.ai.tools.ListLinkedTasksTool
import com.singularity.todo.feature.ai.tools.ListTasksTool
import com.singularity.todo.feature.ai.tools.SearchTasksTool
import com.singularity.todo.feature.ai.chat.ChatViewModel
import com.singularity.todo.feature.tasks.TasksViewModel
import com.singularity.todo.feature.projects.ProjectsViewModel
import com.singularity.todo.feature.genui.GenuiEngine
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.transport.GenuiTransport
import com.singularity.todo.feature.genui.transport.KoogGenuiTransport
import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.feature.tasks.CreateTaskUseCase
import com.singularity.todo.feature.tasks.UpdateTaskUseCase
import com.singularity.todo.feature.tasks.usecase.TaskMutationsUseCase
import com.singularity.todo.feature.projects.CreateProjectUseCase
import com.singularity.todo.feature.projects.usecase.DeleteProjectUseCase
import com.singularity.todo.core.auth.CurrentUser
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * AI tools, GenUI, and AI use cases — shared between JVM and Android.
 * Included by the platform [aiToolsModule] actuals via [includes].
 *
 * Depends on [tasksModule] and [projectsModule] for non-AI bindings
 * (TaskRepository, ProjectsRepository, CreateTaskUseCase, etc.).
 */
internal fun aiToolsCoreModule(): org.koin.core.module.Module = module {
    // ─── AI Service ───

    single<TextGenPort> { KoogAgentService(get(), get(), get(), get(), get()) }

    viewModelOf(::ChatViewModel)

    // ─── GenUI ───

    single { SurfaceController() }
    single { A2uiParser() }

    factory<GenuiTransport> { KoogGenuiTransport(get()) }

    factory {
        GenuiEngine(
            transport = get(),
            parser = get(),
            controller = get(),
        )
    }

    // ─── AI Use Cases ───

    factory { RefineTaskUseCase(get()) }
    factory { SmartRewriteUseCase(get()) }
    factory { GenerateDescriptionUseCase(get()) }
    factory { DecomposeTaskUseCase(get()) }
    factory { GenerateChecklistUseCase(get()) }
    factory { PickTimeUseCase(get()) }
    factory { ClusterTasksUseCase(get()) }
    factory { ClusterNotesUseCase(get()) }
    factory { ImproveNoteUseCase(tool = get<ImproveNoteTool>()) }
    factory { ProjectReviewUseCase(get()) }

    // ─── AI Tools ───

    factory { RefineTaskTool(Logger.withTag("RefineTaskTool"), get(), get()) }
    factory { SmartRewriteTool(get(), get()) }
    factory { GenerateDescriptionTool(get(), get()) }
    factory { DecomposeTaskTool(get(), get()) }
    factory { GenerateChecklistTool(get(), get()) }
    factory { PickTimeTool(get(), get()) }
    factory { ClusterTasksTool(get(), get()) }
    factory { ClusterNotesTool(get(), get()) }
    factory { ProjectReviewTool(get(), get()) }
    factory { WeeklyPlanTool(get(), get()) }
    factory { ImproveNoteTool(get(), get()) }
    factory { GetNoteTool(get()) }
    factory { GetProjectTool(get()) }
    factory { GetTaskTool(get()) }
    factory { ListLinkedTasksTool(get()) }
    factory { ListTasksTool(get()) }
    factory { SearchTasksTool(get()) }

    // ─── AI tools list for KoogAgentService ───

    single<List<ai.koog.agents.core.tools.Tool<*, *>>> {
        listOf(
            get<RefineTaskTool>(),
            get<SmartRewriteTool>(),
            get<GenerateDescriptionTool>(),
            get<DecomposeTaskTool>(),
            get<GenerateChecklistTool>(),
            get<PickTimeTool>(),
            get<ClusterTasksTool>(),
            get<ClusterNotesTool>(),
            get<ProjectReviewTool>(),
            get<WeeklyPlanTool>(),
            get<ImproveNoteTool>(),
            get<GetNoteTool>(),
            get<GetProjectTool>(),
            get<GetTaskTool>(),
            get<ListLinkedTasksTool>(),
            get<ListTasksTool>(),
            get<SearchTasksTool>(),
        )
    }

    // ─── ViewModels with nullable AI deps ───────────────────────────────

    viewModel {
        TasksViewModel(
            taskRepo = get<TaskRepository>(),
            createTask = get<CreateTaskUseCase>(),
            updateTask = get<UpdateTaskUseCase>(),
            currentUser = get<CurrentUser>(),
            mutations = get<TaskMutationsUseCase>(),
            refineTask = getOrNull(),
            generateDescription = getOrNull(),
            generateChecklist = getOrNull(),
            decomposeTask = getOrNull(),
            pickTime = getOrNull(),
        )
    }

    viewModel {
        ProjectsViewModel(
            projectRepo = get<ProjectsRepository>(),
            createProject = get<CreateProjectUseCase>(),
            currentUser = get<CurrentUser>(),
            taskRepository = get<TaskRepository>(),
            projectReview = getOrNull(),
            deleteProject = get<DeleteProjectUseCase>(),
        )
    }
}
