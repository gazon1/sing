package spike.flowmvi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.api.SubscriptionMode
import pro.respawn.flowmvi.compose.dsl.subscribe
import pro.respawn.flowmvi.dsl.StoreBuilder
import pro.respawn.flowmvi.dsl.store

/**
 * The narrowest proof that FlowMVI 3.2.1 is usable here: one state, one intent, one
 * action, one store built through the DSL, and the Compose entry point used, so the
 * `compose` artifact's symbols are part of the compilation rather than only of the
 * dependency graph.
 */
data class ProofState(val loaded: Boolean = false) : MVIState

sealed interface ProofIntent : MVIIntent {
    data object Load : ProofIntent
}

sealed interface ProofAction : MVIAction {
    data class Failed(val message: String) : ProofAction
}

fun proofStore(configure: StoreBuilder<ProofState, ProofIntent, ProofAction>.() -> Unit = {}) =
    store(ProofState()) {
        configure()
    }

@Composable
fun ProofScreen(store: Store<ProofState, ProofIntent, ProofAction>): State<ProofState> =
    store.subscribe(mode = SubscriptionMode.Started) { action ->
        when (action) {
            is ProofAction.Failed -> Unit
        }
    }
