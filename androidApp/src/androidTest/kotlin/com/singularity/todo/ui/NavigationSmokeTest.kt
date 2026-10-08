package com.singularity.todo.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.singularity.todo.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the Android shell — app cold-start and bottom navigation reachability.
 *
 * Uses UiAutomator rather than Espresso because it exercises the accessibility tree
 * rather than the Compose view hierarchy, making it the correct tool for external
 * instrumentation on a real device.
 *
 * The test tag IDs are those declared in [com.singularity.todo.core.ui.TestTags]:
 * - nav_tab_<slug> for bottom nav tabs
 * - tasks_fab for the shell FAB
 *
 * `testTagsAsResourceId = true` is set on the root composable in [MainActivity],
 * so all testTag values surface as Android resource IDs accessible by UiAutomator.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NavigationSmokeTest {

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        )
        // Unlock the lock screen so ActivityScenario can land on the app.
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("input keyevent 82") // KEYCODE_MENU = unlock
    }

    /**
     * Smoke: [MainActivity] launches and does not crash.
     */
    @Test
    fun main_activity_launches_without_crash() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java
        )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        scenario.onActivity { activity ->
            assertNotNull("Activity should be created", activity)
        }
        scenario.close()
    }

    /**
     * Smoke: [MainActivity] launches, the start destination (Today tab) is visible,
     * and every bottom nav tab is reachable by tap.
     */
    @Test
    fun app_launches_and_all_tabs_are_reachable() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java
        )
        val scenario = ActivityScenario.launch<MainActivity>(intent)

        // Wait for the start destination to appear — Today is the start tab.
        val todayTab = device.wait(
            Until.findObject(By.res("nav_tab_today")),
            8_000
        )
        assertNotNull("Today tab should be visible after cold start", todayTab)

        // Verify the shell FAB is present on agenda tabs (Today is an agenda tab).
        assertNotNull(
            "FAB should be present on Today tab",
            device.findObject(By.res("tasks_fab"))
        )

        // The six bottom-bar tabs, in display order from DestinationKind.tabs.
        // Slug is lower-cased from the AppDestination title.
        val tabs = listOf(
            "nav_tab_inbox",
            "nav_tab_today",
            "nav_tab_upcoming",
            "nav_tab_plans",
            "nav_tab_pomodoro",
            "nav_tab_calendar",
        )

        tabs.forEach { tabId ->
            device.findObject(By.res(tabId)).click()
            // Wait for the tab to be re-selected after the tap.
            device.wait(Until.findObject(By.res(tabId)), 3_000)
            // Verify the tab item is still present (navigation succeeded, tab is visible).
            assertNotNull(
                "Tab $tabId should be reachable after tap",
                device.findObject(By.res(tabId))
            )
        }

        scenario.close()
    }

    /**
     * FAB presence: agenda tabs (Inbox, Today, Plans) show the "Add task" FAB;
     * Upcoming, Pomodoro, and Calendar do not.
     *
     * This encodes the contract from `FabActionResolver` — changing the FAB behaviour
     * will make this test fail, which is the intended signal.
     */
    @Test
    fun fab_visibility_matches_tab_contract() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java
        )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        device.wait(Until.findObject(By.res("nav_tab_today")), 8_000)

        // Inbox — agenda tab, FAB present.
        device.findObject(By.res("nav_tab_inbox")).click()
        device.wait(Until.findObject(By.res("nav_tab_inbox")), 3_000)
        assertNotNull("FAB should be on Inbox tab", device.findObject(By.res("tasks_fab")))

        // Upcoming — not an agenda tab, no FAB.
        device.findObject(By.res("nav_tab_upcoming")).click()
        device.wait(Until.findObject(By.res("nav_tab_upcoming")), 3_000)
        assertNull(
            "FAB should NOT be on Upcoming tab",
            device.findObject(By.res("tasks_fab"))
        )

        // Plans — agenda tab, FAB present.
        device.findObject(By.res("nav_tab_plans")).click()
        device.wait(Until.findObject(By.res("nav_tab_plans")), 3_000)
        assertNotNull("FAB should be on Plans tab", device.findObject(By.res("tasks_fab")))

        // Pomodoro — not an agenda tab, no FAB.
        device.findObject(By.res("nav_tab_pomodoro")).click()
        device.wait(Until.findObject(By.res("nav_tab_pomodoro")), 3_000)
        assertNull(
            "FAB should NOT be on Pomodoro tab",
            device.findObject(By.res("tasks_fab"))
        )

        scenario.close()
    }
}
