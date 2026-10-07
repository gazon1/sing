package com.singularity.todo.test.helpers

import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.FileSharePort
import com.singularity.todo.core.files.FileSource
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.files.FileStat
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.SharePort
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.notifications.Notifier
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import com.singularity.todo.core.work.BackgroundWorkScheduler
import com.singularity.todo.core.work.JobSchedule
import com.singularity.todo.feature.calendar_sync.work.GoogleSyncPeriodicTrigger
import com.singularity.todo.feature.pomodoro.PomodoroConfig
import com.singularity.todo.feature.pomodoro.PomodoroPhase
import com.singularity.todo.feature.pomodoro.PomodoroState
import com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Inert implementations of the JVM ports a desktop flow test would otherwise
 * reach through `platformModule()`.
 *
 * The production JVM implementations are not merely inconvenient in a test — they
 * shell out to `secret-tool` and `notify-send`, start a daemon coroutine loop, and
 * write under `~/.singularity-todo`. Each class here records the calls it
 * received so a test that unexpectedly depends on one can assert on it rather
 * than discover the dependency by surprise.
 */

/** In-memory [SecureStoragePort]; the JVM real one shells out to `secret-tool`. */
class InMemorySecureStorage : SecureStoragePort {
    private val values = mutableMapOf<String, String>()

    override suspend fun read(key: String): String? = values[key]

    override suspend fun write(key: String, value: String) {
        values[key] = value
    }

    override suspend fun delete(key: String) {
        values.remove(key)
    }

    override fun isHardwareBacked(): Boolean = false
}

/** In-memory [FileSystem]; nothing reaches the real filesystem. */
class InMemoryFileSystem : FileSystem {
    val files = mutableMapOf<String, ByteArray>()

    override suspend fun readBytes(path: String): ByteArray =
        files[path] ?: error("no such file in InMemoryFileSystem: $path")

    override suspend fun writeBytes(path: String, data: ByteArray) {
        files[path] = data
    }

    override suspend fun delete(path: String): Boolean = files.remove(path) != null

    override suspend fun exists(path: String): Boolean = files.containsKey(path)

    override suspend fun ensureDir(dir: String) = Unit

    override suspend fun listDir(dir: String): List<String> =
        files.keys.filter { it.startsWith("$dir/") }

    override suspend fun stat(path: String): FileStat? =
        files[path]?.let {
            FileStat(
                path = path,
                lastModifiedEpochMillis = 0L,
                sizeBytes = it.size.toLong(),
                isDirectory = false,
            )
        }
}

/** Records share requests instead of opening a system dialog. */
class InertSharePort : SharePort {
    val shared = mutableListOf<Pair<String, String>>()

    override fun shareText(title: String, text: String): Boolean {
        shared += title to text
        return true
    }
}

/**
 * Records file-share requests instead of handing one to the host desktop.
 *
 * Separate from [InertSharePort] rather than a flag on it: this port takes a *path* and a
 * MIME type, and the only caller is Settings' "Export logs" row. A test that presses it
 * wants the call recorded so it can assert the exporter produced a real archive — which is
 * the property worth checking — and it must not open a file manager on a machine that has
 * no display.
 */
class InertFileSharePort : FileSharePort {
    val sharedFiles = mutableListOf<Pair<String, String>>()

    override fun shareFile(filePath: String, mimeType: String): Boolean {
        sharedFiles += filePath to mimeType
        return true
    }
}

/** Always fails: no flow under test imports a backup. */
class UnusedBackupCodec : BackupCodec {
    override suspend fun export(
        manifestBytes: ByteArray,
        payloadBytes: ByteArray,
        attachments: List<Pair<String, ByteArray>>,
        destPath: String,
        fs: FileSystem,
    ): Result<Unit> = error("BackupCodec is not available in desktop flow tests")

    override suspend fun import(sourcePath: String, fs: FileSystem): Result<BackupCodec.CodecReadResult> =
        error("BackupCodec is not available in desktop flow tests")

    override suspend fun importFromSource(source: FileSource): Result<BackupCodec.CodecReadResult> =
        error("BackupCodec is not available in desktop flow tests")
}

/** No-op [FileSourceFactory] — desktop has no content:// or SAF picker. */
class InertFileSourceFactory : FileSourceFactory {
    override fun invoke(path: String): FileSource =
        error("FileSourceFactory is not available in desktop flow tests: $path")
}

/** No-op [ReminderScheduler] for desktop flow tests. */
class InertReminderScheduler : ReminderScheduler {
    val scheduled = mutableListOf<Reminder>()

    /**
     * False, matching the real [com.singularity.todo.feature.reminders.JvmReminderScheduler].
     *
     * A fake that claimed support the platform does not have would let a test pass
     * against behaviour desktop cannot produce. The desktop app currently arms no
     * reminders at all — the `at`-based backend was deleted; see
     * `docs/decisions/2026-10-06-notification-port-deleted-because-it-cancelled-other-peoples-jobs.md`.
     */
    override val isSupported: Boolean = false

    override suspend fun schedule(reminder: Reminder) {
        scheduled += reminder
    }

    override suspend fun cancel(id: ReminderId, userId: UserId) = Unit

    override suspend fun cancelByTask(taskId: TaskId, userId: UserId) = Unit
}

/** Empty focus-task list; the flow suite seeds tasks through the database instead. */
class EmptyPomodoroTaskListProvider : PomodoroTaskListProvider {
    override fun tasks(): StateFlow<List<Task>> = MutableStateFlow(emptyList())
}

/**
 * A [PomodoroTimer] that advances only when a test tells it to.
 *
 * The JVM real timer (`JvmPomodoroTimer`) owns a `Dispatchers.Default` scope and
 * ticks against the wall clock, so its countdown would be non-deterministic in a
 * test. This one holds the same state machine — start / pause / resume / stop /
 * skip and the resulting `isRunning` flip — with no clock behind it, which is what
 * lets a flow assert the play/pause control swap.
 */
class TestPomodoroTimer(
    private val taskListProvider: PomodoroTaskListProvider,
    override val config: PomodoroConfig,
) : PomodoroTimer {

    private val _state = MutableStateFlow(PomodoroState())
    override val state: StateFlow<PomodoroState> = _state.asStateFlow()

    override fun start(taskId: String?) {
        _state.value = _state.value.copy(isRunning = true, taskId = taskId)
    }

    override fun pause() {
        _state.value = _state.value.copy(isRunning = false)
    }

    override fun resume() {
        _state.value = _state.value.copy(isRunning = true)
    }

    override fun stop() {
        _state.value = PomodoroState(
            phase = PomodoroPhase.Work,
            remainingSeconds = config.phaseSecondsOf(PomodoroPhase.Work),
            phaseDurationSeconds = config.phaseSecondsOf(PomodoroPhase.Work),
        )
    }

    override fun skip() {
        _state.value = _state.value.copy(isRunning = false)
    }
}

/**
 * A [SyncWorkScheduler] that records what it was asked and starts nothing.
 *
 * ## Why not the real one
 *
 * `JvmSyncWorkScheduler` (ADR-backed, replaces the deleted `NoopSyncWorkScheduler`) hands
 * work to a `BackgroundWorkScheduler`, and on Desktop that starts a daemon coroutine loop.
 * Binding it in a test module would keep a thread alive for the rest of the run and make
 * the suite's timing depend on how many tests ran before it — the same reason
 * `InertSyncPeriodicTrigger` and `InertReminderScheduler` are inert rather than real.
 *
 * ## Why this is a test double at all, and not a deletion
 *
 * `276a70e3` deleted `NoopSyncWorkScheduler` and replaced it with a working scheduler,
 * which is the right change: a no-op that every Desktop sign-in wrote into is how a push
 * got discarded silently. It left `TestPlatformModule` pointing at the deleted class,
 * and because that file is only compiled by `:desktopApp:test` — step 10 of the gate —
 * the breakage sat on `main` until a gate ran this far. The production binding and the
 * test binding want different things from this seam, so both are spelled out here rather
 * than one standing in for the other.
 */
class InertSyncWorkScheduler : SyncWorkScheduler {
    val oneShot = mutableListOf<Int>()
    val periodic = mutableListOf<Long>()

    override fun enqueuePush() = Unit

    override fun cancelPush() = Unit

    override fun enqueuePeriodic(intervalMillis: Long) = Unit

    override fun cancelPeriodic() = Unit
}

/**
 * Records background work instead of scheduling it.
 *
 * The production `JvmBackgroundWorkScheduler` starts a daemon coroutine loop and keeps a
 * thread alive for the rest of the run. Under `same_thread` parallelism that loop survives
 * its own test class and makes every later timing measurement depend on how many tests ran
 * before it, so the test graph binds this instead.
 */
class InertBackgroundWorkScheduler : BackgroundWorkScheduler {
    val scheduled = mutableListOf<String>()
    val ranNow = mutableListOf<String>()

    override fun schedule(jobId: String, schedule: JobSchedule) {
        scheduled += jobId
    }

    override fun cancel(jobId: String) {
        scheduled -= jobId
    }

    override fun runNow(jobId: String) {
        ranNow += jobId
    }
}

/**
 * Google sync's periodic trigger, inert.
 *
 * Returns `isConfigured() == false` so nothing arms it. Same reason as
 * [InertSyncWorkScheduler]: the production `DelayLoopGoogleSyncPeriodicTrigger` resolves a
 * `CoroutineScope` and starts a delay loop, which is precisely what a test must not start.
 */
class InertGoogleSyncPeriodicTrigger : GoogleSyncPeriodicTrigger {
    override fun start(interval: kotlin.time.Duration) = Unit

    override fun stop() = Unit

    override suspend fun isConfigured(): Boolean = false
}

/**
 * A [CoroutineScope] whose job is already cancelled.
 *
 * Bound so the graph *shape* matches production while guaranteeing the shape cannot run
 * anything. The alternative — a live `createBackgroundScope` — would hand a real
 * `Dispatchers.Default` scope to whatever resolved it, and a launched job there would
 * outlive its test class and make every later timing measurement depend on ordering.
 * Cancelled is the honest version of "this exists so nothing has to be created": a
 * launch into it throws `CancellationException` immediately, which is a loud failure
 * rather than a thread that quietly never ends.
 */
fun alreadyCancelledScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default).also { it.cancel() }

/**
 * Records notifications instead of showing them.
 *
 * The production `JvmNotifier` shells out to `notify-send`, which on a headless CI box
 * either fails or blocks — and a notification appearing during a test would be a real
 * window stealing focus from the Compose test that is running. Recording them lets a test
 * assert that a reminder *was* raised, which is the property worth checking.
 */
class RecordingNotifier : Notifier {
    val posted = mutableListOf<Triple<String, String, String>>()

    /**
     * False, deliberately.
     *
     * The real implementation probes for `notify-send` and a session bus, so on a
     * headless box it would already answer false — and a test asserting *true* would be
     * asserting that the test host has a desktop session, which is not a property of the
     * app. Callers gate on this before posting, so false exercises the same branch a
     * headless CI run takes while still recording what would have been posted.
     */
    override val isSupported: Boolean = false

    override fun post(tag: String, title: String, body: String, viewId: String?) {
        posted += Triple(tag, title, body)
    }
}
