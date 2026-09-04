package com.singularity.todo.core.di

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.Tool
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import com.singularity.todo.feature.ai.KoogAgentService
import com.singularity.todo.feature.ai.TextGenPort
import com.singularity.todo.feature.ai.tools.ClusterNotesInput
import com.singularity.todo.feature.ai.tools.ClusterNotesTool
import com.singularity.todo.feature.ai.tools.ClusterTasksInput
import com.singularity.todo.feature.ai.tools.ClusterTasksTool
import com.singularity.todo.feature.ai.tools.DecomposeTaskInput
import com.singularity.todo.feature.ai.tools.DecomposeTaskTool
import com.singularity.todo.feature.ai.tools.GenerateChecklistInput
import com.singularity.todo.feature.ai.tools.GenerateChecklistTool
import com.singularity.todo.feature.ai.tools.GenerateDescriptionInput
import com.singularity.todo.feature.ai.tools.GenerateDescriptionTool
import com.singularity.todo.feature.ai.tools.GetNoteInput
import com.singularity.todo.feature.ai.tools.GetNoteTool
import com.singularity.todo.feature.ai.tools.GetProjectInput
import com.singularity.todo.feature.ai.tools.GetProjectTool
import com.singularity.todo.feature.ai.tools.GetTaskInput
import com.singularity.todo.feature.ai.tools.GetTaskTool
import com.singularity.todo.feature.ai.tools.ImproveNoteTool
import com.singularity.todo.feature.ai.tools.ListLinkedTasksInput
import com.singularity.todo.feature.ai.tools.ListLinkedTasksTool
import com.singularity.todo.feature.ai.tools.ListTasksInput
import com.singularity.todo.feature.ai.tools.ListTasksTool
import com.singularity.todo.feature.ai.tools.PickTimeInput
import com.singularity.todo.feature.ai.tools.PickTimeTool
import com.singularity.todo.feature.ai.tools.ProjectReviewInput
import com.singularity.todo.feature.ai.tools.ProjectReviewTool
import com.singularity.todo.feature.ai.tools.RefineTaskInput
import com.singularity.todo.feature.ai.tools.RefineTaskTool
import com.singularity.todo.feature.ai.tools.SearchTasksInput
import com.singularity.todo.feature.ai.tools.SearchTasksTool
import com.singularity.todo.feature.ai.tools.SmartRewriteInput
import com.singularity.todo.feature.ai.tools.SmartRewriteTool
import com.singularity.todo.feature.ai.tools.WeeklyPlanInput
import com.singularity.todo.feature.ai.tools.WeeklyPlanTool
import com.singularity.todo.feature.ai.tools.dataGetNoteTool
import com.singularity.todo.feature.ai.tools.dataGetProjectTool
import com.singularity.todo.feature.ai.tools.dataGetTaskTool
import com.singularity.todo.feature.ai.tools.dataListLinkedTasksTool
import com.singularity.todo.feature.ai.tools.dataListTasksTool
import com.singularity.todo.feature.ai.tools.dataSearchTasksTool
import com.singularity.todo.feature.ai.tools.llmClusterNotesTool
import com.singularity.todo.feature.ai.tools.llmClusterTasksTool
import com.singularity.todo.feature.ai.tools.llmDecomposeTaskTool
import com.singularity.todo.feature.ai.tools.llmGenerateChecklistTool
import com.singularity.todo.feature.ai.tools.llmGenerateDescriptionTool
import com.singularity.todo.feature.ai.tools.llmPickTimeTool
import com.singularity.todo.feature.ai.tools.llmProjectReviewTool
import com.singularity.todo.feature.ai.tools.llmRefineTaskTool
import com.singularity.todo.feature.ai.tools.llmSmartRewriteTool
import com.singularity.todo.feature.ai.tools.llmWeeklyPlanTool
import com.singularity.todo.feature.ai.use_cases.ClusterNotesUseCase
import com.singularity.todo.feature.ai.use_cases.ClusterTasksUseCase
import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import com.singularity.todo.feature.ai.use_cases.SmartRewriteUseCase
import com.singularity.todo.feature.genui.GenuiEngine
import com.singularity.todo.feature.genui.transport.GenuiTransport
import com.singularity.todo.feature.genui.transport.KoogGenuiTransport
import com.singularity.todo.feature.projects.ProjectsViewModel
import com.singularity.todo.feature.tasks.TasksViewModel
import org.koin.dsl.module

/**
 * JVM actual for [aiToolsModule].
 *
 * Uses [JvmPromptExecutorPort] which is a JVM-specific class not available on Android.
 * All bindings require `PromptExecutorPort` from [platformModule].
 */
actual fun aiToolsModule() = module {
    // Default LLM
    single<LLModel> { OpenAIModels.Chat.GPT4oMini }

    // PromptExecutorPort → real Koog executor (JVM only)
    single<PromptExecutorPort> { createKoogPromptExecutor() }

    // LLM tools (require PromptExecutor + model)
    factory { RefineTaskTool(get(), get()) }
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

    // Read-only tools
    factory { GetNoteTool(get()) }
    factory { GetProjectTool(get()) }
    factory { GetTaskTool(get()) }
    factory { ListLinkedTasksTool(get()) }
    factory { ListTasksTool(get()) }
    factory { SearchTasksTool(get()) }

    // Factory-based for KoogAgentService list
    factory { llmRefineTaskTool()(get(), get()) }
    factory { llmSmartRewriteTool()(get(), get()) }
    factory { llmGenerateDescriptionTool()(get(), get()) }
    factory { llmDecomposeTaskTool()(get(), get()) }
    factory { llmGenerateChecklistTool()(get(), get()) }
    factory { llmPickTimeTool()(get(), get()) }
    factory { llmClusterTasksTool()(get(), get()) }
    factory { llmClusterNotesTool()(get(), get()) }
    factory { llmProjectReviewTool(get(), get()) }
    factory { llmWeeklyPlanTool()(get(), get()) }

    // Read-only via factory
    factory { dataGetNoteTool(get())() }
    factory { dataGetProjectTool(get())() }
    factory { dataGetTaskTool(get())() }
    factory { dataListLinkedTasksTool(get())() }
    factory { dataListTasksTool(get())() }
    factory { dataSearchTasksTool(get())() }

    // All AI tools list for KoogAgentService
    single<List<Tool<*, *>>> {
        listOf(
            get<SimpleTool<RefineTaskInput>>(),
            get<SimpleTool<SmartRewriteInput>>(),
            get<SimpleTool<GenerateDescriptionInput>>(),
            get<SimpleTool<DecomposeTaskInput>>(),
            get<SimpleTool<GenerateChecklistInput>>(),
            get<SimpleTool<PickTimeInput>>(),
            get<SimpleTool<ClusterTasksInput>>(),
            get<SimpleTool<ClusterNotesInput>>(),
            get<SimpleTool<ProjectReviewInput>>(),
            get<SimpleTool<WeeklyPlanInput>>(),
            get<SimpleTool<GetNoteInput>>(),
            get<SimpleTool<GetProjectInput>>(),
            get<SimpleTool<GetTaskInput>>(),
            get<SimpleTool<ListLinkedTasksInput>>(),
            get<SimpleTool<ListTasksInput>>(),
            get<SimpleTool<SearchTasksInput>>(),
        )
    }

    // AI Use Cases
    factory { RefineTaskUseCase(get()) }
    factory { SmartRewriteUseCase(get()) }
    factory { GenerateDescriptionUseCase(get()) }
    factory { DecomposeTaskUseCase(get()) }
    factory { GenerateChecklistUseCase(get()) }
    factory { PickTimeUseCase(get()) }
    factory { ClusterTasksUseCase(get()) }
    factory { ClusterNotesUseCase(get()) }
    factory { ImproveNoteUseCase(get()) }

    // AI Service
    single<TextGenPort> { KoogAgentService(get(), get(), get(), get(), get()) }

    factory { com.singularity.todo.feature.ai.chat.ChatViewModel(get()) }

    // Koog PromptExecutor singleton (JVM only — Android uses StubPromptExecutorPort)
    single<ai.koog.prompt.executor.model.PromptExecutor> {
        (get<PromptExecutorPort>() as JvmPromptExecutorPort).executor
    }

    // ─── GenUI ──────────────────────────────────────────────────────────

    single { com.singularity.todo.feature.genui.surface.SurfaceController() }
    single { com.singularity.todo.feature.genui.parser.A2uiParser() }

    factory<GenuiTransport> { KoogGenuiTransport(get()) }

    factory {
        GenuiEngine(
            transport = get(),
            parser = get(),
            controller = get(),
        )
    }

    // ─── ViewModels that depend on AI ─────────────────────────────────

    factory { TasksViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { ProjectsViewModel(get(), get(), get(), get(), get()) }
}
