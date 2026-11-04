package com.singularity.todo.shell

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asInsets
import androidx.compose.foundation.layout.only
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
 * [windowInsets] is the **full** window inset set, as `ScaffoldDefaults.contentWindowInsets`
 * reports it — all four edges, not a pre-horizontalised slice. It has to be full for two
 * reasons. The questions that decide the bottom edge — "is the system navigation bar's room
 * already inside the suite's bar height, or on top of it?" — are questions about a
 * non-zero bottom inset, and a caller that hands in `.only(Horizontal)` has already erased
 * the input that answers them, leaving a union whose two arms can only ever agree. And the
 * sides are not the only edge the suite can take: keeping the whole set means the helper,
 * not the call site, decides which edges the content gets.
 *
 * The top edge is dropped here, deliberately and once: `ShellTopBar` sits under the status
 * bar and consumes that inset itself, so padding the content by it as well would move every
 * screen down twice. That is #1566, which is out of scope — dropping the edge is how this
 * helper stays out of it.
 *
 * The remaining edges are combined with `union` — the maximum per side, not a sum — because
 * the suite's padding describes a rectangle the content must stay out of, and the system
 * bars describe another. Where they cover the same ground (the suite's bar is drawn over
 * the system navigation bar and its height includes that inset — measured, see
 * `docs/device-checks/android-shell-insets-360dp.md`), the maximum is the truth and a sum
 * would leave a gap the height of the system bar. The rail case on a wide window is carried
 * by the same arithmetic: the suite reports its room on `start`/`end`, the window reports
 * the sides, and the larger side wins.
 *
 * @param windowInsets every window inset the content could be padded by — the full set, not
 *   a horizontal slice; the top edge is discarded here.
 * @param navigationPadding the padding the navigation suite handed to its content; every
 *   edge of it is room the suite took.
 */
internal fun shellContentWindowInsets(
    windowInsets: WindowInsets,
    navigationPadding: PaddingValues,
): WindowInsets =
    windowInsets
        // The status bar belongs to ShellTopBar; see the KDoc and #1566.
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        .union(navigationPadding.asInsets())
