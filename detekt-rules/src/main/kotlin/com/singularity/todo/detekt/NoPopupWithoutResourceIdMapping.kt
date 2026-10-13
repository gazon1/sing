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
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType

/**
 * Bans the pattern where a `testTag()` call appears inside a Popup-owning container
 * (ModalBottomSheet, Popup, AlertDialog, DropdownMenu) without the container's root
 * modifier calling `mapTestTagsAsResourceIds()`.
 *
 * On Android, UI automation (Maestro, UI Automator) reads element addressesability through
 * the Android resource-id, which is a _window-scoped_ property. A testTag applied inside
 * a Popup stays scoped to the Popup's semantic tree and never reaches the resource-id
 * layer unless the Popup's root modifier explicitly calls `mapTestTagsAsResourceIds()`.
 *
 * The result is a Maestro flow that uses `id: my_sheet` and always fails, even though:
 * - `const val MY_SHEET = "my_sheet"` exists in TestTags.kt
 * - `testTag = TestTags.MY_SHEET` is written in the composable
 * - Every flow that depends on this tag silently falls back to text selectors
 *
 * This is the "Popup testTag duplication" pattern: the constant is duplicated (defined
 * and used) but the element is never addressable by id.
 *
 * ## What this rule flags
 *
 * A composable function that:
 * 1. Calls a Popup-owning function (`ModalBottomSheet`, `Popup`, `AlertDialog`, or
 *    `DropdownMenu`) **without** chaining `.mapTestTagsAsResourceIds()` on the call
 *    expression itself, AND
 * 2. Contains a `testTag()` call inside the lambda block that is passed to that Popup
 *
 * ```kotlin
 * // ❌ Flagged: testTag inside ModalBottomSheet with no resource-id mapping
 * ModalBottomSheet(...) {
 *     Column(modifier = Modifier.testTag(TestTags.MY_SHEET)) {  // unreachable!
 *         ...
 *     }
 * }
 *
 * // ✅ OK: ModalBottomSheet with mapTestTagsAsResourceIds
 * ModalBottomSheet(
 *     modifier = Modifier.mapTestTagsAsResourceIds(),
 *     ...
 * ) {
 *     Column(modifier = Modifier.testTag(TestTags.MY_SHEET)) {  // reachable
 *         ...
 *     }
 * }
 *
 * // ✅ OK: AlertDialog applies mapTestTagsAsResourceIds to its root modifier
 * AlertDialog(
 *     modifier = Modifier
 *         .testTag(TestTags.RESULT_DIALOG)
 *         .mapTestTagsAsResourceIds(),
 *     ...
 * )
 * ```
 *
 * ## Limitations
 *
 * - The rule uses a conservative structural heuristic: it looks for a `testTag()` call
 *   anywhere inside the lambda passed to a Popup function. It does not attempt full
 *   lambda-scope resolution, so it may miss cases where testTag is inside a deeply
 *   nested composable that is NOT in the Popup's content lambda.
 * - `DropdownMenu` is included because it is a Popup on desktop as well as Android,
 *   but the same window-boundary issue applies on desktop (desktop Compose test API
 *   reads testTags directly from the semantics tree, so this rule is most relevant
 *   for Android Popup containers).
 * - Test files are exempt.
 *
 * @see NoPopupWithoutResourceIdMappingProvider for registration.
 */
class NoPopupWithoutResourceIdMapping(config: Config) : Rule(config, "", null) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (isPreviewOrTestContext(expression)) return

        val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return
        val calleeText = callee.text

        if (calleeText !in POPUP_CONTAINERS) return

        // Check if this call already applies mapTestTagsAsResourceIds on itself.
        // AlertDialog pattern: AlertDialog(modifier = Modifier.testTag(...).mapTestTagsAsResourceIds())
        if (hasMapResourceIdOnCall(expression)) return

        // Check if a modifier parameter chain already applies it.
        // ModalBottomSheet(modifier = Modifier.mapTestTagsAsResourceIds(), ...)
        if (hasMapResourceIdOnModifierParam(expression)) return

        // The Popup call is unguarded. Now check if any testTag() exists inside the
        // lambda that is the last positional argument (the content lambda).
        val lambda = contentLambda(expression) ?: return

        val hasTestTagInside = lambda.anyDescendantOfType<KtCallExpression> { call ->
            val name = (call.calleeExpression as? KtNameReferenceExpression)?.text
            name == "testTag"
        }
        if (!hasTestTagInside) return

        // Flag the entire Popup call expression.
        reportFinding(expression, calleeText)
    }

    private fun contentLambda(call: KtCallExpression): KtLambdaExpression? {
        // The content lambda is the last positional argument for all four Popup containers.
        val lastPositional = call.valueArguments.lastOrNull { it.getArgumentExpression() != null }
        return lastPositional?.getArgumentExpression() as? KtLambdaExpression
    }

    /**
     * True when the call expression itself is a dot-qualified chain ending in
     * `mapTestTagsAsResourceIds()` — the AlertDialog pattern where the modifier
     * on the AlertDialog applies the mapping.
     *
     * ```kotlin
     * AlertDialog(
     *     modifier = Modifier
     *         .testTag(TestTags.RESULT_DIALOG)
     *         .mapTestTagsAsResourceIds(),
     * )
     * ```
     */
    private fun hasMapResourceIdOnCall(call: KtCallExpression): Boolean {
        // Direct child dot-qualified: the `modifier = ...` argument's expression is a
        // chain ending in mapTestTagsAsResourceIds.
        for (arg in call.valueArguments) {
            val argExpr = arg.getArgumentExpression() ?: continue
            if (isMapResourceIdChain(argExpr)) return true
        }
        return false
    }

    /**
     * True when a `modifier = Modifier.mapTestTagsAsResourceIds()` named argument is present.
     * This handles the ModalBottomSheet direct modifier pattern:
     *
     * ```kotlin
     * ModalBottomSheet(
     *     modifier = Modifier.mapTestTagsAsResourceIds(),
     *     ...
     * )
     * ```
     */
    private fun hasMapResourceIdOnModifierParam(call: KtCallExpression): Boolean {
        for (arg in call.valueArguments) {
            val name = arg.getArgumentName()?.text
            if (name == "modifier") {
                val expr = arg.getArgumentExpression() ?: continue
                if (isMapResourceIdChain(expr)) return true
            }
        }
        return false
    }

    private fun isMapResourceIdChain(expr: org.jetbrains.kotlin.psi.KtExpression): Boolean {
        var current: org.jetbrains.kotlin.psi.KtExpression? = expr
        while (current != null) {
            when (current) {
                is KtDotQualifiedExpression -> {
                    val selector = current.selectorExpression
                    // mapTestTagsAsResourceIds() is a function call, so the
                    // selector is a KtCallExpression whose callee is the name
                    // reference — not a bare KtNameReferenceExpression.
                    val name = when (selector) {
                        is KtNameReferenceExpression -> selector.text
                        is org.jetbrains.kotlin.psi.KtCallExpression ->
                            (selector.calleeExpression as? KtNameReferenceExpression)?.text
                        else -> null
                    }
                    if (name == "mapTestTagsAsResourceIds") return true
                    // Keep walking the receiver chain
                    current = current.receiverExpression
                }

                else -> return false
            }
        }
        return false
    }

    private fun isPreviewOrTestContext(element: org.jetbrains.kotlin.psi.KtElement): Boolean {
        val file = element.containingKtFile
        val path = file.virtualFile?.path ?: ""
        val fileName = file.name
        if (fileName.contains("preview", ignoreCase = true) ||
            path.contains("/preview/", ignoreCase = true)
        ) {
            return true
        }
        if (TEST_SOURCE_SETS.any { path.contains("/$it/") || path.endsWith("/$it") } ||
            path.contains("/test/")
        ) {
            return true
        }
        // Walk out to the nearest named declaration and exempt if it's a @Preview function.
        var current: org.jetbrains.kotlin.psi.KtElement? = element
        while (current != null) {
            if (current is org.jetbrains.kotlin.psi.KtNamedFunction) {
                val hasPreview = current.annotationEntries.any {
                    it.shortName?.asString() == "Preview"
                }
                if (hasPreview) return true
            }
            current = current.parent as? org.jetbrains.kotlin.psi.KtElement
        }
        return false
    }

    private fun reportFinding(expression: KtCallExpression, calleeName: String) {
        report(
            Finding(
                entity = Entity.from(expression),
                message = "ModalBottomSheet / Popup / AlertDialog / DropdownMenu `$calleeName` " +
                    "receives a testTag inside its content lambda but does not apply " +
                    "`mapTestTagsAsResourceIds()` to the Popup's root modifier. " +
                    "The testTag will not propagate through the Popup window boundary: " +
                    "Maestro `id:` selectors targeting this element will silently fail. " +
                    "Fix: add `.mapTestTagsAsResourceIds()` to the $calleeName call's " +
                    "own `modifier` parameter, or to the Modifier chain on the root element.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    companion object {
        /**
         * Popup-owning composables whose content lambda is isolated in a separate
         * window context on Android. Each requires `mapTestTagsAsResourceIds()` on
         * the root modifier for inner testTags to reach the Android resource-id layer.
         */
        private val POPUP_CONTAINERS = setOf(
            "ModalBottomSheet",
            "Popup",
            "AlertDialog",
            "DropdownMenu",
        )

        private val TEST_SOURCE_SETS = listOf(
            "commonTest",
            "jvmTest",
            "androidTest",
            "iosTest",
            "jsTest",
        )
    }
}

/**
 * Registers [NoPopupWithoutResourceIdMapping] in the `popup-test-tag` rule set.
 */
class NoPopupWithoutResourceIdMappingProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("popup-test-tag")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoPopupWithoutResourceIdMapping") to { cfg: Config ->
                NoPopupWithoutResourceIdMapping(cfg)
            },
        ),
    )
}
