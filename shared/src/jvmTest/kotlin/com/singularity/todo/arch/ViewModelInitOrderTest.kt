package com.singularity.todo.arch

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the property-initialization order inside ViewModels.
 *
 * ## The bug this catches
 *
 * Kotlin runs property initializers and `init` blocks in **declaration order**. A
 * property declared *after* an `init` block is still `null` when that block
 * runs — the compiler accepts it, and the type is not even nullable, so nothing
 * in the type system objects.
 *
 * It only hurts when the `init` launches a coroutine whose body reads the
 * property, because whether that body starts before construction finishes is a
 * scheduling detail rather than a guarantee. In `ProjectDetailViewModel` this
 * produced
 *
 * ```
 * NullPointerException: Cannot invoke "ProjectDetailDraftState.seed(String, String)"
 *   because the return value of "ProjectDetailViewModel.getDraftState()" is null
 * ```
 *
 * roughly one run in two, only under machine load. `AgendaViewModel` had the same
 * ordering with a coroutine that took more dispatcher hops, so it never fired.
 *
 * Neither the compiler nor detekt can see this, and there is no baseline entry
 * that would help: it is a property of the source order, not a lint.
 * See `docs/decisions/2026-09-30-vm-init-property-declaration-order.md`.
 *
 * ## How the scan avoids its own false positives
 *
 * A naive version — "does the text after the first `init` mention a property
 * declared later?" — reports every `init` block's body as if it belonged to the
 * *first* one, so any second `init` produces noise. Here each `init` block's real
 * extent is found by brace depth.
 *
 * Lambda parameters shadow properties of the same name (`combine(a, b) { userId
 * -> … }` uses the parameter, not the field), so identifiers bound as lambda
 * parameters inside the block are excluded.
 */
class ViewModelInitOrderTest {

    @Test
    fun `no ViewModel reads a property before its initializer runs`() {
        val violations = mutableListOf<String>()

        viewModelFiles().forEach { file ->
            violations += findViolations(file)
        }

        if (violations.isEmpty()) return
        fail(
            "Property used in an init block but declared after it — a coroutine " +
                "launched from that init can read it before the initializer runs, " +
                "which is a NullPointerException waiting for a busy machine:\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\nMove the declaration above the init block that reads it. " +
                "See 2026-09-30-vm-init-property-declaration-order.md.",
        )
    }

    /**
     * The scan is worthless if it silently matches nothing, so assert it has
     * teeth: every ViewModel this project has must have been examined, and at
     * least one must contain an `init` block for the check to mean anything.
     */
    @Test
    fun `the scan actually finds init blocks`() {
        val files = viewModelFiles()
        assertTrue(files.size > 10, "expected the project's ViewModels, found ${files.size}")
        val withInit = files.count { it.readText().contains("init {") }
        assertTrue(withInit > 5, "expected several ViewModels with init blocks, found $withInit")
    }

    private fun viewModelFiles(): List<File> = File(commonMainRoot)
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith("ViewModel.kt") }
        .toList()

    private fun findViolations(file: File): List<String> {
        val lines = file.readText().lines()
        val properties = classLevelProperties(lines)
        if (properties.isEmpty()) return emptyList()

        return initBlocks(lines).flatMap { range ->
            val start = range.first
            val body = lines.subList(start, range.last + 1).joinToString("\n")
            val shadowed = lambdaParameterNames(body)
            properties
                .filter { (name, declaredAt) -> declaredAt > start }
                .filter { (name, _) -> name !in shadowed }
                .filter { (name, _) -> Regex("""(?<![\w.])${Regex.escape(name)}(?![\w])""").containsMatchIn(body) }
                .map { (name, declaredAt) ->
                    "${file.name}: `$name` declared at line ${declaredAt + 1}, " +
                        "read by init at line ${start + 1}"
                }
        }
    }

    /** `val x = …` / `var x = …` at class-body indentation, with their line index. */
    private fun classLevelProperties(lines: List<String>): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        var depth = 0
        var inClass = false
        lines.forEachIndexed { index, line ->
            if (CLASS_DECL.containsMatchIn(line)) {
                inClass = true
                depth = line.count { it == '{' } - line.count { it == '}' }
                return@forEachIndexed
            }
            if (!inClass) return@forEachIndexed

            val stripped = line.substringBefore("//")
            if (depth == 1) {
                PROPERTY_DECL.find(stripped)?.let { result += it.groupValues[1] to index }
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0 && index > 0 && line.trimStart().startsWith("}")) {
                inClass = false
            }
        }
        return result
    }

    /** Each `init { … }` block as its first and last line index, found by brace depth. */
    private fun initBlocks(lines: List<String>): List<IntRange> {
        val blocks = mutableListOf<IntRange>()
        lines.forEachIndexed { index, line ->
            if (!line.trimStart().startsWith("init {")) return@forEachIndexed
            var depth = 0
            var end = index
            for (i in index until lines.size) {
                depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
                if (i > index && depth <= 0) {
                    end = i
                    break
                }
            }
            blocks += index..end
        }
        return blocks
    }

    /**
     * Identifiers bound as lambda parameters inside [body], which shadow any
     * property of the same name.
     */
    private fun lambdaParameterNames(body: String): Set<String> {
        val names = mutableSetOf<String>()
        LAMBDA_PARAMS.findAll(body).forEach { match ->
            match.groupValues[1]
                .split(',')
                .map { it.trim().substringBefore(':').trim() }
                .filter { it.isNotEmpty() && IDENTIFIER.matches(it) }
                .forEach { names += it }
        }
        return names
    }

    private companion object {
        val commonMainRoot: String = System.getProperty("commonMain.root")
            ?: error("commonMain.root is not set — see the jvmTest config in shared/build.gradle.kts")

        val CLASS_DECL = Regex("""^\s*(?:internal |data |open |abstract |sealed )*class\s+\w+""")
        val PROPERTY_DECL = Regex("""^\s{4}(?:private |internal |override |public |protected )*(?:val|var) (\w+)""")
        val IDENTIFIER = Regex("""\w+""")
        val LAMBDA_PARAMS = Regex("""\{([\w\s,:<>()?]*?)\s*->""")
    }
}
