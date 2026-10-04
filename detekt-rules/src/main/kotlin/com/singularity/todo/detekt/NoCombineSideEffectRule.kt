package com.singularity.todo.detekt

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Bans side effects inside `combine(...) { ... }` transform lambdas.
 *
 * A `combine` transform must be **pure**: it maps the latest value of each
 * upstream flow to a new value and nothing else. Re-runs of the transform are
 * driven by upstream emissions, not by the transform's own writes, so a write
 * inside it re-fires on every unrelated emission — the classic stale-state and
 * lost-update bug.
 *
 * Flagged inside the transform lambda:
 * - assignments to a `StateFlow` backing field (`someFlow.value = x`, `+= x`)
 * - `DraftState.seed(...)` — seeding is an initialisation step, not a projection
 * - `Channel.send(...)` / `Channel.trySend(...)` — one-shot effects belong downstream
 * - `launchIn(...)` — subscriptions belong in their own `scope.launch { }` collector
 *
 * Pure transformations are untouched: constructing a data class, conditional
 * selection, or calling a pure helper all pass. The check is AST-local — it never
 * descends into a callee's body, so a pure function that internally writes is not
 * flagged here.
 *
 * @see NoCombineSideEffectProvider for registration.
 * @see singularity-todo-testable-vm — "side effects belong in collectors, not combines"
 */
class NoCombineSideEffectRule(config: Config) : Rule(config, "", null) {

    override fun visitLambdaExpression(expression: org.jetbrains.kotlin.psi.KtLambdaExpression) {
        // Without super the traversal stops here and lambdas deeper in the file
        // would never be visited.
        super.visitLambdaExpression(expression)
        if (!isCombineTransform(expression)) return
        inspectForSideEffects(expression)
    }

    /**
     * True when this lambda is the transform argument of a `combine`-family call.
     *
     * Handles both the trailing-lambda form (`combine(a, b) { .. }`) and the named
     * form (`combine(a, b, transform = { .. })`). The two differ in how many PSI
     * wrappers sit between the lambda and the call — a named argument nests the
     * lambda under a value-argument node — so the chain is walked rather than
     * assumed, and stops at the first enclosing call of any name.
     */
    private fun isCombineTransform(lambda: org.jetbrains.kotlin.psi.KtLambdaExpression): Boolean {
        var current: PsiElement? = lambda.parent
        // Bounded walk: the deepest wrapping seen between a lambda and its call is
        // two nodes (lambda -> value-argument -> call). The bound leaves headroom
        // without ever reaching a call in an enclosing statement.
        repeat(MAX_HOPS_UP_TREE) {
            when (val node = current) {
                null -> return false
                is KtCallExpression -> return isCombineCall(node)
                else -> current = node.parent
            }
        }
        return false
    }

    private fun isCombineCall(call: KtCallExpression?): Boolean {
        val callee = call?.calleeExpression as? KtNameReferenceExpression ?: return false
        return callee.text in COMBINE_FUNCTIONS
    }

    /** Depth-first walk of the transform body; reports and stops at the first offender. */
    private fun inspectForSideEffects(node: PsiElement) {
        if (isStateFlowAssignment(node)) {
            report(
                node,
                "Assigning to a StateFlow inside a combine() transform is a side effect. " +
                    "The transform re-runs on every upstream emission, so the write re-fires and " +
                    "produces stale state. Populate the cache from a dedicated 'collect { }' block instead.",
            )
            return
        }
        val callee = (node as? KtCallExpression)
            ?.calleeExpression
            ?.let { it as? KtNameReferenceExpression }
            ?.text
        if (callee != null && callee in SIDE_EFFECT_CALLS) {
            report(
                node,
                "'$callee(...)' inside a combine() transform is a side effect. " +
                    "Keep the transform pure; move this to a dedicated collector, the flow's " +
                    "own builder, or downstream of collect { }.",
            )
            return
        }
        node.children.forEach { inspectForSideEffects(it) }
    }

    private fun isStateFlowAssignment(node: PsiElement): Boolean {
        val binary = node as? KtBinaryExpression ?: return false
        if (binary.operationToken != KtTokens.EQ && binary.operationToken != KtTokens.PLUSEQ) return false
        return binary.left?.text?.trim()?.endsWith(VALUE_SUFFIX) == true
    }

    private fun report(node: PsiElement, message: String) {
        report(
            Finding(
                entity = Entity.from(node),
                message = message,
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    private companion object {
        /** How many PSI parents to walk looking for the enclosing call. */
        const val MAX_HOPS_UP_TREE = 3
        val COMBINE_FUNCTIONS = setOf("combine", "combineStates", "combineTransform")

        /**
         * Calls that write state or emit as a side effect of a projection.
         *
         * `updateState` and `setState` are here because writing UI state from inside a
         * transform is the same defect as `seed()`: the value belongs in the transform's
         * return, and the collector applies it.
         */
        val SIDE_EFFECT_CALLS = setOf("seed", "send", "trySend", "launchIn", "updateState", "setState")
        const val VALUE_SUFFIX = ".value"
    }
}

/**
 * Registers [NoCombineSideEffectRule] in the `no-combine-side-effect` rule set.
 */
class NoCombineSideEffectProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-combine-side-effect")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoCombineSideEffect") to { cfg: Config -> NoCombineSideEffectRule(cfg) },
        ),
    )
}
