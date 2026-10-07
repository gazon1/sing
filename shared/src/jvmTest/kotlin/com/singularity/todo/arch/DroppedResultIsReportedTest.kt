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
 * 1. `SyncRepository.enqueue` self-reports since REQ-OS-028 and `refreshStatus` since
 *    REQ-PROP-001. Their call sites discard the `Result` **on the record**: the contract is that
 *    the seam reports, so the caller does not have to. A rule that flagged them would demand 18
 *    log statements to undo a decision the owner made deliberately.
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

    // ── derivation ────────────────────────────────────────────────────────────────

    /**
     * Methods that report their own failure, so discarding the `Result` is the contract.
     *
     * The one hand-written list left in the rule, and it is a known debt: this should be
     * derived the way `SyncedWriteEnqueuesTest` derives its exemption set, by finding the
     * `Result`-returning method whose body contains `crashReporter.report(`. Both obvious
     * implementations of that derivation were measured and both are wrong — taking the first
     * balanced brace misses `enqueue` and `refreshStatus`, whose reporting `.also { }` sits
     * outside the lambda, and a window to the next `fun` at the same indent invents `save`,
     * because that window runs straight past `override suspend fun getItem`. A correct
     * derivation needs statement-level extraction, which is the next piece of work and not
     * something to smuggle in as a one-line change.
     */
    private fun selfReportingMethods(): Set<String> = setOf(
        // SyncEngine.enqueue — 18 repository call sites discard it on the record.
        "enqueue",
        // ProposalRepositoryImpl.refreshStatus — 3 call sites in ApplyProposalItemUseCase.
        "refreshStatus",
    )

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
        selfReporting: Set<String>,
    ): List<Call> =
        standaloneCalls(source)
            .mapNotNull { call ->
                val declared = receiverTypes(source)[call.receiver]
                val table = declared?.let { declarations[it.substringAfterLast('.')] }
                if (table?.get(call.method) != true) return@mapNotNull null
                if (call.method in selfReporting) return@mapNotNull null
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
    }
}
