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
 * The shell bar override, on the second of its two publishing sides.
 *
 * [ShellBarOverrideTest] pins the bar that takes the shell over
 * ([TopAppBar] with `overrideShell = true`). This test pins [ShellOverride] —
 * the composable a screen with a header of its own uses instead, so the shell
 * draws that header in place of its own title and back button. It is how the
 * calendar puts the month on the Android shell bar.
 *
 * The contract is the same three fields of [ScreenActionsHolder]: `title`,
 * `navigationIcon` and `override`. Nothing else in this test publishes them, so
 * the compiler can only say the fields exist — it cannot say the effects run:
 * a `SideEffect` that stops writing one field, or a takedown that forgets one,
 * compiles cleanly and no other test looks at this composable. The calendar's
 * shell bar would draw nothing and every test would stay green.
 *
 * Both halves are asserted: what [ShellOverride] publishes while it is in the
 * composition, and what it takes back when it leaves. The holder starts empty,
 * so the first half cannot pass without the publication actually happening.
 */
@Tag("fast")
class ShellOverrideTest {
    @Test
    fun `the override publishes its header and takes it down when it leaves`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val overrideMounted = mutableStateOf(true)

            setContent {
                if (overrideMounted.value) {
                    CompositionLocalProvider(LocalScreenActions provides holder) {
                        ShellOverride(
                            title = { Text("March 2026") },
                            navigationIcon = { Text("Back") },
                        )
                    }
                }
            }
            waitForIdle()

            assertTrue(
                holder.override,
                "a screen that owns its header must claim the shell bar, or the shell draws its own title",
            )
            assertNotNull(
                holder.title,
                "the published title must reach the shell, or the screen's header is drawn nowhere",
            )
            assertNotNull(
                holder.navigationIcon,
                "the published navigation icon must reach the shell, or the shell keeps its back button",
            )

            // The screen leaves: the override goes out of the composition, and
            // what it published must go with it — by the composable that
            // published it, and by nothing else.
            overrideMounted.value = false
            waitForIdle()

            assertFalse(
                holder.override,
                "the override must be taken down when the screen that published it leaves",
            )
            assertNull(
                holder.title,
                "the published title must be taken down with the override, not left for the next screen",
            )
            assertNull(
                holder.navigationIcon,
                "the published navigation icon must be taken down with the override",
            )
        }
}
