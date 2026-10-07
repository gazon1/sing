package com.singularity.todo.feature.genui.core

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.genui.catalog.A2uiCatalog
import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private val logger = Logger.withTag("GenuiCatalogUsage")

/**
 * How often each component kind has been decoded, since the process started.
 *
 * The companion to [GenuiRejectionCounter], and it answers the question that one cannot. Rejections
 * say what a model gets *wrong*; this says what it reaches for. The open question behind "should
 * the catalog grow" had no answer at all, because the seventeen kinds were a guess with a prompt
 * attached — and the two ways of being wrong look identical from the outside: a kind nobody ever
 * emits is either a mistake in the prompt, or a component the model was never shown the use of.
 * One is fixed by editing prose, the other by building something. The tally separates them.
 *
 * Counted at the point a component becomes a node, not where the model wrote it: a kind that is
 * written and then dropped as malformed is something the model *tried* and got wrong, and counting
 * it as usage would answer the wrong question.
 *
 * In memory and cumulative, like its counterpart. It is an instrument for whoever is deciding what
 * to build next, not a metric to report on.
 */
class GenuiUsageCounter {

    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Live tally of decoded components by kind. */
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    /** Records one decoded component. */
    fun record(kind: String) {
        _counts.update { it + (kind to ((it[kind] ?: 0) + 1)) }
    }

    /**
     * Kinds the catalog declares and nothing has ever drawn.
     *
     * This is the list worth arguing about: a component nobody reaches for is either a prompt that
     * failed to mention it or a feature that was never needed, and neither is discoverable from
     * the catalog's own declarations.
     */
    fun neverUsed(catalog: A2uiCatalog): List<String> =
        catalog.components.keys.filterNot { (_counts.value[it] ?: 0) > 0 }.sorted()

    /** How often each kind was drawn, most used first. */
    fun ranking(): List<Pair<String, Int>> = _counts.value.entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }

    /**
     * The two lines [report] logs, as data.
     *
     * Returning them rather than formatting them inside [report] is what makes the reporting
     * testable: a log line written by a private `logger` can only be asserted by capturing Kermit
     * output, and a test that does that asserts the logging framework rather than the tally. The
     * question "did this turn tell me which catalog components went unused" is a question about the
     * content of the report, and this is the content.
     *
     * Empty when nothing has been drawn — a prose-only answer, or a failure before the first
     * component. A tally of zeroes against seventeen components is noise, not a finding, and
     * "everything is unused" is the least useful thing this instrument could say.
     */
    fun summary(catalog: A2uiCatalog = SingularityCatalog): List<String> {
        val ranking = ranking()
        if (ranking.isEmpty()) return emptyList()
        val unused = neverUsed(catalog)
        return buildList {
            add("Component usage: ${ranking.joinToString(", ") { "${it.first}=${it.second}" }}")
            if (unused.isNotEmpty()) add("Never drawn: ${unused.joinToString(", ")}")
        }
    }

    /** Logs [summary] — called when an answer ends, where the decision to act on it is made. */
    fun report(catalog: A2uiCatalog = SingularityCatalog) {
        summary(catalog).forEach { line -> logger.i { line } }
    }

    /** Forgets everything. Only meaningful for a test. */
    fun reset() {
        _counts.value = emptyMap()
    }
}
