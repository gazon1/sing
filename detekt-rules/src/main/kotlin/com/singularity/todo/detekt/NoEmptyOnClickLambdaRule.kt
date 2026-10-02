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
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Bans empty lambda placeholders passed as event handlers in composable calls,
 * specifically the pattern `onClick = {}` where `onClick` is an event callback
 * parameter and `{}` is an empty lambda with no side effects.
 *
 * This rule catches a specific anti-pattern: passing `{}` (or `{ /* nothing */ }`)
 * to a composable's event-handler parameter such as `onClick`, `onConfirm`, `onDelete`.
 * These empty lambdas make it impossible to distinguish "intentionally no-op because
 * this screen is not wired yet" from "forgot to handle this event".
 *
 * ## Allowed patterns (not flagged)
 * - `onClick = noopClick` — the shared no-op constant from `PreviewSamples`
 * - `onClick = { /* real logic */ }` — lambda with at least one statement
 * - `onClick = someHandler` — reference to a named function or variable
 * - All calls inside files whose name contains `preview` (case-insensitive) or
 *   inside functions annotated with `@Preview` — preview code is exempt
 *
 * ## Migration
 * 1. For preview code: replace `onClick = {}` with `onClick = noopClick`
 *    (from `com.singularity.todo.core.ui.preview.noopClick`).
 * 2. For production code: either wire the handler properly, make the parameter
 *    nullable (`onClick: (() -> Unit)? = null`), or remove the dead branch.
 *
 * @see NoEmptyOnClickLambdaProvider for registration.
 */
class NoEmptyOnClickLambdaRule(config: Config) : Rule(config, "", null) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (isPreviewContext(expression)) return

        val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return
        val fnName = callee.text

        // Only check well-known composable event handlers
        if (fnName !in EVENT_HANDLERS) return

        // Find named argument for each known onClick-like parameter
        for (paramName in PARAM_NAMES) {
            val arg = expression.valueArguments.find { it.getArgumentName()?.text == paramName }
            if (arg != null) {
                val lambda = arg.getArgumentExpression() as? KtLambdaExpression
                if (lambda != null && isEmptyLambda(lambda)) {
                    reportFinding(lambda, paramName, fnName)
                }
            }
        }
    }

    private fun isEmptyLambda(lambda: KtLambdaExpression): Boolean {
        val body = lambda.functionLiteral.bodyExpression ?: return true
        return body.statements.isEmpty()
    }

    private fun isPreviewContext(element: org.jetbrains.kotlin.psi.KtElement): Boolean {
        val file = element.containingKtFile
        val fileName = file.name.lowercase()
        // Skip preview files
        if (fileName.contains("preview")) return true
        // Skip functions annotated with @Preview
        var current: org.jetbrains.kotlin.psi.KtElement? = element
        while (current != null) {
            if (current is KtNamedFunction) {
                if (current.annotationEntries.any { it.shortName?.asString() == "Preview" }) {
                    return true
                }
                // Stop at function boundary
                break
            }
            current = current.parent as? org.jetbrains.kotlin.psi.KtElement
        }
        return false
    }

    private fun reportFinding(
        lambda: KtLambdaExpression,
        paramName: String,
        fnName: String,
    ) {
        report(
            Finding(
                entity = Entity.from(lambda),
                message = "Empty lambda passed to `$paramName` in `$fnName`. " +
                    "Use `noopClick` (for preview) or wire a real handler.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    companion object {
        /** Composable functions whose onClick-like parameters should be checked. */
        private val EVENT_HANDLERS = setOf(
            "AiActionButton",
            "DeleteActionButton",
            "SettingsActionRow",
            "SettingsValueRow",
            "BackTopAppBar",
            "EmptyState",
            "FilledTonalButton",
            "Button",
            "IconButton",
            "IconPickerRow",
            "SettingsSwitchRow",
        )

        /** Parameter names that indicate an event handler. */
        private val PARAM_NAMES = listOf(
            "onClick",
            "onConfirm",
            "onDelete",
            "onDismiss",
            "onRetry",
            "onSave",
            "onBack",
            "onToggle",
            "onEdit",
            "onCheckedChange",
        )
    }
}

/**
 * Registers [NoEmptyOnClickLambdaRule] in the `no-empty-onclick-lambda` rule set.
 */
class NoEmptyOnClickLambdaProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-empty-onclick-lambda")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoEmptyOnClickLambda") to { cfg: Config -> NoEmptyOnClickLambdaRule(cfg) },
        ),
    )
}
