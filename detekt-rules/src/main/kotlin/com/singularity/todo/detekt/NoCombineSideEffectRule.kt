package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

/**
 * Bans `_state.value = ...` assignments inside `combine { ... }` lambda bodies.
 *
 * The `combine` function derives state from multiple upstream flows. Side effects inside
 * the combine lambda (mutating `_state.value`) cause TOCTOU races: when upstream
 * emissions interleave, `_state.value` can be overwritten mid-collect.
 *
 * The canonical pattern:
 * 1. Dedicated collector per upstream: `scope.launch { upstream.collect { _latestX.value = it } }`
 * 2. Separate `combine` reads from cached `_latestX` values without side effects
 * 3. Final `combine` result assigned to `_state.value` once, outside any lambda
 *
 * See `singularity-todo-coroutine-scopes` skill.
 *
 * @see NoCombineSideEffectRuleProvider for registration.
 */
class NoCombineSideEffectRule(config: Config) : Rule(config, "", null) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        checkCombineCall(expression)
    }

    private fun checkCombineCall(call: KtCallExpression) {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.text != "combine") return

        // Find the lambda argument inside combine(...)
        val lambdaArg = call.valueArguments
            .filterIsInstance<KtLambdaArgument>()
            .firstOrNull { it.getArgumentExpression() is KtLambdaExpression }
            ?: return

        val lambdaExpression = lambdaArg.getArgumentExpression() as? KtLambdaExpression ?: return
        val body = lambdaExpression.functionLiteral.bodyExpression ?: return

        // Scan the lambda body for `_state.value = ...` assignments
        scanForStateValueAssignment(body)
    }

    private fun scanForStateValueAssignment(expr: KtExpression) {
        when (expr) {
            is KtBlockExpression -> {
                for (statement in expr.statements) {
                    scanForStateValueAssignment(statement)
                }
            }
            is KtBinaryExpression -> {
                checkAssignment(expr)
                // Recurse into sub-expressions (e.g. inside when branches)
                expr.right?.let { scanForStateValueAssignment(it) }
            }
            // Also scan inside lambda expressions that appear in the combine body
            is KtLambdaExpression -> {
                expr.functionLiteral.bodyExpression?.let { scanForStateValueAssignment(it) }
            }
        }
    }

    private fun checkAssignment(binaryExpr: KtBinaryExpression) {
        val opRef = binaryExpr.operationToken as? KtOperationReferenceExpression ?: return
        val opText = opRef.text
        // Assignment operators: =, +=, -=, *=, /=, %=, &=, |=, ^=, <<=, >>=
        if (!opText.endsWith("=") && opText != "=") return

        val left = binaryExpr.left ?: return
        if (isStateValueAssignment(left)) {
            report(
                Finding(
                    entity = Entity.from(left),
                    message = "_state.value = ... inside a combine { } lambda is banned. " +
                        "Side effects inside combine cause TOCTOU races. " +
                        "Use dedicated upstream collectors + read-from-cache-in-combine pattern " +
                        "(see singularity-todo-coroutine-scopes skill).",
                    references = emptyList(),
                    suppressReasons = emptyList(),
                ),
            )
        }
    }

    /**
     * Checks whether `left` resolves to `_state.value` (possibly via safe-nav: `_state?.value`).
     */
    private fun isStateValueAssignment(left: KtExpression): Boolean {
        // Direct: _state.value
        if (left is org.jetbrains.kotlin.psi.KtDotQualifiedExpression) {
            val receiver = left.receiverExpression as? KtNameReferenceExpression
            if (receiver?.text == "_state") {
                val selector = left.selectorExpression as? KtSimpleNameExpression
                return selector?.text == "value"
            }
        }
        // Safe-nav: _state?.value
        if (left is KtSafeQualifiedExpression) {
            return isStateValueAssignment(left.receiverExpression)
        }
        return false
    }
}

/**
 * Registers [NoCombineSideEffectRule] in the `no-combine-side-effect` rule set.
 */
class NoCombineSideEffectRuleProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-combine-side-effect")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoCombineSideEffect") to { cfg: Config -> NoCombineSideEffectRule(cfg) },
        ),
    )
}
