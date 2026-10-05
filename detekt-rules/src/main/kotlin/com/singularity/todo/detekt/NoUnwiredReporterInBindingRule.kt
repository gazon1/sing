package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * Flags a Koin `viewModel { … }` binding that constructs a ViewModel without passing it a
 * `CrashReportingPort`.
 *
 * ## Why this rule exists, when the class-level one already does
 *
 * [NoUnreportedFailurePathRule] asks "does this ViewModel class have somewhere to report?" and
 * answers it about the class. That is the wrong half on its own, and the gap is not
 * theoretical: two production bindings on `main` constructed a ViewModel whose class declared
 * `crashReporter: CrashReportingPort = NoOpCrashReportingPort()` and whose default `scope` was
 * `reportingScope(crashReporter)` — and the binding passed neither. Both shipped a no-op
 * reporter, so every `catchTo` failure *and* every unhandled background failure in those two
 * ViewModels went nowhere, in the exact feature this whole migration existed to fix.
 *
 * The class-level rule passed, and would keep passing. It reads a different file and a different
 * question. "The class can report" and "the binding lets it" are separate properties, and only
 * the second one decides whether a failure reaches a dashboard.
 *
 * ## The false-positive trade, stated plainly
 *
 * A detekt rule has no type resolution, so it cannot know whether the constructed class *declares*
 * a `crashReporter` parameter. It matches on the binding's shape: a `viewModel { }` or
 * `viewModel<X> { }` whose body constructs a ViewModel-shaped type without a `crashReporter`
 * argument.
 *
 * That means a ViewModel with no reporter parameter cannot be bound without a suppression. That
 * is deliberate. Passing `crashReporter = get()` to such a class would not compile, so the only
 * alternatives are to add the parameter — manufacturing a dependency to satisfy a check, which
 * the `background-handler-injection` ADR explicitly rejects — or to suppress with a written
 * reason. Suppression is the honest answer: a ViewModel that genuinely cannot fail has said so.
 *
 * There is currently no such binding in the tree, so the rule needs no baseline.
 *
 * ## What is not flagged
 *
 * - `viewModelOf(::Vm)` — Koin resolves every constructor parameter from the graph, so the
 *   reporter is supplied by construction. This is the shape the project prefers and the reason
 *   the defect is rare.
 * - A binding for a type whose name is not ViewModel-shaped. The name list is the same one
 *   [NoUnreportedFailurePathRule] uses, kept in one place by [NoUnreportedFailurePathPolicy].
 * - Bindings that are not `viewModel` — a `single { SomeViewModel(…) }` is a different mistake
 *   with a different fix, and the `NoFactoryViewModelRule` already owns factory-shaped bindings.
 *
 * @see NoUnreportedFailurePathPolicy for the shared name list.
 */
class NoUnwiredReporterInBindingRule(config: Config) : Rule(config, "", null) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!NoUnwiredReporterInBindingPolicy.isViewModelBinding(expression)) return

        // The call to inspect is the *inner* one. `expression` is `viewModel { … }`; its
        // argument list is the trailing lambda, so reading `crashReporter` off it would always
        // come up empty — and the rule would flag every binding, including the correct ones.
        val constructed = NoUnwiredReporterInBindingPolicy.constructedIn(expression) ?: return
        val type = constructed.calleeExpression?.text ?: return
        if (!NoUnreportedFailurePathPolicy.isViewModelName(type)) return
        if (NoUnwiredReporterInBindingPolicy.hasReporterArgument(constructed.valueArgumentList)) return

        report(
            Finding(
                entity = Entity.from(expression),
                message = NoUnwiredReporterInBindingPolicy.message(type),
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }
}

/**
 * The decision [NoUnwiredReporterInBindingRule] makes, over PSI.
 *
 * Split out so each branch is testable without a detekt harness.
 */
internal object NoUnwiredReporterInBindingPolicy {

    /**
     * True when this call is a Koin `viewModel` registration.
     *
     * Two spellings, and getting only one right is how the first draft of this rule matched
     * nothing while compiling and passing:
     *
     * - `viewModel { … }` **with an import in scope** — a bare `KtCallExpression` in a
     *   statement, with no dot-qualified parent to read a receiver from. This is what every
     *   binding in the tree is written as.
     * - `koin.core.module.dsl.viewModel { … }` — here the expression is the *selector* of a
     *   dot-qualified, so the receiver is `koin.core.module.dsl` and the name `viewModel` is
     *   its own callee, not its receiver.
     *
     * Koin's `viewModelOf` is a *function reference* with no lambda, so it can never appear
     * here — which is correct, because it wires every constructor parameter by type.
     */
    fun isViewModelBinding(expression: KtCallExpression): Boolean {
        val callee = expression.calleeExpression?.text ?: return false
        if (callee == "viewModel") return true
        val qualified = expression.parent as? KtDotQualifiedExpression ?: return false
        if (qualified.selectorExpression !== expression) return false
        return qualified.receiverExpression?.text?.endsWith(".viewModel") == true
    }

    /**
     * The constructor call the binding produces.
     *
     * The body is a single lambda whose value is the constructed object, and the outermost call
     * in it is that constructor. Nested calls — `TaskDetailDeps(…)` inside
     * `TaskDetailCoordinator(…)` — come later and are not what is being bound, which is why the
     * test `a nested dependency constructor is not mistaken for the bound type` exists.
     */
    fun constructedIn(expression: KtCallExpression): KtCallExpression? {
        val lambda = expression.lambdaArguments.firstOrNull()?.getLambdaExpression() ?: return null
        var found: KtCallExpression? = null
        lambda.accept(
            object : KtTreeVisitorVoid() {
                override fun visitCallExpression(inner: KtCallExpression) {
                    if (found != null) return
                    val name = inner.calleeExpression?.text ?: return
                    if (name.contains('.')) {
                        // A qualified call like `deps.make(…)` is not a constructor of the
                        // bound type; keep descending.
                        super.visitCallExpression(inner)
                        return
                    }
                    found = inner
                }
            },
        )
        return found
    }

    /** True when a **named** `crashReporter` argument is present. */
    fun hasReporterArgument(arguments: KtValueArgumentList?): Boolean =
        arguments?.arguments?.any { it.getArgumentName()?.asName?.identifier == "crashReporter" } == true

    fun message(constructed: String): String =
        "A `viewModel` binding constructs $constructed without a **named** `crashReporter` " +
            "argument. The argument has to be named: this rule has no type resolution, so it " +
            "cannot see a reporter passed positionally, and a positional `get()` chain is " +
            "unreadable anyway. The class " +
            "may declare `crashReporter: CrashReportingPort = NoOpCrashReportingPort()` and a " +
            "default `scope = reportingScope(crashReporter)` — in which case this binding ships " +
            "a no-op reporter and every failure the ViewModel handles goes nowhere. Pass " +
            "`crashReporter = get()`. If $constructed genuinely cannot fail, suppress with a " +
            "reason rather than adding a parameter to satisfy the rule."
}
