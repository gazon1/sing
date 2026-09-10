package com.singularity.todo.feature.tasks.presentation.components

/**
 * Action callbacks available on a [TaskCard]. Packed into a single value-class
 * so the card itself only takes one parameter for "what can happen here",
 * which keeps the Composable signature readable and easy to extend without
 * breaking call sites.
 */
@JvmInline
value class TaskCardActions(
    val block: (Action) -> Unit,
) {
    enum class Action { Toggle, Delete, Ai, Pin }

    fun onToggle() = block(Action.Toggle)
    fun onDelete() = block(Action.Delete)
    fun onAiClick() = block(Action.Ai)
    fun onPin() = block(Action.Pin)

    companion object {
        val Empty = TaskCardActions {}
    }
}
