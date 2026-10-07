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
import org.jetbrains.kotlin.psi.KtNamedDeclaration
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
 * - (any call site passing an empty lambda to a handler-shaped argument)
 *
 * "Handler-shaped" means `on` followed by an uppercase letter — see
 * [NoEmptyOnClickLambdaPolicy.isHandlerParameter] for why this is a shape and not a
 * list of ten names, which is what the rule used to carry and which five shipped
 * unwired surfaces walked straight past.
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

        // Drive off the parameter *shape*, not a list of known names. This used to be
        // gated on `fnName in EVENT_HANDLERS` — an 11-name allow-list of composables
        // — which meant the rule could only ever see the call sites somebody had
        // already thought to enumerate. Every `onClick = {}` passed to a composable
        // outside that list was invisible, and the rule reported 0 findings while
        // the production code contained them. The KDoc's promise is about the
        // *parameter* being an event handler, so the parameter is the gate.
        //
        // The name-list version of that gate (`PARAM_NAMES`, ten entries) failed the
        // same way one level down: `onAttachFile = {}`, `onAiAction = {}`,
        // `onWriteNote = {}`, `onAddChecklist = {}` and `onUnarchive = {}` were all
        // outside it, and each one was a real unwired surface found by reading the
        // code and invisible to the rule. An allow-list of names is a list somebody
        // has to remember to extend, so it is gone.
        // A read-only field's onValueChange cannot fire, so an empty one is not a dead
        // control — it is the only correct body. Material3 still requires the parameter,
        // so a read-only OutlinedTextField has to write something.
        val readOnlyField = isReadOnlyField(expression)
        for (arg in expression.valueArguments) {
            val paramName = arg.getArgumentName()?.text ?: continue
            if (!NoEmptyOnClickLambdaPolicy.isHandlerParameter(paramName)) continue
            if (readOnlyField && paramName == "onValueChange") continue
            val lambda = arg.getArgumentExpression() as? KtLambdaExpression ?: continue
            if (isEmptyLambda(lambda)) {
                val callee = expression.calleeExpression as? KtNameReferenceExpression
                reportFinding(lambda, paramName, callee?.text ?: "<expr>")
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
        if (!NoEmptyOnClickLambdaPolicy.isHandlerParameter(paramName)) return

        val fallback = expression.right as? KtLambdaExpression ?: return
        if (isEmptyLambda(fallback)) {
            reportFinding(fallback, paramName, "elvis fallback")
        }
    }

    private fun isEmptyLambda(lambda: KtLambdaExpression): Boolean {
        val body = lambda.functionLiteral.bodyExpression ?: return true
        return body.statements.isEmpty()
    }

    /**
     * True when this call passes `readOnly = true` at the same site.
     *
     * A read-only text field cannot produce a value change, so an empty `onValueChange`
     * beside it is unreachable rather than unwired — the field's value comes from a click
     * that opens a dialog (`WorkScheduleSettingsScreen`), and the field only displays the
     * result. Reporting it would be the rule complaining about the only body that is
     * correct.
     *
     * Scoped to the *same call* on purpose: `readOnly` somewhere else in the file says
     * nothing about this field.
     */
    private fun isReadOnlyField(expression: KtCallExpression): Boolean =
        expression.valueArguments.any { arg ->
            arg.getArgumentName()?.text == "readOnly" &&
                arg.getArgumentExpression()?.text == "true"
        }

    private fun isPreviewContext(element: org.jetbrains.kotlin.psi.KtElement): Boolean {
        val file = element.containingKtFile
        val path = file.virtualFile?.path ?: ""
        if (NoEmptyOnClickLambdaPolicy.isPreviewPath(file.name, path)) {
            return true
        }
        if (NoEmptyOnClickLambdaPolicy.isTestPath(path)) {
            return true
        }
        // Walk out to the nearest named declaration — the property or function the call
        // sits in — and exempt it if either it is a @Preview function or its own name
        // marks it as preview-only. The name check has to reach declarations, not just
        // functions: `previewOverrides` is a file-level val that exists to feed previews
        // and is neither in a preview/ path nor annotated.
        var current: org.jetbrains.kotlin.psi.KtElement? = element
        while (current != null) {
            if (current is KtNamedFunction) {
                if (current.annotationEntries.any { it.shortName?.asString() == "Preview" }) {
                    return true
                }
                // The name check has to run here too, before the break. This loop used
                // to stop at the function boundary, so a preview function that is named
                // for its role — `SettingsScreenPreview`, `TaskRowPreview` — was exempt
                // only when it also carried `@Preview`. SettingsScreen's
                // `SettingsScreenPreview(selectedTab)` has no annotation and passes three
                // empty handlers, so widening the name gate to the `on[A-Z]` shape made
                // the rule report a preview as if it were production code.
                if (NoEmptyOnClickLambdaPolicy.isPreviewNamed(current.name)) {
                    return true
                }
                // Stop at function boundary
                break
            }
            if (current is KtNamedDeclaration &&
                NoEmptyOnClickLambdaPolicy.isPreviewNamed(current.name)
            ) {
                return true
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
     * True when a parameter name reads as an event handler.
     *
     * This is the rule's whole gate, and it used to be a list of ten names —
     * `onClick`, `onConfirm`, `onDelete`, `onDismiss`, `onRetry`, `onSave`,
     * `onBack`, `onToggle`, `onEdit`, `onCheckedChange` — which meant the rule could
     * only see handler names somebody had already thought to enumerate. Five real
     * unwired surfaces shipped past it: `onAttachFile = {}` in `TaskCreateScreen`,
     * `onAiAction = {}` in `NoteAiActionSheet`, `onWriteNote = {}`, `onAddChecklist = {}`
     * and `onUnarchive = {}`. Each was a control a user could press that did nothing.
     *
     * The list is not a smaller version of the pattern; it is a stale snapshot of it.
     * So the gate is the shape the project already uses for callbacks — `on` followed
     * by an uppercase letter. That covers every name the old list had, and every one it
     * had missed, and it covers names that do not exist yet.
     *
     * `on` alone is not enough, and neither is `once` or `only`: the uppercase fourth
     * character is what separates a handler from a word that merely starts with `on`.
     */
    fun isHandlerParameter(paramName: String): Boolean =
        paramName.length > 2 &&
            paramName.startsWith("on") &&
            paramName[2].isUpperCase() &&
            paramName !in RESULT_FOLD_LABELS

    /**
     * `kotlin.Result.fold`'s two parameter names.
     *
     * They match the handler shape and are not handlers: `fold` names them, so no
     * caller can choose them, and an empty `onSuccess` branch means "nothing to do on
     * success", which is the whole reason to call `fold` instead of `map`/`getOrElse`.
     * Flagging them trains a reader to ignore the rule — `SearchViewModel.rename`
     * reported for exactly this.
     *
     * Every one of the 40+ `onSuccess =` / `onFailure =` sites in the repository is a
     * `fold` label; none is a UI callback. `NoEmptyOnClickLambdaRuleTest` pins that the
     * two names are not treated as handlers, so this exception cannot quietly grow into
     * a general allow-list.
     */
    private val RESULT_FOLD_LABELS = setOf("onSuccess", "onFailure")

    /** Same list `NoDirectDispatchersPolicy` uses, kept in step deliberately. */
    private val TEST_SOURCE_SETS = listOf("commonTest", "jvmTest", "androidTest", "iosTest", "jsTest")

    /**
     * True when the file looks like preview code: a name containing "preview", or a path
     * under a `preview/` package. The repo's convention is the latter (there is
     * `core/ui/preview/` and no `Preview.kt`), so both are accepted.
     */
    fun isPreviewPath(fileName: String, filePath: String): Boolean =
        fileName.lowercase().contains("preview") ||
            filePath.replace('\\', '/').contains("/preview/", ignoreCase = true)

    /**
     * True when the file is a test source.
     *
     * The rule's claim is that an empty handler lambda means the shipped consumer can
     * never react. A test that constructs a composable is satisfying that composable's
     * signature, not shipping a screen with a dead button — `onDismiss = {}` is how a
     * builder test says "I am only asserting this renders". Flagging those makes the
     * rule report 9 findings on `TaskMenuBuilderTest` and `MenuNodesBuilderTest` and
     * trains a reader to ignore it. Matches the path convention already used by
     * `NoDirectDispatchersPolicy`.
     */
    fun isTestPath(filePath: String): Boolean {
        val path = filePath.replace('\\', '/')
        return TEST_SOURCE_SETS.any { path.contains("/$it/") || path.endsWith("/$it") } ||
            path.contains("/test/")
    }

    /**
     * True when a declaration's own name marks it as preview-only.
     *
     * `isPreviewPath` only sees the file, and `@Preview` only marks a function. Neither
     * reaches a file-level `val` that exists solely to feed previews — `SettingsScreen`'s
     * `previewOverrides` is exactly that, and the rule reported both of its empty
     * lambdas. The name is the only signal there is, so the exemption reads the name.
     */
    fun isPreviewNamed(declarationName: String?): Boolean =
        declarationName != null && declarationName.contains("preview", ignoreCase = true)
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
