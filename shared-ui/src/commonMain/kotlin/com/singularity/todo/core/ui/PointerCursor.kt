package com.singularity.todo.core.ui

import androidx.compose.ui.Modifier

/**
 * Puts the platform's pointing-hand cursor over a control that reacts to a click.
 *
 * Compose's `clickable` does not change the pointer on either client, so every
 * control that acts has to say so. This is the one seam that says it: a caller
 * puts it on the element that owns the click — the row, not the label inside it
 * — and the whole element then reads as one cursor instead of flickering between
 * two as the pointer crosses it. A hand set on a container also covers its
 * children, so a label, an icon or a switch inside the row inherits it; a child
 * that sets its own icon wins.
 *
 * [enabled] is the other half of the rule: a control that cannot act must not
 * claim to. A disabled menu entry or a disabled button keeps the default arrow.
 *
 * On Android this is the identity and [enabled] is ignored: the platform has no
 * pointer and draws no hover cursor, so the call sites stay common code.
 */
expect fun Modifier.pointerCursorHand(enabled: Boolean = true): Modifier
