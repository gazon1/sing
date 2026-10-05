package com.singularity.todo.feature.genui.function

import com.singularity.todo.feature.genui.schema.DataModel
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonElement

/**
 * What a client function is allowed to know.
 *
 * Today is passed in rather than read from a clock, which is the rule the project's date
 * formatters already follow for the same reason: a function that reads the clock is a function
 * whose output changes with when it was called, and that cannot be tested by asserting on a value.
 */
data class A2uiFunctionContext(val today: LocalDate, val dataModel: DataModel)

/**
 * A function a model may call from inside a string.
 *
 * Pure and total by contract: no IO, no suspending, no throwing. Every entry is a promise that the
 * client can compute this correctly on the user's device, and a model is entitled to rely on it —
 * which is precisely why the catalogue lists so few of them.
 */
fun interface A2uiFunction {
    /** Returns null when the arguments cannot be used, so the caller can report rather than print. */
    fun call(args: List<JsonElement>, ctx: A2uiFunctionContext): JsonElement?
}
