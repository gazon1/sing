package com.singularity.todo.feature.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * The document corpora: the roots of every scope a search can see.
 *
 * ## Why this exists
 *
 * The roots used to be string literals in the `SearchScope` enum — `docs/architecture`,
 * `.agents/skills`, `openspec/specs` — and only the Kotlin search engine knew them. The
 * gates that read the same trees each carried their own copy of the path, and the
 * Python side knew about the ADR corpus only. So "where is the rule about X?" was
 * answered from one set of directories and "is that directory still there?" from
 * another, and the two answers could differ without anything saying so.
 *
 * The ADR corpus solved this before: one policy file, read by both sides, with a test
 * that compares the two readers. This is the same arrangement for the document
 * corpora — the policy in `config/docs/document-corpus.json`, the Kotlin reader here,
 * the Python reader in `scripts/document_corpus.py`, and
 * `DocumentCorpusReadersAgreeTest` comparing them.
 *
 * The decisions are not in this policy. They are *records*: they have a frontmatter
 * contract, statuses and a staleness rule, and those live in
 * `config/docs/adr-corpus.json`. One file with two kinds of rule is a file whose
 * reader has to know which kind it is reading.
 */
data class DocumentCorpusConfig(
    /** How many directory levels below a root are walked. */
    val maxDepth: Int,
    /** What a document is. */
    val fileExtension: String,
    /** Scope id to the roots it lives in, relative to the project root. */
    val roots: Map<String, List<String>>,
) {
    /** The roots of a scope, or nothing when the scope is not declared. */
    fun rootsFor(scopeId: String): List<String> = roots[scopeId].orEmpty()

    /** Whether the policy declares this scope at all. */
    fun isDeclared(scopeId: String): Boolean = roots.containsKey(scopeId)

    companion object {
        /** Where the policy lives, relative to the project root. */
        const val CONFIG_PATH = "config/docs/document-corpus.json"

        /**
         * The policy a checkout without the file falls back to.
         *
         * The same roots the literals had. A missing policy file must not change what
         * a search can see — it must only mean that the policy is not there, which is
         * the reader's problem to report, not the corpus's.
         */
        val DEFAULT = DocumentCorpusConfig(
            maxDepth = 6,
            fileExtension = ".md",
            roots = mapOf(
                "architecture" to listOf("docs/architecture"),
                "skills" to listOf(".agents/skills"),
                "specs" to listOf("openspec/specs"),
            ),
        )

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Read `<repoRoot>/config/docs/document-corpus.json`, or fall back to [DEFAULT].
         *
         * [repoRoot] is the directory that holds the policy; the caller usually knows
         * where the project is and can work upwards from there.
         */
        fun loadOrDefault(repoRoot: String?): DocumentCorpusConfig {
            val root = repoRoot ?: return DEFAULT
            val file = Path(root, CONFIG_PATH)
            if (!file.exists()) return DEFAULT
            return runCatching { parse(file.readText()) }.getOrDefault(DEFAULT)
        }

        internal fun parse(text: String): DocumentCorpusConfig {
            val obj = json.parseToJsonElement(text).jsonObject
            val corpora = obj["corpora"]?.jsonObject ?: JsonObject(emptyMap())
            val roots = corpora.mapNotNull { (scope, value) ->
                val declared = value.jsonObject["roots"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.content }
                    ?.filter { it.isNotBlank() }
                    ?: return@mapNotNull null
                if (declared.isEmpty()) null else scope to declared
            }.toMap()
            return DocumentCorpusConfig(
                maxDepth = obj["maxDepth"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT.maxDepth,
                fileExtension = obj["fileExtension"]?.jsonPrimitive?.content ?: DEFAULT.fileExtension,
                roots = if (roots.isEmpty()) DEFAULT.roots else roots,
            )
        }
    }
}
