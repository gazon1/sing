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
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Bans `kotlinx.coroutines.delay(N)` and bare `delay(N)` calls with N > 1 in test sources.
 *
 * Real delays block the test thread and prevent virtual-time testing. Use
 * `advanceUntilIdle()`, `advanceTimeBy()`, or `runCurrent()` from
 * `kotlinx.coroutines.test` instead.
 *
 * Exemptions:
 * - `delay(0)` and `delay(1)` — effectively no-ops, no virtual-time needed
 * - `delay` calls in non-test sources (detekt path filters exclude those)
 *
 * @see NoRealDelayInTestRuleProvider for registration.
 */
class NoRealDelayInTestRule(config: Config) : Rule(config, "", null) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        checkDelayCall(expression)
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val call = expression.selectorExpression as? KtCallExpression ?: return
        checkDelayCall(call)
    }

    private fun checkDelayCall(expression: KtCallExpression) {
        val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.text != "delay") return

        val argument = expression.valueArguments.firstOrNull() ?: return
        val valueText = argument.getArgumentExpression()?.text ?: return
        val value = valueText.toLongOrNull() ?: return
        if (value <= 1) return

        report(
            Finding(
                entity = Entity.from(expression),
                message = "delay($value) is a real-time block in tests. " +
                    "Use advanceUntilIdle(), advanceTimeBy($value), or runCurrent() " +
                    "from kotlinx.coroutines.test instead.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }
}

/**
 * Registers [NoRealDelayInTestRule] in the `no-real-delay-in-test` rule set.
 */
class NoRealDelayInTestRuleProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-real-delay-in-test")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoRealDelayInTest") to { cfg: Config -> NoRealDelayInTestRule(cfg) },
        ),
    )
}
