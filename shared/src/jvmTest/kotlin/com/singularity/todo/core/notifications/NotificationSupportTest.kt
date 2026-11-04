package com.singularity.todo.core.notifications

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The notifier's permission matrix — every cell of it, asserted once.
 *
 * ## What this owns
 *
 * Which cells hold is a fact about every device: below API 33 the permission does not
 * exist, and from API 33 the grant is the answer. The measurement — what Android's
 * `Context` says right now — belongs to the platform, but the rule that turns that
 * measurement into an answer does not, so it lives in [NotificationSupport] and is
 * asserted here.
 *
 * The matrix used to be asserted in the Android host tier, in a Robolectric class,
 * where the only thing that could be checked was a `Context` that answers permission
 * questions differently per state. The cells themselves were never Android's: a
 * device on API 32 answers the same as a fake of one.
 *
 * ## What stayed in the Android tier
 *
 * `AndroidNotifierTest` keeps the two assertions that are about the port rather than
 * the rule: that the grant is read per call, and that a denied permission enqueues
 * nothing. Those are about the Android `Context` and the framework's notification
 * manager, and no JVM test can reach them.
 */
@Tag("fast")
class NotificationSupportTest {
    @Test
    fun `a denied permission means no notification from API 33 on`() {
        assertFalse(
            NotificationSupport.isSupported(
                sdkInt = NotificationSupport.PERMISSION_REQUIRED_SDK,
                permissionGranted = false,
            ),
            "With POST_NOTIFICATIONS denied the app must say it cannot post. Claiming " +
                "otherwise is the defect this rule exists to prevent: the caller is " +
                "told the reminder was delivered while the user sees nothing.",
        )
    }

    @Test
    fun `a granted permission means a notification`() {
        assertTrue(
            NotificationSupport.isSupported(
                sdkInt = NotificationSupport.PERMISSION_REQUIRED_SDK,
                permissionGranted = true,
            ),
        )
    }

    @Test
    fun `a later API answers like the first one that has the permission`() {
        assertFalse(
            NotificationSupport.isSupported(
                sdkInt = NotificationSupport.PERMISSION_REQUIRED_SDK + 1,
                permissionGranted = false,
            ),
            "The permission was introduced in API 33 and stays in force after it; a " +
                "device on a later release is not more permissive than the first one " +
                "that carries the permission.",
        )
    }

    @Test
    fun `below API 33 the permission does not exist and the answer is yes`() {
        // TIRAMISU is where POST_NOTIFICATIONS was introduced. Below it there is
        // nothing to check, so the flag has to be true unconditionally — otherwise
        // reminders would stop firing on every device below 33 and nothing would
        // explain why.
        assertTrue(
            NotificationSupport.isSupported(
                sdkInt = NotificationSupport.PERMISSION_REQUIRED_SDK - 1,
                permissionGranted = false,
            ),
            "Below API 33 there is no permission to deny, so the answer cannot be no.",
        )
    }

    @Test
    fun `the answer follows the grant it is given, not a remembered one`() {
        // The user can revoke the permission from the settings while the app runs, and
        // Android can deliver the app back after a permission change — which is
        // exactly the moment a remembered value is wrong and the user is trying to fix
        // it. The rule holds no state; the platform reads the grant per call.
        val denied =
            NotificationSupport.isSupported(
                sdkInt = NotificationSupport.PERMISSION_REQUIRED_SDK,
                permissionGranted = false,
            )
        val granted =
            NotificationSupport.isSupported(
                sdkInt = NotificationSupport.PERMISSION_REQUIRED_SDK,
                permissionGranted = true,
            )

        assertTrue(granted, "precondition: the grant is what makes the difference")
        assertFalse(denied, "the answer was remembered from the earlier call")
    }
}
