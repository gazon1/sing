package com.singularity.todo.feature.genui.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Exports an [A2uiCatalog] as JSON Schema.
 *
 * This is the same declaration in a form a model can check its own output against, which is the
 * point of having one declaration: the pre-send check and the client-side check are the same
 * contract, and a divergence between them would mean the model is being asked to satisfy a
 * specification the client does not use.
 *
 * Built explicitly rather than by reflection or by serializing the node types, for the same reason
 * the catalog itself is: a schema that cannot be read in the same file as the thing it describes
 * is a schema that drifts.
 *
 * Deterministic — components and properties are emitted in sorted order, so regenerating an
 * unchanged catalog reproduces the same bytes and a diff means a real change.
 */
object CatalogJson {

    /** Renders [catalog] as a JSON Schema document. */
    fun toJsonSchema(catalog: A2uiCatalog): JsonObject = buildJsonObject {
        put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        put("catalogId", catalog.id)
        put("protocolVersion", catalog.protocolVersion)
        put("title", "A2UI component catalog for ${catalog.id}")
        put(
            "description",
            "Validate every component before sending it. Anything not described here is rejected " +
                "by the client.",
        )
        put("functions", functionsSchema(catalog))
        put("anyComponent", anyComponent(catalog))
        put("\$defs", definitions(catalog))
    }

    private fun functionsSchema(catalog: A2uiCatalog): JsonObject = buildJsonObject {
        put("description", "Functions callable from inside a string as \${fn(arg)}.")
        put(
            "names",
            buildJsonArray { catalog.functions.keys.sorted().forEach { add(JsonPrimitive(it)) } },
        )
    }

    private fun anyComponent(catalog: A2uiCatalog): JsonObject = buildJsonObject {
        put("description", "One component: an id, a declared kind, and that kind's properties.")
        put("type", "object")
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("id"))
                add(JsonPrimitive("kind"))
            },
        )
        put(
            "properties",
            buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
                put(
                    "kind",
                    buildJsonObject {
                        put("type", "string")
                        put("enum", kindsArray(catalog))
                    },
                )
            },
        )
        put(
            "oneOf",
            buildJsonArray {
                catalog.components.keys.sorted().forEach { name: String ->
                    add(buildJsonObject { put("\$ref", "#/\$defs/$name") })
                }
            },
        )
    }

    private fun definitions(catalog: A2uiCatalog): JsonObject = buildJsonObject {
        catalog.components.keys.sorted().forEach { name: String ->
            put(name, componentSchema(catalog.component(name) ?: return@forEach))
        }
    }

    private fun componentSchema(schema: A2uiComponentSchema): JsonObject = buildJsonObject {
        put("type", "object")
        put("description", schema.description)
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("id"))
                add(JsonPrimitive("kind"))
                schema.requiredProperties.forEach { add(JsonPrimitive(it)) }
            },
        )
        put(
            "properties",
            buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
                put("kind", buildJsonObject { put("const", schema.name) })
                schema.properties.forEach { property: A2uiProperty ->
                    put(property.name, propertySchema(property))
                }
            },
        )
        val parents: Set<String>? = schema.allowedParents
        if (parents != null) {
            put(
                "parentKinds",
                buildJsonArray {
                    parents.sorted().forEach { add(JsonPrimitive(it)) }
                },
            )
        }
    }

    private fun propertySchema(property: A2uiProperty): JsonObject = buildJsonObject {
        put("description", property.description)
        when (property.type) {
            A2uiType.STRING, A2uiType.JSON, A2uiType.PATH, A2uiType.NODE_REF -> put("type", "string")

            A2uiType.INT -> put("type", "integer")

            A2uiType.BOOL -> put("type", "boolean")

            A2uiType.NODE_REFS -> {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
            }

            A2uiType.TONE, A2uiType.DIRECTION -> Unit
        }
        if (property.enumValues.isNotEmpty()) {
            put("enum", buildJsonArray { property.enumValues.forEach { add(JsonPrimitive(it)) } })
        }
        val default: JsonElement? = property.default
        if (default != null) put("default", default)
    }

    private fun kindsArray(catalog: A2uiCatalog): JsonArray = buildJsonArray {
        catalog.components.keys.sorted().forEach { add(JsonPrimitive(it)) }
    }
}
