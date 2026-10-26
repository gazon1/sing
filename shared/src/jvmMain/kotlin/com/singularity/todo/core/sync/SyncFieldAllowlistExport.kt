package com.singularity.todo.core.sync

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.system.exitProcess

/**
 * The derived field lists, exported from the **compiled** models.
 *
 * ## Why this exists
 *
 * The derivation lives in Kotlin, because only the compiler knows what a model's
 * serial descriptor holds.  The tools that need the lists — the seed generator,
 * the gate that compares the server's table with them — are Python, and a gate
 * that runs a compiler is a gate nobody runs.
 *
 * So the lists are exported from the classes themselves, by a program that loads
 * them, and the export is committed.  The gate then reads the export instead of
 * parsing the Kotlin source: the export says what the compiler really saw, and a
 * parser that misreads a model cannot hide behind it.
 *
 * This is the arrangement `config/docs/tool-surface.json` already uses, and it
 * replaces the regular expression the seed generator used to read the Kotlin
 * source with — a regular expression that stopped at the first closing
 * parenthesis, and so read a list that was three names short.
 *
 * ## The format
 *
 * ```json
 * {
 *   "version": 1,
 *   "docTypes": {
 *     "task": {
 *       "wire": ["id", "title", "…"],
 *       "serverWritable": ["accentColor", "…"]
 *     }
 *   }
 * }
 * ```
 *
 * The entity type is the `DocType` key — the name the server looks the type up
 * by — and the two lists are the wire's names and the server's names.
 */
object SyncFieldAllowlistExport {

    /** The version of the format. Bumped when the shape changes. */
    const val VERSION = 1

    private val json = Json { prettyPrint = true }

    /**
     * The lists as the compiled models declare them.
     */
    fun export(): JsonObject = buildJsonObject {
        put("version", VERSION)
        put(
            "docTypes",
            buildJsonObject {
                SyncContract.WIRE_FIELDS.forEach { (type, wire) ->
                    put(
                        type.key,
                        buildJsonObject {
                            put("wire", names(wire))
                            put("serverWritable", names(SyncContract.serverFieldsFor(type)))
                        },
                    )
                }
            },
        )
    }

    private fun names(fields: List<String>): kotlinx.serialization.json.JsonArray =
        buildJsonArray {
            fields.forEach { add(JsonPrimitive(it)) }
        }

    /**
     * Writes the export to [target].
     *
     * The file is written whole, and a trailing newline keeps it readable in a
     * diff.
     */
    fun writeTo(target: File) {
        target.parentFile?.mkdirs()
        target.writeText(json.encodeToString(JsonObject.serializer(), export()) + "\n")
    }

    /**
     * Whether [target] says what the models declare.
     *
     * The comparison is of the parsed JSON, not of the text: the question is
     * whether the file says the same thing, not whether it says it with the
     * same whitespace. The fast Gradle check and the JVM test both ask it
     * through this one function, so the two cannot drift into asking
     * different questions — a file that one of them calls current and the
     * other calls stale is a file nobody can reason about.
     */
    fun matches(target: File): Boolean {
        if (!target.isFile) return false
        // A file that cannot be parsed is a file that does not say what the
        // models declare — the question stays the same, and the answer is a
        // failure with a message rather than a stack trace.
        val actual = runCatching { Json.parseToJsonElement(target.readText()).jsonObject }
            .getOrNull() ?: return false
        return actual == export()
    }
}

/**
 * Writes the derived field lists to the path given as the first argument.
 *
 * The entry point of the `:shared:exportSyncFieldAllowlist` task, and of nothing
 * else: the file it writes is checked by a test, so a hand-edit of it is
 * reported rather than believed.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--check") {
        val target = File(args.getOrNull(1) ?: error("usage: --check <file>"))
        if (!SyncFieldAllowlistExport.matches(target)) {
            println(
                "config/docs/sync-field-allowlist.json is not what the code exports. " +
                    "Regenerate it with: ./gw :shared:exportSyncFieldAllowlist",
            )
            exitProcess(1)
        }
        println("The committed field lists are the ones the models declare")
        return
    }
    val target = File(args.firstOrNull() ?: "config/docs/sync-field-allowlist.json")
    SyncFieldAllowlistExport.writeTo(target)
    println("Wrote the derived field lists to ${target.absolutePath}")
}
