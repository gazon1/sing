package com.singularity.todo.core.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.singularity.todo.core.error.runCatchingCancellable

/**
 * Android implementation of [FileRevealer].
 * Uses `ACTION_OPEN_DOCUMENT_TREE` to let the user pick and open their attachments folder
 * in the system file manager.
 */
class AndroidFileRevealer(private val context: Context) : FileRevealer {
    @Suppress("BatteryLife")
    // Reported rather than thrown, for the same reason as the JVM side: a
    // device with no document-picker activity (a stripped build, a kiosk
    // profile) would otherwise take the Settings screen down with it.
    //
    // The `return` is load-bearing. With an annotation sitting directly above
    // it, the trailing expression is parsed as a plain statement rather than a
    // declaration, so the implicit return value stops being one — and only
    // :androidApp:assembleDebug notices, since nothing in the JVM target set
    // compiles this file.
    override suspend fun revealAttachmentsFolder(folderPath: String): Boolean {
        val folder = java.io.File(folderPath)
        if (!folder.exists()) folder.mkdirs()

        val uri = Uri.fromFile(folder)

        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            putExtra("android.provider.extra.INITIAL_URI", uri)
        }
        return runCatchingCancellable { context.startActivity(intent) }.isSuccess
    }

    override fun attachmentsBasePath(): String = context.filesDir.absolutePath + "/attachments"
}
