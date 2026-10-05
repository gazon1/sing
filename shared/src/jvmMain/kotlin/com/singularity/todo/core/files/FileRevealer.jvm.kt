package com.singularity.todo.core.files

import com.singularity.todo.core.error.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.File
import java.net.URI

/**
 * JVM implementation of [FileRevealer].
 * Opens the attachments folder using [Desktop.browse] with a file:// URI.
 */
class JvmFileRevealer : FileRevealer {
    override suspend fun revealAttachmentsFolder(folderPath: String): Boolean {
        val folder = File(folderPath)
        if (!folder.exists()) folder.mkdirs()

        // Checked before touching `Desktop` at all, because reaching for it is
        // what fails: on a host with no display `Desktop.isDesktopSupported()`
        // initialises the AWT toolkit and throws `NoClassDefFoundError` on
        // `sun.awt.X11.XToolkit` rather than answering the question. That is the
        // failure this class used to hand straight to the caller, from a button
        // in Settings. `GraphicsEnvironment.isHeadless()` only reads a system
        // property, so asking it cannot fail.
        if (GraphicsEnvironment.isHeadless()) return false

        return withContext(Dispatchers.IO) {
            // The runCatching is not belt-and-braces: `browse` also throws
            // `IOException` when the platform has no handler registered for the
            // scheme, which is the normal state of a bare Linux container.
            runCatchingCancellable { Desktop.getDesktop().browse(URI(folder.toURI().toString())) }.isSuccess
        }
    }

    override fun attachmentsBasePath(): String = System.getProperty("user.home") + "/.singularity-todo/attachments"
}
