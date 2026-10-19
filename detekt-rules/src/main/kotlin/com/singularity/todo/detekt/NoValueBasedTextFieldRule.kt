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
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Bans the value-based text field API — `value:` plus `onValueChange:` — on Compose text fields.
 *
 * A state-based field (`TextFieldState`) owns its own text. A value-based field does not: the
 * caller owns the string, and every keystroke is a round trip through the caller. That round trip
 * is where the defects live — the field discards text injected from outside (Maestro's
 * `inputText`, a paste, an autofill) when the caller's copy is replaced rather than merged, and
 * the filtering that a field needs (digits only, a maximum length) has to be re-implemented by
 * every caller in `onValueChange`.
 *
 * The migration to the state-based API covered every text field in the app. This rule keeps it
 * that way: the value-based shape is one line away from being written again, and the next
 * feature that writes it re-introduces the same defects.
 *
 * ## What is reported
 *
 * A call to `TextField`, `OutlinedTextField`, `BasicTextField`, `SecureTextField`,
 * `OutlinedSecureTextField` or `BasicSecureTextField` that passes a `value:` or an
 * `onValueChange:` argument.
 *
 * ## Allowed patterns (not flagged)
 *
 * - The state-based overloads — they have no `value`/`onValueChange` parameter at all.
 * - Test sources: a test that pins the shape of a component is not shipping a screen.
 *
 * @see NoValueBasedTextFieldProvider for registration.
 */
class NoValueBasedTextFieldRule(config: Config) : Rule(config, "", null) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (isExemptFile(expression)) return

        // A qualified call (`UiNode.TextField(...)` — the GenUI data class) is not a composable
        // call, so only a bare name counts.
        val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.text !in TEXT_FIELD_FUNCTIONS) return

        val offenders = expression.valueArguments.filter { argument ->
            val name = argument.getArgumentName()?.text
            name == "value" || name == "onValueChange"
        }
        if (offenders.isEmpty()) return

        val parameters = offenders.joinToString(", ") { it.getArgumentName()?.text ?: "?" }
        report(
            Finding(
                entity = Entity.from(expression),
                message = "`${callee.text}` uses the value-based text field API ($parameters). " +
                    "Use the state-based API instead: `state = rememberTextFieldState()` for a " +
                    "local field, or `rememberBridgedTextFieldState(...)` when a ViewModel owns " +
                    "the value. The value-based shape discards text injected from outside the " +
                    "field and pushes the filtering a field needs onto every caller.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    /**
     * True when this call is in a file the rule does not police.
     *
     * Test sources are excluded because a test that pins the shape of a component is not
     * shipping a screen. Nothing else is excluded: the GenUI renderer used to be exempt by
     * path, but it was migrated to the state-based API, so the exemption is gone.
     */
    private fun isExemptFile(expression: KtCallExpression): Boolean {
        val file = expression.containingKtFile
        val path = file.virtualFile?.path ?: ""
        return NoValueBasedTextFieldPolicy.isExemptPath(path)
    }
}

/**
 * The file-path half of [NoValueBasedTextFieldRule]'s exemptions, as pure functions so they can
 * be tested without a virtual file system.
 */
internal object NoValueBasedTextFieldPolicy {

    /** Same list `NoEmptyOnClickLambdaPolicy` uses, kept in step deliberately. */
    private val TEST_SOURCE_SETS = listOf("commonTest", "jvmTest", "androidTest", "iosTest", "jsTest")

    /**
     * True when the file is a test source.
     */
    fun isTestPath(filePath: String): Boolean {
        val path = filePath.replace('\\', '/')
        return TEST_SOURCE_SETS.any { path.contains("/$it/") || path.endsWith("/$it") } ||
            path.contains("/test/")
    }

    /**
     * True when the rule does not police the file.
     *
     * Test sources only. The GenUI renderer used to be exempt by path — it was the one
     * documented exception to the state-based migration — but it was migrated to the
     * state-based API, so the exemption is gone.
     */
    fun isExemptPath(filePath: String): Boolean = isTestPath(filePath)
}

/**
 * Registers [NoValueBasedTextFieldRule] in the `no-value-based-text-field` rule set.
 */
class NoValueBasedTextFieldProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-value-based-text-field")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoValueBasedTextField") to { cfg: Config -> NoValueBasedTextFieldRule(cfg) },
        ),
    )
}

/**
 * The composables the rule polices. Every one of them has a state-based overload that takes a
 * `TextFieldState` instead of a `value`/`onValueChange` pair.
 */
internal val TEXT_FIELD_FUNCTIONS = setOf(
    "TextField",
    "OutlinedTextField",
    "BasicTextField",
    "SecureTextField",
    "OutlinedSecureTextField",
    "BasicSecureTextField",
)
