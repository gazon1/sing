package com.singularity.todo.feature.genui.catalog

import kotlinx.serialization.json.JsonPrimitive

/**
 * The component schemas this client renders.
 *
 * Every name here must have a renderer, and every renderer in the registry must have a name here.
 * A check enforces both directions, because the two failures are silent and opposite: a schema
 * with no renderer produces a surface with a hole in it, and a renderer with no schema is a
 * component the model is never told about and therefore never uses.
 *
 * Properties are declared against the wire, not against the node types. The client accepts a model
 * that spells an optional property differently from the internal representation, and the check
 * that matters — required, correctly typed, legal value — happens before any node exists.
 */
internal fun componentSchemas(): Map<String, A2uiComponentSchema> {
    val schemas: List<A2uiComponentSchema> = listOf(
        textSchema(),
        headingSchema(),
        badgeSchema(),
        iconSchema(),
        dividerSchema(),
        buttonSchema(),
        textFieldSchema(),
        checkboxSchema(),
        columnSchema(),
        rowSchema(),
        listSchema(),
        cardSchema(),
        tabsSchema(),
        modalSchema(),
        taskCardSchema(),
        dueDateSchema(),
        projectChipSchema(),
    )
    return schemas.associateBy { schema: A2uiComponentSchema -> schema.name }
}

// ─── Atoms ──────────────────────────────────────────────────────────────────

private fun textSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "text",
    description = "A run of text.",
    properties = listOf(
        A2uiProperty("value", A2uiType.STRING, true, "The text. May contain `${'$'}{…}` templates."),
        A2uiProperty("tone", A2uiType.TONE, false, "Emphasis.", TONES, JsonPrimitive("Default")),
    ),
)

private fun headingSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "heading",
    description = "A section title.",
    properties = listOf(
        A2uiProperty("text", A2uiType.STRING, true, "The heading text."),
        A2uiProperty(
            "level",
            A2uiType.INT,
            false,
            "1 for the largest, 2 or 3 below it.",
            emptyList(),
            JsonPrimitive(2),
        ),
    ),
)

private fun badgeSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "badge",
    description = "A small label, for a count or a state.",
    properties = listOf(
        A2uiProperty("text", A2uiType.STRING, true, "The label."),
        A2uiProperty("tone", A2uiType.TONE, false, "Emphasis.", TONES, JsonPrimitive("Neutral")),
    ),
)

private fun iconSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "icon",
    description = "A small pictogram, for decoration only.",
    properties = listOf(
        A2uiProperty("name", A2uiType.STRING, true, "Icon name, e.g. check, star, clock."),
    ),
)

private fun dividerSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "divider",
    description = "A horizontal rule between two sections.",
    properties = emptyList(),
)

// ─── Input ──────────────────────────────────────────────────────────────────

private fun buttonSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "button",
    description = "A tappable button. Every button SHOULD carry an action.",
    properties = listOf(
        A2uiProperty("label", A2uiType.STRING, true, "The visible label."),
        A2uiProperty("action", A2uiType.STRING, false, "Id reported to the app when tapped."),
        A2uiProperty(
            "data",
            A2uiType.JSON,
            false,
            "Values reported alongside the action. String values may contain `${'$'}{…}` templates, " +
                "which are read from the data model when the button is pressed — this is how a form " +
                "submits what the user typed.",
        ),
    ),
)

private fun textFieldSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "text_field",
    description = "A single-line text input bound to the data model.",
    properties = listOf(
        A2uiProperty("label", A2uiType.STRING, true, "The field label."),
        A2uiProperty("path", A2uiType.PATH, true, "Data-model path the field reads and writes."),
        A2uiProperty(
            "initial",
            A2uiType.STRING,
            false,
            "Value before any data arrives.",
            emptyList(),
            JsonPrimitive(""),
        ),
    ),
)

private fun checkboxSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "checkbox",
    description = "A boolean input bound to the data model.",
    properties = listOf(
        A2uiProperty("label", A2uiType.STRING, true, "The field label."),
        A2uiProperty("path", A2uiType.PATH, true, "Data-model path the field reads and writes."),
        A2uiProperty(
            "initial",
            A2uiType.BOOL,
            false,
            "Value before any data arrives.",
            emptyList(),
            JsonPrimitive(false),
        ),
    ),
)

// ─── Layout ─────────────────────────────────────────────────────────────────

private fun columnSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "column",
    description = "Stacks children vertically.",
    properties = listOf(
        A2uiProperty("children", A2uiType.NODE_REFS, true, "Ids of the components to stack."),
    ),
    childSlot = ChildSlot.Many(),
)

private fun rowSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "row",
    description = "Places children side by side.",
    properties = listOf(
        A2uiProperty("children", A2uiType.NODE_REFS, true, "Ids of the components to place."),
    ),
    childSlot = ChildSlot.Many(),
)

private fun listSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "list",
    description = "A vertical or horizontal run of items. Good for repeated rows.",
    properties = listOf(
        A2uiProperty("children", A2uiType.NODE_REFS, true, "Ids of the items."),
        A2uiProperty(
            "direction",
            A2uiType.DIRECTION,
            false,
            "Layout direction.",
            DIRECTIONS,
            JsonPrimitive("Vertical"),
        ),
    ),
    childSlot = ChildSlot.Many(),
)

private fun cardSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "card",
    description = "A raised surface around exactly one child.",
    properties = listOf(
        A2uiProperty("child", A2uiType.NODE_REF, true, "Id of the component to wrap."),
    ),
    childSlot = ChildSlot.ExactlyOne,
)

private fun tabsSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "tabs",
    description = "A tab strip; each tab owns exactly one panel.",
    properties = listOf(
        A2uiProperty(
            "tabs",
            A2uiType.JSON,
            true,
            "Array of {title, child} objects, child being a component id.",
        ),
    ),
    childSlot = ChildSlot.ExactlyOneEach(arrayProperty = "tabs"),
)

/**
 * The one component with a placement restriction, and it is worth a comment.
 *
 * A modal renders over the whole surface, so a modal inside another modal, inside a list row, or
 * inside a tab panel opens a surface the user cannot get out of — the outer one is behind the
 * inner one, and dismissing the inner leaves the outer re-appearing over the content. The rule is
 * declared on the child so that the check reads "a modal was placed inside a list" rather than
 * "a list is not allowed to contain modals", which is the direction that stays correct when a
 * second placement-restricted component appears.
 */
private fun modalSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "modal",
    description = "A panel shown over the surface, opened by a data-model path.",
    properties = listOf(
        A2uiProperty("child", A2uiType.NODE_REF, true, "Id of the component to show."),
        A2uiProperty("openPath", A2uiType.PATH, true, "Data-model path; the panel shows while it is true."),
    ),
    childSlot = ChildSlot.ExactlyOne,
    allowedParents = setOf("column", "row", "card"),
)

// ─── Domain components ──────────────────────────────────────────────────────
//
// These read the surface's own data model and nothing else. They exist because the generic
// components can only say "here is a column with text in it", and an agent asked to show three
// tasks will build exactly that unless the vocabulary offers the shape the app already uses.
// They deliberately take no repository: a component that could fetch would make the surface's
// content depend on when it was drawn, and a surface has to be reproducible from its messages.

private fun taskCardSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "task_card",
    description = "One task: a title, an optional due chip and project chip. " +
        "Use for any list of things the user has to do.",
    properties = listOf(
        A2uiProperty("title", A2uiType.STRING, true, "The task title. May contain templates."),
        A2uiProperty("duePath", A2uiType.PATH, false, "Data path to the due date (ISO)."),
        A2uiProperty("projectPath", A2uiType.PATH, false, "Data path to the project name."),
        A2uiProperty("done", A2uiType.BOOL, false, "Whether the task is complete.", emptyList(), JsonPrimitive(false)),
        A2uiProperty("action", A2uiType.STRING, false, "Id reported when the card is tapped."),
    ),
)

private fun dueDateSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "due_date",
    description = "A due date, drawn as 'Today' or 'Tomorrow' when that is what it is.",
    properties = listOf(
        A2uiProperty("path", A2uiType.PATH, false, "Data path to the date. Provide this or value."),
        A2uiProperty("value", A2uiType.STRING, false, "A literal ISO date. Provide this or path."),
        A2uiProperty(
            "style",
            A2uiType.STRING,
            false,
            "How to render it.",
            listOf("relative", "absolute", "both"),
            JsonPrimitive("relative"),
        ),
    ),
)

private fun projectChipSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "project_chip",
    description = "A small chip naming a project.",
    properties = listOf(
        A2uiProperty("name", A2uiType.STRING, false, "Literal project name. Provide this or path."),
        A2uiProperty("path", A2uiType.PATH, false, "Data path to the project name. Provide this or name."),
        A2uiProperty("tone", A2uiType.TONE, false, "Emphasis.", TONES, JsonPrimitive("Neutral")),
    ),
)

// ─── Shared enum values ─────────────────────────────────────────────────────

private val TONES: List<String> = listOf("Default", "Neutral", "Positive", "Warning", "Error")
private val DIRECTIONS: List<String> = listOf("Vertical", "Horizontal")

/**
 * The functions a model may call from inside a string.
 *
 * These exist so the model does not do arithmetic and date arithmetic itself, which it does
 * visibly worse than code does. The list is short deliberately: every entry here is a promise
 * that the client can compute it correctly on the user's device, and a longer list is a longer list
 * of ways for a surface to render a confidently wrong date.
 */
internal fun functionSchemas(): Map<String, A2uiFunctionSchema> {
    val schemas: List<A2uiFunctionSchema> = listOf(
        A2uiFunctionSchema(
            "formatDate",
            "Formats an ISO date or date-time. style: absolute | relative | both.",
            listOf("value", "style"),
            A2uiType.STRING,
        ),
        A2uiFunctionSchema(
            "formatRelative",
            "Formats an ISO date as a distance from today, e.g. Today or in 3 days.",
            listOf("value"),
            A2uiType.STRING,
        ),
        A2uiFunctionSchema(
            "formatNumber",
            "Formats a number with thousand separators.",
            listOf("value"),
            A2uiType.STRING,
        ),
        A2uiFunctionSchema(
            "formatBytes",
            "Formats a byte count as a human-readable size.",
            listOf("value"),
            A2uiType.STRING,
        ),
        A2uiFunctionSchema("upper", "Upper-cases a string.", listOf("value"), A2uiType.STRING),
        A2uiFunctionSchema("lower", "Lower-cases a string.", listOf("value"), A2uiType.STRING),
        A2uiFunctionSchema("join", "Joins values with a separator.", listOf("value", "separator"), A2uiType.STRING),
        A2uiFunctionSchema(
            "default",
            "Falls back to a value when the first is empty.",
            listOf("value", "fallback"),
            A2uiType.STRING,
        ),
        A2uiFunctionSchema(
            "ifEmpty",
            "Returns one of two values depending on emptiness.",
            listOf("value", "whenEmpty", "otherwise"),
            A2uiType.STRING,
        ),
    )
    return schemas.associateBy { schema: A2uiFunctionSchema -> schema.name }
}
