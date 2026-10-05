package com.singularity.todo.feature.genui.core

import com.singularity.todo.feature.genui.catalog.A2uiCatalog
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.parser.UiEvent
import com.singularity.todo.feature.genui.surface.SurfaceId

/** A message that passed parsing, and the components that survived it. */
data class ValidatedEvent(val event: UiEvent, val errors: List<A2uiError>)

/**
 * Checks the relationships *between* components, which a per-component decoder cannot see.
 *
 * The node factory knows that a `button` needs a label. This class knows that a `column` may not
 * contain a `modal`, that a reference to an id nothing defined will stay unresolved, and that two
 * components pointing at each other would render forever. Each of those reached the renderer
 * before, where the renderer's only response was to draw nothing and log a line — by which point
 * the model had already been told the surface was fine.
 *
 * Every check here is component-level: the offending component is dropped and the rest of the
 * surface survives. A surface that renders nine of ten items and says why is worth more than one
 * that renders nothing because the tenth arrived early.
 */
class A2uiValidator(private val catalog: A2uiCatalog) {

    /**
     * Validates [event] against the components already known for its surface.
     *
     * @param known components the surface already holds, so that a reference to something defined
     *   by an earlier message is resolvable. Streaming means "not yet defined" and "never defined"
     *   are indistinguishable here, which is why an unresolved reference is a warning about one
     *   component rather than a rejection of the message.
     */
    fun validate(event: UiEvent, known: Map<String, UiNode> = emptyMap()): ValidatedEvent {
        val surfaceId: SurfaceId = event.surfaceId()
        val combined: Map<String, UiNode> = known + event.incomingComponents()
        val errors: MutableList<A2uiError> = mutableListOf()

        for ((id, node) in event.incomingComponents()) {
            for (ref in node.childRefs()) {
                val target: UiNode? = combined[ref]
                if (target == null) {
                    errors += unresolved(id, ref, surfaceId)
                    continue
                }
                if (!catalog.allowsChild(node.kind, target.kind)) {
                    errors += invalidChild(id, node.kind, ref, target.kind, catalog, surfaceId)
                }
            }
        }
        for (cycle in findCycles(combined, event.incomingComponents().keys)) {
            errors += cyclic(cycle.last(), cycle, surfaceId)
        }

        if (errors.isEmpty()) return ValidatedEvent(event, emptyList())
        val rejected: Set<String> = errors.flatMap { rejectedIds(it) }.toSet()
        return ValidatedEvent(event.copyWithComponents(event.incomingComponents() - rejected), errors)
    }

    /**
     * Components that point at each other, each returned once.
     *
     * A depth-first walk with a path stack rather than recursion over the whole graph: a surface is
     * small, but a model that emits a hundred chained containers should not be able to turn
     * validation into a stack overflow — which is a crash rather than a rejection, and is exactly
     * the outcome this layer stopped having.
     */
    private fun findCycles(all: Map<String, UiNode>, incoming: Set<String>): List<List<String>> {
        val cycles: MutableList<List<String>> = mutableListOf()
        val seen: MutableSet<String> = mutableSetOf()

        for (start in incoming) {
            if (start in seen) continue
            val path: MutableList<String> = mutableListOf()
            val onPath: MutableSet<String> = mutableSetOf()
            walk(start, all, path, onPath, seen, cycles)
        }
        return cycles
    }
    private fun walk(
        id: String,
        all: Map<String, UiNode>,
        path: MutableList<String>,
        onPath: MutableSet<String>,
        seen: MutableSet<String>,
        cycles: MutableList<List<String>>,
    ) {
        if (id in onPath) {
            val start: Int = path.indexOf(id)
            if (start >= 0) cycles += path.subList(start, path.size).toList()
            return
        }
        if (id in seen) return
        val node: UiNode = all[id] ?: return
        path += id
        onPath += id
        for (ref in node.childRefs()) {
            walk(ref, all, path, onPath, seen, cycles)
        }
        onPath -= id
        path.removeAt(path.size - 1)
        seen += id
    }

    // ─── Checks ────────────────────────────────────────────────────────────

    private fun unresolved(parent: String, ref: String, surfaceId: SurfaceId) = A2uiError(
        code = A2uiErrorCode.UNKNOWN_NODE_REF,
        message = "'$parent' refers to '$ref', which no message has defined. " +
            "It may still arrive; if it does not, this component will not render.",
        pointer = "/components/$parent/children",
        surfaceId = surfaceId,
        severity = A2uiSeverity.ADVISORY,
        componentId = parent,
    )

    /**
     * The child is dropped, not the container.
     *
     * A list that is forbidden a modal is not itself malformed — it is carrying one thing it
     * should not. Dropping the list would take the whole run of items with it, and dropping the
     * modal is exactly what the user would have got by hand.
     */
    private fun invalidChild(
        parent: String,
        parentKind: String,
        ref: String,
        childKind: String,
        catalog: A2uiCatalog,
        surfaceId: SurfaceId,
    ) = A2uiError(
        code = A2uiErrorCode.INVALID_CHILD,
        message = "'$childKind' cannot go inside '$parentKind'.",
        pointer = "/components/$parent/children",
        surfaceId = surfaceId,
        severity = A2uiSeverity.COMPONENT,
        componentId = ref,
        allowed = catalog.components[childKind]?.allowedParents?.sorted().orEmpty(),
    )

    private fun cyclic(closing: String, cycle: List<String>, surfaceId: SurfaceId) = A2uiError(
        code = A2uiErrorCode.CYCLIC_REFERENCE,
        message = "Components reference each other: ${cycle.joinToString(" -> ")}.",
        pointer = "/components/$closing",
        surfaceId = surfaceId,
        severity = A2uiSeverity.COMPONENT,
        componentId = closing,
    )

    /**
     * Which components a rejection takes down with it.
     *
     * Only component-level rejections drop anything. An advisory — a reference that may still
     * arrive — is reported and leaves the surface alone, which is the difference between a surface
     * that fills in as the stream continues and one that empties itself the moment it is incomplete.
     */
    private fun rejectedIds(error: A2uiError): List<String> =
        if (error.severity == A2uiSeverity.COMPONENT) listOfNotNull(error.componentId) else emptyList()
}

private fun UiEvent.surfaceId(): SurfaceId = when (this) {
    is UiEvent.CreateSurface -> surfaceId
    is UiEvent.UpdateComponents -> surfaceId
    is UiEvent.UpdateData -> surfaceId
    is UiEvent.DeleteSurface -> surfaceId
}

private fun UiEvent.incomingComponents(): Map<String, UiNode> = when (this) {
    is UiEvent.CreateSurface -> components
    is UiEvent.UpdateComponents -> components
    is UiEvent.UpdateData, is UiEvent.DeleteSurface -> emptyMap()
}

private fun UiEvent.copyWithComponents(components: Map<String, UiNode>): UiEvent = when (this) {
    is UiEvent.CreateSurface -> copy(components = components)
    is UiEvent.UpdateComponents -> copy(components = components)
    is UiEvent.UpdateData -> this
    is UiEvent.DeleteSurface -> this
}

/** Ids this node points at, whether it has one child, several, or a tab strip. */
internal fun UiNode.childRefs(): List<String> = when (this) {
    is UiNode.Column -> children.map { it.id }

    is UiNode.Row -> children.map { it.id }

    is UiNode.ListView -> children.map { it.id }

    is UiNode.Card -> listOf(child.id)

    is UiNode.Modal -> listOf(child.id)

    is UiNode.Tabs -> tabs.map { it.child.id }

    // Tab is the nested shape inside a tab strip, never a component the model places by id, so it
    // points at its panel rather than being pointed at.
    is UiNode.Tab -> listOf(child.id)

    is UiNode.TaskCard, is UiNode.DueDate, is UiNode.ProjectChip -> emptyList()

    is UiNode.Text, is UiNode.Heading, is UiNode.Badge, is UiNode.Icon, is UiNode.Divider,
    is UiNode.Button, is UiNode.TextField, is UiNode.Checkbox,
    -> emptyList()
}

internal val UiNode.kind: String
    get() = when (this) {
        is UiNode.Text -> "text"
        is UiNode.Heading -> "heading"
        is UiNode.Button -> "button"
        is UiNode.Column -> "column"
        is UiNode.Row -> "row"
        is UiNode.Card -> "card"
        is UiNode.ListView -> "list"
        is UiNode.Divider -> "divider"
        is UiNode.Badge -> "badge"
        is UiNode.TextField -> "text_field"
        is UiNode.Checkbox -> "checkbox"
        is UiNode.Tabs -> "tabs"
        is UiNode.Icon -> "icon"
        is UiNode.Modal -> "modal"
        is UiNode.Tab -> "tab"
        is UiNode.TaskCard -> "task_card"
        is UiNode.DueDate -> "due_date"
        is UiNode.ProjectChip -> "project_chip"
    }
