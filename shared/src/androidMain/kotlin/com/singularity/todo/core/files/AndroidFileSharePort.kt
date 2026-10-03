package com.singularity.todo.core.files

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider

/**
 * Android implementation of [FileSharePort].
 *
 * Uses `FileProvider` via the authority `${applicationId}.fileprovider`,
 * which is registered in `AndroidManifest.xml` and maps `files/logs/` to the
 * `logs` path alias in `res/xml/file_paths.xml`.
 *
 * @param context Used to create the `FileProvider` URI. Injected as `Context`
 *   from Koin, matching the pattern used by [AndroidFileRevealer].
 */
class AndroidFileSharePort(private val context: Context) : FileSharePort {

    override fun shareFile(filePath: String, mimeType: String): Boolean {
        val file = java.io.File(filePath)
        if (!file.exists()) return false

        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, null)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }
}
