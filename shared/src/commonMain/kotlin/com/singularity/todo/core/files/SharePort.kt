package com.singularity.todo.core.files

/**
 * Hands text to the platform's native share sheet.
 *
 * Both actuals are best-effort and cannot report failure: Android's
 * `Intent.createChooser` and the desktop clipboard/system-open are fire-and-forget from
 * the caller's point of view, and a user who dismisses the chooser has not hit an error.
 * So the return value is [Boolean] "did we manage to start a share" rather than a
 * `Result` — there is no useful error to carry.
 */
interface SharePort {
    /**
     * Offers [text] to the user under the label [title].
     *
     * @return `true` if a share was started, `false` if the platform refused
     *   (e.g. no app on Android can handle `text/plain`).
     */
    fun shareText(title: String, text: String): Boolean
}
