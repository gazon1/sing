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
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenExpression

/** Bans a ViewModel managing its own `MutableStateFlow` instead of the base's single state source. */
internal class MviViewModelExtRule(config: Config) : Rule(config, "", null) {
    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        for (declaration in root.declarations) {
            if (declaration is KtClass) checkClass(declaration)
        }
    }

    private fun checkClass(clazz: KtClass) {
        if (!isViewModelClass(clazz)) return

        // Check if already extends MviViewModel. Compare the RAW name — the type
        // reference text also carries the generic arguments ("MviViewModel<State, …>"),
        // so an exact match never fired and this early-return was dead.
        val extendsMvi = clazz.superTypeListEntries.any { entry ->
            entry is KtSuperTypeCallEntry && entry.typeReference?.text?.substringBefore('<') == "MviViewModel"
        }
        if (extendsMvi) return

        val classBody = clazz.body ?: return
        val properties = classBody.properties

        val hasStateFlow = properties.any { mentionsFlowType(it, "MutableStateFlow") }
        val hasEventChannel = properties.any {
            mentionsFlowType(it, "Channel") || mentionsFlowType(it, "MutableSharedFlow")
        }

        if (hasStateFlow && hasEventChannel) {
            report(
                Finding(
                    entity = Entity.from(clazz),
                    message = "ViewModel manages MutableStateFlow + " +
                        "Channel/MutableSharedFlow but does not extend MviViewModel. " +
                        "Migrate to MviViewModel for unified event/state handling.",
                    references = emptyList(),
                    suppressReasons = emptyList(),
                ),
            )
        }
    }
}

/** Bans an intent handler named anything but `onIntent`, so the MVI entry point has one name. */
internal class IntentMethodNameRule(config: Config) : Rule(config, "", null) {
    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        for (declaration in root.declarations) {
            if (declaration is KtClass) checkClass(declaration)
        }
    }

    private fun checkClass(clazz: KtClass) {
        if (!isViewModelClass(clazz)) return

        val classBody = clazz.body ?: return
        for (declaration in classBody.functions) {
            if (isIntentHandler(declaration)) {
                val name = declaration.name
                if (name != null && name != "onIntent") {
                    report(
                        Finding(
                            entity = Entity.from(declaration),
                            message = "Intent handler method is named '$name' but should be 'onIntent'.",
                            references = emptyList(),
                            suppressReasons = emptyList(),
                        ),
                    )
                }
            }
        }
    }

    private fun isIntentHandler(fn: KtFunction): Boolean {
        val params = fn.valueParameters
        if (params.size != 1) return false
        val paramType = params[0].typeReference?.text ?: return false
        val isIntentParam = paramType.endsWith("Intent") || paramType.endsWith("UiEvent")
        if (!isIntentParam) return false
        val body = fn.bodyExpression ?: return false
        val paramName = params[0].name ?: return false
        return hasWhenWithIsCheck(body, paramName)
    }

    private fun hasWhenWithIsCheck(expr: KtExpression, paramName: String): Boolean = when (expr) {
        is KtWhenExpression -> {
            expr.subjectExpression?.text == paramName &&
                expr.entries.any { entry ->
                    entry.conditions.any { it is KtWhenConditionIsPattern }
                }
        }

        else -> {
            var result = false
            expr.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                override fun visitWhenExpression(expression: KtWhenExpression) {
                    if (expression.subjectExpression?.text == paramName &&
                        expression.entries.any { it.conditions.any { c -> c is KtWhenConditionIsPattern } }
                    ) {
                        result = true
                    }
                    super.visitWhenExpression(expression)
                }
            })
            result
        }
    }
}

/** Bans a `scope` parameter that is not last, so injected dependencies read in declaration order. */
internal class VmScopePositionRule(config: Config) : Rule(config, "", null) {
    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        for (declaration in root.declarations) {
            if (declaration is KtClass) checkClass(declaration)
        }
    }

    private fun checkClass(clazz: KtClass) {
        if (!isViewModelClass(clazz)) return

        val primaryConstructor = clazz.primaryConstructor ?: return
        // Get parameters via valueParameters
        val paramRefs = primaryConstructor.valueParameters
        val scopeParamRef = paramRefs.find {
            it.name == "scope"
        } ?: return
        val lastParamRef = paramRefs.lastOrNull() ?: return
        if (scopeParamRef != lastParamRef) {
            report(
                Finding(
                    entity = Entity.from(scopeParamRef),
                    message = "scope parameter should be the last constructor parameter.",
                    references = emptyList(),
                    suppressReasons = emptyList(),
                ),
            )
        }
    }
}

/** Requires a ViewModel with a `scope` parameter to call `addCloseable(scope)`, or its scope is never cancelled. */
internal class VmCloseableRule(config: Config) : Rule(config, "", null) {
    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        for (declaration in root.declarations) {
            if (declaration is KtClass) checkClass(declaration)
        }
    }

    private fun checkClass(clazz: KtClass) {
        if (!isViewModelClass(clazz)) return

        val primaryConstructor = clazz.primaryConstructor
        if (primaryConstructor == null) return
        val paramRefs = primaryConstructor.valueParameters
        val hasScope = paramRefs.any { it.name == "scope" }
        if (!hasScope) return

        // Skip MviViewModel/DraftMviViewModel subclasses — they
        // manage scope lifecycle internally (addCloseable in the base init).
        // Supertype text includes generic args (e.g. "MviViewModel<S, I, E>"), so
        // compare the raw-name prefix, not the whole text.
        val mviBaseNames = setOf("MviViewModel", "DraftMviViewModel")
        val extendsBase = clazz.superTypeListEntries.any { entry ->
            val rawName = entry.typeReference?.text?.substringBefore('<')
            rawName in mviBaseNames
        }
        if (extendsBase) return

        val classBody = clazz.body
        if (classBody == null) {
            reportNoInitBlock(clazz)
            return
        }

        // Find init block among declarations
        val initBlock = classBody.declarations.filterIsInstance<KtClassInitializer>().firstOrNull()
        if (initBlock == null) {
            reportNoInitBlock(clazz)
            return
        }

        val initBody = initBlock.body
        if (initBody == null) return
        val bodyText = initBody.text
        val callsAddCloseable = bodyText.contains("addCloseable") && bodyText.contains("scope")

        if (!callsAddCloseable) {
            report(
                Finding(
                    entity = Entity.from(initBlock),
                    message = "ViewModel has a scope parameter but init block does not call addCloseable(scope).",
                    references = emptyList(),
                    suppressReasons = emptyList(),
                ),
            )
        }
    }

    private fun reportNoInitBlock(clazz: KtClass) {
        report(
            Finding(
                entity = Entity.from(clazz),
                message = "ViewModel has a scope parameter but no init block calls addCloseable(scope).",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }
}

/**
 * Flags a class that extends [MviViewModel] but declares its own `uiState` / `_uiState`.
 *
 * Such a class has two sources of truth: the base class's `state` (which the base owns
 * and the event channel is paired with) and a shadow flow the screen actually reads.
 * Nothing reports the drift — `onCleared`, `updateState` and the state contract all
 * apply to the inherited `state` while the UI renders the other one.
 *
 * Name-based on purpose, matching the existing [IntentMethodName] rule: PSI has no
 * type inference here, so comparing against the inherited generic argument is not
 * reliably possible, and a broader heuristic would flag the legitimate side-flows
 * that VMs use as `combine` inputs.
 */

internal class ShadowedStateRule(config: Config) : Rule(config, "", null) {
    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        for (declaration in root.declarations) {
            if (declaration is KtClass) checkClass(declaration)
        }
    }

    private fun checkClass(clazz: KtClass) {
        if (!isViewModelClass(clazz)) return

        val extendsMvi = clazz.superTypeListEntries.any { entry ->
            entry is KtSuperTypeCallEntry && entry.typeReference?.text?.substringBefore('<') == "MviViewModel"
        }
        if (!extendsMvi) return

        val body = clazz.body ?: return
        val shadow = body.properties.filter { it.name in SHADOW_NAMES }
        if (shadow.isEmpty()) return

        report(
            Finding(
                entity = Entity.from(shadow.first()),
                message = "ViewModel extends MviViewModel but declares its own state (" +
                    "${shadow.joinToString { it.name ?: "?" }}). " +
                    "MviViewModel already owns the state; use updateState { } / setState() " +
                    "and read `state` in the screen.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    private companion object {
        val SHADOW_NAMES = setOf("uiState", "_uiState")
    }
}

class MviViewModelRulesProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("mvi-viewmodel")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("MviViewModelExt") to { cfg: Config -> MviViewModelExtRule(cfg) },
            RuleName("IntentMethodName") to { cfg: Config -> IntentMethodNameRule(cfg) },
            RuleName("VmScopePosition") to { cfg: Config -> VmScopePositionRule(cfg) },
            RuleName("VmCloseable") to { cfg: Config -> VmCloseableRule(cfg) },
            RuleName("ShadowedState") to { cfg: Config -> ShadowedStateRule(cfg) },
        ),
    )
}

/**
 * Matches top-level classes named `*ViewModel`.
 *
 * The rules used to gate on the FILE being named `*ViewModel.kt`, which silently
 * skipped `TaskDetailViewModel` because it lived in `TaskDetail.kt` — a 523-line
 * ViewModel outside every MVI rule, rule for four epic sprints. Gating on the class
 * name matches how `ViewModelMustHaveKDocRule` in `KDocEnforcementRules.kt` already
 * works, and it is the name that actually carries the contract.
 */
private fun isViewModelClass(clazz: KtClass): Boolean =
    clazz.name?.endsWith("ViewModel") == true

/**
 * True when a property's declared type *or its initialiser* mentions [typeName].
 *
 * Reading only `typeReference` was a silent hole: `private val s = MutableStateFlow(x)`
 * has a null typeReference under type inference, so the property escaped the rule. The
 * rule therefore depended on whether the author happened to write a type annotation,
 * which has nothing to do with the thing being policed.
 *
 * The initialiser is a cheap textual check and needs no type resolution. It can also
 * produce a false positive for `val s: State<UiState> = MutableStateFlow(...)`, which is
 * why the declared type is still checked first — see [NoViewModelExtendsMviPolicy].
 */
private fun mentionsFlowType(prop: KtProperty, typeName: String): Boolean {
    prop.typeReference?.text?.let { if (it.contains(typeName)) return true }
    return prop.initializer?.text?.contains(typeName) == true
}
