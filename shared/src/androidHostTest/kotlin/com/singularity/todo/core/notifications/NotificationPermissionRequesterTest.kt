package com.singularity.todo.core.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
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
 * The composable wires [notificationPermissionResumeObserver] to [readNotificationPermission]:
 * the observer refreshes on `ON_RESUME`, and the read reflects the current grant. Driving the
 * observer with `ON_RESUME` therefore asserts the state is re-read from the live permission for
 * resume-driven returns (e.g. system settings or the runtime permission dialog) without polling.
 *
 * This class is a JUnit4/Robolectric test in `androidHostTest` (no Compose UI test rule in this
 * source set), so it exercises the observer and the live permission read directly.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationPermissionRequesterTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val owner =
        object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = error("unused")
        }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `ON_RESUME re-reads permission when granted in settings`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var hasPermissions = readNotificationPermission(context)
        val observer = notificationPermissionResumeObserver { hasPermissions = readNotificationPermission(context) }

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)

        assertTrue(hasPermissions)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `ON_RESUME re-reads permission when revoked in settings`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var hasPermissions = readNotificationPermission(context)
        val observer = notificationPermissionResumeObserver { hasPermissions = readNotificationPermission(context) }

        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)

        assertFalse(hasPermissions)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `returning without a change keeps the same state`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var hasPermissions = readNotificationPermission(context)
        var refreshes = 0
        var changes = 0
        val observer =
            notificationPermissionResumeObserver {
                refreshes++
                val current = readNotificationPermission(context)
                if (current != hasPermissions) {
                    hasPermissions = current
                    changes++
                }
            }

        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)

        assertTrue(hasPermissions)
        assertEquals(2, refreshes)
        assertEquals(0, changes)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun `ON_RESUME re-reads notification toggle on older Android`() {
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationsEnabled(false)
        var hasPermissions = readNotificationPermission(context)
        val observer = notificationPermissionResumeObserver { hasPermissions = readNotificationPermission(context) }
        assertFalse(hasPermissions)

        shadowOf(nm).setNotificationsEnabled(true)
        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
        assertTrue(hasPermissions)

        shadowOf(nm).setNotificationsEnabled(false)
        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
        assertFalse(hasPermissions)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `repeated resumes keep the state correct`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var hasPermissions = readNotificationPermission(context)
        val observer = notificationPermissionResumeObserver { hasPermissions = readNotificationPermission(context) }

        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
        assertFalse(hasPermissions)

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
        assertTrue(hasPermissions)

        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
        assertTrue(hasPermissions)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `non-resume lifecycle events do not re-read permission`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var reads = 0
        val observer = notificationPermissionResumeObserver { reads++ }

        listOf(
            Lifecycle.Event.ON_CREATE,
            Lifecycle.Event.ON_START,
            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP,
            Lifecycle.Event.ON_DESTROY,
        ).forEach { observer.onStateChanged(owner, it) }

        assertEquals(0, reads)
    }
}
