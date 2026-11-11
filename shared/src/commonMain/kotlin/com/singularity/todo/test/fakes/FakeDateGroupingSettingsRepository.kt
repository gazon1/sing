package com.singularity.todo.test.fakes

import com.singularity.todo.feature.agenda.domain.model.DateGrouping
import com.singularity.todo.feature.agenda.domain.port.DateGroupingSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An in-memory [DateGroupingSettingsRepository] for ViewModel tests. */
class FakeDateGroupingSettingsRepository(initial: DateGrouping = DateGrouping.Default) :
    DateGroupingSettingsRepository {
    private val state = MutableStateFlow(initial)

    override val grouping: Flow<DateGrouping> = state.asStateFlow()

    override suspend fun setGrouping(grouping: DateGrouping) {
        state.value = grouping
    }
}
