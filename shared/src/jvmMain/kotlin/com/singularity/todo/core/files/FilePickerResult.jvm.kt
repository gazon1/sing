package com.singularity.todo.core.files

import io.github.vinceglb.filekit.PlatformFile
import java.io.File

internal actual fun PlatformFile.toFilePickerResult(): FilePickerResult {
    val javaFile: File = this.file
    return FilePickerResult(
        path = javaFile.absolutePath,
        name = javaFile.name,
        mimeType = null,
    )
}
