@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.projects

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.singularity.todo.core.ui.components.OverlayState
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.screen.EditorSheet
import com.singularity.todo.feature.projects.presentation.screen.ProjectEditorSheets
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTextGone
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Open+dismiss smoke tests for the project editor's two picker sheets (#2133).
 *
 * ## What is under test
 *
 * [ProjectEditorSheets] — the production composable `ProjectEditorContent` uses
 * to show the Icon and Parent pickers. It owns the three things the issue asks
 * about, and all three are exercised here:
 *
 *  1. the request that opens a sheet (`overlay.show(EditorSheet.Icon)` /
 *     `overlay.show(EditorSheet.Parent)`),
 *  2. the host that renders the picker while such a request is active,
 *  3. the dismissal — a pick closes the sheet again.
 *
 * The open lambdas these tests press are the ones *production hands the
 * Appearance and Organization chips* through the `content` slot, not lambdas the
 * test wrote for itself. A chip whose request stops showing `EditorSheet.Icon`,
 * or a host that stops rendering on it, fails these tests rather than passing
 * them.
 *
 * ## Why this shape, and not `ProjectEditorContent`
 *
 * `ProjectEditorContent` takes a `ProjectEditorViewModel`, so mounting it needs
 * the project repository, its use cases and a lifecycle-scoped coroutine scope.
 * That is more graph than an open+dismiss smoke test should stand up, and it is
 * not where the sheet wiring lives: the two chips call the lambdas this file
 * tests. What remains uncovered is the one line in each chip —
 * `FilterChip(onClick = openIconPicker)` — between the widget and the request.
 *
 * ## Why every test asserts "closed" first
 *
 * An earlier version started each test with the sheet already open, so it only
 * proved the dismissal; a regression that stopped the editor *asking* for a
 * sheet passed both tests green. Each test here asserts the sheet's own text is
 * absent, presses the production request, and only then asserts it is on screen.
 *
 * ## Why the window is 420×1600
 *
 * Both pickers are shown through `BottomSheetHost`, so both draw inside a
 * `ModalBottomSheet`, whose own window has a fixed height (~1080) that does not
 * follow the test window. At the default 768 px the sheet's content is composed
 * but lands below the visible area, where a click cannot reach it — so the icon
 * test needs the tall window for the same reason the parent test does.
 */
@Tag("slow")
@Tag("slow:compose")
class ProjectEditorSheetSmokeTest {
    @Test
    fun `icon sheet opens and dismisses on picking None`() =
        runIsolatedComposeTest(width = 420, height = 1600) {
            val overlay = OverlayState<EditorSheet>()
            var pickCalls = 0
            var pickedIcon: String? = null

            setContent {
                ProjectEditorSheets(
                    overlay = overlay,
                    currentIcon = null,
                    onPickIcon = {
                        pickCalls++
                        pickedIcon = it
                    },
                    onPickParent = {},
                ) { openIconPicker, _ ->
                    // Stands in for the Appearance section's icon chip; the lambda is
                    // the one production hands that chip.
                    TextButton(onClick = openIconPicker) { Text("Open icon sheet") }
                }
            }

            // Closed: "None" belongs to the icon picker alone, so its absence is the
            // proof that what follows is not asserting against a pre-opened sheet.
            onAllNodesWithText("None").assertCountEquals(0)

            clickText("Open icon sheet")

            // Open: the sheet's title and its "None" action are on screen.
            assertTextDisplayed("Icon")
            assertTextDisplayed("None")

            clickText("None")

            // State change: the pick reached the editor, and it was the root icon
            // (null). The call count carries the evidence — the value alone cannot,
            // since null is also "not picked".
            assertEquals(1, pickCalls, "picking None must reach the editor as a pick")
            assertNull(pickedIcon, "picking None must clear the icon")

            // Dismiss: the host closed the sheet when the pick came back.
            awaitTextGone("None")
        }

    @Test
    fun `parent sheet opens and dismisses on picking the root project`() =
        runIsolatedComposeTest(width = 420, height = 1600) {
            val overlay = OverlayState<EditorSheet>()
            var pickCalls = 0
            var pickedParent: ProjectId? = null

            setContent {
                ProjectEditorSheets(
                    overlay = overlay,
                    currentIcon = null,
                    onPickIcon = {},
                    onPickParent = {
                        pickCalls++
                        pickedParent = it
                    },
                ) { _, openParentPicker ->
                    // Stands in for the Organization section's "Parent project" chip.
                    TextButton(onClick = openParentPicker) { Text("Open parent sheet") }
                }
            }

            // Closed: "None (root project)" exists only inside the parent picker.
            onAllNodesWithText("None (root project)").assertCountEquals(0)

            clickText("Open parent sheet")

            // Open: the sheet's title and its root-project action are on screen.
            assertTextDisplayed("Parent project")
            assertTextDisplayed("None (root project)")

            clickText("None (root project)")

            // State change: the pick reached the editor, and it carries no parent —
            // the root project. Again the count, not the null, is the evidence.
            assertEquals(1, pickCalls, "picking the root project must reach the editor as a pick")
            assertNull(pickedParent, "the root-project pick must carry no parent")

            // Dismiss: the host closed the sheet when the pick came back.
            awaitTextGone("None (root project)")
        }
}
