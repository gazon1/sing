package com.singularity.todo.ui

/**
 * Records what a test did, so a failure can say where it was.
 *
 * ## Why the Android layer needs this
 *
 * The desktop Compose tests have had a step recorder for a while
 * (`desktopApp/src/jvmTest/.../test/helpers/StepRecorder.kt`), and the reason it
 * exists is the same one that produced the red `android-device-tests` runs: a
 * failure that says "the element was not there" is a question, and the answer is
 * usually "the test was three taps earlier than you think".
 *
 * The instrumented suite had no answer to that question. Its failures said which
 * element was missing, and nothing about what the test had done to get there — so
 * a run that failed on the fourth of six tab switches was indistinguishable from
 * a run that failed on the first.
 *
 * A step is a name, an optional detail, how long it took, and whether it worked.
 * The format is the desktop one, kept deliberately the same: two layers that
 * record steps should print them the same way, or a reader has to learn the
 * format twice.
 *
 * @property offsetMs milliseconds from the first recorded step.
 * @property name what the step did, e.g. `"awaitShell"`.
 * @property detail the thing it was done to, e.g. the resource id.
 * @property durationMs how long the step took.
 * @property ok whether the step completed without throwing.
 * @property errorLine a one-line excerpt of the failure, when there was one.
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
 * Accumulates the steps of one test.
 *
 * The recorder is thread-confined: an instrumented test runs its body on the
 * instrumentation thread, and the helpers it calls run there too. A plain list
 * without locks is enough, and a lock here would be a claim about concurrency
 * that nobody checked.
 */
class StepRecorder {
    private val records = ArrayList<StepRecord>(32)
    private var firstTimestamp: Long? = null

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    fun recordOk(
        name: String,
        detail: String?,
        start: Long,
    ) {
        val now = System.currentTimeMillis()
        val first = firstTimestamp ?: now.also { firstTimestamp = it }
        records.add(
            StepRecord(
                offsetMs = now - first,
                name = name,
                detail = detail,
                durationMs = elapsed(start),
                ok = true,
                errorLine = null,
            ),
        )
    }

    fun recordFail(
        name: String,
        detail: String?,
        start: Long,
        error: Throwable,
    ) {
        val now = System.currentTimeMillis()
        val first = firstTimestamp ?: now.also { firstTimestamp = it }
        records.add(
            StepRecord(
                offsetMs = now - first,
                name = name,
                detail = detail,
                durationMs = elapsed(start),
                ok = false,
                errorLine = error::class.simpleName + ": " + error.message.orEmpty().lines().first(),
            ),
        )
    }

    /**
     * The steps as a human-readable log, for `steps.txt`.
     *
     * ```
     * +0.00s  awaitShell(nav_tab_today)    OK    1.35s
     * +1.35s  click(nav_tab_inbox)         OK    0.12s
     * +1.47s  awaitObject(nav_tab_upcoming) FAIL  10.00s  AssertionError: ...
     * ```
     */
    fun format(): String = buildString {
        for (record in records) {
            val sign = if (record.offsetMs >= 0) "+" else ""
            val seconds = record.offsetMs / 1000.0
            val duration = record.durationMs / 1000.0
            val detail = record.detail?.let { "($it)" } ?: ""
            val status = if (record.ok) "OK  " else "FAIL"
            val error = record.errorLine?.let { "  $it" } ?: ""
            appendLine(
                "$sign${"%.2f".format(seconds)}s  ${record.name}$detail    $status  " +
                    "${"%.2f".format(duration)}s$error",
            )
        }
    }

    /**
     * The last few failed steps, for the failure message.
     *
     * The message is the part that reaches CI without anyone opening a file, so
     * the failed steps go in it: the reader learns where the test was, not only
     * that something was missing.
     */
    fun summary(last: Int = 3): String {
        val failed = records.filter { !it.ok }.takeLast(last)
        if (failed.isEmpty()) return ""
        return buildString {
            appendLine("Last steps:")
            for (record in failed) {
                val seconds = record.offsetMs / 1000.0
                val error = record.errorLine?.let { " → $it" } ?: ""
                val detail = record.detail?.let { "($it)" } ?: ""
                appendLine("  +${"%.2f".format(seconds)}s  ${record.name}$detail$error")
            }
        }
    }
}

/** The recorder of the test running on this thread, if there is one. */
private val currentRecorder = ThreadLocal<StepRecorder?>()

/** The current test's recorder, or null when the code runs outside a test scope. */
internal fun stepRecorder(): StepRecorder? = currentRecorder.get()

/** Installs [recorder] as the current thread's recorder. */
internal fun setStepRecorder(recorder: StepRecorder) {
    currentRecorder.set(recorder)
}

/** Clears the current thread's recorder. */
internal fun clearStepRecorder() {
    currentRecorder.remove()
}

/**
 * Runs [block] as a recorded step.
 *
 * The original throwable is always rethrown: this records what happened, it does
 * not decide what happens. A step that is not recorded because there is no
 * recorder (a helper called outside a test) still runs.
 */
internal inline fun <T> step(
    name: String,
    detail: String? = null,
    block: () -> T,
): T {
    val recorder = stepRecorder()
    if (recorder == null) return block()
    val start = System.currentTimeMillis()
    return try {
        block().also { recorder.recordOk(name, detail, start) }
    } catch (throwable: Throwable) {
        recorder.recordFail(name, detail, start, throwable)
        throw throwable
    }
}

/**
 * Runs [block] as a recorded step, treating a null result as a failure.
 *
 * A wait that timed out is a failed step even though nothing threw: the
 * element the test needed was not there, and a log that says "OK" for it sends
 * the reader looking in the wrong place.
 *
 * @param name the step name.
 * @param detail what the step was done to.
 * @param block the work; a null result is recorded as a failure.
 * @return whatever [block] returned, null included.
 */
internal inline fun <T : Any> stepOrFail(
    name: String,
    detail: String? = null,
    block: () -> T?,
): T? {
    val recorder = stepRecorder()
    if (recorder == null) return block()
    val start = System.currentTimeMillis()
    val result = block()
    if (result == null) {
        recorder.recordFail(name, detail, start, AssertionError("no $name result for $detail"))
    } else {
        recorder.recordOk(name, detail, start)
    }
    return result
}
