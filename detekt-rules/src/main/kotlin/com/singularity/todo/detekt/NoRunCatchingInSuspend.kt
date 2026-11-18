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
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

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
                    message =
                        "runCatching is not allowed inside a suspend context. " +
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
     * Three shapes count:
     *
     * - a `suspend fun` — a [KtNamedFunction] carrying the modifier.
     * - a **suspend lambda** — a trailing/argument lambda passed to a call whose parameter
     *   type is `suspend`.
     * - an **inline lambda** nested inside a suspend context — e.g.
     *   `launch { items.forEach { runCatching { ... } } }`. `forEach` executes its lambda in the
     *   caller's context, so a `runCatching` inside it still runs on the coroutine and must be
     *   flagged.
     *
     * Lambda membership is decided by the **callee's name**, not by resolution: a PSI rule has
     * no type information. [SUSPEND_CALLS] marks suspend-lambda owners, [INLINE_CALLS] marks
     * common inline higher-order calls whose lambdas are transparent to the enclosing context,
     * and any other non-suspending lambda is treated as a boundary to avoid broad false
     * positives on unknown/non-inline callbacks.
     */
    private fun isInsideSuspendContext(expression: KtCallExpression): Boolean {
        var current = expression.parent
        while (current != null) {
            when (current) {
                is KtNamedFunction -> {
                    // A `suspend fun` boundary stops the walk in both directions: a
                    // `runCatching` below a non-suspend function is not ours to report.
                    return current.hasModifier(SUSPEND_KEYWORD)
                }

                is KtLambdaExpression -> {
                    if (isSuspendLambda(current)) return true
                    if (isInlineLambda(current)) {
                        current = current.parent
                        continue
                    }
                    return false
                }

                else -> {
                    current = current.parent
                }
            }
        }
        return false
    }

    /**
     * Whether [lambda] is an argument to a call whose parameter type is `suspend`.
     *
     * The owner is not the lambda's direct parent: a trailing lambda hangs off a
     * [KtLambdaArgument], while a parenthesized lambda hangs off a [KtValueArgument] inside a
     * [KtValueArgumentList]. Reading only [KtLambdaArgument] misses the parenthesized form and
     * lets traversal fall through, so `scope.launch { items.forEach({ runCatching { ... } }) }`
     * would be treated as a boundary. Getting this wrong is invisible: the rule stays silent
     * rather than wrong.
     */
    private fun isSuspendLambda(lambda: KtLambdaExpression): Boolean = owningCallName(lambda) in SUSPEND_CALLS

    private fun isInlineLambda(lambda: KtLambdaExpression): Boolean = owningCallName(lambda) in INLINE_CALLS

    private fun owningCallName(lambda: KtLambdaExpression): String? {
        val argument = lambda.parent as? KtValueArgument ?: return null
        val owner =
            when (val parent = argument.parent) {
                is KtCallExpression -> parent
                is KtValueArgumentList -> parent.parent as? KtCallExpression
                else -> null
            } ?: return null
        val callee = owner.calleeExpression as? KtNameReferenceExpression ?: return null
        return callee.text
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
        val SUSPEND_CALLS =
            setOf(
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
                "sequence",
            )

        /**
         * Common inline higher-order calls whose lambda runs in the caller's context.
         *
         * PSI rules cannot resolve inline-ness, so this name-based allowlist is used to walk
         * through inline lambdas (e.g. `forEach`, `let`) and keep detecting `runCatching` inside
         * an enclosing suspend context. Unknown/non-inline calls remain boundaries.
         */
        val INLINE_CALLS =
            setOf(
                "also",
                "apply",
                "buildList",
                "buildMap",
                "buildSet",
                "buildString",
                "filter",
                "filterIndexed",
                "filterIsInstance",
                "filterNot",
                "flatMap",
                "flatMapIndexed",
                "fold",
                "foldIndexed",
                "forEach",
                "forEachIndexed",
                "let",
                "map",
                "mapIndexed",
                "mapNotNull",
                "measureTimeMillis",
                "measureTimedValue",
                "onEach",
                "onEachIndexed",
                "reduce",
                "reduceIndexed",
                "repeat",
                "run",
                "synchronized",
                "takeIf",
                "takeUnless",
                "use",
                "with",
            )

        val SUSPEND_KEYWORD = KtTokens.SUSPEND_KEYWORD
    }
}

/**
 * Registers [NoRunCatchingInSuspend].
 */
class NoRunCatchingInSuspendProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-run-catching-in-suspend")

    override fun instance(): RuleSet =
        RuleSet(
            ruleSetId,
            mapOf<RuleName, (Config) -> Rule>(
                RuleName("NoRunCatchingInSuspend") to { cfg: Config -> NoRunCatchingInSuspend(cfg) },
            ),
        )
}
