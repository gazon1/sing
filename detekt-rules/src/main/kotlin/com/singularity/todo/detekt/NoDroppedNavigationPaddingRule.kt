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
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * The shell's navigation-suite content lambda must spend the padding the suite hands it.
 *
 * `NavigationSuiteScaffold` draws the bottom navigation bar around its content and tells
 * the content how much room the bar took through the lambda's `PaddingValues`. A lambda
 * that declares no parameter drops that value, and the last row of every screen is then
 * drawn behind the navigation bar. That is the shape the Android shell had before #1699,
 * and the reason the calendar's last week row, Settings' "Font Size" card and a task
 * card's last row were all cut in half.
 *
 * The padding counts as spent when it reaches the content, by either of the two shapes
 * the shell may use: folded into an inner `Scaffold`'s `contentWindowInsets`, or applied
 * to what the lambda draws (`Modifier.padding(navigationPadding)`). A lambda that declares
 * the parameter and never reads it is reported exactly like one that never declared it,
 * because the padding is just as lost.
 *
 * Three shapes the rule used to skip in silence, all of which would have let #1699 back
 * in through a spelling rather than through a mistake:
 *
 *  - **the content lambda written positionally** (`NavigationSuiteScaffold(items, …, { p ->
 *    … })`) is read as the last unnamed argument, so it is analysed like a trailing lambda;
 *  - **a local alias** (`val p = navigationPadding`, then `padding(p)`) is followed one
 *    level of `val`/`var` indirection deep, transitively, so spending the alias counts as
 *    spending the padding;
 *  - **a call whose content lambda cannot be seen at all** (a reference passed by name, or
 *    a call with no content lambda) is *reported* rather than passed over. A guard that
 *    goes quiet for a shape it cannot read is a guard that says "safe" about code it never
 *    looked at, which is the failure mode this rule exists to prevent. The message names
 *    the two spellings that make the lambda visible.
 *
 * The suite's room is what is in scope: a bottom bar on a phone, a rail on a wide window.
 * The status bar is the shell's own `TopAppBar`'s to consume, so the rule does not demand
 * a top inset — doing so would pad the content twice, which is #1566, not this regression.
 *
 * A helper unit test cannot see this: `shellContentWindowInsets` can be perfect while the
 * shell never calls it. The call site is the defect, so the call site is what is checked.
 *
 * @see NoDroppedNavigationPaddingProvider for registration.
 */
class NoDroppedNavigationPaddingRule(config: Config) : Rule(config, "", null) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeText()
        if (callee == null || callee !in expression.navigationSuiteScaffoldNames()) return

        val content = expression.contentLambda()
        if (content == null) {
            reportDropped(expression, INVISIBLE_CONTENT_MESSAGE)
            return
        }
        val paddingParameter = content.functionLiteral.valueParameters.firstOrNull()
        if (paddingParameter == null) {
            reportDropped(expression, NO_PARAMETER_MESSAGE)
            return
        }

        val paddingName = paddingParameter.name
        if (paddingName == null) {
            // A destructuring parameter has no single name to read, so the padding cannot
            // reach the content by either of the shapes this rule anchors on. Reported, not
            // skipped: a parameter the rule cannot follow is the same lost padding as one
            // that was never declared, and `PaddingValues` has no componentN to destructure
            // in the first place.
            reportDropped(expression, NO_PARAMETER_MESSAGE)
            return
        }
        if (!content.spends(content.paddingNames(paddingName))) {
            reportDropped(expression, UNUSED_PARAMETER_MESSAGE.format(paddingName))
        }
    }

    private fun reportDropped(expression: KtCallExpression, message: String) {
        report(
            Finding(
                entity = Entity.from(expression),
                message = message,
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    /**
     * The content lambda of the call.
     *
     * Three spellings, in the order the compiler reads them: a named `content = { … }`, a
     * trailing lambda, and a lambda written positionally inside the parentheses. The named
     * form is preferred when a call carries more than one, because that is the one whose
     * contract the rule is about. A positional lambda is only the *last* unnamed argument —
     * `navigationSuiteItems` is at the other end of the signature — and only a lambda, so a
     * call such as `NavigationSuiteScaffold(items = …, content = appContent)` is left to
     * [INVISIBLE_CONTENT_MESSAGE] instead of being misread.
     */
    private fun KtCallExpression.contentLambda(): KtLambdaExpression? {
        val named =
            valueArguments
                .firstOrNull { it.getArgumentName()?.text == CONTENT }
                ?.getArgumentExpression()
        if (named != null) return named as? KtLambdaExpression

        lambdaArguments.lastOrNull()?.getArgumentExpression()?.let {
            return it as? KtLambdaExpression
        }

        val positional = valueArguments.lastOrNull()
        return if (positional != null && positional.getArgumentName() == null) {
            positional.getArgumentExpression() as? KtLambdaExpression
        } else {
            null
        }
    }

    /**
     * The padding name plus every local alias of it declared inside the lambda.
     *
     * `val p = navigationPadding` puts the same `PaddingValues` behind another name, and
     * `padding(p)` spends it just as surely as `padding(navigationPadding)` does. The
     * closure is computed to a fixpoint over the lambda's own `val`/`var` declarations, so
     * a chain of them is followed too; it stops at the lambda's boundary, because a name
     * that escapes the lambda is not a name this rule can reason about without resolving
     * the whole call graph.
     */
    private fun KtLambdaExpression.paddingNames(paddingName: String): Set<String> {
        val properties = mutableListOf<KtProperty>()
        accept(
            object : KtTreeVisitorVoid() {
                override fun visitProperty(property: KtProperty) {
                    properties += property
                    super.visitProperty(property)
                }
            },
        )

        val names = mutableSetOf(paddingName)
        var changed = true
        while (changed) {
            changed = false
            properties.forEach { property ->
                val name = property.name ?: return@forEach
                if (name in names) return@forEach
                val initializer = property.initializer?.text ?: return@forEach
                if (names.any { initializer.mentions(it) }) {
                    names += name
                    changed = true
                }
            }
        }
        return names
    }

    /** Whether the lambda reads a name that spends the padding on the content. */
    private fun KtLambdaExpression.spends(paddingNames: Set<String>): Boolean {
        var spent = false
        accept(
            object : KtTreeVisitorVoid() {
                override fun visitCallExpression(call: KtCallExpression) {
                    if (call.spendsPadding(paddingNames)) spent = true
                    super.visitCallExpression(call)
                }
            },
        )
        return spent
    }

    /**
     * Whether this call spends the padding: it either feeds the shell's insets, or pads the
     * content with it.
     *
     * The two shapes are the anchor the rule reads. A call that merely mentions the name —
     * an argument somewhere else, a log line — is not the content being padded, so the
     * match is limited to the two arguments that actually decide the layout.
     */
    private fun KtCallExpression.spendsPadding(paddingNames: Set<String>): Boolean {
        val insets =
            valueArguments
                .firstOrNull { it.getArgumentName()?.text == CONTENT_WINDOW_INSETS }
                ?.getArgumentExpression()
        if (insets != null && paddingNames.any { insets.mentions(it) }) return true

        return calleeText() == PADDING &&
            valueArguments.any { argument ->
                val expression = argument.getArgumentExpression() ?: return@any false
                paddingNames.any { expression.mentions(it) }
            }
    }

    private fun KtCallExpression.calleeText(): String? =
        when (val callee = calleeExpression) {
            // `NavigationSuiteScaffold(…)`
            is KtNameReferenceExpression -> callee.getReferencedName()
            // `androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold(…)`.
            // Without this arm the callee does not resolve to a name reference at all and the
            // call is skipped in silence — the guard would be off for the one call site it
            // exists to protect, and #2192's regression would come back with no signal.
            is KtDotQualifiedExpression ->
                (callee.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
            else -> null
        }

    /**
     * The names this file uses for `NavigationSuiteScaffold`.
     *
     * An aliased import (`import …NavigationSuiteScaffold as ShellNavSuite`) renames the
     * callee, so matching only the plain text would skip the call. The alias is read from
     * the file's imports; anything the rule cannot resolve is left alone rather than
     * guessed at.
     */
    private fun KtCallExpression.navigationSuiteScaffoldNames(): Set<String> {
        val names = mutableSetOf(NAVIGATION_SUITE_SCAFFOLD)
        containingKtFile.importDirectives.forEach { directive ->
            val alias = directive.aliasName ?: return@forEach
            val imported = directive.importedFqName?.asString() ?: return@forEach
            if (imported == NAVIGATION_SUITE_SCAFFOLD_FQ) names += alias
        }
        return names
    }

    /** A word match, so `navigationPadding` is not found inside a longer identifier. */
    private fun String.mentions(identifier: String): Boolean =
        Regex("""\b${Regex.escape(identifier)}\b""").containsMatchIn(this)

    private companion object {
        const val NAVIGATION_SUITE_SCAFFOLD = "NavigationSuiteScaffold"

        /** The imported symbol an aliased call renames; see [navigationSuiteScaffoldNames]. */
        const val NAVIGATION_SUITE_SCAFFOLD_FQ =
            "androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold"
        const val CONTENT = "content"
        const val CONTENT_WINDOW_INSETS = "contentWindowInsets"
        const val PADDING = "padding"

        const val NO_PARAMETER_MESSAGE =
            "The NavigationSuiteScaffold content lambda declares no padding parameter, so the " +
                "room the navigation suite took is dropped and the content is drawn under the " +
                "bar or rail. Take the PaddingValues and spend them on the content."

        const val UNUSED_PARAMETER_MESSAGE =
            "The NavigationSuiteScaffold content lambda takes `%s` and never spends it: the " +
                "room the navigation suite took is dropped and the content is drawn under the " +
                "bar or rail. Fold it into the inner Scaffold's contentWindowInsets, or pad " +
                "the content with it."

        const val INVISIBLE_CONTENT_MESSAGE =
            "The NavigationSuiteScaffold call's content lambda could not be read, so whether " +
                "the navigation suite's PaddingValues reach the content cannot be checked. " +
                "Write it as a trailing lambda or as `content = { … }` so the guard can see it. " +
                "This is reported rather than skipped: an unreadable call site is not a safe one."
    }
}

/**
 * Registers [NoDroppedNavigationPaddingRule] in the `no-dropped-navigation-padding` rule set.
 */
class NoDroppedNavigationPaddingProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-dropped-navigation-padding")

    override fun instance(): RuleSet =
        RuleSet(
            ruleSetId,
            mapOf<RuleName, (Config) -> Rule>(
                RuleName("NoDroppedNavigationPadding") to { cfg: Config ->
                    NoDroppedNavigationPaddingRule(cfg)
                },
            ),
        )
}
