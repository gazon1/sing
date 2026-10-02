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
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Flags methods inside `*UseCase` classes that are pure pass-throughs to a Repository.
 *
 * A pass-through method is defined as:
 * - Located in a class whose name ends with `UseCase` (excluding abstract `LlmUseCase<I,O>`)
 * - Public or internal named function (not private, not operator)
 * - Has an **expression body** (single-expression shortcut syntax: `fun foo() = repo.bar()`)
 * - The call receiver is a property on the class whose declared type ends in `Repository`
 *
 * Legitimate use cases (not flagged):
 * - Methods whose body is a `KtBlockExpression` (even short — that's real logic)
 * - Methods that call `clock.xxx()` — domain timestamp injection is legitimate
 * - Methods whose call receiver is `tool.*` — LLM use cases call Koog tool facades
 * - The abstract `LlmUseCase<I,O>` base class
 *
 * Note: test-source exemption is not implemented via path filters in this rule.
 *
 * @see PassThroughUseCaseProvider for how this rule is registered.
 */
class PassThroughUseCaseRule(config: Config) : Rule(config, "", null) {

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        if (classOrObject !is KtClass) return
        val name = classOrObject.name ?: return
        // Exclude the abstract LLM base class — its execute() wraps tool.execute + JSON decode
        if (name == "LlmUseCase") return
        if (!name.endsWith("UseCase")) return

        for (declaration in classOrObject.declarations) {
            if (declaration !is KtNamedFunction) continue
            checkFunction(classOrObject, declaration)
        }
    }

    private fun checkFunction(psiClass: KtClass, function: KtNamedFunction) {
        // Skip operators and private functions
        if (function.hasModifier(KtTokens.OPERATOR_KEYWORD)) return
        if (function.hasModifier(KtTokens.PRIVATE_KEYWORD)) return

        // Skip if body is a block — that's real logic
        val bodyExpression = function.bodyExpression ?: return
        if (bodyExpression is KtBlockExpression) return

        // Must be an expression body — single-expression shortcut syntax:
        // fun foo() = repository.bar()
        if (!isSingleRepositoryCall(bodyExpression)) return

        val callExpression = (bodyExpression as? KtDotQualifiedExpression) ?: return
        val selector = callExpression.selectorExpression as? KtCallExpression ?: return
        val receiver = callExpression.receiverExpression as? KtNameReferenceExpression ?: return

        // Skip clock.* — domain timestamp injection is legitimate domain logic
        if (receiver.getReferencedName() == "clock") return
        // Skip tool.* — LLM use cases call Koog SimpleTool facade methods
        if (receiver.getReferencedName() == "tool") return

        // Resolve the receiver property and check its type ends in Repository
        val receiverTypeName = resolveReceiverType(psiClass, receiver) ?: return
        if (!receiverTypeName.endsWith("Repository")) return

        val methodName = function.name ?: return

        report(
            Finding(
                entity = Entity.atName(function),
                message = buildString {
                    append("UseCase method '$methodName' is a pass-through to Repository.")
                    append(" The method delegates to '${receiver.getReferencedName()}.$methodName'")
                    append(" without adding logic.")
                    append(" Call the repository directly from the ViewModel,")
                    append(" or move real logic into this UseCase")
                    append(" (validation, clock injection, ID generation, composition, 404 mapping).")
                },
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    /**
     * True when [expr] is a single call expression:
     * `repository.foo()` — KtDotQualifiedExpression with a KtCallExpression selector
     */
    private fun isSingleRepositoryCall(expr: KtExpression): Boolean {
        if (expr !is KtDotQualifiedExpression) return false
        return expr.selectorExpression is KtCallExpression
    }

    /**
     * Returns the simple type name of the receiver property declared in [psiClass],
     * or null if the receiver is not a property on this class.
     */
    private fun resolveReceiverType(psiClass: KtClass, receiver: KtNameReferenceExpression): String? {
        val receiverName = receiver.getReferencedName()

        val property = psiClass.declarations
            .filterIsInstance<KtProperty>()
            .find { it.name == receiverName }

        val typeReference = property?.typeReference ?: return null
        return typeReference.text // e.g. "ChecklistRepository"
    }
}

/**
 * Registers [PassThroughUseCaseRule] in the `pass-through-use-case` rule set.
 * Inner-class pattern: rule and provider live in the same file.
 */
class PassThroughUseCaseProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("pass-through-use-case")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf(RuleName("PassThroughUseCase") to { cfg: Config -> PassThroughUseCaseRule(cfg) }),
    )
}
