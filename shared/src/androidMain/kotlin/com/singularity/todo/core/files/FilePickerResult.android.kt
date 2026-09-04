package com.singularity.todo.core.files

import io.github.vinceglb.filekit.PlatformFile

internal actual fun PlatformFile.toFilePickerResult(): FilePickerResult {
    // On Android, PlatformFile wraps a Uri or a File.
    // toString() returns the URI string for content:// URIs.
    val uriString = this.toString()
    val name = uriString.substringAfterLast("/").substringBeforeLast("!")
    return FilePickerResult(
        path = uriString,
        name = name,
        mimeType = null,
    )
}
