package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A navigation icon a screen publishes for the shell bar — asked each time the
 * bar is drawn, and answered with the icon to draw or `null` when the screen
 * has none right now.
 *
 * The answer is a function and not a value because a screen can start or stop
 * supplying an icon while it stays on screen (the calendar gains a back
 * button, a selection ends). A decision taken once, when the screen published,
 * kept the entry-time answer for as long as the screen was composed (#1962
 * review).
 */
typealias PublishedNavigationIcon = @Composable () -> (@Composable () -> Unit)?

/**
 * The one bar the Android shell draws, as the screen on the way through it
 * describes it.
 *
 * A screen publishes its action slots through the platform-aware
 * [TopAppBar]/[BackTopAppBar] wrappers, and a screen that owns a header of its
 * own publishes that header too ([publishBar]); the shell reads the slots and
 * the published header from here and renders them in its single bar. On desktop
 * the holder is absent and every screen draws its own bar.
 *
 * Every publication carries the identity of the composable that made it, so
 * "take MY header down" and "take MY slots down" are both expressible, and a
 * bar leaving during a navigation cannot take down what the arriving screen
 * has already published ([retractBar], [claimActions], [clearActions]).
 */
class ScreenActionsHolder {
    /** The action buttons of the screen on top; the shell draws them. */
    var actions: (@Composable RowScope.() -> Unit)? by mutableStateOf(null)

    // Selection-mode state for the Android shell bar: when set, the shell shows
    // "N selected" with an exit-selection back button instead of the normal bar.
    var selectionTitle: String? by mutableStateOf(null)
    var onExitSelection: (() -> Unit)? by mutableStateOf(null)

    // The header a screen owns. On Android the shell owns the ONLY bar, so a
    // screen that draws its own header (the calendar's month, the agenda's
    // title with the greeting under it) publishes that header here and the
    // shell draws it INSTEAD of its own title and back button. Two bars would
    // be two bars.
    //
    // `null` means "the screen publishes no header", and the shell keeps its
    // own destination title. That is not the same as publishing a title and no
    // navigation icon: the second wants its title in the bar and the shell's
    // own back button, because the shell's button knows where the shell is.
    var title: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    /**
     * Draws the publisher's navigation icon, or nothing when it has none.
     *
     * The presence of an icon is decided when the shell draws the bar and not
     * when the screen publishes it: a screen can start or stop supplying one
     * while it stays on screen, and an answer captured at publication time
     * stayed for as long as the screen was composed (#1962 review).
     */
    var navigationIcon: PublishedNavigationIcon? by mutableStateOf(null)
        private set

    // Whether the published header replaces the shell's own title and
    // navigation icon. Derived from [title] and not stored beside it, so "this
    // screen owns the bar" cannot disagree with "this screen published a
    // title".
    val override: Boolean get() = title != null

    /** The screen whose header the bar draws; see [retractBar]. */
    private var barOwner: Any? = null

    /** The screen whose action slots the bar draws; see [claimActions]. */
    private var slotsOwner: Any? = null

    /**
     * Publishes a screen's own header: the title, its navigation icon, and the
     * fact that they replace the shell's. The three belong together — the
     * shell reads them as one header — so they are written in one call.
     *
     * A screen publishes **once**, when its composition enters, and keeps the
     * published content current by reading state inside [title] and
     * [navigationIcon] instead of publishing again. That is what makes
     * [retractBar]'s owner check sound. A publication driven by a `SideEffect`
     * runs on every recomposition, and a screen that is leaving the composition
     * still recomposes while the navigation transition plays: the departing
     * screen published itself back on top of the screen that had just arrived,
     * and its own [retractBar] then erased the arriving screen's header (#1962
     * review).
     *
     * @param owner an identity the publishing screen remembers and hands back
     *   to [retractBar]; it is what makes "take MY header down" expressible.
     */
    fun publishBar(
        owner: Any,
        title: @Composable () -> Unit,
        navigationIcon: PublishedNavigationIcon? = null,
    ) {
        this.title = title
        this.navigationIcon = navigationIcon
        barOwner = owner
    }

    /**
     * Takes the published header down — but only if [owner] is the publisher
     * the bar is still showing.
     *
     * A screen calls this from its own `DisposableEffect` when it leaves the
     * composition. During a navigation two screens are composed for the length
     * of the transition, and the one leaving is usually disposed *after* the
     * one arriving has published; an unconditional clear here would erase the
     * arriving screen's header and leave the bar with the shell's destination
     * title (#2008). With the owner check the leaving screen can only take down
     * its own header, and nothing else takes it down for it.
     *
     * The action slots are not touched: they are not part of the header, and
     * they are claimed and released through [claimActions] and [clearActions].
     */
    fun retractBar(owner: Any) {
        if (barOwner !== owner) return
        title = null
        navigationIcon = null
        barOwner = null
    }

    /**
     * Claims the action slots for the bar that is about to fill them.
     *
     * A bar claims once, from its `DisposableEffect` as its composition enters
     * — the same point at which it publishes its header, and for the same
     * reason. [clearActions] is the matching release and is keyed on this
     * claim: without it, a bar leaving during a transition emptied the slots
     * the arriving screen had already filled (#1962 review).
     *
     * A screen that publishes a header only (the calendar, through
     * [ShellOverride]) does not claim the slots: it has no buttons of its own,
     * and the previous bar's claim must stay valid so that bar's leave can
     * still empty them.
     */
    fun claimActions(owner: Any) {
        slotsOwner = owner
    }

    /**
     * Clears the action slots — but only for the bar that claimed them.
     *
     * A screen calls this as it leaves the composition. A bar leaving *after*
     * the arriving screen has claimed the slots must leave them alone: the
     * arriving screen filled them and does not republish until it recomposes
     * for some other reason, so an unconditional clear left the shell bar with
     * the arriving screen's title and none of its buttons (#1962 review).
     */
    fun clearActions(owner: Any) {
        if (slotsOwner !== owner) return
        actions = null
        selectionTitle = null
        onExitSelection = null
        slotsOwner = null
    }

    /**
     * Called by the shell when the top-level route changes — **inside the
     * composition that draws the new route, before the arriving screen
     * composes** (see `androidShellNav3Root`).
     *
     * Only the transient slots are cleared. A screen without a bar of its own
     * never publishes actions, so the previous screen's buttons would otherwise
     * stay in the shell bar; those slots are the shell's to empty.
     *
     * A published **header is not cleared here**. `title`, `navigationIcon` and
     * the identity of the publisher belong to the screen that published them,
     * and only that screen takes them down, in its own `DisposableEffect`
     * ([retractBar]) — "nothing else takes it down for it" is one of the
     * acceptance criteria #2008 is settled by. Cleared here — after the
     * arriving screen had published, as the old `LaunchedEffect` did — the
     * agenda lost its title and its greeting on a tab switch, because the
     * screen on top has no way of knowing the shell erased it and does not
     * publish again until it recomposes for some other reason (#1962).
     */
    fun onTopLevelRouteChanged() {
        actions = null
        selectionTitle = null
        onExitSelection = null
        slotsOwner = null
    }
}

val LocalScreenActions = compositionLocalOf<ScreenActionsHolder?> { null }
