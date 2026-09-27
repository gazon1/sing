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

        /** Strips KDoc and line comments so prose about DAOs is not read as a call. */
        private fun KoFileDeclaration.codeOnly(): String =
            text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//[^\n]*"""), "")

        /**
         * Every `@Query(...)` + following `fun` in this file, as
         * `Triple(daoName, methodName, sql)`. Read from the raw file text so the
         * assertion does not depend on Konsist's annotation-argument API.
         *
         * The owning DAO is resolved by *position* — the nearest `interface *Dao`
         * declaration preceding the query. Taking the first interface in the file
         * would attribute every query in a multi-DAO file to the first one.
         */
        private fun queryFunctions(file: KoFileDeclaration): List<Triple<String, String, String>> {
            val source = file.codeOnly()
            val interfaces = Regex("""interface\s+(\w*Dao)\b""").findAll(source)
                .map { it.groupValues[1] to it.range.first }
                .toList()
            fun ownerAt(offset: Int): String = interfaces.lastOrNull { it.second < offset }?.first ?: "?"

            val pattern = Regex(
                """@Query\(\s*((?:"(?:[^"\\]|\\.)*"\s*)+)\)\s*\n\s*(?:suspend\s+)?fun\s+(\w+)""",
            )
            val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
            return pattern.findAll(source).map { m ->
                Triple(
                    ownerAt(m.range.first),
                    m.groupValues[2],
                    literal.findAll(m.groupValues[1]).joinToString("") { it.groupValues[1] },
                )
            }.toList()
        }

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

        /**
         * DAO mutations that are intentionally not user-scoped, keyed by
         * `"<DaoName>.<method>"`. Every entry is a deliberate decision recorded in
         * `docs/decisions/2026-09-27-write-layer-soundness.md`; adding one without
         * updating that ADR is a regression.
         *
         * The pattern is: either the row is not user data at all (device-local
         * bookkeeping, internal machinery, app-wide config), or the table has no
         * `user_id` column to scope by.
         */
        private val GLOBAL_DAO_MUTATION_ALLOWLIST = setOf(
            // Retention sweeps over the whole table — device-local telemetry.
            "LlmUsageDao.pruneOlderThan",
            "TaskDao.pruneOlderThan",
            // A profile *is* the scope, not something scoped by a user id.
            "ProfileDao.deleteById",
            // App-wide remote-config cache, not user data.
            "RemoteConfigCacheDao.deleteDefault",
            "RemoteConfigDao.deleteDefault",
            // Sync outbox rows are internal machinery keyed by patchId/entityId.
            "SyncOutboxDao.delete",
            "SyncOutboxDao.markFailed",
            "SyncOutboxDao.deleteByEntity",
            "SyncOutboxDao.clearAll",
            // calendar_sync_task_map has no user_id column: it is device-local
            // bookkeeping mapping calendar events, not user-owned data. Making it
            // per-profile would need a schema migration — ledger #17.
            "CalendarSyncTaskMapDao.delete",
            "CalendarSyncTaskMapDao.deleteByEventId",
            "CalendarSyncTaskMapDao.deleteStale",
            "CalendarSyncTaskMapDao.clearAll",
        )

        /**
         * Core-layer files permitted to call a DAO mutation directly.
         *
         * `BackupImporter` restores rows for an arbitrary `userId`, while every
         * user-scoped repository resolves its target from the *ambient* profile and
         * rejects a foreign one — so it cannot go through them. Documented at the
         * write loop in the file itself (ledger #2 in the write-layer ADR).
         */
        private val CORE_DAO_WRITE_ALLOWLIST = setOf(
            "core/backup/BackupImporter.kt",
        )

        /** Class-name suffixes that are themselves a repository/port implementation. */
        private val REPOSITORY_IMPL_SUFFIXES =
            listOf("RepositoryImpl", "Recorder", "Adapters", "ArchiveRepository")

        /** Packages whose DAOs are legitimately written by the class that owns them. */
        private val CORE_DAO_OWNER_PACKAGES =
            listOf("$PKG.core.database", "$PKG.core.sync", "$PKG.core.llm")

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

    @Test
    fun `DAO mutations are ownership-scoped`() {
        val mutating = Regex("""\b(UPDATE|DELETE\s+FROM)\b""", RegexOption.IGNORE_CASE)
        val offenders = scope.files.flatMap { file ->
            queryFunctions(file)
                .filter { (_, _, sql) -> mutating.containsMatchIn(sql) }
                .filter { (dao, method, _) -> "$dao.$method" !in GLOBAL_DAO_MUTATION_ALLOWLIST }
                .filter { (_, _, sql) -> "user_id" !in sql }
                .map { (dao, method, _) -> "${file.path}: $dao.$method() has no user_id in its WHERE" }
        }
        assertNoOffenders(
            offenders,
            "every UPDATE/DELETE must filter on user_id so a write cannot cross users. " +
                "If a query is genuinely global, add it to GLOBAL_DAO_MUTATION_ALLOWLIST and " +
                "record the decision in the write-layer ADR.",
        ) { it }
    }

    @Test
    fun `core layer reaches DAO mutations only through a repository`() {
        val mutationCall = Regex("""\.\w*(upsert|insert|update|delete|softDelete|restore|clear)\w*\s*\(""")
        val offenders = scope.files
            .filterNot { file -> CORE_DAO_WRITE_ALLOWLIST.any { file.path.endsWith(it) } }
            .map { it.packageName() to it }
            .filter { (pkg, _) -> pkg.startsWith("$PKG.core.") }
            .filter { (pkg, _) -> CORE_DAO_OWNER_PACKAGES.none { pkg == it || pkg.startsWith("$it.") } }
            .filter { (_, file) -> Regex("""\b\w*[Dd]ao\b""").containsMatchIn(file.codeOnly()) }
            .filter { (_, file) -> mutationCall.containsMatchIn(file.codeOnly()) }
            .filterNot { (_, file) ->
                Regex("""class\s+(\w+)""").find(file.codeOnly())?.groupValues?.get(1)
                    ?.let { n -> REPOSITORY_IMPL_SUFFIXES.any { n.endsWith(it) } } == true
            }
            .map { (_, file) -> file.path }
        assertNoOffenders(
            offenders,
            "core/ must not write DAOs directly unless it *is* the repository for that DAO, " +
                "or it is a documented allowlist entry (BackupImporter restores for an arbitrary " +
                "userId, which ambient-scoped repositories cannot express).",
        ) { it }
    }
}
