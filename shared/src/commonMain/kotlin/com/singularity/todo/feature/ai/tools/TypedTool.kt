package com.singularity.todo.feature.ai.tools

/**
 * A tool that returns a deserialized object rather than a JSON string.
 *
 * Unlike [SimpleTool] which is designed for the Koog agent executor (which expects
 * `execute(): String` and calls `encodeResultToString` internally), `TypedTool` is
 * intended for direct use by application use cases. The agent continues to receive
 * `SimpleTool` instances — this interface is purely for the use-case layer to
 * eliminate the JSON → String → JSON round-trip.
 *
 * @param I Input type (tool argument)
 * @param O Output type (deserialized result)
 */
interface TypedTool<I, O> {
    suspend fun executeTyped(args: I): O
}
