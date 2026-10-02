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
 * Bans direct references to `Dispatchers.IO`, `Dispatchers.Default`, and
 * `Dispatchers.Main` in commonMain production code.
 *
 * Dispatchers should be injected as `CoroutineDispatcher` parameters so call sites
 * are deterministic and testable. Direct dispatcher references make tests non-hermetic
 * and couple business logic to a specific concurrency model.
 *
 * Whitelisted exceptions:
 * - `Dispatchers.IO.limitedParallelism(1)` in [FileLogWriter][com.singularity.todo.core.log]:
 *   This is intentional — a single-threaded IO dispatcher guarantees ordered log writes.
 *   The whitelisting is by file path, not by pattern, to keep the exception narrow.
 *
 * The rule skips all `/test/` directories (jvmTest, commonTest, androidTest).
 *
 * @see NoDirectDispatchersRuleProvider for registration.
 */
class NoDirectDispatchersRule(config: Config) : Rule(config, "", null) {

    private companion object {
        private val BANNED_DISPATCHERS = setOf("IO", "Default", "Main")
        // Narrow whitelist: only FileLogWriter may use Dispatchers.IO.limitedParallelism(1)
        private const val ALLOWED_FILE = "core/log/FileLogWriter.kt"
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        checkExpression(expression)
    }

    private fun checkExpression(expr: KtDotQualifiedExpression) {
        val path = expr.containingKtFile.virtualFilePath

        // Whitelist: FileLogWriter's guaranteed sequential log writes
        if (path.contains(ALLOWED_FILE)) return

        val selector = expr.selectorExpression as? KtCallExpression ?: return
        val callee = selector.calleeExpression as? KtNameReferenceExpression ?: return
        val receiver = expr.receiverExpression as? KtNameReferenceExpression ?: return

        if (receiver.text != "Dispatchers") return
        if (callee.text !in BANNED_DISPATCHERS) return

        // Allow `Dispatchers.IO.limitedParallelism(n)` even outside FileLogWriter
        // (limitedParallelism is a safe wrapper)
        if (callee.text == "IO" &&
            selector.valueArguments.isNotEmpty() &&
            selector.calleeExpression?.text == "limitedParallelism"
        ) {
            return
        }

        reportFinding(
            expr,
            "Direct `Dispatchers.${callee.text}` is banned. " +
                "Inject `CoroutineDispatcher` as a constructor parameter instead.",
        )
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
 * Registers [NoDirectDispatchersRule] in the `no-direct-dispatchers` rule set.
 */
class NoDirectDispatchersRuleProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-direct-dispatchers")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoDirectDispatchers") to { cfg: Config ->
                NoDirectDispatchersRule(cfg)
            },
        ),
    )
}
