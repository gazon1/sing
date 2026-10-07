package com.singularity.todo.core.notifications

import com.singularity.todo.core.process.Subprocess

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Desktop notifications through `notify-send(1)`.
 *
 * ## Why `notify-send` and not a D-Bus call
 *
 * `notify-send` is the supported frontend of the desktop notification daemon, it is part
 * of a base install, and it handles the daemon handshake. A raw `org.freedesktop.Notifications`
 * call would mean resolving the bus and the interface by hand for no behavioural gain.
 *
 * ## Why `isSupported` probes the host instead of returning `true`
 *
 * This class is constructed on a machine that may have no `notify-send` (a container, a
 * CI runner, an SSH session without a display) and a session bus that exists but has no
 * notification daemon. In both cases `notify-send` fails or hangs. A constant `true`
 * would put that knowledge in every caller instead of in the one place that can measure
 * it, which is exactly the mistake `NotificationPort` made in the opposite direction.
 *
 * The probe runs **once**, lazily. It shells out to `--version`, which writes to stdout
 * and never touches a bus, so it cannot hang on a headless host the way `notify-send`
 * itself can.
 */
class JvmNotifier(
    private val scope: CoroutineScope,
    private val runCommand: suspend (List<String>) -> Int = { argv -> Subprocess.runQuietly(argv) },
    /** Where the blocking subprocess runs. Injected so a test can run it inline. */
    private val ioContext: CoroutineContext = Dispatchers.IO,
    /**
     * Whether this host can display anything. Injected so a test can declare a host
     * rather than inherit whatever machine the build happens to run on.
     *
     * Separate from [runCommand] because the probe runs **outside** any coroutine — it is
     * a blocking call reachable from the non-suspending `isSupported` — and routing it
     * through the suspending runner would mean the first assertion of this class depended
     * on whether the developer had `notify-send` installed.
     */
    private val hostCanDisplay: () -> Boolean = ::probeNotifySend,
) : Notifier {

    private val available: Boolean by lazy { hostCanDisplay() }

    override val isSupported: Boolean get() = available

    override fun post(tag: String, title: String, body: String, viewId: String?) {
        // Fire-and-forget: `post` is not suspending because it is called from
        // `AlarmReceiver.handleReminderFire`'s analogue — a process that exists only to
        // say one thing and exit. Blocking a non-suspending call here would stall a
        // worker thread for the length of a subprocess.
        scope.launch {
            if (!available) return@launch
            withContext(ioContext) {
                runCommand(
                    // `viewId` is ignored: `notify-send` has no tap-through action, so
                    // the deeplink the notification would carry cannot be delivered here.
                    // Recorded as the one field of the port this platform cannot honour.
                    listOf(
                        NOTIFY_SEND,
                        // `--replace` makes the tag a replacement key rather than an
                        // always-stacked new notification. A reminder that fires twice
                        // (a reboot catch-up after a real fire, say) then shows once.
                        "--replace=$tag",
                        "--app-name=Singularity",
                        title,
                        body,
                    ),
                )
            }
        }
    }

    private companion object {
        const val NOTIFY_SEND = "notify-send"

        /**
         * Whether `notify-send` is on this host.
         *
         * `--version` prints and exits; it never contacts a bus, so it cannot hang on a
         * headless host the way `notify-send` itself can. Exit code 0 is the signal —
         * anything else, including a missing binary, means this host cannot be relied on
         * to display anything.
         *
         * The `Process` is bound to a name on purpose: `start().inputStream.close()`
         * chains off a `Unit`-returning call and does not compile as a fluent expression,
         * which is how the first version of this file went wrong.
         */
        fun probeNotifySend(): Boolean = Subprocess.runQuietly(listOf(NOTIFY_SEND, "--version")) == 0
    }
}
