package com.singularity.todo.feature.projects.presentation.components

import com.singularity.todo.core.emoji.Emoji

/**
 * Pure resolution of a project icon into a renderable kind.
 *
 * Kept separate from the Composable so the rule is testable without Compose.
 */
sealed interface ResolvedProjectIcon {
    data class EmojiIcon(val value: String) : ResolvedProjectIcon

    data object None : ResolvedProjectIcon
}

fun resolveProjectIcon(icon: Emoji?): ResolvedProjectIcon =
    when {
        icon == null || icon.value.isBlank() -> ResolvedProjectIcon.None
        else -> ResolvedProjectIcon.EmojiIcon(icon.value)
    }
