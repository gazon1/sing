package com.singularity.todo.feature.notes

import com.singularity.todo.test.fakes.FakeSettingsRepository

/**
 * Minimal test double for [SettingsRepository] — implements interface directly.
 */
typealias FakeNotesSettingsRepository = FakeSettingsRepository

/** Pass-through HTML port for tests that don't need conversion verification */
class FakeMarkdownHtmlPort : MarkdownHtmlPort {
    override fun toHtml(md: String) = "<p>$md</p>"
    override fun toMarkdown(html: String) = html.trim().removePrefix("<p>").removeSuffix("</p>")
}
