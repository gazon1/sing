package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtUserType

/**
 * A `@Composable` function must not take a raw collection as a parameter.
 *
 * ## Why
 *
 * The Compose compiler treats a parameter as skippable only when its type is stable.
 * A `List` is an interface: the compiler cannot know that two calls handed the same
 * list will not see it change, so the parameter is unstable and the composable is
 * **not** skippable — it recomposes whenever the parent does.
 *
 * The usual fix (`remember { listOf(...) }`) is not free either: `listOf` returns a
 * fresh instance, so a stable-but-equal parameter is compared by `equals`, which for
 * a collection is a deep comparison of every element. A long list turns "skip the
 * recomposition" into "compare the whole list, then skip it".
 *
 * The type that solves both halves is a persistent collection
 * (`ImmutableList`, `PersistentList`): the compiler knows it is stable, and its
 * `equals` compares structure rather than identity, so an unchanged list compares
 * equal and the composable is skipped.
 *
 * ## Scope
 *
 * Only `@Composable` functions are checked. A repository method or a ViewModel
 * function taking a `List` is ordinary Kotlin and must not be flagged — the rule is
 * about what the Compose compiler can decide, and it has no opinion outside a
 * composable.
 *
 * Typealiases are not resolved: the rule reads the declared type text, so a
 * parameter declared through a typealias that hides a `List` is not reported. That
 * is a known limit, not an oversight — resolving typealiases needs semantic
 * analysis, which this rule does not have.
 *
 * @see NoMutableCollectionCastRule — the other half of the stability contract.
 * @see 2026-10-22-compose-stability-contract
 */
class NoUnstableCollectionParamRule(config: Config) : Rule(config, "", null) {

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.isComposable()) return
        if (ComposeCollectionExemptions.isKnown(function.qualifiedName())) return

        function.valueParameters.forEach { parameter ->
            val collection = parameter.declaredCollectionType() ?: return@forEach
            report(
                Finding(
                    entity = Entity.from(parameter),
                    message = "@Composable function takes a raw '$collection' parameter. " +
                        "The Compose compiler cannot treat it as stable, so the composable is " +
                        "not skippable. Pass a persistent collection " +
                        "(ImmutableList / ImmutableSet / ImmutableMap) and build it with " +
                        "toImmutableList() / toImmutableSet() at the boundary, or wrap a local " +
                        "list in remember { }.",
                    references = emptyList(),
                    suppressReasons = emptyList(),
                ),
            )
        }
    }

    /**
     * The composable's qualified name: its package, plus the declarations it
     * is nested in, plus its own name.
     *
     * The exemption register is keyed by the same name the Compose compiler
     * prints in its stability report, so a site can be matched between the two
     * without a translation table.
     */
    private fun KtNamedFunction.qualifiedName(): String {
        val parts = mutableListOf<String>()
        var current: PsiElement? = this
        while (current != null && current !is KtFile) {
            if (current is KtClassOrObject) {
                parts.add(current.name ?: "")
            }
            current = current.parent
        }
        parts.add(name ?: "")
        parts.reverse()
        val packageName = containingKtFile.packageFqName.asString()
        val joined = parts.joinToString(".")
        return if (packageName.isEmpty()) joined else "$packageName.$joined"
    }

    /** True when the function carries the Compose `@Composable` annotation. */
    private fun KtNamedFunction.isComposable(): Boolean =
        annotationEntries.any { entry ->
            entry.typeReference?.text?.substringAfterLast('.') == COMPOSABLE
        }

    /**
     * The name of the raw collection type the parameter declares, or null when the
     * parameter is not a raw collection.
     *
     * The type is read from the declaration text rather than from the resolved type:
     * a generic parameter is `List<Tag>` and the interesting part is the name
     * before the type arguments.
     */
    private fun KtParameter.declaredCollectionType(): String? {
        val reference = typeReference ?: return null
        val element = reference.typeElement
        // `List<Tag>?` is still a raw collection: nullability does not make it stable.
        val type = (element as? KtNullableType)?.innerType ?: element
        val userType = type as? KtUserType ?: return null
        // A qualified name is not the stdlib collection. `NotesRoute.List` is a
        // nested route object — the last segment happens to be spelled `List`,
        // and a rule that called it a collection would send the reader to
        // convert a route into a persistent collection.
        val qualifier = userType.qualifier?.text
        if (qualifier != null && qualifier != "kotlin.collections") return null
        val name = userType.referencedName
        return if (name in RAW_COLLECTIONS) name else null
    }

    private companion object {
        const val COMPOSABLE = "Composable"

        /**
         * The raw collection types a composable parameter must not use.
         *
         * `Collection` is here because it is the same problem: an interface the
         * compiler cannot reason about. `Iterable` is deliberately absent — it is
         * rarer as a composable parameter, and a rule that reports rarely is a rule
         * people turn off.
         */
        val RAW_COLLECTIONS = setOf(
            "List",
            "MutableList",
            "Set",
            "MutableSet",
            "Map",
            "MutableMap",
            "Collection",
        )
    }
}

/**
 * Registers [NoUnstableCollectionParamRule] in the `no-unstable-collection-param`
 * rule set.
 */
class NoUnstableCollectionParamProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-unstable-collection-param")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoUnstableCollectionParam") to { cfg: Config -> NoUnstableCollectionParamRule(cfg) },
        ),
    )
}
