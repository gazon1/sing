package com.singularity.todo.core.files

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Android [FileSource]: handles both local file paths and `content://` URIs.
 *
 * On Android, the system file picker (FileKit / SAF) returns a `content://` URI.
 * The [AndroidFileSystem] cannot handle these, so this class uses
 * [android.content.ContentResolver.openInputStream] directly.
 */
class AndroidFileSource(private val context: Context, private val path: String) : FileSource {
    override suspend fun readBytes(): ByteArray = if (path.startsWith("content://")) {
        val uri = Uri.parse(path)
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.FileNotFoundException("Cannot open: $uri")
    } else {
        File(path).readBytes()
    }
}

/**
 * Android [FileSourceFactory]: returns an [AndroidFileSource] using the app [Context].
 */
class AndroidFileSourceFactory(private val context: Context) : FileSourceFactory {
    override operator fun invoke(path: String): FileSource = AndroidFileSource(context, path)
}
