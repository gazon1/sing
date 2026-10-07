package com.singularity.todo.core.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
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
 * `AndroidNotifier.isSupported` measures the permission instead of asserting the presence of
 * a system service.
 *
 * ## What this is the only local test for
 *
 * This class is in `androidMain`, and `androidMain` had no way to be tested: the
 * `androidHostTest` source set existed but was empty and declared no Android stack, so a test
 * written there would have compiled nowhere and run nowhere. `isSupported` was
 * `override val isSupported: Boolean = true` with the comment "the only question is the user's
 * grant" — and it answered `true` without asking. On API 33+ with `POST_NOTIFICATIONS` denied,
 * `post` returned immediately while the flag promised a notification, so `ReminderDelivery`
 * reported `Posted` and the user saw nothing.
 *
 * The fix was a getter over `checkSelfPermission`. Nothing about that getter is checkable
 * without a `Context` that answers permission questions differently per state, which is
 * exactly what Robolectric provides. So this test is not coverage for its own sake — it is the
 * reason the fix is known to hold.
 *
 * ## Why a JUnit4 class, and why that is a hazard
 *
 * Robolectric is a JUnit4 runner; the class is discovered through the Vintage engine. JUnit4
 * has no `@Tag`, and a `fast`/`slow` selection is `includeTags(...)`, which drops untagged
 * tests regardless of engine. This class would therefore run locally and be **silently skipped
 * in CI**, which is worse than having no test: it would report a green nobody had earned.
 *
 * Two things make that impossible here. `testAndroidHostTest` is excluded from tag filtering
 * in `shared/build.gradle.kts`, with the reason written there. And `shared:testAndroidHostTest`
 * has a floor in `config/docs/test-runs-baseline.txt`, so if this class stops running, the
 * floor drops and `check-test-runs.py` fails. If you are reading this because the floor
 * complained, this is the class it was counting.
 *
 * There is deliberately no `@Tag` here, and adding one is not possible. See ADR
 * 2026-10-04-test-execution-integrity.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidNotifierTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun notifier() = AndroidNotifier(context)

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `denied notifications permission is reported as unsupported`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(
            "With POST_NOTIFICATIONS denied the flag must say so. Reporting true is the defect " +
                "this class exists to prevent: post() returns early and the caller is told the " +
                "notification was delivered.",
            notifier().isSupported,
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `granted notifications permission is reported as supported`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(notifier().isSupported)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun `below API 33 the permission does not exist and the flag is true`() {
        // TIRAMISU is where POST_NOTIFICATIONS was introduced. Below it there is nothing to
        // check, so the flag has to be true unconditionally — otherwise reminders would
        // stop firing on every device below 33 and nothing would explain why.
        assertTrue(notifier().isSupported)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `the grant is read per call, not captured when the notifier was built`() {
        // A `val` initialised at construction answers with the permission the app *started*
        // with. The user can revoke it from settings while the app runs, and Android can
        // deliver the app back after a permission change — which is exactly the moment a
        // cached value is wrong and the user is trying to fix something.
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = notifier()
        assertTrue("precondition: granted at construction", notifier.isSupported)

        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(
            "The flag was captured at construction and still reports the permission the app " +
                "started with, after the user revoked it.",
            notifier.isSupported,
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `post with permission denied delivers nothing`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = notifier()

        notifier.post(tag = "reminder:u1:r1", title = "Task", body = "Due now", viewId = null)

        // The shadow lives on the framework NotificationManager, not on
        // NotificationManagerCompat — that class is a wrapper with no shadow of its own, and
        // asking for one fails to compile rather than failing the test.
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(
            "A notification was enqueued while POST_NOTIFICATIONS was denied — the user would " +
                "never see it, and every caller upstream was told otherwise.",
            0,
            shadowOf(manager).size(),
        )
    }
}
