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
        val lines = source.lines()
        // The bound-list form: `task_id IN (:taskIds)`. A subquery that names the
        // selector (`task_id IN (SELECT ...)`) is the shape these reads must keep.
        val boundList = Regex("""task_id\s+IN\s*\(\s*:""", RegexOption.IGNORE_CASE)

        selectorMethods.forEach { method ->
            val at = lines.indexOfFirst { Regex("""fun\s+$method\s*\(""").containsMatchIn(it) }
            assertTrue(at >= 0, "TaskDao has no $method")
            // The @Query is the few lines above the declaration.
            val body = lines.subList(maxOf(0, at - 12), at + 1).joinToString("\n")
            assertFalse(
                boundList.containsMatchIn(body),
                "$method binds a per-row id list, which SQLite refuses past its " +
                    "host-parameter ceiling and nothing bounds the number of rows behind " +
                    "the selector:\n$body",
            )
            assertTrue(
                Regex("""IN\s*\(\s*SELECT""", RegexOption.IGNORE_CASE).containsMatchIn(body),
                "$method should read its cross-references through a subquery on the selector",
            )
        }
    }

    private fun daoSource() = SourceScan.fileNamed("Daos.kt")
}
