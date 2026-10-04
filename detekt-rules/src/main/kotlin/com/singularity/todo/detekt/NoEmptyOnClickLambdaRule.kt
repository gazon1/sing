package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
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
 * Three shapes are reported, all keyed on the *parameter* name:
 * - `onClick = {}` at a call site
 * - `onClick ?: { }` as an elvis fallback in a body
 * - (any call site passing an empty lambda to a `PARAM_NAMES` argument)
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

        // Drive off PARAM_NAMES, not a list of known callees. This used to be
        // gated on `fnName in EVENT_HANDLERS` — an 11-name allow-list of composables
        // — which meant the rule could only ever see the call sites somebody had
        // already thought to enumerate. Every `onClick = {}` passed to a composable
        // outside that list was invisible, and the rule reported 0 findings while
        // the production code contained them. The KDoc's promise is about the
        // *parameter* being an event handler, so the parameter is the gate.
        for (paramName in PARAM_NAMES) {
            val arg = expression.valueArguments.find { it.getArgumentName()?.text == paramName }
            if (arg != null) {
                val lambda = arg.getArgumentExpression() as? KtLambdaExpression
                if (lambda != null && isEmptyLambda(lambda)) {
                    val callee = expression.calleeExpression as? KtNameReferenceExpression
                    reportFinding(lambda, paramName, callee?.text ?: "<expr>")
                }
            }
        }
    }

    /**
     * Flags the elvis-fallback shape: `onClick ?: { }`.
     *
     * The defect is the *pair*. A handler parameter that defaults to an empty
     * lambda is harmless on its own — `onClick()` just does nothing, and the
     * declaration reads as a normal optional default. It becomes a real bug when
     * the body writes `onClick ?: { }`, because the elvis can never take its
     * right-hand branch for a non-null parameter: the fallback is dead code that
     * reads as if it were the only place the handler is implemented. This is the
     * same shape `find-unwired-surfaces.py` calls `default-noop`.
     *
     * Note this is deliberately NOT the same as flagging `onClick: () -> Unit = {}`
     * on its own — a test in this file pins that distinction, and a declaration
     * that never uses elvis is a normal optional-parameter default.
     */
    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        if (isPreviewContext(expression)) return
        if (expression.operationToken != KtTokens.ELVIS) return

        val receiver = expression.left as? KtNameReferenceExpression ?: return
        val paramName = receiver.text
        if (paramName !in PARAM_NAMES) return

        val fallback = expression.right as? KtLambdaExpression ?: return
        if (isEmptyLambda(fallback)) {
            reportFinding(fallback, paramName, "elvis fallback")
        }
    }

    private fun isEmptyLambda(lambda: KtLambdaExpression): Boolean {
        val body = lambda.functionLiteral.bodyExpression ?: return true
        return body.statements.isEmpty()
    }

    private fun isPreviewContext(element: org.jetbrains.kotlin.psi.KtElement): Boolean {
        val file = element.containingKtFile
        if (NoEmptyOnClickLambdaPolicy.isPreviewPath(file.name, file.virtualFile?.path ?: "")) {
            return true
        }
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
                message = "Passing an empty lambda to `$paramName` in `$fnName` defeats the " +
                    "handler: the consumer cannot tell 'intentionally no-op' from 'not wired'. " +
                    "Use `noopClick` (for preview) or wire a real handler.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    companion object {
        /** Parameter names that indicate an event handler. This is the rule's
         *  only gate — see [visitCallExpression] for why the callee allow-list
         *  that used to live here was removed. */
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
 * The file-name/path half of [NoEmptyOnClickLambdaRule]'s preview exemption, as a pure
 * function so it can be tested.
 *
 * It had to be extracted for the same reason as the other two rules: `compileContentForTest`
 * derives the file name from the package argument, so no fixture can produce a file named
 * `*Preview*` and the branch was untestable. It was also untested — an earlier version of
 * this file's test asserted `"taskrowpreview".contains("preview")`, which is true
 * regardless of the rule and so proved nothing.
 */
internal object NoEmptyOnClickLambdaPolicy {

    /**
     * True when the file looks like preview code: a name containing "preview", or a path
     * under a `preview/` package. The repo's convention is the latter (there is
     * `core/ui/preview/` and no `Preview.kt`), so both are accepted.
     */
    fun isPreviewPath(fileName: String, filePath: String): Boolean =
        fileName.lowercase().contains("preview") ||
            filePath.replace('\\', '/').contains("/preview/", ignoreCase = true)
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
