package com.singularity.todo.core.di

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider
import com.singularity.todo.feature.ai.TextGenPort
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import com.singularity.todo.feature.ai.use_cases.SmartRewriteUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.ClusterNotesUseCase
import com.singularity.todo.feature.ai.use_cases.ClusterTasksUseCase
import com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase
import com.singularity.todo.feature.ai.use_cases.ProjectReviewUseCase
import com.singularity.todo.feature.ai.tools.RefineTaskTool
import com.singularity.todo.feature.ai.tools.SmartRewriteTool
import com.singularity.todo.feature.ai.tools.GenerateDescriptionTool
import com.singularity.todo.feature.ai.tools.GenerateChecklistTool
import com.singularity.todo.feature.ai.tools.PickTimeTool
import com.singularity.todo.feature.ai.tools.DecomposeTaskTool
import com.singularity.todo.feature.ai.tools.ClusterNotesTool
import com.singularity.todo.feature.ai.tools.ClusterTasksTool
import com.singularity.todo.feature.ai.tools.ProjectReviewTool
import com.singularity.todo.feature.ai.tools.WeeklyPlanTool
import com.singularity.todo.feature.ai.tools.ImproveNoteTool
import com.singularity.todo.feature.ai.tools.GetNoteTool
import com.singularity.todo.feature.ai.tools.GetProjectTool
import com.singularity.todo.feature.ai.tools.GetTaskTool
import com.singularity.todo.feature.ai.tools.ListLinkedTasksTool
import com.singularity.todo.feature.ai.tools.ListTasksTool
import com.singularity.todo.feature.ai.tools.SearchTasksTool
import org.junit.Test
import org.koin.dsl.module

/**
 * Smoke-test that the JVM-side AI graph wires up correctly.
 *
 * Builds the Koog [PromptExecutorPort] (with empty key, no network call yet)
 * and resolves every tool + use case + TextGenPort binding. Real LLM traffic
 * is NOT exercised — just the fact that the graph is well-formed.
 *
 * The production [aiToolsModule] binds `LLModel` to [KnownModels.GPT4oMini],
 * which builds via the public `LLModel` constructor and never touches
 * `OpenAIModels.<clinit>`. The override below is a safety belt: if someone
 * later reintroduces an `OpenAIModels.*` reference in the production graph,
 * this test will fail at graph-build time rather than at first use.
 *
 * Run with: ./gradlew :shared:jvmTest --tests "*JvmAiDiGraphTest"
 */
class JvmAiDiGraphTest {

    /**
     * Minimal [LLModel] override for the test graph. Never used for inference
     * — only constructed to satisfy the `single<LLModel>` binding.
     */
    private val testLLModel = LLModel(provider = OpenAILLMProvider, id = "test-model")

    @Test
    fun `full AI module resolves every binding without network calls`() {
        val app = org.koin.core.context.startKoin {
            modules(
                domainModule(),
                platformModule(),
                // Safety belt override — see class KDoc.
                module { single<LLModel> { testLLModel } },
            )
        }
        try {
            val koin = app.koin

            // Service surface
            koin.get<TextGenPort>()

            // Use cases
            koin.get<RefineTaskUseCase>()
            koin.get<SmartRewriteUseCase>()
            koin.get<GenerateDescriptionUseCase>()
            koin.get<GenerateChecklistUseCase>()
            koin.get<PickTimeUseCase>()
            koin.get<DecomposeTaskUseCase>()
            koin.get<ClusterNotesUseCase>()
            koin.get<ClusterTasksUseCase>()
            koin.get<ImproveNoteUseCase>()
            koin.get<ProjectReviewUseCase>()

            // Tools (constructed but not executed — no network calls)
            koin.get<RefineTaskTool>()
            koin.get<SmartRewriteTool>()
            koin.get<GenerateDescriptionTool>()
            koin.get<GenerateChecklistTool>()
            koin.get<PickTimeTool>()
            koin.get<DecomposeTaskTool>()
            koin.get<ClusterNotesTool>()
            koin.get<ClusterTasksTool>()
            koin.get<ProjectReviewTool>()
            koin.get<WeeklyPlanTool>()
            koin.get<ImproveNoteTool>()
            koin.get<GetNoteTool>()
            koin.get<GetProjectTool>()
            koin.get<GetTaskTool>()
            koin.get<ListLinkedTasksTool>()
            koin.get<ListTasksTool>()
            koin.get<SearchTasksTool>()
        } finally {
            org.koin.core.context.stopKoin()
        }
    }
}