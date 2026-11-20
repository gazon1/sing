package com.singularity.todo.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which profile the tool server was told to work as.
 *
 * Two arguments, two meanings, never both.
 */
sealed interface ProfileSelection {
    /**
     * Work as this profile id, and do not touch the application's stored active profile.
     *
     * The mode for a process started by somebody else — an external agent, launched by
     * the application. The process does not own the active profile and must not change
     * it, and it must not open the store that holds it: the store takes a file lock, and
     * the application is holding it.
     */
    data class FixedId(val id: String) : ProfileSelection

    /**
     * The legacy label, with its original meaning.
     *
     * `ai-agent` activates the AI Agent profile; anything else is ignored. Preserved
     * because client manifests in the wild pass it, and changing what it means would
     * break every one of them silently.
     */
    data class LegacyLabel(val name: String) : ProfileSelection

    /** No argument: the profile comes from the application's stored state. */
    data object Stored : ProfileSelection

    /** Both arguments: the request says two different things, and neither can be obeyed. */
    data class Ambiguous(val label: String, val id: String) : ProfileSelection
}

/**
 * Reads the profile selection out of the command line.
 *
 * Supports both `--profile=<value>`/`--profile-id=<value>` and space-separated
 * `--profile <value>`/`--profile-id <value>` forms. Malformed or incomplete explicit
 * profile arguments are rejected instead of being silently ignored.
 */
@Suppress("CyclomaticComplexMethod", "ThrowsCount", "LoopWithTooManyJumpStatements") // per-form refusals
fun parseProfileSelection(args: Array<String>): ProfileSelection {
    var label: String? = null
    var id: String? = null
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (arg == "--") {
            break
        }
        when {
            arg == "--profile-id" -> {
                val value = args.getOrNull(i + 1)
                if (value == null || value == "--" || value.startsWith("--")) {
                    throw IllegalArgumentException("Missing value for --profile-id")
                }
                id = value
                i += 2
                continue
            }

            arg.startsWith("--profile-id=") -> {
                id = arg.substringAfter("--profile-id=")
            }

            arg == "--profile" -> {
                val value = args.getOrNull(i + 1)
                if (value == null || value == "--" || value.startsWith("--")) {
                    throw IllegalArgumentException("Missing value for --profile")
                }
                label = value
                i += 2
                continue
            }

            arg.startsWith("--profile=") -> {
                label = arg.substringAfter("--profile=")
            }
        }
        i += 1
    }
    if (id != null && id.isEmpty()) {
        throw IllegalArgumentException("Missing value for --profile-id")
    }
    if (label != null && label.isEmpty()) {
        throw IllegalArgumentException("Missing value for --profile")
    }
    return when {
        label != null && id != null -> ProfileSelection.Ambiguous(label, id)
        id != null -> ProfileSelection.FixedId(id)
        label != null -> ProfileSelection.LegacyLabel(label)
        else -> ProfileSelection.Stored
    }
}

/**
 * The body of a JSON-RPC error.
 *
 * Built by the serialiser, never by string interpolation.
 *
 * ## Why this is not a template
 *
 * The old builder interpolated the message into a JSON string by hand. An error text with
 * a quote in it produced a message a client could not parse, and the same text carried
 * the internals of the failure — file paths, database messages, the text of an
 * exception. A caller that cannot parse the error cannot report it, and a caller that
 * receives internal paths learns things it was not meant to.
 *
 * So the message a caller receives is neutral, and the detail goes to the log.
 */
@Serializable
data class JsonRpcErrorBody(val code: Int, val message: String)

/** A JSON-RPC error response. */
@Serializable
data class JsonRpcErrorEnvelope(val jsonrpc: String = "2.0", val id: String? = null, val error: JsonRpcErrorBody)

private val errorJson = Json { encodeDefaults = true }

/**
 * The bytes of a JSON-RPC error response.
 *
 * @param code the JSON-RPC error code
 * @param message what the caller is told. Neutral by contract: it says what happened,
 *   and it does not carry a path, a stack trace or an exception message.
 */
fun jsonRpcError(
    code: Int,
    message: String,
): String =
    errorJson.encodeToString(
        JsonRpcErrorEnvelope.serializer(),
        JsonRpcErrorEnvelope(error = JsonRpcErrorBody(code = code, message = message)),
    )
