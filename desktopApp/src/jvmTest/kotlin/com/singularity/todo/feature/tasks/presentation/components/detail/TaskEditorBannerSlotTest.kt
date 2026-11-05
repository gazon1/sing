package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.text.OwnedText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.feature.tasks.domain.state.TaskEditorSheet
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The editor's offer slot: the first-run banner and the pending AI proposals.
 *
 * The slot has a place of its own — directly under the title and the description, above
 * the attribute rows — so an offer a person is meant to act on does not travel with the
 * child sections the caller happens to pass.
 *
 * The window is 360 dp wide because that is the width the offer has to be reachable at.
 * On a narrow phone the attribute rows and the task's own content fill the screen, and
 * an offer below them is an offer nobody acts on.
 */
@Tag("fast")
@OptIn(ExperimentalTestApi::class)
class TaskEditorBannerSlotTest {
    @Test
    fun `the offer is on screen at 360 dp and above the attribute rows`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                TaskEditorContent(
                    model = TaskEditorModel(),
                    titleSource = OwnedText(text = "", revision = 0),
                    descriptionSource = OwnedText(text = "", revision = 0),
                    onTitleChange = {},
                    onDescriptionChange = {},
                    callbacks =
                        TaskEditorCallbacks(
                            onBack = {},
                            onCheckToggle = null,
                            startDate = null,
                            project = null,
                            tags = null,
                            recurrence = null,
                            pin = null,
                            dependencies = null,
                            checklist = null,
                            attachments = null,
                            estimate = null,
                            onTimeEntryAdd = null,
                            onTimeEntrySave = null,
                            bannerSections = { FirstRunSection(onAskAi = {}) },
                        ),
                    now = NOW,
                    sheets = rememberDialogState<TaskEditorSheet>(),
                )
            }

            // Reachable without scrolling: the offer is on the screen of a 360 dp wide
            // phone, drawn directly under the title and the description.
            assertTagDisplayed(TestTags.FIRST_RUN_SECTION)

            // And above the attribute rows. The priority row is always drawn and it is
            // the first of them, so it is the anchor the order is measured against.
            val offer = unclippedBoundsOfTag(TestTags.FIRST_RUN_SECTION)
            val priority = unclippedBoundsOfTag(TestTags.TASK_EDITOR_PRIORITY_ROW)
            assertTrue(
                offer.bottom <= priority.top,
                "the offer is drawn below the attribute rows: offer $offer, priority $priority",
            )
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-14T12:00:00Z")
    }
}
