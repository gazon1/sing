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
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeReference

/**
 * Bans a `TextFieldState` stored in a ViewModel or in a UI state class.
 *
 * This is the storage half of the state-based text field migration. A `TextFieldState` is a
 * mutable, snapshot-backed holder. The field's text lives in it while the user types, and the
 * field is the only thing that can merge text that arrives from outside. That is exactly why
 * it belongs in the composable that owns the field.
 *
 * A ViewModel's contract is a value: a `MutableStateFlow<UiState>` over an immutable data
 * class. A `String` in that state is comparable, saveable and reportable. A `TextFieldState`
 * is none of those, so putting one there creates a second source of truth for the same text
 * — the state and the field can disagree, and nothing reports the drift. ADR
 * `2026-10-19-text-field-state-migration` rejected hoisting the state into the ViewModel for
 * exactly this reason; the rule is the half of that decision that keeps it in place.
 *
 * The companion rule `NoValueBasedTextField` bans the *old* shape (a `TextField(state = …)`
 * call carrying `value =` / `onValueChange =`). This one bans the *new* mistake: keeping the
 * state-based buffer in the layer that must not own it.
 *
 * ## What is reported
 *
 * - A property whose declared type is `TextFieldState` — nullable, or a collection of them —
 *   inside a class that is a ViewModel (a class named `*ViewModel`, or one extending
 *   `MviViewModel` / `DraftMviViewModel`).
 * - A constructor parameter of type `TextFieldState` in such a class.
 * - A property of type `TextFieldState` in a class that implements a `*UiState`-style sealed
 *   interface declared in the same file.
 * - A ViewModel property that stores the result of `rememberTextFieldState()` /
 *   `rememberBridgedTextFieldState()` — the creation call is correct, storing it is not.
 *
 * ## Allowed patterns (not flagged)
 *
 * - A `TextFieldState` in a composable, in a composable helper, in a preview or in a test.
 * - A local variable or a function parameter of type `TextFieldState` in ordinary code — a
 *   composable helper that takes the field's state is the sanctioned shape.
 * - A ViewModel property of type `String` — the value the ViewModel is supposed to keep.
 * - A `TextFieldState` held by a class that is neither a ViewModel nor a UI state class.
 *
 * ## Known limits
 *
 * - The UI state half only sees interfaces declared in the same file. A `*UiState` interface
 *   declared elsewhere is not resolved, so a data class in another file that implements it is
 *   not checked.
 * - Detection is by declared type text, so a `TextFieldState` reached through a type alias is
 *   not seen.
 *
 * @see NoTextFieldStateInViewModelProvider for registration.
 */
class NoTextFieldStateInViewModelRule(config: Config) : Rule(config, "", null) {

    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        val classes = collectClasses(root)
        val uiStateInterfaces = uiStateInterfaceNames(classes)
        for (clazz in classes) {
            checkClass(clazz, uiStateInterfaces)
        }
    }

    /**
     * Every class in the file, nested ones included.
     *
     * A UI state's variants are usually nested inside the sealed interface that declares
     * them, so a top-level-only walk would miss the very classes this rule is about.
     */
    private fun collectClasses(root: KtFile): List<KtClass> {
        val classes = mutableListOf<KtClass>()
        root.accept(
            object : KtTreeVisitorVoid() {
                override fun visitClass(klass: KtClass) {
                    classes.add(klass)
                    super.visitClass(klass)
                }
            },
        )
        return classes
    }

    /**
     * Names of the interfaces in this file that a UI state class implements.
     *
     * Two shapes qualify: a sealed interface (this repo's `sealed interface *UiState`), and
     * any interface named `*UiState`. Both are recognised only when they are declared in the
     * same file, so the rule never has to resolve a type across files to make a call.
     */
    private fun uiStateInterfaceNames(classes: List<KtClass>): Set<String> =
        classes.filter { it.isInterface() && isUiStateInterface(it) }
            .mapNotNull { it.name }
            .toSet()

    /** True for a sealed interface, or one whose name ends with `UiState`. */
    private fun isUiStateInterface(clazz: KtClass): Boolean =
        clazz.hasModifier(KtTokens.SEALED_KEYWORD) || clazz.name?.endsWith("UiState") == true

    /**
     * Checks one class: a ViewModel, or a class that implements a UI state interface.
     */
    private fun checkClass(clazz: KtClass, uiStateInterfaces: Set<String>) {
        val isViewModel = isViewModelClass(clazz)
        val isUiState = isUiStateImplementation(clazz, uiStateInterfaces)
        if (!isViewModel && !isUiState) return

        val owner = clazz.name ?: "class"
        for (property in clazz.body?.declarations?.filterIsInstance<KtProperty>().orEmpty()) {
            if (mentionsTextFieldState(property.typeReference)) {
                report(property, owner)
            } else if (isViewModel && storesCreatedTextFieldState(property)) {
                report(property, owner)
            }
        }
        for (parameter in constructorParameters(clazz)) {
            if (mentionsTextFieldState(parameter.typeReference)) {
                report(parameter, owner)
            }
        }
    }

    /**
     * True for a class that is a ViewModel: one named `*ViewModel`, or one that extends the
     * MVI base class.
     *
     * Name-based, like the other ViewModel rules in this module: PSI has no type resolution
     * here, so the inheritance check is by the simple name of the supertype.
     */
    private fun isViewModelClass(clazz: KtClass): Boolean {
        if (clazz.name?.endsWith("ViewModel") == true) return true
        return clazz.superTypeListEntries.any { rawSuperName(it) in MVI_BASE_TYPES }
    }

    /** True for a class that implements one of the file's UI state interfaces. */
    private fun isUiStateImplementation(clazz: KtClass, uiStateInterfaces: Set<String>): Boolean {
        if (clazz.isInterface()) return false
        return clazz.superTypeListEntries.any { rawSuperName(it) in uiStateInterfaces }
    }

    /** The primary constructor's parameters, plus any secondary constructor's. */
    private fun constructorParameters(clazz: KtClass): List<KtParameter> =
        clazz.primaryConstructor?.valueParameters.orEmpty() +
            clazz.secondaryConstructors.flatMap { it.valueParameters }

    /**
     * The simple name of a supertype, without generic arguments and without the package.
     *
     * The raw name is compared because the type reference text also carries the generic
     * arguments (`MviViewModel<State, Intent, Event>`), so an exact match never fires.
     */
    private fun rawSuperName(entry: KtSuperTypeListEntry): String? =
        entry.typeReference?.text?.substringBefore('<')?.substringAfterLast('.')?.trim()

    /**
     * True when the declared type mentions `TextFieldState`.
     *
     * The word boundary keeps `MyTextFieldState` and `rememberBridgedTextFieldState` out:
     * the first is a different type, the second is a call, not a type.
     */
    private fun mentionsTextFieldState(type: KtTypeReference?): Boolean {
        val text = type?.text ?: return false
        return TEXT_FIELD_STATE_TYPE.containsMatchIn(text)
    }

    /**
     * True for a property that stores the result of `rememberTextFieldState()`.
     *
     * The creation call is the sanctioned way to make one; storing it in a ViewModel is the
     * defect. Only the inferred-type form is checked — a property with a declared
     * `TextFieldState` type is already caught by [mentionsTextFieldState].
     */
    private fun storesCreatedTextFieldState(property: KtProperty): Boolean {
        if (property.typeReference != null) return false
        val initializer = property.initializer?.text ?: return false
        return CREATION_CALLS.any { it in initializer }
    }

    /** Reports the offending declaration, naming the symbol and the alternative. */
    private fun report(declaration: KtProperty, owner: String) {
        report(
            Finding(
                entity = Entity.from(declaration),
                message = "`${declaration.name}` holds a `TextFieldState` in `$owner`. " +
                    "A `TextFieldState` belongs to the composable that owns the field: keep a " +
                    "plain `String` in the ViewModel's UI state and let the composable own the " +
                    "`TextFieldState`, bridged by `rememberBridgedTextFieldState(...)`. A " +
                    "mutable buffer inside an immutable state is a second source of truth for " +
                    "the same text.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    /** Reports an offending constructor parameter, naming the symbol and the alternative. */
    private fun report(declaration: KtParameter, owner: String) {
        report(
            Finding(
                entity = Entity.from(declaration),
                message = "`${declaration.name}` takes a `TextFieldState` in `$owner`. " +
                    "A `TextFieldState` belongs to the composable that owns the field: keep a " +
                    "plain `String` in the ViewModel's UI state and let the composable own the " +
                    "`TextFieldState`, bridged by `rememberBridgedTextFieldState(...)`. A " +
                    "mutable buffer inside an immutable state is a second source of truth for " +
                    "the same text.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    private companion object {

        /** The ViewModel base classes whose subclasses are ViewModels whatever their name. */
        val MVI_BASE_TYPES = setOf("MviViewModel", "DraftMviViewModel")

        /** A declared type mentioning `TextFieldState`, nullable or in a collection. */
        val TEXT_FIELD_STATE_TYPE = Regex("""\bTextFieldState\b""")

        /** The composable-only factories whose result must not be stored in a ViewModel. */
        val CREATION_CALLS = listOf("rememberTextFieldState(", "rememberBridgedTextFieldState(")
    }
}

/**
 * Registers [NoTextFieldStateInViewModelRule] in the `no-text-field-state-in-viewmodel` rule
 * set.
 */
class NoTextFieldStateInViewModelProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-text-field-state-in-viewmodel")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoTextFieldStateInViewModel") to { cfg: Config ->
                NoTextFieldStateInViewModelRule(cfg)
            },
        ),
    )
}
