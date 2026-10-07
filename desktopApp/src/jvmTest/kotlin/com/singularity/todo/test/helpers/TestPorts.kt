package com.singularity.todo.test.helpers

import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.FileSource
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.files.FileStat
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.SharePort
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.feature.pomodoro.PomodoroConfig
import com.singularity.todo.feature.pomodoro.PomodoroPhase
import com.singularity.todo.feature.pomodoro.PomodoroState
import com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import com.singularity.todo.core.sync.work.SyncWorkScheduler

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

/** No-op [ReminderScheduler]; the JVM real one arms `at` jobs. */
class InertReminderScheduler : ReminderScheduler {
    val scheduled = mutableListOf<Reminder>()

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
