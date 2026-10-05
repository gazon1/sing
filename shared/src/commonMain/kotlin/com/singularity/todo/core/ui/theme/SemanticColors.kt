package com.singularity.todo.core.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Semantic colours: a value that answers a question about the ITEM or the OUTCOME,
 * not about the app's appearance.
 *
 * These deliberately do NOT follow the theme. A connection status is not a theme
 * role, and a destructive confirmation is not either — tying either to the accent
 * would mean "this row is deleted" could render in whatever colour the user picked.
 *
 * The line between these and the theme is the reason this file exists. Anything
 * that is a *role* belongs in `MaterialTheme.colorScheme`; anything that is a
 * *verdict* lives here. #199 moved two screen-local palettes here rather than
 * converting them, because conversion was the wrong operation.
 */

/**
 * AI provider connection status, for the settings badge.
 *
 * Three states, not two: a provider that has never been configured is neither
 * healthy nor failed, and rendering it as one of those is how a user ends up
 * chasing a key they never entered.
 */
object AiStatusColors {
    val Ok = Color(0xFF4CAF50)
    val Error = Color(0xFFF44336)
    val Unknown = Color(0xFF9E9E9E)
}

/**
 * What a swipe on a note row is about to do.
 *
 * These name the *consequence*, which is why they are fixed. Archive, un-archive
 * and delete are three different verdicts; a user reading a colour while their
 * thumb is moving is reading the verdict, not the brand.
 */
object NoteSwipeColors {
    val Archive = Color(0xFFFFB300)
    val UnArchive = Color(0xFF43A047)
    val Delete = Color(0xFFE53935)
}

/**
 * Per-profile avatar tint.
 *
 * This is identity, not decoration: it is how a user tells two profiles apart in
 * a list. Deriving it from the accent would make every avatar the same hue, which
 * is the same argument as the chart-series palette and the same conclusion — a
 * categorical scale is not a theme role.
 *
 * Eight hues, cycled by profile index, so a ninth profile reuses the first rather
 * than running out of distinguishable colours.
 */
object ProfileIdentityColors {
    val Blue = Color(0xFF2196F3)
    val Green = Color(0xFF4CAF50)
    val Red = Color(0xFFF44336)
    val Orange = Color(0xFFFF9800)
    val Purple = Color(0xFF9C27B0)
    val Cyan = Color(0xFF00BCD4)
    val Pink = Color(0xFFE91E63)
    val Grey = Color(0xFF607D8B)

    val ordered: List<Color> = listOf(Blue, Green, Red, Orange, Purple, Cyan, Pink, Grey)

    fun at(index: Int): Color = ordered[index.mod(ordered.size)]
}

/**
 * Tone colours for generated UI.
 *
 * A tone arrives from the content being rendered — an error the assistant
 * reported, a warning it raised. Those are verdicts about the content, and they
 * must not take the user's colour preference: an error rendered in the accent a
 * user picked is a different signal from an error rendered in red, and the whole
 * point of the tone is that it means one specific thing.
 */
object GenUiToneColors {
    val Error = Color.Red
    val Warning = Color(0xFFCC7700)
    val Positive = Color(0xFF2E7D32)
}
