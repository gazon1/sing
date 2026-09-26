package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import kotlin.test.Test
import kotlin.test.fail

/**
 * Automated architectural boundaries (Konsist) — the enforcement layer for the rules
 * that previously lived only in the manual `singularity-todo-clean-architecture-audit`
 * grep audit. Runs as part of `:shared:jvmTest` (check.sh + CI `test-and-check`).
 *
 * Scope: `shared/src/commonMain/kotlin` production sources. Platform source sets and
 * tests are out of scope.
 *
 * Allowlists below are deliberate debt entries — every one is documented in
 * `docs/decisions/2026-09-26-konsist-architecture-tests.md`. Adding a new entry
 * without updating the ADR is a regression.
 */
class ArchitectureTest {

    companion object {
        private const val PKG = "com.singularity.todo"

        /** Injected by the jvmTest task config in `shared/build.gradle.kts`. */
        private val commonMainRoot: String = System.getProperty("commonMain.root")
            ?: error(
                "commonMain.root system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )

        // Absolute path — hence scopeFromExternalDirectory (scopeFromDirectory resolves
        // relative to Konsist's detected project root and would double the prefix).
        private val scope = Konsist.scopeFromExternalDirectory(commonMainRoot)

        /**
         * Package name derived from the file path (the directory layout mirrors
         * packages in commonMain, so this is deterministic and needs no extra API).
         */
        private fun KoFileDeclaration.packageName(): String {
            val normalizedPath = path.replace('\\', '/')
            val normalizedRoot = commonMainRoot.replace('\\', '/').trimEnd('/')
            return normalizedPath
                .removePrefix("$normalizedRoot/")
                .substringBeforeLast('/')
                .replace('/', '.')
        }

        private fun KoFileDeclaration.importFqns(): List<String> = imports.map { it.name }

        /**
         * Files inside `feature/<x>/<layer>(/...)` whose imports match [forbidden].
         * Top-level feature packages without a layer segment are out of scope —
         * same semantics as the manual audit skill.
         */
        private fun offendingFiles(layer: String, forbidden: (importFqn: String) -> Boolean): List<KoFileDeclaration> =
            scope.files
                .filterNot { file -> LAYER_ALLOWLIST.any { file.path.endsWith(it) } }
                .filter {
                    val pkg = it.packageName()
                    pkg.startsWith("$PKG.feature.") && pkg.contains(".$layer")
                }
                .filter { file -> file.importFqns().any(forbidden) }

        private fun <T> assertNoOffenders(offenders: List<T>, rule: String, describe: (T) -> String) {
            if (offenders.isEmpty()) return
            val listed = offenders.joinToString("\n") { "  - ${describe(it)}" }
            fail("Architectural rule violated: $rule\n$listed")
        }

        /**
         * `CreateTaskFromDraft` (domain/usecase) consumes presentation-state
         * `TaskDraft` / `DueDateOption`. Those types are `@Serializable` data holders
         * annotated with Compose `@Immutable` for stability — moving them to domain
         * would violate the "pure domain has no Compose imports" convention
         * (see ADR 2026-09-26-konsist-architecture-tests, debt entry).
         */
        private val LAYER_ALLOWLIST = setOf("feature/tasks/domain/usecase/CreateTaskFromDraft.kt")

        /** Packages allowed to import Koog (AGENTS.md ban #7; genui sanctioned). */
        private val KOOG_ALLOWED_PACKAGES = listOf(
            "$PKG.feature.ai",
            "$PKG.feature.genui",
            "$PKG.core.di",
            "$PKG.core.llm",
            "$PKG.core.ai",
        )
    }

    @Test
    fun `feature domain does not import presentation or data layers`() {
        val offenders = offendingFiles("domain") { imp ->
            imp.startsWith("$PKG.feature.") &&
                (imp.contains(".presentation.") || imp.contains(".data."))
        }
        assertNoOffenders(offenders, "domain layer must depend only on domain models, ports and core") { it.path }
    }

    @Test
    fun `feature presentation does not import data layer`() {
        val offenders = offendingFiles("presentation") { imp ->
            imp.startsWith("$PKG.feature.") && imp.contains(".data.")
        }
        assertNoOffenders(offenders, "presentation must not reach into feature data packages") { it.path }
    }

    @Test
    fun `feature data does not import presentation layer`() {
        val offenders = offendingFiles("data") { imp ->
            imp.startsWith("$PKG.feature.") && imp.contains(".presentation.")
        }
        assertNoOffenders(offenders, "data layer must not reference presentation types") { it.path }
    }

    @Test
    fun `koog imports are confined to ai-related packages`() {
        val offenders = scope.files
            .filter { file -> file.importFqns().any { it.startsWith("ai.koog") } }
            .filter { file ->
                val pkg = file.packageName()
                KOOG_ALLOWED_PACKAGES.none { allowed -> pkg == allowed || pkg.startsWith("$allowed.") }
            }
        assertNoOffenders(offenders, "ai.koog imports are allowed only in $KOOG_ALLOWED_PACKAGES") { it.path }
    }

    @Test
    fun `commonMain does not import java io File`() {
        val offenders = scope.files
            .filter { file -> file.importFqns().any { it == "java.io.File" } }
        assertNoOffenders(offenders, "use the FileSystem port instead of java.io.File (AGENTS.md ban #5)") { it.path }
    }

    @Test
    fun `repository implementations are imported only from core di`() {
        val offenders = scope.files
            .filter { file -> file.importFqns().any { it.endsWith("RepositoryImpl") } }
            .filter { file ->
                val pkg = file.packageName()
                pkg != "$PKG.core.di" && !pkg.startsWith("$PKG.core.di.")
            }
        assertNoOffenders(offenders, "*RepositoryImpl may only be referenced by the DI composition root") { it.path }
    }

    @Test
    fun `repository interfaces declare no Blocking methods`() {
        val offenders = scope.interfaces()
            .filter { it.name.endsWith("Repository") }
            .filter { iface -> iface.functions().any { fn -> fn.name.endsWith("Blocking") } }
        assertNoOffenders(
            offenders,
            "repositories expose suspend + Flow APIs; *Blocking() methods are banned (AGENTS.md ban #2)",
        ) { it.path }
    }
}
