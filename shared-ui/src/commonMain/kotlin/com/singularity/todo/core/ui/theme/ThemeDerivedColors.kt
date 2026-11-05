package com.singularity.todo.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * Theme-derived colours shared by the core UI components and the features that
 * use them.
 *
 * These are NOT a second palette. Every value here is a role the active theme
 * already provides, so a composable that needs a muted label reads
 * [mutedTextColor] rather than carrying a hex value of its own.
 *
 * The values live in `core.ui` because both the shared components and the
 * features read them. A colour that only one feature reads stays in that
 * feature's own theme; a colour both sides read has its one home here, so the
 * core layer never has to import a feature's presentation theme.
 */

/**
 * Third-tier text: timestamps, counts, other metadata that must recede.
 *
 * A scheme has no "tertiary text" role — it has [ColorScheme.onSurfaceVariant] and
 * nothing quieter — so the de-emphasis is expressed as opacity on that role. That
 * keeps the colour accent-independent, which matters because this is used for
 * metadata: an overdue item's timestamp should not change hue when the user
 * changes accent.
 */
@Composable
fun mutedTextColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)

/**
 * A hairline between rows.
 *
 * [ColorScheme.outline] is the theme's own divider role but is drawn at full
 * strength, which is heavier than a row separator wants. Fading it keeps the
 * separator's weight consistent across both modes without inventing a colour.
 */
@Composable
fun dividerColor(): Color = MaterialTheme.colorScheme.outlineVariant
