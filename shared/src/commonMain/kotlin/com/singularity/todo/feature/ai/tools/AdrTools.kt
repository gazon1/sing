package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.platform.HostEnvironmentPort
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Clock

/** Directory name, relative to whichever root [AdrStorage] settles on. */
private const val DECISIONS_DIR_NAME = "docs/decisions"

/**
 * ADR (Architecture Decision Record) storage helper.
 * Reads/writes Markdown files in the `docs/decisions/` directory.
 *
 * File naming: `docs/decisions/{YYYY-MM-DD}-{slug}.md`
 * Frontmatter: YAML with `title`, `date`, `tags`.
 *
 * Uses `kotlin.io.path` stdlib (cross-platform) rather than `java.io.File`.
 *
 * ## Why the environment arrives as a constructor parameter
 *
 * This used to be an `object` that asked `System.getProperty("user.dir")` and
 * `System.getProperty("user.home")` for itself. Neither property exists off the JVM,
 * and an object cannot be handed a fake — so a test could not point the ADR tools at
 * a temporary directory, and on Android the lookup was two undocumented properties
 * away from a null dereference. It is a class now, and [HostEnvironmentPort] says
 * what it needs without naming a platform.
 */
class AdrStorage(
    private val host: HostEnvironmentPort,
    private val clock: Clock,
    private val timeZone: TimeZoneProvider,
) {

    /**
     * The absolute path to the decisions directory: the project root when
     * `docs/decisions/` is there, otherwise a per-user copy.
     *
     * Resolved once, at construction. The answer cannot change under a running app —
     * a directory that appears after the tools are built would be a different process
     * — and resolving per call would re-stat the filesystem on every read.
     */
    private val decisionsDir: String = resolveDecisionsDir()

    private fun resolveDecisionsDir(): String {
        val projectAdrDir = Path("${host.workingDirectory()}/$DECISIONS_DIR_NAME")
        return if (projectAdrDir.exists() && projectAdrDir.isDirectory()) {
            projectAdrDir.toString()
        } else {
            Path(host.homeDirectory(), ".singularity-todo", DECISIONS_DIR_NAME).toString()
        }
    }

    /**
     * The resolved decisions directory.
     *
     * Public because every tool's answer carries a path, and a caller — or a test —
     * that is told "written to X" should be able to check X rather than trust it.
     */
    fun decisionsDir(): String = decisionsDir

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
        val dirPath = Path(decisionsDir)
        if (!dirPath.isDirectory()) return emptyList()
        // Recursive: walks all subdirectories so that future per-entry splits
        // (T2) are found without any further changes to this function.
        return dirPath.listDirectoryEntries()
            .filter { it.isDirectory() || it.fileName.toString().endsWith(".md") }
            .flatMap { entry ->
                if (entry.isDirectory()) {
                    entry.listDirectoryEntries("*.md")
                } else {
                    listOf(entry)
                }
            }
            .mapNotNull { filePath ->
                val slug = filePath.fileName.toString().removeSuffix(".md")
                readAdr(slug)?.let { AdrSummary(it.slug, it.title, it.date, it.tags) }
            }
            .sortedByDescending { it.date }
    }

    fun writeAdr(slug: String, title: String, tags: List<String>, body: String): String {
        val dirPath = Path(decisionsDir)
        dirPath.createDirectories()
        // The date in a new ADR's frontmatter is the day it was written, in the
        // writer's own zone — a property of the moment, not of the tool. It is now
        // read from an injected clock and zone so a test can assert the stamp
        // instead of accepting whatever day it happens to run on (#91).
        val date = todayAt(clock, timeZone.current()).toString()
        val tagsStr = tags.joinToString(", ", "[", "]") { "\"$it\"" }
        val frontmatter = "---\ntitle: \"$title\"\ndate: $date\nstatus: open\ntags: $tagsStr\n---\n\n"
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

class ListAdrsTool(private val storage: AdrStorage) :
    SimpleTool<ListAdrsInput>(
        TypeToken.of(ListAdrsInput::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: ListAdrsInput): String {
        val adrs = storage.listAdrs().take(args.limit)
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

class ReadAdrTool(private val storage: AdrStorage) :
    SimpleTool<ReadAdrInput>(
        TypeToken.of(ReadAdrInput::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: ReadAdrInput): String {
        val adr = storage.readAdr(args.slug)
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

class WriteAdrTool(private val storage: AdrStorage) :
    SimpleTool<WriteAdrInput>(
        TypeToken.of(WriteAdrInput::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: WriteAdrInput): String {
        val path = storage.writeAdr(args.slug, args.title, args.tags, args.body)
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
