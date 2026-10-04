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
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Bans `.stateIn(...)` calls in production code.
 *
 * The canonical VM pattern uses plain `MutableStateFlow` + `scope.launch { }.collect {}`.
 * `stateIn` is permitted only on pure read-through VMs annotated with
 * `@OptIn(CombineStateInReadThrough::class)`. This rule flags violations so they
 * can be migrated to the canonical pattern per the `vm-migration-playbook` skill.
 *
 * Note: test-source exemption is not implemented via path filters in this rule.
 *
 * @see NoStateInRuleProvider for registration.
 */
class NoStateInRule(config: Config) : Rule(config, "", null) {

    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        checkDotQualified(expression)
    }

    private fun checkDotQualified(expr: KtDotQualifiedExpression) {
        val selector = expr.selectorExpression as? KtCallExpression ?: return
        val callee = selector.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.text != "stateIn") return

        // Find enclosing class
        var current: KtExpression? = expr
        while (current != null) {
            if (current is KtClass) {
                // Check for @OptIn(CombineStateInReadThrough::class)
                if (hasCombineStateInOptIn(current)) return
                break
            }
            current = current.parent as? KtExpression
        }

        report(
            Finding(
                entity = Entity.from(expr),
                message = ".stateIn(...) is banned in production VMs. " +
                    "Use plain MutableStateFlow + scope.launch { }.collect {} instead. " +
                    "Only pure read-through VMs with @OptIn(CombineStateInReadThrough::class) may use stateIn.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    private fun hasCombineStateInOptIn(cls: KtClass): Boolean = cls.annotationEntries.any { entry ->
        entry.typeReference?.text == "OptIn" &&
            entry.valueArguments.any { arg ->
                arg.getArgumentExpression()?.text == "CombineStateInReadThrough::class"
            }
    }
}

/**
 * Registers [NoStateInRule] in the `no-state-in` rule set.
 */
class NoStateInRuleProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-state-in")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoStateIn") to { cfg: Config -> NoStateInRule(cfg) },
        ),
    )
}
