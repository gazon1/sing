package com.singularity.todo.feature.agenda.domain.model

import com.singularity.todo.feature.agenda.domain.selector.SelectorTransformer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Agenda layout determines how sections are rendered.
 *
 * - [ListFlat] — single scrollable list, sections are collapsible headers
 * - [ListGrouped] — sticky section headers, tasks grouped under each
 * - [TimeGrid] — time-based grid (hour rows), used for day-focus view
 */
@Serializable
enum class AgendaLayout {
    ListFlat,
    ListGrouped,
    TimeGrid,
}

/**
 * A single section within an [AgendaDefinition].
 *
 * @param name Display name of the section (e.g. "Today", "Overdue", "No Date").
 * @param order Sorting weight; sections are sorted ascending by this value when
 *        rendering an agenda with multiple sections.
 * @param selector The predicate that selects tasks for this section.
 * @param discard If true, matched tasks are removed from subsequent sections.
 *        Used for exclude-first semantics (e.g. "All tasks except Completed").
 */
@Serializable
@SerialName("Section")
data class Section(val name: String, val order: Int = 0, val selector: Selector, val discard: Boolean = false)

/**
 * The top-level agenda definition — a named collection of sections.
 *
 * Produced by [AgendaPresets] factory functions or directly by the agenda DSL builder.
 *
 * @param title Human-readable title shown in the top bar (e.g. "Inbox", "Upcoming").
 * @param sections Ordered list of [Section] definitions.
 * @param layout How to render the sections.
 */
@Serializable
@SerialName("AgendaDefinition")
data class AgendaDefinition(
    val title: String,
    val sections: List<Section>,
    val layout: AgendaLayout = AgendaLayout.ListFlat,
    @Transient
    val transformers: List<SelectorTransformer> = emptyList(),
)

// ─── DSL builders ─────────────────────────────────────────────────────────────

/**
 * Builds an [AgendaDefinition] using a DSL with receivers.
 *
 * Example:
 * ```
 * agenda("My Agenda") {
 *   section("Today") {
 *     selector = Selector.DateBucket(RelativeBucket.Today)
 *   }
 *   section("Overdue") {
 *     selector = Selector.Overdue
 *     discard = true  // remove from later sections
 *   }
 * }
 * ```
 */
fun agenda(
    title: String,
    layout: AgendaLayout = AgendaLayout.ListFlat,
    transformers: List<SelectorTransformer> = emptyList(),
    block: AgendaScope.() -> Unit,
): AgendaDefinition {
    val scope = AgendaScope().apply(block)
    return AgendaDefinition(
        title = title,
        sections = scope.sections,
        layout = layout,
        transformers = transformers,
    )
}

@AgendaDslMarker
class AgendaScope {
    internal val sections = mutableListOf<Section>()

    fun section(
        name: String,
        selector: Selector? = null,
        order: Int = sections.size,
        discard: Boolean = false,
        block: SectionScope.() -> Unit = {},
    ) {
        val scope = SectionScope().apply(block)
        val effectiveSelector = selector ?: scope.selector
        checkNotNull(effectiveSelector) {
            "Section '$name' has no selector — pass as parameter or assign inside block"
        }
        sections.add(Section(name, order, effectiveSelector, discard))
    }
}

@AgendaDslMarker
class SectionScope {
    var selector: Selector? = null
}

@DslMarker
annotation class AgendaDslMarker
