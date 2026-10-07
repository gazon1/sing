package com.singularity.todo.core.attachments

import com.singularity.todo.core.files.FileCategory
import com.singularity.todo.core.files.MimeTypes

/**
 * Where an attachment should be opened.
 *
 * [External] is a real destination, not a failure: the platform may well have a handler.
 * What the platform lacks is a guarantee, which is why [External] arrives at the user as
 * a choice between "open it there" and "share it", never as a dead button.
 */
enum class ViewerTarget {
    /** The in-app image viewer: bitmap, zoom, pan. */
    InAppImage,

    /** The in-app text viewer, with markdown formatting for `.md`. */
    InAppText,

    /** Hand the file to the platform. */
    External,
}

/**
 * A resolved open request: what to show, and the type that decided it.
 *
 * [mimeType] is carried because the fallback screens explain *why* a file went out
 * externally, and "there is no built-in viewer for `application/pdf`" is an answer
 * while "there is no built-in viewer" is not.
 */
data class ViewerRoute(val target: ViewerTarget, val mimeType: String)

/**
 * Decides where an attachment opens. Pure, and free of platform types, so it is
 * testable without an emulator and cannot drift between platforms.
 *
 * The MIME type decides when it is known; the extension decides when it is not. An
 * attachment restored from a backup can carry a file name and no MIME type, and a
 * missing MIME type must not send every such file to the platform when half of them
 * are images the app can show perfectly well.
 *
 * SVG is the one deliberate asymmetry: `image/svg+xml` starts with `image/` and is
 * still [ViewerTarget.External], because the project has no SVG renderer. See
 * [MimeTypes.classifyMime].
 */
object AttachmentViewerRoute {

    fun routeFor(fileName: String?, mimeType: String?): ViewerRoute {
        val resolvedMime = mimeType?.takeIf { it.isNotBlank() }
            ?: fileName?.let { name ->
                MimeTypes.extensionOf(name)?.let { MimeTypes.fromExtension(it) }
            }
            ?: "application/octet-stream"

        val category = MimeTypes.classifyMime(resolvedMime, fileName)
        val target = when (category) {
            FileCategory.Image -> ViewerTarget.InAppImage
            FileCategory.Text -> ViewerTarget.InAppText
            FileCategory.External -> ViewerTarget.External
        }
        return ViewerRoute(target = target, mimeType = resolvedMime)
    }
}
