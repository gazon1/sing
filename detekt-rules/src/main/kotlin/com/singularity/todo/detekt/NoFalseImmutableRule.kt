package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/**
 * A class marked `@Immutable` has to be immutable.
 *
 * ## Why
 *
 * `@Immutable` is a promise the compiler cannot check. It says: this instance
 * never changes, so the compiler may compare it by `equals` and skip the
 * recomposition. The compiler believes the promise — it does not look at the
 * fields.
 *
 * A class that carries a `var` or a raw `List` breaks that promise, and the
 * breakage is silent: the screen does not crash, it shows a value that is one
 * recomposition out of date. The class was marked `@Immutable` to get the
 * skipping, and the skipping is exactly what makes the staleness invisible.
 *
 * Six screen states carried a false `@Immutable` before 2026-10-22, and the
 * annotation came back while the question was open. A rule is cheaper than a
 * habit: it is checked on every build, and it does not need somebody to
 * remember.
 *
 * ## Scope
 *
 * Only `@Immutable` is checked. `@Stable` is a different promise — "the
 * instance may change, and Compose is not told about it" — and a `@Stable`
 * class with `private var by mutableStateOf(...)` is the ordinary pattern in
 * this repository (`DialogState`, `FormState`, `OverlayState`): the state is
 * observable through the snapshot system, and the annotation is about the
 * *reference* being stable, not about the fields.
 *
 * The rule reads the declared type, so it cannot resolve a typealias that
 * hides a collection, and it says nothing about a property whose type is
 * inferred. Those are known limits, not oversights: resolving them needs
 * semantic analysis this rule does not have.
 *
 * @see NoUnstableCollectionParamRule — the other half of the stability contract.
 * @see 2026-10-22-compose-stability-contract
 */
class NoFalseImmutableRule(config: Config) : Rule(config, "", null) {

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (!klass.isImmutable()) return

        klass.declaredProperties().forEach { declared ->
            if (declared.mutable) {
                report(
                    Finding(
                        entity = declared.entity,
                        message = "@Immutable class declares a mutable property " +
                            "'${declared.name}'. @Immutable promises that the instance never " +
                            "changes, and the compiler believes the promise: it compares the " +
                            "instance by equals and skips the recomposition. A 'var' breaks that " +
                            "promise silently — the screen shows a value that is one " +
                            "recomposition out of date. Use a 'val', or drop @Immutable and let " +
                            "the class be honestly unstable.",
                        references = emptyList(),
                        suppressReasons = emptyList(),
                    ),
                )
                return@forEach
            }
            val collection = declared.rawCollectionName() ?: return@forEach
            report(
                Finding(
                    entity = declared.entity,
                    message = "@Immutable class declares a raw '$collection' property " +
                        "'${declared.name}'. The compiler trusts @Immutable and never looks at " +
                        "the field, so a collection that changes underneath it goes unnoticed. " +
                        "Use a persistent collection (ImmutableList / ImmutableSet / " +
                        "ImmutableMap), or drop @Immutable.",
                    references = emptyList(),
                    suppressReasons = emptyList(),
                ),
            )
        }
    }

    /**
     * The properties a class declares: the constructor parameters that are
     * properties, and the properties of the body.
     *
     * A `data class Foo(val items: List<String>)` has no property in the
     * class body — `items` is a constructor parameter that happens to be a
     * property, and a rule that reads only the body sees a class with no
     * fields and calls it immutable.
     */
    private fun KtClass.declaredProperties(): List<DeclaredProperty> {
        val fromConstructor = primaryConstructorParameters
            .filter { it.hasValOrVar() }
            .map { parameter ->
                DeclaredProperty(
                    name = parameter.name ?: "",
                    type = parameter.typeReference?.text,
                    mutable = parameter.isMutable(),
                    entity = Entity.from(parameter),
                )
            }
        val fromBody = declarations
            .filterIsInstance<KtProperty>()
            .map { property ->
                DeclaredProperty(
                    name = property.name ?: "",
                    type = property.typeReference?.text,
                    mutable = property.isMutable(),
                    entity = Entity.from(property),
                )
            }
        return fromConstructor + fromBody
    }

    /** True when the class carries the Compose `@Immutable` annotation. */
    private fun KtClass.isImmutable(): Boolean =
        annotationEntries.any { entry ->
            entry.typeReference?.text?.substringAfterLast('.') == IMMUTABLE
        }

    /** True when the constructor parameter is a `var` rather than a `val`. */
    private fun KtParameter.isMutable(): Boolean = (valOrVarKeyword?.text ?: "") == "var"

    /** True when the property is a `var` rather than a `val`. */
    private fun KtProperty.isMutable(): Boolean = (valOrVarKeyword?.text ?: "") == "var"

    /**
     * The name of the raw collection the declared type is, or null when the type
     * is not a raw collection.
     *
     * The type is read from the declaration text rather than from the resolved
     * type: the interesting part of `ImmutableList<Tag>` is the name before the
     * type arguments.
     */
    private fun DeclaredProperty.rawCollectionName(): String? {
        val text = type ?: return null
        val name = text.removeSuffix("?").substringBefore('<').trim()
        if (name.isEmpty()) return null
        // A qualified name is not the stdlib collection: `NotesRoute.List` is a
        // nested route object whose last segment happens to be spelled `List`.
        val qualifier = name.substringBeforeLast('.', "")
        if (qualifier.isNotEmpty() && qualifier != "kotlin.collections") return null
        return if (name in RAW_COLLECTIONS) name else null
    }

    /** A property the class declares, and what it declares it as. */
    private class DeclaredProperty(val name: String, val type: String?, val mutable: Boolean, val entity: Entity)

    private companion object {
        const val IMMUTABLE = "Immutable"

        /** The raw collection types an `@Immutable` class must not carry. */
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
 * Registers [NoFalseImmutableRule] in the `no-false-immutable` rule set.
 */
class NoFalseImmutableProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-false-immutable")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoFalseImmutable") to { cfg: Config -> NoFalseImmutableRule(cfg) },
        ),
    )
}
