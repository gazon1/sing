package com.singularity.todo.shell

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asInsets
import androidx.compose.foundation.layout.union

/**
 * The window insets the Android shell's own scaffold pads its content by.
 *
 * The shell draws its navigation suite — a bottom navigation bar on a phone, a rail on a
 * wide window — around the content, and the suite tells the content how much room it took
 * through the [PaddingValues] it hands to its content lambda. Those edges are the ones the
 * content has to clear, or the last row is drawn behind the bar (and, on a wide window, the
 * content starts under the rail).
 *
 * Every edge the suite reports is kept, and the union — the maximum per side, not a sum —
 * means a system inset the caller already passes is not counted twice. The top edge is
 * normally zero; it is taken anyway rather than special-cased, so the rule is "the suite's
 * room is reserved" instead of "the bottom of the suite's room is reserved", which is what
 * let the rail case through.
 *
 * @param horizontalInsets the window insets the content is padded by on the sides — the
 *   system bars' horizontal insets.
 * @param navigationPadding the padding the navigation suite handed to its content; every
 *   edge of it is room the suite took.
 */
internal fun shellContentWindowInsets(
    horizontalInsets: WindowInsets,
    navigationPadding: PaddingValues,
): WindowInsets = horizontalInsets.union(navigationPadding.asInsets())
