package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cross-reference reads that serve one selector name the selector, not a list of ids.
 *
 * ## The defect this prevents
 *
 * A subtask list used to enrich itself from the profile-wide cross-reference read, which
 * materialises every tag and dependency edge of the profile. The obvious narrowing derives
 * the ids from the subtask rows and asks `IN (:taskIds)` — and that shape has a ceiling
 * SQLite enforces: one host parameter per id, and `too many SQL variables` past the limit.
 * Nothing bounds the number of subtasks a parent may have (`watchSubtasksForUser` has no
 * `LIMIT`, and no domain rule caps it), so the narrowed read could fail on a large parent
 * in exactly the place it was introduced to make large profiles safe.
 *
 * The shape that holds is a subquery on the selector — the parent id or the series id is
 * one bound parameter whatever the number of children. This gate keeps the next reader
 * from reintroducing an id list here, because the failure it causes depends on how many
 * rows a user happens to have and will not show up in a test with three subtasks.
 *
 * ## What it deliberately does not cover
 *
 * `blockedTaskIdsForUser` binds a list of ids too, and it is **pre-existing**: its callers
 * pass a project's open tasks or a filtered list. The same latent ceiling applies to it,
 * and that is a separate change — this gate has no business failing the first person who
 * touches an unrelated query. It judges the four methods this read introduced, by name.
 */
@Tag("fast")
class SelectorScopedCrossRefQueryTest {
    /** The four cross-reference reads this selector-scoped path introduced. */
    private val selectorMethods =
        listOf(
            "observeTagCrossRefsOfSubtasks",
            "observeDependencyCrossRefsOfSubtasks",
            "observeTagCrossRefsOfSeries",
            "observeDependencyCrossRefsOfSeries",
        )

    @Test
    fun `the selector-scoped cross-reference reads exist`() {
        val source = KotlinText.stripComments(daoSource().readText())
        selectorMethods.forEach { method ->
            assertTrue(
                Regex("""fun\s+$method\s*\(""").containsMatchIn(source),
                "TaskDao has no $method — the subtask and series enrichment reads its selector, not a list of ids",
            )
        }
    }

    @Test
    fun `the selector-scoped reads name their selector instead of binding ids`() {
        val source = KotlinText.stripComments(daoSource().readText())
        val sqlByMethod = queriesByMethod(source)

        selectorMethods.forEach { method ->
            val sql =
                sqlByMethod[method]
                    ?: fail("TaskDao has no @Query for $method — the gate cannot judge what it did not find")
            assertFalse(
                Regex("""task_id\s+IN\s*\(\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(sql),
                "$method binds a per-row id list, which SQLite refuses past its " +
                    "host-parameter ceiling and nothing bounds the number of rows behind " +
                    "the selector:\n$sql",
            )
            assertTrue(
                Regex("""IN\s*\(\s*SELECT""", RegexOption.IGNORE_CASE).containsMatchIn(sql),
                "$method should read its cross-references through a subquery on the selector:\n$sql",
            )
        }
    }

    /**
     * Every `@Query` SQL in the file, keyed by the method it annotates.
     *
     * The annotation is associated with the **next** declaration that follows it, not with
     * a fixed window — a positional window can inspect a neighbouring method's query and
     * pass while the method under test differs. Queries are matched across lines, because
     * these are triple-quoted.
     */
    private fun queriesByMethod(source: String): Map<String, String> {
        val queries = mutableMapOf<String, String>()
        val annotation = Regex("""@Query\(\s*""", RegexOption.IGNORE_CASE)
        var searchFrom = 0
        while (true) {
            val match = annotation.find(source, searchFrom) ?: break
            val sql =
                stringLiteralAt(source, match.range.last + 1)
                    ?: fail("an @Query at offset ${match.range.first} has no readable SQL")
            val declaration =
                Regex("""fun\s+(\w+)\s*\(""").find(source, match.range.last + 1)
                    ?: fail("an @Query at offset ${match.range.first} annotates no function")
            queries[declaration.groupValues[1]] = sql
            searchFrom = match.range.last + 1
        }
        return queries
    }

    /** The contents of the triple-quoted or plain string literal that starts at [from]. */
    private fun stringLiteralAt(
        source: String,
        from: Int,
    ): String? {
        var at = from
        while (at < source.length && source[at].isWhitespace()) at++
        return when {
            source.startsWith("\"\"\"", at) -> {
                val end = source.indexOf("\"\"\"", at + 3)
                if (end < 0) null else source.substring(at + 3, end)
            }

            at < source.length && source[at] == '"' -> {
                val end = source.indexOf('"', at + 1)
                if (end < 0) null else source.substring(at + 1, end)
            }

            else -> {
                null
            }
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private fun daoSource() = SourceScan.fileNamed("Daos.kt")
}
