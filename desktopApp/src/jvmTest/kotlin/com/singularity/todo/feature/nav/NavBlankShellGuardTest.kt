@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.nav

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import com.singularity.todo.test.helpers.TestViewModelStoreOwner
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shell must never render nothing.
 *
 * `NavDisplay` throws `IllegalArgumentException: entries cannot be empty` on an empty
 * entry list, and a shell that renders nothing has no way out — the failure REQ-DN-003
 * exists to prevent. The guard sits inside [Nav3State.toDecoratedEntries], a composable,
 * which is why the plain navigation-state unit tests cannot reach it. This test drives the
 * conversion itself, with every back stack emptied through the public navigation state.
 *
 * Every composition's entry list is recorded, not only the settled one: the defect this
 * pins was a *frame* that rendered nothing while the settled state looked fine.
 */
@Tag("slow")
@Tag("slow:compose")
class NavBlankShellGuardTest {
    @Test
    fun `emptying every back stack still yields entries on every pass and reseeds the start stack`() =
        runIsolatedComposeTest {
            val passes = mutableListOf<List<NavEntry<NavKey>>>()
            lateinit var state: Nav3State

            setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides TestViewModelStoreOwner()) {
                    state = rememberNav3State()
                    state.toDecoratedEntries { key: AppDestination -> NavEntry(key) {} }
                        .also { passes += it }
                }
            }
            waitForIdle()

            assertTrue(
                passes.isNotEmpty() && passes.all { it.isNotEmpty() },
                "The seeded state produced no entries — the test measured nothing.",
            )

            val passesBeforeEmptying = passes.size
            // Empty every tab, the way a restored blank state would arrive.
            runOnIdle {
                state.topLevelRoutes.forEach { state.requireBackStackFor(it).clear() }
            }
            waitForIdle()

            assertTrue(
                passes.size > passesBeforeEmptying,
                "Emptying the back stacks did not recompose the conversion — the test measured nothing.",
            )
            assertTrue(
                passes.drop(passesBeforeEmptying).none { it.isEmpty() },
                "A composition pass ran with every back stack empty and returned no entries — " +
                    "NavDisplay throws on that list, so that frame is a blank shell.",
            )
            assertEquals(
                listOf(state.startRoute),
                state.requireBackStackFor(state.startRoute).toList(),
                "The start stack was not reseeded with its own route.",
            )
        }
}
