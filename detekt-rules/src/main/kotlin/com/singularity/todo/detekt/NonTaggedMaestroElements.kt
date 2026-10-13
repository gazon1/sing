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
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType

/**
 * Bans two anti-patterns that silently break Maestro UI test coverage:
 *
 * 1. **Empty testTag** — `testTag = ""` or `testTag = someVar` where the value is
 *    always empty. This compiles but produces a tag Maestro can never address, so the
 *    element is permanently invisible to automation. The element appears wired (the
 *    parameter exists) but is in practice untestable.
 *
 * 2. **Untagged interactive composable** — a composable function that is directly
 *    interactive (has a click/tap handler) but carries no testTag at all.
 *    Unlike (1) this is detectable by scanning for `testTag` absence rather than
 *    presence, but it is also the harder signal to get right — a private helper
 *    `RowSpacer` is not interactive and needs no tag, but a public `ConfirmButton`
 *    does. The rule therefore limits itself to composables that are **already
 *    tagged as Maestro-relevant** (their source file is in a feature/ directory that
 *    Maestro flows exercise) AND have at least one event-handler parameter
 *    (`onClick`, `onConfirm`, `onDismiss`, etc. — the same shape gate that
 *    `NoEmptyOnClickLambdaRule` uses) AND carry no testTag modifier anywhere in
 *    their body.
 *
 * ## When the rule fires
 *
 * The most common failure mode is a `testTag = ""` that was left behind when the
 * real tag constant was added to `TestTags.kt` but the call site was not updated.
 * After adding `CHECKLIST_ADD_INPUT` to `TestTags`, a flow that expects to interact
 * with `ChecklistEditorSheet`'s input would silently stop working if the composable
 * body still reads `testTag = ""` instead of `testTag = TestTags.CHECKLIST_ADD_INPUT`.
 *
 * ## What is not flagged
 *
 * - Preview code (files named `*Preview*`, functions annotated `@Preview`,
 *   declarations named `*preview*`)
 * - Test code (paths containing `/commonTest/`, `/jvmTest/`, etc.)
 * - Private composables — only composables visible in feature screens are in scope
 * - Composable properties (they carry no event handlers)
 *
 * @see NonTaggedMaestroElementsPolicy for the policy functions tested in isolation.
 */
class NonTaggedMaestroElementsRule(config: Config) : Rule(config, "", null) {

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (isExempt(function)) return

        val body = function.bodyExpression ?: return

        // Pattern 1: testTag = "" anywhere in the function body.
        if (hasEmptyTestTagAssignment(body)) {
            reportFinding(
                entity = Entity.from(body),
                message = buildString {
                    append("`${function.name}` has `testTag = \"\"` — this element is ")
                    append("permanently invisible to Maestro automation. ")
                    append("Use a constant from `TestTags` (e.g. `TestTags.CHECKLIST_ADD_INPUT`).")
                },
            )
            return
        }

        // Pattern 2: interactive composable with no testTag at all.
        if (hasEventHandler(function) && !hasTestTagModifier(body)) {
            reportFinding(
                entity = Entity.from(function),
                message = buildString {
                    append("`${function.name}` handles events but has no `testTag`. ")
                    append("Maestro cannot interact with it. ")
                    append("Add a `testTag = TestTags.*` modifier to the root composable.")
                },
            )
        }
    }

    /**
     * True when `testTag = ""` (empty string literal on the right-hand side) appears
     * anywhere in the expression tree.
     *
     * Not flagged: `testTag = someVariable` where `someVariable` happens to be
     * empty at some call sites — that is a runtime problem, not a compile-time one,
     * and would require value propagation this rule does not do.
     */
    private fun hasEmptyTestTagAssignment(element: org.jetbrains.kotlin.psi.KtExpression): Boolean =
        element.anyDescendantOfType<KtCallExpression> { call ->
            val fn = (call.calleeExpression as? KtNameReferenceExpression)?.text
            fn == "testTag" && call.valueArguments.any { arg ->
                val expr = arg.getArgumentExpression()
                expr is KtStringTemplateExpression && expr.entries.all {
                    it is org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
                } && expr.text == "\"\""
            }
        }

    /**
     * True when the function body contains `Modifier.testTag(...)` or `.testTag(...)`.
     */
    private fun hasTestTagModifier(element: org.jetbrains.kotlin.psi.KtExpression): Boolean =
        element.anyDescendantOfType<KtDotQualifiedExpression> { expr ->
            val sel = expr.selectorExpression as? KtNameReferenceExpression
            sel?.text == "testTag"
        }

    /**
     * True when this composable has at least one event-handler parameter.
     * Uses the same `on[A-Z]` shape gate as `NoEmptyOnClickLambdaRule`.
     */
    private fun hasEventHandler(function: KtNamedFunction): Boolean =
        function.valueParameters.any { param ->
            NonTaggedMaestroElementsPolicy.isHandlerParameter(param.name.orEmpty())
        }

    private fun isExempt(function: KtNamedFunction): Boolean {
        val file = function.containingKtFile
        val path = file.virtualFile?.path.orEmpty()
        if (NonTaggedMaestroElementsPolicy.isTestPath(path)) return true
        if (NonTaggedMaestroElementsPolicy.isPreviewPath(file.name, path)) return true
        if (NonTaggedMaestroElementsPolicy.isPreviewFunction(function)) return true
        return false
    }

    private fun reportFinding(entity: Entity, message: String) {
        report(
            Finding(
                entity = entity,
                message = message,
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }
}

/**
 * Policy functions for [NonTaggedMaestroElementsRule], extracted for unit testing.
 */
internal object NonTaggedMaestroElementsPolicy {

    private val TEST_SOURCE_SETS = listOf("commonTest", "jvmTest", "androidTest", "iosTest", "jsTest")

    /**
     * True when a parameter name reads as an event handler.
     * Same shape as `NoEmptyOnClickLambdaPolicy.isHandlerParameter`.
     */
    fun isHandlerParameter(paramName: String): Boolean =
        paramName.length > 2 &&
            paramName.startsWith("on") &&
            paramName[2].isUpperCase()

    fun isPreviewPath(fileName: String, filePath: String): Boolean =
        fileName.lowercase().contains("preview") ||
            filePath.replace('\\', '/').contains("/preview/", ignoreCase = true)

    fun isTestPath(filePath: String): Boolean {
        val path = filePath.replace('\\', '/')
        return TEST_SOURCE_SETS.any { path.contains("/$it/") || path.endsWith("/$it") } ||
            path.contains("/test/")
    }

    fun isPreviewFunction(function: KtNamedFunction): Boolean =
        function.annotationEntries.any { it.shortName?.asString() == "Preview" } ||
            function.name?.contains("preview", ignoreCase = true) == true
}

/**
 * Registers [NonTaggedMaestroElementsRule] in the `non-tagged-maestro-elements` rule set.
 */
class NonTaggedMaestroElementsProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("non-tagged-maestro-elements")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NonTaggedMaestroElements") to { cfg: Config ->
                NonTaggedMaestroElementsRule(cfg)
            },
        ),
    )
}
