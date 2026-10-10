package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.platform.HostEnvironmentPort
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Clock

/** Relative corpus root. The value itself lives in config/docs/adr-corpus.json (#520);
 *  this is only the fallback for checkouts with no config/ directory. */
private val DECISIONS_DIR_NAME = AdrCorpusConfig.DEFAULT.root

/** Slug format: lowercase letters, digits, hyphens and underscores only. Guards against path traversal. */
private val VALID_SLUG_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")

private const val MAX_DEPTH = 3 // Guards against unbounded filesystem walks

/**
 * Backlog states that mean "still live". Normalized, so `OPEN` and `open` are the
 * same answer — before, this list carried five spellings and the policy gate
 * accepted one, so the tool and the gate disagreed about what "open" means.
 */
private val OPEN_DEFERRED_STATES = setOf("open", "partial", "partially")

/** Quote characters stripped from a `superseded-by` value before it is resolved. */
private const val CHAR_QUOTE = '\u0022'
private const val CHAR_APOSTROPHE = '\u0027'

/**
 * `[2026-10-05-thing](2026-10-05-thing.md)` — how an ADR is cited in prose, and so
 * how it arrives here when a human or a model pastes the reference it found.
 *
 * The *label* is captured and discarded on purpose: it is display text that can
 * differ from the target (`[the new ADR](2026-10-05-thing.md)`), and resolving
 * against it would fail on exactly the references that read best.
 */
private val MD_LINK = Regex("""\[[^\]]*\]\(([^)]+)\)""")

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

    /**
     * Corpus rules, read from `config/docs/adr-corpus.json` (#520).
     *
     * Resolved next to [decisionsDir] so a checkout always gets its own rules and
     * the `~/.singularity-todo` fallback gets the mirrored defaults.
     */
    private val corpus: AdrCorpusConfig = AdrCorpusConfig.loadOrDefault(repoRoot())

    /**
     * Nearest ancestor of the working directory holding `config/docs/adr-corpus.json`.
     *
     * The walk has to *terminate at the filesystem root*. `"".substringBeforeLast('/', "")`
     * is `""` — feeding it back into the sequence produces an infinite generator,
     * which does not spin in a loop you can see: it burns a core in `Files.exists`
     * forever and the test run simply never finishes.
     */
    private fun repoRoot(): String? =
        generateSequence(Path(host.workingDirectory()).toAbsolutePath().toString()) { current ->
            current.substringBeforeLast('/', "").takeIf { it.isNotEmpty() && it != current }
        }
            .plus(host.homeDirectory())
            .firstOrNull { Path("$it/config/docs/adr-corpus.json").exists() }

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

    /**
     * Returns true if [slug] is a valid ADR filename slug.
     * Guards against path traversal (`../`), absolute paths, and special characters.
     */
    fun isValidSlug(slug: String): Boolean =
        slug.isNotBlank() && VALID_SLUG_REGEX.matches(slug) && slug != "." && slug != ".."

    /**
     * Throws [IllegalArgumentException] if [slug] is not valid.
     * Use in every public method that accepts a slug before any filesystem operation.
     */
    private fun requireValidSlug(slug: String) {
        require(isValidSlug(slug)) {
            "Invalid slug: '$slug'. Use only lowercase letters, digits, hyphens and underscores."
        }
    }

    // ─── Frontmatter parsing ───────────────────────────────────────────────────

    /**
     * `status` used to be absent, which is why `readAdrStatus` re-scanned the body
     * for an inline `**Status: X**` line: the parser could not return it. The body
     * scan then also matched prose, and the vocabulary check below had to accept
     * five spellings. Carrying the field here removes both.
     */
    data class AdrFrontmatter(
        val title: String,
        val date: String,
        val tags: List<String>,
        val status: String = "",
    )

    fun parseFrontmatter(content: String): AdrFrontmatter? {
        val start = content.indexOf("---")
        val end = content.indexOf("---", start + 3)
        if (start == -1 || end == -1) return null
        val yaml = content.substring(start + 3, end).trim()
        var title = ""
        var date = ""
        var status = ""
        val tags = mutableListOf<String>()
        for (line in yaml.lines()) {
            when {
                line.startsWith("title:") -> title = line.removePrefix("title:").trim().trim('"')

                line.startsWith("date:") -> date = line.removePrefix("date:").trim()

                line.startsWith("status:") -> status = line.removePrefix("status:").trim().trim('"')

                line.startsWith("tags:") -> {
                    val rest = line.removePrefix("tags:").trim().removeSurrounding("[", "]")
                    if (rest.isNotEmpty()) {
                        tags.addAll(rest.split(",").map { it.trim().trim('"') })
                    }
                }
            }
        }
        if (title.isEmpty()) return null
        return AdrFrontmatter(title, date, tags, status)
    }

    fun readAdr(slug: String): AdrFile? {
        requireValidSlug(slug)
        return readAdrAt(Path(filePath(slug)))
    }

    /**
     * Reads the ADR at an already-resolved [path].
     *
     * Split out of [readAdr] because [corpus.discover] hands back real paths, and
     * rebuilding one from a bare slug silently drops every file that is not at the
     * top level — which is the entire `deferred/` backlog.
     */
    private fun readAdrAt(path: Path): AdrFile? = runCatching {
        if (!path.exists()) return@runCatching null
        if (path.isDirectory()) return@runCatching null
        val slug = path.fileName.toString().removeSuffix(".md")
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

    fun listAdrs(): List<AdrSummary> =
        corpus.discover(decisionsDir, MAX_DEPTH)
            .mapNotNull { readAdrAt(Path(it)) }
            .map { AdrSummary(it.slug, it.title, it.date, it.tags) }
            .sortedByDescending { it.date }

    fun writeAdr(slug: String, title: String, tags: List<String>, body: String): String {
        requireValidSlug(slug)
        val dirPath = Path(decisionsDir)
        dirPath.createDirectories()
        val path = Path(filePath(slug))
        // Fail loudly on overwrite — the MCP tool is the system of record for ADRs,
        // and silent clobbering would lose the decision trail.
        if (path.exists()) {
            error("ADR already exists: '$slug'. Use update instead of overwriting.")
        }
        // The date in a new ADR's frontmatter is the day it was written, in the
        // writer's own zone — a property of the moment, not of the tool. It is now
        // read from an injected clock and zone so a test can assert the stamp
        // instead of accepting whatever day it happens to run on (#91).
        val date = todayAt(clock, timeZone.current()).toString()
        val tagsStr = tags.joinToString(", ", "[", "]") { "\"$it\"" }
        val frontmatter = "---\ntitle: \"$title\"\ndate: $date\nstatus: open\ntags: $tagsStr\n---\n\n"
        path.writeText(frontmatter + body, Charsets.UTF_8)
        return path.toString()
    }

    /**
     * Reduce whatever the caller passed to a bare slug, or `null`.
     *
     * Four shapes are accepted because four shapes arrive in practice: a slug, a
     * slug with `.md`, a markdown link pasted out of a body, and any of those
     * quoted. `trim('[', ']', '"', '\'')` alone is not enough — it stops at the
     * `)` of `(slug.md)` and hands the resolver half a link.
     */
    private fun slugFromSupersededBy(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val target = MD_LINK.matchEntire(value)?.groupValues?.get(1) ?: value
        return target
            .trim('[', ']', CHAR_QUOTE, CHAR_APOSTROPHE)
            .substringBefore('#')
            .removeSuffix(".md")
            .trim()
            .ifEmpty { null }
    }

    /**
     * Change an ADR's status frontmatter. The body is never touched.
     *
     * Why a separate operation rather than a mode of [writeAdr]: the body of a
     * decision is immutable by design — that is what [writeAdr]'s fail-loudly on
     * overwrite protects. But the *frontmatter* describes the lifecycle of the
     * decision and is meant to change: `open` → `accepted`, and later
     * `open` → `superseded` when a newer ADR replaces it. Mixing the two in one
     * call would give up the guarantee above.
     *
     * The four file-local invariants of `docs/doc-maintenance.md` are enforced
     * here rather than in the gate, because the writer is the only place that
     * cannot be bypassed by someone editing the file by hand (#523):
     *
     *  - `status` is one of the documented vocabulary values
     *  - `status: superseded` requires `supersededBy`
     *  - `supersededBy` names a slug that actually exists
     *  - `status: archived` is refused — `archived` belongs to `archive/`
     *
     * The gate keeps only what a writer cannot know: how old a file is, where it
     * lives, and what happened to it outside any tool's reach (a hand edit, a
     * merge, a deletion).
     */
    fun updateAdr(
        slug: String,
        status: String,
        supersededBy: String? = null,
    ): String {
        requireValidSlug(slug)
        val path = Path(filePath(slug))
        if (!path.exists()) {
            error("No ADR at '$slug'. Creating it is writeAdr's job; this only changes an existing one.")
        }
        require(status in corpus.statuses) {
            "Invalid status '$status'. Allowed: ${corpus.statuses.sorted().joinToString(", ")}."
        }
        require(status != corpus.archivedStatus) {
            "status `${corpus.archivedStatus}` is reserved for docs/decisions/${corpus.archiveDir}/ " +
                "and cannot be set through this tool."
        }
        if (status == "superseded") {
            require(!supersededBy.isNullOrBlank()) {
                "status `superseded` requires supersededBy — a decision that says 'replaced' " +
                    "must name what replaced it."
            }
        }

        val original = path.readText(Charsets.UTF_8)
        if (parseFrontmatter(original) == null) {
            error("'$slug' has no parseable frontmatter block; refusing to edit it by guesswork.")
        }
        val lines = original.lines()

        // Resolve the replacement target against the whole corpus — decisions,
        // archive and backlog — because a superseded ADR may point at any of them.
        val resolved = slugFromSupersededBy(supersededBy)
        if (resolved != null) {
            val known = corpus.discover(decisionsDir, MAX_DEPTH)
                .map { it.substringAfterLast('/').removeSuffix(".md") }
                .toSet() + corpus.deferred(decisionsDir)
            require(resolved in known) {
                "supersededBy '$resolved' does not exist in ${corpus.root} or its ${corpus.deferredDir}/."
            }
        }

        // indexOfFirst hands the lambda the *element*, not the index, so the closing
        // fence is searched in the tail after the opening one and shifted back.
        // A missing closing fence yields -1, which `end > start` rejects.
        val start = lines.indexOfFirst { it.trim() == "---" }
        val closing = lines.drop(start + 1).indexOfFirst { it.trim() == "---" }
        val end = closing + start + 1
        require(start == 0 && closing >= 0 && end > start) {
            "'$slug' frontmatter block is malformed."
        }

        // Frontmatter only. `body` — everything after the closing fence — is rejoined
        // verbatim, without a `trimEnd()` and without a separating newline: the
        // first element already carries the blank line the file was written with,
        // and adding either one silently rewrites the decision's prose. The
        // "survives byte for byte" test exists because of exactly this.
        val body = lines.subList(end + 1, lines.size)
        val rebuilt = buildList {
            add("status: $status")
            if (resolved != null) add("superseded-by: $resolved")
            addAll(lines.subList(1, end).filterNot { line ->
                line.startsWith("status:") || line.startsWith("superseded-by:") ||
                    line.startsWith("superseded_by:")
            })
        }
        val out = buildString {
            append("---").append('\n')
            append(rebuilt.joinToString("\n")).append('\n')
            append("---").append('\n')
            append(body.joinToString("\n"))
        }
        path.writeText(out, Charsets.UTF_8)
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

    @Serializable
    data class ListOpenDeferredOutput(val entries: List<AdrSummary>)

    /**
     * Lists deferred backlog entries with OPEN or PARTIAL status.
     * Excludes resolved/closed entries.
     */
    fun listOpenDeferred(): List<AdrSummary> = listAdrs().filter { summary ->
        // Read the frontmatter to get status — listAdrs doesn't parse it.
        // We use the date-sorted list as input; status is checked per-entry.
        val file = readAdr(summary.slug)
        val status = file?.let { readAdrStatus(it.path) }
        corpus.normalizeStatus(status) in OPEN_DEFERRED_STATES
    }

    private fun readAdrStatus(filePath: String): String? {
        val content = runCatching { Path(filePath).readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val fm = parseFrontmatter(content) ?: return null
        // Frontmatter first — it is what writeAdr emits. The body scan is for legacy
        // entries that carry an inline `**Status: X**` and no frontmatter field.
        val bodyStart = content.indexOf("---", 3)
        val body = if (bodyStart != -1) content.substring(bodyStart + 3) else content
        val statusRe = Regex("""^\*\*\s*Status[^:*]*:?\*?\*?:?\s*(.+?)\s*$""", RegexOption.MULTILINE)
        val raw = fm.status.takeIf { it.isNotBlank() }
            ?: statusRe.find(body)?.groupValues?.get(1)?.trim()?.split(" ")?.firstOrNull()
        return corpus.normalizeStatus(raw)
    }
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

class ListOpenDeferredTool(private val storage: AdrStorage) :
    SimpleTool<Unit>(
        TypeToken.of(Unit::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: Unit): String {
        val entries = storage.listOpenDeferred()
        return Json.encodeToString(
            AdrStorage.ListOpenDeferredOutput.serializer(),
            AdrStorage.ListOpenDeferredOutput(entries),
        )
    }

    companion object {
        const val NAME = "list_open_deferred"
        const val DESCRIPTION = "List all deferred backlog entries with OPEN or PARTIAL status."
    }
}
