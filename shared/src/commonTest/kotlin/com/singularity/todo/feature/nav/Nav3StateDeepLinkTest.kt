package com.singularity.todo.feature.nav

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.nav.deeplink.DeepLinkTarget
import com.singularity.todo.feature.tasks.domain.model.TaskId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a deep link does to the navigation state.
 *
 * The rule under test: a link names a tab *and* a screen, and the tab's stack
 * becomes exactly `[tab, screen]`. That is what makes "back" land on the tab's
 * own screen instead of on whatever the app happened to be showing.
 *
 * Pure JVM — [Nav3State] is not a composable, so the constructor can be driven
 * directly with plain [NavBackStack] instances.
 */
@Tag("fast")
class Nav3StateDeepLinkTest {

    private val today: AppNavKey = AppDestination.AgendaGraph(AgendaStartRoute.Today)
    private val plans: AppNavKey = AppDestination.Plans

    private fun state(): Nav3State = Nav3State(
        startRoute = today,
        topLevelRouteState = mutableStateOf(today),
        backStacks = mapOf(
            today to NavBackStack(today),
            plans to NavBackStack(plans),
        ),
    )

    private fun detail(id: String): AppDestination =
        AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(TaskId(id)))

    @Test
    fun `a link on the current tab rebuilds that tab's stack`() {
        val state = state()

        state.openDeepLink(DeepLinkTarget(today, detail("t1")))

        assertEquals(
            listOf(today, detail("t1")),
            state.requireBackStackFor(today).toList(),
        )
    }

    @Test
    fun `a link on another tab switches to that tab`() {
        val state = state()

        state.openDeepLink(DeepLinkTarget(plans, AppDestination.Pomodoro))

        assertEquals(plans, state.topLevelRoute)
        assertEquals(
            listOf(plans, AppDestination.Pomodoro),
            state.requireBackStackFor(plans).toList(),
        )
    }

    @Test
    fun `the previous tab becomes the start tab, so back does not teleport`() {
        val state = state()

        state.openDeepLink(DeepLinkTarget(plans, AppDestination.Pomodoro))

        assertEquals(today, state.previousTopLevelRoute)
    }

    @Test
    fun `the other tab's stack is left alone`() {
        val state = state()

        state.openDeepLink(DeepLinkTarget(plans, AppDestination.Pomodoro))

        assertEquals(listOf(today), state.requireBackStackFor(today).toList())
    }

    @Test
    fun `applying the same link twice leaves one stack, not two`() {
        val state = state()
        val target = DeepLinkTarget(today, detail("t1"))

        state.openDeepLink(target)
        state.openDeepLink(target)

        assertEquals(listOf(today, detail("t1")), state.requireBackStackFor(today).toList())
    }
}
