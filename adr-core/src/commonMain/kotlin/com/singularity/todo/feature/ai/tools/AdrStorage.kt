package com.singularity.todo.feature.ai.tools

import com.singularity.todo.core.platform.HostEnvironmentPort
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Clock

/** Relative corpus root. The value itself lives in config/docs/adr-corpus.json (#520);
 *  this is only the fallback for checkouts with no config/ directory. */
private val DECISIONS_DIR_NAME = AdrCorpusConfig.DEFAULT.root

/** Slug format: lowercase letters, digits, hyphens and underscores only. Guards against path traversal. */
private val VALID_SLUG_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")

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
     * The search over the project's knowledge.
     *
     * Built here so that it sees the *resolved* decisions directory and the *resolved*
     * corpus rules — the same ones `listAdrs` and the status gate use. A search with its
     * own idea of where the decisions are would answer a different question than the
     * one the corpus describes.
     *
     * The engine is exposed rather than wrapped: a wrapper would be a second name for
     * the same two answers, and the storage has no reason to own the search.
     */
    val search: CorpusSearch = CorpusSearch(
        projectRoot = projectRoot,
        decisionsDir = decisionsDir,
        corpus = corpus,
        readAdr = { path -> readAdrAt(Path(path)) },
    )

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
    data class AdrFrontmatter(val title: String, val date: String, val tags: List<String>, val status: String = "")

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

    /**
     * The decisions — and only the decisions.
     *
     * `corpus.discover` walks the whole corpus, which includes the `deferred/`
     * backlog: those files live under `docs/decisions/`, and the walk does not know
     * that a backlog entry is not a decision. The rule that says so is
     * [AdrCorpusConfig.isDecision], and it was declared but never applied here — so
     * `list_adrs` answered with the backlog in it, and the two tools that exist to
     * tell them apart (`list_adrs` and `list_open_deferred`) overlapped.
     *
     * A rule that is written down and not applied is the same defect as a rule that is
     * written down twice.
     */
    fun listAdrs(): List<AdrSummary> =
        corpus.discover(decisionsDir, corpus.discoverMaxDepth)
            .filter { corpus.isDecision(it) }
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
            val known = corpus.discover(decisionsDir, corpus.discoverMaxDepth)
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
            val kept = lines.subList(1, end).filterNot { line ->
                line.startsWith("status:") || line.startsWith("superseded-by:") ||
                    line.startsWith("superseded_by:") || line.startsWith("review-by:") ||
                    line.startsWith("review_by:")
            }
            addAll(kept)
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
    data class UpdateAdrOutput(val slug: String, val status: String, val supersededBy: String?, val path: String)

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
        // The backlog is read from *its own* directory, not filtered out of the
        // decisions. It used to be the other way round: `listAdrs()` walked the whole
        // corpus — including `deferred/` — and this method filtered that list down to
        // the backlog. That worked only because the decisions listing included the
        // backlog in the first place, and it had already gone wrong once: when
        // discovery became recursive, the filter started admitting every `open`
        // decision in the corpus, and `adr list-open` reported 41 entries of which
        // almost none were backlog (#520).
        //
        // The two listings now read the two parts of the corpus, and the rule that
        // says a backlog entry is not a decision is applied where the decisions are
        // listed.
        return corpus.deferred(decisionsDir)
            .mapNotNull { slug -> readAdrAt(Path(decisionsDir, corpus.deferredDir, "$slug.md")) }
            .filter { corpus.normalizeStatus(it.status) in OPEN_DEFERRED_STATES }
            .map { AdrSummary(it.slug, it.title, it.date, it.tags, it.status) }
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
