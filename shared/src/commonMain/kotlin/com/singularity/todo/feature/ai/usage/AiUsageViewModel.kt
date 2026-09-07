package com.singularity.todo.feature.ai.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.observability.DailyUsage
import com.singularity.todo.core.observability.ToolUsage
import com.singularity.todo.core.observability.ModelUsage
import com.singularity.todo.core.observability.UsageRecorder
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

class AiUsageViewModel(
    private val usageRecorder: UsageRecorder,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<AiUsageUiState> = combine(
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
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AiUsageUiState(),
    )

    fun prune(olderThanDays: Int = 90) {
        viewModelScope.launch {
            usageRecorder.prune(olderThanDays)
        }
    }
}
