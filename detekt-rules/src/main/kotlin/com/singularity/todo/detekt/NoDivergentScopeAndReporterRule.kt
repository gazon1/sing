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
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * A component whose two failure paths are chosen independently can send them to two different
 * places — and nothing notices.
 *
 * ## The invariant
 *
 * A ViewModel reports the failures it handles through its own `crashReporter`. It also owns a
 * coroutine scope, and an unhandled failure in that scope is reported by the scope's own failure
 * policy. `MviViewModel` derives the default scope from the reporter, so the common case has one
 * destination. Supply the scope instead of accepting the derived one, and the two become separate
 * arguments that nothing correlates.
 *
 * Both currently resolve the same singleton, so **no failure is going anywhere unexpected today**.
 * That is the reason this is a rule rather than a bug report: the two are equal by how the
 * binding happens to be written, not by anything that checks.
 *
 * ## Why the other two rules in this set cannot see it
 *
 * `NoUnreportedFailurePath` asks whether a class has a reporter. `NoUnwiredReporterInBinding`
 * asks whether a binding passes one. Neither asks whether the scope on the adjacent line points
 * at the *same* one — and comparing two `get()` calls for identity is a type-resolution
 * question, which a detekt rule does not have.
 *
 * So the rule does not compare the two. It removes the possibility: if the only way to obtain a
 * scope is to derive it from the reporter, there is no second argument to correlate and no
 * comparison to be unable to make. What is left to check is whether that is still true, which is
 * the second finding below.
 *
 * ## The two findings
 *
 * 1. **The constructor.** A ViewModel declaring both `crashReporter` and `scope` must give
 *    `scope` a default that references `crashReporter`. A `scope` with no default forces every
 *    binding to supply one, which is the situation this rule exists to end.
 * 2. **The binding.** A `viewModel` binding must not pass *both* a named `crashReporter` and a
 *    `scope` argument. This is separate from the constructor finding on purpose: a class can
 *    have the correct default and a binding can still override it. `SettingsViewModel` did
 *    exactly that — its constructor derived the scope, and its binding replaced the derivation
 *    with a graph-supplied one, so a constructor-only check would have passed it forever.
 *
 * @see NoUnreportedFailurePathPolicy for the shared ViewModel name list.
 * @see NoUnwiredReporterInBindingRule for the rule this is the sibling of.
 */
class NoDivergentScopeAndReporterRule(config: Config) : Rule(config, "", null) {

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (!NoUnreportedFailurePathPolicy.isViewModelName(klass.name)) return

        val scope = NoDivergentScopeAndReporterPolicy.scopeParameter(klass) ?: return
        // No reporter beside the scope means there is nothing to diverge from — that component
        // reports through the scope's own handler and has no second destination.
        if (!NoDivergentScopeAndReporterPolicy.hasReporterParameter(klass)) return
        if (NoDivergentScopeAndReporterPolicy.defaultReferencesReporter(scope)) return

        report(
            Finding(
                entity = Entity.from(scope),
                message = NoDivergentScopeAndReporterPolicy.constructorMessage(klass.name),
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!NoUnwiredReporterInBindingPolicy.isViewModelBinding(expression)) return

        val constructed = NoUnwiredReporterInBindingPolicy.constructedIn(expression) ?: return
        val type = constructed.calleeExpression?.text ?: return
        if (!NoUnreportedFailurePathPolicy.isViewModelName(type)) return

        val arguments = constructed.valueArgumentList ?: return
        if (!NoUnwiredReporterInBindingPolicy.hasReporterArgument(arguments)) return
        if (!NoDivergentScopeAndReporterPolicy.hasScopeArgument(arguments)) return

        report(
            Finding(
                entity = Entity.from(expression),
                message = NoDivergentScopeAndReporterPolicy.bindingMessage(type),
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }
}

/**
 * The decisions [NoDivergentScopeAndReporterRule] makes, over PSI.
 *
 * Split out so every branch has a test that does not need a detekt harness.
 */
internal object NoDivergentScopeAndReporterPolicy {

    /** The primary constructor's parameter named `scope`, or null when there is none. */
    fun scopeParameter(klass: KtClass): KtParameter? =
        klass.primaryConstructorParameters.firstOrNull { it.name == "scope" }

    fun hasReporterParameter(klass: KtClass): Boolean =
        klass.primaryConstructorParameters.any { it.name == "crashReporter" }

    /**
     * True when the scope parameter's default expression mentions `crashReporter`.
     *
     * A reference check rather than a structural one, and deliberately so: the rule is not
     * asking whether the default builds the *right* scope, only whether the reporter is what
     * the scope is derived from. Anything stricter would need type resolution to tell a
     * derivation from a coincidence, and a rule that cannot make that distinction is the
     * artefact this repository keeps finding.
     */
    fun defaultReferencesReporter(parameter: KtParameter): Boolean =
        parameter.defaultValue?.text?.contains("crashReporter") == true

    fun hasScopeArgument(arguments: KtValueArgumentList): Boolean =
        arguments.arguments.any { it.getArgumentName()?.asName?.identifier == "scope" }

    fun constructorMessage(className: String?): String =
        "$className declares both `crashReporter` and `scope`, and `scope` has no default " +
            "derived from the reporter. That forces every binding to supply a scope " +
            "independently, so the failures this ViewModel handles and the failures that escape " +
            "its background work are chosen separately and nothing keeps them in the same place. " +
            "Give `scope` the default `reportingScope(crashReporter)` and drop the argument from " +
            "the binding. If a test needs its own scope, passing one is still supported — the " +
            "default is what production inherits."

    fun bindingMessage(constructed: String): String =
        "This binding passes both `crashReporter` and `scope` to $constructed. The two are " +
            "chosen independently, so the failures the ViewModel handles reach one place and the " +
            "failures that escape its background work can reach another — the same symptom under " +
            "two unrelated keys, with nothing to correlate them. Drop the `scope` argument: the " +
            "ViewModel derives it from `crashReporter`. If it genuinely needs a scope from the " +
            "graph, that is a real decision and belongs in a comment saying why — not a default " +
            "the next binding will copy."
}

/**
 * Registers [NoDivergentScopeAndReporterRule] in the `no-unreported-failure-path` rule set.
 */
class NoDivergentScopeAndReporterProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-unreported-failure-path")

    /**
     * Three rules, one invariant: a failure the component can produce reaches the place the
     * component chose for it.
     *
     *   1. the class has somewhere to report — [NoUnreportedFailurePathRule]
     *   2. the binding lets it — [NoUnwiredReporterInBindingRule]
     *   3. both of its paths lead to the same place — [NoDivergentScopeAndReporterRule]
     *
     * The third shipped after the first two proved insufficient twice: once for a binding that
     * passed no reporter at all, and once for a class whose scope could be pointed somewhere
     * else entirely. Each was found by reading code, not by a gate.
     */
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoUnreportedFailurePath") to { cfg: Config ->
                NoUnreportedFailurePathRule(cfg)
            },
            RuleName("NoUnwiredReporterInBinding") to { cfg: Config ->
                NoUnwiredReporterInBindingRule(cfg)
            },
            RuleName("NoDivergentScopeAndReporter") to { cfg: Config ->
                NoDivergentScopeAndReporterRule(cfg)
            },
        ),
    )
}
