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
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile

private class ViewModelMustHaveKDocRule(config: Config) : Rule(config, "", null) {
    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        for (declaration in root.declarations) {
            if (declaration is KtClass) checkClass(declaration)
        }
    }

    private fun checkClass(clazz: KtClass) {
        val name = clazz.name ?: return
        if (!name.endsWith("ViewModel")) return
        
        
        if (hasKDoc(clazz)) return
        report(
            Finding(
                entity = Entity.atName(clazz),
                message = "ViewModel '$name' has no class-level KDoc. " +
                    "Add a KDoc block describing this ViewModel's responsibility, " +
                    "UiState sealed subclass, and Intent/Event sealed subclass.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    private fun hasKDoc(declaration: KtDeclaration): Boolean {
        val docText = declaration.docComment?.text
        return !docText.isNullOrBlank()
    }
}

private class RepositoryInterfaceMustHaveKDocRule(config: Config) : Rule(config, "", null) {
    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        for (declaration in root.declarations) {
            if (declaration is KtClass) checkClass(declaration)
        }
    }

    private fun checkClass(clazz: KtClass) {
        val name = clazz.name ?: return
        if (!name.endsWith("Repository")) return
        if (!clazz.isInterface()) return
        if (hasKDoc(clazz)) return
        report(
            Finding(
                entity = Entity.atName(clazz),
                message = "Repository interface '$name' has no class-level KDoc. " +
                    "Document the repository's responsibility, key methods, and error semantics.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    private fun hasKDoc(declaration: KtDeclaration): Boolean {
        val docText = declaration.docComment?.text
        return !docText.isNullOrBlank()
    }
}

class KDocEnforcementRulesProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("kdoc-enforcement")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("ViewModelMustHaveKDoc") to { cfg: Config -> ViewModelMustHaveKDocRule(cfg) },
            RuleName("RepositoryInterfaceMustHaveKDoc") to { cfg: Config -> RepositoryInterfaceMustHaveKDocRule(cfg) },
        ),
    )
}
