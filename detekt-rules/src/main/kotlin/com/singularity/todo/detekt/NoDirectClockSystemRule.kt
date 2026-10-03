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
 * Bans direct references to `Clock.System` in commonMain production code.
 *
 * The project injects `kotlin.time.Clock` as a Koin singleton (`single { Clock.System }`).
 * Direct references bypass the DI container, making code harder to test and creating
 * implicit time dependencies that cannot be mocked or controlled.
 *
 * The only allowed call sites are:
 * - [core.platform.Clock.kt][com.singularity.todo.core.platform] — `todayAt` and `todayFlow`
 *   wrap Clock.System behind the public `Clock` interface.
 * - [CoreDiModule][com.singularity.todo.core.di] — where the singleton binding is declared.
 *
 * Note: test sources are not exempted — the rule runs in all source sets.
 * For wall-clock timestamps in test helpers use `java.time.Instant.now()`
 * directly; it is not subject to this rule.
 * ## Migration
 * Instead of `Clock.System.now()`, inject `Clock` as a constructor or module parameter:
 * ```kotlin
 * class MyRepository(private val clock: Clock) {
 *     fun now(): Instant = clock.now()
 * }
 * ```
 *
 * @see NoDirectClockSystemProvider for registration.
 */
class NoDirectClockSystemRule(config: Config) : Rule(config, "", null) {

    /**
     * Returns true if [expr] is `Clock.System`.
     */
    private fun isClockSystem(expr: KtDotQualifiedExpression): Boolean {
        val innerReceiver = expr.receiverExpression as? KtNameReferenceExpression ?: return false
        val innerSelector = expr.selectorExpression as? KtNameReferenceExpression ?: return false
        return innerReceiver.text == "Clock" && innerSelector.text == "System"
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)

        if (isClockSystem(expression)) {
            if (isAllowedFile(expression)) return
            reportFinding(expression, "Direct `Clock.System` is banned. Inject `kotlin.time.Clock` instead.")
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression
        // `Clock.System.now()` — callee is `KtDotQualifiedExpression(Clock.System)`
        val dotCallee = callee as? KtDotQualifiedExpression ?: return
        if (isClockSystem(dotCallee)) {
            if (isAllowedFile(expression)) return
            reportFinding(expression, "Direct `Clock.System` is banned. Inject `kotlin.time.Clock` instead.")
        }
    }

    private fun isAllowedFile(element: org.jetbrains.kotlin.psi.KtElement): Boolean {
        val path = element.containingKtFile.virtualFilePath.replace('\\', '/')
        return isAllowedPath(path)
    }

    /**
     * Returns true if [path] points to an allowed source file.
     * The path must end with the canonical file name to avoid false positives
     * on similarly-named files in other directories (e.g. `Clock.kt.bak`,
     * `Clock.ktHelpers.kt`, or files in subdirectories).
     */
    internal fun isAllowedPath(path: String): Boolean {
        return path.endsWith("/core/platform/Clock.kt") ||
            path.endsWith("/core/di/CoreDiModule.kt")
    }

    private fun reportFinding(element: org.jetbrains.kotlin.psi.KtElement, message: String) {
        report(
            Finding(
                entity = Entity.from(element),
                message = message,
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }
}

/**
 * Registers [NoDirectClockSystemRule] in the `no-direct-clock-system` rule set.
 */
class NoDirectClockSystemProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-direct-clock-system")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoDirectClockSystem") to { cfg: Config -> NoDirectClockSystemRule(cfg) },
        ),
    )
}
