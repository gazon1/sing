package com.singularity.todo.feature.nav

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("fast")
class Nav3StateFallbackTest {

    private val today: AppNavKey = AppDestination.AgendaGraph(AgendaStartRoute.Today)

    @Test
    fun `goBack reseeds an empty stack instead of leaving it empty`() {
        val state = Nav3State(
            startRoute = today,
            topLevelRouteState = mutableStateOf(today),
            backStacks = mapOf(today to NavBackStack(today)),
        )
        val navigator = Navigator(state)
        state.requireBackStackFor(today).clear()
        navigator.goBack()
        assertEquals(listOf(today), state.requireBackStackFor(today).toList())
    }
}
