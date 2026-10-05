package com.singularity.todo.feature.genui.catalog

import kotlinx.serialization.json.JsonObject

/**
 * The catalog this client renders: 14 components and 8 functions.
 *
 * The dialect is private and pinned. It is **not** the A2UI v0.9 wire format, and no part of that
 * format is accepted: no `version` envelope, no `component` discriminator, no `updateDataModel`.
 * The catalog-as-contract idea came from the specification and is worth taking whole; the field
 * names are not, because release-note content is served into the app from outside this repository
 * and a rename is exactly the change that breaks content nobody here can migrate atomically.
 *
 * A future adapter for the real specification belongs beside this declaration and implements the
 * same interface — see `docs/decisions/2026-10-05-genui-catalog-as-contract.md`.
 */
object SingularityCatalog : A2uiCatalog {

    override val id: String = "singularity.todo/genui"

    override val protocolVersion: Int = 1

    override val components: Map<String, A2uiComponentSchema> = componentSchemas()

    override val functions: Map<String, A2uiFunctionSchema> = functionSchemas()

    override fun jsonSchema(): JsonObject = CatalogJson.toJsonSchema(this)
}
