package com.singularity.todo.core.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * JVM implementation of [FileRevealer].
 * Opens the attachments folder using [Desktop.browse] with a file:// URI.
 */
class JvmFileRevealer : FileRevealer {
    override suspend fun revealAttachmentsFolder(folderPath: String): Boolean {
        val folder = File(folderPath)
        if (!folder.exists()) folder.mkdirs()

        return withContext(Dispatchers.IO) {
            // `Throwable`, not `Exception`, and that is the whole difficulty.
            //
            // The obvious guard — ask `GraphicsEnvironment.isHeadless()` first —
            // does not work, because this host sets `DISPLAY=:0` with no server
            // behind it, so `isHeadless()` answers `false` and then initialises
            // the graphics environment to do it: `AWTError: Can't connect to X11
            // window server`. Asking the question is the failure. A guarded
            // version of this class shipped and passed its own filtered test run
            // while the uncaught exception went straight past it.
            //
            // Nor does `runCatchingCancellable` cover it: that helper catches
            // `CancellationException` and `Exception` and deliberately lets
            // `Error` through, which is right for a real `Error` and wrong here,
            // because a half-initialised toolkit fails as `NoClassDefFoundError`
            // on `sun.awt.X11.XToolkit`.
            //
            // So: catch everything, rethrow cancellation. What is being asked
            // of this method is "did the system open a window for the user", and
            // any failure to reach one is the same answer — `false` — which is
            // the answer the caller can show a message about. Suppressed rather
            // than baselined because the narrow alternative is a list of the
            // four AWT failure types, which is a list that grows on the next JDK.
            @Suppress("TooGenericExceptionCaught")
            try {
                Desktop.getDesktop().browse(folder.toURI())
                true
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Throwable) {
                false
            }
        }
    }

    override fun attachmentsBasePath(): String = System.getProperty("user.home") + "/.singularity-todo/attachments"
}
