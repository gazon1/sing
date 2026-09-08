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
import com.singularity.todo.feature.ai.tools.CreateTaskTool
import com.singularity.todo.feature.ai.tools.UpdateTaskTool
import com.singularity.todo.feature.ai.tools.DeleteTaskTool
import com.singularity.todo.feature.ai.tools.CreateNoteTool
import com.singularity.todo.feature.ai.tools.UpdateNoteTool
import com.singularity.todo.feature.ai.tools.DeleteNoteTool
import com.singularity.todo.feature.ai.tools.CreateProjectTool
import com.singularity.todo.feature.ai.tools.DeleteProjectTool
import com.singularity.todo.feature.ai.tools.ListProjectsTool
import com.singularity.todo.feature.ai.tools.UpdateProjectTool
import com.singularity.todo.feature.ai.tools.CreateTagTool
import com.singularity.todo.feature.ai.tools.DeleteTagTool
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.profile.ProfileRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProfileRepository
import org.junit.Test
import org.koin.dsl.module

/**
 * Smoke-test that the JVM-side AI graph wires up correctly.
 *
 * Builds the Koog [PromptExecutorPort] (with empty key, no network call yet)
 * and resolves every tool + use case + TextGenPort binding. Real LLM traffic
 * is NOT exercised — just the fact that the graph is well-formed.
 *
 * Uses [FakeProfileRepository] and [FakeProfileAwareCurrentUser] to avoid
 * needing a real DataStore in tests.
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
                platformModule(),
                *domainModule().toTypedArray(),
                aiToolsModule(),
                // Safety belt overrides — see class KDoc.
                module {
                    single<LLModel> { testLLModel }
                    // Use fakes to avoid needing real DataStore
                    single<ProfileRepository> { FakeProfileRepository() }
                    single<ProfileAwareCurrentUser> { FakeProfileAwareCurrentUser() }
                },
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
            // Write tools
            koin.get<CreateTaskTool>()
            koin.get<UpdateTaskTool>()
            koin.get<DeleteTaskTool>()
            koin.get<CreateNoteTool>()
            koin.get<UpdateNoteTool>()
            koin.get<DeleteNoteTool>()
            koin.get<CreateProjectTool>()
            koin.get<UpdateProjectTool>()
            koin.get<DeleteProjectTool>()
            koin.get<ListProjectsTool>()
            koin.get<CreateTagTool>()
            koin.get<DeleteTagTool>()
        } finally {
            org.koin.core.context.stopKoin()
        }
    }
}
