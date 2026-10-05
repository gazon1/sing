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
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Bans `.stateIn(...)` calls in production code.
 *
 * The canonical VM pattern uses plain `MutableStateFlow` + `scope.launch { }.collect {}`.
 * `stateIn` holds its upstream active for a fixed window after the last subscriber,
 * which is what made VMs untestable without virtual time; the ban exists to keep
 * that from coming back. Migrate violations with the `vm-migration-playbook` skill.
 *
 * There is deliberately **no opt-in hatch**. This rule used to exempt classes
 * annotated `@OptIn(CombineStateInReadThrough::class)`, but no such annotation
 * exists anywhere in the repository — so the exemption could never be taken, and
 * the KDoc and the finding message both told agents to apply an annotation that
 * would not compile. A documented escape hatch that does not exist is worse than
 * no escape hatch: it reads as permission. If a legitimate read-through VM ever
 * needs one, the honest form is a `@Suppress("NoStateIn")` with a reason, which
 * at least shows up in review.
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

        report(
            Finding(
                entity = Entity.from(expr),
                message = ".stateIn(...) is banned in production VMs. " +
                    "Use plain MutableStateFlow + scope.launch { }.collect {} instead. " +
                    "If this is a genuine read-through VM, suppress with " +
                    "@Suppress(\"NoStateIn\") and a reason rather than an opt-in annotation.",
                references = emptyList(),
                suppressReasons = listOf("NoStateIn"),
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
