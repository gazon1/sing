package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Bans `kotlin.runCatching { ... }` inside a `suspend` function **or a suspend lambda**.
 *
 * `kotlin.runCatching` catches `Throwable` — including `CancellationException`.
 * In a suspend function, `CancellationException` must propagate to abort the
 * calling coroutine; swallowing it lets the coroutine silently continue past
 * its cancellation boundary, corrupting state.
 *
 * The safe alternative is [kotlinx.coroutines.runCatchingCancellable], which
 * re-throws `CancellationException`. If the existing code already wraps the
 * `runCatching` call in a `catchTo` or other outer handler that re-throws
 * cancellation, that is out of scope for this rule (it would require taint
 * tracking to model accurately).
 *
 * The canonical fix is to wrap the `runCatching` call in a
 * `runCatchingCancellable { runCatching { ... } }` block, or to replace
 * `runCatching` with direct `try/catch` that re-throws CancellationException.
 *
 * This rule uses a baseline: existing violations are suppressed, new additions fail.
 *
 * ## What the activation note got wrong
 *
 * `config/detekt/detekt.yml` records that after `db132beb` migrated 199 call sites, "with the
 * rule active the tree reports zero violations, verified by planting one and reading the
 * report". That verification planted a `suspend fun`. The walk stopped at named functions, so
 * `scope.launch { runCatching { ... } }` — the shape every background repository `init` in
 * this codebase uses — was never examined, and `SyncPrefs` carried a plain `runCatching`
 * through the gate. The tree reported zero because the shape was not searched, not because it
 * was clean.
 *
 * @see NoSwallowedCancellation
 */
class NoRunCatchingInSuspend(config: Config) : Rule(config, "", null) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.text != "runCatching") return

        if (isInsideSuspendContext(expression)) {
            report(
                Finding(
                    entity = Entity.from(expression),
                    message = "runCatching is not allowed inside a suspend context. " +
                        "Use runCatchingCancellable { runCatching { ... } } instead, " +
                        "or replace with try/catch that re-throws CancellationException.",
                    references = emptyList(),
                    suppressReasons = emptyList(),
                ),
            )
        }
    }

    /**
     * Whether [expression] sits inside something that runs on a coroutine.
     *
     * Two shapes count, and the second one used to be missed:
     *
     * - a `suspend fun` — a [KtNamedFunction] carrying the modifier.
     * - a **suspend lambda** — a trailing/argument lambda passed to a call whose parameter
     *   type is `suspend`.
     *
     * The second is not a cosmetic addition. `scope.launch { runCatching { ... } }` is the
     * shape every background repository `init` block in this codebase uses, so a walk that stops
     * at named functions reported a clean tree while `SyncPrefs` sat there with a plain
     * `runCatching`. The rule's own activation note claimed "the tree reports zero
     * violations", which was true only of the shape that had been tested.
     *
     * Lambda membership is decided by the **callee's name**, not by resolution: a PSI rule has
     * no type information, and an allowlist is the safe polarity for that. A name not in
     * [SUSPEND_CALLS] is assumed not to take a suspend lambda, so `list.forEach { runCatching
     * { ... } }` is still not flagged — which is the correct answer, since a `forEach` lambda
     * is not suspending.
     */
    private fun isInsideSuspendContext(expression: KtCallExpression): Boolean {
        var current = expression.parent
        while (current != null) {
            when (current) {
                is KtNamedFunction -> {
                    // A `suspend fun` boundary stops the walk in both directions: a
                    // `runCatching` below a non-sus suspend function is not ours to report.
                    return current.hasModifier(SUSPEND_KEYWORD)
                }

                is KtLambdaExpression -> return isSuspendLambda(current)

                else -> current = current.parent
            }
        }
        return false
    }

    /**
     * Whether [lambda] is an argument to a call whose parameter type is `suspend`.
     *
     * The owner is not the lambda's direct parent: in Kotlin PSI a trailing lambda's
     * [KtLambdaExpression] hangs off a [KtLambdaArgument], and *that* is what points at the
     * call. Reading `lambda.parent as? KtCallExpression` therefore yields null, the walk falls
     * through to the enclosing `fun`, and `scope.launch { runCatching { ... } }` reports zero
     * findings while looking like a passing test — because the `fun` that owns the `launch` is
     * not itself `suspend`. Getting this wrong is invisible: the rule stays silent rather than
     * wrong, which is the one failure mode a detector cannot announce.
     */
    private fun isSuspendLambda(lambda: KtLambdaExpression): Boolean {
        val argument = lambda.parent as? KtLambdaArgument ?: return false
        val owner = argument.parent as? KtCallExpression ?: return false
        val callee = owner.calleeExpression as? KtNameReferenceExpression ?: return false
        return callee.text in SUSPEND_CALLS
    }

    private companion object {
        /**
         * Calls whose lambda parameter is a `suspend` lambda.
         *
         * `launch`/`async` are the ones that matter here — they are how every background
         * collector in the app is written. `collect` is included because the collector lambda
         * of a `Flow` is suspend and `runCatching` inside one is the same hazard.
         *
         * Deliberately an allowlist: adding a name risks a false positive on a same-named
         * non-suspend call, while omitting one only loses a detection the rule did not
         * previously have.
         */
        val SUSPEND_CALLS = setOf(
            "launch",
            "async",
            "withContext",
            "coroutineScope",
            "supervisorScope",
            "runInterruptible",
            "produce",
            "actor",
            "channelFlow",
            "collect",
            "collectLatest",
            "transform",
            "mapLatest",
            "launchIn",
        )

        val SUSPEND_KEYWORD = KtTokens.SUSPEND_KEYWORD
    }
}

/**
 * Registers [NoRunCatchingInSuspend].
 */
class NoRunCatchingInSuspendProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-run-catching-in-suspend")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoRunCatchingInSuspend") to { cfg: Config -> NoRunCatchingInSuspend(cfg) },
        ),
    )
}
