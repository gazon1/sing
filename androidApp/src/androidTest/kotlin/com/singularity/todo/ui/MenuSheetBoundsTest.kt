package com.singularity.todo.ui

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.singularity.todo.MainActivity
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.shell.AppMenu
import com.singularity.todo.testing.InstrumentedTestBase
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Menu sheet bounds on compact and expanded Android windows.
 *
 * Issue #2754: on expanded width the app menu sheet was as wide as the default
 * 640dp cap and obscured most of the content beside the navigation rail. The
 * menu must stay a bounded panel on wide windows while keeping its phone
 * presentation and full reachability.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MenuSheetBoundsTest : InstrumentedTestBase() {
    @Test
    fun menu_sheet_uses_available_width_on_compact_phone() {
        withWidthDp(COMPACT_WIDTH_DP, expectedBucket = WidthBucket.COMPACT) {
            val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            val scenario = ActivityScenario.launch<MainActivity>(intent)
            try {
                device.awaitShell()
                val sheet = openMenuSheet()

                val bounds = sheet.visibleBounds
                val widthDp = pxToDp(bounds.width())
                val screenDp = screenWidthDp()
                assertTrue(
                    "compact menu sheet should use available width (screen ${screenDp}dp), got ${widthDp}dp: ${device.diagnostics()}",
                    widthDp >= screenDp - COMPACT_EDGE_TOLERANCE_DP,
                )
                assertTrue(
                    "compact menu sheet should not be horizontally displaced, left=${bounds.left}px: ${device.diagnostics()}",
                    bounds.left <= dpToPx(COMPACT_EDGE_TOLERANCE_DP),
                )

                assertMenuContentReachable(sheet)
            } finally {
                scenario.close()
            }
        }
    }

    @Test
    fun menu_sheet_is_constrained_and_clear_of_rail_on_720dp_expanded_width() {
        withWidthDp(EXPANDED_WIDTH_DP, expectedBucket = WidthBucket.EXPANDED) {
            val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            val scenario = ActivityScenario.launch<MainActivity>(intent)
            try {
                device.awaitShell()
                val railItem =
                    device.awaitTag(
                        TestTags.navTab("Today"),
                        "a navigation rail item on expanded width",
                        TAB_TIMEOUT_MS,
                    )

                val sheet = openMenuSheet()
                val bounds = sheet.visibleBounds
                val widthDp = pxToDp(bounds.width())

                assertTrue(
                    "expanded menu sheet should be capped near 480dp on ${EXPANDED_WIDTH_DP}dp window, got ${widthDp}dp: ${device.diagnostics()}",
                    widthDp in EXPANDED_MIN_WIDTH_DP..EXPANDED_MAX_WIDTH_DP,
                )
                assertTrue(
                    "expanded menu sheet must not obscure the navigation rail " +
                        "(sheetLeft=${bounds.left}, railRight=${railItem.visibleBounds.right}): ${device.diagnostics()}",
                    bounds.left >= railItem.visibleBounds.right - dpToPx(RAIL_CLEARANCE_TOLERANCE_DP),
                )

                assertMenuContentReachable(sheet)
            } finally {
                scenario.close()
            }
        }
    }

    private fun openMenuSheet(): UiObject2 {
        device.tapTag(TestTags.NAV_MENU_BUTTON)
        return device.awaitTag(TestTags.MENU_SHEET, "the app menu sheet", TAB_TIMEOUT_MS)
    }

    private fun assertMenuContentReachable(sheet: UiObject2) {
        val required = requiredMenuTags()
        scrollToTop(sheet)
        val found = linkedSetOf<String>()
        collectVisibleMenuTags(required, found)
        repeat(MENU_SCROLL_PASSES) {
            if (required.all { it in found }) return
            sheet.scroll(Direction.DOWN, MENU_SCROLL_PERCENT)
            collectVisibleMenuTags(required, found)
        }
        scrollToTop(sheet)
        collectVisibleMenuTags(required, found)
        repeat(MENU_SCROLL_PASSES) {
            if (required.all { it in found }) return
            sheet.scroll(Direction.DOWN, MENU_SCROLL_PERCENT)
            collectVisibleMenuTags(required, found)
        }
        val missing = required.filterNot { it in found }
        assertTrue(
            "menu entries/actions not reachable in menu sheet: $missing; found=$found; ${device.diagnostics()}",
            missing.isEmpty(),
        )
    }

    private fun requiredMenuTags(): Set<String> =
        buildSet {
            addAll(AppMenu.account.map { it.id })
            addAll(AppMenu.search.map { it.id })
            addAll(AppMenu.sheetDestinations.map { it.id })
            if (device.findObject(By.res(TestTags.MENU_ATTACH_ACCOUNT)) != null) {
                add(TestTags.MENU_ATTACH_ACCOUNT)
            }
            add(TestTags.MENU_PROJECTS_HEADER)
            add(TestTags.MENU_ALL_PROJECTS)
            val expand = device.findObject(By.res(TestTags.MENU_PROJECTS_EXPAND)) != null
            val collapse = device.findObject(By.res(TestTags.MENU_PROJECTS_COLLAPSE)) != null
            when {
                expand -> add(TestTags.MENU_PROJECTS_EXPAND)
                collapse -> add(TestTags.MENU_PROJECTS_COLLAPSE)
                else -> add(TestTags.MENU_PROJECTS_EXPAND)
            }
        }

    private fun collectVisibleMenuTags(
        required: Set<String>,
        found: MutableSet<String>,
    ) {
        required.forEach { tag ->
            if (tag !in found && isTagVisible(tag)) found += tag
        }
        if (TestTags.MENU_ATTACH_ACCOUNT !in required &&
            TestTags.MENU_ATTACH_ACCOUNT !in found &&
            isTagVisible(TestTags.MENU_ATTACH_ACCOUNT)
        ) {
            // attach-account is session-dependent; if rendered it must be reachable
            found += TestTags.MENU_ATTACH_ACCOUNT
        }
    }

    private fun isTagVisible(tag: String): Boolean = device.findObject(By.res(tag))?.visibleBounds?.isEmpty == false

    private fun scrollToTop(sheet: UiObject2) {
        repeat(MENU_SCROLL_PASSES) { sheet.scroll(Direction.UP, MENU_SCROLL_PERCENT) }
    }

    private fun withWidthDp(
        targetWidthDp: Int,
        expectedBucket: WidthBucket,
        block: () -> Unit,
    ) {
        val before = DensityState.read(device)
        assumeTrue(
            "cannot safely restore unparsed density override; refusing to change density: ${before.raw}",
            !before.hasOverride || before.overrideDpi != null,
        )
        val restore = restoreAction(before)
        try {
            val widthPx = device.displayWidth
            val targetDensity = widthPx * 160 / targetWidthDp
            assumeTrue(
                "device cannot simulate ${targetWidthDp}dp width (needs density $targetDensity dpi, " +
                    "supported ${MIN_DENSITY_DPI}..${MAX_DENSITY_DPI}, widthPx=$widthPx, $before)",
                targetDensity in MIN_DENSITY_DPI..MAX_DENSITY_DPI,
            )
            device.executeShellCommand("wm density $targetDensity")
            waitForConfigSettle()

            val actualWidthDp = screenWidthDp()
            assumeTrue(
                "density change did not produce requested $expectedBucket width: requested=${targetWidthDp}dp " +
                    "actual=${actualWidthDp}dp density=$targetDensity widthPx=${device.displayWidth} before=[$before]",
                expectedBucket.matches(actualWidthDp, targetWidthDp),
            )
            block()
        } finally {
            try {
                restore()
            } finally {
                waitForConfigSettle()
            }
        }
    }

    private fun restoreAction(before: DensityState): () -> Unit =
        when {
            !before.hasOverride -> ({ device.executeShellCommand("wm density reset") })
            before.overrideDpi != null -> ({ device.executeShellCommand("wm density ${before.overrideDpi}") })
            else -> error("unparsed density override must be rejected before mutation: ${before.raw}")
        }

    private fun waitForConfigSettle() {
        device.waitForIdle()
        SystemClock.sleep(CONFIG_SETTLE_MS)
    }

    private fun effectiveDensityDpi(): Int =
        DensityState.read(device).effectiveDpi()
            ?: InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.displayMetrics.densityDpi

    private fun screenWidthDp(): Int = device.displayWidth * 160 / effectiveDensityDpi().coerceAtLeast(1)

    private fun pxToDp(px: Int): Int = px * 160 / effectiveDensityDpi().coerceAtLeast(1)

    private fun dpToPx(dp: Int): Int = dp * effectiveDensityDpi().coerceAtLeast(1) / 160

    private enum class WidthBucket {
        COMPACT,
        EXPANDED,
        ;

        fun matches(
            actualWidthDp: Int,
            targetWidthDp: Int,
        ): Boolean =
            when (this) {
                COMPACT -> {
                    actualWidthDp < COMPACT_MAX_WIDTH_DP &&
                        abs(actualWidthDp - targetWidthDp) <= WIDTH_TOLERANCE_DP
                }

                EXPANDED -> {
                    actualWidthDp >= COMPACT_MAX_WIDTH_DP &&
                        abs(actualWidthDp - targetWidthDp) <= WIDTH_TOLERANCE_DP
                }
            }
    }

    private data class DensityState(
        val raw: String,
        val hasOverride: Boolean,
        val overrideDpi: Int?,
        val physicalDpi: Int?,
    ) {
        fun effectiveDpi(): Int? = overrideDpi ?: physicalDpi

        override fun toString(): String = raw.trim().replace('\n', ' ')

        companion object {
            fun read(device: UiDevice): DensityState {
                val raw = device.executeShellCommand("wm density")
                val override =
                    OVERRIDE_DENSITY
                        .find(raw)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                val physical =
                    PHYSICAL_DENSITY
                        .find(raw)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                val hasOverride = raw.contains("Override density", ignoreCase = true)
                return DensityState(
                    raw = raw,
                    hasOverride = hasOverride,
                    overrideDpi = override,
                    physicalDpi = physical,
                )
            }
        }
    }

    companion object {
        private const val COMPACT_WIDTH_DP = 360
        private const val COMPACT_MAX_WIDTH_DP = 600
        private const val EXPANDED_WIDTH_DP = 720
        private const val EXPANDED_MIN_WIDTH_DP = 440
        private const val EXPANDED_MAX_WIDTH_DP = 520
        private const val COMPACT_EDGE_TOLERANCE_DP = 32
        private const val RAIL_CLEARANCE_TOLERANCE_DP = 8
        private const val WIDTH_TOLERANCE_DP = 40
        private const val MENU_SCROLL_PASSES = 10
        private const val MENU_SCROLL_PERCENT = 0.6f
        private const val CONFIG_SETTLE_MS = 1500L
        private const val MIN_DENSITY_DPI = 120
        private const val MAX_DENSITY_DPI = 640

        private val OVERRIDE_DENSITY = Regex("Override density:\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val PHYSICAL_DENSITY = Regex("Physical density:\\s*(\\d+)", RegexOption.IGNORE_CASE)
    }
}
