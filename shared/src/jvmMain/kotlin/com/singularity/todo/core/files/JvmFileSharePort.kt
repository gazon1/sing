package com.singularity.todo.core.files

import java.awt.Desktop
import java.io.File

/**
 * JVM implementation of [FileSharePort].
 *
 * Uses [Desktop.browse] on the file's URI. Only ZIP archives are shared
 * via this mechanism; other file types are offered as plain file references.
 */
class JvmFileSharePort : FileSharePort {

    override fun shareFile(filePath: String, mimeType: String): Boolean {
        val file = File(filePath)
        if (!file.exists()) return false

        return runCatching {
            val uri = file.toURI().toURL().toString()
            Desktop.getDesktop().browse(java.net.URI(uri))
        }.isSuccess
    }
}
