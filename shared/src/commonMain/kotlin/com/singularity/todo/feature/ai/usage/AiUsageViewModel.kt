package com.singularity.todo.feature.ai.usage

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.observability.DailyUsage
import com.singularity.todo.core.observability.ModelUsage
import com.singularity.todo.core.observability.ToolUsage
import com.singularity.todo.core.observability.UsageRecorder
import com.singularity.todo.feature.profile.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

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
    profileRepository: ProfileRepository,
    private val scope: CoroutineScope,
) : ViewModel() {

    /** Production constructor — Koin uses this. */
    constructor(usageRecorder: UsageRecorder, profileRepository: ProfileRepository) : this(
        usageRecorder = usageRecorder,
        profileRepository = profileRepository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

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
        scope,
        SharingStarted.WhileSubscribed(5_000),
        AiUsageUiState(),
    )
}
