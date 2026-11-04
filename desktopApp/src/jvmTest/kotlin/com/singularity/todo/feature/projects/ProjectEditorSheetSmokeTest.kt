@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.projects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.feature.projects.presentation.components.EditorParentPickerSheet
import com.singularity.todo.feature.projects.presentation.components.IconPickerSheet
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTextGone
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Open+dismiss smoke tests for the project editor's two picker sheets.
 *
 * ## Why the sheets are mounted directly
 *
 * `ProjectEditorScreen` creates its ViewModel through `koinViewModel()`, and the
 * isolated Compose harness starts no Koin graph — mounting the screen throws
 * before a sheet can be reached. `ProjectEditorContent` takes the ViewModel as a
 * parameter, but building one needs the whole repository graph, which is more
 * than an open+dismiss smoke test should need.
 *
 * These are the two surfaces the editor hosts, mounted with the editor's own
 * wiring: picking closes the sheet and hands the value back. That is also the
 * dismissal a person can perform — the icon sheet has no close control of its
 * own, and a `ModalBottomSheet`'s scrim tap is not something the test API can
 * aim at — so the pick *is* the dismiss path, and each test asserts the open
 * first, then the pick, then that the sheet is gone. A sheet that never opened,
 * or a pick that never closed it, fails instead of passing quietly.
 *
 * `onDismiss` is wired as the editor wires it (the scrim/back path) but is not
 * scripted: there is no addressable affordance to press.
 *
 * ## Why the window is 420×1600
 *
 * A `ModalBottomSheet` draws in its own window of a fixed height (~1080) that
 * does not follow the test window; at the default 768 px the sheet's content
 * ends up below the window, composed but untappable.
 */
@Tag("slow")
@Tag("slow:compose")
class ProjectEditorSheetSmokeTest {
    @Test
    fun `icon sheet opens and dismisses on picking None`() =
        runIsolatedComposeTest(width = 420, height = 1600) {
            var open by mutableStateOf(true)
            var picked = false
            var pickedIcon: String? = "sentinel"

            setContent {
                if (open) {
                    IconPickerSheet(
                        currentIcon = null,
                        onPick = {
                            picked = true
                            pickedIcon = it
                            open = false
                        },
                        onDismiss = { open = false },
                    )
                }
            }

            // Open: the sheet's title and its "None" action are on screen.
            assertTextDisplayed("Icon")
            assertTextDisplayed("None")

            clickText("None")

            // State change: the pick reached the editor, and it was the root
            // icon (null) rather than some other icon.
            assertTrue(picked, "picking None must reach the editor as a pick")
            assertNull(pickedIcon, "picking None must clear the icon")

            // Dismiss: the sheet left the scene.
            awaitTextGone("None")
        }

    @Test
    fun `parent sheet opens and dismisses on picking the root project`() =
        runIsolatedComposeTest(width = 420, height = 1600) {
            var open by mutableStateOf(true)
            var picked = false
            var pickedParent: Any? = "sentinel"

            setContent {
                if (open) {
                    EditorParentPickerSheet(
                        onPick = {
                            picked = true
                            pickedParent = it
                            open = false
                        },
                        onDismiss = { open = false },
                    )
                }
            }

            // Open: the sheet's title and its root-project action are on screen.
            // "None (root project)" exists nowhere else, so finding it is the
            // proof that the sheet opened.
            assertTextDisplayed("Parent project")
            assertTextDisplayed("None (root project)")

            clickText("None (root project)")

            // State change: the pick reached the editor, and it carries no
            // parent — the root project.
            assertTrue(picked, "picking the root project must reach the editor as a pick")
            assertNull(pickedParent, "the root-project pick must carry no parent")

            // Dismiss: the sheet left the scene.
            awaitTextGone("None (root project)")
        }
}
