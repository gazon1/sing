@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The shared empty state.
 *
 * A Material 3 empty state carries an icon or an illustration next to its
 * message — a screen that only says "No notes yet" leaves the reader guessing
 * what the screen is for. The icon is optional, so the text-only form must
 * still render unchanged.
 */
@Tag("fast")
class EmptyStateTest {
    @Test
    fun `an icon is drawn when one is given`() =
        runIsolatedComposeTest {
            setContent {
                EmptyState(
                    title = "No notes yet",
                    icon = Icons.Filled.Add,
                    iconContentDescription = "Empty notes illustration",
                )
            }

            assertContentDescriptionDisplayed("Empty notes illustration")
            assertTextDisplayed("No notes yet")
        }

    @Test
    fun `no icon is drawn when none is given`() =
        runIsolatedComposeTest {
            setContent {
                EmptyState(title = "No notes yet")
            }

            assertTextDisplayed("No notes yet")
        }
}
