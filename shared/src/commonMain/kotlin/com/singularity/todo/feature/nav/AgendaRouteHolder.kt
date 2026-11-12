package com.singularity.todo.feature.nav

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The route the agenda's nested graph is showing, published to the shell.
 *
 * The agenda graph owns a stack of its own ([AgendaStartRoute] keys), and the shell
 * that draws the button over the agenda sits *above* that graph. The shell can see
 * the graph as one destination, but not the route inside it, so the button over the
 * agenda was resolved from the graph's *start* route — the tab it was opened from.
 *
 * Every screen inside the agenda therefore got the button of the tab it was opened
 * from, including the saved-view screens, which carry no shell button at all
 * ([FabActionResolver]) and draw their own. The shell's button was drawn on top of
 * that button, so on Android the saved-views screen had no reachable create button
 * at all, and the Maestro agenda flows could not get past their first assertion
 * there.
 *
 * The graph publishes the route it is on here and the shell resolves the button
 * from it. One owner each: the graph writes, the shell reads.
 */
@Stable
class AgendaRouteHolder {
    /** The route the agenda graph is on, or `null` while no agenda graph is composed. */
    var route: AgendaStartRoute? by mutableStateOf(null)
        private set

    /** Called by the agenda graph when it shows [route]. */
    fun publish(route: AgendaStartRoute) {
        this.route = route
    }

    /** Called by the agenda graph when it leaves composition. */
    fun clear() {
        this.route = null
    }
}

/**
 * The holder a shell provides and the agenda graph publishes to.
 *
 * `null` where no shell provides one — a preview, or a test that mounts a screen
 * without a shell around it — and the graph then publishes nothing, leaving the
 * shell on the behaviour it had before: the button of the tab the agenda opened
 * with.
 */
val LocalAgendaRouteHolder = compositionLocalOf<AgendaRouteHolder?> { null }
