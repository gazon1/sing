package com.singularity.todo.feature.genui.catalog

import com.singularity.todo.feature.genui.schema.UiPath
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

/**
 * A node in a GenUI declarative UI surface.
 * Each variant maps to a JSON `kind` discriminator for polymorphic serialization.
 *
 * @see BasicCatalog for the list of allowed kinds.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface UiNode {

    @Serializable @SerialName("text")
    data class Text(
        val value: String,
        val tone: Tone = Tone.Default,
    ) : UiNode

    @Serializable @SerialName("heading")
    data class Heading(
        val text: String,
        val level: Int = 2,
    ) : UiNode

    @Serializable @SerialName("button")
    data class Button(
        val label: String,
        val action: String? = null,
        val data: JsonObject? = null,
    ) : UiNode

    @Serializable @SerialName("column")
    data class Column(
        val children: List<NodeRef>,
    ) : UiNode

    @Serializable @SerialName("row")
    data class Row(
        val children: List<NodeRef>,
    ) : UiNode

    @Serializable @SerialName("card")
    data class Card(
        val child: NodeRef,
    ) : UiNode

    @Serializable @SerialName("list")
    data class ListView(
        val children: List<NodeRef>,
        val direction: Direction = Direction.Vertical,
    ) : UiNode

    @Serializable @SerialName("divider")
    data object Divider : UiNode

    @Serializable @SerialName("badge")
    data class Badge(
        val text: String,
        val tone: Tone = Tone.Neutral,
    ) : UiNode

    @Serializable @SerialName("text_field")
    data class TextField(
        val label: String,
        val path: UiPath,
        val initial: String = "",
    ) : UiNode

    @Serializable @SerialName("checkbox")
    data class Checkbox(
        val label: String,
        val path: UiPath,
        val initial: Boolean = false,
    ) : UiNode

    @Serializable @SerialName("tabs")
    data class Tabs(
        val tabs: List<Tab>,
    ) : UiNode

    @Serializable @SerialName("icon")
    data class Icon(
        val name: String,
    ) : UiNode

    @Serializable @SerialName("modal")
    data class Modal(
        val child: NodeRef,
        val openPath: UiPath,
    ) : UiNode

    // ─── Shared enums ───────────────────────────────────────────────────────

    @Serializable
    enum class Tone { Default, Neutral, Positive, Warning, Error }

    @Serializable
    enum class Direction { Vertical, Horizontal }

    @Serializable
    data class Tab(
        val title: String,
        val child: NodeRef,
    ) : UiNode
}

/** Reference to a node by its string id. */
@JvmInline
@Serializable
value class NodeRef(val id: String)
