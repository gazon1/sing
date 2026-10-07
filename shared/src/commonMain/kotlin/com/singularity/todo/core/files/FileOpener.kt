package com.singularity.todo.core.files

/**
 * The result of asking the platform to open a file.
 *
 * A `Boolean` would say only "did something happen", which leaves the caller with two
 * indistinguishable outcomes: the platform has a handler and opened it, or the platform
 * has none. The second is not an error — it is an ordinary situation on a device with
 * no PDF reader installed — and the user needs something to *do* about it: an
 * explanation and the share sheet. A sealed outcome makes that branch impossible to
 * forget, because a `when` over it will not compile while a case is missing.
 *
 * "No handler" is deliberately an outcome and not an exception. Throwing would mean the
 * caller had to catch it, and a caller that forgets leaves the user on a button that
 * does nothing.
 */
sealed interface OpenOutcome {
    /** The platform accepted the request and handed the file to a handler. */
    data object Opened : OpenOutcome

    /** No installed application can open this type. The caller must offer another way. */
    data object NoHandler : OpenOutcome
}

/**
 * Opens a file in whatever application the platform chooses for its type.
 *
 * `suspend` because both implementations block: `java.awt.Desktop` performs blocking
 * I/O on the JVM, and `startActivity` on Android must not run on the main thread's
 * caller's stack without the platform's own lifecycle guarantees.
 *
 * Android uses `ACTION_VIEW` with a `FileProvider` URI — the `attachments/` path is
 * already declared in `file_paths.xml`, so no manifest change is needed. JVM uses
 * `java.awt.Desktop`.
 *
 * This port does not own the lifecycle of [filePath]; the caller owns the file.
 */
interface FileOpener {
    suspend fun open(filePath: String, mimeType: String): OpenOutcome
}
