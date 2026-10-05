package com.singularity.todo.feature.genui.catalog

import kotlinx.serialization.json.JsonElement

/**
 * The declared vocabulary of a GenUI surface: which components may be used, what each one
 * accepts, and which functions may be called from inside a string.
 *
 * This is the contract between the model and the client, and it is declared **once**. Three
 * things are derived from it — the instructions the model reads, the validation applied to what
 * the model sends back, and a machine-readable schema an agent can check itself against — because
 * three hand-maintained lists would disagree with each other within about two changes, and only one
 * of the three could be tested.
 *
 * The previous implementation derived a bare list of names by reflecting over the node type
 * hierarchy. That list could not say what a `button` accepts, and a type that forgot its
 * serialization name dropped out of it silently — and a set produced by reflection can change
 * with the build's shrinking configuration. Neither failure was visible from a test.
 *
 * @see singularityCatalog for the declaration this client renders.
 */
interface A2uiCatalog {
    /** Identifier of the catalog, and of the wire dialect it describes. */
    val id: String

    /**
     * Version of the message schema this catalog describes.
     *
     * Bumped only for a change an older client cannot read. It is not the catalog's own version —
     * adding a component is additive and leaves this alone, because an older client rejects the
     * unknown name and reports it, which is the behaviour the correction loop depends on.
     */
    val protocolVersion: Int

    /** Component schemas by wire name (the `kind` discriminator value). */
    val components: Map<String, A2uiComponentSchema>

    /** Function schemas by name, callable from inside a string template. */
    val functions: Map<String, A2uiFunctionSchema>

    /**
     * This catalogue as a JSON Schema.
     *
     * Part of the interface rather than a helper, because the export is what lets a model check its
     * own output against the same contract the client validates against. Two descriptions of the
     * vocabulary — one for the client and one for the model — would be two contracts.
     */
    fun jsonSchema(): kotlinx.serialization.json.JsonObject

    /** Returns the schema for [name], or null when the catalog does not declare it. */
    fun component(name: String): A2uiComponentSchema? = components[name]

    /** Returns true when [childKind] may appear directly inside [parentKind]. */
    fun allowsChild(parentKind: String, childKind: String): Boolean {
        val parent: A2uiComponentSchema = components[parentKind] ?: return false
        if (childKind !in components) return false
        val allowed: Set<String>? = components[childKind]?.allowedParents
        return allowed == null || parentKind in allowed
    }
}

/**
 * The value shape of a component property.
 *
 * Declared rather than inferred from the node type hierarchy, because the point of the check is to
 * happen *before* a value has been turned into a node: a model that sends `"level": "two"` should
 * be told which property was wrong, not have a default silently substituted for it.
 */
enum class A2uiType {
    STRING,
    INT,
    BOOL,
    NODE_REF,
    NODE_REFS,
    TONE,
    DIRECTION,
    PATH,
    JSON,
}

/**
 * One property of a component.
 *
 * @param enumValues legal values for an enumerated type; empty for every other type. Kept beside
 *   the type rather than inside it so that a new enumeration is a property of a component and not a
 *   new branch in every validator.
 * @param default the value applied when the model omits an optional property. Present so the
 *   generated schema and the instructions can both state it, instead of each describing the
 *   behaviour differently.
 */
data class A2uiProperty(
    val name: String,
    val type: A2uiType,
    val required: Boolean,
    val description: String,
    val enumValues: List<String> = emptyList(),
    val default: JsonElement? = null,
)

/**
 * Where a component's children live, which is what distinguishes a container from a leaf.
 *
 * This is the check that catches a `modal` placed inside a `list` and a `row` given a second
 * child — both of which the renderer cannot express, and both of which used to reach the renderer
 * and be dropped there, after the model had been told nothing.
 */
sealed interface ChildSlot {
    /** A leaf: the component takes no children at all. */
    data object None : ChildSlot

    /** A container with a `children` array of references. [min] is the least that is meaningful. */
    data class Many(val min: Int = 0) : ChildSlot

    /** A container with a single `child` reference. */
    data object ExactlyOne : ChildSlot

    /**
     * A container whose children arrive inside a named array of objects, each naming its own
     * single child — the tab strip, where every tab owns one panel.
     */
    data class ExactlyOneEach(val arrayProperty: String) : ChildSlot
}

/**
 * One component of the declared vocabulary.
 *
 * @param allowedParents the components that may contain this one, or null for "any". Declared on
 *   the child rather than on the parent because the rule that matters is about the component being
 *   inserted: a modal is the same modal wherever it goes, and a list of allowed parents per
 *   container would make adding a container mean re-stating every restriction.
 */
data class A2uiComponentSchema(
    val name: String,
    val description: String,
    val properties: List<A2uiProperty>,
    val childSlot: ChildSlot = ChildSlot.None,
    val allowedParents: Set<String>? = null,
) {
    /** Returns the schema of [propertyName], or null when the component does not declare it. */
    fun property(propertyName: String): A2uiProperty? =
        properties.firstOrNull { it.name == propertyName }

    /** Returns the names of every required property, in declaration order. */
    val requiredProperties: List<String> get() = properties.filter { it.required }.map { it.name }
}

/**
 * A function the model may call from inside a string template.
 *
 * Declared in the catalog rather than in the implementation so that the instructions and the
 * registry cannot list different functions: a function the model was told about but that the
 * client does not have is an error on every surface that uses it.
 *
 * @param params positional parameter names, in order. Named parameters are permitted for any of
 *   them; the list exists so the model knows how many arguments to pass and in what order.
 */
data class A2uiFunctionSchema(
    val name: String,
    val description: String,
    val params: List<String>,
    val returns: A2uiType,
) {
    /** Returns the signature as it appears in the generated instructions. */
    val signature: String get() = "$name(${params.joinToString(", ")}) -> ${returns.name.lowercase()}"
}
