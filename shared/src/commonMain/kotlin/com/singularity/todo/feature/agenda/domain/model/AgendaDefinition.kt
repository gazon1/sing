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
 * @param id Stable identifier for this section. Used to restore a section's prefill draft.
 * @param name Display name of the section (e.g. "Today", "Overdue", "No Date").
 * @param order Sorting weight; sections are sorted ascending by this value when
 *        rendering an agenda with multiple sections.
 * @param selector The predicate that selects tasks for this section.
 * @param discard If true, matched tasks are removed from subsequent sections.
 *        Used for exclude-first semantics (e.g. "All tasks except Completed").
 * @param prefill Default values pre-filled when the user taps '+' in this section.
 */
@Serializable
@SerialName("Section")
data class Section(
    // Nullable to support deserialization of saved agendas created before `id` existed.
    // Use `effectiveId` to access the guaranteed-non-null identifier.
    val id: String? = null,
    val name: String,
    val order: Int = 0,
    val selector: Selector,
    val discard: Boolean = false,
    val prefill: SectionPrefill? = null,
) {
    /**
     * Guaranteed-non-null stable id for this section.
     * For sections loaded from legacy saved agendas (where `id == null`), derives a
     * stable id from the section name so the `+` button prefill key is deterministic.
     */
    val effectiveId: String get() = id ?: name.lowercase()
        .replace(" ", "_")
        .replace(Regex("[^a-z0-9_]"), "")
}

/**
 * Pre-fill values for a task created via the '+' button in an agenda section.
 * Stored in [com.singularity.todo.core.draft.DraftStore] before navigating to the
 * create screen, and read back by [com.singularity.todo.feature.tasks.presentation.screen.TaskCreateScreen].
 *
 * @param sectionId The id of the section that initiated the create.
 * @param title Optional title pre-filled from the section context.
 * @param dueDate Optional *absolute* due date. Use this only when the date is a
 *   fixed point in the definition — which in practice is never, since a saved
 *   view is a template that outlives the day it was written. For anything
 *   date-bucketed use [relativeDueDate], resolved against today at the moment
 *   the user taps '+'.
 * @param relativeDueDate Date bucket resolved at tap time. [AgendaViewModel]
 *   takes the range's `from` as the due date, so "This Week" prefills the start
 *   of the current week and "This Month" the first of the month.
 */
@Serializable
data class SectionPrefill(
    val sectionId: String,
    val title: String? = null,
    val dueDate: kotlinx.datetime.LocalDate? = null,
    val relativeDueDate: RelativeBucket? = null,
)

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
        id: String,
        name: String = id,
        selector: Selector? = null,
        order: Int = sections.size,
        discard: Boolean = false,
        prefill: SectionPrefill? = null,
        block: SectionScope.() -> Unit = {},
    ) {
        val scope = SectionScope().apply(block)
        val effectiveSelector = selector ?: scope.selector
        checkNotNull(effectiveSelector) {
            "Section '$name' has no selector — pass as parameter or assign inside block"
        }
        sections.add(Section(id, name, order, effectiveSelector, discard, prefill))
    }
}

@AgendaDslMarker
class SectionScope {
    var selector: Selector? = null
}

@DslMarker
annotation class AgendaDslMarker
