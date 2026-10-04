package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * A ViewModel that can fail but never passes a `CrashReportingPort` sends its failures
 * into a no-op default, so they reach the UI and nothing else. The code compiles, the
 * ViewModel is fully wired, and the reporting silently does nothing.
 *
 * ## Why this rule exists rather than a source-text scan
 *
 * The check this replaces ran three regexes over a file's text and fired only on
 * `emitError`/`catchTo` call sites. That missed every ViewModel which handled failures by
 * hand — and there were eight of them, each converting a failure into UI state with its own
 * `try`/`catch`/`fold` and reporting nothing. A text scan cannot see a helper the
 * ViewModel calls, nor a composable that delegates its catching, so widening the regexes
 * would have widened a heuristic rather than fixed a blind spot.
 *
 * Matching the PSI makes "can this fail" a statement about the code: a `launch` call, a
 * `runCatching` family call, a flow `catch`, or a `catch` clause, anywhere in the class body
 * including inside a lambda the ViewModel passed to something else.
 *
 * ## The two findings
 *
 * 1. **No reporter.** The class can fail and has no `crashReporter` anywhere — not as a
 *    constructor parameter, not passed to a super call, not calling `report` directly.
 * 2. **A hand-built scope.** `GlobalScope`, `MainScope(`, or `CoroutineScope(` inside a
 *    ViewModel. Every scope from the background factory carries the failure handler that
 *    makes a failed launch a report instead of a killed Android process; a scope built here
 *    bypasses it, and it compiles silently.
 *
 * @see NoUnreportedFailurePathPolicy for the decision, split out so it is testable
 *      without constructing PSI.
 */
class NoUnreportedFailurePathRule(config: Config) : Rule(config, "", null) {

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (!NoUnreportedFailurePathPolicy.isViewModelName(klass.name)) return

        val scopeBypass: KtElement? = NoUnreportedFailurePathPolicy.scopeBypassIn(klass)
        if (scopeBypass != null) {
            report(
                Finding(
                    entity = Entity.from(klass),
                    message = NoUnreportedFailurePathPolicy.scopeBypassMessage(klass, scopeBypass),
                    references = emptyList(),
                    suppressReasons = emptyList(),
                ),
            )
            // A bypassed scope is a bigger hole than a missing reporter: the failure
            // handler is not reached at all. Reporting both would bury the second.
            return
        }

        if (!NoUnreportedFailurePathPolicy.canFail(klass)) return
        if (NoUnreportedFailurePathPolicy.hasReporter(klass)) return

        report(
            Finding(
                entity = Entity.from(klass),
                message = NoUnreportedFailurePathPolicy.missingReporterMessage(klass),
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }
}

/**
 * The decision [NoUnreportedFailurePathRule] makes, over PSI.
 *
 * Split from the rule so each branch has a test that does not need a detekt harness —
 * a rule whose only test surface is "run the whole engine" is a rule whose no-op failure
 * mode is invisible, which is the failure mode this repository has already written two
 * decision records about.
 */
internal object NoUnreportedFailurePathPolicy {

    /** `NotePreview` is a ViewModel by role and not by name; the name list is inherited. */
    fun isViewModelName(name: String?): Boolean = name == "NotePreview" || name?.endsWith("ViewModel") == true

    private val LAUNCH_CALLEES = setOf("launch", "async", "withContext")
    private val RUN_CATCHING_CALLEES = setOf("runCatching", "runCatchingCancellable", "runCatchingResult")

    /**
     * True when the class can produce a failure: it starts a coroutine, wraps a call in the
     * `runCatching` family, has a flow `catch`, or contains a `catch` clause.
     *
     * `withContext` counts because a body that throws inside it propagates. Flow `catch` is
     * `KtCallExpression` with callee `catch`; the `catch (e: …)` clause is a different PSI
     * element and is counted separately, so the two spellings are not conflated.
     */
    fun canFail(klass: KtClass): Boolean {
        var found = false
        klass.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                if (found) return
                val callee = expression.calleeExpression?.text
                if (callee != null && (callee in LAUNCH_CALLEES || callee in RUN_CATCHING_CALLEES)) {
                    found = true
                    return
                }
                super.visitCallExpression(expression)
            }

            override fun visitCatchSection(catchSection: KtCatchClause) {
                found = true
            }
        })
        return found
    }

    /**
     * True when the class already has somewhere to report to: a `crashReporter` constructor
     * parameter, a reference to one, or a direct `report(...)` call.
     *
     * `report` is matched as a *call*, not as a bare name. A ViewModel with an unrelated
     * property called `report` would otherwise clear itself, which is the rule's own no-op
     * failure mode in a different costume.
     */
    fun hasReporter(klass: KtClass): Boolean {
        val declaredAsParameter = klass.primaryConstructorParameters
            .any { it.name == "crashReporter" }
        if (declaredAsParameter) return true
        var found = false
        klass.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                if (found) return
                if (expression.calleeExpression?.text == "report") {
                    found = true
                    return
                }
                super.visitCallExpression(expression)
            }

            override fun visitReferenceExpression(expression: KtReferenceExpression) {
                if (found) return
                val name = (expression as? KtNameReferenceExpression)?.getReferencedName()
                if (name == "crashReporter") {
                    found = true
                    return
                }
                super.visitReferenceExpression(expression)
            }
        })
        return found
    }

    /**
     * The first scope-bypassing construction in the class, or null.
     *
     * `AutoCloseableCoroutineScope(` and `CoroutineScope(` are both constructors; the rule
     * wants the *unmanaged* one. A ViewModel taking an injected scope is the whole point of
     * the check, so only direct construction counts.
     *
     * `GlobalScope` gets its own branch because in kotlinx.coroutines it is an `object`, not
     * a function: `GlobalScope.launch { }` is a dot-qualified expression whose *receiver* is
     * the name and whose selector is the call, so a call-caleese only match would have missed
     * the one spelling everyone actually writes. The retired text predicate was
     * `code.contains("GlobalScope")`, which caught it; the PSI version has to be at least as
     * blind as what it replaces.
     */
    fun scopeBypassIn(klass: KtClass): KtElement? {
        var bypass: KtCallExpression? = null
        var bareObject: KtNameReferenceExpression? = null
        klass.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                if (bypass != null) return
                when (expression.calleeExpression?.text) {
                    "GlobalScope", "MainScope", "CoroutineScope" -> bypass = expression
                    else -> super.visitCallExpression(expression)
                }
            }

            override fun visitReferenceExpression(expression: KtReferenceExpression) {
                if (bypass != null || bareObject != null) return
                val name = (expression as? KtNameReferenceExpression)?.getReferencedName()
                if (name == "GlobalScope") {
                    bareObject = expression
                    return
                }
                super.visitReferenceExpression(expression)
            }
        })
        return bypass ?: bareObject
    }

    fun scopeBypassMessage(klass: KtClass, bypass: KtElement): String {
        val written = when (bypass) {
            is KtCallExpression -> bypass.calleeExpression?.text ?: "?"
            else -> bypass.text
        }
        return "$klass constructs its own scope ($written). " +
            "Every scope from the background factory carries the failure handler that turns a " +
            "failed launch into a report instead of a killed Android process; a scope built " +
            "here bypasses it and compiles silently. Take an injected " +
            "AutoCloseableCoroutineScope instead."
    }

    fun missingReporterMessage(klass: KtClass): String =
        "${klass.name} can fail but never passes a CrashReportingPort, so its failures are " +
            "silently dropped. Add `crashReporter: CrashReportingPort` to the constructor " +
            "(before `scope`) and `crashReporter = get()` to the Koin binding."
}

/**
 * Registers [NoUnreportedFailurePathRule] in the `no-unreported-failure-path` rule set.
 */
class NoUnreportedFailurePathProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-unreported-failure-path")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoUnreportedFailurePath") to { cfg: Config ->
                NoUnreportedFailurePathRule(cfg)
            },
        ),
    )
}
