package com.singularity.todo.feature.ai.tools

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two readers of the document corpora must see the same documents.
 *
 * The policy lives in `config/docs/document-corpus.json` and both readers read it:
 * the Kotlin search engine (`DocumentCorpusConfig`) and the Python reader
 * (`scripts/document_corpus.py`). Two readers of one policy is the arrangement the
 * ADR corpus has had since 2026-10-10, and it is the reason the answer to "where is
 * the rule about X?" does not depend on which side asks.
 *
 * The divergence shows up here as a set difference — not as a symptom downstream, and
 * not as a fix one side made on its own. A search that can see documents the gates do
 * not check is a search whose results nothing vets; a search that sees fewer is a
 * search that cannot answer a question the gates answer.
 *
 * The test crosses a process boundary — it runs `python3` — so it is tagged `slow`.
 */
@Tag("slow")
class DocumentCorpusReadersAgreeTest {

    @Test
    fun `the app reads the documents the gates read`() {
        val root = repoRoot()

        for (scope in listOf(SearchScope.ARCHITECTURE, SearchScope.SKILLS, SearchScope.SPECS)) {
            val config = DocumentCorpusConfig.loadOrDefault(root.path)
            assertTrue(
                config.isDeclared(scope.id),
                "the policy declares no scope '${scope.id}' — the search would have " +
                    "nothing to look in",
            )

            val kotlinPaths = search(root, config).documentsIn(scope).toSortedSet()
            val pythonPaths = pythonDocuments(root, scope.id)

            assertTrue(
                kotlinPaths.isNotEmpty(),
                "the app found no documents in the ${scope.id} scope at all",
            )
            assertEquals(
                pythonPaths,
                kotlinPaths,
                "the app and the gates see different documents in the ${scope.id} scope — " +
                    "one side found ${pythonPaths.size}, the other ${kotlinPaths.size}",
            )
        }
    }

    /** The search engine, built the way the storage builds it. */
    private fun search(root: File, config: DocumentCorpusConfig): CorpusSearch = CorpusSearch(
        projectRoot = root.path,
        decisionsDir = File(root, "docs/decisions").path,
        corpus = AdrCorpusConfig.loadOrDefault(root.path),
        readAdr = { null },
        documents = config,
    )

    /**
     * The document paths the Python reader finds.
     *
     * The Python reader is run as a program, not imported: importing it means loading a
     * dataclass through `importlib`, and under Python 3.14 that path has to register the
     * module before executing it.
     */
    private fun pythonDocuments(root: File, scope: String): Set<String> {
        val script = File(root, "scripts/document_corpus.py")
        assertTrue(script.isFile, "no document corpus reader at $script")

        val process = ProcessBuilder(
            "python3",
            script.absolutePath,
            "--documents",
            scope,
        )
            .directory(root)
            .redirectErrorStream(false)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor()
        assertEquals(0, finished, "the Python document corpus reader failed:\n$output")

        val paths = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()
        assertTrue(paths.isNotEmpty(), "the Python reader found no documents in $scope at all")
        return paths
    }

    /** The repository root, found by walking up from the working directory. */
    private fun repoRoot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) {
        it.parentFile
    }
        .firstOrNull { File(it, "scripts/document_corpus.py").isFile }
        ?: error("could not find the repository root from ${System.getProperty("user.dir")}")
}
