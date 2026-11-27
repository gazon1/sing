package spike.flowmvi

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import org.koin.compose.KoinContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.compose.dsl.subscribe
import pro.respawn.flowmvi.dsl.StoreBuilder
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.dsl.updateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Gate 2 of #3234, actually exercised: does a Navigation 3 entry **own** a FlowMVI
 * store — start it, reduce with it, and stop it when the entry leaves the back stack?
 *
 * `Ownership.kt` only showed the pieces are reachable. This drives the real thing: a
 * `NavDisplay` with [rememberViewModelStoreNavEntryDecorator] — the decorator the app's
 * nested graphs apply — a [ProofStoreHolder] resolved through Koin with `koinViewModel`,
 * and an intent sent from the composed entry.
 *
 * Three claims, in the order the review asked for them:
 *
 * 1. the entry resolves a ViewModel store **of its own**, not the composition's;
 * 2. the store is **started**, so an intent sent from the entry actually reduces;
 * 3. popping the entry runs `onCleared`, so the store **stops** — the leak the decorator
 *    exists to prevent, and the part the earlier version never checked.
 */
@OptIn(ExperimentalTestApi::class)
class DestinationOwnershipTest {

    private data object Base : NavKey
    private data object Route : NavKey

    @Test
    fun `a navigation entry starts, reduces with, and stops its flowmvi store`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val koin = koinApplication { modules(ownershipModule()) }.koin
            val outerStore = TestViewModelStoreOwner()
            var entryState: ProofState? = null
            var entryStore: Store<ProofState, ProofIntent, ProofAction>? = null
            var entryViewModelStore: ViewModelStore? = null
            var popped by mutableStateOf(false)

            setContent {
                KoinContext(koin) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides outerStore) {
                        val backStack = remember { NavBackStack<NavKey>(Base, Route) }
                        NavDisplay(
                            backStack = backStack,
                            entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator()),
                            entryProvider = entryProvider {
                                entry<Base> { Box {} }
                                entry<Route> {
                                    val holder: ProofStoreHolder =
                                        koinViewModel { parametersOf(loadReducer) }
                                    val state by holder.store.subscribe { }
                                    entryStore = holder.store
                                    entryViewModelStore = LocalViewModelStoreOwner.current?.viewModelStore
                                    entryState = state
                                    Box {}
                                }
                            },
                        )
                        // Popping the entry above Base is the destination leaving.
                        if (popped) backStack.remove(Route)
                    }
                }
            }
            waitForIdle()

            val store = requireNotNull(entryStore) { "the entry never composed" }

            assertNotSame(
                outerStore.viewModelStore,
                entryViewModelStore,
                "the entry shared the composition's ViewModel store — no decorator applied",
            )
            assertTrue(
                store.isStarted,
                "the holder never started its store, so it could reduce nothing",
            )

            // The store reduces on its own scope, not on the test's frame clock, so
            // `waitForIdle` is not enough — wait for the state it publishes.
            store.intent(ProofIntent.Load)
            waitUntil(timeoutMillis = 5_000) { entryState?.loaded == true }
            assertEquals(
                true,
                entryState?.loaded,
                "an intent sent from the entry did not reduce — the store is not running",
            )
            assertTrue(store.isActive, "the store died while its destination was still on top")

            popped = true
            waitForIdle()

            assertFalse(
                store.isActive,
                "the store outlived its destination — ownership is not destination-scoped",
            )
        }
}

/** What the holder is configured with: one intent that leaves a trace in the state. */
private val loadReducer: StoreBuilder<ProofState, ProofIntent, ProofAction>.() -> Unit = {
    reduce { intent ->
        if (intent is ProofIntent.Load) updateState { copy(loaded = true) }
    }
}

private class TestViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}
