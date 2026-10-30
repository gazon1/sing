package com.singularity.todo.shell

import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The menu's own ids — the thing a test addresses an entry by.
 *
 * The menu is drawn by two surfaces, and both draw the entries of one model.
 * What the model must guarantee is that every entry has an id of its own: an
 * id that is a constant, not something made of the label, because a label is
 * copy the app is free to change and a test that addresses an entry by its
 * label breaks the day somebody rewords it.
 */
@Tag("fast")
class AppMenuTest {

    @Test
    fun everyMenuDestinationHasAnIdOfItsOwn() {
        // The model's ids are built when the model is touched, so a destination
        // that joined the menu without a tag fails here — with the name of the
        // destination in the message — instead of drawing an entry no test can
        // address.
        val ids = AppMenu.destinations.map { it.id }

        assertEquals(
            DestinationKind.menuEntries.size,
            ids.size,
            "Every menu destination must be an entry of the menu.",
        )
        assertEquals(
            ids.size,
            ids.toSet().size,
            "Two menu entries share an id: $ids",
        )
    }

    @Test
    fun everyMenuIdIsAConstantAndNotTheLabel() {
        for (entry in AppMenu.destinations) {
            assertTrue(
                entry.id.startsWith("menu_"),
                "The id of '${entry.destination}' is not a menu id: ${entry.id}",
            )
            assertTrue(
                entry.id != entry.label.lowercase().replace(" ", "").replace("-", "_"),
                "The id of '${entry.destination}' is derived from its label: ${entry.id}",
            )
        }
    }

    @Test
    fun theCalendarIsInTheMenuAndThePlansIsNot() {
        // The Calendar left the bottom bar for the menu, and the Plans tab
        // stayed a tab: the two facts are what the menu's shape is made of.
        assertTrue(AppDestination.Calendar in AppMenu.allDestinations)
        assertTrue(AppDestination.Plans !in AppMenu.allDestinations)
    }

    @Test
    fun attachAccountEntryPointsToAttachAccountDestination() {
        assertEquals(TestTags.MENU_ATTACH_ACCOUNT, AppMenu.attachAccount.id)
        assertEquals(AppDestination.AttachAccount, AppMenu.attachAccount.destination)
    }
}
