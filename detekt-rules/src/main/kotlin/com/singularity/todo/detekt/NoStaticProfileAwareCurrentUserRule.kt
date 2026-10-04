package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Flags any reference to `ProfileAwareCurrentUser` through its static companion
 * (`ProfileAwareCurrentUser.scopedUserId`, `.current`, `.instance`, `.setInstance`, or
 * `ProfileAwareCurrentUser.Companion.*`).
 *
 * After PR12b, `ProfileAwareCurrentUser` is a pure DI class — all access must go through
 * constructor injection. The static companion was removed, so a static reference is
 * either dead code or a silent cross-profile read.
 *
 * Legitimate (not flagged):
 * - Direct instantiation: `ProfileAwareCurrentUser(get(), get(), scope)`
 * - Type references in declarations
 * - Access to any other class
 *
 * ## This rule could not fire until 2026-10-05
 *
 * The original implementation cast the receiver to `KtNameReferenceExpression` and then
 * compared its text against the fully-qualified class name:
 *
 * ```
 * val receiver = expression.receiverExpression as? KtNameReferenceExpression ?: return
 * if (receiver.getReferencedName() != "com.singularity.todo...ProfileAwareCurrentUser") return
 * ```
 *
 * A `KtNameReferenceExpression` is a bare identifier, so its text never contains dots and
 * can never equal a dotted FQN. And the two spellings that *would* match a dotted name —
 * the fully-qualified form and the `.Companion` form — both have a `KtDotQualifiedExpression`
 * receiver, so they returned even earlier. All three ways of writing the forbidden access
 * were excluded by the rule's own guards. Measured, not inferred: see the probe output
 * recorded in `RuleFiresSmokeTest`.
 *
 * The fix matches on the receiver's *written text*, normalised, so all three spellings
 * are covered.
 *
 * @see NoStaticProfileAwareCurrentUserProvider for how this rule is registered.
 * @see 2026-10-05-no-direct-dispatchers-rule-was-a-no-op for the shared failure mode.
 */
class NoStaticProfileAwareCurrentUserRule(config: Config) : Rule(config, "", null) {

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (!NoStaticProfileAwareCurrentUserPolicy.isForbiddenAccess(expression)) return
        report(
            Finding(
                entity = Entity.from(expression),
                message = NoStaticProfileAwareCurrentUserPolicy.messageFor(expression),
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }
}

/**
 * The decision [NoStaticProfileAwareCurrentUserRule] makes, as a pure function of the
 * expression's written text.
 *
 * Split out so the branches are testable without constructing PSI: the rule's whole
 * surface is "what does the receiver text look like", and a rule whose only test surface
 * is PSI is a rule whose no-op failure mode is invisible.
 */
internal object NoStaticProfileAwareCurrentUserPolicy {

    const val SIMPLE_NAME = "ProfileAwareCurrentUser"

    private val COMPANION_MEMBERS = setOf("scopedUserId", "current", "instance", "setInstance")

    /** Normalises a receiver expression to the text a reader would recognise. */
    fun normalise(receiverText: String): String =
        receiverText.filterNot { it.isWhitespace() }.removeSuffix(".Companion")

    /**
     * True when the receiver names [SIMPLE_NAME], either bare or fully qualified.
     *
     * Matching the simple name is what the rule always intended; requiring the FQN is
     * what made it unsatisfiable. Import resolution is not available in a detekt rule, so
     * a same-named class in another package would also match — an acceptable trade for a
     * rule whose entire purpose is to catch a banned access pattern.
     */
    fun isTarget(receiverText: String): Boolean {
        val bare = normalise(receiverText)
        return bare == SIMPLE_NAME || bare.endsWith(".$SIMPLE_NAME")
    }

    fun isForbiddenAccess(expression: KtDotQualifiedExpression): Boolean {
        val receiver = expression.receiverExpression ?: return false
        if (!isTarget(receiver.text)) return false
        val selector = expression.selectorExpression as? KtNameReferenceExpression ?: return false
        return selector.getReferencedName() in COMPANION_MEMBERS
    }

    fun messageFor(expression: KtDotQualifiedExpression): String {
        val member = (expression.selectorExpression as? KtNameReferenceExpression)
            ?.getReferencedName() ?: "?"
        return "ProfileAwareCurrentUser companion accessor '$member' is forbidden. " +
            "Access ProfileAwareCurrentUser via constructor injection. " +
            "The static companion was removed in PR12b."
    }
}

/**
 * Registers [NoStaticProfileAwareCurrentUserRule] in the
 * `no-static-profile-aware-current-user` rule set.
 */
class NoStaticProfileAwareCurrentUserProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-static-profile-aware-current-user")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoStaticProfileAwareCurrentUser") to { cfg: Config ->
                NoStaticProfileAwareCurrentUserRule(cfg)
            },
        ),
    )
}
