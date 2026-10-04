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
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTryExpression

/**
 * Bans `catch (e: Throwable)` / `catch (e: Exception)` inside `suspend` functions
 * without an intervening `throw (e as? CancellationException ?: ...)`.
 *
 * `CancellationException` is the cancellation mechanism in structured concurrency.
 * Catching it and returning a [Result.failure] makes the calling coroutine
 * silently continue past its cancellation boundary — a data-corruption class bug.
 * The canonical fix is `catch (e: CancellationException) { throw e }` before the
 * general `catch`, or using [kotlinx.coroutines.runCatchingCancellable].
 *
 * This rule targets the 16 HIGH-risk sites identified in Phase 1 of the
 * cancellation-integrity epic. It requires no baseline: all violations are
 * pre-filed and pre-fixed.
 *
 * This rule does NOT catch [runCatching] — see [NoRunCatchingInSuspend].
 *
 * @see NoRunCatchingInSuspend
 */
class NoSwallowedCancellation(config: Config) : Rule(config, "", null) {

    // The forward scan below uses two `continue` branches, and they are not redundant:
    // one handles a non-suspend clause, the other skips a clause already protected by an
    // earlier rethrow. Flattening them to satisfy detekt's jump-count heuristic would
    // obscure the ordering that makes this rule correct, and this rule guards a real bug
    // class (swallowed cancellation), so it is worth less readable than worth tidy.
    @Suppress("LoopWithTooManyJumpStatements")
    override fun visitTryExpression(expression: KtTryExpression) {
        super.visitTryExpression(expression)
        val clauses = expression.catchClauses
        // Iterate forward: if the FIRST catch rethrows CE, all subsequent catches are protected.
        // If a later catch rethrows CE, earlier catches are NOT protected (CE reaches them first).
        var cancellationHandled = false
        for (catchClause in clauses) {
            if (!isInsideSuspendFunction(catchClause)) {
                cancellationHandled = false
                continue
            }
            if (cancellationHandled) {
                // A previous catch already handles CE; this clause is unreachable for CE.
                continue
            }
            val catchBody = catchClause.catchBody ?: continue
            val firstStmt = findFirstStatement(catchBody)
            if (firstStmt == null || !isCancellationRethrow(firstStmt)) {
                val paramName = catchClause.catchParameter?.text ?: "Exception"
                report(
                    Finding(
                        entity = Entity.from(catchClause),
                        message = "catch ($paramName) in a suspend function " +
                            "must re-throw CancellationException before catching other exceptions. " +
                            "Add: catch (e: CancellationException) { throw e }",
                        references = emptyList(),
                        suppressReasons = emptyList(),
                    ),
                )
            } else {
                // This catch rethrows CE; subsequent catches are protected.
                cancellationHandled = true
            }
        }
    }

    private fun isInsideSuspendFunction(element: PsiElement): Boolean {
        var current: PsiElement? = element.parent
        while (current != null) {
            if (current is org.jetbrains.kotlin.psi.KtNamedFunction &&
                current.hasModifier(KtTokens.SUSPEND_KEYWORD)
            ) {
                return true
            }
            if (current is org.jetbrains.kotlin.psi.KtClassBody ||
                current is org.jetbrains.kotlin.psi.KtFile
            ) {
                return false
            }
            current = current.parent
        }
        return false
    }

    /**
     * Returns true if [expr] is a `throw` of a CancellationException
     * (direct name reference, or contains "CancellationException" in text), or a
     * `runCatchingCancellable { ... }` call.
     */
    private fun isCancellationRethrow(expr: KtExpression?): Boolean {
        when (expr) {
            is KtThrowExpression -> {
                val thrown = expr.thrownExpression
                // throw e — conservative: assume it's a CE rethrow (the most common pattern)
                if (thrown is KtNameReferenceExpression) return true
                // throw (e as? CancellationException) or throw (e as CancellationException)
                if (thrown != null && thrown.text.contains("CancellationException")) return true
            }

            is KtCallExpression -> {
                val callee = expr.calleeExpression as? KtNameReferenceExpression
                if (callee?.text == "runCatchingCancellable") return true
            }
        }
        return false
    }

    /**
     * Finds the first meaningful statement/expression in a catch body.
     */
    private fun findFirstStatement(element: org.jetbrains.kotlin.psi.KtExpression): KtExpression? {
        // If it's a block, get the first statement
        val block = element as? org.jetbrains.kotlin.psi.KtBlockExpression
        if (block != null) {
            return block.statements.firstOrNull() as? KtExpression
        }
        // Single expression body
        return element as? KtExpression
    }
}

/**
 * Registers [NoSwallowedCancellation].
 */
class NoSwallowedCancellationProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-swallowed-cancellation")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoSwallowedCancellation") to { cfg: Config -> NoSwallowedCancellation(cfg) },
        ),
    )
}
