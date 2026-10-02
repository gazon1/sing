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
 * Test sources are exempt (detekt's standard path filters handle patterns in test directories).
 *
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
        val path = element.containingKtFile.virtualFilePath
        return path.contains("core/platform/Clock.kt") ||
            path.contains("core/di/CoreDiModule.kt") ||
            path.contains("feature/notes/domain/DailyNoteFactory.kt") ||
            path.contains("commonTest/") ||
            path.contains("/test/")
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
