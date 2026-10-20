package com.singularity.todo.test.helpers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * A lifecycle that a test drives by hand.
 *
 * A screen that collects with `collectAsStateWithLifecycle` needs a lifecycle that has
 * reached STARTED; without one it renders nothing, and the test fails on a missing
 * node rather than on anything it is about — the most expensive way to be told that
 * the harness, not the code, is wrong.
 */
class TestLifecycleOwner : LifecycleOwner {

    private val registry: LifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = registry

    /** Moved after the composition exists, so there is something to collect against. */
    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }
}

/**
 * A ViewModel store that a test owns.
 *
 * `koinViewModel()` resolves through the store owner of the composition, so a screen
 * that resolves its ViewModel that way needs one — the app's window supplies it, and a
 * bare `setContent` does not.
 */
class TestViewModelStoreOwner : ViewModelStoreOwner {

    override val viewModelStore: ViewModelStore = ViewModelStore()
}

/**
 * Hosts [content] with the two owners a screen needs: a lifecycle that is already
 * resumed, and a ViewModel store to resolve ViewModels into.
 *
 * The lifecycle is resumed from an effect, after the first composition, so that a
 * screen collecting with `collectAsStateWithLifecycle` has a composition to collect
 * against when the collection starts.
 */
@Composable
fun ScreenWithOwners(content: @Composable () -> Unit) {
    val lifecycleOwner = remember { TestLifecycleOwner() }
    val storeOwner = remember { TestViewModelStoreOwner() }
    CompositionLocalProvider(
        LocalLifecycleOwner provides lifecycleOwner,
        LocalViewModelStoreOwner provides storeOwner,
    ) {
        content()
    }
    LaunchedEffect(lifecycleOwner) { lifecycleOwner.resume() }
}
