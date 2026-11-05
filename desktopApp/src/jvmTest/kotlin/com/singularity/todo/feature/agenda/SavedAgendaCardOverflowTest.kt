package com.singularity.todo.feature.agenda

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.presentation.components.SavedAgendaCard
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlin.time.Instant
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The saved-agenda card's overflow control, addressed the way a test addresses it.
 *
 * The overflow is an `IconButton` whose only content is the text "⋮". The tag used
 * to sit on that text, so the tagged node carried no click action of its own: a
 * test had to inject a click at the label's centre and rely on the enclosing
 * button receiving it, and an Android flow that selected the id selected the
 * label rather than the control.
 *
 * The tag now sits on the button — the node that owns the click action — while the
 * visible label stays "⋮".
 *
 * The lookup uses the **unmerged** tree on purpose: it is where the button is a
 * node of its own, so the assertion is about the control that carries the click
 * action rather than about whatever node the merged tree folds the tag into.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("fast")
class SavedAgendaCardOverflowTest {
    @Test
    fun `the overflow tag sits on the button that owns the click action`() =
        runIsolatedComposeTest {
            setContent { card() }

            val tagged =
                onAllNodes(
                    hasTestTag(TestTags.SAVED_AGENDA_OVERFLOW_BUTTON),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()

            assertEquals(
                1,
                tagged.size,
                "exactly one node may carry the overflow tag",
            )
            assertNotNull(
                tagged.single().config.getOrNull(SemanticsActions.OnClick),
                "the tagged node must be the control that opens the menu, not the " +
                    "\"⋮\" label next to it",
            )
            assertEquals(
                0,
                onAllNodes(
                    hasText("⋮") and hasTestTag(TestTags.SAVED_AGENDA_OVERFLOW_BUTTON),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().size,
                "the \"⋮\" label must no longer carry the tag",
            )
        }

    @Test
    fun `clicking the tagged overflow opens the menu`() =
        runIsolatedComposeTest {
            setContent { card() }

            onAllNodes(
                hasTestTag(TestTags.SAVED_AGENDA_OVERFLOW_BUTTON) and hasClickAction(),
                useUnmergedTree = true,
            ).onFirst().performClick()

            awaitTag(TestTags.SAVED_AGENDA_MENU_EDIT)
            awaitTag(TestTags.SAVED_AGENDA_MENU_COPY_TO_PROFILE)
            awaitTag(TestTags.SAVED_AGENDA_MENU_DELETE)
        }

    @Composable
    private fun card() {
        SavedAgendaCard(
            view = VIEW,
            onClick = {},
            onDelete = {},
            onEdit = {},
            onCopyToProfile = {},
        )
    }

    private companion object {
        val VIEW =
            SavedAgendaView(
                id = SavedAgendaViewId.fromString("saved-agenda-view-1"),
                userId = UserId.anonymous,
                name = "Overflow View",
                sectionsJson = "[]",
                createdAt = Instant.parse("2026-10-05T09:00:00Z"),
                updatedAt = Instant.parse("2026-10-05T10:00:00Z"),
            )
    }
}
