package com.singularity.todo.feature.calendar_sync.work

import kotlin.time.Duration

/**
 * The platform-owned half of *Google* Calendar sync: something that fires a sync request
 * every [interval], and can be stopped.
 *
 * ## Why this exists and is separate from [com.singularity.todo.core.sync.SyncPeriodicTrigger]
 *
 * Same seam, different clock. The app's own sync runs on a user-chosen interval that lives in
 * the sync preferences and is re-read whenever the active scope changes; the Google pass has
 * no such setting, and its preconditions are different in kind — an account can be connected
 * or disconnected at any moment, which the sync core has no equivalent of. One binding that
 * served both would have to answer "whose interval?" and "configured how?", and the answer to
 * the second question is not the same on the two features. Two seams, two bindings.
 *
 * Like its sibling, this interface decides *when* and never *what*: it fires a request, and
 * [com.singularity.todo.feature.calendar_sync.sync.GoogleSyncCoordinator.syncNow] decides
 * whether there is anything to do. It is public for the same reason the sibling is — a
 * platform seam has to be nameable by every module that has to provide a platform,
 * including a test one.
 *
 * ## Why desktop is a supported platform, not an Android-only feature
 *
 * This was an open question, and the answer is that the Google pass is pure network plus
 * Room: an HTTPS call and rows in the local database. It needs no CalendarProvider, no
 * ContentResolver, no permission the user has to grant — every platform-specific piece of the
 * *system* calendar sync is absent by construction here. The only thing it needs from the OS is
 * "wake up later", and the JVM binding answers that with the same
 * [com.singularity.todo.core.sync.DelayLoopSyncPeriodicTrigger] the desktop app's own sync
 * uses. Excluding desktop would mean shipping a sync that works on a phone and silently does
 * nothing on the machine the user is sitting at.
 */
interface GoogleSyncPeriodicTrigger {

    /**
     * Arms the trigger to fire every [interval].
     *
     * Idempotent by construction rather than by a guard: re-arming replaces the previous
     * schedule instead of adding a second one — the JVM loop cancels its job first, and the
     * Android binding enqueues unique work with `ExistingPeriodicWorkPolicy.KEEP`. That is why
     * the app entry points can call this unconditionally without owning a "have I started"
     * flag whose only job would be to survive a second call that does nothing.
     */
    fun start(interval: Duration)

    /** Cancels any pending schedule. The next [start] re-arms it. */
    fun stop()

    /**
     * Whether a pass is worth attempting right now, re-read on every call.
     *
     * ## Why this is on the seam and not read once at arm time
     *
     * Because connecting a Google account is an ordinary thing a user does ten minutes after
     * launch. A trigger that decided at startup would either never start (the account was not
     * connected yet) or never stop (it was connected then, and is not now) — and neither
     * failure is visible: the loop runs, syncs nothing, and reports success. Reading the
     * answer per cycle turns "when did we arm" into "what is true now", which is the only
     * version of that question that survives the account changing underneath the app.
     *
     * It is `suspend` because the answer is not in memory: it is a DataStore read for the
     * chosen calendar and a keystore read for the credential, and both can fail. The worker's
     * retry policy depends on that failure being visible as an exception rather than folded
     * into a `false` that looks like "not configured".
     */
    suspend fun isConfigured(): Boolean
}

/**
 * How often the Google pass runs, in minutes.
 *
 * Not a setting and not a preference: the pass has no user-facing cadence control yet, and
 * adding a second place to configure an interval would be a field with no screen behind it.
 *
 * The value is WorkManager's documented minimum for a periodic request, and that is the whole
 * reason it is not shorter — Android clamps anything below it and throws at enqueue time. The
 * JVM loop has no such floor, but both platforms share one constant so that "how often does
 * Google sync run" has exactly one answer in the codebase.
 */
const val GOOGLE_SYNC_INTERVAL_MINUTES: Int = 15
