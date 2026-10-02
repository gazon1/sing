package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Watches pending AI proposals for the current task, newest first.
 *
 * Proposals are local-only staging records — this collector only ever reads them.
 * Writing happens through [TaskDetailCoordinator][com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator]
 * intent routing to [ProposalRepository][com.singularity.todo.feature.proposals.domain.port.ProposalRepository].
 *
 * The collector is nullable so tests can omit it without breaking the coordinator's combine.
 *
 * @param proposals A hot or cold flow of all proposals for the task; the collector
 *   derives its own `pendingItems` view and exposes only that to the coordinator.
 */
class TaskProposalsCollector(scope: AutoCloseableCoroutineScope, proposals: Flow<List<AiProposal>>) {
    private val _state = MutableStateFlow(emptyList<AiProposal>())
    val state: StateFlow<List<AiProposal>> = _state.asStateFlow()

    init {
        scope.launch {
            proposals.collect { all ->
                // Only show proposals that have at least one pending item.
                _state.value = all.filter { it.pendingItems.isNotEmpty() }
            }
        }
    }
}
