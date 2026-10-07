package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A `Result` nobody looked at is handled, or reported where it is produced.
 *
 * ## The hole this closes
 *
 * `kotlin.Result` declares exactly one annotation, `@SinceKotlin("1.3")` — there is no
 * `@MustUseReturnValue`, so the compiler will not warn when a `Result`-returning call is
 * written as a bare statement. Verified against the stdlib class file, not assumed.
 *
 * That is the whole mechanism behind a defect class that kept arriving: a repository call
 * whose `Result` nobody consumed, in a function that reports success anyway. The dropped-`Result`
 * sweep of 2026-10-07 found 32 sites across 6 shapes. This test is the ratchet that stops the
 * twenty-ninth.
 *
 * ## Why the return type is read, not guessed
 *
 * The obvious version of this rule matches a receiver-type suffix and a write-verb method name.
 * Measured on this tree, that version is **9 for 9 false positives**: it reported
 * `SyncStateRepository.setAutoSyncEnabled` and `setScheduledInterval`,
 * `GoogleCalendarSettingsRepository.setSelectedCalendarId` and `setImportForeignEvents`,
 * `CalendarSyncRepository.setEnabled` / `setTargetCalendarId` / `setTargetAppPackage` — all of
 * which return `Unit`, so there is no `Result` to drop — plus `SyncRepository.syncOnce`, already
 * wrapped in a `try`/`catch` that reports.
 *
 * Nine for nine, in one direction. A rule like that does not merely over-report; it would send
 * the next author to unwrap nine `Result`s that do not exist, and it would be switched off with
 * the false positives rather than with the real findings.
 *
 * So the return type is read from the declaring declaration. The lookup is keyed by
 * **declaring type, not by method name**: `upsert` returns `Result<SavedAgendaView>` on
 * `SavedAgendaViewsRepository` and `Task` on `TaskRepository`, and `save` returns `Unit` on
 * `SessionStore` while some other `save` returns a `Result`. Merged into one map, whichever
 * declaration was seen first would win for every call site, and this rule reported
 * `SyncDocumentWriter`'s five `repo.upsert(…)` and `AuthRepository`'s `sessionStore.save(…)` —
 * all six wrong, the first implementation's only findings.
 *
 * Read at the call site, those six were correct code: `TaskRepository.upsert` returns the entity
 * and **throws**, so the pull path's `handleEvent` catches it, reports
 * `sync.apply_failed` and returns `ApplyOutcome.Failed`.
 *
 * ## What counts as handled
 *
 * A `Result` is not dropped when something is chained onto the call — `.getOrThrow()`,
 * `.onSuccess` / `.onFailure`, `.fold`, `.getOrElse`, `.map` — when the call's value is assigned
 * (`fun x(): Result<T> = repo.y(…)`), or when it sits inside a lambda opened by a consumer,
 * including the `emitError { }` / `catchTo { }` seam the house uses in ViewModels.
 *
 * Three failure modes had to be designed out, each measured rather than imagined:
 *
 * - **Comments.** `FeatureSlot.kt`'s KDoc contains a worked example whose last two lines are
 *   exactly the dropped shape. Scanning raw text reports them.
 * - **Multi-line chains.** `repo.startEntry(` … `).onFailure { … }` is one consumed call written
 *   across four lines. A line-based rule calls it dropped; five of the seven candidates a
 *   line-based probe found here were exactly that.
 * - **Nesting.** `emitError("Move failed") { updateTask.invoke(…) }` handles the failure, and
 *   the call's own receiver is the thing being called, not the wrapper — so the enclosing
 *   lambda has to be walked.
 *
 * ## Where it is deliberately silent
 *
 * 1. A `Result`-returning method that reports the failure it captured is exempt, because the
 *    contract is that the seam reports and the caller does not have to. The set is **derived**
 *    and keyed by declaring type — see [selfReportingMethods], where the two derivations that
 *    failed first are recorded, and why walking backwards does not have their failure mode.
 * 2. `test/fakes` and `test/helpers` are not production. Several fakes return `Result.failure`
 *    deliberately; that is how a control works.
 *
 * ## What this does not prove
 *
 * That every `Result` in the tree is handled. It sees standalone statements in production
 * `commonMain`, resolving the return type from the same tree. Three blind spots are known and
 * are not fixed here:
 *
 * - **A receiver whose type is inferred rather than declared** is not resolved and is skipped.
 * - **A failure dropped in a loop where the value itself is used** — `map { … repo.create(x) … }`
 *   with the id kept and the failure dropped. `DecomposeAndCreateTool` was exactly this: it
 *   created N subtasks and returned every id regardless. There is no `Result` left to unwrap,
 *   so a rule about dropped `Result`s cannot see it. That class needs a rule of its own.
 * - **A `Result` consumed through reflection or a generic helper.**
 *
 * This is a ratchet against losing a handler in a refactor, not a proof.
 */
@Tag("fast")
class DroppedResultIsReportedTest {

    @Test
    fun `no production Result is dropped where nobody handles it`() {
        val declarations = declaredMethods()
        val selfReporting = selfReportingMethods()

        assertTrue(
            declarations.values.any { table -> table.values.any { it } },
            "no Result-returning declaration was found, so this rule can only pass vacuously. " +
                "The scan found nothing — which is exactly what a rule whose resolver stopped " +
                "matching looks like, and it is indistinguishable from a codebase that stopped " +
                "violating.",
        )

        val offenders = SourceScan.productionFiles().flatMap { file ->
            droppedCallsIn(SourceScan.stripComments(file.readText()), declarations, selfReporting)
                .map {
                    "${file.name}:${it.line} ${it.receiver}.${it.method}() " +
                        "returns a Result and nothing looks at it"
                }
        }

        if (offenders.isNotEmpty()) fail(describe(offenders))
    }

    // ── controls ──────────────────────────────────────────────────────────────────

    @Test
    fun `the resolver reads the return type off the declaring type`() {
        // The control for the resolver, and the reason this rule reads declarations.
        // `upsert` is the collision that made a name-based rule unusable: `Unit` on a DAO,
        // `Result` on a checklist repository, the entity on a task repository. A rule that
        // decides from the name is wrong about all three at once.
        val resolved = declaredMethodsIn(
            """
            interface TaskDao {
                suspend fun upsert(task: Task): Unit
            }
            interface TaskRepository {
                suspend fun upsert(task: Task): Task
            }
            interface ChecklistRepository {
                suspend fun upsert(item: ChecklistItem): Result<Unit>
            }
            interface SessionStore {
                suspend fun save(session: Session.SignedIn)
            }
            """.trimIndent(),
        )
        assertEqual(
            mapOf(
                "TaskDao" to mapOf("upsert" to false),
                "TaskRepository" to mapOf("upsert" to false),
                "ChecklistRepository" to mapOf("upsert" to true),
                "SessionStore" to mapOf("save" to false),
            ),
            resolved,
            "the same name on three types resolves three ways; merged, whichever declaration " +
                "came first would win for every call site",
        )
    }

    @Test
    fun `a consumed Result is not a dropped one`() {
        // The second half of the control. Reading the return type is only half the rule; the
        // other half is telling a bare statement from a consumed value, and getting that wrong
        // in the false-positive direction is what switches a rule off.
        val source = """
            val repo: ChecklistRepository = ChecklistRepository()

            interface ChecklistRepository {
                suspend fun create(item: ChecklistItem): Result<Unit>
                suspend fun update(item: ChecklistItem): Result<Unit>
                suspend fun count(item: ChecklistItem): Result<Int>
            }

            repo.create(item)
            repo.create(item).getOrThrow()
            repo.create(
                item,
            ).onFailure { e -> log(e) }
            emitError { repo.create(item) }
            emitError("Move failed") { repo.update(item) }
            fun wrapper(): Result<Int> = repo.count(item)
        """.trimIndent()
        val stripped = SourceScan.stripComments(source)
        val dropped = droppedCallsIn(stripped, declaredMethodsIn(stripped), emptySet())

        assertEqual(
            listOf("create"),
            dropped.map { it.method },
            "only the bare statement is dropped",
        )
        assertEqual(
            listOf(9),
            dropped.map { it.line },
            "and it is the bare one on line 9 — the .getOrThrow(), the multi-line " +
                ".onFailure, the two emitError { } seams and the assigned expression body " +
                "are all consumed",
        )
    }

    @Test
    fun `a worked example in a comment is not a dropped Result`() {
        // The control for comment stripping, and for the false positive it exists to stop.
        // `FeatureSlot.kt` carries this exact shape in its KDoc today; a text-only scan reports
        // it, and a rule that reports the documentation gets switched off.
        val source = """
            val repo: ChecklistRepository = ChecklistRepository()

            interface ChecklistRepository {
                suspend fun create(item: ChecklistItem): Result<Unit>
                suspend fun update(item: ChecklistItem): Result<Unit>
            }

            /**
             * The autosave lambda:
             *     repo.create(item)
             *     repo.update(item)
             */
            fun real() {
                repo.create(item)
                log("done")
            }
        """.trimIndent()

        val stripped = SourceScan.stripComments(source)
        val dropped = droppedCallsIn(stripped, declaredMethodsIn(stripped), emptySet())

        assertEqual(listOf(14), dropped.map { it.line }, "only the real call on line 14 remains")
        assertTrue(
            dropped.none { it.line == 10 || it.line == 11 },
            "lines 10 and 11 are inside a KDoc example and must never be findings",
        )
    }

    @Test
    fun `the self-reporting exemption is read off the body, not off a name`() {
        // The control for the derivation. Three methods that all touch a failure, and only one
        // of them is exempt — because the exemption is not "reports" but "reports the very
        // Result it returns", and only that one builds its Result by capturing.
        val source = """
            class SyncEngine {
                suspend fun enqueue(entity: SyncableEntity): Result<Unit> = runCatchingResult {
                    dao.insert(entity)
                }.also { result ->
                    val error = result.exceptionOrNull() ?: return@also
                    log.e(error) { "enqueue failed" }
                    crashReporter.report(error, "sync.enqueue_failed")
                }
            }

            class SyncPhaseReporter {
                fun pushFailed(error: AppError): Result<PushSummary> {
                    crashReporter.report(error, error.code)
                    return Result.failure(error)
                }
            }

            class SilentPlanner {
                suspend fun plan(scope: SyncScope): Result<Int> = runCatchingResult {
                    seed(scope)
                }
            }
        """.trimIndent()

        assertEqual(
            setOf(MethodKey("SyncEngine", "enqueue")),
            capturesAndReports(SourceScan.stripComments(source)),
            "the seed set only. A method that reports the Result it captured is exempt; a " +
                "method that constructs Result.failure is not, because for its callers that " +
                "Result is how the failure travels; a method that neither reports nor is " +
                "reported is not exempt either",
        )
    }

    @Test
    fun `an override and a pure delegation both inherit the reporting`() {
        // Both links were found by running the derivation on the tree, not by designing them.
        // `ApplyProposalItemUseCase` calls `proposals.refreshStatus(…)` where `proposals` is
        // the *interface*, and the report lives in `ProposalRepositoryImpl`; keying on the
        // implementation alone reported that correct call site as dropped. And
        // `SyncRepositoryImpl.enqueue` is `= engine.enqueue(entity)` — nothing of its own, so it
        // reports exactly when `SyncEngine.enqueue` does.
        val source = """
            interface ProposalRepository {
                suspend fun refreshStatus(id: ProposalId): Result<Unit>
            }

            class ProposalRepositoryImpl(
                private val crashReporter: CrashReportingPort,
            ) : ProposalRepository {
                override suspend fun refreshStatus(id: ProposalId): Result<Unit> =
                    runCatchingCancellable<Unit> {
                        dao.update(id)
                    }.also { result ->
                        val error = result.exceptionOrNull() ?: return@also
                        crashReporter.report(error, "proposals.refresh_status_failed")
                    }
            }

            class SyncEngine {
                suspend fun enqueue(entity: SyncableEntity): Result<Unit> = runCatchingResult {
                    dao.insert(entity)
                }.also { result ->
                    val error = result.exceptionOrNull() ?: return@also
                    log.e(error) { "enqueue failed" }
                }
            }

            interface SyncRepository {
                suspend fun enqueue(entity: SyncableEntity): Result<Unit>
            }

            class SyncRepositoryImpl(
                private val engine: SyncEngine,
            ) : SyncRepository {
                // One line, as it is in SyncRepositoryImpl.kt:49. The multi-line form is not
                // recognised, and saying so is why this control is written the way it is.
                override suspend fun enqueue(entity: SyncableEntity): Result<Unit> = engine.enqueue(entity)
            }
        """.trimIndent()

        val stripped = SourceScan.stripComments(source)
        val derived = closeOver(capturesAndReports(stripped), declaredMethodsIn(stripped), listOf(stripped))

        assertEqual(
            setOf(
                MethodKey("SyncEngine", "enqueue"),
                MethodKey("SyncRepositoryImpl", "enqueue"),
                MethodKey("SyncRepository", "enqueue"),
                MethodKey("ProposalRepositoryImpl", "refreshStatus"),
                MethodKey("ProposalRepository", "refreshStatus"),
            ),
            derived,
            "the implementation that reports, the supertype the caller names, and the " +
                "forwarding method in between all report the same failure",
        )
    }

    @Test
    fun `a forwarding method inherits the reporting of what it forwards to`() {
        // The control for the key, and the reason it is a pair. `enqueue` is not a unique name:
        // `SyncEngine.enqueue` reports at the seam, and `SyncRepositoryImpl.enqueue` reaches it
        // by forwarding. A list keyed by the name alone exempts both — and would keep exempting
        // `syncRepository.enqueue` at 17 call sites even after an implementation that *swallowed*
        // the failure instead of forwarding it, since a swallow is not a delegation. The rule
        // would have been protecting the regression rather than the report.
        val derived = selfReportingMethods()

        assertTrue(
            derived.contains(MethodKey("SyncEngine", "enqueue")),
            "SyncEngine.enqueue captures its failure and reports it, so its 18 discarded call " +
                "sites are exempt on the record; the derivation must find it. Derived: $derived",
        )
        assertTrue(
            derived.contains(MethodKey("ProposalRepositoryImpl", "refreshStatus")),
            "same shape, different file — REQ-PROP-001's seam. Derived: $derived",
        )
        assertTrue(
            MethodKey("SyncRepository", "enqueue") in derived,
            "SyncRepositoryImpl.enqueue is `= engine.enqueue(entity)` and reports nothing of its " +
                "own, so it inherits the seam by forwarding alone — and the interface the 17 " +
                "discarding call sites actually name inherits it again. Derived: $derived",
        )
        assertTrue(
            MethodKey("SyncRepositoryImpl", "enqueue") in derived,
            "the forwarding method itself, not only the interface. Derived: $derived",
        )
        assertTrue(
            MethodKey("SyncPhaseReporter", "pushFailed") !in derived,
            "it reports, but it returns Result.failure on purpose: the caller must read it. " +
                "Derived: $derived",
        )
    }

    // ── derivation ────────────────────────────────────────────────────────────────

    /**
     * Methods that report their own failure, so discarding the `Result` is the contract.
     *
     * Derived, not written down, and keyed by **declaring type and method together** for the
     * reason the resolver is: the hand-written list this replaced was keyed by name alone, and
     * `enqueue` is not unique. `SyncEngine.enqueue` reports at the seam; `SyncRepository.enqueue`
     * only delegates to it. Name-keyed, both were exempt, so a `SyncRepositoryImpl.enqueue` that
     * swallowed the failure tomorrow would have kept 17 call sites silent — the rule would have
     * been protecting the regression rather than the report.
     *
     * ### How it is derived, and the two derivations that failed first
     *
     * For each report call, walk **backwards** to the nearest preceding `fun` and to the class or
     * object above it, then require two things of that function: it returns a `Result`, and it
     * builds that `Result` by capturing a failure — its body opens with `runCatching… {`.
     *
     * Both earlier derivations looked forward, and both were wrong for the same reason: the
     * reporting `.also { }` sits **outside** the `runCatching` lambda in every one of these
     * methods. Taking the first balanced brace from the report call stops inside the lambda and
     * never reaches the signature; a window to the next `fun` at the same indent runs straight
     * past `override suspend fun getItem` and invents a `save`. Walking backwards has neither
     * failure mode — a `fun` keyword does not nest inside another one in Kotlin, so the nearest
     * preceding `fun` is the enclosing function by construction.
     *
     * The `runCatching` requirement is what keeps the exemption narrow. `SyncPhaseReporter
     * .pushFailed` reports too — `log.e` and `crashReporter.report` — but it *constructs*
     * `Result.failure(error)` on purpose, because for its callers the returned `Result` **is**
     * how the failed phase travels. Exempting it would stop the rule from asking the right
     * question of the one place that must answer it.
     */
    private fun selfReportingMethods(): Set<MethodKey> {
        val sources = SourceScan.productionFiles().map { SourceScan.stripComments(it.readText()) }
        val direct = sources.flatMapTo(mutableSetOf()) { capturesAndReports(it) }
        return closeOver(direct, declaredMethods(), sources)
    }

    /**
     * Closes the seed set over the two ways a method can inherit another method's reporting.
     *
     * Both were found by running this on the tree, not by designing them:
     *
     * - **Override.** A caller never names `ProposalRepositoryImpl`; it names
     *   `ProposalRepository`. The report lives in the implementation, the caller sees the
     *   interface, so keying on the implementation alone reported a correct call site as
     *   dropped. Keying by name instead — what this rule's hand-written list used to do —
     *   hid that same fact by accident.
     * - **Delegation.** `SyncRepositoryImpl.enqueue` is `= engine.enqueue(entity)`: it reports
     *   nothing of its own and returns exactly what `SyncEngine.enqueue` returns, so it is
     *   exempt for the same reason. `SyncRepository.enqueue` inherits that through the
     *   override, which is why its 17 discarded call sites are not findings.
     *
     * Bounded at [MAX_HOPS] rounds and by the `seen` set, because a lexical rule must not be
     * able to walk a cycle in the delegation graph.
     */
    private fun closeOver(
        seed: Set<MethodKey>,
        declarations: Map<String, Map<String, Boolean>>,
        sources: List<String>,
    ): Set<MethodKey> {
        val found = seed.toMutableSet()
        repeat(MAX_HOPS) {
            var added = false
            for (source in sources) {
                added = added or spreadOverOverride(found, declarations, source)
                added = added or spreadOverDelegation(found, source)
            }
            if (!added) return found
        }
        return found
    }

    /** `Impl.override` reports ⇒ the supertype that declares the method reports too. */
    private fun spreadOverOverride(
        found: MutableSet<MethodKey>,
        declarations: Map<String, Map<String, Boolean>>,
        source: String,
    ): Boolean {
        // Read once per file, not once per key: the hop is the hot loop, and re-parsing every
        // class header for every candidate is what made this rule slower than the test run it
        // was supposed to take part in.
        val supertypes = supertypesIn(source)
        var added = false
        for (key in found.toList()) {
            for (supertype in supertypes[key.type].orEmpty()) {
                if (declarations[supertype]?.get(key.method) == true && found.add(MethodKey(supertype, key.method))) {
                    added = true
                }
            }
        }
        return added
    }

    /** `Impl.m = other.n(…)` and `Other.n` reports ⇒ `Impl.m` reports, by forwarding alone. */
    private fun spreadOverDelegation(found: MutableSet<MethodKey>, source: String): Boolean {
        val receivers = receiverTypes(source)
        var added = false
        for (delegation in delegationsIn(source)) {
            val target = receivers[delegation.receiver]?.substringAfterLast('.')
            if (target != null &&
                MethodKey(target, delegation.method) in found &&
                found.add(MethodKey(delegation.fromType, delegation.fromMethod))
            ) {
                added = true
            }
        }
        return added
    }

    /**
     * Class name -> the supertypes its header declares: `class Impl(…) : A, B {`.
     *
     * Matched by balanced parentheses rather than by a pattern, and the reason is a real class
     * in this tree: `SyncRepositoryImpl`'s constructor ends with
     * `private val log: Logger = Logger.withTag("SyncRepository")`, whose `)` sits inside the
     * parameter list. Both plausible patterns stop there — `[^)]*` at the inner bracket, a lazy
     * `.*?` at the same place — and the class silently acquires no supertypes, so an `override`
     * never spreads and a correct call site is reported as a dropped `Result`. A rule that is
     * wrong quietly is worse than one that is wrong loudly.
     */
    private fun supertypesIn(source: String): Map<String, List<String>> =
        CLASS_DECLARATION.findAll(source).associate { match ->
            // The pattern ends on the `(`, so that index is already the opener. Searching for the
            // *next* one finds `Logger.withTag(` inside the parameter list instead, the balanced
            // close lands there, and every class silently acquires no supertypes — which reads as
            // "no overrides anywhere" rather than as a parsing mistake.
            match.groupValues[1] to supertypesAfter(source, SourceScan.closingParen(source, match.range.last))
        }

    /** The supertypes between the closing `)` of the parameter list and the body's `{`. */
    private fun supertypesAfter(source: String, closeParen: Int): List<String> {
        if (closeParen < 0) return emptyList()
        var i = closeParen + 1
        while (i < source.length && source[i].isWhitespace()) i++
        if (i >= source.length || source[i] != ':') return emptyList()
        val bodyStart = source.indexOf('{', i)
        if (bodyStart < 0) return emptyList()
        return source.substring(i + 1, bodyStart)
            .split(',')
            .map { it.trim().substringBefore('<').substringBefore('(').trim() }
            .filter { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
    }

    /**
     * The seed set: the methods that capture their own failure and report it.
     *
     * Walking **backwards** is what makes this work. The reporting `.also { }` sits outside the
     * `runCatching` lambda in every one of these methods, which is why the two forward-looking
     * derivations both failed — see the class KDoc. A `fun` keyword does not nest inside another
     * one in Kotlin, so the nearest preceding `fun` is the enclosing function by construction.
     */
    private fun capturesAndReports(source: String): Set<MethodKey> =
        REPORT_CALL.findAll(source)
            .mapNotNull { report ->
                val funMatch = FUNCTION_DECLARATION.findAll(source.take(report.range.first)).lastOrNull()
                    ?: return@mapNotNull null
                val typeMatch = TYPE_DECLARATION.findAll(source.take(funMatch.range.first)).lastOrNull()
                    ?: return@mapNotNull null
                val head = source.substring(funMatch.range.last + 1, report.range.first)
                if (!capturesItsOwnFailure(head)) return@mapNotNull null
                MethodKey(typeMatch.groupValues[1], funMatch.groupValues[1])
            }.toSet()

    /**
     * The one-expression delegations in a file: `fun m(…) = receiver.n(…)`.
     *
     * Only the single-line form, because recognising a delegation spread over several lines
     * needs the statement-level extraction this rule deliberately does not attempt. A
     * multi-line delegation is therefore not inherited, and a caller of one is reported. That is
     * the safe direction: a false report costs one `.getOrThrow()`, a missed exemption costs a
     * silent failure.
     */
    private fun delegationsIn(source: String): List<Delegation> =
        DELEGATION.findAll(source).mapNotNull { match ->
            val owner = TYPE_DECLARATION.findAll(source.take(match.range.first)).lastOrNull()
                ?: return@mapNotNull null
            Delegation(
                fromType = owner.groupValues[1],
                fromMethod = match.groupValues[1],
                receiver = match.groupValues[2],
                method = match.groupValues[3],
            )
        }.toList()

    /** `Impl.m` that returns nothing but `receiver.n(…)`. */
    private data class Delegation(
        val fromType: String,
        val fromMethod: String,
        val receiver: String,
        val method: String,
    )

    /**
     * True when the function's own text before the report call contains a `runCatching… {`.
     *
     * Asking for the capture rather than for the report is the whole discrimination: the report
     * says the failure is visible, and the capture says the returned `Result` is that same
     * failure rather than a fresh one the caller is meant to read.
     */
    private fun capturesItsOwnFailure(headOfFunction: String): Boolean =
        CAPTURES_FAILURE.containsMatchIn(headOfFunction)

    /** A method the caller can be told about: a type the caller can name, and its method. */
    private data class MethodKey(val type: String, val method: String)

    /** declaring type -> (method -> does it return Result?). */
    private fun declaredMethods(): Map<String, Map<String, Boolean>> =
        SourceScan.productionFiles().fold(mutableMapOf<String, Map<String, Boolean>>()) { acc, file ->
            acc += declaredMethodsIn(SourceScan.stripComments(file.readText()))
            acc
        }

    private fun declaredMethodsIn(source: String): Map<String, Map<String, Boolean>> {
        val byType = mutableMapOf<String, MutableMap<String, Boolean>>()
        TYPE_DECLARATION.findAll(source).forEach { decl ->
            val bodyStart = source.indexOf('{', decl.range.last + 1)
            val body = if (bodyStart >= 0) SourceScan.bodyOf(source, bodyStart) else null
            if (body == null) return@forEach
            val methods = byType.getOrPut(decl.groupValues[1]) { mutableMapOf() }
            // Default first, then refine: a function with no declared return type returns
            // Unit, so an absent entry must not read as "unknown, maybe a Result".
            ANY_FUNCTION.findAll(body).forEach { methods[it.groupValues[1]] = false }
            RETURNS_NOT_RESULT.findAll(body).forEach { methods[it.groupValues[1]] = false }
            RETURNS_RESULT.findAll(body).forEach { methods[it.groupValues[1]] = true }
        }
        return byType
    }

    private data class Call(val receiver: String, val method: String, val line: Int, val offset: Int)

    private fun droppedCallsIn(
        source: String,
        declarations: Map<String, Map<String, Boolean>>,
        selfReporting: Set<MethodKey>,
    ): List<Call> =
        standaloneCalls(source)
            .mapNotNull { call ->
                val declared = receiverTypes(source)[call.receiver]?.substringAfterLast('.')
                val table = declared?.let { declarations[it] }
                if (table?.get(call.method) != true) return@mapNotNull null
                if (declared != null && MethodKey(declared, call.method) in selfReporting) {
                    return@mapNotNull null
                }
                if (isConsumed(source, call)) return@mapNotNull null
                call
            }

    private fun standaloneCalls(source: String): List<Call> {
        var offset = 0
        return source.split('\n').mapIndexedNotNull { index, line ->
            val trimmed = line.trimStart()
            val m = STANDALONE_CALL.find(trimmed)
            if (m == null) {
                offset += line.length + 1
                return@mapIndexedNotNull null
            }
            val leading = line.length - trimmed.length
            Call(
                receiver = m.groupValues[1],
                method = m.groupValues[2],
                line = index + 1,
                offset = offset + leading,
            ).also { offset += line.length + 1 }
        }
    }

    /** True when the text before [upTo] ends in `=`, i.e. the call's value is assigned. */
    private fun endsWithAssignment(source: String, upTo: Int): Boolean =
        source.substring(0, upTo.coerceAtMost(source.length)).trimEnd().endsWith('=')

    /**
     * Consumption is decided past the closing parenthesis, skipping whitespace.
     *
     * Two shapes have to be seen. `repo.startEntry(` … `).onFailure { … }` is one consumed call
     * across four lines, and looking only to the end of the line calls it dropped. And a call
     * whose value is the enclosing block's value — the last expression of a lambda — is consumed
     * by whoever asked for the lambda.
     */
    private fun isConsumed(source: String, call: Call): Boolean {
        val start = source.indexOf("${call.receiver}.${call.method}", call.offset)
        val open = if (start >= 0) source.indexOf('(', start) else -1
        val close = if (open >= 0) SourceScan.closingParen(source, open) else -1
        // Indistinguishable from handled: a call that cannot be located, or whose parens do
        // not balance, is not evidence of anything. Saying so here keeps the rule from
        // reporting a shape it has not actually read.
        val located = start >= 0 && close >= 0
        val after = if (located) source.substring(close + 1).trimStart() else ""

        return located && (
            endsWithAssignment(source, call.offset) ||
                (after.startsWith('.') && CONSUMES_ON_RETURN.containsMatchIn(after)) ||
                after.startsWith('}') || after.startsWith(')') ||
                call.receiver in CONSUMING_SEAMS ||
                insideConsumingLambda(source, start)
        )
    }

    /**
     * Walks back from [upTo] to the nearest unclosed `{` and asks whether the text before it
     * opens a consuming wrapper.
     *
     * Scanning the enclosing braces is what sees through nesting: the call's own receiver is the
     * thing being called, and the wrapper that consumes the failure is several frames up.
     * `ProjectDetailViewModel`'s MoveTaskToProject arm is the case — `emitError("Move task
     * failed", …) { updateTask.invoke(…) }` — and checking only the call's own receiver
     * reported it as dropped, which it is not.
     */
    private fun insideConsumingLambda(source: String, upTo: Int): Boolean {
        var depth = 0
        var i = upTo - 1
        while (i >= 0) {
            when (source[i]) {
                '}' -> depth++

                '{' -> {
                    if (depth == 0) {
                        // Inclusive of the brace: the wrapper's `(` and the `{` that opens its
                        // lambda are both inside this window, and cutting the head at the brace
                        // drops exactly the `{` the pattern looks for.
                        val head = source.substring(0, i + 1).takeLast(160)
                        return CONSUMING_WRAPPER.containsMatchIn(head)
                    }
                    depth--
                }
            }
            i--
        }
        return false
    }

    /** receiver name -> declared type, from this file's properties and parameters. */
    private fun receiverTypes(source: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (m in PROPERTY_TYPE.findAll(source)) out.putIfAbsent(m.groupValues[1], m.groupValues[2])
        for (m in PARAM_TYPE.findAll(source)) out.putIfAbsent(m.groupValues[1], m.groupValues[2])
        return out
    }

    private fun describe(offenders: List<String>): String =
        "These calls return a Result that nobody consumes, in code that reports success anyway " +
            "— the shape behind every defect in the dropped-Result sweep of 2026-10-07:\n" +
            offenders.joinToString("\n") { "  $it" } +
            "\n\nDo one of three things, and leave the reason in the code either way:\n" +
            "  1. unwrap it — .getOrThrow(), or the emitError { } / catchTo { } seam;\n" +
            "  2. report it where it is produced, if the caller cannot react — that is what " +
            "REQ-OS-028 did for SyncEngine.enqueue;\n" +
            "  3. if the caller genuinely cannot care, say so where the method is declared, so " +
            "the next reader does not have to rediscover it."

    private fun assertEqual(expected: Any, actual: Any, message: String) {
        if (expected != actual) fail("$message: expected <$expected> but was <$actual>")
    }

    private companion object {
        val TYPE_DECLARATION =
            Regex("""\b(?:internal\s+|abstract\s+|open\s+|sealed\s+)*(?:interface|class|object)\s+(\w+)""")

        val RETURNS_RESULT = Regex("""fun\s+(\w+)\s*\([^)]*\)\s*:\s*Result<""")
        val RETURNS_NOT_RESULT =
            Regex("""fun\s+(\w+)\s*\([^)]*\)\s*(?::\s*(?!Result<)|\s*=\s*\w|\s*\))""")

        /** Any function: no declared return type means `Unit`, not a Result. */
        val ANY_FUNCTION = Regex("""fun\s+(\w+)\s*\(""")

        /** A statement that is exactly `<receiver>.<method>(` — not a `.continuation`. */
        val STANDALONE_CALL = Regex("""^(\w+)\.(\w+)\s*\(""")

        val CONSUMES_ON_RETURN = Regex(
            """\.(\??)\s*(getOrThrow|onSuccess|onFailure|fold|getOrElse|getOrNull|isSuccess|""" +
                """isFailure|exceptionOrNull|onNull|map|mapCatching|let|also|recoverCatching|""" +
                """getOrDefault|orElse)\b""",
        )

        /** Receivers whose call is an argument to a consumer that takes the failure. */
        val CONSUMING_SEAMS = setOf("emitError", "catchTo")

        /** A lambda opened by any of these is a place where the failure is consumed. */
        val CONSUMING_WRAPPER = Regex(
            """(emitError|catchTo|runCatching\w*|onSuccess|onFailure|fold|getOrElse|map|""" +
                """mapCatching|let|also|recoverCatching)\s*(\{|\(.*\)\s*\{)""",
        )

        val PROPERTY_TYPE = Regex("""\b(?:val|var)\s+(\w+)\s*:\s*([A-Za-z0-9_.<>]+)""")
        val PARAM_TYPE = Regex("""\b(\w+)\s*:\s*([A-Za-z0-9_.<>]+)\s*[,)]""")

        /** Any function declaration, used to walk backwards to the enclosing one. */
        val FUNCTION_DECLARATION = Regex("""\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(""")

        /** Where a failure becomes visible. `log.e` counts: it is this app's own seam. */
        val REPORT_CALL = Regex("""\b(?:crashReporter\.report|reportHandled|reportNonFatal|log\.e)\s*\(""")

        /**
         * The capture itself: `runCatchingResult {`, `runCatchingCancellable<Unit> {`.
         *
         * Required in the text between the signature and the report call, which is also where
         * the failure has to travel for the exemption to mean anything.
         */
        val CAPTURES_FAILURE = Regex("""\brunCatching\w*\s*(<[^>]*>\s*)?\{""")

        /** `class Impl(` — the supertypes after it are found by balancing, not by pattern. */
        val CLASS_DECLARATION =
            Regex("""\b(?:internal\s+|abstract\s+|open\s+|sealed\s+|data\s+)*class\s+(\w+)\s*\(""")

        /** A whole function that is nothing but `= receiver.method(…)`. */
        val DELEGATION = Regex(
            """^\s*(?:override\s+)?(?:suspend\s+)?fun\s+(\w+)\s*\([^)]*\)\s*:\s*Result<[^>]*>*\s*=\s*(\w+)\.(\w+)\s*\(.*\)\s*$""",
            // MULTILINE, or `^` and `$` bind to the whole file and the pattern matches nothing
            // outside line one. Without it the derivation silently found zero delegations and
            // the rule reported `SyncRepository.enqueue`'s 17 call sites.
            RegexOption.MULTILINE,
        )

        /** A lexical rule must not be able to walk a cycle in the delegation graph. */
        const val MAX_HOPS = 4
    }
}
