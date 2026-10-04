package com.singularity.todo.test.helpers

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.math.roundToLong

/**
 * Records a single test step: name, optional detail, duration, pass/fail, and error.
 *
 * @property offsetMs  Milliseconds from the first recorded step.
 * @property name      Step name, e.g. `"awaitTag"`.
 * @property detail    Extra context, e.g. the tag being awaited.
 * @property durationMs How long the step took in milliseconds.
 * @property ok        True if the step completed without throwing.
 * @property errorLine One-line excerpt of the exception message, or null if ok.
 */
data class StepRecord(
    val offsetMs: Long,
    val name: String,
    val detail: String?,
    val durationMs: Long,
    val ok: Boolean,
    val errorLine: String?,
)

/**
 * Accumulates step records for one test.
 *
 * Thread-confined: a test always runs on one thread (enforced by
 * `junit.jupiter.execution.parallel.mode.default = same_thread`), so a plain
 * `ArrayList` without locks is sufficient.
 *
 * `ThreadLocal` is used so step recording is available inside any helper call
 * without threading confusion.
 */
class StepRecorder {
    private val records = ArrayList<StepRecord>(32)
    private var firstTimestamp: Long? = null

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    fun recordOk(name: String, detail: String?, start: Long) {
        val now = System.currentTimeMillis()
        val first = firstTimestamp ?: now.also { firstTimestamp = it }
        val offset = now - first
        records.add(StepRecord(offset, name, detail, elapsed(start), ok = true, errorLine = null))
    }

    fun recordFail(name: String, detail: String?, start: Long, error: Throwable) {
        val now = System.currentTimeMillis()
        val first = firstTimestamp ?: now.also { firstTimestamp = it }
        val offset = now - first
        val msg = error::class.simpleName + ": " + error.message.orEmpty().lines().first()
        records.add(StepRecord(offset, name, detail, elapsed(start), ok = false, errorLine = msg))
    }

    /**
     * Formats all records as a human-readable step log for `steps.txt`.
     *
     * ```
     * +0.00s  awaitTag(project_detail_quick_add)    OK    0.12s
     * +0.13s  tap(FAB_CREATE_PROJECT)             OK    0.03s
     * +0.16s  awaitTag(project_detail_quick_add)    FAIL  5.00s  Tag ... not found
     * ```
     */
    fun format(): String = buildString {
        for (r in records) {
            val sign = if (r.offsetMs >= 0) "+" else ""
            val secs = r.offsetMs / 1000.0
            val dur = r.durationMs / 1000.0
            val det = r.detail?.let { "($it)" } ?: ""
            val status = if (r.ok) "OK  " else "FAIL"
            val err = r.errorLine?.let { "  $it" } ?: ""
            appendLine("$sign${"%.2f".format(secs)}s  ${r.name}$det    $status  ${"%.2f".format(dur)}s$err")
        }
    }

    /**
     * Returns the last `last` failed records as a summary string, suitable for
     * embedding in a suppressed exception message so it appears in CI reports
     * without opening the bundle directory.
     */
    fun summary(last: Int = 3): String {
        val failed = records.filter { !it.ok }.takeLast(last)
        if (failed.isEmpty()) return ""
        return buildString {
            appendLine("Last steps:")
            for (r in failed) {
                val secs = r.offsetMs / 1000.0
                val err = r.errorLine?.let { " → $it" } ?: ""
                appendLine("  +${"%.2f".format(secs)}s  ${r.name}${r.detail?.let { "($it)" } ?: ""}$err")
            }
        }
    }

    fun lastFailedStepDetail(): String? = records.lastOrNull { !it.ok }?.detail
}

/** Thread-local StepRecorder for the current test, or null when not available. */
private val currentRecorder = ThreadLocal<StepRecorder?>()

/** Returns the current test's StepRecorder, or null when outside a test scope. */
@PublishedApi
internal fun stepRecorder(): StepRecorder? = currentRecorder.get()

/**
 * Installs [recorder] as the current thread's StepRecorder.
 * Called by the test harness before the test body; cleared in `finally`.
 */
internal fun setStepRecorder(recorder: StepRecorder) {
    currentRecorder.set(recorder)
}

/** Clears the current thread's StepRecorder. */
internal fun clearStepRecorder() {
    currentRecorder.remove()
}

/**
 * Wraps a helper call with step recording.
 *
 * The step name describes the operation; [detail] stores structured context
 * (e.g. the testTag) so it is available for the annotated screenshot without
 * regex extraction from the name.
 *
 * The original throwable is always rethrown — this function never swallows errors.
 */
@OptIn(ExperimentalTestApi::class)
inline fun <T> DesktopComposeUiTest.step(
    name: String,
    detail: String? = null,
    block: () -> T,
): T {
    val recorder = stepRecorder()
    if (recorder == null) return block() // no-op outside harness (e.g. runIsolatedComposeTest)
    val start = System.currentTimeMillis()
    return try {
        block().also { recorder.recordOk(name, detail, start) }
    } catch (t: Throwable) {
        recorder.recordFail(name, detail, start, t)
        throw t
    }
}
