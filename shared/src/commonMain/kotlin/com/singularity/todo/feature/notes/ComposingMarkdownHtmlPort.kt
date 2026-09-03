package com.singularity.todo.feature.notes

/**
 * Production [MarkdownHtmlPort] using simple regex-based conversion.
 *
 * toHtml: basic markdown → HTML
 * toMarkdown: basic HTML → markdown
 */
class RichEditorMarkdownHtmlPort : MarkdownHtmlPort {

    override fun toHtml(markdown: String): String {
        if (markdown.isBlank()) return ""
        val lines = markdown.split('\n')
        val out = StringBuilder()
        var inCodeBlock = false
        var inList = false

        for (line in lines) {
            when {
                line.startsWith("```") -> {
                    if (inCodeBlock) {
                        out.append("</code></pre>")
                        inCodeBlock = false
                    } else {
                        out.append("<pre><code>")
                        inCodeBlock = true
                    }
                }
                inCodeBlock -> out.append(line.replace("<", "&lt;").replace(">", "&gt;"))
                line.startsWith("### ") -> { out.append("<h3>").append(line.removePrefix("### ")).append("</h3>") }
                line.startsWith("## ") -> { out.append("<h2>").append(line.removePrefix("## ")).append("</h2>") }
                line.startsWith("# ") -> { out.append("<h1>").append(line.removePrefix("# ")).append("</h1>") }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    if (!inList) { out.append("<ul>"); inList = true }
                    out.append("<li>").append(line.drop(2)).append("</li>")
                }
                line.matches(Regex("^\\d+\\.\\s.*")) -> {
                    if (!inList) { out.append("<ol>"); inList = true }
                    out.append("<li>").append(line.replaceFirst(Regex("^\\d+\\.\\s"), "")).append("</li>")
                }
                else -> {
                    if (inList) { out.append("</ul>"); inList = false }
                    out.append(convertInline(line)).append("<br>")
                }
            }
        }
        if (inList) out.append("</ul>")
        return "<p>${out}</p>".replace("<p></p>", "")
    }

    private fun convertInline(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) { append("<strong>").append(text.substring(i + 2, end)).append("</strong>"); i = end + 2 }
                    else { append(text[i]); i++ }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) { append("<code>").append(text.substring(i + 1, end)).append("</code>"); i = end + 1 }
                    else { append(text[i]); i++ }
                }
                text[i] == '*' || (text[i] == '_' && !text.startsWith("__", i)) -> {
                    val end = text.indexOf(text[i], i + 1)
                    if (end != -1) { append("<em>").append(text.substring(i + 1, end)).append("</em>"); i = end + 1 }
                    else { append(text[i]); i++ }
                }
                text.startsWith("[", i) -> {
                    val closeBracket = text.indexOf(']', i)
                    val openParen = text.indexOf('(', closeBracket + 1)
                    val closeParen = text.indexOf(')', openParen + 1)
                    if (closeBracket != -1 && openParen != -1 && closeParen != -1) {
                        append("<a href=\"")
                        append(text.substring(openParen + 1, closeParen))
                        append("\">")
                        append(text.substring(i + 1, closeBracket))
                        append("</a>")
                        i = closeParen + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                else -> { append(text[i]); i++ }
            }
        }
    }

    override fun toMarkdown(html: String): String {
        if (html.isBlank()) return ""
        return html
            .replace("<strong>", "**").replace("</strong>", "**")
            .replace("<b>", "**").replace("</b>", "**")
            .replace("<em>", "*").replace("</em>", "*")
            .replace("<i>", "*").replace("</i>", "*")
            .replace("<u>", "_").replace("</u>", "_")
            .replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
            .replace("<p>", "").replace("</p>", "\n")
            .replace("<h1>", "# ").replace("</h1>", "\n")
            .replace("<h2>", "## ").replace("</h2>", "\n")
            .replace("<h3>", "### ").replace("</h3>", "\n")
            .replace("<ul><li>", "- ").replace("</li></ul>", "\n").replace("</li>", "\n")
            .replace("<ol><li>", "1. ").replace("</li></ol>", "\n")
            .replace("<code>", "`").replace("</code>", "`")
            .replace("<pre><code>", "```\n").replace("</code></pre>", "\n```")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"")
            .let { val linkRegex = Regex("<a href=\"([^\"]+)\">([^<]+)</a>")
                linkRegex.replace(it) { m -> "[${m.groupValues[2]}](${m.groupValues[1]})" }
            }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
