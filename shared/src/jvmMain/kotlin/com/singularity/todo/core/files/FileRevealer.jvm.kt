package com.singularity.todo.core.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * JVM implementation of [FileRevealer].
 * Opens the attachments folder using [Desktop.browse] with a file:// URI.
 */
class JvmFileRevealer : FileRevealer {
    override suspend fun revealAttachmentsFolder(folderPath: String) {
        val folder = File(folderPath)
        if (!folder.exists()) folder.mkdirs()
        withContext(Dispatchers.IO) {
            Desktop.getDesktop().browse(URI("file://${folder.absolutePath}"))
        }
    }

    override fun attachmentsBasePath(): String = System.getProperty("user.home") + "/.singularity-todo/attachments"
}
