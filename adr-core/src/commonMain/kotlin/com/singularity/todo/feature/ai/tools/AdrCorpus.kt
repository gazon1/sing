package com.singularity.todo.feature.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/** Relative path to the rules file, from a checkout root. */
internal const val CORPUS_CONFIG_PATH = "config/docs/adr-corpus.json"

/**
 * The JVM/Common mirror of `config/docs/adr-corpus.json`.
 *
 * Four Python consumers and this one had discovered the same corpus five different
 * ways, and they had already drifted apart — three of the five could not see
 * `docs/decisions/deferred/`, so the status gate reported "all statuses in
 * vocabulary" while 129 files used a vocabulary of their own (#520).
 *
 * The rules now live in one JSON file. Python reads it with stdlib `json`, this
 * reads it with kotlinx-serialization — neither side needs a new dependency, and
 * neither can quietly disagree with the other.
 *
 * **When the file is not reachable** (the `~/.singularity-todo` fallback in
 * [AdrStorage], or a checkout without `config/`), [loadOrDefault] falls back to
 * the built-in values below. Those are a *fallback*, not a second source of
 * truth: `scripts/check-adr-config-sync.py` fails if the two ever disagree, so
 * the constants cannot drift without it being caught.
 */
data class AdrCorpusConfig(
    val root: String,
    val decisionPattern: Regex,
    val archiveDir: String,
    val deferredDir: String,
    val ignoredFiles: Set<String>,
    val statuses: Set<String>,
    val archivedStatus: String,
    val staleDays: Int,
    /**
     * Every frontmatter key an ADR may carry. See [frontmatterKeys].
     */
    val frontmatterKeys: Set<String> = DEFAULT.frontmatterKeys,
    /**
     * Order the normalizer emits keys in. See [frontmatterKeys].
     */
    val frontmatterKeyOrder: List<String> = DEFAULT.frontmatterKeyOrder,
    /**
     * The status a brand-new decision is born with, and the key order it starts
     * with. Declared here rather than hardcoded in [AdrStorage.writeAdr] because
     * "a new ADR is `open`" was true by accident until #518 recorded it as a rule.
     */
    val newAdrStatus: String = DEFAULT.newAdrStatus,
    val newAdrKeyOrder: List<String> = DEFAULT.newAdrKeyOrder,
) {
    /**
     * The frontmatter contract, read from the same file as everything else (#518).
     *
     * Three code paths used to own this between them: [AdrStorage.writeAdr] wrote
     * `title, date, status: open, tags` with no validation,
     * `normalize-adr-frontmatter.py` held a `KNOWN_KEYS` allowlist, and
     * `resolve_stale_adr_status.py` did regex surgery on `^status:`. Any edit to one
     * left the other two describing a different format, and they had already drifted:
     * `updateAdr` began emitting `review-by:` in #527 and the normalizer rejected the
     * repo's own tool output as `unknown frontmatter key`.
     *
     * The allowlist is a safety property, not a formality. A body written *inside* the
     * frontmatter block still parses as valid YAML, so nothing else catches it — and
     * carrying those "keys" through is how a body gets duplicated into the file. So
     * the writer must not only stay inside the list, it must fail loudly rather than
     * invent a key the normalizer will reject.
     */
    fun isKnownFrontmatterKey(key: String): Boolean = key in frontmatterKeys

    /**
     * Backlog entries under `<root>/<deferredDir>/`, as slugs.
     *
     * These are *not* decisions (see the class docstring), but `supersededBy` must
     * still be able to point at one — a decision can be replaced by a planned one.
     */
    fun deferred(root: String): List<String> {
        val d = "$root/$deferredDir"
        val dir = java.io.File(d)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }
            ?.map { it.name.removeSuffix(".md") }
            ?.filterNot { it in ignoredFiles }
            ?.sorted()
            ?: emptyList()
    }

    fun isArchived(path: String): Boolean = path.contains("/$archiveDir/")

    /** `docs/decisions/<deferredDir>/` — backlog, deliberately NOT decisions. */
    fun isDeferred(path: String): Boolean = path.contains("/$deferredDir/")

    fun isIgnored(path: String): Boolean = path.substringAfterLast('/') in ignoredFiles

    /**
     * Every ADR the corpus considers real: dated, not backlog, not ignored,
     * not archived. `archive/` is skipped on purpose — those files carry
     * `status: archived` and a different vocabulary, and including them made
     * `list --status open` disagree with the policy gate.
     */
    fun discover(root: String, maxDepth: Int = 3): List<String> =
        walk(Path(root), depth = 0, maxDepth = maxDepth).map { it.toString() }

    private fun walk(dir: java.nio.file.Path, depth: Int, maxDepth: Int): List<java.nio.file.Path> {
        if (depth > maxDepth || !dir.isDirectory()) return emptyList()
        return dir.listDirectoryEntries().flatMap { entry ->
            val path = entry.toString()
            when {
                entry.isDirectory() ->
                    if (isArchived(path)) emptyList() else walk(entry, depth + 1, maxDepth)

                isIgnored(path) -> emptyList()

                else -> listOf(entry)
            }
        }
    }

    fun isDecision(path: String): Boolean =
        !isDeferred(path) && !isIgnored(path) && decisionPattern.containsMatchIn(path.substringAfterLast('/'))

    /**
     * Fold case and strip brackets. `OPEN` and `open` must answer the same question
     * everywhere — a policy gate and a query that disagree on what "open" means is
     * worse than either being wrong alone.
     *
     * This deliberately does NOT map out-of-vocabulary values (`CLOSED` ->
     * `accepted`): that is a one-time migration (#516), and doing it silently here
     * would let a typo be reinterpreted as a decision.
     */
    fun normalizeStatus(raw: String?): String =
        raw?.trim()?.trim('[', ']', '"', '\'')?.lowercase().orEmpty()

    fun isValidStatus(status: String): Boolean =
        status in statuses || status == archivedStatus

    companion object {
        /** Mirrors `config/docs/adr-corpus.json`. Kept honest by check-adr-config-sync.py. */
        val DEFAULT = AdrCorpusConfig(
            root = "docs/decisions",
            decisionPattern = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}-"),
            archiveDir = "archive",
            deferredDir = "deferred",
            ignoredFiles = setOf("DIGEST.md"),
            statuses = setOf("accepted", "deferred", "superseded", "open"),
            archivedStatus = "archived",
            staleDays = 30,
            frontmatterKeys = setOf(
                "title", "date", "created", "updated", "last_updated", "status", "tags", "Tags",
                "deciders", "decider", "authors", "author", "reviewedBy", "review-by", "owner",
                "profile", "epic", "description", "summary", "id", "slug", "type", "impact",
                "review", "supersedes", "superseded-by", "superseded_by", "doesNotSupersede",
                "replaces", "follows", "decides", "adr", "adr-number", "end-date", "labels",
                "status-was", "issuesRelated", "related", "references", "skills", "context",
                "Context", "Decision", "Rationale", "Consequences", "Links", "deprecated-by",
                "gh", "issue", "backlog", "tracked-as",
            ),
            frontmatterKeyOrder = listOf(
                "title",
                "date",
                "status",
                "tags",
                "deciders",
                "supersedes",
                "superseded-by",
                "epic",
            ),
            newAdrStatus = "open",
            newAdrKeyOrder = listOf("title", "date", "status", "tags"),
        )

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Read `<repoRoot>/config/docs/adr-corpus.json`, or fall back to [DEFAULT].
         *
         * [repoRoot] is any ancestor that holds `config/docs/adr-corpus.json`; the
         * caller usually knows a decisions directory and can work upwards from it.
         */
        fun loadOrDefault(repoRoot: String?): AdrCorpusConfig {
            val root = repoRoot ?: return DEFAULT
            val file = Path(root, CORPUS_CONFIG_PATH)
            if (!file.exists()) return DEFAULT
            return runCatching { parse(file.readText()) }.getOrDefault(DEFAULT)
        }

        internal fun parse(text: String): AdrCorpusConfig {
            val obj = json.parseToJsonElement(text).jsonObject
            val vocab = obj["vocabulary"]?.jsonObject
            // An empty object rather than `?:` per field: the frontmatter block is
            // optional in an older checkout, but scattering five `?:` fallbacks across
            // the constructor is five chances to fall back to the wrong default.
            val fm = obj["frontmatter"]?.jsonObject ?: JsonObject(emptyMap())
            return AdrCorpusConfig(
                root = obj.string("root", DEFAULT.root),
                decisionPattern = Regex(obj.string("decisionPattern", DEFAULT.decisionPattern.pattern)),
                archiveDir = obj.string("archiveDir", DEFAULT.archiveDir),
                deferredDir = obj.string("deferredDir", DEFAULT.deferredDir),
                ignoredFiles = obj.stringList("ignoredFiles", DEFAULT.ignoredFiles),
                statuses = vocab?.stringList("statuses", DEFAULT.statuses)
                    ?: DEFAULT.statuses,
                archivedStatus = vocab?.string("archivedStatus", DEFAULT.archivedStatus)
                    ?: DEFAULT.archivedStatus,
                staleDays = obj["staleDays"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT.staleDays,
                frontmatterKeys = fm.stringList("knownKeys", DEFAULT.frontmatterKeys),
                frontmatterKeyOrder = fm.orderedStringList(
                    "keyOrder",
                    DEFAULT.frontmatterKeyOrder,
                ),
                newAdrStatus = fm.string("newAdrStatus", DEFAULT.newAdrStatus),
                newAdrKeyOrder = fm.orderedStringList("newAdrKeyOrder", DEFAULT.newAdrKeyOrder),
            )
        }

        private fun JsonObject.string(key: String, fallback: String): String =
            this[key]?.jsonPrimitive?.content ?: fallback

        private fun JsonObject.stringList(key: String, fallback: Set<String>): Set<String> =
            this[key]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: fallback

        /**
         * Order-preserving counterpart of [stringList].
         *
         * A `Set` would quietly discard the sequence, and frontmatter key order is the
         * difference between `--- title, date, status, tags ---` and whatever the
         * insertion order of a hash set happened to be. Order is part of the contract.
         */
        private fun JsonObject.orderedStringList(key: String, fallback: List<String>): List<String> =
            this[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: fallback
    }
}
