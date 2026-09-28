package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import kotlin.test.Test
import kotlin.test.fail

/**
 * The DI-facade invariant from
 * `docs/decisions/2026-09-27-di-module-aggregator-narrative.md`, made executable.
 *
 * `core/di/Modules.kt` is a **facade aggregator**, not a source of truth: it composes the
 * per-domain `*DiModule.kt` functions and registers the few bindings that must land at
 * root scope. The rule is that `single { }`, `factory { }` and `viewModel { }` declarations
 * appear **only inside a facade function** — never loose in the file body, and never in a
 * `*DiModule.kt`.
 *
 * ## Why a rule that passes on its first run
 *
 * The invariant already holds; nothing needed fixing. This test exists because the project
 * has a run of guards that were inactive without anyone noticing — seven custom rulesets
 * with no `detekt.yml` block, a provider missing from the ServiceLoader file, a rule whose
 * only finding was credited in an ADR it never produced. A test that passes now is the
 * case working as intended: it makes the invariant fail loudly the first time it is
 * violated, instead of leaving a reviewer to notice.
 *
 * That is also why [positiveControlDetectsLooseBinding] is here. A guard that has never
 * been seen to fail is not known to work.
 *
 * Scope: `shared/src/commonMain/kotlin` production sources.
 */
class DiFacadeTest {

    companion object {
        private const val PKG = "com.singularity.todo"

        /** Injected by the jvmTest task config in `shared/build.gradle.kts`. */
        private val commonMainRoot: String = System.getProperty("commonMain.root")
            ?: error(
                "commonMain.root system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )

        private val scope = Konsist.scopeFromExternalDirectory(commonMainRoot)

        /**
         * Functions in [Modules.kt] that are allowed to declare bindings.
         *
         * `domainModule` and `coreLoggingModule` are the two documented facades.
         * `aiToolsModule` is included because it is an `expect` declaration in the same
         * file whose platform actuals are the ones that declare the tools.
         */
        private val FACADE_FUNCTIONS = setOf("domainModule", "coreLoggingModule", "aiToolsModule")

        /** Koin declarations that must not appear loose in a DI file. */
        private val BINDING_CALLS = setOf("single", "factory", "viewModel", "singleOf", "factoryOf", "viewModelOf")

        /** Package name derived from the file path, as [ArchitectureTest] does. */
        private fun KoFileDeclaration.packageName(): String {
            val normalizedPath = path.replace('\\', '/')
            val normalizedRoot = commonMainRoot.replace('\\', '/').trimEnd('/')
            return normalizedPath
                .removePrefix("$normalizedRoot/")
                .substringBeforeLast('/')
                .replace('/', '.')
        }

        /**
         * The facade aggregator, resolved lazily so a lookup failure surfaces as a test
         * failure with a readable message rather than an initializer crash.
         */
        private val modulesFile: KoFileDeclaration by lazy {
            val candidates = scope.files.filter { it.packageName() == "$PKG.core.di" }
            val match = candidates.filter { it.name == "Modules" }
            if (match.size != 1) {
                error(
                    "expected exactly one core/di/Modules.kt, found ${match.size} among " +
                        candidates.map { it.name }.sorted(),
                )
            }
            match.single()
        }

        /** Strips KDoc and line comments so prose about DI is not read as a call. */
        private fun KoFileDeclaration.codeOnly(): String =
            text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//[^\n]*"""), "")
    }

    /**
     * Positive control: the detector below is proven able to fail.
     *
     * Runs the same scan over a synthetic facade file that *does* declare a binding
     * outside its facade function. If this ever passes, the rule has stopped detecting
     * and every other test in this class is vacuous.
     */
    @Test
    fun positiveControlDetectsLooseBinding() {
        val synthetic = """
            package com.example

            fun coreLoggingModule() = module {
                factory { Logger.withTag("App") }
            }

            fun strayHelper() {
                single { SomethingElse(get()) }
            }
        """.trimIndent()

        val strayDeclarations = bindingDeclarationsIn(synthetic, facadeNames = setOf("coreLoggingModule"))

        if (strayDeclarations.isEmpty()) {
            fail(
                "the facade scan found nothing in a file that declares a binding outside a " +
                    "facade function — the rule is no longer detecting, so every other " +
                    "assertion in DiFacadeTest is vacuous",
            )
        }
        kotlin.test.assertEquals(1, strayDeclarations.size, "expected the one stray binding")
    }

    @Test
    fun `Modules kt declares no binding outside a facade function`() {
        val stray = bindingDeclarationsIn(modulesFile.codeOnly(), FACADE_FUNCTIONS)
        if (stray.isNotEmpty()) {
            fail(
                "core/di/Modules.kt declares Koin bindings outside a facade function. " +
                    "It is a facade aggregator, not a source of truth — put the binding in " +
                    "the owning feature's *DiModule.kt, or wrap it in one of " +
                    "$FACADE_FUNCTIONS. Offenders: $stray",
            )
        }
    }

    @Test
    fun `per-domain DiModule files declare no bindings`() {
        val offenders = scope.files
            .filter { it.name.endsWith("DiModule.kt") }
            .filter { it.packageName().startsWith("$PKG.core.di") }
            .filter { file ->
                bindingDeclarationsIn(file.codeOnly(), facadeNames = emptySet()).isNotEmpty()
            }
            .map { it.path }

        if (offenders.isNotEmpty()) {
            fail(
                "A per-domain *DiModule.kt declares Koin bindings directly. Each must return a " +
                    "single `module { }` built from its feature's declarations, so the " +
                    "aggregator in core/di/Modules.kt stays the only place that composes them. " +
                    "Offenders: $offenders",
            )
        }
    }

    @Test
    fun `every facade function in Modules kt is one of the known set`() {
        val declared = modulesFile.functions()
            .filter { it.name in setOf("domainModule", "coreLoggingModule", "aiToolsModule") }
            .map { it.name }

        val unknown = declared.filterNot { it in FACADE_FUNCTIONS }
        if (unknown.isNotEmpty()) {
            fail("Unexpected top-level function in Modules.kt: $unknown")
        }
    }

    /**
     * Lines in [source] that call a Koin binding declaration and are not inside a function
     * whose name is in [facadeNames].
     *
     * Brace-depth counting rather than a PSI walk, because the question is positional —
     * "is this declaration inside a facade body" — and the file is a facade aggregator by
     * construction, so the scan only ever runs over a handful of lines.
     */
    private fun bindingDeclarationsIn(source: String, facadeNames: Set<String>): List<String> {
        val offenders = mutableListOf<String>()
        var depth = 0
        var enclosingFns = mutableListOf<String>()

        source.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()

            // A top-level `fun name(` opens a function body at depth 0.
            if (depth == 0 && line.startsWith("fun ")) {
                enclosingFns.add(line.substringAfter("fun ").substringBefore("(").trim().substringBefore(":").trim())
            }

            val firstToken = line.substringBefore(" ").substringBefore("(").substringBefore("{").trim()
            if (firstToken in BINDING_CALLS && enclosingFns.none { it in facadeNames }) {
                val owner = enclosingFns.lastOrNull() ?: "<file body>"
                offenders += "line ${index + 1} in $owner"
            }

            depth += line.count { it == '{' }
            depth -= line.count { it == '}' }
            if (depth <= 0) {
                depth = 0
                enclosingFns.clear()
            }
        }
        return offenders
    }
}
