package com.singularity.todo.core.log

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import java.awt.Toolkit
import java.lang.reflect.Method
import java.time.Instant

/**
 * JVM implementation of [LogExporter] — copies the current log file to a timestamped
 * export directory and copies the path to the system clipboard.
 */
class SaveToFileLogExporter(
    private val logDirectory: okio.Path = logDirectory(),
) : LogExporter {

    override suspend fun export() = withContext(Dispatchers.IO) {
        val logFile = logDirectory.resolve("log.0.txt")
        if (!FileSystem.SYSTEM.exists(logFile)) {
            return@withContext
        }

        val timestamp = Instant.now().toString().replace(":", "-")
        val exportDir = exportBase.resolve(timestamp)
        FileSystem.SYSTEM.createDirectories(exportDir)

        val dest = exportDir.resolve("singularity-logs.txt")

        // Copy log contents using raw byte array.
        val bytes = FileSystem.SYSTEM.read(logFile) { readByteArray() }
        FileSystem.SYSTEM.write(dest) { write(bytes) }

        // Copy path to clipboard.
        copyPathToClipboard(dest.toString())

        Logger.d("SaveToFileLogExporter") { "Logs exported to $dest" }
    }

    private val exportBase: okio.Path
        get() {
            val homePath = platformPath(System.getProperty("user.home") ?: "/tmp")
            return homePath.resolve(".local").resolve("share").resolve("singularity").resolve("logs-export")
        }

    private fun platformPath(raw: String): okio.Path {
        val method: Method = okio.Path::class.java.getMethod("get", String::class.java)
        @Suppress("UNCHECKED_CAST")
        return method.invoke(null, raw) as okio.Path
    }

    private fun copyPathToClipboard(path: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = java.awt.datatransfer.StringSelection(path)
        clipboard.setContents(selection, null)
    }
}
