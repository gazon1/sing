@file:Suppress(
    // A CLI process blocks until its single command finishes; runBlocking is the
    // sync-to-async bridge at this boundary, never a parked thread inside the app.
    "NoRunBlocking",
    // A command may throw anything (Clikt, JSON, IO); every failure must become a
    // structured JSON error instead of a stack trace on stdout, so the catch is broad
    // on purpose and the text is reported, not discarded.
    "TooGenericExceptionCaught",
    "SwallowedException",
    // Command and journey parsing is a flat, ordered validation table: one branch per
    // accepted shape, one refusal per malformed input. Splitting it would scatter the
    // order that makes the refusals deterministic.
    "LongMethod",
    "CyclomaticComplexMethod",
    "ThrowsCount",
    "LoopWithTooManyJumpStatements",
)

package com.singularity.todo.mcp.introspection

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.NoSuchSubcommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import com.singularity.todo.feature.profile.ProfileBootstrapper
import com.singularity.todo.mcp.ProfileSelection
import com.singularity.todo.mcp.ServerDependencies
import com.singularity.todo.mcp.ToolServerKoinStartup
import com.singularity.todo.mcp.parseProfileSelection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import java.io.File

private val cliJson =
    Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

internal object ScreenInspectorCli {
    fun run(
        args: List<String>,
        defaultKoinStartup: ToolServerKoinStartup? = null,
        inspectorHostCompiledIn: Boolean,
    ): Int =
        if (inspectorHostCompiledIn) {
            runCompiledIn(args, defaultKoinStartup)
        } else {
            refuseNotCompiledIn()
        }

    // The CLI is a host too, and the build gate applies to it: a release build compiled the
    // inspector out, so no command may reach a dispatch path there. The refusal is checked before
    // help and before the container starts.
    private fun refuseNotCompiledIn(): Int {
        printJson(
            err(
                InspectorErrorCode.UNSUPPORTED.wire,
                "The screen inspector host is not compiled into this build. " +
                    "Build with -Psingularity.inspector.host.enabled=true to use the agent CLI.",
            ),
        )
        return 2
    }

    private fun runCompiledIn(
        args: List<String>,
        defaultKoinStartup: ToolServerKoinStartup?,
    ): Int {
        // `help` is a command in the JSON contract (not Clikt's text help).
        if (args.firstOrNull() == "help") {
            printJson(helpPayload())
            return 0
        }

        // Preserve existing profile-selection contract/ambiguity handling.
        val selection =
            try {
                parseProfileSelection(args.toTypedArray())
            } catch (e: IllegalArgumentException) {
                printJson(
                    err(
                        InspectorErrorCode.INVALID_ARGUMENTS.wire,
                        e.message ?: "Invalid profile arguments.",
                    ),
                )
                return 2
            }
        if (selection is ProfileSelection.Ambiguous) {
            printJson(
                err(
                    InspectorErrorCode.INVALID_ARGUMENTS.wire,
                    "Conflicting profile arguments: pass either --profile or --profile-id, not both.",
                ),
            )
            return 2
        }
        val fixedProfileId = (selection as? ProfileSelection.FixedId)?.id
        val legacyLabel = (selection as? ProfileSelection.LegacyLabel)?.name

        val root = AgentRoot(defaultKoinStartup, fixedProfileId, legacyLabel)
        return try {
            root.parse(args)
            root.exitCode
        } catch (e: PrintHelpMessage) {
            printJson(helpPayload())
            0
        } catch (e: InspectorException) {
            printJson(e.toJson())
            2
        } catch (e: ProgramResult) {
            e.statusCode
        } catch (e: NoSuchSubcommand) {
            printJson(
                err(
                    InspectorErrorCode.INVALID_ARGUMENTS.wire,
                    "Unknown screen inspector command.",
                    details = helpPayload()["result"] as? JsonObject,
                ),
            )
            2
        } catch (e: CliktError) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: "Invalid arguments."
            printJson(err(InspectorErrorCode.INVALID_ARGUMENTS.wire, message))
            2
        } catch (e: Exception) {
            printJson(err(InspectorErrorCode.INTERNAL.wire, e.message ?: "Screen inspector command failed."))
            1
        }
    }
}

private class AgentRoot(
    val defaultKoinStartup: ToolServerKoinStartup?,
    val fixedProfileId: String?,
    val legacyLabel: String?,
) : CliktCommand(name = "agent") {
    // Accepted so Clikt consumes global profile args anywhere in argv.
    private val profileIdOpt: String? by option("--profile-id")
    private val profileOpt: String? by option("--profile")

    var exitCode: Int = 0
        private set

    init {
        context {
            helpOptionNames = setOf("--help", "-h")
            allowInterspersedArgs = true
        }
        subcommands(
            ScreensCommand(),
            SessionsCommand(),
            OpenCommand(),
            DescribeCommand(),
            SnapshotCommand("inspect"),
            SnapshotCommand("snapshot"),
            ActionsCommand(),
            InvokeCommand(),
            WaitCommand(),
            CancelCommand(),
            CloseCommand(),
            JourneyCommand(),
        )
    }

    override fun run() {
        // Profile selection is resolved from raw argv in ScreenInspectorCli.run.
        profileIdOpt
        profileOpt
        // No subcommand: show JSON help (matches `agent` / `agent help` contract).
        if (currentContext.invokedSubcommand == null) {
            printJson(helpPayload())
        }
    }

    fun withRuntime(block: suspend (ScreenInspectorRuntime) -> JsonObject): Int {
        val startedKoin = ensureKoin(defaultKoinStartup, fixedProfileId, legacyLabel)
        val runtime = ScreenInspectorRuntime(GlobalContext.get())
        return try {
            printJson(blockSuspend(runtime, block))
            0
        } finally {
            runtime.close()
            if (startedKoin) runCatching { stopKoin() }
        }
    }

    private fun blockSuspend(
        runtime: ScreenInspectorRuntime,
        block: suspend (ScreenInspectorRuntime) -> JsonObject,
    ): JsonObject = runBlocking { block(runtime) }
}

private abstract class ScreenInspectorSubcommand(name: String, help: String = "") : CliktCommand(name = name) {
    init {
        context {
            helpOptionNames = setOf("--help", "-h")
            allowInterspersedArgs = true
        }
    }

    protected val root: AgentRoot
        get() =
            currentContext.parent?.command as? AgentRoot
                ?: error("Screen inspector subcommand must run under AgentRoot")

    protected fun emit(result: JsonObject) {
        printJson(result)
    }

    protected fun emitOk(payload: JsonObject) {
        emit(ok(payload))
    }
}

private class ScreensCommand : ScreenInspectorSubcommand("screens") {
    override fun run() {
        emitOk(ScreenRegistry.list())
    }
}

private class SessionsCommand : ScreenInspectorSubcommand("sessions") {
    override fun run() {
        root.withRuntime { runtime -> ok(runtime.sessions()) }
    }
}

private class OpenCommand : ScreenInspectorSubcommand("open") {
    private val screen: String by option("--screen").required()
    private val paramsRaw: String? by option("--params")
    private val taskId: String? by option("--task-id", "--taskId")

    override fun run() {
        root.withRuntime { runtime ->
            val params = parseParams(paramsRaw, taskId)
            ok(runtime.open(screen, params))
        }
    }
}

private class DescribeCommand : ScreenInspectorSubcommand("describe") {
    private val sessionId: String? by option("--session")

    override fun run() {
        root.withRuntime { runtime -> ok(runtime.describe(sessionId)) }
    }
}

private class SnapshotCommand(name: String) : ScreenInspectorSubcommand(name) {
    private val sessionId: String? by option("--session")

    override fun run() {
        root.withRuntime { runtime -> ok(runtime.snapshot(sessionId)) }
    }
}

private class ActionsCommand : ScreenInspectorSubcommand("actions") {
    private val sessionId: String? by option("--session")

    override fun run() {
        root.withRuntime { runtime -> ok(runtime.actions(sessionId)) }
    }
}

private class InvokeCommand : ScreenInspectorSubcommand("invoke") {
    private val action: String by option("--action").required()
    private val inputRaw: String? by option("--input")
    private val sessionId: String? by option("--session")
    private val waitMs: Long? by option("--wait-ms").long()

    override fun run() {
        root.withRuntime { runtime ->
            val input = parseInput(inputRaw)
            val timeout = waitMs
            if (timeout != null && timeout < 0L) {
                throw InspectorException(
                    InspectorErrorCode.INVALID_ARGUMENTS,
                    "Parameter '--wait-ms' must be a non-negative integer.",
                )
            }
            var result = runtime.invoke(sessionId, action, input)
            if (timeout != null) {
                val opId = result["operationId"]?.jsonPrimitive?.contentOrNull
                if (opId != null) {
                    val waited = runtime.wait(sessionId, opId, timeout)
                    result =
                        buildJsonObject {
                            result.forEach { (k, v) -> put(k, v) }
                            put("wait", waited)
                        }
                }
            }
            failIfOperationFailed(result, action, sessionId)
            ok(result)
        }
    }
}

private class WaitCommand : ScreenInspectorSubcommand("wait") {
    private val operationId: String by option("--operation").required()
    private val timeoutMs: Long by option("--timeout-ms").long().default(30_000L)
    private val sessionId: String? by option("--session")

    override fun run() {
        if (timeoutMs < 0L) {
            throw InspectorException(
                InspectorErrorCode.INVALID_ARGUMENTS,
                "Parameter '--timeout-ms' must be a non-negative integer.",
            )
        }
        root.withRuntime { runtime ->
            val result = runtime.wait(sessionId, operationId, timeoutMs)
            failIfOperationFailed(result, null, sessionId)
            ok(result)
        }
    }
}

private class CancelCommand : ScreenInspectorSubcommand("cancel") {
    private val operationId: String by option("--operation").required()
    private val sessionId: String? by option("--session")

    override fun run() {
        root.withRuntime { runtime -> ok(runtime.cancel(sessionId, operationId)) }
    }
}

private class CloseCommand : ScreenInspectorSubcommand("close") {
    private val sessionId: String? by option("--session")

    override fun run() {
        root.withRuntime { runtime -> ok(runtime.close(sessionId)) }
    }
}

private class JourneyCommand : ScreenInspectorSubcommand("journey") {
    private val fileOpt: String? by option("--file")
    private val fileArg: String? by argument(name = "file").optional()

    override fun run() {
        val filePath =
            fileOpt ?: fileArg ?: throw InspectorException(
                InspectorErrorCode.INVALID_ARGUMENTS,
                "Journey requires '--file <path>'.",
            )

        val startedKoin = ensureKoin(root.defaultKoinStartup, root.fixedProfileId, root.legacyLabel)
        val runtime = ScreenInspectorRuntime(GlobalContext.get())
        val stepsOut = mutableListOf<JsonObject>()
        var failed = false
        try {
            val journey =
                try {
                    parseJourneyFile(filePath)
                } catch (e: InspectorException) {
                    throw e
                } catch (e: Exception) {
                    throw InspectorException(
                        InspectorErrorCode.INVALID_ARGUMENTS,
                        "Journey file could not be read or parsed: ${e.message ?: "invalid input"}.",
                    )
                }
            val steps = journey.steps
            for ((index, step) in steps.withIndex()) {
                val name = step["name"]?.jsonPrimitive?.contentOrNull ?: "step-$index"
                val op =
                    step["op"]?.jsonPrimitive?.contentOrNull
                        ?: throw InspectorException(
                            InspectorErrorCode.INVALID_ARGUMENTS,
                            "Journey step '$name' is missing 'op'.",
                        )
                val startedAt = System.currentTimeMillis()
                val continueOnError = step["continueOnError"]?.jsonPrimitive?.booleanOrNull == true
                val stepResult =
                    try {
                        runBlocking { executeJourneyOp(runtime, op, step) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: InspectorException) {
                        failed = true
                        stepsOut.add(
                            buildJsonObject {
                                put("name", name)
                                put("op", op)
                                put("ok", false)
                                put("durationMs", System.currentTimeMillis() - startedAt)
                                put("error", e.toJson()["error"] ?: JsonNull)
                            },
                        )
                        if (continueOnError) continue else break
                    } catch (e: Exception) {
                        failed = true
                        stepsOut.add(
                            buildJsonObject {
                                put("name", name)
                                put("op", op)
                                put("ok", false)
                                put("durationMs", System.currentTimeMillis() - startedAt)
                                put(
                                    "error",
                                    buildJsonObject {
                                        put("code", InspectorErrorCode.INTERNAL.wire)
                                        put("message", e.message ?: "Step failed.")
                                    },
                                )
                            },
                        )
                        if (continueOnError) continue else break
                    }

                stepsOut.add(
                    buildJsonObject {
                        put("name", name)
                        put("op", op)
                        put("ok", true)
                        put("durationMs", System.currentTimeMillis() - startedAt)
                        put("result", stepResult)
                    },
                )
            }
        } finally {
            runtime.close()
            if (startedKoin) runCatching { stopKoin() }
        }

        printJson(
            buildJsonObject {
                put("ok", !failed)
                put(
                    "result",
                    buildJsonObject {
                        put("journey", filePath)
                        put("steps", JsonArray(stepsOut))
                    },
                )
            },
        )
        if (failed) throw ProgramResult(2)
    }

    private data class ParsedJourney(val steps: List<JsonObject>)

    private fun parseJourneyFile(filePath: String): ParsedJourney {
        val text =
            try {
                File(filePath).readText()
            } catch (e: Exception) {
                throw InspectorException(
                    InspectorErrorCode.INVALID_ARGUMENTS,
                    "Journey file could not be read: ${e.message ?: filePath}",
                )
            }
        val rootElement =
            try {
                cliJson.parseToJsonElement(text)
            } catch (e: Exception) {
                throw InspectorException(
                    InspectorErrorCode.INVALID_ARGUMENTS,
                    "Journey file is not valid JSON: ${e.message ?: "parse error"}",
                )
            }
        val rootJson =
            rootElement as? JsonObject
                ?: throw InspectorException(
                    InspectorErrorCode.INVALID_ARGUMENTS,
                    "Journey file must be a JSON object.",
                )
        val stepsElement =
            rootJson["steps"]
                ?: throw InspectorException(
                    InspectorErrorCode.INVALID_ARGUMENTS,
                    "Journey file must contain a 'steps' array.",
                )
        val stepsArray =
            stepsElement as? JsonArray
                ?: throw InspectorException(
                    InspectorErrorCode.INVALID_ARGUMENTS,
                    "Journey 'steps' must be an array.",
                )
        val steps =
            stepsArray.mapIndexed { index, element ->
                val obj =
                    element as? JsonObject
                        ?: throw InspectorException(
                            InspectorErrorCode.INVALID_ARGUMENTS,
                            "Journey step $index must be a JSON object.",
                        )
                val nameElement = obj["name"]
                if (nameElement != null && (nameElement !is JsonPrimitive || !nameElement.isString)) {
                    throw InspectorException(
                        InspectorErrorCode.INVALID_ARGUMENTS,
                        "Journey step $index field 'name' must be a string.",
                    )
                }
                val opElement =
                    obj["op"]
                        ?: throw InspectorException(
                            InspectorErrorCode.INVALID_ARGUMENTS,
                            "Journey step $index is missing 'op'.",
                        )
                if (opElement !is JsonPrimitive || !opElement.isString) {
                    throw InspectorException(
                        InspectorErrorCode.INVALID_ARGUMENTS,
                        "Journey step $index field 'op' must be a string.",
                    )
                }
                val continueOnErrorElement = obj["continueOnError"]
                if (continueOnErrorElement != null) {
                    val isBoolean =
                        continueOnErrorElement is JsonPrimitive &&
                            !continueOnErrorElement.isString &&
                            continueOnErrorElement.booleanOrNull != null
                    if (!isBoolean) {
                        throw InspectorException(
                            InspectorErrorCode.INVALID_ARGUMENTS,
                            "Journey step $index field 'continueOnError' must be a boolean.",
                        )
                    }
                }
                val paramsElement = obj["params"]
                if (paramsElement != null && paramsElement !is JsonObject) {
                    throw InspectorException(
                        InspectorErrorCode.INVALID_ARGUMENTS,
                        "Journey step $index field 'params' must be a JSON object.",
                    )
                }
                val inputElement = obj["input"]
                if (inputElement != null && inputElement !is JsonObject) {
                    throw InspectorException(
                        InspectorErrorCode.INVALID_ARGUMENTS,
                        "Journey step $index field 'input' must be a JSON object.",
                    )
                }
                validateJourneyNonNegativeLong(obj, "waitMs", index)
                validateJourneyNonNegativeLong(obj, "timeoutMs", index)
                obj
            }
        return ParsedJourney(steps)
    }
}

private fun validateJourneyNonNegativeLong(
    obj: JsonObject,
    key: String,
    index: Int,
) {
    val element = obj[key] ?: return
    if (element !is JsonPrimitive || element.isString || element.booleanOrNull != null) {
        throw InspectorException(
            InspectorErrorCode.INVALID_ARGUMENTS,
            "Journey step $index field '$key' must be a non-negative integer.",
        )
    }
    val value = element.longOrNull
    if (value == null || value < 0L) {
        throw InspectorException(
            InspectorErrorCode.INVALID_ARGUMENTS,
            "Journey step $index field '$key' must be a non-negative integer.",
        )
    }
}

private fun ensureKoin(
    defaultKoinStartup: ToolServerKoinStartup?,
    fixedProfileId: String?,
    legacyLabel: String?,
): Boolean {
    if (GlobalContext.getOrNull() != null) return false
    val startup =
        defaultKoinStartup
            ?: throw InspectorException(
                InspectorErrorCode.INTERNAL,
                "Screen inspector requires a Koin startup because no container is running.",
            )
    // Fixed-id mode is owned by the startup; legacy/stored modes start without a fixed profile id.
    startup.start(fixedProfileId)
    val deps = ServerDependencies.fromKoin()
    // Ensure DB/profile bootstrapping so the inspector runs against real app state.
    deps.database.hashCode()
    runBlocking {
        deps.profileBootstrapper.run(
            seedExtras = listOf(ProfileBootstrapper.SeedProfile.AI_AGENT),
            activateName = legacyProfileNameToActivate(legacyLabel),
        )
    }
    return true
}

/**
 * Legacy label semantics: `--profile=ai-agent` activates the AI Agent profile; other labels are
 * ignored (stored profile remains), matching the server's documented contract.
 */
private fun legacyProfileNameToActivate(legacyLabel: String?): String? =
    when (legacyLabel?.lowercase()) {
        "ai-agent", "agent" -> "AI Agent"
        else -> null
    }

private suspend fun executeJourneyOp(
    runtime: ScreenInspectorRuntime,
    op: String,
    step: JsonObject,
): JsonObject =
    when (op) {
        "screens" -> {
            runtime.capabilities()
        }

        "sessions" -> {
            runtime.sessions()
        }

        "open" -> {
            runtime.open(step.string("screen"), step.obj("params"))
        }

        "describe" -> {
            runtime.describe(step.optString("sessionId"))
        }

        "inspect", "snapshot" -> {
            runtime.snapshot(step.optString("sessionId"))
        }

        "actions" -> {
            runtime.actions(step.optString("sessionId"))
        }

        "invoke" -> {
            val action = step.string("action")
            val result = runtime.invoke(step.optString("sessionId"), action, step.obj("input"))
            val waitMs = step["waitMs"]?.jsonPrimitive?.longOrNull
            val combined =
                if (waitMs != null) {
                    val opId = result["operationId"]?.jsonPrimitive?.contentOrNull
                    if (opId != null) {
                        val waited = runtime.wait(step.optString("sessionId"), opId, waitMs)
                        buildJsonObject {
                            result.forEach { (k, v) -> put(k, v) }
                            put("wait", waited)
                        }
                    } else {
                        result
                    }
                } else {
                    result
                }
            throwIfOperationFailed(combined, action)
            combined
        }

        "wait" -> {
            val result =
                runtime.wait(
                    step.optString("sessionId"),
                    step.string("operationId"),
                    step["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 30_000L,
                )
            throwIfOperationFailed(result, null)
            result
        }

        "cancel" -> {
            runtime.cancel(step.optString("sessionId"), step.string("operationId"))
        }

        "close" -> {
            runtime.close(step.optString("sessionId"))
        }

        else -> {
            throw InspectorException(InspectorErrorCode.INVALID_ARGUMENTS, "Unknown journey op '$op'.")
        }
    }

private fun observedOperation(payload: JsonObject): JsonObject = (payload["wait"] as? JsonObject) ?: payload

/** The observed failure of an operation, or null when it is accepted/completed/timeout. */
private fun operationFailure(payload: JsonObject): Pair<JsonObject, String>? {
    val observed = observedOperation(payload)
    if (observed["status"]?.jsonPrimitive?.contentOrNull != "failed") return null
    return observed to (observed["message"]?.jsonPrimitive?.contentOrNull ?: "Operation failed.")
}

private fun operationDetails(
    observed: JsonObject,
    action: String?,
    sessionId: String?,
): JsonObject =
    buildJsonObject {
        observed["operationId"]?.let { put("operationId", it) }
        action?.let { put("action", it) }
        sessionId?.takeIf { it.isNotBlank() }?.let { put("sessionId", it) }
    }

/** A CLI command that observes a failed operation reports it as a structural error and exits 2. */
private fun failIfOperationFailed(
    result: JsonObject,
    action: String?,
    sessionId: String?,
) {
    val (observed, message) = operationFailure(result) ?: return
    printJson(
        err(
            InspectorErrorCode.OPERATION_FAILED.wire,
            message,
            details = operationDetails(observed, action, sessionId),
        ),
    )
    throw ProgramResult(2)
}

/** A journey step that observes a failed operation fails the step (timeout stays non-terminal). */
private fun throwIfOperationFailed(
    result: JsonObject,
    action: String?,
) {
    val (observed, message) = operationFailure(result) ?: return
    throw InspectorException(
        InspectorErrorCode.OPERATION_FAILED,
        message,
        details = operationDetails(observed, action, null),
    )
}

private fun parseParams(
    paramsRaw: String?,
    taskId: String?,
): JsonObject {
    if (paramsRaw != null) {
        val element =
            try {
                cliJson.parseToJsonElement(paramsRaw)
            } catch (e: Exception) {
                throw InspectorException(
                    InspectorErrorCode.INVALID_ARGUMENTS,
                    "Parameter '--params' must be valid JSON: ${e.message ?: "parse error"}",
                )
            }
        return element as? JsonObject
            ?: throw InspectorException(
                InspectorErrorCode.INVALID_ARGUMENTS,
                "Parameter '--params' must be a JSON object.",
            )
    }
    return if (taskId != null) buildJsonObject { put("taskId", taskId) } else JsonObject(emptyMap())
}

private fun parseInput(inputRaw: String?): JsonObject =
    if (inputRaw == null || inputRaw.isBlank()) {
        JsonObject(emptyMap())
    } else {
        val element =
            try {
                cliJson.parseToJsonElement(inputRaw)
            } catch (e: Exception) {
                throw InspectorException(
                    InspectorErrorCode.INVALID_ARGUMENTS,
                    "Parameter '--input' must be valid JSON: ${e.message ?: "parse error"}",
                )
            }
        element as? JsonObject
            ?: throw InspectorException(
                InspectorErrorCode.INVALID_ARGUMENTS,
                "Parameter '--input' must be a JSON object.",
            )
    }

private fun JsonObject.string(key: String): String {
    val element =
        this[key]
            ?: throw InspectorException(InspectorErrorCode.INVALID_ARGUMENTS, "Missing required field '$key'.")
    val primitive = element as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        throw InspectorException(
            InspectorErrorCode.INVALID_ARGUMENTS,
            "Field '$key' must be a JSON string.",
        )
    }
    return primitive.content
}

private fun JsonObject.optString(key: String): String? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        throw InspectorException(
            InspectorErrorCode.INVALID_ARGUMENTS,
            "Field '$key' must be a JSON string.",
        )
    }
    return primitive.content
}

private fun JsonObject.obj(key: String): JsonObject {
    val value = this[key] ?: return JsonObject(emptyMap())
    return value as? JsonObject
        ?: throw InspectorException(
            InspectorErrorCode.INVALID_ARGUMENTS,
            "Field '$key' must be a JSON object.",
        )
}

private fun printJson(element: JsonElement) {
    println(cliJson.encodeToString(JsonElement.serializer(), element))
}

private fun helpPayload(): JsonObject =
    buildJsonObject {
        put("ok", true)
        put(
            "result",
            buildJsonObject {
                put("name", "screen-inspector")
                put("contractVersion", com.singularity.todo.core.llm.SCREEN_INTROSPECTION_CONTRACT_VERSION)
                put(
                    "notes",
                    buildJsonArray {
                        add(
                            JsonPrimitive(
                                "Each non-journey CLI invocation runs in its own process with an in-memory " +
                                    "runtime, so sessions/operations are not shared across separate command " +
                                    "invocations. Use 'journey --file <journey.json>' for stateful multi-step " +
                                    "workflows in one process; use the MCP screen inspector tools for an " +
                                    "interactive long-lived runtime.",
                            ),
                        )
                    },
                )
                put(
                    "commands",
                    buildJsonArray {
                        val oneShot = "  (one-shot unless used in journey)"
                        listOf(
                            "screens",
                            "sessions$oneShot",
                            "open --screen <id> [--params <json> | --task-id <id>]$oneShot",
                            "describe [--session <id>]$oneShot",
                            "inspect|snapshot [--session <id>]$oneShot",
                            "actions [--session <id>]$oneShot",
                            "invoke --action <name> [--input <json>] [--session <id>] [--wait-ms <ms>]$oneShot",
                            "wait --operation <id> [--timeout-ms <ms>] [--session <id>]$oneShot",
                            "cancel --operation <id> [--session <id>]$oneShot",
                            "close [--session <id>]$oneShot",
                            "journey --file <journey.json>  (stateful multi-step workflow in one process)",
                        ).forEach { add(JsonPrimitive(it)) }
                    },
                )
            },
        )
    }
