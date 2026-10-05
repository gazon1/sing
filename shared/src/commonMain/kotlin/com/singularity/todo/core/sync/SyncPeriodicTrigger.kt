package com.singularity.todo.core.sync

import kotlin.time.Duration

/**
 * The platform-owned half of periodic sync: something that fires a request every
 * [interval], and can be stopped.
 *
 * The trigger does not run a sync cycle and does not decide *what* syncing means. It
 * only decides *when*, and it does that in a way the OS can or cannot survive:
 *
 * - Android: [com.singularity.todo.core.sync.work.SyncWorkScheduler] territory —
 *   WorkManager, which persists across process death, enforces its own backoff and
 *   respects battery and network constraints.
 * - JVM: a [kotlinx.coroutines.delay] loop, because a desktop app has no equivalent
 *   of AlarmManager that outlives its process.
 *
 * This seam exists because "which platform am I on" must not be answered by a type
 * test on an injected dependency. See [SyncRunner.startScheduledSync] for the bug
 * that produced it.
 *
 * ## Why it is public
 *
 * It was `internal`, which made it unbindable from `desktopApp`'s own test module —
 * and that graph then could not be built, so the desktop flow tests failed with a
 * `NoDefinitionFoundException` for a type nobody outside `:shared` is allowed to
 * name. A platform seam has to be nameable by every module that has to provide a
 * platform, including a test one. Its siblings `SyncWorkScheduler` and
 * `ReminderScheduler` are public for the same reason.
 */
interface SyncPeriodicTrigger {
    fun start(interval: Duration)
    fun stop()
}
