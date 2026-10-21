package com.singularity.todo.feature.ai.tools

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two readers of this corpus must answer the same question.
 *
 * The app reads the corpus from Kotlin ([AdrCorpus]); the gates that check the corpus
 * read it from Python (`scripts/adr_corpus.py`). They are two implementations of one
 * policy, and nothing used to say they had to agree — so they drifted: the Python
 * reader globbed the top level while the Kotlin reader walked the tree, and a decision
 * in a subdirectory was a decision to the app and nothing at all to the gates.
 *
 * This test runs the Python reader and compares its answer with the one this module
 * computes. The Python side is the reference because the gates are what check the
 * corpus: an application that reads *more* than the gates check is an application whose
 * decisions are never gated, and one that reads *less* is an application that cannot
 * see a decision the gates do.
 *
 * So a divergence shows up here, as a set difference — not as a symptom somewhere
 * downstream, and not as a fix that one of the two sides made on its own.
 */
@Tag("fast")
class AdrCorpusReadersAgreeTest {

    @Test
    fun `the app reads the corpus the gates read`() {
        val root = repoRoot()
        val corpus = File(root, "docs/decisions")
        assertTrue(corpus.isDirectory, "no corpus at $corpus")

        val config = AdrCorpusConfig.loadOrDefault(root.toString())
        val kotlinSlugs = config.discover(corpus.toString())
            .filter { config.isDecision(it) }
            .map { it.substringAfterLast('/').removeSuffix(".md") }
            .toSortedSet()

        val pythonSlugs = pythonDecisions(root)

        assertEquals(
            pythonSlugs,
            kotlinSlugs,
            "the app and the gates read different corpora — the Python reader found one " +
                "set of decisions and the app found another",
        )
    }

    /**
     * The decision slugs the Python reader finds.
     *
     * The Python reader is run as a program, not imported: importing it means the
     * dataclass machinery of `adr_corpus.py` has to be loaded through `importlib`, and
     * under Python 3.14 that path has to register the module before executing it.
     */
    private fun pythonDecisions(root: File): Set<String> {
        val script = File(root, "scripts/adr_corpus.py")
        assertTrue(script.isFile, "no corpus reader at $script")

        val process = ProcessBuilder(
            "python3",
            script.absolutePath,
            "--decisions",
        )
            .directory(root)
            .redirectErrorStream(false)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor()
        assertEquals(0, finished, "the Python corpus reader failed:\n$output")

        val slugs = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()
        assertTrue(slugs.isNotEmpty(), "the Python reader found no decisions at all")
        return slugs
    }

    /** The repository root, found by walking up from the working directory. */
    private fun repoRoot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) {
        it.parentFile
    }
        .firstOrNull { File(it, "config/docs/adr-corpus.json").isFile }
        ?: error("could not find the repository root from ${System.getProperty("user.dir")}")
}
