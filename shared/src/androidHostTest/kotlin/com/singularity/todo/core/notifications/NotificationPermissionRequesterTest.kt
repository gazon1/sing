package com.singularity.todo.core.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The permission state the reminder sheet reads is re-derived from the real platform
 * state when the app comes back to the foreground.
 *
 * ## What the lifecycle is, here
 *
 * The observer is driven through a real [LifecycleRegistry] rather than by calling
 * `onStateChanged` by hand: registering it, walking it through
 * `RESUMED → CREATED → RESUMED`, and removing it are the framework's transitions, so
 * what is asserted is that the observer is wired correctly — not that a lambda the
 * test supplied gets called. The alternative, a `LifecycleOwner` whose `lifecycle`
 * getter throws, proves only that `notificationPermissionResumeObserver` filters on
 * `ON_RESUME`; it says nothing about registration, about the framework's replay of
 * `ON_RESUME` to an observer added while the screen is already resumed, or about the
 * `removeObserver` in `DisposableEffect`'s `onDispose`.
 *
 * What is still not covered is the composable wiring itself — that `DisposableEffect`
 * in `rememberNotificationPermissionRequester` adds and removes *this* observer. That
 * needs a Compose UI test rule, and `androidHostTest` deliberately declares none
 * (`shared/build.gradle.kts` explains why: no consumer, and `check-dependency-usage`
 * flags a dependency nothing uses). The test below covers the observer half, which is
 * where the filtering and the read live.
 *
 * ## Why `ON_RESUME` is the whole of the production contract
 *
 * Measured on Android 15 (see
 * `docs/decisions/2026-11-16-on-resume-is-enough-for-the-notification-permission.md`):
 * revoking `POST_NOTIFICATIONS` kills the app process, and granting it is either
 * followed by `ON_RESUME` (system settings) or reported by the launch
 * `RequestPermission` callback. The notification shade is a window, not an Activity
 * transition, and delivers no lifecycle event — but nothing reachable from it changes
 * the grant without one of the two paths above.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationPermissionRequesterTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    /**
     * A [LifecycleOwner] whose lifecycle is the framework's, not the test's.
     *
     * `createUnsafe` because the registry is moved by the test thread rather than by an
     * Activity's main-thread transitions; `LifecycleRegistry` otherwise refuses every
     * state change made off the main thread.
     */
    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry
    }

    /** An owner already at `RESUMED`, as the screen is when the user leaves for Settings. */
    private fun resumedOwner(): TestOwner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }

    /** The trip away: `ON_PAUSE` then `ON_STOP`, neither of which may re-read. */
    private fun TestOwner.background() {
        registry.currentState = Lifecycle.State.CREATED
    }

    /** The trip back: `ON_START` then `ON_RESUME`. */
    private fun TestOwner.foreground() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `coming back from settings reads a permission granted there`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val owner = resumedOwner()
        var hasPermissions = readNotificationPermission(context)
        owner.lifecycle.addObserver(
            notificationPermissionResumeObserver { hasPermissions = readNotificationPermission(context) },
        )
        assertFalse(hasPermissions)

        owner.background()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        owner.foreground()

        assertTrue(hasPermissions)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `coming back from settings reads a permission revoked there`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val owner = resumedOwner()
        var hasPermissions = readNotificationPermission(context)
        owner.lifecycle.addObserver(
            notificationPermissionResumeObserver { hasPermissions = readNotificationPermission(context) },
        )
        assertTrue(hasPermissions)

        owner.background()
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        owner.foreground()

        assertFalse(hasPermissions)
    }

    /**
     * The observer is added while the screen is already resumed — a sheet opened on a
     * visible screen, not a screen that resumed afterwards. The registry replays
     * `ON_RESUME` to it, so the state is read at registration too.
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `registering on a resumed screen reads the permission`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var reads = 0
        val owner = resumedOwner()
        owner.lifecycle.addObserver(notificationPermissionResumeObserver { reads++ })

        assertEquals(1, reads)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `returning without a change keeps the same state`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val owner = resumedOwner()
        var hasPermissions = readNotificationPermission(context)
        var refreshes = 0
        var changes = 0
        owner.lifecycle.addObserver(
            notificationPermissionResumeObserver {
                refreshes++
                val current = readNotificationPermission(context)
                if (current != hasPermissions) {
                    hasPermissions = current
                    changes++
                }
            },
        )
        // Registration replays ON_RESUME to an observer added on a resumed screen; count
        // the reads the two trips away and back produce, not that one.
        refreshes = 0

        owner.background()
        owner.foreground()
        owner.background()
        owner.foreground()

        assertTrue(hasPermissions)
        assertEquals(2, refreshes)
        assertEquals(0, changes)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun `coming back reads the notification toggle on older Android`() {
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationsEnabled(false)
        val owner = resumedOwner()
        var hasPermissions = readNotificationPermission(context)
        owner.lifecycle.addObserver(
            notificationPermissionResumeObserver { hasPermissions = readNotificationPermission(context) },
        )
        assertFalse(hasPermissions)

        owner.background()
        shadowOf(nm).setNotificationsEnabled(true)
        owner.foreground()
        assertTrue(hasPermissions)

        owner.background()
        shadowOf(nm).setNotificationsEnabled(false)
        owner.foreground()
        assertFalse(hasPermissions)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `repeated resumes keep the state correct`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val owner = resumedOwner()
        var hasPermissions = readNotificationPermission(context)
        owner.lifecycle.addObserver(
            notificationPermissionResumeObserver { hasPermissions = readNotificationPermission(context) },
        )

        owner.background()
        owner.foreground()
        assertFalse(hasPermissions)

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        owner.background()
        owner.foreground()
        assertTrue(hasPermissions)

        owner.background()
        owner.foreground()
        assertTrue(hasPermissions)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `events other than resume do not re-read the permission`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var reads = 0
        val owner = TestOwner()
        owner.lifecycle.addObserver(notificationPermissionResumeObserver { reads++ })

        // INITIALIZED → STARTED → CREATED walks ON_CREATE, ON_START, ON_PAUSE, ON_STOP
        // without ever reaching ON_RESUME.
        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.CREATED

        assertEquals(0, reads)
    }

    /**
     * The cleanup half of `DisposableEffect`: once removed, the observer hears nothing,
     * so a screen that has gone away cannot be handed a state write.
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `a removed observer stops being notified`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var reads = 0
        val owner = resumedOwner()
        val observer = notificationPermissionResumeObserver { reads++ }
        owner.lifecycle.addObserver(observer)
        assertEquals(1, reads)

        owner.lifecycle.removeObserver(observer)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        owner.background()
        owner.foreground()

        assertEquals(1, reads)
    }
}
