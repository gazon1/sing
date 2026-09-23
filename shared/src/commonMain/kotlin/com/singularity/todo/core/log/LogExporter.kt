package com.singularity.todo.core.log

/**
 * Exports application log files so they can be shared with a developer or support team.
 *
 * Platform implementations:
 * - **Android**: collects recent log files and launches [android.content.Intent.ACTION_SEND]
 *   with [android.content.Intent.EXTRA_STREAM] via FileProvider.
 * - **JVM**: copies log files to a timestamped export directory and copies the path
 *   to the system clipboard via [java.awt.Toolkit].
 */
interface LogExporter {
    /** Collects and exports log files. Implementation is platform-specific. */
    suspend fun export()
}
