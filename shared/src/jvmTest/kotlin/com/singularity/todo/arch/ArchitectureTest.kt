@file:Suppress("VariableNaming", "PropertyName")

package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Automated architectural boundaries (Konsist) — the enforcement layer for the rules
 * that previously lived only in the manual `singularity-todo-clean-architecture-audit`
 * grep audit. Runs as part of `:shared:jvmTest` (check.sh + the CI `tests` job).
 *
 * Scope: `shared/src/commonMain/kotlin` production sources. Platform source sets and
 * tests are out of scope.
 *
 * Allowlists below are deliberate debt entries — every one is documented in
 * `docs/decisions/2026-09-26-konsist-architecture-tests.md`. Adding a new entry
 * without updating the ADR is a regression.
 */
@Tag("slow")
@Tag("slow:konsist")
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
                .filter { file -> breaksTheLayerRule(file.packageName(), file.importFqns(), layer, forbidden) }

        /**
         * Whether a file breaks the layer rule.
         *
         * A pure function of the package and the imports, so that the positive control
         * can run it over a synthetic file: a rule that has never been seen to fire is a
         * rule that has stopped working, and `docs/architecture/rule-control-audit.md` §3
         * calls that class of rule "Type C".
         */
        private fun breaksTheLayerRule(
            packageName: String,
            importFqns: List<String>,
            layer: String,
            forbidden: (importFqn: String) -> Boolean,
        ): Boolean = isInLayer(packageName, layer) && importFqns.any(forbidden)

        /**
         * Whether the package is in the feature's `layer` layer.
         *
         * The layer is a segment of the package: `feature.tasks.domain.logic` is in the
         * domain layer, `feature.tasks.presentation.screen` is in the presentation layer,
         * and so is `feature.ai.chat.domain` — one level deeper, and the rule still sees
         * it.
         *
         * A package that renders the app's own vocabulary is not a domain layer, and it
         * does not claim to be one: the components that draw a task card live in
         * `feature.genui.render.app`.
         */
        private fun isInLayer(packageName: String, layer: String): Boolean =
            packageName.startsWith("$PKG.feature.") && packageName.contains(".$layer")

        /**
         * Whether the source builds the question the index is asked by hand.
         *
         * A pure function of the package and the source, so that the positive
         * control can run it over a synthetic file: a rule that has never been
         * seen to fire is a rule that has stopped working.
         *
         * @param packageName the package the file is in.
         * @param source the file's code, without its comments.
         */
        private fun buildsTheQuestionByHand(packageName: String, source: String): Boolean =
            packageName != "$PKG.core.search" && INDEX_QUESTION.containsMatchIn(source)

        /** The shape of a hand-built question: the type, and a call. */
        private val INDEX_QUESTION = Regex("TextMatchQuery\\s*\\(")

        private fun <T> assertNoOffenders(offenders: List<T>, rule: String, describe: (T) -> String) {
            if (offenders.isEmpty()) return
            val listed = offenders.joinToString("\n") { "  - ${describe(it)}" }
            fail("Architectural rule violated: $rule\n$listed")
        }

        /**
         * `CreateTaskFromDraft` (domain/usecase) consumes presentation-state
         * `TaskDraft` / `DueDateOption`.
         *
         * The reason is the layer, not an annotation: those types live in
         * presentation so that the domain does not depend on it, and the use
         * case is the one place where the draft crosses that boundary on its
         * way into the domain. They used to carry Compose `@Immutable` for
         * stability, which is not what makes them stable now — the stability
         * contract (`config/compose/stability_config.conf`) is — and which
         * would have been a worse reason, because it made the domain's
         * dependency on the UI toolkit a permanent one.
         *
         * @see 2026-10-22-compose-stability-contract
         * @see 2026-09-26-konsist-architecture-tests
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
            // The profile reset is a cross-repository operation by definition: it
            // empties the profile, including the tables whose repository has no
            // bulk delete. Every one of those deletes is scoped to the profile's
            // user id, so it cannot cross a profile — the property the rule
            // protects is kept, and the ownership it asks for is meaningless for
            // a reset that owns nothing. The tables it reaches and the ones it
            // leaves are in `docs/architecture/wipe-inventory.md`.
            "core/wipe/ProfileContentResetter.kt",
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

        /**
         * A call that writes through a DAO.
         *
         * The receiver is part of the match, because the receiver is what says what
         * the call writes: `taskDao.deleteForUser(...)` is a row, `fs.delete(...)` is a
         * file. `db.taskDao().deleteForUser(...)` is the same row — the `()` is allowed
         * between the name and the dot.
         */
        private val DAO_WRITE = Regex(
            """\b\w*[Dd]ao(?:\(\))?\.\w*(?:upsert|insert|update|delete|softDelete|restore|clear)\w*\s*\(""",
        )

        /**
         * A DAO the file holds, by the name it is held under.
         *
         * The name a DAO is given says nothing: `private val tasks: TaskDao` is a DAO
         * whatever it is called, and a rule that only recognises `taskDao` misses it.
         * So the names come from the declarations — a property whose type is a DAO —
         * and the receiver of a write call is compared against those.
         */
        private val DAO_DECLARATION = Regex("""\b(?:val|var)\s+(\w+)\s*:\s*\w*Dao\b""")

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
    fun `the question asked of the index is built by the rule`() {
        // The rule knows what the index can answer — see `FreeTextRule.plan()`.
        // A caller that builds the question by hand answers that question
        // again, somewhere else, and a question built wrongly still looks like
        // an answer: three callers used to do it, and one of them built it for
        // a rule the index cannot narrow at all.
        //
        // The rule and the shape it produces live in `core/search`, next to the
        // plan and the port. Everything else asks the rule.
        val offenders = scope.files
            .filter { file -> buildsTheQuestionByHand(file.packageName(), file.codeOnly()) }
        assertNoOffenders(
            offenders,
            "the index question is built by the rule, in core/search",
        ) { it.path }
    }

    /**
     * Positive control: the scan above is proven able to fail.
     *
     * The scan runs over a synthetic file that *does* build the question by
     * hand, outside `core/search`. If this ever passes, the scan has stopped
     * detecting — and an assertion that cannot fail is not an assertion:
     * `docs/architecture/rule-control-audit.md` §3 calls that class of rule
     * "Type C", and the rule above is one of them until this control exists.
     */
    @Test
    fun positiveControlDetectsAQuestionBuiltByHand() {
        val synthetic = """
            package com.singularity.todo.feature.tasks.data

            fun search(text: String): TextMatchQuery = TextMatchQuery(listOf(text))
        """.trimIndent()

        assertTrue(
            buildsTheQuestionByHand("com.singularity.todo.feature.tasks.data", synthetic),
            "the scan found nothing in a file that builds the index question by hand — " +
                "the scan has stopped detecting, so the rule above is vacuous",
        )
        assertFalse(
            buildsTheQuestionByHand("com.singularity.todo.core.search", synthetic),
            "the scan reported a question built inside core/search, where the rule itself lives",
        )
    }

    /**
     * Positive control: the layer rule is proven able to fail, and proven to read the
     * layer and not the word.
     *
     * The rule above finds nothing in the tree today, which is the state a rule wants
     * to be in and also the state a broken scanner is in. The control runs the rule
     * over a synthetic file that breaks it — a domain file importing a presentation
     * type — and fails if the rule finds nothing.
     */
    @Test
    fun positiveControlDetectsADomainFileThatImportsPresentation() {
        val presentationImport = "com.singularity.todo.feature.tasks.presentation.components.list.TaskMarkingsRow"
        val forbidsPresentation = { fqn: String -> fqn.contains(".presentation.") }

        assertTrue(
            breaksTheLayerRule(
                "com.singularity.todo.feature.tasks.domain.logic",
                listOf(presentationImport),
                "domain",
                forbidsPresentation,
            ),
            "the rule found nothing in a domain file that imports a presentation type — the " +
                "rule has stopped detecting, so the rule above is vacuous",
        )
        assertTrue(
            breaksTheLayerRule(
                "com.singularity.todo.feature.ai.chat.domain",
                listOf(presentationImport),
                "domain",
                forbidsPresentation,
            ),
            "the rule found nothing in a domain package one level deeper than the area — the " +
                "layer is a segment of the package, wherever it sits in it",
        )
        assertFalse(
            breaksTheLayerRule(
                "com.singularity.todo.feature.genui.render.app",
                listOf(presentationImport),
                "domain",
                forbidsPresentation,
            ),
            "the rule read a package that renders the app's own vocabulary as the domain " +
                "layer — that package is presentation, and it no longer says 'domain'",
        )
    }

    @Test
    fun `feature use cases do not import repository implementations`() {
        // UseCases sit between ViewModel and Repository. Depending on a *RepositoryImpl
        // directly couples a UseCase to a platform-specific implementation, breaking
        // the port/implementation separation. UseCases must depend only on the
        // repository interface (which lives in commonMain) and on core ports.
        //
        // Allowlist: AI use cases legitimately call Koog tool implementations, which
        // are not RepositoryImpl by name — they are checked by the koog-import rule.
        val offenders = scope.files
            .filter { file ->
                val pkg = file.packageName()
                // Files in feature/<x>/domain/ (UseCase classes land here by convention)
                pkg.startsWith("$PKG.feature.") && !pkg.contains(".presentation.") && !pkg.contains(".data.")
            }
            .filter { file ->
                file.importFqns().any { it.endsWith("RepositoryImpl") }
            }
            // *DiModule files are DI wiring classes; they import RepositoryImpl to bind
            // implementations into the Koin graph and are exempt from this rule.
            // Use relativePath (not fileName) to ensure the check works on all platforms.
            .filter { file -> !relativePath(file).contains("DiModule") }
        assertNoOffenders(
            offenders,
            "UseCase files must not import *RepositoryImpl — depend on the repository interface " +
                "from commonMain, not the platform-specific implementation",
        ) { it.path }
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
        // The app's dark mode is a user setting (ThemeMode tri-state, default System).
        // SingularityTheme is the single resolution point: it reads isSystemInDarkTheme()
        // only when ThemeMode.System is active, and provides the resolved boolean via
        // LocalIsDarkTheme. A subtree that re-derives it from the OS silently desyncs
        // from the palette actually in effect — this shipped: the calendar screen painted
        // its hand-authored navy dark palette onto a light app whenever the system was
        // dark and the user had left the default setting alone.
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
        val offenders = scope.files
            .filterNot { file -> CORE_DAO_WRITE_ALLOWLIST.any { file.path.endsWith(it) } }
            .map { it.packageName() to it }
            .filter { (pkg, _) -> pkg.startsWith("$PKG.core.") }
            .filter { (pkg, _) -> CORE_DAO_OWNER_PACKAGES.none { pkg == it || pkg.startsWith("$it.") } }
            .filter { (_, file) -> writesADao(file.codeOnly()) }
            .filterNot { (_, file) ->
                Regex("""class\s+(\w+)""").find(file.codeOnly())?.groupValues?.get(1)
                    ?.let { n -> REPOSITORY_IMPL_SUFFIXES.any { n.endsWith(it) } } == true
            }
            .map { (_, file) -> file.path }
        assertNoOffenders(
            offenders,
            "core/ must not write DAOs directly unless it *is* the repository for that DAO, " +
                "or it is a documented allowlist entry (BackupImporter restores for an arbitrary " +
                "userId, which ambient-scoped repositories cannot express; the profile reset is " +
                "a cross-repository operation that owns nothing and deletes only the profile's " +
                "own rows).",
        ) { it }
    }

    /**
     * Whether the code calls a DAO's write method.
     *
     * The receiver has to be the DAO. A rule that matches any `something.delete(` reads a
     * file system for a row: `fs.delete(deltaPath)` is a file that goes away, not a row,
     * and the exporter was reported for it — a defect that is not there, in a rule that
     * cannot tell a row from a file.
     *
     * The receiver is the DAO in either of two ways: it is named like one
     * (`taskDao.deleteForUser(...)`, `db.taskDao().deleteForUser(...)`), or the file
     * declares it as one (`private val tasks: TaskDao`, then `tasks.deleteForUser(...)`).
     * The name alone is not enough — a DAO is a DAO whatever it is called — and the
     * declaration alone is not enough either, because the DAO may be handed over by a
     * call rather than held.
     */
    private fun writesADao(code: String): Boolean {
        if (DAO_WRITE.containsMatchIn(code)) return true
        // A DAO under a name of its own: the declaration says what it is.
        val daos = DAO_DECLARATION.findAll(code).map { it.groupValues[1] }.toSet()
        return daos.any { dao ->
            Regex(
                """\b$dao\s*\.\s*\w*(?:upsert|insert|update|delete|softDelete|restore|clear)\w*\s*\(""",
            ).containsMatchIn(code)
        }
    }

    /**
     * Positive control: the rule above is proven able to fail, and proven to leave the
     * file system alone.
     *
     * A rule that has never been seen to fire is a rule that has stopped working, and a
     * rule that fires on the wrong thing is a rule that sends people to fix code that is
     * fine — `docs/architecture/rule-control-audit.md` §3 calls that class of rule
     * "Type C", and the rule above is one of them until this control exists.
     */
    @Test
    fun positiveControlDetectsADaoWriteAndLeavesTheFileSystemAlone() {
        val daoWrite = """
            package com.singularity.todo.core.whatever

            fun purge(taskDao: TaskDao, id: String, userId: String) {
                taskDao.deleteForUser(id, userId)
            }
        """.trimIndent()
        val fileSystemDelete = """
            package com.singularity.todo.core.whatever

            fun purge(fs: FileSystem, path: String) {
                fs.delete(path)
            }
        """.trimIndent()
        val daoUnderAnotherName = """
            package com.singularity.todo.core.whatever

            class Purger(private val tasks: TaskDao) {
                fun purge(userId: String) {
                    tasks.deleteForUser("t-1", userId)
                }
            }
        """.trimIndent()

        assertTrue(
            writesADao(daoWrite),
            "the rule found nothing in a file that deletes a row through a DAO — the rule has " +
                "stopped detecting, so the rule above is vacuous",
        )
        assertFalse(
            writesADao(fileSystemDelete),
            "the rule read a file being deleted as a row being deleted — the receiver is the " +
                "thing that says which one it is",
        )
        assertTrue(
            writesADao(daoUnderAnotherName),
            "the rule found nothing in a file that deletes a row through a DAO it holds under " +
                "another name — the name says nothing, the declaration says what it is",
        )
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
        "TaskDao.getOrderingEdgeIdsForUser",
    )

    /**
     * `SyncEngine` is a coordinator: it owns no business logic.
     *
     * - No `init{}` block: phase construction and handler registration happen at
     *   construction time through primary-constructor delegation, not through an
     *   `init {}` block that would hide sequencing.
     * - No direct `outboxDao` calls: outbox writes go through `OutboxService`, push
     *   logic lives in `PushPhase`, and pull logic in `PullPhase`. `SyncEngine`
     *   coordinates the two phases without implementing either.
     *
     * This rule will break if an `init {}` block or a direct `outboxDao.` call
     * is added to `SyncEngine` — that code belongs in the phase classes.
     */
    @Test
    fun `SyncEngine must not own business logic`() {
        val engineFile =
            scope.files.find { it.path.replace('\\', '/').endsWith("core/sync/SyncEngine.kt") }
                ?: error("SyncEngine.kt not found in commonMain")

        val code = engineFile.codeOnly()

        val initBlockPattern = Regex("""\binit\s*\{""")
        val outboxDaoCallPattern = Regex("""\boutboxDao\.\w+\s*\(""")

        val offenders = mutableListOf<String>()
        if (initBlockPattern.containsMatchIn(code)) {
            offenders.add("SyncEngine has an init {} block — business logic belongs in PushPhase / PullPhase")
        }
        if (outboxDaoCallPattern.containsMatchIn(code)) {
            offenders.add("SyncEngine calls outboxDao directly — outbox writes belong in OutboxService / PushPhase")
        }
        assertNoOffenders(
            offenders,
            "SyncEngine must not own business logic (no init{} and no direct outboxDao calls)",
        ) { it }
    }

    /**
     * `OutboxService` is a stateless service: it receives all its dependencies through
     * the primary constructor and has exactly one public entry point ([OutboxService.enqueue]).
     * No `init{}` block — construction-time sequencing would hide the order in which
     * [SyncPhaseReporter] operations are recorded, and that order is load-bearing
     * (REQ-OS-028: the local-storage phase labels must match the span labels in production).
     *
     * This rule breaks when someone adds an `init{}` block to `OutboxService`.
     */
    @Test
    fun `OutboxService must not own stateful logic`() {
        val outboxFile =
            scope.files.find {
                it.path.replace('\\', '/').endsWith("core/sync/OutboxService.kt")
            } ?: error("OutboxService.kt not found in commonMain")

        val code = outboxFile.codeOnly()
        val initBlockPattern = Regex("""\binit\s*\{""")

        val offenders = mutableListOf<String>()
        if (initBlockPattern.containsMatchIn(code)) {
            offenders.add(
                "OutboxService has an init {} block — sequencing logic belongs " +
                    "in the caller (SyncEngine), not here. Phase-label ordering is load-bearing.",
            )
        }
        assertNoOffenders(
            offenders,
            "OutboxService must not own stateful logic (no init{} block)",
        ) { it }
    }

    /**
     * `SyncBootstrapper` registers pull handlers but does not implement event-handling logic.
     *
     * The actual per-event processing (protocol version guard, JsonObject payload
     * validation, CREATED/UPDATED/RESTORED/DELETED dispatch, exception classification)
     * lives in `PullEventHandler`. `SyncBootstrapper` creates one and delegates to it
     * from the handler lambda, but the handler *implementation* (the lambda body that
     * calls `pullEventHandler.handleEvent`) is the event-handling logic — and that
     * logic must not live inside `SyncBootstrapper`'s `init {}`.
     *
     * This rule breaks when someone adds event-handling code directly in the
     * `SyncBootstrapper.init {}` block or in any other part of the bootstrapper,
     * instead of in `PullEventHandler`.
     *
     * Fix: move the `pullEventHandler.handleEvent` call out of `SyncBootstrapper.init {}`
     * and into the `registerHandler` call site in `SyncEngine`:
     * `engine.registerHandler(type) { event -> pullEventHandler.handleEvent(event) { ... } }`.
     * `SyncBootstrapper` is then left with only the handler registration loop.
     */
    @Test
    fun `SyncBootstrapper must not own event-handling logic`() {
        val bootstrapperFile =
            scope.files.find { it.path.replace('\\', '/').endsWith("core/sync/SyncBootstrapper.kt") }
                ?: error("SyncBootstrapper.kt not found in commonMain")

        val code = bootstrapperFile.codeOnly()

        // The event-handling call: pullEventHandler.handleEvent is invoked inside the
        // registerHandler lambda. If this call appears in the bootstrapper's init {}
        // block, the bootstrapper owns the event-handling logic.
        val handleEventPattern = Regex("""pullEventHandler\.handleEvent""")

        val offenders = mutableListOf<String>()
        if (handleEventPattern.containsMatchIn(code)) {
            offenders.add(
                "SyncBootstrapper calls pullEventHandler.handleEvent — " +
                    "event-handling logic belongs in PullEventHandler, not here",
            )
        }
        assertNoOffenders(
            offenders,
            "SyncBootstrapper must not own event-handling logic",
        ) { it }
    }

    /**
     * `PushPhase` and `PullPhase` must not create their own `StateFlow`.
     *
     * `SyncEngine` owns a single `SyncEngineState` that is the authoritative source of
     * sync status, last push result, and last pull result. Both phases report through
     * `SyncPhaseReporter` (injected as `phases`) and expose their outcomes via
     * `SyncEngine`'s public `StateFlow` properties. A phase that instantiates its own
     * `StateFlow` creates a parallel source of truth that is invisible to `SyncEngine`'s
     * observers and breaks the single-state invariant.
     *
     * This rule scans the two phase files for `MutableStateFlow` construction. The
     * `SyncPhaseReporter` interface and `SyncEngineState` are the sanctioned conduit.
     */
    @Test
    fun `SyncEngine child phases must not create their own StateFlow`() {
        val phaseFiles = scope.files.filter { file ->
            val path = file.path.replace('\\', '/')
            path.endsWith("core/sync/PushPhase.kt") || path.endsWith("core/sync/PullPhase.kt")
        }

        val offenders = phaseFiles
            .filter { file ->
                // MutableStateFlow(...) is the direct creation; StateFlow is the read-only
                // interface and is fine to hold as a property. The ban is on creating one.
                Regex("""MutableStateFlow\s*\(""").containsMatchIn(file.codeOnly())
            }
            .map { it.path }

        assertNoOffenders(
            offenders,
            "PushPhase and PullPhase must not create MutableStateFlow — " +
                "report through SyncPhaseReporter injected as 'phases'",
        ) { it }
    }
}
