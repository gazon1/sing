package com.singularity.todo.feature.projects

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.projects.presentation.components.EditorParentPickerSheet
import com.singularity.todo.feature.projects.presentation.components.IconPickerSheet
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The project editor's picker sheets, closed without choosing anything.
 *
 * Both sheets are modal, and both could only be left by picking a value — the
 * "None" and "None (root project)" actions — or by tapping the sheet's scrim.
 * Neither carried a labelled dismiss, so "cancel without changing anything" had
 * no name a test or a screen reader could address: the only way to observe a
 * dismissal from a UI test was to close the sheet through a pick.
 *
 * Each sheet now carries a dismiss control of its own. It dismisses and nothing
 * else — the field it edits is left exactly as it was.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("fast")
class ProjectPickerDismissTest {
    @Test
    fun `the icon picker closes without picking an icon`() =
        runIsolatedComposeTest {
            var pickCalled = false
            var dismissed = false

            setContent {
                IconPickerSheet(
                    currentIcon = null,
                    onPick = { pickCalled = true },
                    onDismiss = { dismissed = true },
                )
            }

            clickTag(TestTags.PROJECT_PICKER_DISMISS)

            assertTrue(dismissed, "the dismiss control must dismiss the sheet")
            assertFalse(pickCalled, "dismissing must not pick an icon")
        }

    @Test
    fun `the parent picker closes without picking a parent`() =
        runIsolatedComposeTest {
            var pickCalled = false
            var dismissed = false

            setContent {
                EditorParentPickerSheet(
                    onPick = { pickCalled = true },
                    onDismiss = { dismissed = true },
                )
            }

            clickTag(TestTags.PROJECT_PICKER_DISMISS)

            assertTrue(dismissed, "the dismiss control must dismiss the sheet")
            assertFalse(pickCalled, "dismissing must not pick a parent")
        }
}
