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
 * ## The exemption is keyed by the parameter
 *
 * The register of debt ([ComposeCollectionExemptions]) names a composable and,
 * under it, the raw collection parameters that composable still declares. The
 * exemption is therefore checked against the *parameter*, not against the
 * composable: a raw collection parameter that the register does not name is a
 * finding even when the composable itself is a known site.
 *
 * The same comparison runs in the other direction, and it is what makes the
 * register trustworthy: a registered parameter that no overload of the composable
 * declares any more is **stale** — the debt it names has been paid, or has
 * changed shape, and the register says otherwise.
 *
 * The stale check runs after the file has been visited, because a composable can
 * have **overloads**: `Nav3State.toDecoratedEntries` is declared twice, and only
 * one of the two takes the raw collection. A check that looked at one overload
 * would report the other one's parameters as missing.
 *
 * A composable that was deleted altogether leaves its entry behind, and no rule
 * can see it — the rule visits the code that exists. That case belongs to the
 * gate (`scripts/check-compose-stability-config.py`), which reads the sources.
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

    /**
     * The composables of the file being visited, by qualified name.
     *
     * A qualified name is not unique: a composable may have overloads, and they
     * share the name. The register describes one of them, so the parameters of
     * all of them are collected before the stale check runs.
     */
    private val sitesInFile = mutableMapOf<String, Site>()

    override fun preVisit(root: KtFile) {
        super.preVisit(root)
        sitesInFile.clear()
    }

    override fun postVisit(root: KtFile) {
        super.postVisit(root)
        reportStaleParameters()
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.isComposable()) return

        val qualifiedName = function.qualifiedName()
        val registered = ComposeCollectionExemptions.parametersOf(qualifiedName)
        val site = sitesInFile.getOrPut(qualifiedName) { Site(function) }

        function.valueParameters.forEach { parameter ->
            val name = parameter.name ?: return@forEach
            site.parameters.putIfAbsent(name, parameter.declaredTypeText())
            reportUnregisteredRawParameter(qualifiedName, registered, parameter)
        }
    }

    /**
     * A raw collection parameter must be named by the register.
     *
     * A parameter that is not in the register is the finding this rule was written
     * for: the composable may be a known site, but *this* parameter is not one the
     * register accounts for.
     */
    private fun reportUnregisteredRawParameter(
        qualifiedName: String,
        registered: Map<String, String>,
        parameter: KtParameter,
    ) {
        val collection = parameter.declaredCollectionType() ?: return
        val name = parameter.name ?: return
        val recorded = registered[name]
        val actual = parameter.declaredTypeText()

        if (recorded == null) {
            report(
                Finding(
                    entity = Entity.from(parameter),
                    message = "@Composable function takes a raw '$collection' parameter. " +
                        "The Compose compiler cannot treat it as stable, so the composable is " +
                        "not skippable. Pass a persistent collection " +
                        "(ImmutableList / ImmutableSet / ImmutableMap) and build it with " +
                        "toImmutableList() / toImmutableSet() at the boundary, or wrap a local " +
                        "list in remember { }. If this site is a known debt, name the " +
                        "parameter in ComposeCollectionExemptions.",
                    references = emptyList(),
                    suppressReasons = emptyList(),
                ),
            )
            return
        }

        if (actual == null || sameType(recorded, actual)) return
        report(
            Finding(
                entity = Entity.from(parameter),
                message = "the register of debt records '$name' of $qualifiedName as " +
                    "'$recorded', and the parameter declares '$actual'. The entry no longer " +
                    "describes the code — update it, or delete it if the parameter is no " +
                    "longer a raw collection.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    /**
     * Every parameter the register names must still be declared by some overload
     * of the composable.
     *
     * This is the half of the contract that keeps the register honest. A register
     * that only grows is a register that stops describing anything: the entry
     * says "this composable takes a raw `List` here", and the code says the
     * parameter is gone. The debt was paid and the register still counts it.
     */
    private fun reportStaleParameters() {
        sitesInFile.forEach { (qualifiedName, site) ->
            val registered = ComposeCollectionExemptions.parametersOf(qualifiedName)
            registered.forEach { (name, _) ->
                if (site.parameters.containsKey(name)) return@forEach
                report(
                    Finding(
                        entity = Entity.from(site.function),
                        message = "the register of debt names a parameter this composable does " +
                            "not declare: '$name' of $qualifiedName. The debt it names has been " +
                            "paid — delete the entry.",
                        references = emptyList(),
                        suppressReasons = emptyList(),
                    ),
                )
            }
        }
    }

    /**
     * The composable's qualified name: its package, plus the declarations it
     * is nested in, plus its own name.
     *
     * The order is the one the Compose compiler prints in its stability report —
     * the outermost class first, the function last — so a name can be copied
     * from the report into the exemption register without a translation table.
     */
    private fun KtNamedFunction.qualifiedName(): String {
        val parts = mutableListOf<String>()
        var current: PsiElement? = parent
        while (current != null && current !is KtFile) {
            if (current is KtClassOrObject) {
                parts.add(current.name ?: "")
            }
            current = current.parent
        }
        parts.reverse()
        parts.add(name ?: "")
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

    /** The declared type of the parameter, as the source spells it. */
    private fun KtParameter.declaredTypeText(): String? = typeReference?.text

    /**
     * A composable of the file being visited, and the parameters it declares.
     */
    private class Site(val function: KtNamedFunction, val parameters: MutableMap<String, String?> = mutableMapOf())

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

        /** Whitespace, which the source may wrap a long type across. */
        val WHITESPACE = Regex("\\s+")

        /**
         * A qualified path, so that `androidx.navigation3.runtime.NavEntryDecorator`
         * and `NavEntryDecorator` compare as the same type.
         */
        val QUALIFIED_PATH = Regex("([A-Za-z_][A-Za-z0-9_]*\\.)+([A-Za-z_][A-Za-z0-9_]*)")

        /**
         * True when two spellings of a type are the same type.
         *
         * The comparison ignores whitespace and package qualifiers, and nothing
         * else: `List<String>` and `List<String?>` are different types, and the
         * register that called them the same would be wrong about the code.
         */
        fun sameType(recorded: String, declared: String): Boolean =
            recorded.normalizedType() == declared.normalizedType()

        /** The type text without whitespace and without package qualifiers. */
        fun String.normalizedType(): String =
            replace(WHITESPACE, "").replace(QUALIFIED_PATH, "$2")
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
