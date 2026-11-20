package com.singularity.todo.mcp.introspection

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.koin.core.Koin
import org.koin.core.context.GlobalContext

internal class ScreenInspectorTools(private val koinProvider: () -> Koin = { GlobalContext.get() }) : AutoCloseable {
    @Volatile
    private var runtimeRef: ScreenInspectorRuntime? = null

    private val runtime: ScreenInspectorRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ScreenInspectorRuntime(koinProvider()).also { created ->
            runtimeRef = created
            runCatching {
                Runtime.getRuntime().addShutdownHook(
                    Thread {
                        runCatching { created.close() }
                    },
                )
            }
        }
    }

    private val json =
        Json {
            prettyPrint = false
            encodeDefaults = true
            explicitNulls = false
        }

    // One registration block per tool: the schema and handler read top to bottom.
    @Suppress("LongMethod", "ThrowsCount")
    fun register(server: Server) {
        registerTool(
            server,
            "screen_list",
            "List supported introspectable screens and their parameter schemas.",
            emptySchema(),
        ) { _ ->
            ok(runtime.capabilities())
        }
        registerTool(server, "screen_sessions", "List open screen inspector sessions.", emptySchema()) { _ ->
            ok(runtime.sessions())
        }
        registerTool(
            server,
            "screen_open",
            "Open an introspectable screen session and return its initial snapshot and actions.",
            schema(
                required = listOf("screen"),
                properties =
                    mapOf(
                        "screen" to stringProp("Screen id, e.g. tasks.task-detail."),
                        "params" to objProp("Screen parameters object."),
                    ),
            ),
        ) { request ->
            val screen = request.requireString("screen")
            val paramsElement = request.arguments?.get("params")
            val params =
                when (paramsElement) {
                    null -> {
                        JsonObject(emptyMap())
                    }

                    is JsonObject -> {
                        paramsElement
                    }

                    else -> {
                        throw InspectorException(
                            InspectorErrorCode.INVALID_ARGUMENTS,
                            "Field 'params' must be a JSON object.",
                        )
                    }
                }
            ok(runtime.open(screen, params))
        }
        registerTool(
            server,
            "screen_describe",
            "Describe the current or specified screen session, including actions and schemas.",
            sessionSchema(),
        ) { request ->
            ok(runtime.describe(request.optString("sessionId")))
        }
        registerTool(
            server,
            "screen_snapshot",
            "Read the allowlisted snapshot of the current or specified screen session.",
            sessionSchema(),
        ) { request ->
            ok(runtime.snapshot(request.optString("sessionId")))
        }
        registerTool(
            server,
            "screen_actions",
            "List currently available actions and exact input schemas for a screen session.",
            sessionSchema(),
        ) { request ->
            ok(runtime.actions(request.optString("sessionId")))
        }
        registerTool(
            server,
            "screen_invoke",
            "Invoke a named screen action. Returns operation id and observed status (accepted/completed/failed).",
            schema(
                required = listOf("action"),
                properties =
                    mapOf(
                        "sessionId" to stringProp("Target session id. Optional if only one session is open."),
                        "action" to stringProp("Action name from screen_actions."),
                        "input" to objProp("Action input object matching the published schema."),
                    ),
            ),
        ) { request ->
            val action = request.requireString("action")
            val inputElement = request.arguments?.get("input")
            val input =
                when (inputElement) {
                    null -> {
                        JsonObject(emptyMap())
                    }

                    is JsonObject -> {
                        inputElement
                    }

                    else -> {
                        throw InspectorException(
                            InspectorErrorCode.INVALID_ARGUMENTS,
                            "Field 'input' must be a JSON object.",
                        )
                    }
                }
            ok(runtime.invoke(request.optString("sessionId"), action, input))
        }
        registerTool(
            server,
            "screen_wait",
            "Wait for a screen operation to complete/fail/cancel/timeout and return the latest snapshot.",
            schema(
                required = listOf("operationId"),
                properties =
                    mapOf(
                        "sessionId" to stringProp("Target session id. Optional if only one session is open."),
                        "operationId" to stringProp("Operation id returned by screen_invoke."),
                        "timeoutMs" to intProp("Wait timeout in milliseconds. Default 30000."),
                    ),
            ),
        ) { request ->
            val opId = request.requireString("operationId")
            val timeoutElement = request.arguments?.get("timeoutMs")
            val timeout =
                when (timeoutElement) {
                    null -> {
                        30_000L
                    }

                    is kotlinx.serialization.json.JsonPrimitive -> {
                        if (timeoutElement.isString || timeoutElement.booleanOrNull != null) {
                            throw InspectorException(
                                InspectorErrorCode.INVALID_ARGUMENTS,
                                "timeoutMs must be a non-negative integer.",
                            )
                        }
                        val value =
                            timeoutElement.longOrNull
                                ?: throw InspectorException(
                                    InspectorErrorCode.INVALID_ARGUMENTS,
                                    "timeoutMs must be a non-negative integer.",
                                )
                        value
                    }

                    else -> {
                        throw InspectorException(
                            InspectorErrorCode.INVALID_ARGUMENTS,
                            "timeoutMs must be a non-negative integer.",
                        )
                    }
                }
            if (timeout < 0L) {
                throw InspectorException(
                    InspectorErrorCode.INVALID_ARGUMENTS,
                    "timeoutMs must be a non-negative integer.",
                )
            }
            ok(runtime.wait(request.optString("sessionId"), opId, timeout))
        }
        registerTool(
            server,
            "screen_cancel",
            "Cancel a pending screen operation if possible. Dispatched screen actions cannot be safely stopped; returns unsupported while in-flight.",
            schema(
                required = listOf("operationId"),
                properties =
                    mapOf(
                        "sessionId" to stringProp("Target session id. Optional if only one session is open."),
                        "operationId" to stringProp("Operation id returned by screen_invoke."),
                    ),
            ),
        ) { request ->
            ok(runtime.cancel(request.optString("sessionId"), request.requireString("operationId")))
        }
        registerTool(server, "screen_close", "Close a screen inspector session.", sessionSchema()) { request ->
            ok(runtime.close(request.optString("sessionId")))
        }
    }

    // The MCP boundary must turn anything a tool throws into a structured error payload.
    @Suppress("TooGenericExceptionCaught")
    private fun registerTool(
        server: Server,
        name: String,
        description: String,
        inputSchema: ToolSchema,
        handler: suspend (CallToolRequest) -> JsonObject,
    ) {
        server.addTool(
            name,
            description,
            inputSchema,
            "",
            null,
            null,
            null,
            JsonObject(emptyMap()),
        ) { request ->
            val payload =
                try {
                    handler(request)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: InspectorException) {
                    e.toJson()
                } catch (e: Exception) {
                    err(InspectorErrorCode.INTERNAL.wire, e.message ?: "Screen inspector tool failed.")
                }
            val text = json.encodeToString(JsonElement.serializer(), payload)
            val isError = payload["ok"]?.jsonPrimitive?.contentOrNull == "false"
            CallToolResult(
                content = listOf(TextContent(text = text)),
                structuredContent = payload,
                isError = isError,
            )
        }
    }

    private fun sessionSchema(): ToolSchema =
        schema(
            properties =
                mapOf(
                    "sessionId" to stringProp("Target session id. Optional if only one session is open."),
                ),
        )

    private fun emptySchema(): ToolSchema = schema(properties = emptyMap())

    private fun schema(
        required: List<String> = emptyList(),
        properties: Map<String, JsonObject>,
    ): ToolSchema =
        ToolSchema(
            schema = null as String?,
            properties = JsonObject(properties),
            required = required,
            defs = null as JsonObject?,
        )

    private fun stringProp(description: String): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("description", description)
        }

    private fun objProp(description: String): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("description", description)
        }

    private fun intProp(description: String): JsonObject =
        buildJsonObject {
            put("type", "integer")
            put("description", description)
        }

    private fun CallToolRequest.requireString(name: String): String {
        val element =
            arguments?.get(name)
                ?: throw InspectorException(
                    InspectorErrorCode.INVALID_ARGUMENTS,
                    "Missing required argument '$name'.",
                )
        val primitive = element as? kotlinx.serialization.json.JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw InspectorException(
                InspectorErrorCode.INVALID_ARGUMENTS,
                "Argument '$name' must be a JSON string.",
            )
        }
        return primitive.content
    }

    private fun CallToolRequest.optString(name: String): String? {
        val element = arguments?.get(name) ?: return null
        val primitive = element as? kotlinx.serialization.json.JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw InspectorException(
                InspectorErrorCode.INVALID_ARGUMENTS,
                "Argument '$name' must be a JSON string.",
            )
        }
        return primitive.content
    }

    override fun close() {
        runtimeRef?.close()
    }
}
