package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Bans `updateState { it }` — a reducer that returns its input unchanged.
 *
 * The no-op reaches production through a specific trap. Written inside a `collect`
 * lambda, the reducer's `it` shadows the collected element:
 *
 * ```
 * flow.map { State.Loaded(...) }
 *     .collect { updateState { it } }   // the inner `it` is the *current* state
 * ```
 *
 * The inner `it` binds to the `updateState` transform parameter, so the transform is the
 * identity function and the freshly collected state is discarded. The screen stays on
 * its `Loading` branch forever while the data flows in normally. To assign a value
 * that is already computed, use `setState(collected)`; to genuinely derive a new value,
 * mutate it — `updateState { it.copy(isLoading = false) }`.
 *
 * Both the implicit-`it` form and the explicitly named form are flagged, since both are
 * the identity function.
 *
 * @see NoOpUpdateStateProvider for registration.
 */
class NoOpUpdateStateRule(config: Config) : Rule(config, "", null) {

    override fun visitCallExpression(expression: KtCallExpression) {
        // Without super the tree traversal stops at this node and updateState calls
        // deeper in the file would never be visited.
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.text != "updateState") return

        if (!isIdentityTransform(expression)) return

        report(
            Finding(
                entity = Entity.from(expression),
                message = "updateState { it } is a no-op: the transform returns the current state " +
                    "unchanged, so the value being collected is discarded and the screen never " +
                    "leaves its Loading branch. Use setState(collected) to assign an already " +
                    "computed value, or updateState { it.copy(...) } to actually derive one.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    /**
     * True when [call]'s sole lambda argument is the identity function — either
     * `updateState { it }` or `updateState { s -> s }`.
     */
    private fun isIdentityTransform(call: KtCallExpression): Boolean {
        if (call.valueArguments.size != 1) return false
        val arg = call.valueArguments.single().getArgumentExpression() ?: return false
        // The argument is the lambda itself in trailing-lambda form; collectDescendantsOfType
        // only walks descendants, so the root node has to be checked separately.
        val lambda = arg as? KtLambdaExpression
            ?: arg.collectDescendantsOfType<KtLambdaExpression>().firstOrNull()
            ?: return false

        val body = lambda.singleBodyReference() ?: return false
        val params = lambda.valueParameters

        // updateState { it } — body is the implicit lambda parameter
        if (params.isEmpty()) return body.getReferencedName() == "it"
        // updateState { s -> s } — body echoes the declared parameter
        return params.size == 1 && body.getReferencedName() == params.single().name
    }

    /**
     * The lambda's body as a bare name reference, or null when it is anything else.
     *
     * A lambda body parses as a [KtBlockExpression] even when it holds a single
     * expression, so `{ it }` has to be unwrapped before it can be matched. A block
     * with more than one statement is never a bare identity reducer.
     */
    private fun KtLambdaExpression.singleBodyReference(): KtNameReferenceExpression? {
        val body = bodyExpression ?: return null
        val statement = (body as? KtBlockExpression)?.statements?.singleOrNull() ?: body
        return statement as? KtNameReferenceExpression
    }
}

/**
 * Registers [NoOpUpdateStateRule] in the `no-op-update-state` rule set.
 */
class NoOpUpdateStateProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-op-update-state")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoOpUpdateState") to { cfg: Config -> NoOpUpdateStateRule(cfg) },
        ),
    )
}
