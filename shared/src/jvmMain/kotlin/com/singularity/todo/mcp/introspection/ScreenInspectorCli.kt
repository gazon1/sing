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
import com.singularity.todo.mcp.ProfileSelection
import com.singularity.todo.mcp.ServerDependencies
import com.singularity.todo.mcp.ToolServerKoinStartup
import com.singularity.todo.mcp.parseProfileSelection
import com.singularity.todo.mcp.toolServerModules
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
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
    ): Int {
        // `help` is a command in the JSON contract (not Clikt's text help).
        if (args.firstOrNull() == "help") {
            printJson(helpPayload())
            return 0
        }

        // Preserve existing profile-selection contract/ambiguity handling.
        val selection = parseProfileSelection(args.toTypedArray())
        val fixedProfileId =
            when (selection) {
                is ProfileSelection.FixedId -> {
                    selection.id
                }

                is ProfileSelection.Ambiguous -> {
                    printJson(
                        err(
                            InspectorErrorCode.INVALID_ARGUMENTS.wire,
                            "Conflicting profile arguments: pass either --profile or --profile-id, not both.",
                        ),
                    )
                    return 2
                }

                else -> {
                    null
                }
            }

        val root = AgentRoot(defaultKoinStartup, fixedProfileId)
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

private class AgentRoot(val defaultKoinStartup: ToolServerKoinStartup?, val fixedProfileId: String?) :
    CliktCommand(name = "agent") {
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
        val startedKoin = ensureKoin(defaultKoinStartup, fixedProfileId)
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
            var result = runtime.invoke(sessionId, action, input)
            val timeout = waitMs
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
            ok(result)
        }
    }
}

private class WaitCommand : ScreenInspectorSubcommand("wait") {
    private val operationId: String by option("--operation").required()
    private val timeoutMs: Long by option("--timeout-ms").long().default(30_000L)
    private val sessionId: String? by option("--session")

    override fun run() {
        root.withRuntime { runtime -> ok(runtime.wait(sessionId, operationId, timeoutMs)) }
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

        val startedKoin = ensureKoin(root.defaultKoinStartup, root.fixedProfileId)
        val runtime = ScreenInspectorRuntime(GlobalContext.get())
        val stepsOut = mutableListOf<JsonObject>()
        var failed = false
        try {
            val rootJson = cliJson.parseToJsonElement(File(filePath).readText()).jsonObject
            val steps = rootJson["steps"]?.jsonArray ?: JsonArray(emptyList())
            for ((index, element) in steps.withIndex()) {
                val step = element.jsonObject
                val name = step["name"]?.jsonPrimitive?.contentOrNull ?: "step-$index"
                val op =
                    step["op"]?.jsonPrimitive?.contentOrNull
                        ?: throw InspectorException(
                            InspectorErrorCode.INVALID_ARGUMENTS,
                            "Journey step '$name' is missing 'op'.",
                        )
                val startedAt = System.currentTimeMillis()
                val continueOnError =
                    step["continueOnError"]?.jsonPrimitive?.booleanOrNull == true ||
                        step["continueOnError"]?.jsonPrimitive?.contentOrNull == "true"
                val stepResult =
                    try {
                        runBlocking { executeJourneyOp(runtime, op, step) }
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
}

private fun ensureKoin(
    defaultKoinStartup: ToolServerKoinStartup?,
    fixedProfileId: String?,
): Boolean {
    if (GlobalContext.getOrNull() != null) return false
    if (defaultKoinStartup != null) {
        defaultKoinStartup.start(fixedProfileId)
    } else {
        startKoin { modules(toolServerModules(fixedProfileId)) }
    }
    val deps = ServerDependencies.fromKoin()
    // Ensure DB/profile bootstrapping so the inspector runs against real app state.
    deps.database.hashCode()
    runBlocking { deps.profileBootstrapper.run() }
    return true
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
            val result = runtime.invoke(step.optString("sessionId"), step.string("action"), step.obj("input"))
            val waitMs = step["waitMs"]?.jsonPrimitive?.longOrNull
            if (waitMs != null) {
                val opId = result["operationId"]?.jsonPrimitive?.contentOrNull
                if (opId != null) {
                    val waited = runtime.wait(step.optString("sessionId"), opId, waitMs)
                    return buildJsonObject {
                        result.forEach { (k, v) -> put(k, v) }
                        put("wait", waited)
                    }
                }
            }
            result
        }

        "wait" -> {
            runtime.wait(
                step.optString("sessionId"),
                step.string("operationId"),
                step["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 30_000L,
            )
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

private fun parseParams(
    paramsRaw: String?,
    taskId: String?,
): JsonObject {
    if (paramsRaw != null) return cliJson.parseToJsonElement(paramsRaw).jsonObject
    return if (taskId != null) buildJsonObject { put("taskId", taskId) } else JsonObject(emptyMap())
}

private fun parseInput(inputRaw: String?): JsonObject =
    if (inputRaw == null ||
        inputRaw.isBlank()
    ) {
        JsonObject(emptyMap())
    } else {
        cliJson.parseToJsonElement(inputRaw).jsonObject
    }

private fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull
        ?: throw InspectorException(InspectorErrorCode.INVALID_ARGUMENTS, "Missing required field '$key'.")

private fun JsonObject.optString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject = (this[key] as? JsonObject) ?: JsonObject(emptyMap())

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
                    "commands",
                    buildJsonArray {
                        add(JsonPrimitive("screens"))
                        add(JsonPrimitive("sessions"))
                        add(JsonPrimitive("open --screen <id> [--params <json> | --task-id <id>]"))
                        add(JsonPrimitive("describe [--session <id>]"))
                        add(JsonPrimitive("inspect|snapshot [--session <id>]"))
                        add(JsonPrimitive("actions [--session <id>]"))
                        add(
                            JsonPrimitive(
                                "invoke --action <name> [--input <json>] [--session <id>] [--wait-ms <ms>]",
                            ),
                        )
                        add(JsonPrimitive("wait --operation <id> [--timeout-ms <ms>] [--session <id>]"))
                        add(JsonPrimitive("cancel --operation <id> [--session <id>]"))
                        add(JsonPrimitive("close [--session <id>]"))
                        add(JsonPrimitive("journey --file <journey.json>"))
                    },
                )
            },
        )
    }
