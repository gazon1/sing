package com.singularity.todo.mcp

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The registry snapshot: what the toolbox looked like when the server started.
 *
 * The report needs two things to say "this tool was registered and never called": the
 * records of what happened, and a record of what the toolbox *was*. Without the second,
 * the difference between "never registered" and "never called" is invisible — and the
 * first is not a defect at all.
 *
 * Written next to the call records, in the application's own data directory, and
 * best-effort: a server that cannot write its registry still serves its tools.
 */
object RegistrySnapshot {

    private const val DIRECTORY = "tool-calls"
    private const val FILE_NAME = "registry.json"
    private const val TAG = "RegistrySnapshot"

    @Suppress("TooGenericExceptionCaught")
    fun write(snapshot: ToolsetSnapshot, dataDirectory: String) {
        try {
            val dir = File(dataDirectory, DIRECTORY)
            dir.mkdirs()
            File(dir, FILE_NAME).writeText(payload(snapshot).toString())
        } catch (e: Throwable) {
            // A server that cannot write its registry still serves its tools. The report
            // says the registry is missing, which is the honest answer — and the reason
            // goes to the log, not to the writer that just failed.
            Logger.withTag(TAG).w(e) { "Could not write the registry snapshot" }
        }
    }

    private fun payload(snapshot: ToolsetSnapshot) = buildJsonObject {
        put("startedAt", System.currentTimeMillis())
        put("registeredTools", toolNames(snapshot.registered))
        put("disabledTools", toolNames(snapshot.disabled))
        put("configurationStatus", snapshot.configurationStatus.name.lowercase())
        put("buildVersion", snapshot.identity.buildVersion)
        put("catalogFingerprint", snapshot.identity.catalogFingerprint)
        put("activeToolsetFingerprint", snapshot.identity.activeToolsetFingerprint)
        // The flags that named no tool. The report is the operator's window on a
        // running server, and "a flag names a tool this build does not have" is
        // exactly the thing an operator looks for.
        put("unmatchedFlags", JsonArray(snapshot.unmatchedFlags.map { JsonPrimitive(it) }))
    }

    private fun toolNames(tools: List<ToolboxToolEntry>): JsonArray = buildJsonArray {
        tools.sortedBy { it.name }.forEach { tool -> add(buildJsonObject { put("name", tool.name) }) }
    }
}
