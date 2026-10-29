package com.singularity.todo.arch

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.fail

/**
 * Detects `singleOf`, `factoryOf`, and `viewModelOf` call sites whose target class
 * declares Kotlin default arguments in its primary constructor — which Koin's
 * reflection-based resolution does NOT apply.
 *
 * ## Why this exists
 *
 * `koin-compiler-plugin` validates the DI graph at compile time, but it cannot see
 * Kotlin default arguments: `singleOf(::Class)` resolves the constructor reflectively
 * without calling the Kotlin compiler, so every parameter — defaulted or not — must be
 * resolvable from the graph. A class with defaults fails at *runtime* with
 * `NoDefinitionFoundException` the first time a screen that needs it is opened.
 *
 * This test parses `*Of(::Class)` call sites from source and fails when the target's
 * primary constructor declares any default values.
 *
 * ## Allowlisting
 *
 * Three targets have no-arg constructors verified safe by hand and cannot be parsed
 * correctly by the naive constructor-scan here:
 *
 * - `SavedAgendaSeedStore` — no-arg constructor
 * - `StubAttachmentUploadService` — no-arg constructor
 * - The `factoryOf(::Impl)` match inside a comment at `AiToolsModule.android.kt:92`
 *
 * These are kept in [ALLOWLIST] and must be re-verified if their constructors change.
 *
 * ## Scope
 *
 * `shared/src/commonMain/kotlin` production sources.
 */
@Tag("fast")
class SingleOfKotlinDefaultsTest {

    companion object {
        private const val PKG = "com.singularity.todo"

        private val scope = CommonMainKonsistScope.scope

        /**
         * Call-site patterns this test checks.
         *
         * The three `Of` variants mirror the three binding DSL functions Koin provides.
         * `singleOf(::T)`, `factoryOf(::T)`, `viewModelOf(::T)`.
         */
        private val KOIN_OF_CALL_PATTERN = Regex("""(singleOf|factoryOf|viewModelOf)\(::(\w+)\)""")

        /**
         * Classes whose constructor has been manually verified to have no defaults, but
         * the naive class-body scan cannot determine this reliably. The entry at
         * `AiToolsModule.android.kt` is inside a `//` comment and is not a real call site.
         */
        private val ALLOWLIST = setOf(
            // SavedAgendaSeedStore — no-arg constructor, verified safe 2026-10-16
            "SavedAgendaSeedStore",
            // StubAttachmentUploadService — no-arg constructor, verified safe 2026-10-16
            "StubAttachmentUploadService",
            // factoryOf(::Impl) inside a comment — not a real call site
            "Impl",
        )

        /**
         * Matches a primary constructor parameter with a default value.
         * Handles: `param: Type = value`, `param: Type = value,`, `param: Type = value)`.
         * Excludes: `val param: Type` (no default), `override val param: Type = ...` (secondary).
         */
        private val PARAM_WITH_DEFAULT_PATTERN = Regex(
            """^\s*(?:val|var)\s+\w+\s*:\s*\S+\s*=\s*.+""",
        )

        private fun KoFileDeclaration.packageName(): String {
            val normalizedPath = path.replace('\\', '/')
            val normalizedRoot = CommonMainKonsistScope.commonMainRoot.replace('\\', '/').trimEnd('/')
            return normalizedPath
                .removePrefix("$normalizedRoot/")
                .substringBeforeLast('/')
                .replace('/', '.')
        }
    }

    /**
     * Positive control: the detector below is proven able to fail.
     *
     * A synthetic file with a class that has defaults registered via `singleOf`
     * must be detected. If this ever passes, the rule has stopped detecting.
     */
    @Test
    fun positiveControlDetectsClassWithDefaults() {
        val synthetic = """
            package com.example

            class Greeter(val greeting: String = "Hello", val count: Int = 1)

            fun myModule() = module {
                singleOf(::Greeter)
            }
        """.trimIndent()

        val findings = scanForDefaultsInSynthetic(synthetic)
        if (findings.isEmpty()) {
            fail(
                "the detector found nothing in a synthetic file that declares " +
                    "a class with defaults registered via singleOf — the rule is no longer detecting",
            )
        }
        kotlin.test.assertEquals(1, findings.size, "expected exactly one finding")
        kotlin.test.assertTrue(
            findings.first().contains("Greeter") && findings.first().contains("defaults"),
            "expected the finding to name Greeter and mention defaults, got: ${findings.first()}",
        )
    }

    /**
     * Verifies the allowlist entries genuinely have no-arg or no-default constructors.
     *
     * This prevents the allowlist from silently becoming a hiding place for a real problem.
     * If this test fails, the class should be fixed (preferred) or the allowlist entry
     * should be replaced with a more specific class-body check.
     */
    @Test
    fun allowlistedClassesHaveNoDefaults() {
        val allowlistedClasses = ALLOWLIST.filterNot { it == "Impl" } // "Impl" is a comment match, skip
        if (allowlistedClasses.isEmpty()) return

        val offenders = allowlistedClasses.mapNotNull { className ->
            val candidates = scope.files
                .filter { it.name.contains(className) }
                .filter { file ->
                    val primaryConstructor = extractPrimaryConstructor(file)
                    primaryConstructor != null && hasDefaults(primaryConstructor)
                }
            if (candidates.isNotEmpty()) {
                val paths = candidates.map { it.path }.joinToString()
                "$className: found in $paths"
            } else {
                null
            }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "Allowlist entries that still have Kotlin defaults:\n" +
                    offenders.joinToString("\n") { "  - $it" } +
                    "\n\nThese should be fixed or removed from ALLOWLIST.",
            )
        }
    }

    /**
     * Every `singleOf`, `factoryOf`, or `viewModelOf` call site in production commonMain
     * must target a class whose primary constructor has no Kotlin default arguments,
     * unless the target is in [ALLOWLIST].
     */
    @Test
    fun noSingleOfFactoryOfViewModelOfTargetsHaveKotlinDefaults() {
        val offenders = mutableListOf<String>()

        scope.files
            .filter { it.name.endsWith(".kt") }
            .filter { it.packageName().startsWith(PKG) }
            .forEach { file ->
                val allowlistedClassesInFile = ALLOWLIST.toMutableSet()

                // Remove "Impl" from allowlist check for non-AiToolsModule files.
                // It is only allowlisted because it appears in a comment in AiToolsModule.android.kt.
                if (!file.name.contains("AiToolsModule")) {
                    allowlistedClassesInFile.remove("Impl")
                }

                val callSites = KOIN_OF_CALL_PATTERN.findAll(file.text).toList()
                for (call in callSites) {
                    val targetClass = call.groupValues[2]
                    if (targetClass in allowlistedClassesInFile) continue

                    // Find the class definition in this file or an imported file.
                    val classCandidates = scope.files.filter { it.name.contains(targetClass) }
                    val matchingClass =
                        classCandidates.firstOrNull { candidate ->
                            val primaryCtor = extractPrimaryConstructor(candidate)
                            primaryCtor != null && hasDefaults(primaryCtor)
                        }

                    if (matchingClass != null) {
                        offenders +=
                            "${file.name}: `singleOf(::$targetClass)` / " +
                            "`factoryOf(::$targetClass)` / `viewModelOf(::$targetClass)` " +
                            "targets a class whose primary constructor has Kotlin default arguments. " +
                            "Rewrite as an explicit lambda that constructs the instance directly " +
                            "(e.g. `single { $targetClass(get()) }`), which applies Kotlin " +
                            "defaults. See #662. Location: ${file.path}:${file.text.substring(
                                0,
                                call.range.first,
                            ).count { it == '\n' } + 1}"
                    }
                }
            }

        if (offenders.isEmpty()) return
        fail(
            "Koin *Of(::Class) call sites whose targets have Kotlin defaults:\n" +
                offenders.joinToString("\n") { "  - $it" } +
                "\n\nRewrite each as an explicit lambda (e.g. `single { ClassName(get()) }` " +
                "or `viewModel { ClassName(get(), get()) }`) so that Kotlin default arguments " +
                "are applied at construction time rather than relying on Koin's reflective " +
                "constructor resolution. Add an ALLOWLIST entry ONLY for classes whose " +
                "constructors have been verified to have no defaults.",
        )
    }

    /**
     * Scans [source] for a class with a primary constructor that has default values,
     * returning the list of findings (class name + location).
     */
    private fun scanForDefaultsInSynthetic(source: String): List<String> {
        val findings = mutableListOf<String>()
        val callMatches = KOIN_OF_CALL_PATTERN.findAll(source).toList()
        for (call in callMatches) {
            val targetClass = call.groupValues[2]
            // Find the class in the synthetic source.
            val classPattern = Regex("""class\s+$targetClass\s*\(.*?\)""")
            val classMatch = classPattern.find(source)
            if (classMatch != null) {
                // Extract just the parameter list (content inside the first parentheses pair).
                val paramsMatcher = Regex("""class\s+$targetClass\s*\((.*)\)""")
                val paramsMatch = paramsMatcher.find(classMatch.value)
                val params = paramsMatch?.groupValues?.getOrNull(1) ?: classMatch.value
                if (hasDefaults(params)) {
                    findings += "$targetClass at position ${call.range.first} has defaults"
                }
            }
        }
        return findings
    }

    /**
     * Extracts the parameter list from a primary constructor.
     *
     * Matches: `class Foo(param1: Type, param2: Type = default)` or
     * `class Foo constructor(param1: Type, ...)`.
     */
    private fun extractPrimaryConstructor(file: KoFileDeclaration): String? {
        // Match the primary constructor of the file's main class (the one whose name
        // matches the file name, or the first class in the file).
        val className = file.name.removeSuffix(".kt")
        // Try to find a constructor declaration in the class body.
        val constructorPattern = Regex(
            """class\s+$className\s*(?:constructor\s*)?\((.*?)\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        return constructorPattern.find(file.text)?.groupValues?.getOrNull(1)
    }

    /**
     * Returns true if the constructor parameter list contains any parameter with a
     * default value. Uses findAll rather than matches so a multi-parameter constructor
     * string (comma-separated on one line) still detects each individual default.
     */
    private fun hasDefaults(constructorParams: String): Boolean =
        PARAM_WITH_DEFAULT_PATTERN.findAll(constructorParams).any()
}
