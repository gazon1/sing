package com.singularity.todo.feature.ai.usage

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.observability.DailyUsage
import com.singularity.todo.core.observability.ModelUsage
import com.singularity.todo.core.observability.RoomUsageRecorder
import com.singularity.todo.core.observability.ToolUsage
import com.singularity.todo.feature.profile.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(AiUsageUiState())
    val uiState: StateFlow<AiUsageUiState> = _uiState.asStateFlow()

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
                val totalCost = tools.mapNotNull { it.totalCostUsdMicros }.takeIf { it.isNotEmpty() }?.sum()
                AiUsageUiState(
                    isLoading = false,
                    profileName = profile.name,
                    dailyUsage = daily,
                    toolUsage = tools,
                    modelUsage = models,
                    totalTokens = totalTokens,
                    totalCostUsdMicros = totalCost,
                )
            }.collect { _uiState.value = it }
        }
    }
}
