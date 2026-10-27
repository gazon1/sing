package com.singularity.todo.feature.plans.presentation.viewmodel

import com.singularity.todo.core.ui.MviEvent

/** One-shot things the plan screen tells its host about. */
sealed interface PlansUiEvent : MviEvent {
    data class ShowError(val message: String) : PlansUiEvent
}
