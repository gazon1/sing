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
 *
 * ## Where the answer is drawn
 *
 * The shell owns the only bar, so the shell asks the question and draws the
 * answer — in the shell's own composition, not in the screen's. A screen that
 * publishes an icon therefore publishes *content*: a composable that reads
 * Compose state and nothing else. The state it reads is subscribed to where it
 * is drawn, so a screen that changes what its icon says is redrawn like any
 * other state change; a screen that changes *whether* it has an icon changes
 * the answer, and the shell asks again on the next frame.
 *
 * The shell keys what it draws by the publishing screen
 * ([ScreenActionsHolder.headerOwner]), so state remembered inside a published
 * header belongs to that screen and does not survive into the next one.
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
 * ## What belongs to whom
 *
 * The header and the slots have deliberately different lifetimes, and both are
 * held **per owner**: every publication and every claim is recorded against
 * the screen that made it, and only that screen can take it down. Nothing here
 * is a single slot that the last writer wins — that is what let one screen's
 * leave erase another's header (#2008) and one bar's leave empty another
 * bar's buttons (#1962 review).
 *
 * - **The header** ([publishBar], [retractBar]) belongs to the *screen* that
 *   published it and to nothing else. Only that screen takes it down, from its
 *   own `DisposableEffect`, and only its own: the publication carries the
 *   identity of the composable that made it, so a screen leaving during a
 *   navigation cannot erase the header the arriving screen has already
 *   published (#2008). When the newest publication goes, the one before it
 *   takes over — a screen that is still on screen keeps its header even while
 *   another screen's header was drawn over it, which is what brings the
 *   agenda's greeting back when the user returns to the agenda tab.
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
    /**
     * The header each screen published, oldest first.
     *
     * One entry per publishing screen. The newest entry is the header the
     * shell draws; the entries behind it are the headers of the screens that
     * are still composed but are not the ones the shell is drawing.
     */
    private var publications: List<Publication> by mutableStateOf(emptyList())

    /**
     * The action slots each bar claimed, oldest first.
     *
     * One entry per claiming bar, tagged with the top-level route the bar was
     * composed for. A bar that never claimed — a screen that publishes a
     * header only — has no entry here and cannot touch anybody else's.
     */
    private var claims: List<Claim> by mutableStateOf(emptyList())

    /** A header a screen published. */
    private data class Publication(
        val owner: Any,
        val title: @Composable () -> Unit,
        val navigationIcon: PublishedNavigationIcon?,
    )

    /** The action slots a bar claimed. */
    private data class Claim(
        val owner: Any,
        val route: Any?,
        val actions: (@Composable RowScope.() -> Unit)?,
        val selectionTitle: String?,
        val onExitSelection: (() -> Unit)?,
    )

    /** The title the shell draws, or `null` when no screen published a header. */
    val title: (@Composable () -> Unit)? get() = publishedHeader?.title

    /** The navigation icon the shell asks about, or `null` when there is no header. */
    val navigationIcon: PublishedNavigationIcon? get() = publishedHeader?.navigationIcon

    /**
     * Whether the published header replaces the shell's own title and
     * navigation icon. Derived from [title] and not stored beside it, so "this
     * screen owns the bar" cannot disagree with "this screen published a
     * title".
     */
    val override: Boolean get() = publishedHeader != null

    /**
     * The screen whose header the shell draws.
     *
     * The shell keys what it draws by it, so that state remembered inside a
     * published header belongs to the screen that published it and does not
     * survive into the next one's header.
     */
    val headerOwner: Any? get() = publishedHeader?.owner

    private val publishedHeader: Publication? get() = publications.lastOrNull()

    /**
     * The action slots of the bar that belongs to [route], or `null` when the
     * bar that claimed them was composed for another route.
     *
     * This is the whole of "the shell drops the previous screen's buttons on a
     * navigation". It is a **read**, not a write: the shell asks for the route
     * it is drawing, so the answer is already right in the composition that
     * swaps the route. There is no clear that can run too late, and no state
     * write during composition to invalidate the scope that had just read it
     * (#1962 review).
     */
    fun actionsFor(route: Any?): (@Composable RowScope.() -> Unit)? = claimFor(route)?.actions

    /** The selection title of the bar that belongs to [route]; see [actionsFor]. */
    fun selectionTitleFor(route: Any?): String? = claimFor(route)?.selectionTitle

    /** The exit-selection callback of the bar that belongs to [route]; see [actionsFor]. */
    fun onExitSelectionFor(route: Any?): (() -> Unit)? = claimFor(route)?.onExitSelection

    /**
     * The claim of the bar that belongs to [route] — the newest one, so that a
     * bar that re-entered on the same route is the one whose buttons are
     * drawn.
     */
    private fun claimFor(route: Any?): Claim? = claims.lastOrNull { it.route === route }

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
        val publication = Publication(owner = owner, title = title, navigationIcon = navigationIcon)
        publications = publications.filterNot { it.owner === owner } + publication
    }

    /**
     * Takes the published header down — the one this [owner] published, and
     * only that one.
     *
     * A screen calls this from its own `DisposableEffect` when it leaves the
     * composition. During a navigation two screens are composed for the length
     * of the transition, and the one leaving is usually disposed *after* the
     * one arriving has published; an unconditional clear here would erase the
     * arriving screen's header and leave the bar with the shell's destination
     * title (#2008). With the owner check the leaving screen can only take down
     * its own header, and nothing else takes it down for it.
     *
     * When the header taken down is the one the shell is drawing, the header
     * behind it takes over: a screen that is still composed keeps its header
     * even though another screen's header was drawn over it in the meantime.
     *
     * The action slots are not touched: they are not part of the header, and
     * they are claimed and released through [claimActions] and [clearActions].
     */
    fun retractBar(owner: Any) {
        publications = publications.filterNot { it.owner === owner }
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
     * A claim is recorded against its owner, so a second bar claiming on the
     * same route does not orphan the first one's claim: each bar clears its
     * own, and the bar the shell draws from is the newest claim for the route
     * it is drawing.
     *
     * A screen that publishes a header only (the calendar, through
     * [ShellOverride]) does not claim the slots: it has no buttons of its own.
     */
    fun claimActions(owner: Any, route: Any?) {
        claims =
            claims.filterNot { it.owner === owner } +
                Claim(owner = owner, route = route, actions = null, selectionTitle = null, onExitSelection = null)
    }

    /**
     * Publishes the bar's action slots — but only into the claim that bar
     * made.
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
        val claim = claims.firstOrNull { it.owner === owner } ?: return
        claims =
            claims.map {
                if (it.owner === owner) {
                    claim.copy(
                        actions = actions,
                        selectionTitle = selectionTitle,
                        onExitSelection = onExitSelection,
                    )
                } else {
                    it
                }
            }
    }

    /**
     * Clears the action slots — the ones this [owner] claimed, and only those.
     *
     * A screen calls this as it leaves the composition. A bar leaving *after*
     * the arriving screen has claimed the slots must leave them alone: the
     * arriving bar's claim is a record of its own, and emptying it would take
     * the arriving screen's buttons away.
     *
     * The claim that goes is this bar's own, so a claim nobody releases — a
     * bar that claimed and never came back — cannot leave the previous bar's
     * route on record: the record and its owner are one thing, and the owner
     * is what removes it.
     */
    fun clearActions(owner: Any) {
        claims = claims.filterNot { it.owner === owner }
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
