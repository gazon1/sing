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
 * The header and the slots have deliberately different lifetimes:
 *
 * - **The header** ([publishBar], [retractBar]) belongs to the *screen* that
 *   published it and to nothing else. Only that screen takes it down, from its
 *   own `DisposableEffect`, and only its own: the publication carries the
 *   identity of the composable that made it, so a screen leaving during a
 *   navigation cannot erase the header the arriving screen has already
 *   published (#2008).
 * - **The action slots** ([publishSlots], [claimActions], [clearActions])
 *   belong to the *top-level route* the bar was composed for. A bar tags its
 *   slots with the route it entered on, and the shell asks for the slots **of
 *   the route it is drawing** ([actionsFor]). The previous screen's buttons
 *   therefore stop being drawn in the same composition that swaps the route,
 *   without anyone writing state during composition and without a clear whose
 *   correctness depends on whether it ran before or after the arriving screen
 *   published (#1962 review).
 */
class ScreenActionsHolder {
    /** The action buttons of the bar that holds the claim; read through [actionsFor]. */
    var actions: (@Composable RowScope.() -> Unit)? by mutableStateOf(null)
        private set

    // Selection-mode state for the Android shell bar: when set, the shell shows
    // "N selected" with an exit-selection back button instead of the normal bar.
    var selectionTitle: String? by mutableStateOf(null)
        private set
    var onExitSelection: (() -> Unit)? by mutableStateOf(null)
        private set

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

    /** The bar that filled the slots; see [claimActions]. */
    private var slotsOwner: Any? = null

    /**
     * The top-level route the slot-holding bar was composed for.
     *
     * Kept beside [slotsOwner] and not derived from the shell's current route,
     * because it is the *publisher's* route that decides whether the slots may
     * still be drawn: a bar that outlives a navigation keeps publishing from
     * its own `SideEffect`, and what it publishes belongs to the screen it was
     * made for, not to the screen the shell has moved to.
     */
    private var slotsRoute: Any? = null

    /**
     * The action slots of the bar that belongs to [route], or `null` when the
     * slots on record were published by a screen the shell has already left.
     *
     * This is the whole of "the shell drops the previous screen's buttons on a
     * navigation". It is a **read**, not a write: the shell asks for the route
     * it is drawing, so the answer is already right in the composition that
     * swaps the route. There is no clear that can run too late, and no state
     * write during composition to invalidate the scope that had just read it
     * (#1962 review).
     */
    fun actionsFor(route: Any?): (@Composable RowScope.() -> Unit)? =
        if (isOnRoute(route)) actions else null

    /** The selection title of the bar that belongs to [route]; see [actionsFor]. */
    fun selectionTitleFor(route: Any?): String? = if (isOnRoute(route)) selectionTitle else null

    /** The exit-selection callback of the bar that belongs to [route]; see [actionsFor]. */
    fun onExitSelectionFor(route: Any?): (() -> Unit)? =
        if (isOnRoute(route)) onExitSelection else null

    private fun isOnRoute(route: Any?): Boolean = slotsOwner != null && slotsRoute === route

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
     * Claims the action slots for the bar that is about to fill them, on the
     * top-level [route] that bar was composed for.
     *
     * A bar claims once, from its `DisposableEffect` as its composition enters
     * — the same point at which it publishes its header, and for the same
     * reason: a bar that is *leaving* still recomposes while the transition
     * plays, and claiming again there would let it take the slots back from the
     * screen that had just claimed them.
     *
     * The claim empties the slots it takes over, so the shell never draws a
     * previous bar's buttons between this claim and the claimer's first
     * [publishSlots]: the two happen in the same frame, effects before side
     * effects.
     *
     * A screen that publishes a header only (the calendar, through
     * [ShellOverride]) does not claim the slots: it has no buttons of its own.
     */
    fun claimActions(owner: Any, route: Any?) {
        slotsOwner = owner
        slotsRoute = route
        actions = null
        selectionTitle = null
        onExitSelection = null
    }

    /**
     * Publishes the bar's action slots — but only for the bar that claimed
     * them.
     *
     * The owner check is what stops a bar that is leaving during a transition
     * from writing its actions over the arriving bar's: both send a
     * `SideEffect` in the same frame and only the claim holder's is accepted
     * (#1962 review). A stale write that *is* accepted — the leaving bar before
     * the arriving one claims — is still invisible, because it carries the
     * leaving route and the shell is asking for another one ([actionsFor]).
     */
    fun publishSlots(
        owner: Any,
        actions: (@Composable RowScope.() -> Unit)?,
        selectionTitle: String?,
        onExitSelection: (() -> Unit)?,
    ) {
        if (slotsOwner !== owner) return
        this.actions = actions
        this.selectionTitle = selectionTitle
        this.onExitSelection = onExitSelection
    }

    /**
     * Clears the action slots — but only for the bar that claimed them.
     *
     * A screen calls this as it leaves the composition. A bar leaving *after*
     * the arriving screen has claimed the slots must leave them alone: the
     * arriving
     */
    fun clearActions(owner: Any) {
        if (slotsOwner !== owner) return
        actions = null
        selectionTitle = null
        onExitSelection = null
        slotsOwner = null
        slotsRoute = null
    }
}

/**
 * The top-level route the shell is drawing, published to the bars composed
 * under it.
 *
 * A bar cannot know which route it belongs to — it is composed by a screen that
 * knows nothing about navigation — so the shell says so once, and each bar tags
 * the slots it publishes with the route it entered on. See
 * [ScreenActionsHolder.actionsFor] for why that tag, and not a clear on the
 * route change, is what keeps a leaving screen's buttons out of the bar.
 */
val LocalScreenActionsRoute = compositionLocalOf<Any?> { null }

/** The holder the shell publishes into, or `null` on a platform that draws its own bar. */
val LocalScreenActions = compositionLocalOf<ScreenActionsHolder?> { null }
