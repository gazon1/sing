package com.singularity.todo.core.ui.featureSlot

import com.singularity.todo.core.ui.MviIntent
import kotlinx.coroutines.flow.StateFlow

/**
 * A screen feature that owns one coherent slice of UI state and the intents that mutate it.
 *
 * A `FeatureSlot` is the unit that keeps a coordinator [com.singularity.todo.core.ui.MviViewModel]
 * from becoming a god VM. Where a coordinator subscribes to eight repositories and merges
 * them into one screen state, each slice becomes a slot with its own state type and its own
 * small `onIntent` dispatcher. The coordinator then holds one flow per slot and merges them
 * with [combineStates].
 *
 * ## Why this shape
 *
 * - **One reason to change.** A slot's state type covers a single concern, so the type
 *   itself documents the boundary — unlike a section comment inside a shared `Ui` data class.
 * - **Testable in isolation.** A slot test constructs one slot with only the dependencies
 *   that slot uses, instead of standing up every dependency of the whole screen.
 * - **Bounded blast radius.** An intent that reaches the wrong slot fails to compile as a
 *   type error, rather than silently updating an unrelated field.
 *
 * ## Contract
 *
 * [state] must be a [StateFlow] so the coordinator always has a current value to merge —
 * a cold `Flow` would make the coordinator's merge start from an unknown state and re-subscribe
 * on every recomposition of the subscription. [onIntent] must not block: long-running work
 * belongs in the slot's own coroutine scope.
 *
 * ## Example
 *
 * ```kotlin
 * class TaskChecklistSlot(
 *     private val taskId: TaskId,
 *     private val repo: ChecklistRepository,
 *     private val scope: AutoCloseableCoroutineScope,
 * ) : FeatureSlot<TaskChecklistState, TaskChecklistIntent> {
 *
 *     private val _state = MutableStateFlow(TaskChecklistState())
 *     override val state: StateFlow<TaskChecklistState> = _state.asStateFlow()
 *
 *     init {
 *         scope.launch {
 *             repo.watchByTask(taskId.value).collect { items ->
 *                 _state.update { it.copy(items = items) }
 *             }
 *         }
 *     }
 *
 *     override fun onIntent(intent: TaskChecklistIntent) {
 *         when (intent) {
 *             is TaskChecklistIntent.Add -> scope.launch { repo.addItem(taskId.value, intent.title) }
 *             is TaskChecklistIntent.Toggle -> scope.launch { repo.toggleItem(taskId.value, intent.itemId, "user") }
 *         }
 *     }
 * }
 * ```
 *
 * Slots are plain Kotlin classes, not ViewModels: they are constructed by their coordinator,
 * are not registered in Koin, and have no lifecycle of their own beyond the coordinator's scope.
 *
 * @param S the state type this slot exposes. Small and single-purpose.
 * @param I the intent type this slot handles. Every variant it accepts belongs to this slot.
 * @see combineStates
 * @see docs/decisions/2026-09-27-feature-slot-pattern.md
 */
interface FeatureSlot<S, I : MviIntent> {

    /** The current state of this slice. Must always have a value. */
    val state: StateFlow<S>

    /** Handles one intent belonging to this slot. Must not block. */
    fun onIntent(intent: I)
}
