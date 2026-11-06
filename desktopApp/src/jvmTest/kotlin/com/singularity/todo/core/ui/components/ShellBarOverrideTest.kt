@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shell bar override, on the publishing side.
 *
 * On Android the shell owns the only bar, so a screen that draws its own header
 * publishes that header to the shell instead of drawing a second bar. Two
 * composables do that publishing: [TopAppBar] with `overrideShell = true`,
 * which this test pins, and [ShellOverride], which [ShellOverrideTest] pins.
 * The contract both keep with the shell is three fields of
 * [ScreenActionsHolder]: `title`, `navigationIcon` and `override`.
 *
 * Nothing asserted that contract before this test. The only test that touched
 * the holder checked that its fields start empty — which is a statement about
 * the constructor, not about the publication. So a screen could publish into a
 * holder that had no such field, the shell would draw nothing, and neither the
 * compiler nor any test would have said so: that is how #1698 left the Android
 * bar with no title at all.
 *
 * The test therefore mounts the bar alone, with a holder provided through
 * [LocalScreenActions], and asserts both halves of the contract: what the bar
 * publishes while it is there, and what it takes back when it leaves.
 */
@Tag("fast")
class ShellBarOverrideTest {
    @Test
    fun `the bar publishes its own header and takes the override down when it leaves`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val barVisible = mutableStateOf(true)

            setContent {
                if (barVisible.value) {
                    CompositionLocalProvider(LocalScreenActions provides holder) {
                        TopAppBar(
                            title = { Text("Calendar") },
                            navigationIcon = { Text("Back") },
                            overrideShell = true,
                        )
                    }
                }
            }
            waitForIdle()

            assertTrue(
                holder.override,
                "a bar that takes the shell over must say so, or the shell draws its own title",
            )
            assertNotNull(
                holder.title,
                "the published title must reach the shell, or the bar shows nothing",
            )
            assertNotNull(
                holder.navigationIcon,
                "the published navigation icon must reach the shell, or the shell keeps its back button",
            )

            // The screen leaves: the bar goes out of the composition, and the
            // override it published must go with it — by the bar that published
            // it, and by nothing else.
            barVisible.value = false
            waitForIdle()

            assertFalse(
                holder.override,
                "the bar that published the override must take it down when it leaves",
            )
            assertNull(
                holder.title,
                "the published title must be taken down with the override",
            )
            assertNull(
                holder.navigationIcon,
                "the published navigation icon must be taken down with the override",
            )
        }
}
