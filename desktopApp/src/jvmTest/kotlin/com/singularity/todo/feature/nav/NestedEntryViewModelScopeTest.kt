@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.nav

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.test.helpers.TestViewModelStoreOwner
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/**
 * Does a nested `NavDisplay` give its entry its own ViewModel store?
 *
 * ## Why this test exists
 *
 * The nested graphs carried a platform split that nobody had measured: Android
 * applied the ViewModel-store decorator, Desktop did not, and the Desktop files
 * *claimed* that "each NavDisplay entry already has proper per-entry ViewModel
 * scoping". Nothing in nav3 provides that scoping — the decorator is the only
 * thing that does — so the claim needed a measurement before anyone built on it.
 *
 * The measurement: on Desktop, without the decorator, the entry inherited the
 * outer store. Koin keys a ViewModel by its class alone — **not** by the
 * parameters — so two entries sharing a store get the *same* ViewModel instance:
 * the second screen silently reuses the first one's state, with the first one's
 * parameters.
 *
 * ## What this test holds now
 *
 * The nested graphs apply [rememberAppEntryDecorators], and this test holds that
 * helper to its promise: with it, the entry resolves a store of its own.
 *
 * ## One entry, not two
 *
 * The single-pane scene strategy composes the top entry only, so a second entry
 * would never run and the test would measure nothing. The store a *second* entry
 * would get is decided by the same decorator, so one entry answers the question.
 */
@Tag("slow")
class NestedEntryViewModelScopeTest {

    /** A route of a nested graph. */
    private data object ProbeRoute : NavKey

    @Test
    fun `a nested entry does not share the outer view model store`() = runIsolatedComposeTest {
        val entryStores = mutableListOf<Any>()
        val owner = TestViewModelStoreOwner()

        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                NavDisplay(
                    backStack = NavBackStack(ProbeRoute),
                    entryDecorators = rememberAppEntryDecorators(),
                    entryProvider = entryProvider {
                        entry<ProbeRoute> {
                            entryStores += requireNotNull(LocalViewModelStoreOwner.current).viewModelStore
                        }
                    },
                )
            }
        }
        waitForIdle()

        assertEquals(
            1,
            entryStores.size,
            "The nested entry never composed — the test measured nothing.",
        )
        assertNotSame(
            owner.viewModelStore,
            entryStores[0],
            "The nested entry resolved the outer ViewModel store — the nested NavDisplay " +
                "does not scope the store per entry.",
        )
    }
}
