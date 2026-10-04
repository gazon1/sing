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
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * Flags an `AppError` construction that does not pass an explicit `code`.
 *
 * ## What the default code costs
 *
 * Every `AppError` subtype carries a default code — `error.validation`, `error.not_found`,
 * and so on — so `AppError.Validation("Title cannot be blank")` compiles and looks correct.
 * The code is what a crash reporter groups by. Fourteen different validation failures in this
 * codebase shipped with the default, which means the dashboard held one group called
 * `error.validation` containing all fourteen, and no way to tell "the user typed a blank task
 * title" from "the tag colour was transparent" except by reading fourteen stack traces.
 *
 * The prose message distinguishes them. The message is not transmitted off-device, is not
 * stable across a rewrite, and — for a group named after it — is exactly what a translator or
 * a copy-edit would break.
 *
 * So: a named error type that does not name *which* error it is. The default is a placeholder
 * for a value the author has to supply, and the only check that catches forgetting it is a
 * check.
 *
 * ## What is not flagged
 *
 * - `AppError` itself (the declaration of the defaults)
 * - Any subtype constructor, including ones that pass `cause` but no `code` — same defect,
 *   and fixing it is the point
 * - `runCatchingResult`, which builds `AppError.Unknown` for *unclassified* throwables; there
 *   is no per-call-site identity to give it, and inventing one would be the same defect
 *   wearing a different hat. `Unknown` is the honest answer for "we do not know what this is".
 *
 * @see AppErrorCodePolicy for the decision, split out so it is testable without PSI.
 */
class AppErrorCodeRule(config: Config) : Rule(config, "", null) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!AppErrorCodePolicy.isAppErrorConstruction(expression)) return
        if (AppErrorCodePolicy.hasCodeArgument(expression.valueArgumentList)) return

        report(
            Finding(
                entity = Entity.from(expression),
                message = AppErrorCodePolicy.message(expression),
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }
}

/**
 * The decision [AppErrorCodeRule] makes, over PSI.
 *
 * Split out so each branch is testable without a detekt harness — a rule whose only test
 * surface is "run the whole engine" is a rule whose no-op failure mode is invisible.
 */
internal object AppErrorCodePolicy {

    private const val QUALIFIED = "com.singularity.todo.core.error.AppError"
    private const val CODE_ARG = "code"

    /**
     * True when this call constructs one of the `AppError` subtypes.
     *
     * Both spellings count: `AppError.Validation(…)` — which is what a file that imports the
     * type actually contains, and what all 29 real call sites contain — and the fully-qualified
     * `com.singularity.todo.core.error.AppError.Validation(…)`. Matching only the qualified
     * form is the mistake this rule's first draft made: it compiled, passed every test whose
     * fixture happened to be written qualified, and matched nothing in the repository. The
     * import is not in the source text a rule sees.
     *
     * Import resolution is not available to a detekt rule, so a same-named class in another
     * package would also match — the same trade `NoStaticProfileAwareCurrentUserPolicy` makes
     * deliberately, and a defensible one for a rule that exists to catch one shape.
     */
    fun isAppErrorConstruction(expression: KtCallExpression): Boolean {
        val qualified = expression.parent as? KtDotQualifiedExpression ?: return false
        val receiver = qualified.receiverExpression?.text ?: return false
        if (receiver != SIMPLE_NAME && receiver != QUALIFIED && !receiver.endsWith(".$QUALIFIED")) return false

        val subtype = expression.calleeExpression?.text ?: return false
        return subtype in SUBTYPES
    }

    private const val SIMPLE_NAME = "AppError"

    private val SUBTYPES = setOf("Validation", "NotFound", "Unauthorized", "Persistence", "Network", "Unknown")

    /** True when the call passes a **named** `code` argument. A positional one cannot exist. */
    fun hasCodeArgument(arguments: KtValueArgumentList?): Boolean =
        arguments?.arguments?.any { it.getArgumentName()?.asName?.identifier == CODE_ARG } == true

    fun message(expression: KtCallExpression): String {
        val subtype = expression.calleeExpression?.text ?: "?"
        return "$subtype is constructed without an explicit `code`, so it inherits the " +
            "subtype-wide default (error.validation, error.not_found, …) and every " +
            "$subtype in the app lands in one dashboard group. The message is what " +
            "distinguishes them and it is not transmitted, not stable, and not machine-shaped. " +
            "Pass `code = \"<module>.<what_failed>\"`."
    }

    /** Exposed for the test that documents the accepted set. */
    fun subtypes(): Set<String> = SUBTYPES
}

/**
 * Registers [AppErrorCodeRule] in the `app-error-code` rule set.
 */
class AppErrorCodeProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("app-error-code")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("AppErrorCode") to { cfg: Config -> AppErrorCodeRule(cfg) },
        ),
    )
}
