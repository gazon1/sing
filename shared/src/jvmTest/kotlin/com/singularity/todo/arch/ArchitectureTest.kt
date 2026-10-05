@file:Suppress("VariableNaming", "PropertyName")

package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
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
@Tag("slow")
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

        /** File name including extension, for rules that key on the file rather than the package. */
        private fun KoFileDeclaration.fileName(): String = path.replace('\\', '/').substringAfterLast('/')

        /** File's own text, for rules that match call sites rather than declarations. */
        private fun KoFileDeclaration.sourceText(): String = text.replace('\\', '/')

        /**
         * Source-set-relative path, forward slashes, with the `kotlin/` source root
         * dropped: `com/singularity/todo/feature/notes/Ids.kt`.
         *
         * This is what allowlists key on, so an entry names one file rather than
         * every file that happens to share a base name.
         */
        private fun relativePath(file: KoFileDeclaration): String {
            val normalizedRoot = commonMainRoot.replace('\\', '/').trimEnd('/')
            val under = file.path.replace('\\', '/').removePrefix("$normalizedRoot/")
            return under.substringAfter("/kotlin/").ifEmpty { under }
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
         * The single file allowed to resolve the *system* dark flag.
         *
         * `SingularityTheme.kt` owns it: the flag appears there once as the default
         * value of its `darkTheme` parameter (correct — it is the fallback for previews
         * that pass nothing) and once as the value published through `LocalIsDarkTheme`.
         * Every other consumer must read the local.
         */
        private val DARK_THEME_READER_ALLOWLIST = setOf("SingularityTheme.kt")

        /**
         * Feature files still carrying fixed colours, each with the reason it is
         * not a theme file yet. Every entry is a tracked follow-up — an allowlist
         * entry without an issue is how an allowlist becomes permanent.
         *
         * - `feature/notes/Ids.kt` — **not** a UI palette. `NoteColor` is a domain
         *   value class holding the user's note-highlight colour; it is data, not
         *   theming, and it is the one entry that should stay listed permanently.
         *
         * Matched by path suffix, not by bare file name. Four files in this
         * repository are called `Ids.kt`, so a bare-name entry silently allowed
         * all four — and a literal added to a sibling feature's `Ids.kt` would
         * have passed the gate while going unnoticed. That is the failure mode
         * this rule exists to prevent, reproduced inside the rule itself.
         */
        private val COLOUR_LITERAL_ALLOWLIST = setOf(
            "com/singularity/todo/feature/notes/Ids.kt",
        )

        /**
         * The ceiling for `colour allowlist does not grow`.
         *
         * Six when the ratchet landed, now **one**, and the one is permanent
         * (`feature/notes/Ids.kt` — a domain value class, not theming); the others
         * is the remainder of #197, and it lowers this number too.
         *
         * Four of the six were *relocated* rather than converted, and that is the
         * finding: #199 was filed as four screens with "the same defect as the
         * tasks feature", and measuring them showed three hold verdicts or
         * categorical scales — a validation status, a swipe consequence, a chart
         * series palette, a per-profile identity tint. None of those is a theme
         * role, and converting them would have tied "this is overdue" and "which
         * profile is this" to a colour preference. They moved into the theme
         * package, where a reader finds them, and stopped pretending to be
         * derived. When it reaches one, delete the ratchet:
         * a ceiling of one with a permanent single entry is a documented fact, not
         * a guard.
         */
        private const val COLOUR_ALLOWLIST_CEILING = 1

        /**
         * Files allowed to reach the filesystem from commonMain.
         *
         * `AdrTools` is the MCP `write_adr` tool: its whole job is to write a markdown
         * file into `docs/decisions/`. Routing that through the user-facing FileSystem
         * port would put a documentation write on the app's data path, so the direct
         * access is the design, not an oversight (ADR 2026-09-26-konsist-architecture-tests,
         * debt entry).
         */
        private val FILE_API_ALLOWLIST = setOf("feature/ai/tools/AdrTools.kt")

        /**
         * Column names that make a DAO write profile-scoped.
         *
         * `user_id` on every entity table. `owner_id` on
         * `sync_state`, whose primary key is `(owner_id, profile_id)` — the same pair
         * of semantics under a different name. Only `user_id` was recognised, so every
         * correctly scoped `sync_state` write was reported as a hole, and the two ways
         * to silence that were both wrong: allowlisting a scoped query claims in
         * writing that it is cross-profile, and renaming the column ties a schema
         * decision to a lint rule.
         */
        private val SCOPING_COLUMNS = listOf("user_id", "owner_id")

        /**
         * DAO mutations that are intentionally not user-scoped, keyed by
         * `"<DaoName>.<method>"`. Every entry is a deliberate decision recorded in
         * `docs/decisions/2026-09-27-write-layer-soundness.md`; adding one without
         * updating that ADR is a regression.
         *
         * The pattern is: either the row is not user data at all (device-local
         * bookkeeping, internal machinery, app-wide config), or the table has no
         * ownership column to scope by.
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
            // The dead-letter shelf is the same transport queue by another name: a
            // patch that exceeded its retry budget is moved there verbatim, and the
            // rows carry a patch id rather than a user. Absent until now — the gate
            // had never been run against the file.
            "SyncDeadLetterDao.delete",
            "SyncDeadLetterDao.clearAll",
            // `sync_state` is scoped by `owner_id` (see SCOPING_COLUMNS) on every other
            // write. This one is the deliberate exception: a sign-out drains every
            // scope, because leaving the previous account's cursor behind would make
            // the next sign-in resume inside its history.
            "SyncStateDao.clearAll",
            // A sign-out drops every scope's shadow at once, like the state table. A
            // scoped drain would leave the next account diffing against a base that
            // describes the previous one's uploads.
            "SyncShadowDao.clearAll",
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

        /**
         * Non-`*DiModule.kt` packages allowed to bind a `*RepositoryImpl` directly.
         *
         * `core/di` is the aggregator package documented in ADR
         * `2026-09-27-di-module-aggregator-narrative`; `Modules.kt` binds
         * `ProfileRepositoryImpl` directly because that binding is not feature-scoped.
         * Scoped to the DI package rather than to the whole feature tree — the previous
         * version whitelisted `feature.agenda`/`tasks`/`notes` wholesale, which let any
         * screen or ViewModel in those packages import an implementation silently.
         */
        private val DI_IMPL_ALLOWLIST = setOf("$PKG.core.di")

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
    fun `colour literals live in theme files, not in feature code`() {
        // A fixed colour in a composable is a value that cannot follow the theme.
        // The failure it produces is invisible in review and certain in the app:
        // the Tasks feature carried 27 of them in two `object`s, so a user in the
        // default light mode saw a dark task screen and the accent picker did
        // nothing there. See ADR
        // 2026-10-05-materialkolor-seed-palette-and-resolved-dark-flag.
        //
        // Scope is the WHOLE feature tree, not `feature/*/presentation`. Several
        // screens keep their composables directly under `feature/<x>/`, so a rule
        // scoped to a `presentation` segment would pass while 22 real literals sat
        // outside it — a green gate over an unchanged problem.
        val offenders = scope.files
            .filterNot { file -> relativePath(file) in COLOUR_LITERAL_ALLOWLIST }
            .filter { it.packageName().startsWith("$PKG.feature") }
            .filterNot { "/theme/" in it.path.replace('\\', '/') }
            .filter { file -> "Color(0x" in file.codeOnly() }
        assertNoOffenders(
            offenders,
            "a feature composable must read the active theme; fixed colours belong in a theme file",
        ) { it.path }
    }

    @Test
    fun `a preview uses the app theme, not a bare MaterialTheme`() {
        // A bare `MaterialTheme { }` gives the M3 *baseline* scheme. Once a
        // composable reads `MaterialTheme.colorScheme`, that is a palette the app
        // never produces — and the preview is the only place a human sees the
        // screen without launching it. Seven previews did exactly this after the
        // task surfaces moved onto the theme, and they rendered a screen that
        // looked plausible and was wrong.
        //
        // The wrapper is the fix; this rule is what stops it coming back.
        //
        // `codeOnly()` on the theme check, and deliberately not on the `@Preview`
        // scan: a file has to *say* `@Preview` to be a preview, but a comment
        // explaining this very rule quotes `MaterialTheme {` verbatim, and reading
        // that as an offender would make the fix unrepresentable in its own repo.
        val offenders = scope.files
            .filter { it.sourceText().contains("@Preview") }
            .filter { "MaterialTheme {" in it.codeOnly() }
        assertNoOffenders(
            offenders,
            "a @Preview must wrap in PreviewThemed, not a bare MaterialTheme — see PreviewSamples.kt",
        ) { it.path }
    }

    @Test
    fun `the colour allowlist does not grow`() {
        // The colour rule protects additively: a new literal in a feature composable
        // fails the build. It does not stop anyone from *exempting* the file, which
        // is how a list of debt becomes permanent without a single line of
        // remediation. Debt retired by paperwork is not debt retired.
        //
        // The ceiling is the number of entries the list had when this rule landed.
        // Lowering it is the point of the rule: #199 and #197 each remove entries,
        // and a change that raises the count has to say here why, in the diff.
        val actual = COLOUR_LITERAL_ALLOWLIST.size
        assertTrue(
            actual <= COLOUR_ALLOWLIST_CEILING,
            "COLOUR_LITERAL_ALLOWLIST grew from $COLOUR_ALLOWLIST_CEILING to $actual entries. " +
                "Exempting a file is not the same as converting it: either convert the file to the " +
                "theme (and remove its entry), or state the exemption and raise this ceiling in the " +
                "same commit — with a reason, not a number.",
        )
    }

    @Test
    fun `only the theme itself reads isSystemInDarkTheme`() {
        // The app's dark mode is a user setting (SettingsBundle.Appearance.darkTheme,
        // default false), not a system read-through, and there is no "follow system"
        // tri-state. A subtree that re-derives it from the OS silently desynchronises
        // from the palette actually in effect — this shipped: the calendar screen
        // painted its hand-authored navy dark palette onto a light app whenever the
        // system was dark and the user had left the default setting alone.
        //
        // Theme-aware code reads LocalIsDarkTheme, provided by SingularityTheme from
        // the same value it used to build the color scheme. See ADR
        // 2026-10-05-materialkolor-seed-palette-and-resolved-dark-flag.
        val offenders = scope.files
            .filterNot { it.fileName() in DARK_THEME_READER_ALLOWLIST }
            .filter { file ->
                // codeOnly() so a KDoc mention of the name does not count as a call.
                "isSystemInDarkTheme(" in file.codeOnly()
            }
        assertNoOffenders(
            offenders,
            "only SingularityTheme may resolve the system dark flag; consumers read LocalIsDarkTheme",
        ) { it.path }
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
    fun `commonMain does not use JVM file APIs`() {
        // AGENTS.md ban #5 is "no java.io.File — go through the FileSystem port".
        // The ban is on reaching the disk from shared code, so it covers the whole
        // family rather than one class: `java.nio.file.Files` and `kotlin.io.path.*`
        // hit the same disk without the port and used to slip past the narrower check.
        //
        // `java.io.IOException` is deliberately NOT covered — it is an exception type,
        // not filesystem access, and four stores legitimately catch it around a
        // DataStore/prefs read.
        val bannedPrefixes = listOf("java.nio.file.", "kotlin.io.path.")
        val bannedExact = listOf(
            "java.io.File",
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.RandomAccessFile",
        )
        val offenders = scope.files
            .filterNot { file -> FILE_API_ALLOWLIST.any { file.path.replace('\\', '/').endsWith(it) } }
            .filter { file ->
                file.importFqns().any { fqn ->
                    fqn in bannedExact || bannedPrefixes.any { fqn.startsWith(it) }
                }
            }
        assertNoOffenders(
            offenders,
            "commonMain must not touch the filesystem directly — use the FileSystem port " +
                "(AGENTS.md ban #5). A test-only helper that reads source files belongs in " +
                "a test source set, not in commonMain.",
        ) { it.path }
    }

    @Test
    fun `repository implementations are imported only from di modules`() {
        val offenders = scope.files
            .filter { file -> file.importFqns().any { it.endsWith("RepositoryImpl") } }
            // Keyed on the *file*, not the package. The earlier version whitelisted whole
            // feature packages (agenda/tasks/notes), which meant any screen, ViewModel or
            // slot in those packages could import an implementation and the rule stayed
            // green — it enforced "the feature knows the impl", not "only DI knows the impl",
            // which is the boundary it exists to protect.
            .filter { file -> file.packageName() !in DI_IMPL_ALLOWLIST }
            .filter { file -> !file.fileName().endsWith("DiModule.kt") }
        assertNoOffenders(
            offenders,
            "*RepositoryImpl may only be referenced by *DiModule.kt (core or feature)",
        ) { it.path }
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
                .filter { (_, _, sql) -> SCOPING_COLUMNS.none { it in sql } }
                .map { (dao, method, _) -> "${file.path}: $dao.$method() has no ownership predicate" }
        }
        assertNoOffenders(
            offenders,
            "every UPDATE/DELETE must filter on an ownership column so a write cannot " +
                "cross profiles. If a query is genuinely global, add it to " +
                "GLOBAL_DAO_MUTATION_ALLOWLIST and record the decision in the write-layer ADR.",
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

    /**
     * Repository read methods must be user-scoped.
     *
     * This rule scans `*RepositoryImpl` files and checks that every DAO getter it calls
     * either (a) has `user_id` in its SQL WHERE clause, or (b) is in [UNSCOPED_READ_ALLOWLIST].
     *
     * The allowlist covers methods that ARE safe because the caller already guarantees the
     * taskId/task belongs to the current user — the cross-ref table itself has no userId
     * column. Adding to this list requires an ADR.
     */
    @Test
    fun `repository read methods are user-scoped`() {
        val offenders = scope.files
            .filter { file -> Regex("""class\s+\w+RepositoryImpl\b""").containsMatchIn(file.codeOnly()) }
            .flatMap { file ->
                val code = file.codeOnly()
                queryFunctions(file)
                    .filter { (_, _, sql) ->
                        !Regex("""\b(UPDATE|DELETE|INSERT|REPLACE|UPSERT)\b""", RegexOption.IGNORE_CASE)
                            .containsMatchIn(sql)
                    }
                    .filter { (dao, method, sql) ->
                        "$dao.$method" !in UNSCOPED_READ_ALLOWLIST && "user_id" !in sql
                    }
                    .map { (dao, method, _) -> "${file.path}: $dao.$method() has no userId in SQL" }
            }
        assertNoOffenders(
            offenders,
            "repository read methods must scope by userId (SQL has user_id in WHERE, " +
                "or DAO method is in UNSCOPED_READ_ALLOWLIST if safe by construction). " +
                "Adding to the allowlist requires an ADR.",
        ) { it }
    }

    /**
     * Methods whose DAO calls are safe WITHOUT userId filtering.
     * All are "safe by construction": the caller guarantees the entity belongs to the
     * current user, so a cross-user leak is impossible.
     *
     * Adding an entry requires an ADR documenting why the call site guarantees safety.
     */
    private val UNSCOPED_READ_ALLOWLIST = setOf(
        // task_dependencies cross-ref table has no user_id column; the task_id
        // parameter comes from an already-scoped task list (the caller only works
        // with tasks owned by the current profile).
        "TaskDao.getDependencyIdsForTask",
        "TaskDao.getDependencyIdsForUser",
        "TaskDao.getBlockingTaskIdsForTask",
    )

    /**
     * Nav3 backStack seed must match the start route, or Detail must be explicitly added.
     *
     * [NavDisplay] renders based on `backStack.top`, not the `start` parameter.
     * When a graph entry creates a stack seeded with `Create(null)` but passes
     * `Detail` as `start`, the editor shows an empty `Create` screen.
     *
     * The fix: when `start` resolves to a route type that differs from the stack seed,
     * the entry block MUST call `backStack.add(startRoute)` before rendering so that
     * `NavDisplay` dispatches to the correct entry immediately.
     *
     * This rule scans `JvmNavEntries.kt` for the problematic pattern:
     * `rememberInMemoryNavBackStack(TasksRoute.Create(...))` + `start = ...Detail(...)`
     * without a corresponding `backStack.add(detail)` call.
     *
     * Allowlist: `AgendaNavGraph` where seed = `route.start` (identical by construction,
     * no explicit add needed). Documented in ADR 2026-10-03-nav3-backstack-top-vs-start-dispatch.
     */
    @Test
    fun `nav3 backStack seed mismatch requires explicit add for Detail routes`() {
        val jvmNavEntries = scope.files
            .filter { it.path.replace('\\', '/').endsWith("feature/nav/JvmNavEntries.kt") }
            .firstOrNull()
            ?: return // Not found on Android — rule is JVM-only

        val source = jvmNavEntries.text

        // Find every entry<AppDestination.Xxx> block and its body
        val entryBlockPattern = Regex(
            """entry<AppDestination\.(\w+)> \{ route ->\s*\n(.*?)\n\s+\}""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val offenders = mutableListOf<String>()

        entryBlockPattern.findAll(source).forEach { match ->
            val graphName = match.groupValues[1] // e.g. "TasksGraph", "AgendaGraph"
            val body = match.groupValues[2]

            // Skip AgendaNavGraph — seed = route.start by construction (ADR 2026-10-03)
            if (graphName == "AgendaGraph") return@forEach

            // Find rememberInMemoryNavBackStack(seed) in this block
            val seedMatch = Regex(
                """rememberInMemoryNavBackStack\(\s*(\w+)\s*\(""",
            ).find(body)

            // Find the start = ... expression in the NavGraph call
            val startMatch = Regex(
                """start\s*=\s*(.+?),""",
            ).find(body)

            if (seedMatch != null && startMatch != null) {
                val seedType = seedMatch.groupValues[1] // e.g. "TasksRoute.Create"
                val startExpr = startMatch.groupValues[1].trim()

                // Detect mismatch: seed is Create(null) but start contains Detail
                val seedIsCreate = seedType.contains("Create")
                val startIsDetail = startExpr.contains("Detail")

                if (seedIsCreate && startIsDetail) {
                    // Must have a backStack.add() call for the Detail route
                    val hasAddCall = Regex("""backStack\.add\(""").containsMatchIn(body)
                    if (!hasAddCall) {
                        offenders.add(
                            "JvmNavEntries entry<AppDestination.$graphName>: " +
                                "stack seeded with $seedType but start=$startExpr — " +
                                "NavDisplay renders stack.top, not start. " +
                                "Add 'backStack.add(startRoute)' before TasksNavGraph(...) " +
                                "when startRoute is TasksRoute.Detail. " +
                                "See ADR 2026-10-03-nav3-backstack-top-vs-start-dispatch.",
                        )
                    }
                }
            }
        }

        assertNoOffenders(
            offenders,
            "Nav3 backStack seed / start mismatch must be resolved with explicit backStack.add()",
        ) { it }
    }
}
