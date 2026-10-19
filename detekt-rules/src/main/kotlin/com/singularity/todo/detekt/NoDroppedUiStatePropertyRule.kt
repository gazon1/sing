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
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Bans a variant of a sealed `*UiState` that does not carry the properties of the
 * interface it implements.
 *
 * A sealed UI state is a state *machine* with more than one step: the list is loading,
 * the list is empty, the list failed, the list holds something. The properties the
 * interface declares are the ones that describe **what the screen is showing** — the
 * query the list is filtered by, the sort order, the selection — and every step of the
 * machine has to carry them.
 *
 * A variant that replaces such a property with a constant does not "have" a value, it
 * has a *default*: the value the screen had before the user did anything. The screen
 * binds those properties to its controls, so the constant reaches the user as a reset.
 * That is not a cosmetic defect:
 *
 * - The projects list binds its search field to `searchQuery`. A failure that reset it
 *   cleared the text the user had typed, and — because the field is bound to that
 *   value — the cleared text was pushed back to the ViewModel as if the user had
 *   cleared it. The list could then never come back.
 * - The notes list binds its query, its filter chip and its sort control the same
 *   way, and its error handler emitted an empty list state, resetting all three.
 *
 * The rule requires every variant to declare every interface property as a
 * **constructor parameter without a default value**. The compiler then forces the
 * ViewModel to pass the value it actually holds — the enforcement is a compile error,
 * not a lint, which is why the rule exists at all.
 *
 * ## What is reported
 *
 * - A variant that does not declare a property of the interface at all.
 * - A variant that declares it in the class body instead of the constructor — a
 *   property with an initializer is a constant, and a constant is a reset.
 * - A variant that declares it in the constructor but gives it a default value: a
 *   default is a value the ViewModel did not have to think about.
 *
 * ## Allowed patterns (not flagged)
 *
 * - A UI state whose interface declares no properties — there is nothing to carry.
 * - A variant that declares the property as a required constructor parameter.
 * - A plain (non-sealed) interface, and any interface whose name does not end with
 *   `UiState`.
 *
 * ## Known limits
 *
 * - The interface must be *sealed* and named `*UiState`. An open interface with a
 *   different name is not recognised, and PSI has no type resolution here.
 * - The check is per file: a variant in another file that implements the interface is
 *   not seen.
 *
 * @see NoDroppedUiStatePropertyProvider for registration.
 */
class NoDroppedUiStatePropertyRule(config: Config) : Rule(config, "", null) {

    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        for (sealedInterface in sealedUiStateInterfaces(root)) {
            checkVariants(sealedInterface)
        }
    }

    /**
     * The sealed interfaces in the file whose name ends with `UiState`.
     *
     * The name is the house convention (`ProjectsUiState`, `NotesUiState`, …) and it
     * is what makes the rule's scope legible: a state the app owns is a `*UiState`.
     */
    private fun sealedUiStateInterfaces(root: KtFile): List<KtClass> {
        val found = mutableListOf<KtClass>()
        root.accept(
            object : KtTreeVisitorVoid() {
                override fun visitClass(klass: KtClass) {
                    if (klass.isInterface() &&
                        klass.hasModifier(KtTokens.SEALED_KEYWORD) &&
                        klass.name?.endsWith("UiState") == true
                    ) {
                        found.add(klass)
                    }
                    super.visitClass(klass)
                }
            },
        )
        return found
    }

    /**
     * The properties the interface declares itself.
     *
     * A property of a *nested* class is that class's own business; only the
     * interface's own declarations are the ones every variant has to carry.
     */
    private fun interfaceProperties(sealedInterface: KtClass): List<KtProperty> =
        sealedInterface.body?.declarations?.filterIsInstance<KtProperty>().orEmpty()

    /** The variants of the interface: the nested classes and objects that implement it. */
    private fun variants(sealedInterface: KtClass): List<KtClass> =
        sealedInterface.body?.declarations?.filterIsInstance<KtClass>().orEmpty().filter { variant ->
            variant.isInterface().not() && variant.superTypeListEntries.any { entry ->
                entry.typeReference?.text?.substringBefore('<')?.substringAfterLast('.')?.trim() ==
                    sealedInterface.name
            }
        }

    private fun checkVariants(sealedInterface: KtClass) {
        val properties = interfaceProperties(sealedInterface)
        if (properties.isEmpty()) return
        for (variant in variants(sealedInterface)) {
            for (property in properties) {
                val name = property.name ?: continue
                val parameter = constructorParameter(variant, name)
                when {
                    parameter == null && declaresInBody(variant, name) -> report(
                        variant,
                        name,
                        "declares it in the class body, which makes it a constant",
                    )

                    parameter == null -> report(variant, name, "does not declare it at all")

                    parameter.hasDefaultValue() -> report(
                        variant,
                        name,
                        "gives it a default value, and a default is a value the ViewModel " +
                            "did not have to think about",
                    )
                }
            }
        }
    }

    /** The variant's primary-constructor parameter for [name], if it has one. */
    private fun constructorParameter(variant: KtClass, name: String): KtParameter? =
        variant.primaryConstructor?.valueParameters?.firstOrNull { it.name == name }

    /** True when the variant declares the property in its body rather than its constructor. */
    private fun declaresInBody(variant: KtClass, name: String): Boolean =
        variant.body?.declarations?.filterIsInstance<KtProperty>().orEmpty().any { it.name == name }

    /** Reports the variant, naming the property and what is wrong with it. */
    private fun report(variant: KtClass, name: String, problem: String) {
        report(
            Finding(
                entity = Entity.from(variant),
                message = "`${variant.name}` $problem (`$name`), but it is a property of the " +
                    "interface it implements. Every variant of a sealed UI state has to carry " +
                    "the properties the interface declares: the screen binds them to its " +
                    "controls, so a variant that replaces one with a constant resets what " +
                    "the user had chosen. Declare it as a constructor parameter without a " +
                    "default value — the compiler then forces the ViewModel to pass the " +
                    "value it holds.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }
}

/**
 * Registers [NoDroppedUiStatePropertyRule] in the `no-dropped-ui-state-property` rule set.
 */
class NoDroppedUiStatePropertyProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-dropped-ui-state-property")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoDroppedUiStateProperty") to { cfg: Config ->
                NoDroppedUiStatePropertyRule(cfg)
            },
        ),
    )
}
