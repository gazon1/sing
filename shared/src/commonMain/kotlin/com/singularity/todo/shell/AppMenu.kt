package com.singularity.todo.shell

import androidx.compose.ui.graphics.vector.ImageVector
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.feature.nav.icon

/**
 * One entry of the app menu.
 *
 * @param id The stable id of the entry. It is what a test addresses the entry
 *   by, and it must not move when the label is reworded.
 * @param label The text the user reads.
 * @param destination Where the entry goes.
 * @param icon The icon of the entry, if it has one.
 */
data class MenuEntry(val id: String, val label: String, val destination: AppDestination, val icon: ImageVector? = null)

/**
 * The app menu, as one model both platforms draw.
 *
 * ## Why the model is shared
 *
 * The Android sheet and the Desktop drawer are drawn by different code, and
 * before this model each had its own list. They drifted: the sheet had a
 * "Destinations" section the drawer did not, and the drawer had entries the
 * sheet did not. "The same menu on both platforms" was then a claim a reviewer
 * had to check by eye.
 *
 * One model makes it a fact: both surfaces draw these lists, so they offer
 * the same entries — and the tree, which is drawn by one composable, is the
 * same tree.
 *
 * ## The sections
 *
 * The sheet groups the entries into Account, Search and Destinations, because
 * that grouping is what the sheet has always had. The drawer shows the
 * destinations in one column, because that is what a drawer is. The *entries*
 * are the same either way, and the grouping is a matter of layout.
 */
object AppMenu {

    /** The entries about the account. */
    val account: List<MenuEntry> = listOf(
        MenuEntry(
            id = TestTags.MENU_SETTINGS,
            label = "Profile & sync",
            destination = AppDestination.Settings,
            icon = AppDestination.Settings.icon,
        ),
        MenuEntry(
            id = TestTags.MENU_PROFILES,
            label = AppDestination.ProfileSwitcher.title,
            destination = AppDestination.ProfileSwitcher,
            icon = AppDestination.ProfileSwitcher.icon,
        ),
    ).map { entry -> if (entry.icon == null) entry.copy(icon = entry.destination.icon) else entry }

    /** Account entries, including Attach account when the session is anonymous. */
    fun accountEntries(anonymous: Boolean): List<MenuEntry> =
        if (anonymous) account + attachAccount else account

    /** The entry that opens the quick search. */
    val search: List<MenuEntry> = listOf(
        MenuEntry(
            id = TestTags.MENU_QUICK_SEARCH,
            label = "Quick search",
            destination = AppDestination.Search,
        ),
    )

    /** The entry that attaches the current anonymous session to an account. Only shown for anonymous sessions. */
    val attachAccount: MenuEntry = MenuEntry(
        id = TestTags.MENU_ATTACH_ACCOUNT,
        label = AppDestination.AttachAccount.title,
        destination = AppDestination.AttachAccount,
        icon = AppDestination.AttachAccount.icon,
    ).let { if (it.icon == null) it.copy(icon = it.destination.icon) else it }

    /**
     * The destinations, in the order the drawer shows them.
     *
     * This is [DestinationKind.menuEntries] — the declared set of the menu's
     * destinations — with a stable id for each.
     */
    val destinations: List<MenuEntry> = DestinationKind.menuEntries.map { destination ->
        val entry = MenuEntry(
            id = menuItemId(destination),
            label = destination.title,
            destination = destination,
            icon = destination.icon,
        )
        if (entry.icon == null) entry.copy(icon = entry.destination.icon) else entry
    }

    /**
     * The destinations the sheet shows under "Destinations".
     *
     * The account and the search entries are already shown in their own
     * sections, so listing them again would put the same destination on the
     * screen twice.
     */
    val sheetDestinations: List<MenuEntry> = destinations.filterNot { entry ->
        entry.destination in account.map { it.destination } || entry.destination in search.map { it.destination }
    }

    /**
     * Every destination the menu offers, on either platform.
     *
     * The set, not the list: the two surfaces group the entries differently,
     * and the claim is that they offer the same ones.
     */
    val allDestinations: Set<AppDestination> =
        (account + search + sheetDestinations).map { it.destination }.toSet()

    /**
     * The stable id of the menu entry of [destination].
     *
     * The ids are constants rather than derived from the label, because a
     * label is copy the app is free to change and a test that addresses an
     * entry by its label breaks the day somebody rewords it.
     *
     * The last branch is a failure and not a derivation: a destination that
     * joins the menu without a tag of its own has no id to address it by, and
     * an id made of its label would move the day somebody rewords that label.
     * The menu is drawn at startup, so the missing tag is caught the moment
     * the app opens rather than by a test that quietly stops finding the
     * entry.
     */
    private fun menuItemId(destination: AppDestination): String = when (destination) {
        AppDestination.Statistics -> TestTags.MENU_STATISTICS

        AppDestination.Notes -> TestTags.MENU_NOTES

        AppDestination.AiChat -> TestTags.MENU_AI_CHAT

        AppDestination.Search -> TestTags.MENU_SEARCH

        AppDestination.Archive -> TestTags.MENU_ARCHIVE

        AppDestination.Proposals -> TestTags.MENU_PROPOSALS

        AppDestination.Plans -> TestTags.MENU_PLANS

        AppDestination.Pomodoro -> TestTags.MENU_POMODORO

        AppDestination.AiUsage -> TestTags.MENU_AI_USAGE

        AppDestination.ProfileSwitcher -> TestTags.MENU_PROFILES

        AppDestination.Settings -> TestTags.MENU_SETTINGS

        else -> error(
            "The destination '$destination' is in the menu but has no tag of its own. " +
                "Add a MENU_* tag to TestTags and name it here.",
        )
    }
}
