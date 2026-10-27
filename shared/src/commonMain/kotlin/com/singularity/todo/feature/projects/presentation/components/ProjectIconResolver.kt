package com.singularity.todo.feature.projects.presentation.components

import com.singularity.todo.feature.projects.presentation.theme.ProjectIconRegistry

/**
 * Pure resolution of a stored project icon string into a renderable kind.
 *
 * Kept separate from the Composable so the "registered key vs. free text vs.
 * fallback" rule is testable without Compose.
 */
sealed interface ResolvedProjectIcon {
    data class Material(val key: String) : ResolvedProjectIcon
    data class Emoji(val value: String) : ResolvedProjectIcon
    data object None : ResolvedProjectIcon
}

fun resolveProjectIcon(icon: String?): ResolvedProjectIcon = when {
    icon.isNullOrBlank() -> ResolvedProjectIcon.None
    ProjectIconRegistry.iconByKey(icon) != null -> ResolvedProjectIcon.Material(icon)
    else -> ResolvedProjectIcon.Emoji(icon)
}
