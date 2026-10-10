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
) {
    /** `docs/decisions/<archiveDir>/` */
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
            val file = Path("$root/config/docs/adr-corpus.json")
            if (!file.exists()) return DEFAULT
            return runCatching { parse(file.readText()) }.getOrDefault(DEFAULT)
        }

        internal fun parse(text: String): AdrCorpusConfig {
            val obj = json.parseToJsonElement(text).jsonObject
            val vocab = obj["vocabulary"]?.jsonObject
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
            )
        }

        private fun JsonObject.string(key: String, fallback: String): String =
            this[key]?.jsonPrimitive?.content ?: fallback

        private fun JsonObject.stringList(key: String, fallback: Set<String>): Set<String> =
            this[key]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: fallback
    }
}