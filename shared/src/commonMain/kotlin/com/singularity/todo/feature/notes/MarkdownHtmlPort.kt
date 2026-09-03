package com.singularity.todo.feature.notes

/**
 * Bidirectional converter between Markdown and HTML.
 * SAM-convertible — can be instantiated as a lambda in tests:
 * ```
 * val port: MarkdownHtmlPort = object : MarkdownHtmlPort {
 *     override fun toHtml(md: String) = "<p>$md</p>"
 *     override fun toMarkdown(html: String) = html
 * }
 * ```
 */
interface MarkdownHtmlPort {
    fun toHtml(markdown: String): String
    fun toMarkdown(html: String): String
}
