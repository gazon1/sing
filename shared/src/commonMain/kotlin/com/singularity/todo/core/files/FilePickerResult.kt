package com.singularity.todo.core.files

import io.github.vinceglb.filekit.PlatformFile

/**
 * Wraps FileKit's PlatformFile to provide stable, platform-neutral access to file metadata.
 * FileKit's own expect/actual properties (name, path, mimeType) can have resolution issues
 * in KMP when compiled across targets, so this wrapper shields callers from those problems.
 */
data class FilePickerResult(
    val path: String,
    val name: String,
    val mimeType: String?,
)

/** Extracts [FilePickerResult] from FileKit's PlatformFile in a platform-specific way. */
internal expect fun PlatformFile.toFilePickerResult(): FilePickerResult
