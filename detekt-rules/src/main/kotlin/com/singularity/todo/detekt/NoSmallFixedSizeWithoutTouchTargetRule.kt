package com.singularity.todo.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtFile

class NoSmallFixedSizeWithoutTouchTargetRule(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Warning,
        "Touch targets below 48.dp must use minimumInteractiveComponentSize/touchTarget.",
        Debt.FIVE_MINS,
    )

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        if (file.name.endsWith("Preview.kt")) return
    }
}
