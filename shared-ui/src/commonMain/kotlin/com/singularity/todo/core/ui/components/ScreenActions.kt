package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The one bar the Android shell draws, as the screens on the way through it
 * describe it.
 *
 * A screen publishes its actions through the platform-aware
 * [TopAppBar]/[BackTopAppBar] wrappers, and a screen that owns a header of its
 * own publishes that header too ([publishBar]); the shell reads [actions] and
 * the published bar from here and renders them in its single bar. On desktop
 * the holder is absent and every screen draws its own bar.
 */
class ScreenActionsHolder {
    var actions: (@Composable RowScope.() -> Unit)? by mutableStateOf(null)

    // The bar a screen owns. On Android the shell owns the ONLY bar, so a
    // screen that draws its own header (the calendar's month, the settings
    // title) publishes that header here and the shell draws it INSTEAD of its
    // own title and back button. Two bars would be two bars.
    //
    // `null` means "the screen has no opinion here", and the shell keeps what
    // it has. That is not the same as [override]: a screen that publishes a
    // title and no navigation icon wants its title in the bar and the shell's
    // own back button — the shell's back button knows where the shell is.
    var title: (@Composable () -> Unit)? by mutableStateOf(null)
        private set
    var navigationIcon: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    // Whether the published title and navigation icon replace the shell's own.
    // Only [publishBar] and [retractBar] write it, so "this screen owns the
    // bar" is one fact and not a flag that can disagree with the title.
    var override: Boolean by mutableStateOf(false)
        private set

    // Selection-mode state for the Android shell bar: when set, the shell shows
    // "N selected" with an exit-selection back button instead of the normal bar.
    var selectionTitle: String? by mutableStateOf(null)
    var onExitSelection: (() -> Unit)? by mutableStateOf(null)

    /** The publisher whose header the bar is drawing; see [retractBar]. */
    private var barOwner: Any? = null

    /**
     * Publishes a screen's own bar: the title, its navigation icon, and the
     * fact that they replace the shell's. The three belong together — the
     * shell reads them as one header — so they are written in one call.
     *
     * A screen publishes **once**, when its composition enters, and keeps the
     * published content current by reading state inside [title] instead of
     * publishing again. That is what makes [retractBar]'s owner check sound.
     * A publication driven by a `SideEffect` runs on every recomposition, and a
     * screen that is leaving the composition still recomposes while the
     * navigation transition plays: the departing screen published itself back
     * on top of the screen that had just arrived, and its own [retractBar] then
     * erased the arriving screen's header (#1962 review).
     *
     * @param owner an identity the publishing screen remembers and hands back
     *   to [retractBar]; it is what makes "take MY header down" expressible.
     */
    fun publishBar(
        owner: Any,
        title: @Composable () -> Unit,
        navigationIcon: (@Composable () -> Unit)? = null,
    ) {
        this.title = title
        this.navigationIcon = navigationIcon
        override = true
        barOwner = owner
    }

    /**
     * Takes the published bar down — but only if [owner] is the publisher the
     * bar is still showing.
     *
     * A screen calls this from its own `DisposableEffect` when it leaves the
     * composition. During a navigation two screens are composed for the length
     * of the transition, and the one leaving is usually disposed *after* the
     * one arriving has published; an unconditional clear here would erase the
     * arriving screen's header and leave the bar with the shell's destination
     * title (#2008). With the owner check the leaving screen can only take down
     * its own header.
     */
    fun retractBar(owner: Any) {
        if (barOwner !== owner) return
        title = null
        navigationIcon = null
        override = false
        barOwner = null
    }

    /**
     * Called by the shell when the top-level route changes — **inside the
     * composition that draws the new route, before the arriving screen
     * composes** (see `androidShellNav3Root`).
     *
     * A screen without a bar of its own never publishes actions, so the
     * previous screen's buttons would otherwise stay in the shell bar; those
     * transient slots are cleared here.
     *
     * The published bar is cleared here too, and the *order* is the whole
     * point. Dropped before the arriving screen publishes, nothing follows the
     * clear that could disagree with it: the screen now on top has the last
     * word, a screen that publishes nothing leaves the shell's own title in
     * place (which is what makes the fallback reachable at all, rather than
     * only when the departing screen happens to be disposed), and a screen that
     * publishes a header has it drawn. Dropped *after* — as a `LaunchedEffect`
     * did, once the frame was applied — the clear erased the header the
     * arriving screen had just published and left the bar with the destination
     * title until that screen happened to recompose. That was #2008: the
     * agenda lost its title and greeting on a tab switch.
     *
     * A screen that leaves the composition still takes its own header down
     * through [retractBar]; it is the backstop for navigations that do not
     * change the top-level route, such as a nested screen opened over the
     * agenda. After this clear its owner check makes that retraction a no-op.
     */
    fun onTopLevelRouteChanged() {
        actions = null
        selectionTitle = null
        onExitSelection = null
        title = null
        navigationIcon = null
        override = false
        barOwner = null
    }
}

val LocalScreenActions = compositionLocalOf<ScreenActionsHolder?> { null }
