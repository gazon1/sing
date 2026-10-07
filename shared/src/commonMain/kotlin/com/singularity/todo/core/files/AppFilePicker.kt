package com.singularity.todo.core.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

/**
 * What the user is being asked to pick. The purpose drives both the dialog title
 * and the file-type filter, so a caller never has to hand-roll a [FileKitType].
 *
 * The filter is a convenience, not a guarantee: the Android document picker and the
 * desktop file dialog both let the user pick a different type in some configurations,
 * so code that consumes the result must still validate the payload it receives.
 */
enum class FilePickPurpose {
    /** A previously created backup archive to restore the database from. */
    Backup,

    /** An exported settings snapshot to import. */
    SettingsJson,

    /**
     * A file to attach to a task.
     *
     * Its filter is [MimeTypes.supportedExtensions] — the table the app already judges
     * files by — rather than a list written out here. That list did not exist, which is
     * why attaching a file from the UI was impossible end to end: there was no pick
     * purpose for it, so no caller could ask for one.
     */
    Attachment,
}

/** Dialog title shown for this purpose. */
internal val FilePickPurpose.title: String
    get() = when (this) {
        FilePickPurpose.Backup -> "Select backup file"
        FilePickPurpose.SettingsJson -> "Select settings file"
        FilePickPurpose.Attachment -> "Select file"
    }

/**
 * File-type filter for this purpose.
 *
 * Backups are zipped archives produced by [com.singularity.todo.core.backup.BackupExporter];
 * settings snapshots are the JSON produced by `SettingsExporter.exportAsJson()`;
 * attachments are anything the app's own [MimeTypes] table recognises.
 * FileKit filters by extension rather than MIME type, so all are expressed as extensions.
 */
internal val FilePickPurpose.fileKitType: FileKitType
    get() = when (this) {
        FilePickPurpose.Backup -> FileKitType.File(extensions = setOf("zip"))
        FilePickPurpose.SettingsJson -> FileKitType.File(extensions = setOf("json"))
        FilePickPurpose.Attachment -> FileKitType.File(extensions = MimeTypes.supportedExtensions)
    }

/**
 * Remembers a launcher for the platform's native file picker.
 *
 * There is no expect/actual split here: FileKit's `rememberFilePickerLauncher` is itself
 * multiplatform — on Android it drives an `ActivityResultContracts.OpenDocument` launcher,
 * on desktop it opens the AWT file dialog. Callers in `commonMain` therefore get a real
 * picker on both platforms from this single function.
 *
 * The returned lambda opens the picker. [onPicked] is invoked on the main thread exactly
 * once per launch, with `null` when the user cancels.
 *
 * **Reading the result.** [FilePickerResult.path] is a plain filesystem path on desktop,
 * but on Android it is a `content://` URI. Do not pass it to `java.io.File` — resolve it
 * through the content resolver on Android (see the backup restore path) or a plain file
 * read on desktop.
 *
 * @param purpose what the user is picking; sets the dialog title and file-type filter
 * @param onPicked receives the picked file, or `null` if the user cancelled
 * @return a lambda that opens the picker
 */
@Composable
fun rememberAppFilePicker(purpose: FilePickPurpose, onPicked: (FilePickerResult?) -> Unit): () -> Unit {
    // Reading the callback through rememberUpdatedState keeps a stable lambda identity
    // across recompositions even when the caller's callback is a fresh allocation.
    val currentOnPicked = rememberUpdatedState(onPicked)
    val launcher = rememberFilePickerLauncher(
        type = purpose.fileKitType,
        onResult = { file -> currentOnPicked.value(file?.toFilePickerResult()) },
    )
    return remember(launcher) { { launcher.launch() } }
}
