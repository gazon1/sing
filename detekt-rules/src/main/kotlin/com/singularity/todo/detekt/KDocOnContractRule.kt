package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Flags contract-bearing declarations that lack KDoc.
 *
 * "Contract-bearing" means:
 * - `expect`/`actual` declarations (platform boundary)
 * - Interfaces whose name ends in `Repository` (domain boundary)
 * - Classes whose name ends in `ViewModel` (presentation boundary)
 *
 * KDoc is required on these declarations because they define cross-module
 * contracts that callers rely on without reading the implementation.
 *
 * @see KDocOnContractProvider for how this rule is registered.
 */
class KDocOnContractRule(config: Config) : Rule(config, "", null) {

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
    }

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        checkClassOrObject(classOrObject)
    }

    private fun checkClassOrObject(classOrObject: KtClassOrObject) {
        val name = classOrObject.name ?: return
        val modifierText = classOrObject.modifierList?.text ?: ""

        val isRepository = classOrObject is KtClass &&
            classOrObject.getClassOrInterfaceKeyword()?.text == "interface" &&
            name.endsWith("Repository")

        val isViewModel = classOrObject is KtClass &&
            name.endsWith("ViewModel")

        val isExpectActual = modifierText.contains("expect") || modifierText.contains("actual")

        if (!isRepository && !isViewModel && !isExpectActual) return

        if (!hasKDoc(classOrObject)) {
            val kind = when {
                isRepository -> "Repository interface"
                isViewModel -> "ViewModel"
                modifierText.contains("expect") -> "expect class"
                else -> "actual class"
            }
            report(classOrObject, "$kind `$name`")
        }
    }

    private fun checkFunction(function: KtFunction) {
        val name = function.name ?: return
        val modifierText = function.modifierList?.text ?: ""
        if (!modifierText.contains("expect") && !modifierText.contains("actual")) return
        if (!hasKDoc(function)) {
            val kind = if (modifierText.contains("expect")) "expect function" else "actual function"
            report(function, "$kind `$name`")
        }
    }

    private fun checkProperty(property: KtProperty) {
        val name = property.name ?: return
        val modifierText = property.modifierList?.text ?: ""
        if (!modifierText.contains("expect") && !modifierText.contains("actual")) return
        if (!hasKDoc(property)) {
            val kind = if (modifierText.contains("expect")) "expect property" else "actual property"
            report(property, "$kind `$name`")
        }
    }

    private fun checkObject(obj: KtObjectDeclaration) {
        val name = obj.name ?: return
        val modifierText = obj.modifierList?.text ?: ""
        if (!modifierText.contains("expect") && !modifierText.contains("actual")) return
        if (!hasKDoc(obj)) {
            val kind = if (modifierText.contains("expect")) "expect object" else "actual object"
            report(obj, "$kind `$name`")
        }
    }

    /**
     * True when a KDoc comment (`/** ... */`) is attached to [declaration].
     * Uses the Kotlin PSI `getDocComment()` API.
     */
    private fun hasKDoc(declaration: KtNamedDeclaration): Boolean {
        return declaration.getDocComment() != null
    }

    private fun report(declaration: KtNamedDeclaration, description: String) {
        val entity = Entity.atName(declaration)
        report(
            Finding(
                entity = entity,
                message = "Missing KDoc on $description. " +
                    "Contract-bearing declarations require KDoc to document their " +
                    "boundary, inputs, outputs, and error semantics.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }
}
