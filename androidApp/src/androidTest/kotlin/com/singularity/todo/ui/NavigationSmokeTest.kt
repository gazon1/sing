package com.singularity.todo.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.singularity.todo.MainActivity
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.testing.InstrumentedTestBase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** How many times the menu sheet is scrolled before an entry is given up on. */
private const val MENU_SCROLL_ATTEMPTS = 6

/** How much of the sheet one scroll gesture covers. */
private const val MENU_SCROLL_PERCENT = 0.5f

/**
 * Smoke tests for the Android shell — app cold-start and bottom navigation reachability.
 *
 * Uses UiAutomator rather than Espresso because it exercises the accessibility tree
 * rather than the Compose view hierarchy, making it the correct tool for external
 * instrumentation on a real device.
 *
 * The test tag IDs are those declared in [com.singularity.todo.core.ui.TestTags]:
 * - nav_tab_<slug> for bottom nav tabs
 * - menu_<id> for the entries of the menu sheet
 * - tasks_fab for the shell FAB
 *
 * `testTagsAsResourceId = true` is set on the root composable in [MainActivity],
 * so all testTag values surface as Android resource IDs accessible by UiAutomator.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NavigationSmokeTest : InstrumentedTestBase() {
    @Before
    fun setup() {
        // Unlock the lock screen so ActivityScenario can land on the app.
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("input keyevent 82") // KEYCODE_MENU = unlock
    }

    /**
     * Smoke: [MainActivity] launches and does not crash.
     */
    @Test
    fun main_activity_launches_without_crash() {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        scenario.onActivity { activity ->
            // ActivityScenario.launch throws if the activity fails to create; reaching
            // here is the smoke signal. Asserting the non-null lambda parameter would
            // be tautological.
            assertTrue(activity.window != null)
        }
        scenario.close()
    }

    /**
     * Smoke: [MainActivity] launches, the start destination (Today tab) is visible,
     * and every bottom nav tab is reachable by tap.
     */
    @Test
    fun app_launches_and_all_tabs_are_reachable() {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)

        // Wait for the start destination to appear — Today is the start tab.
        val todayTab = device.awaitShell()
        assertNotNull("Today tab should be visible after cold start", todayTab)

        // Verify the shell FAB is present on agenda tabs (Today is an agenda tab).
        assertNotNull(
            "FAB should be present on Today tab",
            device.findObject(By.res("tasks_fab")),
        )

        // The bottom-bar tabs, in display order from DestinationKind.tabs.
        // Slug is lower-cased from the AppDestination title.
        val tabs = DestinationKind.tabs.map { TestTags.navTab(it.title) }

        tabs.forEach { tabId ->
            device.tapTag(tabId)
            // Wait for the tab to be re-selected after the tap.
            device.awaitTagOrNull(tabId, 3_000)
            // Verify the tab item is still present (navigation succeeded, tab is visible).
            assertNotNull(
                "Tab $tabId should be reachable after tap",
                device.findObject(By.res(tabId)),
            )
        }

        scenario.close()
    }

    /**
     * FAB presence: the agenda destinations show the "Add task" FAB; Upcoming,
     * Pomodoro and Calendar do not.
     *
     * Plans and Pomodoro are **menu** destinations, not tabs: they left the tab
     * bar and live behind the "Menu" button (see `DestinationKind`), so they are
     * reached through the menu sheet. Waiting for a bottom-bar tag the screen
     * does not contain can only end in a timeout, and it says nothing about the
     * FAB — the failure would name a wait, not a behaviour.
     *
     * This encodes the contract from `FabActionResolver` — changing the FAB behaviour
     * will make this test fail, which is the intended signal.
     */
    @Test
    fun fab_visibility_matches_tab_contract() {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        device.awaitShell()

        // Inbox — agenda tab, FAB present.
        device.tapTag("nav_tab_inbox")
        device.awaitTagOrNull("nav_tab_inbox", 3_000)
        assertNotNull("FAB should be on Inbox tab", device.findObject(By.res("tasks_fab")))

        // Upcoming — not an agenda tab, no FAB.
        device.tapTag("nav_tab_upcoming")
        device.awaitTagOrNull("nav_tab_upcoming", 3_000)
        assertNull(
            "FAB should NOT be on Upcoming tab",
            device.findObject(By.res("tasks_fab")),
        )

        // Plans — a menu destination, and it does show the "Add task" FAB.
        openMenuDestination(TestTags.MENU_PLANS)
        device.awaitTag(TestTags.PLANS_SCREEN, "the Plans screen", TAB_TIMEOUT_MS)
        assertNotNull("FAB should be on the Plans screen", device.findObject(By.res("tasks_fab")))

        // Pomodoro — a menu destination too, and it has no FAB.
        openMenuDestination(TestTags.MENU_POMODORO)
        // The Pomodoro screen has to be up before the FAB is judged: the FAB of
        // the screen underneath is still in the tree while the menu sheet is
        // open, so an assertion made too early would be about the screen the
        // navigation came from.
        device.awaitTag(
            TestTags.Pomodoro.TIMER_LABEL,
            "the Pomodoro screen's timer",
            TAB_TIMEOUT_MS,
        )
        device.wait(Until.gone(By.res("tasks_fab")), TAB_TIMEOUT_MS)
        assertNull(
            "FAB should NOT be on the Pomodoro screen",
            device.findObject(By.res("tasks_fab")),
        )

        scenario.close()
    }

    /**
     * Opens a destination that lives in the menu sheet rather than in the tab bar.
     *
     * The sheet is a scrollable column: an entry can be in the tree and still be
     * below the fold, so the sheet is scrolled until the entry is on screen and
     * the entry is then tapped.
     */
    private fun openMenuDestination(tag: String) {
        device.tapTag(TestTags.NAV_MENU_BUTTON)
        val sheet = device.awaitTag(TestTags.MENU_SHEET, "the app menu sheet", TAB_TIMEOUT_MS)
        menuEntry(sheet, tag).tap()
    }

    /**
     * The menu entry with [tag], scrolled into view.
     *
     * `scroll(Direction.DOWN)` is a swipe towards the top of the screen, and that
     * is what moves the content of the sheet up and brings the later entries into
     * it — the same direction a thumb moves to read further down a list.
     */
    private fun menuEntry(
        sheet: UiObject2,
        tag: String,
    ): UiObject2 {
        var attempts = 0
        while (true) {
            val entry = device.findObject(By.res(tag))
            if (entry != null && entry.visibleBounds.isNotEmpty) return entry
            if (attempts >= MENU_SCROLL_ATTEMPTS) break
            sheet.scroll(Direction.DOWN, MENU_SCROLL_PERCENT)
            attempts++
        }
        throw AssertionError(
            "the menu entry $tag is not on the menu sheet: " + device.diagnostics(),
        )
    }
}
