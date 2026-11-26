package spike.flowmvi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.dsl.StoreBuilder
import pro.respawn.flowmvi.dsl.store

/**
 * Gate 2 of #3234: does a FlowMVI store fit the destination-ownership slot the app
 * already uses under Navigation 3?
 *
 * The app scopes a destination with `rememberViewModelStoreNavEntryDecorator`, which
 * clears the ViewModels that destination owns when its entry leaves the back stack. A
 * FlowMVI store is **not** a ViewModel, so on its own it sits outside that scope. The
 * wrapper below is the whole seam: one ViewModel that owns the store, so Navigation 3's
 * decorator is what stops it. It compiles against Koin 4.2.2 and `koinViewModel`, which
 * is what makes "Nav3 integration" a measured claim rather than a README claim.
 */
class ProofStoreHolder(val store: Store<ProofState, ProofIntent, ProofAction>) : ViewModel()

fun ownershipModule(): Module = module {
    viewModel { (configure: StoreBuilder<ProofState, ProofIntent, ProofAction>.() -> Unit) ->
        ProofStoreHolder(store(ProofState()) { configure() })
    }
}

/** The store outlives the composition, but not the destination, because the VM owns it. */
@Composable
fun ProofDestination(
    configure: StoreBuilder<ProofState, ProofIntent, ProofAction>.() -> Unit = {},
): Unit {
    val holder: ProofStoreHolder = koinViewModel { parametersOf(configure) }
    remember(holder) { holder.store }
}

/** Compile-time proof that the actionless ViewModel API the app uses is reachable too. */
@Composable
fun ProofPlainViewModel(): ViewModel = viewModel<ViewModel>()

/** Compile-time proof that a Koin graph can be built and resolved here. */
fun resolveHolder(): ProofStoreHolder = koinApplication {
    modules(ownershipModule())
}.koin.get<ProofStoreHolder>()
