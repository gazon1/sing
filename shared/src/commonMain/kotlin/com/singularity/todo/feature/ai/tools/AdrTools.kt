package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.lang.System.getProperty
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * ADR (Architecture Decision Record) storage helper.
 * Reads/writes Markdown files in the `docs/decisions/` directory.
 *
 * File naming: `docs/decisions/{YYYY-MM-DD}-{slug}.md`
 * Frontmatter: YAML with `title`, `date`, `tags`.
 *
 * Uses `kotlin.io.path` stdlib (cross-platform) rather than `java.io.File`.
 */
object AdrStorage {

    private const val DECISIONS_DIR_NAME = "docs/decisions"

    /**
     * Returns the absolute path to the decisions directory.
     * Uses `user.dir` (project root) when `docs/decisions/` exists there,
     * otherwise falls back to `~/.singularity-todo/docs/decisions/`.
     */
    fun decisionsDir(): String {
        val workingDir = getProperty("user.dir")
        val projectAdrDir = Path("$workingDir/$DECISIONS_DIR_NAME")
        return if (projectAdrDir.exists() && projectAdrDir.isDirectory()) {
            projectAdrDir.toString()
        } else {
            Path(getProperty("user.home"), ".singularity-todo", DECISIONS_DIR_NAME).toString()
        }
    }

    fun filePath(slug: String): String = "${decisionsDir()}/$slug.md"

    // ─── Frontmatter parsing ───────────────────────────────────────────────────

    data class AdrFrontmatter(val title: String, val date: String, val tags: List<String>)

    fun parseFrontmatter(content: String): AdrFrontmatter? {
        val start = content.indexOf("---")
        val end = content.indexOf("---", start + 3)
        if (start == -1 || end == -1) return null
        val yaml = content.substring(start + 3, end).trim()
        var title = ""
        var date = ""
        val tags = mutableListOf<String>()
        for (line in yaml.lines()) {
            when {
                line.startsWith("title:") -> title = line.removePrefix("title:").trim().trim('"')

                line.startsWith("date:") -> date = line.removePrefix("date:").trim()

                line.startsWith("tags:") -> {
                    val rest = line.removePrefix("tags:").trim().removeSurrounding("[", "]")
                    if (rest.isNotEmpty()) {
                        tags.addAll(rest.split(",").map { it.trim().trim('"') })
                    }
                }
            }
        }
        if (title.isEmpty()) return null
        return AdrFrontmatter(title, date, tags)
    }

    fun readAdr(slug: String): AdrFile? {
        val path = Path(filePath(slug))
        return runCatching {
            if (!path.exists()) return@runCatching null
            val content = path.readText(Charsets.UTF_8)
            val frontmatter = parseFrontmatter(content)
            val bodyStart = content.indexOf("---", 3)
            val body = if (bodyStart != -1) content.substring(bodyStart + 3).trim() else content
            AdrFile(
                slug = slug,
                title = frontmatter?.title ?: "(no title)",
                date = frontmatter?.date ?: "",
                tags = frontmatter?.tags ?: emptyList(),
                body = body,
                path = path.toString(),
            )
        }.getOrNull()
    }

    fun listAdrs(): List<AdrSummary> {
        val dir = File(decisionsDir())
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.extension == "md" }
            ?.mapNotNull { file ->
                val slug = file.nameWithoutExtension
                readAdr(slug)?.let { AdrSummary(it.slug, it.title, it.date, it.tags) }
            }
            ?.sortedByDescending { it.date }
            ?: emptyList()
    }

    fun writeAdr(slug: String, title: String, tags: List<String>, body: String): String {
        val dir = File(decisionsDir())
        dir.mkdirs()
        val date = java.time.LocalDate.now().toString()
        val tagsStr = tags.joinToString(", ", "[", "]") { "\"$it\"" }
        val frontmatter = "---\ntitle: \"$title\"\ndate: $date\ntags: $tagsStr\n---\n\n"
        val path = Path(filePath(slug))
        path.writeText(frontmatter + body, Charsets.UTF_8)
        return path.toString()
    }

    // ─── Data classes ─────────────────────────────────────────────────────────

    @Serializable
    data class AdrSummary(val slug: String, val title: String, val date: String, val tags: List<String>)

    @Serializable
    data class AdrFile(
        val slug: String,
        val title: String,
        val date: String,
        val tags: List<String>,
        val body: String,
        val path: String,
    )

    @Serializable
    data class ListAdrsOutput(val adrs: List<AdrSummary>)

    @Serializable
    data class ReadAdrOutput(
        val slug: String,
        val title: String,
        val date: String,
        val tags: List<String>,
        val body: String,
    )

    @Serializable
    data class WriteAdrOutput(val slug: String, val path: String, val title: String)
}

// ─── Tools ─────────────────────────────────────────────────────────────────────

@Serializable
data class ListAdrsInput(val limit: Int = 50)

class ListAdrsTool :
    SimpleTool<ListAdrsInput>(
        TypeToken.of(ListAdrsInput::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: ListAdrsInput): String {
        val adrs = AdrStorage.listAdrs().take(args.limit)
        return Json.encodeToString(
            AdrStorage.ListAdrsOutput.serializer(),
            AdrStorage.ListAdrsOutput(adrs),
        )
    }

    companion object {
        const val NAME = "list_adrs"
        const val DESCRIPTION = "List all Architecture Decision Records sorted by date (newest first)."
    }
}

@Serializable
data class ReadAdrInput(val slug: String)

class ReadAdrTool :
    SimpleTool<ReadAdrInput>(
        TypeToken.of(ReadAdrInput::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: ReadAdrInput): String {
        val adr = AdrStorage.readAdr(args.slug)
            ?: return Json.encodeToString(
                AdrStorage.ReadAdrOutput.serializer(),
                AdrStorage.ReadAdrOutput(args.slug, "(not found)", "", emptyList(), ""),
            )
        return Json.encodeToString(
            AdrStorage.ReadAdrOutput.serializer(),
            AdrStorage.ReadAdrOutput(adr.slug, adr.title, adr.date, adr.tags, adr.body),
        )
    }

    companion object {
        const val NAME = "read_adr"
        const val DESCRIPTION = "Read a specific ADR by its slug (e.g. 2026-09-05-koin-suspend-bridge)."
    }
}

@Serializable
data class WriteAdrInput(val slug: String, val title: String, val tags: List<String> = emptyList(), val body: String)

class WriteAdrTool :
    SimpleTool<WriteAdrInput>(
        TypeToken.of(WriteAdrInput::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: WriteAdrInput): String {
        val path = AdrStorage.writeAdr(args.slug, args.title, args.tags, args.body)
        return Json.encodeToString(
            AdrStorage.WriteAdrOutput.serializer(),
            AdrStorage.WriteAdrOutput(args.slug, path, args.title),
        )
    }

    companion object {
        const val NAME = "write_adr"
        const val DESCRIPTION = "Create or update an Architecture Decision Record (ADR) as a Markdown file."
    }
}
