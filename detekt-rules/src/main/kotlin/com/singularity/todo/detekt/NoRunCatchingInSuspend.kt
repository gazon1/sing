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
 * Bans `kotlin.runCatching { ... }` directly inside a `suspend` function.
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
 * @see NoSwallowedCancellation
 */
class NoRunCatchingInSuspend(config: Config) : Rule(config, "", null) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.text != "runCatching") return

        // Walk up to find if we're inside a suspend function
        var current = expression.parent
        while (current != null) {
            if (current is org.jetbrains.kotlin.psi.KtNamedFunction &&
                current.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SUSPEND_KEYWORD)
            ) {
                report(
                    Finding(
                        entity = Entity.from(expression),
                        message = "runCatching is not allowed inside a suspend function. " +
                            "Use runCatchingCancellable { runCatching { ... } } instead, " +
                            "or replace with try/catch that re-throws CancellationException.",
                        references = emptyList(),
                        suppressReasons = emptyList(),
                    ),
                )
                return
            }
            current = current.parent
        }
    }
}

/**
 * Registers [NoRunCatchingInSuspend].
 */
class NoRunCatchingInSuspendProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-run-catching-in-suspend")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoRunCatchingInSuspend") to { cfg: Config -> NoRunCatchingInSuspend(cfg) },
        ),
    )
}
