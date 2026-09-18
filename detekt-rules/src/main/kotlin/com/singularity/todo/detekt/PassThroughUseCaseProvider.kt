package com.singularity.todo.detekt

import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/**
 * Contributes the [PassThroughUseCaseRule] to the shared and desktopApp detekt analysis.
 *
 * Detekt auto-loads all [RuleSetProvider] implementations on the classpath via
 * `ServiceLoader`. By adding `detektPlugins(project(":detekt-rules"))` to a module's
 * `dependencies { }` block, this rule set is included in that module's detekt run.
 *
 * @see PassThroughUseCaseRule for the rule itself.
 */
class PassThroughUseCaseProvider : RuleSetProvider {

    override val ruleSetId: RuleSetId = RuleSetId("pass-through")

    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf(
            RuleName("PassThroughUseCase") to { cfg -> PassThroughUseCaseRule(cfg) },
        ),
    )
}
