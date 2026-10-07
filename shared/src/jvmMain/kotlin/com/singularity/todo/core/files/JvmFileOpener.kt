package com.singularity.todo.core.files

import java.awt.Desktop
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * JVM implementation of [FileOpener].
 *
 * [Desktop.open] performs blocking I/O — it hands the URI to the desktop's file
 * association machinery and waits — which is why [FileOpener.open] is `suspend` and
 * why this class does no threading of its own; the injected scope does the moving.
 *
 * Two different failures arrive here and both mean the same thing to the user:
 * [UnsupportedOperationException] when the desktop environment has no file manager at
 * all (headless CI, some minimal Linux sessions), and [IOException] when the handler for
 * this specific type refuses the file. Neither is exceptional enough to propagate —
 * a desktop with no PDF reader is an ordinary configuration, and the caller's response
 * (explain, offer to share) is the same either way.
 */
class JvmFileOpener : FileOpener {

    override suspend fun open(filePath: String, mimeType: String): OpenOutcome {
        val file = File(filePath)
        if (!file.exists()) return OpenOutcome.NoHandler

        val desktop = try {
            if (!Desktop.isDesktopSupported()) return OpenOutcome.NoHandler
            Desktop.getDesktop()
        } catch (e: CancellationException) {
            throw e
        } catch (_: UnsupportedOperationException) {
            return OpenOutcome.NoHandler
        }

        return try {
            desktop.open(file)
            OpenOutcome.Opened
        } catch (e: CancellationException) {
            throw e
        } catch (_: UnsupportedOperationException) {
            OpenOutcome.NoHandler
        } catch (_: IOException) {
            OpenOutcome.NoHandler
        }
    }
}
