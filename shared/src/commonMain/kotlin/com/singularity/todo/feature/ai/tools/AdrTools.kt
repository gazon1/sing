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

/** Legacy inline status line: `**Status: OPEN — tracked issue #123**`. */
private val INLINE_STATUS = Regex("""^\*\*\s*Status[^:*]*:?\*?\*?:?\s*(.+?)\s*$""", RegexOption.MULTILINE)

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
     * The nearest ancestor of the working directory that looks like a project checkout,
     * or `null` if this process was not launched from one.
     *
     * **Walking up, not checking one directory** is the whole point (#513). The CLI used
     * to look only at `user.dir`, and under Gradle that is the daemon's directory — so
     * `adr new` wrote to `~/.singularity-todo/docs/decisions/`, where the ADR is real but
     * outside git, and the next clone of the project has never heard of it.
     *
     * A checkout is recognised by either marker, because they fail independently: a
     * repository can have decisions before it has corpus rules, and vice versa.
     */
    private val projectRoot: String? = ancestorsFromCwd()
        .plus(host.homeDirectory())
        .firstOrNull {
            Path("$it/$DECISIONS_DIR_NAME").isDirectory() ||
                Path("$it/$CORPUS_CONFIG_PATH").exists()
        }

    /**
     * The absolute path to the decisions directory: the checkout's when there is one,
     * otherwise a per-user copy.
     *
     * Resolved once, at construction. The answer cannot change under a running app —
     * a directory that appears after the tools are built would be a different process
     * — and resolving per call would re-stat the filesystem on every read.
     */
    private val decisionsDir: String = projectRoot
        ?.let { Path("$it/$DECISIONS_DIR_NAME").toString() }
        ?: Path(host.homeDirectory(), ".singularity-todo", DECISIONS_DIR_NAME).toString()

    /**
     * Corpus rules, read from `config/docs/adr-corpus.json` (#520).
     *
     * Resolved from the same [projectRoot] as [decisionsDir] so a checkout always gets
     * its own rules and the `~/.singularity-todo` fallback gets the mirrored defaults.
     */
    private val corpus: AdrCorpusConfig = AdrCorpusConfig.loadOrDefault(projectRoot)

    /**
     * The working directory and every ancestor up to the filesystem root.
     *
     * The walk has to *terminate at the root*. `"".substringBeforeLast('/', "")` is `""`
     * — feeding it back into the sequence produces an infinite generator, which does not
     * spin in a loop you can see: it burns a core in `Files.exists` forever and the test
     * run simply never finishes. It cost a real day before anyone looked at a stack.
     */
    private fun ancestorsFromCwd(): Sequence<String> =
        generateSequence(Path(host.workingDirectory()).toAbsolutePath().toString()) { current ->
            current.substringBeforeLast('/', "").takeIf { it.isNotEmpty() && it != current }
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
            status = frontmatter?.status?.takeIf { it.isNotBlank() }
                ?: inlineStatusIn(content),
        )
    }.getOrNull()

    fun listAdrs(): List<AdrSummary> =
        corpus.discover(decisionsDir, MAX_DEPTH)
            .mapNotNull { readAdrAt(Path(it)) }
            .map { AdrSummary(it.slug, it.title, it.date, it.tags, it.status) }
            .sortedByDescending { it.date }

    /**
     * Write a brand-new decision.
     *
     * The frontmatter it emits is assembled from [corpus]'s declared contract rather
     * than from a literal in this function (#518). Three writers used to describe the
     * same format — this one, `normalize-adr-frontmatter.py`'s key allowlist, and
     * `resolve_stale_adr_status.py`'s regex — and they had already drifted far enough
     * that the normalizer rejected output produced by this very tool.
     *
     * Two things are therefore read from `config/docs/adr-corpus.json`:
     *
     *  - **the birth status.** A new decision is `open`, and stays `open` until
     *    someone records a resolution with [updateAdr]. That was hardcoded here and
     *    stated nowhere, so it was true by accident rather than by decision.
     *  - **the key order.** `title, date, status, tags` is what the digest and the
     *    listings read as "the identifying keys"; it is data, not code.
     *
     * Every emitted key is checked against the allowlist before it is written, so a
     * future field cannot be added here and silently break the normalizer — which is
     * precisely how `review-by` did it in #527.
     */
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
        val values = mapOf(
            "title" to "\"$title\"",
            "date" to date,
            "status" to corpus.newAdrStatus,
            "tags" to tagsStr,
        )
        // Refuse before writing, not after. A key outside the allowlist would be
        // rejected by `normalize-adr-frontmatter.py` as `unknown frontmatter key`, and
        // the failure would surface on some later run against a corpus the writer no
        // longer remembers touching.
        val order = corpus.newAdrKeyOrder
        val unknown = (order + values.keys).filterNot { corpus.isKnownFrontmatterKey(it) }
        check(unknown.isEmpty()) {
            "refusing to write ADR '$slug': frontmatter key(s) ${unknown.distinct()} are not in " +
                "${CORPUS_CONFIG_PATH} -> frontmatter.knownKeys, so the normalizer would reject " +
                "this file as structurally broken"
        }
        val orderedKeys = order + values.keys.filterNot { it in order }
        val frontmatter = buildString {
            append("---\n")
            orderedKeys.forEach { key -> append(key).append(": ").append(values.getValue(key)).append('\n') }
            append("---\n\n")
        }
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
        /**
         * ISO date on which this decision is due to be revisited (#527).
         *
         * Optional, and that is the design. The 30-day staleness rule was a number
         * nobody chose — no ADR ever said "we will revisit this in 30 days" — so it
         * stays as the *fallback* for decisions that express no intent, and an
         * explicit date overrides it. A decision that says when it will be looked at
         * again is a decision; one that is merely old is not.
         */
        reviewBy: String? = null,
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
        // An unparseable date is refused rather than written: a `review-by` the gate
        // cannot compare against a date is a promise nobody can be held to.
        val reviewed = reviewBy?.trim()?.takeIf { it.isNotEmpty() }?.also { candidate ->
            val parsed = runCatching { kotlinx.datetime.LocalDate.parse(candidate) }
            require(parsed.isSuccess) {
                "reviewBy '$candidate' is not an ISO date (YYYY-MM-DD)."
            }
        }

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
        // Same guard as [writeAdr], and it exists because this line was the bug:
        // `review-by` was added here in #527 without being added to the normalizer's
        // allowlist, so `normalize-adr-frontmatter.py` rejected the repo's own output
        // as `unknown frontmatter key`. All three keys below are known today; the
        // check is what keeps the fourth one from repeating it.
        val writtenKeys = buildList {
            add("status")
            if (resolved != null) add("superseded-by")
            if (reviewed != null) add("review-by")
        }
        require(writtenKeys.all { corpus.isKnownFrontmatterKey(it) }) {
            "refusing to update '$slug': frontmatter key(s) " +
                "${writtenKeys.filterNot { corpus.isKnownFrontmatterKey(it) }} are not in " +
                "${CORPUS_CONFIG_PATH} -> frontmatter.knownKeys, so the normalizer would " +
                "reject this file as structurally broken"
        }
        val rebuilt = buildList {
            add("status: $status")
            if (resolved != null) add("superseded-by: $resolved")
            if (reviewed != null) add("review-by: $reviewed")
            addAll(lines.subList(1, end).filterNot { line ->
                line.startsWith("status:") || line.startsWith("superseded-by:") ||
                    line.startsWith("superseded_by:") || line.startsWith("review-by:") ||
                    line.startsWith("review_by:")
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

    /**
     * One row of `adr list`.
     *
     * [status] is here rather than left to a second read because every consumer that
     * filtered on it — `list-open`, and `adr stale` (#521) — was re-opening each file
     * to find a value the summary already had. Kept last so the positional constructor
     * call sites read in field order.
     */
    @Serializable
    data class AdrSummary(
        val slug: String,
        val title: String,
        val date: String,
        val tags: List<String>,
        val status: String = "",
    )

    @Serializable
    data class AdrFile(
        val slug: String,
        val title: String,
        val date: String,
        val tags: List<String>,
        val body: String,
        val path: String,
        /** Raw frontmatter value, unfolded and un-normalised — see [AdrCorpusConfig.normalizeStatus]. */
        val status: String = "",
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
    data class UpdateAdrOutput(
        val slug: String,
        val status: String,
        val supersededBy: String?,
        val path: String,
    )

    @Serializable
    data class ListOpenDeferredOutput(val entries: List<AdrSummary>)

    /**
     * Lists deferred backlog entries with OPEN or PARTIAL status.
     *
     * Excludes resolved/closed entries.
     *
     * Reads [AdrSummary.status] instead of re-opening every file. The old version
     * called `readAdr(slug)` per entry, and since #520 `listAdrs` can return paths
     * below `deferred/` — so each of those lookups resolved a *root* path that does
     * not exist, returned null, and left the status blank. The filter then silently
     * passed nothing through, which is why the backlog looked empty to every caller.
     */
    fun listOpenDeferred(): List<AdrSummary> {
        // Filter by *location* as well as status. The name says deferred, and until
        // #520 it happened to agree because listAdrs() only walked the corpus root —
        // every top-level ADR was invisible to it, so the status test was applied to
        // the backlog alone. Once discovery became recursive the same filter started
        // admitting every `open` decision in the corpus, and `adr list-open` reported
        // 41 entries of which almost none were backlog.
        val backlog = corpus.deferred(decisionsDir).toSet()
        return listAdrs().filter { summary ->
            summary.slug in backlog &&
                corpus.normalizeStatus(summary.status) in OPEN_DEFERRED_STATES
        }
    }

    /**
     * `**Status: X**` inside the body, for entries written before `status:` was a
     * frontmatter field.
     *
     * Takes text, not a path: the caller already has the file open, and asking for a
     * path here meant rebuilding one from a bare slug — which resolves to the corpus
     * root and silently finds nothing for every file under `deferred/`.
     */
    private fun inlineStatusIn(content: String): String {
        val bodyStart = content.indexOf("---", 3)
        val body = if (bodyStart != -1) content.substring(bodyStart + 3) else content
        return INLINE_STATUS
            .find(body)
            ?.groupValues?.get(1)
            ?.trim()
            ?.split(" ")
            ?.firstOrNull()
            .orEmpty()
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

        /**
         * Says exactly what it does, which is not what it used to say (#511).
         *
         * This string reaches the tool *schema*, and an agent reads the schema before
         * it acts. "Create or update" produced a reliable failure: the agent called
         * `write_adr` on an existing slug, got `ADR already exists`, and worked
         * around it by inventing `slug-v2` — leaving an orphaned predecessor with no
         * `supersedes` edge, which is precisely the state the update path exists to
         * prevent.
         *
         * The second paragraph is the part that changes behaviour: it names
         * [UpdateAdrTool.NAME] and says what superseding means.
         */
        const val DESCRIPTION =
            "Create a NEW Architecture Decision Record (ADR) as a Markdown file. Fails if " +
                "the slug already exists — a decision is never overwritten. " +
                "To close or revise an existing decision use update_adr, which changes its " +
                "status and records the supersedes/superseded-by link. To say something new " +
                "that replaces an old decision, write a new ADR naming the old one in its " +
                "`supersedes:` frontmatter field, then call update_adr on the old one with " +
                "status=superseded — do NOT invent a '-v2' slug, it leaves the predecessor " +
                "unlinked."
    }
}

@Serializable
data class UpdateAdrInput(
    val slug: String,
    val status: String,
    val supersededBy: String? = null,
)

/** The `update_adr` MCP tool — the write-side half of [AdrStorage.updateAdr] (#511). */
class UpdateAdrTool(private val storage: AdrStorage) :
    SimpleTool<UpdateAdrInput>(
        TypeToken.of(UpdateAdrInput::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: UpdateAdrInput): String {
        val path = storage.updateAdr(args.slug, args.status, args.supersededBy)
        return Json.encodeToString(
            AdrStorage.UpdateAdrOutput.serializer(),
            AdrStorage.UpdateAdrOutput(args.slug, args.status, args.supersededBy, path),
        )
    }

    companion object {
        const val NAME = "update_adr"

        /**
         * The contract, stated where an agent will read it before choosing.
         *
         * The body is never touched — that is the property that makes this safe to
         * call on a decision somebody else wrote.
         */
        const val DESCRIPTION =
            "Change an existing ADR's status frontmatter; the body is never modified. " +
                "status must be one of: accepted, deferred, superseded, open. " +
                "status=superseded additionally requires supersededBy, naming an existing " +
                "slug (a markdown link is accepted). Use this to close a decision once " +
                "it is settled, and to mark one as replaced after writing its successor — " +
                "the replacement is a separate write_adr call, not a new '-v2' slug of " +
                "this one."
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
