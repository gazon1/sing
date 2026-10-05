package com.singularity.todo.feature.ai.usage

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.DailyUsage
import com.singularity.todo.core.observability.ModelUsage
import com.singularity.todo.core.observability.RoomUsageRecorder
import com.singularity.todo.core.observability.ToolUsage
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

data class AiUsageUiState(
    val isLoading: Boolean = true,
    val profileName: String = "",
    val dailyUsage: List<DailyUsage> = emptyList(),
    val toolUsage: List<ToolUsage> = emptyList(),
    val modelUsage: List<ModelUsage> = emptyList(),
    val totalTokens: Long = 0,
    val totalCostUsdMicros: Long? = null,
)

sealed interface AiUsageIntent : MviIntent
// Currently no user intents — purely observational

/**
 * AI usage statistics screen ViewModel.
 *
 * Owns: usage data aggregated by day, tool, and model for the active profile.
 * Triggers: profile switch (re-queries with new profile ID).
 * No one-shot events — purely observational state.
 *
 * @see AiUsageUiState
 */
class AiUsageViewModel(
    private val usageRecorder: RoomUsageRecorder,
    profileRepository: ProfileRepository,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<AiUsageUiState, AiUsageIntent, Nothing>(
        initialState = AiUsageUiState(),
        crashReporter = crashReporter,
        scope = scope,
    ) {

    init {
        addCloseable(scope)
        scope.launch {
            combine(
                profileRepository.activeProfile(),
                profileRepository.activeProfileId.flatMapLatest { profileId ->
                    combine(
                        usageRecorder.observeByDay(profileId.value, 30),
                        usageRecorder.observeByTool(profileId.value),
                        usageRecorder.observeByModel(profileId.value),
                    ) { daily, tools, models ->
                        Triple(daily, tools, models)
                    }
                },
            ) { profile, (daily, tools, models) ->
                val totalTokens = tools.sumOf { it.totalTokens }
                val totalCost = tools.mapNotNull { it.totalCostUsdMicros }
                    .takeIf { it.isNotEmpty() }
                    ?.sum()
                AiUsageUiState(
                    isLoading = false,
                    profileName = profile.name,
                    dailyUsage = daily,
                    toolUsage = tools,
                    modelUsage = models,
                    totalTokens = totalTokens,
                    totalCostUsdMicros = totalCost,
                )
            }.collect { newState -> updateState { newState } }
        }
    }

    override fun onIntent(intent: AiUsageIntent) {
        // No intents yet — purely observational
    }
}
