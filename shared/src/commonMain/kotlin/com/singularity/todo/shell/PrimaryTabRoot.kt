package com.singularity.todo.shell

import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination

/**
 * Whether the screen on top is the root of a primary tab.
 *
 * The Inbox, Today and Calendar roots are not screens in a back stack: they are
 * where a tab starts. A Back arrow there promises a step back that the root has
 * no meaning for — the user asked for this tab, and the tab is where they are.
 *
 * The arrow was drawn anyway, because the shell's leading control was derived
 * from the navigation state alone: after a tab switch that state says "there is
 * a previous tab to go back to", and the root drew an arrow for it (#3093). The
 * state still says so — nothing here changes what a back *does* — but the root
 * does not draw the arrow any more.
 *
 * The decision is typed: it is made from the route, never from a title or a
 * label. The agenda's own route comes from the route its graph publishes (see
 * [com.singularity.todo.feature.nav.AgendaRouteHolder]), so the Inbox and Today
 * roots are told apart from the drill-downs that live inside them — the saved
 * views, the project and tag views — which keep their own Back.
 *
 * @param target the destination whose screen is on top, with the agenda's inner
 *   route folded in — the same resolution the shell's button uses (see
 *   [shellFabTarget]), so the two shell decisions classify a screen alike.
 */
internal fun isPrimaryTabRoot(target: AppDestination): Boolean =
    when (target) {
        AppDestination.Calendar -> {
            true
        }

        is AppDestination.AgendaGraph -> {
            target.start == AgendaStartRoute.Inbox || target.start == AgendaStartRoute.Today
        }

        // Every other destination keeps the Back it has: a menu entry, a pushed
        // screen, a drill-down. Only the three roots above are tab roots.
        else -> {
            false
        }
    }
