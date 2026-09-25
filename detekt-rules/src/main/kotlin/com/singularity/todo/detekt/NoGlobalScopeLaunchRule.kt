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
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression

/**
 * Bans direct usage of `GlobalScope.launch`, `GlobalScope.async`,
 * and `GlobalScope.cancel` in production code.
 *
 * `GlobalScope` is an unstructured, process-wide coroutine scope. The canonical
 * pattern injects a [kotlinx.coroutines.CoroutineScope] as a constructor parameter
 * so that cancellation is tied to the component lifecycle. This rule flags violations
 * per the `coroutine-scopes` skill.
 *
 * Test sources are exempt.
 *
 * @see NoGlobalScopeLaunchRuleProvider for registration.
 */
class NoGlobalScopeLaunchRule(config: Config) : Rule(config, "", null) {

    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
    }

    override fun visitExpression(expression: KtExpression) {
        super.visitExpression(expression)
        checkExpression(expression)
    }

    private fun checkExpression(expression: KtExpression) {
        when (expression) {
            is KtDotQualifiedExpression -> checkDotQualified(expression)
            is KtSafeQualifiedExpression -> checkDotQualified(expression)
        }
    }

    private fun checkDotQualified(expr: KtDotQualifiedExpression) {
        checkReceiverAndMethod(expr.receiverExpression as? KtNameReferenceExpression, expr.selectorExpression as? KtCallExpression, expr)
    }

    private fun checkDotQualified(expr: KtSafeQualifiedExpression) {
        checkReceiverAndMethod(expr.receiverExpression as? KtNameReferenceExpression, expr.selectorExpression as? KtCallExpression, expr)
    }

    private fun checkReceiverAndMethod(
        receiver: KtNameReferenceExpression?,
        call: KtCallExpression?,
        expr: KtExpression,
    ) {
        if (receiver == null || call == null) return
        if (receiver.text != "GlobalScope") return
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return
        val methodName = callee.text
        if (methodName in ALLOWED_METHODS) return

        report(
            Finding(
                entity = Entity.from(expr),
                message = "GlobalScope.$methodName is banned. " +
                    "Use injected CoroutineScope instead: pass `scope: CoroutineScope` " +
                    "as a constructor parameter and call scope.launch { ... }.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    companion object {
        private val ALLOWED_METHODS = setOf("toString", "hashCode", "equals", "coroutineContext")
    }
}

/**
 * Registers [NoGlobalScopeLaunchRule] in the `no-global-scope` rule set.
 */
class NoGlobalScopeLaunchRuleProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-global-scope")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoGlobalScopeLaunch") to { cfg: Config -> NoGlobalScopeLaunchRule(cfg) },
        ),
    )
}
