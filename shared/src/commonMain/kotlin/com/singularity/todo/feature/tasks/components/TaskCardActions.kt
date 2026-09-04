package com.singularity.todo.feature.tasks.components

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
    enum class Action { Toggle, Delete, Ai }

    fun onToggle() = block(Action.Toggle)
    fun onDelete() = block(Action.Delete)
    fun onAiClick() = block(Action.Ai)

    companion object {
        val Empty = TaskCardActions {}
    }
}
