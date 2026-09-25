package com.singularity.todo.core.ui.mvi

/**
 * Marker interface for MVI intents (user actions).
 *
 * Each feature declares its own sealed intent hierarchy, e.g.:
 * ```
 * sealed interface TagsIntent : MviIntent {
 *     data class Delete(val id: TagId) : TagsIntent
 * }
 * ```
 *
 * ## Sealed enforcement
 * `MviIntent` and `MviEvent` are NOT `sealed` (Kotlin requires same-package for sealed extension).
 * The [MviViewModelExtRule] detekt rule enforces that all feature intents/events are sealed.
 */
interface MviIntent

/**
 * Marker interface for one-shot UI events.
 *
 * Unlike [MviIntent] (continuous input), events fire once and are consumed by a single subscriber.
 * Each feature declares its own sealed event hierarchy, e.g.:
 * ```
 * sealed interface TagsUiEvent : MviEvent {
 *     data class ShowError(val message: String) : TagsUiEvent
 * }
 * ```
 *
 * Use [com.singularity.todo.core.ui.EventBus] for Channel-backed emission or [com.singularity.todo.core.ui.SharedEventBus] for SharedFlow-backed.
 * @see com.singularity.todo.core.ui.EventBus
 * @see com.singularity.todo.core.ui.SharedEventBus
 */
interface MviEvent
