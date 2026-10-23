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
 * ## Why two arguments rather than one
 *
 * The label was there first, and it means a *name* whose value only one label had any
 * effect for. Everything else was ignored — so a process asking for a profile it could
 * not have was told nothing and carried on, which is how a process ends up reading
 * somebody else's data.
 *
 * The id is the new argument: it names a profile exactly, it never changes the stored
 * active profile, and an id nobody knows is an error rather than a fallback.
 *
 * The label stays because distributed manifests depend on it. It is deprecated, not
 * repurposed.
 */
fun parseProfileSelection(args: Array<String>): ProfileSelection {
    val label = args.firstOrNull { it.startsWith("--profile=") }?.removePrefix("--profile=")
    val id = args.firstOrNull { it.startsWith("--profile-id=") }?.removePrefix("--profile-id=")

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
fun jsonRpcError(code: Int, message: String): String =
    errorJson.encodeToString(
        JsonRpcErrorEnvelope.serializer(),
        JsonRpcErrorEnvelope(error = JsonRpcErrorBody(code = code, message = message)),
    )
