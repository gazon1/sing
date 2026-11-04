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
 * The parameter is matched by name at those two anchors; a local alias
 * (`val p = navigationPadding`) is not followed. Writing the padding straight into the
 * insets or the modifier is what the rule asks for anyway, and a rule that followed every
 * alias would be a dataflow analysis pretending to be a lint rule.
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
        if (expression.calleeText() != NAVIGATION_SUITE_SCAFFOLD) return

        val content = expression.contentLambda() ?: return
        val paddingParameter = content.functionLiteral.valueParameters.firstOrNull()
        if (paddingParameter == null) {
            reportDropped(expression, NO_PARAMETER_MESSAGE)
            return
        }

        val paddingName = paddingParameter.name ?: return
        if (!content.spends(paddingName)) {
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
     * A trailing lambda is a [org.jetbrains.kotlin.psi.KtLambdaArgument], while a named
     * `content = { … }` is a value argument, so both spellings are read. The named form is
     * preferred when a call carries both, because that is the one whose contract the rule
     * is about.
     */
    private fun KtCallExpression.contentLambda(): KtLambdaExpression? {
        val named =
            valueArguments
                .firstOrNull { it.getArgumentName()?.text == CONTENT }
                ?.getArgumentExpression()
        val trailing = lambdaArguments.lastOrNull()?.getArgumentExpression()
        val lastValue = valueArguments.lastOrNull()?.getArgumentExpression()
        return (named ?: trailing ?: lastValue) as? KtLambdaExpression
    }

    /** Whether the lambda reads the padding name anywhere that spends it on the content. */
    private fun KtLambdaExpression.spends(paddingName: String): Boolean {
        var spent = false
        accept(
            object : KtTreeVisitorVoid() {
                override fun visitCallExpression(call: KtCallExpression) {
                    if (call.spendsPadding(paddingName)) spent = true
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
    private fun KtCallExpression.spendsPadding(paddingName: String): Boolean {
        val insets =
            valueArguments
                .firstOrNull { it.getArgumentName()?.text == CONTENT_WINDOW_INSETS }
                ?.getArgumentExpression()
        if (insets?.mentions(paddingName) == true) return true

        return calleeText() == PADDING &&
            valueArguments.any { it.getArgumentExpression()?.mentions(paddingName) == true }
    }

    private fun KtCallExpression.calleeText(): String? =
        (calleeExpression as? KtNameReferenceExpression)?.text

    /** A word match, so `navigationPadding` is not found inside a longer identifier. */
    private fun String.mentions(identifier: String): Boolean =
        Regex("""\b${Regex.escape(identifier)}\b""").containsMatchIn(this)

    private companion object {
        const val NAVIGATION_SUITE_SCAFFOLD = "NavigationSuiteScaffold"
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
