package com.singularity.todo.core.files

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * JVM implementation of [SharePort].
 *
 * Desktop has no system-wide "share sheet", so this composes the two things that do
 * exist: the system clipboard (always available) and, when the desktop integration
 * supports it, `Desktop.getDesktop().browse` on a `mailto:` URL carrying the text.
 *
 * The clipboard is the load-bearing half and is written unconditionally. `browse` is a
 * best-effort second — it throws on a headless or unsupported desktop, which is not a
 * failure the user needs to hear about, because they already have the text.
 */
class JvmSharePort : SharePort {

    override fun shareText(title: String, text: String): Boolean {
        val clipboard = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }.isSuccess

        val browsed = runCatching {
            val subject = java.net.URLEncoder.encode(title, Charsets.UTF_8.name())
            val body = java.net.URLEncoder.encode(text, Charsets.UTF_8.name())
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI("mailto:?subject=$subject&body=$body"))
                true
            } else {
                false
            }
        }.getOrDefault(false)

        return clipboard || browsed
    }
}
