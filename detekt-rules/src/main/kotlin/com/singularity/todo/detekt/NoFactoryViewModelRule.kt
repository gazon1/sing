package com.singularity.todo.detekt

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Bans registering ViewModels in Koin via the per-injection `factory { }` / `factoryOf(::...)`
 * scopes (AGENTS.md: "factory { Vm(...) } — never for ViewModels — memory leak").
 *
 * A `factory` VM is recreated on every injection and is not lifecycle-bound, so its
 * injected `AutoCloseableCoroutineScope` is never closed and its state resets on
 * configuration changes. The canonical registrations are `viewModel { ... }` /
 * `viewModelOf(::...)`.
 *
 * @see NoFactoryViewModelProvider for registration.
 */
class NoFactoryViewModelRule(config: Config) : Rule(config, "", null) {

    override fun visitCallExpression(expression: KtCallExpression) {
        // Without super the tree traversal stops at this node and nested calls
        // (e.g. `factory` inside `module { ... }`) are never visited.
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return

        when (callee.text) {
            "factory" -> {
                val body = expression.lambdaBody() ?: return
                val ctors = mutableListOf<KtCallExpression>()
                collectCtorCalls(body, ctors)
                ctors.filter { it.isViewModelConstructor() }.forEach { report(it) }
            }

            "factoryOf" -> {
                val refs = mutableListOf<KtCallableReferenceExpression>()
                collectOfType(expression, refs)
                if (refs.any { VIEWMODEL_CTOR.matches(it.text.removePrefix("::")) }) {
                    report(expression)
                }
            }
        }
    }

    private fun KtCallExpression.lambdaBody(): PsiElement? =
        (lambdaArguments.singleOrNull()?.getArgumentExpression() as? KtLambdaExpression)
            ?: (valueArguments.firstOrNull()?.getArgumentExpression() as? KtLambdaExpression)

    private fun collectCtorCalls(element: PsiElement, acc: MutableList<KtCallExpression>) {
        for (child in element.children) {
            if (child is KtCallExpression) acc.add(child)
            collectCtorCalls(child, acc)
        }
    }

    private fun collectOfType(element: PsiElement, acc: MutableList<KtCallableReferenceExpression>) {
        for (child in element.children) {
            if (child is KtCallableReferenceExpression) acc.add(child)
            collectOfType(child, acc)
        }
    }

    private fun KtCallExpression.isViewModelConstructor(): Boolean =
        (calleeExpression as? KtNameReferenceExpression)?.let { VIEWMODEL_CTOR.matches(it.text) } == true

    private fun report(expression: KtCallExpression) {
        report(
            Finding(
                entity = Entity.from(expression),
                message = "ViewModels must not be registered with factory/factoryOf — " +
                    "use viewModel { } or viewModelOf(::...) so the instance is lifecycle-bound " +
                    "and its injected AutoCloseableCoroutineScope gets closed.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    private companion object {
        /** Uppercase-starting identifier ending in ViewModel — constructor-call convention. */
        val VIEWMODEL_CTOR = Regex("^[A-Z][A-Za-z0-9]*ViewModel$")
    }
}

/**
 * Registers [NoFactoryViewModelRule] in the `no-factory-viewmodel` rule set.
 */
class NoFactoryViewModelProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-factory-viewmodel")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoFactoryViewModel") to { cfg: Config -> NoFactoryViewModelRule(cfg) },
        ),
    )
}
