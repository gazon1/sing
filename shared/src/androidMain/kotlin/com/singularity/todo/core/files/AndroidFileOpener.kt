package com.singularity.todo.core.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Android implementation of [FileOpener].
 *
 * Uses `FileProvider` via the authority `${applicationId}.fileprovider`, registered in
 * `AndroidManifest.xml`. The `attachments/` path is already declared in
 * `res/xml/file_paths.xml` — attachment files live under the app's own files dir under a
 * task id — so no manifest change accompanies this class.
 *
 * [ActivityNotFoundException] is the documented way for Android to say "nothing on this
 * device handles this type". It is an ordinary outcome rather than a failure, and it is
 * translated into [OpenOutcome.NoHandler] so the caller can offer the share sheet
 * instead of leaving the user on a button that did nothing.
 */
class AndroidFileOpener(private val context: Context) : FileOpener {

    override suspend fun open(filePath: String, mimeType: String): OpenOutcome {
        val file = File(filePath)
        if (!file.exists()) return OpenOutcome.NoHandler

        val uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalArgumentException) {
            // The path is outside every `files-path` in file_paths.xml. Same situation
            // as "no handler": the file cannot be opened from here.
            return OpenOutcome.NoHandler
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return try {
            context.startActivity(intent)
            OpenOutcome.Opened
        } catch (e: CancellationException) {
            throw e
        } catch (_: ActivityNotFoundException) {
            OpenOutcome.NoHandler
        } catch (_: SecurityException) {
            // No app holds the grant for this URI. Same user-visible situation as
            // "no handler": the file cannot be opened, and the caller needs an
            // alternative. Letting this escape would crash instead.
            OpenOutcome.NoHandler
        }
    }
}
