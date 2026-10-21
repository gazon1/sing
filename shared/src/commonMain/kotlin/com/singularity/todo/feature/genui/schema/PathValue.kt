package com.singularity.todo.feature.genui.schema

import kotlinx.serialization.json.JsonElement

/**
 * A value at a path, and the count of the times the server replaced it.
 *
 * The two are one thing: a caller that needs the count needs the value it goes with,
 * and reading them apart can pair a value with a count that belongs to another write.
 *
 * This is the GenUI's own pair rather than the field contract's `Revisioned`, because
 * the schema layer must not depend on the UI layer's types — the GenUI's data model
 * knows nothing about text fields. The render layer turns this into the field
 * contract's source once, at the boundary.
 *
 * @param value the value at the path, or null when the model holds nothing there.
 * @param revision how many times the server replaced that value. Zero until the server
 *   writes to that path for the first time.
 */
data class PathValue(val value: JsonElement?, val revision: Long)
