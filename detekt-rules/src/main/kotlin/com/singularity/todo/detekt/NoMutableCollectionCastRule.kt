package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Bans casting to a mutable collection type.
 *
 * ## Why
 *
 * The stability contract in `config/compose/stability_config.conf` declares a set of
 * domain types stable: the Compose compiler compares them by `equals`, which is a
 * *structural* comparison, and assumes their contents do not change underneath.
 *
 * A cast to `MutableList` / `MutableSet` / `MutableMap` is a way to break that
 * assumption from inside code the compiler cannot see. The value still compares equal
 * to a copy that was made before the mutation, so the UI keeps showing the old
 * contents: the row does not update, and nothing reports an error. The symptom is a
 * screen that is stale, not a crash — which is exactly the kind of defect that is
 * expensive to diagnose later and trivial to prevent now.
 *
 * There are **no** occurrences of this cast in the repository today. That is the
 * point of the rule: the cast is not forbidden because it was found and cleaned up,
 * but because the contract it would break is now written down, and a rule that only
 * describes last year's mistakes is a comment.
 *
 * ## What to do instead
 *
 * Build the mutable collection first and hand the *immutable* one over:
 *
 * ```kotlin
 * val mutable = mutableListOf<String>()
 * fill(mutable)
 * val stable: ImmutableList<String> = mutable.toImmutableList()
 * ```
 *
 * The mutation happens where it is visible; the value that crosses the boundary is
 * the one the compiler can reason about.
 *
 * @see NoUnstableCollectionParamRule — the other half of the stability contract.
 * @see 2026-10-22-compose-stability-contract
 */
class NoMutableCollectionCastRule(config: Config) : Rule(config, "", null) {

    override fun visitBinaryWithTypeRHSExpression(expression: KtBinaryExpressionWithTypeRHS) {
        super.visitBinaryWithTypeRHSExpression(expression)
        checkCast(expression)
    }

    private fun checkCast(expression: KtBinaryExpressionWithTypeRHS) {
        // `as` and `as?` — the operation is a name expression, not a token.
        val operation = expression.operationReference.text
        if (operation != "as" && operation != "as?") return

        val userType = expression.right?.typeElement as? KtUserType ?: return
        val name = userType.referencedName
        if (name !in MUTABLE_COLLECTIONS) return

        report(
            Finding(
                entity = Entity.from(expression),
                message = "Casting to '$name' breaks the stability contract: the Compose " +
                    "compiler compares a stable value by equals, so a collection mutated " +
                    "after the cast keeps comparing equal to its old contents and the UI " +
                    "goes stale. Build the mutable collection first, then hand over an " +
                    "immutable one (toImmutableList() / toImmutableSet() / toImmutableMap()).",
                references = emptyList(),
                suppressReasons = listOf("NoMutableCollectionCast"),
            ),
        )
    }

    private companion object {
        val MUTABLE_COLLECTIONS = setOf(
            "MutableList",
            "MutableSet",
            "MutableMap",
            "MutableCollection",
        )
    }
}

/**
 * Registers [NoMutableCollectionCastRule] in the `no-mutable-collection-cast`
 * rule set.
 */
class NoMutableCollectionCastProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-mutable-collection-cast")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoMutableCollectionCast") to { cfg: Config -> NoMutableCollectionCastRule(cfg) },
        ),
    )
}
