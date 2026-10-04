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
 * Bans direct usage of `viewModelScope.launch`, `viewModelScope.async`,
 * and `viewModelScope.cancel` in production code.
 *
 * The canonical pattern injects a [kotlinx.coroutines.CoroutineScope] as a constructor
 * parameter so VMs are testable without `Dispatchers.setMain`. This rule flags violations
 * so they can be migrated per the `vm-migration-playbook` skill.
 *
 * Note: test-source exemption is not implemented via path filters in this rule.
 *
 * @see NoViewModelScopeInProductionProvider for registration.
 */
class NoViewModelScopeInProductionRule(config: Config) : Rule(config, "", null) {

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
        checkReceiverAndMethod(
            expr.receiverExpression as? KtNameReferenceExpression,
            expr.selectorExpression as? KtCallExpression,
            expr,
        )
    }

    private fun checkDotQualified(expr: KtSafeQualifiedExpression) {
        checkReceiverAndMethod(
            expr.receiverExpression as? KtNameReferenceExpression,
            expr.selectorExpression as? KtCallExpression,
            expr,
        )
    }

    private fun checkReceiverAndMethod(
        receiver: KtNameReferenceExpression?,
        call: KtCallExpression?,
        expr: KtExpression,
    ) {
        if (receiver == null || call == null) return
        if (receiver.text != "viewModelScope") return
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return
        val methodName = callee.text
        if (methodName in ALLOWED_METHODS) return

        report(
            Finding(
                entity = Entity.from(expr),
                message = "viewModelScope.$methodName is banned. " +
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
 * Registers [NoViewModelScopeInProductionRule] in the `no-viewmodel-scope` rule set.
 */
class NoViewModelScopeInProductionProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-viewmodel-scope")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoViewModelScopeInProduction") to { cfg: Config ->
                NoViewModelScopeInProductionRule(cfg)
            },
        ),
    )
}
