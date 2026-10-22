package com.singularity.todo.feature.search.query

import com.singularity.todo.core.search.TextMatchPlan
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.TEST_TZ
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The share of the searches the index cannot narrow.
 *
 * ## What is measured
 *
 * The plan a search is given: [TextMatchPlan.Narrowed] — the index answers
 * which records carry the word — or [TextMatchPlan.BroadScan] — the index
 * cannot answer the rule, and the rows are read and looked at in memory.
 *
 * ## The corpus, and what it is
 *
 * The corpus is **the queries this project exercises**: the free-text queries
 * of the search tests and the queries the Maestro flows type, plus the filters
 * the filter sheet produces, plus the two shapes the product's own surfaces
 * cannot produce.
 *
 * It is *not* a sample of what people type. The app keeps no history of the
 * queries it was given, so there is nothing to sample — and a number taken
 * from a corpus the project made up would be a number about that corpus. What
 * this measurement can say is which *shapes* reach the broad scan, and whether
 * the product's own surfaces can produce them.
 *
 * That is the question the decision turns on: a plan change that makes the
 * broad scan cheaper (issue #944) is worth its cost if people reach it. If
 * the product's surfaces cannot reach it, the cost buys nothing.
 *
 * ## The two costs, which are not the same
 *
 * A broad scan is not one thing:
 *
 * - a rule with **only negated terms** reads every task of the profile and
 *   filters it in memory — the full scan the index was built to avoid;
 * - a rule with **no terms at all** — a query of filters only — reads every
 *   note, project and tag to find that it matches none of them. The tasks are
 *   filtered by the database and are not scanned; the other three kinds are
 *   read for nothing.
 *
 * The second is the everyday case: every filter from the sheet is a query of
 * filters only. The first is the corner: it needs a query no surface of the
 * app produces.
 *
 * ## The floor
 *
 * The numbers below are asserted, so a change in the plan semantics — #944,
 * or anything else — breaks this test instead of quietly changing what the
 * search costs. A measurement that only reads out a number does not hold
 * anything; this one does.
 */
@Tag("fast")
class SearchPlanShareTest {

    // ─── The corpus ───────────────────────────────────────────────────────────

    /**
     * One query of the corpus, and where it comes from.
     *
     * @param query the query, as a person or a surface states it.
     * @param source where the query comes from — the file or the surface that
     *   produces it. A corpus without a source is a list of strings.
     */
    private data class Corpus(val query: String, val source: String)

    private val corpus = listOf(
        // The free-text queries the search tests use.
        Corpus("milk", "SearchTextQueryTest"),
        Corpus("milk report", "SearchTextQueryTest"),
        Corpus("alpha beta", "SearchFreeTextRuleTest"),
        Corpus("alpha", "SearchFreeTextRuleTest"),
        Corpus("beta", "SearchFreeTextRuleTest"),
        Corpus("alpha NOT beta", "SearchFreeTextRuleTest"),
        Corpus("cafe", "FtsMatcherAgreementTest"),
        Corpus("quarterly numbers", "FtsMatcherAgreementTest"),
        Corpus("zzzznomatchzzzz999999", "the search tests — a query that matches nothing"),
        // The queries the flows type.
        Corpus("Pokupki", "Maestro/flows/search"),
        Corpus("arch", "Maestro/flows/search/08-word-prefix.yaml"),
        Corpus("port", "Maestro/flows/search/08-word-prefix.yaml"),
        Corpus("Task2026", "Maestro/flows/search"),
        Corpus("Saved2026", "Maestro/flows/search/07-saved-search.yaml"),
        // The filters the filter sheet produces — a query of filters only.
        Corpus("priority:high", "the filter sheet"),
        Corpus("due:today", "the filter sheet"),
        Corpus("pinned", "the filter sheet"),
        Corpus("tag:work", "the filter sheet"),
        // The shapes the product's own surfaces cannot produce.
        Corpus("NOT beta", "the grammar only — a rule with nothing but a negation"),
        Corpus("alpha OR NOT beta", "the grammar only — an OR with a negation"),
    )

    // ─── The measurement ──────────────────────────────────────────────────────

    private val tagLookup = object : TagLookup {
        override suspend fun findByName(userId: String, name: String): TagLookupResult? = null
    }

    private val projectLookup = object : ProjectLookup {
        override suspend fun findByName(userId: String, name: String): ProjectLookupResult? = null
    }

    private val resolver = DefaultSearchQueryResolver(
        tagLookup,
        projectLookup,
        FakeClock(Instant.parse("2026-09-16T10:00:00Z")),
        TEST_TZ,
    )

    private val userId = "user_1"

    /** The plan a query of the corpus is given, and what it costs. */
    private data class Measured(val corpus: Corpus, val plan: TextMatchPlan, val hasTerms: Boolean) {
        /** The rows are read and looked at in memory. */
        val scansTheRows: Boolean get() = plan is TextMatchPlan.BroadScan && hasTerms

        /** The other kinds are read to find that the query matches none of them. */
        val readsTheOtherKinds: Boolean get() = plan is TextMatchPlan.BroadScan && !hasTerms
    }

    private suspend fun measure(corpus: Corpus): Measured {
        val query = SingularityQueryParser(corpus.query).parse()
        val resolved = resolver.resolve(query, userId)
        return Measured(corpus, resolved.textMatchPlan, !resolved.freeTextRule.isEmpty)
    }

    // ─── The tests ────────────────────────────────────────────────────────────

    @Test
    fun `the index narrows most of the corpus, and the broad scan is the minority`() = runTest {
        val measured = corpus.map { measure(it) }

        val narrowed = measured.filter { it.plan is TextMatchPlan.Narrowed }
        val broad = measured.filter { it.plan is TextMatchPlan.BroadScan }

        assertEquals(
            14,
            narrowed.size,
            "the queries the index answers: " +
                narrowed.joinToString { it.corpus.query },
        )
        assertEquals(
            6,
            broad.size,
            "the queries that reach the broad scan: " +
                broad.joinToString { "${it.corpus.query} (${it.corpus.source})" },
        )
    }

    @Test
    fun `the broad scan is reached by the shapes the product cannot produce`() = runTest {
        val measured = corpus.map { measure(it) }

        val broad = measured.filter { it.plan is TextMatchPlan.BroadScan }
            .map { it.corpus.query }

        assertEquals(
            listOf("priority:high", "due:today", "pinned", "tag:work", "NOT beta", "alpha OR NOT beta"),
            broad,
        )
    }

    @Test
    fun `a query of filters only does not scan the tasks`() = runTest {
        val measured = corpus.map { measure(it) }

        val scans = measured.filter { it.scansTheRows }.map { it.corpus.query }

        assertEquals(
            listOf("NOT beta", "alpha OR NOT beta"),
            scans,
            "the queries that read every task of the profile and filter it in memory",
        )
    }

    @Test
    fun `a query of filters only reads the other kinds for nothing`() = runTest {
        val measured = corpus.map { measure(it) }

        val reads = measured.filter { it.readsTheOtherKinds }.map { it.corpus.query }

        assertEquals(
            listOf("priority:high", "due:today", "pinned", "tag:work"),
            reads,
            "the queries that read every note, project and tag to find that they match none",
        )
    }
}
