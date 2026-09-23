package com.singularity.todo.core.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import okio.Buffer
import okio.FileSystem
import okio.Path

/**
 * Android implementation of [LogExporter] — collects recent log entries and launches
 * [Intent.ACTION_SEND] so the user can email or share the log file.
 */
class IntentLogExporter(
    private val context: Context,
    private val logDirectory: Path = logDirectory(),
) : LogExporter {

    override suspend fun export() {
        val logFile = logDirectory.resolve("log.0.txt")
        if (!FileSystem.SYSTEM.exists(logFile)) {
            return
        }

        // Read up to MAX_SIZE bytes from the current log file.
        val buffer = Buffer()
        FileSystem.SYSTEM.source(logFile).use { src ->
            buffer.readFrom(src, MAX_EXPORT_SIZE)
        }

        // Write to cache dir for FileProvider access.
        val exportFile = context.cacheDir.toPath().resolve("singularity-logs.txt")
        FileSystem.SYSTEM.write(exportFile) { writeAll(buffer) }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile.toFile(),
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, "Share logs")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private companion object {
        /** Maximum bytes to export from the current log file (≈ 5 MB). */
        private const val MAX_EXPORT_SIZE = 5L * 1024 * 1024
    }
}

private fun java.io.File.toPath(): Path = okio.Path(this.absolutePath)
private fun Path.toFile(): java.io.File = java.io.File(toString())
