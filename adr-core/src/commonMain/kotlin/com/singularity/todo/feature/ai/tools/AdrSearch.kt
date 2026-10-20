package com.singularity.todo.feature.ai.tools

import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * The bounds a search applies to what the caller asked for.
 *
 * Public, because a caller that clamps a limit and a caller that wants to know the
 * ceiling have to agree on the number — and a test that asserts the clamp needs the
 * same number the implementation uses.
 */
object SearchLimits {
    /** How many hits a search returns when the caller does not say. */
    const val DEFAULT = 20

    /** The ceiling. A caller's number is clamped, never obeyed. */
    const val MAX = 50

    /** How much of a matching line a snippet carries. */
    const val SNIPPET_MAX = 200
}

/**
 * Which corpus a search looks in.
 *
 * The scopes are the places the project's own knowledge lives. They are declared once
 * here so that the tool, the CLI and the report all search the same trees — a search
 * that answered from a different set of directories than the one the corpus rules
 * describe would be a second corpus.
 */
enum class SearchScope(val id: String, val relativePath: String?) {
    /** The decision records and the deferred backlog. */
    DECISIONS("decisions", null),

    /** `docs/architecture/` — the hand-written architecture documents. */
    ARCHITECTURE("architecture", "docs/architecture"),

    /** `.agents/skills/` — the procedures. */
    SKILLS("skills", ".agents/skills"),

    /** `openspec/specs/` — the capability specifications. */
    SPECS("specs", "openspec/specs"),

    /** Every scope above. */
    ALL("all", null),
    ;

    companion object {
        fun from(raw: String?): SearchScope =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: ALL
    }
}

/** Whether the deferred backlog counts as a decision. */
enum class SearchKind(val id: String) {
    DECISION("decision"),
    DEFERRED("deferred"),
    ALL("all"),
    ;

    companion object {
        fun from(raw: String?): SearchKind =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: ALL
    }
}

/**
 * One search hit.
 *
 * The same shape for a decision and for a document, so that a caller can read one list
 * without branching on the kind. The fields that do not apply to a document — the date,
 * the status, the tags — are empty rather than absent, because a missing field and an
 * empty one mean different things to a consumer that formats them.
 */
@Serializable
data class AdrSearchHit(
    val slug: String,
    val title: String,
    val date: String = "",
    val tags: List<String> = emptyList(),
    val status: String = "",
    /** "decision", "deferred" or "document". */
    val kind: String = "",
    /** The scope the hit came from: "decisions", "architecture", "skills", "specs". */
    val scope: String = "",
    val snippet: String = "",
    /** Relative to the project root, so the path means the same thing on every machine. */
    val path: String = "",
)

/**
 * The answer to a search, and everything that has to be said alongside it.
 *
 * Three things that are routinely collapsed into one, and must not be:
 *
 * - `available = false` — the corpus was **not searched** (no checkout, unreadable
 *   directory). There is no answer.
 * - `available = true` with no hits — the corpus **was searched** and nothing matched.
 *   That is an answer, and a useful one.
 * - `error` — the call itself was wrong (a blank query). Nothing was searched.
 *
 * An empty list alone cannot say which of the three happened, and an agent that cannot
 * tell them apart either keeps searching a corpus that is not there, or reports "nothing
 * found" when the truth is "nothing was looked at".
 */
@Serializable
data class SearchEnvelope(
    val available: Boolean,
    val hits: List<AdrSearchHit> = emptyList(),
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * The search over the project's knowledge.
 *
 * ## One engine, two question shapes
 *
 * `searchDecisions` answers "what did we decide, and is it still open?" — it matches
 * title, tags and body, and returns the status of each decision. `searchDocuments`
 * answers "where is the rule about X?" — it matches the text of the documents, the
 * skills and the specifications. The two need different *answers*; they do not need
 * different *engines*, and a second engine would be a second set of rules to keep
 * honest.
 *
 * ## The corpus rules are not re-implemented
 *
 * The decisions come from [AdrCorpusConfig.discover] and [AdrCorpusConfig.deferred], so
 * the archive is skipped, the ignored files are skipped, and the backlog is not a
 * decision — exactly as the status gate and the CLI already treat them. A search that
 * disagreed with the gate about what a decision *is* would be worse than no search.
 *
 * ## Why the constructor is not public
 *
 * The engine is built by [AdrStorage], which is the only thing that knows where the
 * decisions live and which rules apply. A caller that could build its own would be able
 * to search a different corpus than the one the project describes — and two corpora are
 * exactly what this design exists to prevent.
 */
class CorpusSearch internal constructor(
    private val projectRoot: String?,
    private val decisionsDir: String,
    private val corpus: AdrCorpusConfig,
    private val readAdr: (String) -> AdrStorage.AdrFile?,
) {

    /**
     * The decisions and the deferred backlog.
     *
     * A blank query is a *caller* error, not an empty result: nothing was searched, so
     * the envelope says so instead of pretending the corpus is empty.
     */
    fun searchDecisions(query: String, limit: Int = DEFAULT_LIMIT, kind: SearchKind = SearchKind.ALL):
        SearchEnvelope {
        val needle = query.trim()
        if (needle.isEmpty()) {
            return SearchEnvelope(
                available = false,
                error = "query must not be blank",
            )
        }
        val dir = Path(decisionsDir)
        if (!dir.isDirectory()) {
            return SearchEnvelope(
                available = false,
                warnings = listOf("the decisions directory is not available: $decisionsDir"),
            )
        }

        val hits = mutableListOf<AdrSearchHit>()
        val warnings = mutableListOf<String>()

        if (kind != SearchKind.DEFERRED) {
            // `discover` walks the whole corpus, including the backlog. The rule that
            // says a backlog entry is not a decision is `isDecision`, and it has to be
            // applied here — otherwise the search would list the backlog twice, once
            // as a decision and once as a backlog entry.
            hits += corpus.discover(decisionsDir)
                .filter { corpus.isDecision(it) }
                .mapNotNull { path -> decisionHit(path, needle, warnings) }
        }

        if (kind != SearchKind.DECISION) {
            val deferredDir = Path(decisionsDir, corpus.deferredDir)
            if (deferredDir.isDirectory()) {
                hits += corpus.deferred(decisionsDir).mapNotNull { slug ->
                    val path = Path(deferredDir.toString(), "$slug.md").toString()
                    deferredHit(path, needle, warnings)
                }
            }
        }

        val ordered = hits
            .sortedWith(compareByDescending<AdrSearchHit> { it.date }.thenBy { it.slug })
            .take(clamp(limit))
        return SearchEnvelope(available = true, hits = ordered, warnings = warnings)
    }

    /**
     * The documents, the skills and the specifications.
     *
     * The decisions are included when the scope covers them, so that "search
     * everything" is one call rather than two.
     */
    fun searchDocuments(query: String, scope: SearchScope = SearchScope.ALL, limit: Int = DEFAULT_LIMIT):
        SearchEnvelope {
        val needle = query.trim()
        if (needle.isEmpty()) {
            return SearchEnvelope(available = false, error = "query must not be blank")
        }

        val hits = mutableListOf<AdrSearchHit>()
        val warnings = mutableListOf<String>()

        if (scope == SearchScope.ALL || scope == SearchScope.DECISIONS) {
            val decisions = searchDecisions(needle, limit, SearchKind.ALL)
            if (!decisions.available) {
                warnings += decisions.warnings
            }
            hits += decisions.hits
        }

        if (scope != SearchScope.DECISIONS) {
            if (projectRoot == null) {
                warnings += "the document scopes need a project checkout, and this process has none"
            } else {
                hits += SearchScope.entries
                    .filter { it.relativePath != null }
                    .filter { scope == SearchScope.ALL || it == scope }
                    .flatMap { searchScopeDirectory(it, needle, warnings) }
            }
        }

        val ordered = hits.sortedWith(compareBy<AdrSearchHit> { it.scope }.thenBy { it.path })
            .take(clamp(limit))
        return SearchEnvelope(
            available = true,
            hits = ordered,
            warnings = warnings,
        )
    }

    private fun searchScopeDirectory(scope: SearchScope, needle: String, warnings: MutableList<String>):
        List<AdrSearchHit> {
        val relative = scope.relativePath ?: return emptyList()
        val root = Path(projectRoot ?: return emptyList(), relative)
        if (!root.isDirectory()) {
            warnings += "the ${scope.id} scope is not available: $relative"
            return emptyList()
        }
        return markdownFiles(root).mapNotNull { file ->
            // An unreadable file is a warning, not a failure: one broken document must
            // not turn the whole scope into "nothing was searched".
            val text = runCatching { file.readText() }.getOrElse {
                warnings += "skipped an unreadable file under $relative"
                return@mapNotNull null
            }
            val match = matchIn(needle, titleOf(text), emptyList(), text) ?: return@mapNotNull null
            AdrSearchHit(
                slug = file.fileName.toString().removeSuffix(".md"),
                title = titleOf(text),
                kind = "document",
                scope = scope.id,
                snippet = match,
                path = relativeToRoot(file.toString()),
            )
        }
    }

    /**
     * One decision, if it matches.
     *
     * A decision that cannot be read is a warning rather than a failure: the rest of
     * the corpus was still searched, and the caller is told what was skipped.
     */
    private fun decisionHit(path: String, needle: String, warnings: MutableList<String>): AdrSearchHit? {
        val adr = readAdr(path) ?: run {
            warnings += "skipped an unreadable decision under $decisionsDir"
            return null
        }
        val match = matchIn(needle, adr.title, adr.tags, adr.body) ?: return null
        return AdrSearchHit(
            slug = adr.slug,
            title = adr.title,
            date = adr.date,
            tags = adr.tags,
            status = adr.status,
            kind = SearchKind.DECISION.id,
            scope = SearchScope.DECISIONS.id,
            snippet = match,
            path = relativeToRoot(path),
        )
    }

    /** One backlog entry, if it matches. */
    private fun deferredHit(path: String, needle: String, warnings: MutableList<String>): AdrSearchHit? {
        val adr = readAdr(path) ?: run {
            warnings += "skipped an unreadable backlog entry under ${corpus.deferredDir}"
            return null
        }
        val match = matchIn(needle, adr.title, adr.tags, adr.body) ?: return null
        return AdrSearchHit(
            slug = adr.slug,
            title = adr.title,
            date = adr.date,
            tags = adr.tags,
            status = adr.status,
            kind = SearchKind.DEFERRED.id,
            scope = SearchScope.DECISIONS.id,
            snippet = match,
            path = relativeToRoot(path),
        )
    }

    private fun markdownFiles(root: Path, depth: Int = 0): List<Path> {
        if (depth > MAX_DEPTH) return emptyList()
        return root.listDirectoryEntries().flatMap { entry ->
            when {
                entry.isDirectory() -> markdownFiles(entry, depth + 1)
                entry.toString().endsWith(".md") -> listOf(entry)
                else -> emptyList()
            }
        }
    }

    private fun titleOf(text: String): String = text.lineSequence()
        .firstOrNull { it.trimStart().startsWith("# ") }
        ?.trimStart('#', ' ')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: ""

    /**
     * The first line that matches, trimmed to [SNIPPET_MAX].
     *
     * The line, not the whole document: a hit that shows the whole body is a hit that
     * costs the reader the same context the search was meant to save.
     */
    private fun matchIn(needle: String, title: String, tags: List<String>, body: String): String? {
        val lowerNeedle = needle.lowercase()
        if (title.lowercase().contains(lowerNeedle)) {
            return title.trim().take(SNIPPET_MAX)
        }
        if (tags.any { it.lowercase().contains(lowerNeedle) }) {
            return "tag: ${tags.joinToString(", ")}".take(SNIPPET_MAX)
        }
        val line = body.lineSequence()
            .firstOrNull { it.lowercase().contains(lowerNeedle) }
            ?: return null
        val trimmed = line.trim()
        return if (trimmed.length > SNIPPET_MAX) {
            trimmed.take(SNIPPET_MAX) + "…"
        } else {
            trimmed
        }
    }

    private fun relativeToRoot(path: String): String {
        val root = projectRoot ?: return path
        return path.removePrefix(root).trimStart('/', '/')
    }

    private fun clamp(limit: Int): Int = limit.coerceIn(1, MAX_LIMIT)

    companion object {
        const val DEFAULT_LIMIT = SearchLimits.DEFAULT
        const val MAX_LIMIT = SearchLimits.MAX
        const val SNIPPET_MAX = SearchLimits.SNIPPET_MAX
        private const val MAX_DEPTH = 6
    }
}
