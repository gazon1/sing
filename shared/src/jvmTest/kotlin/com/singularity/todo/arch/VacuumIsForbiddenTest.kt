package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * No production code runs `VACUUM`.
 *
 * ## The defect this prevents
 *
 * The full-text index is an *external content* index: it holds the postings of
 * the words and no text, and a row is found through the `docid`, which is the
 * implicit `rowid` of the content table (`tasks`, `notes`, `projects`, `tags`).
 *
 * `VACUUM` rebuilds a database file, and in doing so it **renumbers the
 * `rowid` of every table that has no `INTEGER PRIMARY KEY`**. The id of a task
 * is a UUID, so the tables have no such key and their `rowid`s are plain
 * counters — exactly the kind `VACUUM` renumbers.
 *
 * After a `VACUUM` the index would still hold the postings of the old `rowid`s,
 * and every row it points at would be a different row: the search would return
 * the wrong records, or none. Nothing would report it. `VACUUM` succeeds, the
 * database is intact, and the search is quietly wrong.
 *
 * Nothing holds that apart. There is no `VACUUM` in the code today — this test
 * is what keeps it that way, because the next person to reach for it (a
 * "compact the database" maintenance routine, a backup step, a repair path
 * after a crash) has no way of knowing what it would break.
 *
 * ## What the gate reads
 *
 * The word `VACUUM` in a *string literal* of production code. Comments are
 * blanked first, so a file that explains why `VACUUM` is forbidden — this
 * file's neighbour, the ADR, a KDoc — is not reported. A string that merely
 * holds the word is reported, which is the right way round: a gate that cries
 * wolf gets allowlisted, and a gate that stays quiet until the damage is done
 * is not a gate.
 *
 * All three source sets that can reach the database are read: `commonMain`,
 * `androidMain` and `jvmMain`. A `VACUUM` in the platform code would break the
 * index exactly as thoroughly as one in the shared code.
 */
@Tag("fast")
class VacuumIsForbiddenTest {

    @Test
    fun the_scanner_finds_a_vacuum() {
        val source = """
            fun compact(path: String) {
                driver.execute("VACUUM")
            }
        """.trimIndent()
        assertTrue(
            mentionsVacuum(source),
            "a VACUUM in a string literal is the shape this gate is looking for",
        )
    }

    @Test
    fun the_scanner_ignores_a_vacuum_that_merely_appears_in_prose() {
        // The word in a comment must not stand in for the statement. Otherwise the
        // file that explains why VACUUM is forbidden is the file the gate reports,
        // and the gate is allowlisted on its first day.
        val source = """
            /**
             * Never run a VACUUM here: it renumbers the rowid the index points at.
             */
            fun compact(path: String) {
                driver.execute("PRAGMA optimize")
            }
        """.trimIndent()
        assertTrue(
            !mentionsVacuum(source),
            "a comment mentioning VACUUM is not a VACUUM",
        )
    }

    @Test
    fun the_scanner_finds_a_vacuum_in_a_raw_query() {
        val source = """
            @Query("VACUUM")
            fun compact(): Unit
        """.trimIndent()
        assertTrue(mentionsVacuum(source))
    }

    @Test
    fun the_scanner_ignores_a_word_that_only_contains_vacuum() {
        // "vacuuming" is not the statement. A gate that matched the letters would
        // report a name, a label or a setting.
        val source = """val setting = "vacuuming_mode""""
        assertTrue(!mentionsVacuum(source), "a word that merely contains the letters is not a VACUUM")
    }

    @Test
    fun no_production_code_runs_a_vacuum() {
        val offenders = productionSources()
            .map { it to mentionsVacuum(KotlinText.stripComments(it.readText())) }
            .filter { (_, mentions) -> mentions }
            .map { (file, _) -> file.name }

        if (offenders.isNotEmpty()) {
            fail(
                buildString {
                    appendLine(
                        "Production code mentions VACUUM in a string literal. VACUUM renumbers " +
                            "the implicit rowid of every table without an INTEGER PRIMARY KEY, " +
                            "and the full-text index points at those rowids through docid — so " +
                            "the search would answer with the wrong records, silently:",
                    )
                    offenders.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine(
                        "If the database has to be compacted, rebuild the index afterwards " +
                            "(`INSERT INTO tasks_fts(tasks_fts) VALUES('rebuild')` and the same " +
                            "for the other three), or open a separate issue that says how the " +
                            "index and the renumbered rowids are to be brought back into step.",
                    )
                },
            )
        }
    }

    @Test
    fun every_source_set_that_can_reach_the_database_is_read() {
        // A gate that reads one of three source sets checks a third of the code and
        // reports itself as a gate over all of it. The roots come from the build
        // (the same properties the other source-reading gates use), so a source set
        // that stops being configured shows up here rather than silently going
        // unchecked.
        val roots = sourceRoots()
        assertTrue(
            roots.isNotEmpty(),
            "no production source root was configured — the gate would pass over zero files",
        )
        assertEquals(
            setOf("commonMain", "androidMain", "jvmMain"),
            roots.keys,
            "the set of source sets this gate reads changed. A new source set that can " +
                "reach the database has to be read here too.",
        )
    }

    // ── the scanner ─────────────────────────────────────────────────────────

    /**
     * True when [source] holds the statement `VACUUM` in a string literal.
     *
     * The word has to stand alone: `VACUUM` is a statement, and a name that
     * contains the letters is not one.
     *
     * The comments are blanked first: a file that explains why `VACUUM` is
     * forbidden has to be able to say so.
     */
    private fun mentionsVacuum(source: String): Boolean =
        VACUUM.containsMatchIn(KotlinText.stripComments(source))

    private companion object {
        val VACUUM = Regex("""\bVACUUM\b""", RegexOption.IGNORE_CASE)

        /**
         * The production source roots, by source set.
         *
         * The property names are the ones the `jvmTest` task sets. A root that is
         * not configured is left out rather than guessed at, and
         * [every_source_set_that_can_reach_the_database_is_read] is what notices.
         */
        fun sourceRoots(): Map<String, File> {
            val roots = mutableMapOf<String, File>()
            val properties = mapOf(
                "commonMain" to "commonMain.root",
                "androidMain" to "androidMain.root",
                "jvmMain" to "jvmMain.root",
            )
            properties.forEach { (sourceSet, property) ->
                val path = System.getProperty(property) ?: return@forEach
                val root = File(path)
                if (root.isDirectory) roots[sourceSet] = root
            }
            return roots
        }

        /** Every production `.kt` file of every source set that can reach the database. */
        fun productionSources(): List<File> =
            sourceRoots().values.flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".kt") }
                    .filterNot { file -> with(SourceScan) { file.isTestDouble() } }
                    .toList()
            }
    }
}
