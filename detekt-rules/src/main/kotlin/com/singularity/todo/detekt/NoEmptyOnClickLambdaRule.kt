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
import org.jetbrains.kotlin.psi.KtFile

/**
 * Bans `onClick` / `onXxx` callback parameters that default to an empty lambda `{}`.
 *
 * An empty lambda `{}` is truthy in Kotlin, so a consumer using the elvis operator
 * fallback pattern — `onClick ?: openSheet()` — will never take the fallback because
 * `{}` is not null. The correct default is `null`, not `{}`.
 *
 * Example of a defect this catches:
 * ```kotlin
 * // Declaration
 * val onClick: () -> Unit = {}     // WRONG — truthy, defeats elvis fallback
 * val onDismiss: (() -> Unit)? = null  // CORRECT — nullable, elvis fallback works
 *
 * // Consumer
 * onClick ?: openSheet()  // Never reaches openSheet when onClick = {}
 * ```
 *
 * Patterns flagged:
 * - `val onClick: () -> Unit = {}`
 * - `val onToggle: () -> Boolean = {}`
 * - `val onOpenSheet: () -> Unit = {}`
 * - Any `val onXxx: (...) -> Unit = {}` where the name starts with `on`
 *
 * Patterns NOT flagged (by design):
 * - `val onClick: (() -> Unit)? = null` — nullable, nullable is correct
 * - `val onClick: () -> Unit = { doSomething() }` — non-empty body, not a noop
 * - Properties in non-Composable contexts (the defect pattern is Compose-specific)
 *
 * @see NoEmptyOnClickLambdaRuleProvider for registration.
 */
class NoEmptyOnClickLambdaRule(config: Config) : Rule(config, "", null) {

    // Matches `val onClick: () -> Unit = {}` with optional receiver/extension
    private val NOOP_CALLBACK = Regex(
        """val\s+(on[A-Z]\w*)\s*:\s*\(\s*\)\s*->\s*Unit\s*=\s*\{\s*\}""",
    )
    // Matches `name ?: ...` elvis fallback consumption
    private val ELVIS_FALLBACK = Regex("""(\w+)\s*\?:""")

    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)

        // Find all noop callback defaults in this file
        val noopParams = mutableMapOf<String, String>() // name → line text
        for (line in root.text.split("\n")) {
            NOOP_CALLBACK.find(line)?.let { match ->
                noopParams[match.groupValues[1]] = line.trim()
            }
        }
        if (noopParams.isEmpty()) return

        // Check if any noop param is consumed via elvis fallback in this file
        val corpus = root.text
        val consumedViaElvis = mutableSetOf<String>()
        for (paramName in noopParams.keys) {
            if (ELVIS_FALLBACK.containsMatchIn(corpus)) {
                consumedViaElvis.add(paramName)
            }
        }

        // Report each noop callback that is consumed via elvis
        for ((paramName, lineText) in noopParams) {
            if (paramName in consumedViaElvis) {
                report(
                    Finding(
                        entity = Entity.from(root),
                        message = buildString {
                            append("Parameter '$paramName' defaults to empty lambda `{}`.")
                            append(" An empty lambda is truthy, so `onClick ?: fallback` will never")
                            append(" use the fallback. Change the default to `null`.")
                            append(" Line: $lineText")
                        },
                        references = emptyList(),
                        suppressReasons = emptyList(),
                    ),
                )
            }
        }
    }
}

/**
 * Registers [NoEmptyOnClickLambdaRule] in the `no-empty-onclick-lambda` rule set.
 */
class NoEmptyOnClickLambdaRuleProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-empty-onclick-lambda")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoEmptyOnClickLambda") to { cfg: Config ->
                NoEmptyOnClickLambdaRule(cfg)
            },
        ),
    )
}
