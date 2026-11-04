package com.singularity.todo.shell

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asInsets
import androidx.compose.foundation.layout.union

/**
 * The window insets the Android shell's own scaffold pads its content by.
 *
 * The shell draws its navigation suite — the bottom navigation bar — around the
 * content, and the suite tells the content how much room the bar took through
 * the [PaddingValues] it hands to its content lambda. The bottom edge of that
 * padding is the height the content has to clear, or the last row of every
 * screen is drawn behind the navigation bar.
 *
 * Only the bottom edge is taken: the top edge is left to the shell's app bar,
 * which consumes the status bar inset itself, and the sides are left to the
 * horizontal insets the caller passes in — adding a top inset here as well
 * would pad the content twice.
 *
 * @param horizontalInsets the window insets the content is padded by on the
 *   sides — the system bars' horizontal insets.
 * @param navigationPadding the padding the navigation suite handed to its
 *   content; its bottom edge is the navigation bar's height.
 */
internal fun shellContentWindowInsets(
    horizontalInsets: WindowInsets,
    navigationPadding: PaddingValues,
): WindowInsets =
    horizontalInsets.union(
        PaddingValues(bottom = navigationPadding.calculateBottomPadding()).asInsets(),
    )
