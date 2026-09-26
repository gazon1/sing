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
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenExpression

private class MviViewModelExtRule(config: Config) : Rule(config, "", null) {
    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        if (!root.name.endsWith("ViewModel.kt")) return
        for (declaration in root.declarations) {
            if (declaration is KtClass) checkClass(declaration)
        }
    }

    private fun checkClass(clazz: KtClass) {
        // Check if already extends MviViewModel
        val extendsMvi = clazz.superTypeListEntries.any { entry ->
            entry is KtSuperTypeCallEntry && entry.typeReference?.text == "MviViewModel"
        }
        if (extendsMvi) return

        val classBody = clazz.body ?: return
        val properties = classBody.properties

        val hasStateFlow = properties.any { prop ->
            prop.typeReference?.text?.contains("MutableStateFlow") == true
        }

        val hasEventChannel = properties.any { prop ->
            val typeText = prop.typeReference?.text ?: return@any false
            typeText.contains("Channel") || typeText.contains("MutableSharedFlow")
        }

        if (hasStateFlow && hasEventChannel) {
            report(
                Finding(
                    entity = Entity.from(clazz),
                    message = "ViewModel manages MutableStateFlow + Channel/MutableSharedFlow but does not extend MviViewModel. " +
                        "Migrate to MviViewModel for unified event/state handling.",
                    references = emptyList(),
                    suppressReasons = emptyList(),
                ),
            )
        }
    }
}

private class IntentMethodNameRule(config: Config) : Rule(config, "", null) {
    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        if (!root.name.endsWith("ViewModel.kt")) return
        for (declaration in root.declarations) {
            if (declaration is KtClass) checkClass(declaration)
        }
    }

    private fun checkClass(clazz: KtClass) {
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

    private fun isIntentHandler(fun_: KtFunction): Boolean {
        val params = fun_.valueParameters
        if (params.size != 1) return false
        val paramType = params[0].typeReference?.text ?: return false
        val isIntentParam = paramType.endsWith("Intent") || paramType.endsWith("UiEvent")
        if (!isIntentParam) return false
        val body = fun_.bodyExpression ?: return false
        val paramName = params[0].name ?: return false
        return hasWhenWithIsCheck(body, paramName)
    }

    private fun hasWhenWithIsCheck(expr: KtExpression, paramName: String): Boolean {
        return when (expr) {
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
}

private class VmScopePositionRule(config: Config) : Rule(config, "", null) {
    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        if (!root.name.endsWith("ViewModel.kt")) return
        for (declaration in root.declarations) {
            if (declaration is KtClass) checkClass(declaration)
        }
    }

    private fun checkClass(clazz: KtClass) {
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

private class VmCloseableRule(config: Config) : Rule(config, "", null) {
    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        if (!root.name.endsWith("ViewModel.kt")) return
        for (declaration in root.declarations) {
            if (declaration is KtClass) checkClass(declaration)
        }
    }

    private fun checkClass(clazz: KtClass) {
        val primaryConstructor = clazz.primaryConstructor
        if (primaryConstructor == null) return
        val paramRefs = primaryConstructor.valueParameters
        val hasScope = paramRefs.any { it.name == "scope" }
        if (!hasScope) return

        // Skip StatefulViewModel/MviViewModel/DraftMviViewModel subclasses — they
        // manage scope lifecycle internally (addCloseable in the base init).
        // Supertype text includes generic args (e.g. "MviViewModel<S, I, E>"), so
        // compare the raw-name prefix, not the whole text.
        val mviBaseNames = setOf("StatefulViewModel", "MviViewModel", "DraftMviViewModel")
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

class MviViewModelRulesProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("mvi-viewmodel")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("MviViewModelExt") to { cfg: Config -> MviViewModelExtRule(cfg) },
            RuleName("IntentMethodName") to { cfg: Config -> IntentMethodNameRule(cfg) },
            RuleName("VmScopePosition") to { cfg: Config -> VmScopePositionRule(cfg) },
            RuleName("VmCloseable") to { cfg: Config -> VmCloseableRule(cfg) },
        ),
    )
}
