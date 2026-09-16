package com.singularity.todo.core.files

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Android implementation of [FileRevealer].
 * Uses `ACTION_OPEN_DOCUMENT_TREE` to let the user pick and open their attachments folder
 * in the system file manager.
 */
class AndroidFileRevealer(private val context: Context) : FileRevealer {
    override suspend fun revealAttachmentsFolder(folderPath: String) {
        val folder = java.io.File(folderPath)
        if (!folder.exists()) folder.mkdirs()

        val uri = Uri.fromFile(folder)

        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            putExtra("android.provider.extra.INITIAL_URI", uri)
        }
        // This requires an Activity context — must be called from a Composable context.
        @Suppress("BatteryLife")
        context.startActivity(intent)
    }

    override fun attachmentsBasePath(): String = context.filesDir.absolutePath + "/attachments"
}
