package com.singularity.todo.mcp
import com.singularity.todo.feature.ai.McpToolCatalog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The tool surface, exported from the **compiled** declarations.
 *
 * ## Why this exists
 *
 * The catalogue, the tool classes and the documentation are three declarations of one
 * surface, and the gate that keeps them together reads the *source text* of two of
 * them. A regex that misreads a class does not fail — it reads less, and the gate
 * reports a surface that is smaller than the code. That is the shape of defect this
 * whole change is about: a check that passes on a tree it did not really read.
 *
 * So the surface is exported from the classes themselves, by a program that loads them,
 * and the export is committed. The gate then compares what it parsed with what the
 * compiler saw: parsed == exported == compiled. A parser that misreads a class cannot
 * hide, because the export says what the class really declares.
 *
 * ## The format
 *
 * JSON, sorted by name, one object per tool:
 *
 * ```json
 * {
 *   "version": 2,
 *   "tools": [
 *     {
 *       "name": "list_tasks",
 *       "toolClass": "ListTasksTool",
 *       "domain": "tasks",
 *       "description": "…",
 *       "readOnly": false,
 *       "destructive": false,
 *       "idempotent": true,
 *       "writeBehavior": "PROPOSAL_WRITE",
 *       "exceptionReason": null
 *     }
 *   ]
 * }
 * ```
 *
 * The description is read from the tool class, because that is where it lives: the
 * catalogue no longer carries a copy of it.
 *
 * The write behaviour is read from the catalogue, because that is where it lives: it is
 * the one declaration of how each tool writes, and publishing it here means a reader
 * outside the compiler — a document, an agent, a check — can see it without reading the
 * catalogue's source.
 */
object ToolSurfaceExport {

    /**
     * The version of the format. Bumped when the shape changes.
     *
     * Version 2 adds the write behaviour and the exception reason to every tool.
     */
    const val VERSION = 2

    private val json = Json { prettyPrint = true }

    /**
     * The surface as the compiled code declares it.
     *
     * @throws IllegalStateException when a catalogue entry names a class that is not
     *   there, or a class that does not declare the name the catalogue gave it. A
     *   surface that cannot be read must not be exported as a smaller one.
     */
    fun surface(): JsonObject = buildJsonObject {
        put("version", VERSION)
        // The contract version the export describes. The export says which tools exist
        // under which contract, so a reader can tell "this build exposes three more
        // tools" from "this build speaks a different contract".
        put("surfaceContractVersion", McpToolCatalog.SURFACE_CONTRACT_VERSION)
        put(
            "tools",
            buildJsonArray {
                McpToolCatalog.catalog
                    .sortedBy { it.name }
                    .forEach { entry ->
                        add(toolJson(entry))
                    }
            },
        )
        // The flag key space: the names a remote configuration may use to switch a tool
        // off. It is the tools' names and nothing else — the same strings the client
        // calls — so declaring it here makes the space checkable: a configuration, or a
        // document, can be compared with it.
        put(
            "flagKeys",
            buildJsonArray {
                McpToolCatalog.catalog
                    .map { it.name }
                    .sorted()
                    .forEach { name -> add(JsonPrimitive(name)) }
            },
        )
    }

    private fun toolJson(entry: McpToolCatalog.ToolEntry): JsonObject {
        val clazz = entry.toolClass
        val declaredName = constant(clazz.java, "NAME")
        require(declaredName == entry.name) {
            "the catalogue says ${entry.name} is ${clazz.simpleName}, but that class declares " +
                "$declaredName"
        }
        val description = constant(clazz.java, "DESCRIPTION")
        val annotations = entry.annotations
        return buildJsonObject {
            put("name", entry.name)
            put("toolClass", clazz.simpleName ?: entry.name)
            put("domain", entry.domain)
            put("description", description)
            put("readOnly", annotations.readOnlyHint)
            put("destructive", annotations.destructiveHint)
            put("idempotent", annotations.idempotentHint)
            // How the tool writes, published so that it can be read without the
            // catalogue's source. The reason is null for every tool that is not a
            // declared exception — a reason that does not apply is a reason nobody
            // will read, and the catalogue refuses to carry one.
            put("writeBehavior", entry.writeBehavior.name)
            put("exceptionReason", entry.exceptionReason?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }

    /**
     * A `const val` of the tool class.
     *
     * The Kotlin compiler compiles a `const val` in a companion object as a static field
     * of the containing class, so that is where the constant is read from.
     */
    private fun constant(clazz: Class<*>, name: String): String {
        val field = clazz.declaredFields.firstOrNull { it.name == name }
            ?: error("${clazz.simpleName} does not declare a $name constant")
        field.isAccessible = true
        return field.get(null) as? String
            ?: error("${clazz.simpleName}.$name is not a string")
    }

    /**
     * Writes the surface to [target].
     *
     * The file is written whole, and a trailing newline keeps it readable in a diff.
     */
    fun writeTo(target: File) {
        target.parentFile?.mkdirs()
        target.writeText(json.encodeToString(JsonObject.serializer(), surface()) + "\n")
    }
}

/**
 * Writes the tool surface to the path given as the first argument.
 *
 * The entry point of the `:shared:exportToolSurface` task, and of nothing else: the
 * file it writes is checked by a test, so a hand-edit of it is reported rather than
 * believed.
 */
fun main(args: Array<String>) {
    val target = File(args.firstOrNull() ?: "config/docs/tool-surface.json")
    ToolSurfaceExport.writeTo(target)
    println("Wrote the tool surface to ${target.absolutePath}")
}
