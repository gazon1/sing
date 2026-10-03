package com.singularity.todo.feature.nav

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation3.runtime.NavKey

/**
 * Resets [listState] to the top when its tab is reselected.
 *
 * Tapping the tab you are already on emits a [Nav3State.reselectEvents] value carrying that
 * tab's route. Screens opt into the platform behaviour by calling this once with the route
 * that owns the list:
 *
 * ```kotlin
 * val listState = rememberLazyListState()
 * TabReselectScrollReset(route = AgendaStartRoute.Today, listState = listState)
 * LazyColumn(state = listState) { /* … */ }
 * ```
 *
 * Events for other tabs are ignored, so several screens may observe the same flow without
 * coordinating with each other.
 *
 * [LaunchedEffect] keyed on [route] cancels any in-flight `animateScrollToItem` when the
 * screen is recomposed with a different tab, so a rapid double-tap cannot leave two
 * animations fighting over the same list.
 *
 * @param route The top-level route this list belongs to.
 * @param listState The list to reset.
 * @param state The navigation state to observe. Defaults to the app-wide [Nav3State] holder.
 */
@Suppress("FunctionSignature")
@Composable
fun TabReselectScrollReset(route: NavKey, listState: LazyListState, state: Nav3State = LocalNav3State.current) {
    val currentState = rememberUpdatedState(state)
    LaunchedEffect(route) {
        currentState.value.reselectEvents.collect { reselected ->
            if (reselected == route) {
                listState.scrollToItem(0)
            }
        }
    }
}
