package com.singularity.todo.feature.genui.parser

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses A2UI v0.9 JSON-Lines into [UiEvent].
 *
 * Each line of the input stream is one JSON object with a single top-level key
 * naming the operation. Example:
 * ```
 * {"createSurface":{"surfaceId":"s1","rootId":"r1","components":[...]}}
 * ```
 *
 * This parser is **pure** — it has no side effects and no dependencies.
 * Pass a line of JSON, get a [UiEvent] (or `null` for blank lines).
 */
class A2uiParser(private val json: Json = defaultJson) {

    fun parseLine(line: String): UiEvent? {
        if (line.isBlank()) return null
        val element = runCatching { json.parseToJsonElement(line) }.getOrNull() ?: return null
        val obj = element.jsonObject
        val op = obj.keys.firstOrNull() ?: return null
        val data = obj[op]?.jsonObject ?: return null

        return when (op) {
            "createSurface" -> parseCreateSurface(data)
            "updateComponents" -> parseUpdateComponents(data)
            "updateData" -> parseUpdateData(data)
            "deleteSurface" -> parseDeleteSurface(data)
            else -> null
        }
    }

    /**
     * Transforms a flow of raw JSON strings into a flow of parsed [UiEvent].
     * Line-by-line: each string element should be one JSON object.
     */
    fun parseStream(lines: kotlinx.coroutines.flow.Flow<String>): kotlinx.coroutines.flow.Flow<UiEvent> =
        kotlinx.coroutines.flow.flow {
            lines.collect { line ->
                parseLine(line)?.let { emit(it) }
            }
        }

    // ─── Private parsing helpers ────────────────────────────────────────────

    private fun parseCreateSurface(data: JsonObject): UiEvent? {
        val surfaceIdStr = data["surfaceId"]?.jsonPrimitive?.content ?: return null
        val rootIdStr = data["rootId"]?.jsonPrimitive?.content ?: return null
        val componentsArray: JsonArray = data["components"]?.jsonArray ?: return null
        val components = mutableMapOf<String, UiNode>()

        for (element: JsonElement in componentsArray) {
            val comp: JsonObject = element.jsonObject
            val id: String = comp["id"]?.jsonPrimitive?.content ?: continue
            val kind: String = comp["kind"]?.jsonPrimitive?.content ?: continue
            val node: UiNode = parseNode(kind, comp) ?: continue
            components[id] = node
        }

        return UiEvent.CreateSurface(
            surfaceId = SurfaceId(surfaceIdStr),
            rootId = NodeRef(rootIdStr),
            components = components,
        )
    }

    private fun parseUpdateComponents(data: JsonObject): UiEvent? {
        val surfaceIdStr = data["surfaceId"]?.jsonPrimitive?.content ?: return null
        val componentsArray: JsonArray = data["components"]?.jsonArray ?: return null
        val components = mutableMapOf<String, UiNode>()

        for (element: JsonElement in componentsArray) {
            val comp: JsonObject = element.jsonObject
            val id: String = comp["id"]?.jsonPrimitive?.content ?: continue
            val kind: String = comp["kind"]?.jsonPrimitive?.content ?: continue
            val node: UiNode = parseNode(kind, comp) ?: continue
            components[id] = node
        }

        return UiEvent.UpdateComponents(
            surfaceId = SurfaceId(surfaceIdStr),
            components = components,
        )
    }

    private fun parseUpdateData(data: JsonObject): UiEvent? {
        val surfaceIdStr = data["surfaceId"]?.jsonPrimitive?.content ?: return null
        val pathStr: String = data["path"]?.jsonPrimitive?.content ?: return null
        val value: JsonElement = data["value"] ?: return null
        return UiEvent.UpdateData(
            surfaceId = SurfaceId(surfaceIdStr),
            path = UiPath.parse(pathStr),
            value = value,
        )
    }

    private fun parseDeleteSurface(data: JsonObject): UiEvent? {
        val surfaceIdStr = data["surfaceId"]?.jsonPrimitive?.content ?: return null
        return UiEvent.DeleteSurface(surfaceId = SurfaceId(surfaceIdStr))
    }

    private fun parseNode(kind: String, obj: JsonObject): UiNode? = when (kind) {
        "text" -> obj["value"]?.jsonPrimitive?.content?.let { UiNode.Text(it, toneOf(obj["tone"])) }

        "heading" -> obj["text"]?.jsonPrimitive?.content?.let { t ->
            UiNode.Heading(t, obj["level"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2)
        }

        "button" -> obj["label"]?.jsonPrimitive?.content?.let { l ->
            UiNode.Button(
                label = l,
                action = obj["action"]?.jsonPrimitive?.content,
                data = obj["data"]?.jsonObject,
            )
        }

        "column" -> UiNode.Column(childrenOf(obj["children"]))

        "row" -> UiNode.Row(childrenOf(obj["children"]))

        "card" -> obj["child"]?.jsonPrimitive?.content?.let { UiNode.Card(NodeRef(it)) }

        "list" -> UiNode.ListView(
            children = childrenOf(obj["children"]),
            direction = directionOf(obj["direction"]),
        )

        "divider" -> UiNode.Divider

        "badge" -> obj["text"]?.jsonPrimitive?.content?.let { t ->
            UiNode.Badge(t, toneOf(obj["tone"]))
        }

        "text_field" -> obj["label"]?.jsonPrimitive?.content?.let { l ->
            UiNode.TextField(
                label = l,
                path = pathOf(obj["path"]),
                initial = obj["initial"]?.jsonPrimitive?.content ?: "",
            )
        }

        "checkbox" -> obj["label"]?.jsonPrimitive?.content?.let { l ->
            UiNode.Checkbox(
                label = l,
                path = pathOf(obj["path"]),
                initial = obj["initial"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            )
        }

        "tabs" -> {
            val tabsArray: JsonArray = obj["tabs"]?.jsonArray ?: return null
            val tabs: List<UiNode.Tab> = tabsArray.mapNotNull { el: JsonElement ->
                val t: JsonObject = el.jsonObject
                val title: String = t["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val child: String = t["child"]?.jsonPrimitive?.content ?: return@mapNotNull null
                UiNode.Tab(title, NodeRef(child))
            }
            UiNode.Tabs(tabs)
        }

        "icon" -> obj["name"]?.jsonPrimitive?.content?.let { UiNode.Icon(it) }

        "modal" -> obj["child"]?.jsonPrimitive?.content?.let { child ->
            UiNode.Modal(NodeRef(child), pathOf(obj["openPath"]))
        }

        else -> null
    }

    private fun childrenOf(el: JsonElement?): List<NodeRef> {
        val arr: JsonArray = el?.jsonArray ?: return emptyList()
        return arr.mapNotNull { it.jsonPrimitive.content }.map { NodeRef(it) }
    }

    private fun pathOf(el: JsonElement?): UiPath = el?.jsonPrimitive?.content?.let { UiPath.parse(it) } ?: UiPath.Root

    private fun toneOf(el: JsonElement?): UiNode.Tone = el?.jsonPrimitive?.content?.let { s ->
        runCatching { UiNode.Tone.valueOf(s) }.getOrNull()
    } ?: UiNode.Tone.Default

    private fun directionOf(el: JsonElement?): UiNode.Direction = el?.jsonPrimitive?.content?.let { s ->
        runCatching { UiNode.Direction.valueOf(s) }.getOrNull()
    } ?: UiNode.Direction.Vertical

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
