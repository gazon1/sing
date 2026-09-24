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
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Bans `kotlinx.coroutines.runBlocking { ... }` in production code.
 *
 * `runBlocking` blocks the calling thread and is inappropriate in KMP production code
 * (ViewModels, repositories, use cases). It belongs only in tests and main entry points.
 * The canonical alternative is `CoroutineScope.launch { ... }` or `scope.launch { ... }`.
 *
 * Test sources are exempt — detekt's standard path filters handle test paths.
 *
 * @see NoRunBlockingProvider for registration.
 */
class NoRunBlockingRule(config: Config) : Rule(config, "", null) {

    override fun visitCallExpression(expression: KtCallExpression) {
        val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.text != "runBlocking") return

        report(
            Finding(
                entity = Entity.from(expression),
                message = "runBlocking is banned in production code. " +
                    "Use 'CoroutineScope.launch { ... }' or inject a scope and call " +
                    "'scope.launch { ... }' instead.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }
}

/**
 * Registers [NoRunBlockingRule] in the `no-runblocking` rule set.
 */
class NoRunBlockingProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-runblocking")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoRunBlocking") to { cfg: Config -> NoRunBlockingRule(cfg) },
        ),
    )
}
