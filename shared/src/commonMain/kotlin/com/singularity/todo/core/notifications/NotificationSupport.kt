package com.singularity.todo.core.notifications

/**
 * Whether a device can show a notification — the rule, without the measurement.
 *
 * `AndroidNotifier.isSupported` answers a question about the *device*: is
 * `POST_NOTIFICATIONS` granted right now? Answering it needs a `Context`, and a
 * `Context` exists only in the platform source set — which is why the matrix used to
 * be asserted there, in a Robolectric class, and nowhere else.
 *
 * But the rule is not the measurement. Which cells of the matrix hold is a fact about
 * every device, not about Android's `Context`: below API 33 the permission does not
 * exist and the answer is yes whatever the grant says; from API 33 the grant is the
 * answer. So the rule lives here, in common code, and the platform class supplies the
 * two facts only it can read.
 *
 * The matrix is asserted once, in `NotificationSupportTest` on the JVM. The Android
 * class keeps the assertions that are about the port — that the grant is read per
 * call, and that a denied permission enqueues nothing.
 */
object NotificationSupport {
    /** `POST_NOTIFICATIONS` was introduced in API 33 (Tiramisu). Below it there is nothing to grant. */
    const val PERMISSION_REQUIRED_SDK = 33

    /**
     * Whether a notification can be shown.
     *
     * @param sdkInt the platform's API level.
     * @param permissionGranted whether `POST_NOTIFICATIONS` is granted *now*.
     */
    fun isSupported(
        sdkInt: Int,
        permissionGranted: Boolean,
    ): Boolean = sdkInt < PERMISSION_REQUIRED_SDK || permissionGranted
}
