package com.singularity.todo.test.helpers

import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.FileSource
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.files.FileStat
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.SharePort
import com.singularity.todo.core.notifications.NotificationPort
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

/** No-op [NotificationPort]; the JVM real one shells out to `notify-send`/`at`. */
class InertNotificationPort : NotificationPort {
    val scheduled = mutableListOf<String>()
    val cancelled = mutableListOf<String>()

    override val isAvailable: Boolean = false

    override suspend fun scheduleAt(
        key: String,
        title: String,
        body: String,
        fireAtEpochMs: Long,
        payload: String?,
        viewId: String?,
    ) {
        scheduled += key
    }

    override suspend fun cancel(key: String) {
        cancelled += key
    }

    override suspend fun cancelAll() {
        cancelled.clear()
    }
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
