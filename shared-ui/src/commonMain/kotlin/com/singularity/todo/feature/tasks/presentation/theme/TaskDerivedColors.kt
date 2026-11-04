package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * The task surfaces' theme-derived colours.
 *
 * These are NOT a second palette. Every value here is a role the active theme
 * already provides, so a composable that needs a muted label reads
 * [mutedTextColor] rather than carrying a hex value of its own.
 *
 * The two objects this replaced (`TaskColors` and `TaskListColors`) were two
 * hand-written dark palettes — 27 literals across 17 files — that could not
 * respond to the theme at all. A user in the app's default light mode therefore
 * saw a dark task screen on the app's primary surface, and the accent picker did
 * nothing there because the accent was written in as a literal blue.
 *
 * Most values are plain scheme roles and are read directly from
 * `MaterialTheme.colorScheme`. Only the few that need a derived value (a muted
 * tier of text) live here, so that the arithmetic happens once instead of at
 * every call site.
 */

/**
 * Third-tier text: timestamps, counts, other metadata that must recede.
 *
 * A scheme has no "tertiary text" role — it has [ColorScheme.onSurfaceVariant] and
 * nothing quieter — so the de-emphasis is expressed as opacity on that role. That
 * keeps the colour accent-independent, which matters because this is used for
 * task metadata: an overdue task's timestamp should not change hue when the user
 * changes accent.
 */
@Composable
fun mutedTextColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)

/**
 * Placeholder text in an empty input.
 *
 * Quieter than [mutedTextColor] on purpose: a placeholder that reads as strongly
 * as real content gets typed over by someone who thought it was the value.
 */
@Composable
fun placeholderTextColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

/**
 * A hairline between rows.
 *
 * [ColorScheme.outline] is the theme's own divider role but is drawn at full
 * strength, which is heavier than a row separator wants. Fading it keeps the
 * separator's weight consistent across both modes without inventing a colour.
 */
@Composable
fun dividerColor(): Color = MaterialTheme.colorScheme.outlineVariant

/**
 * A raised surface — a pressed row, a header strip.
 *
 * The task surfaces need one step above [ColorScheme.surface] that is still in
 * the neutral family. `surfaceContainer` is that step in the scheme; naming it
 * once here keeps the choice out of the call sites.
 */
@Composable
fun elevatedSurfaceColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh
