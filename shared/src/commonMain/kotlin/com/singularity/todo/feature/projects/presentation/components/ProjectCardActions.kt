package com.singularity.todo.feature.projects.presentation.components

/**
 * Callbacks for [ProjectCard], packed into a single value-class parameter.
 * Lets the card take a single `actions:` argument that's easy to extend
 * (delete / review / pin / archive / …) without breaking call sites.
 */
@JvmInline
value class ProjectCardActions(
    val block: (Action) -> Unit,
) {
    enum class Action { Delete, Review }

    fun onDelete() = block(Action.Delete)
    fun onReviewClick() = block(Action.Review)

    companion object {
        val Empty = ProjectCardActions {}
    }
}
