package com.singularity.todo.core.ui.components

/**
 * Marker interface for one-shot UI events emitted by ViewModels.
 *
 * Unlike UI state (continuous, observed via StateFlow), events fire once and
 * should not be replayed on configuration change.
 *
 * ## Migration note
 *
 * Concrete event types (`ShowDialog`, `ShowError`, `NavigateBack`) have been
 * moved to per-feature sealed interfaces (e.g. [com.singularity.todo.feature.tasks.TasksUiEvent]).
 * Each feature now declares its own events — search for `sealed interface XxxUiEvent`.
 *
 * This marker allows [NotificationHost] to stay generic: it accepts `Flow<T : UiEvent>`.
 */
sealed interface UiEvent
