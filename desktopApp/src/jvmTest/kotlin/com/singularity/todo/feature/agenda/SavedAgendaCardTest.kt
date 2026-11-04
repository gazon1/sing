package com.singularity.todo.feature.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaViewFactory
import com.singularity.todo.feature.agenda.presentation.components.SavedAgendaCard
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

/**
 * Desktop component test for [SavedAgendaCard]'s overflow menu.
 *
 * The card's menu is a `DropdownMenu`, which opens **its own window** on desktop.
 * The question this suite answers is the one the whole app's overflow menus hinge
 * on: does pressing an item *inside* that window actually call the callback the
 * item was given?
 *
 * The card is mounted alone, because that is where the callback can be observed
 * directly — a flow test can only observe what the callback leads to.
 */
@OptIn(ExperimentalTestApi::class)
class SavedAgendaCardTest {
    private val view =
        SavedAgendaViewFactory.create(
            userId = UserId.anonymous,
            name = "Weekly Review",
            sectionsJson = """{"title":"Weekly Review","sections":[]}""",
            now = Clock.System.now(),
        )

    @Test
    fun pressing_edit_in_the_overflow_menu_calls_on_edit() =
        runIsolatedComposeTest {
            var edits = 0
            setContent {
                SavedAgendaCard(
                    view = view,
                    onClick = {},
                    onDelete = {},
                    onEdit = { edits++ },
                    onCopyToProfile = {},
                )
            }

            // The overflow is a "⋮" label with no test tag.
            clickText("⋮")
            clickTag(TestTags.SAVED_AGENDA_MENU_EDIT)

            assertEquals(1, edits, "the Edit item of the overflow menu did not call onEdit")
        }
}
