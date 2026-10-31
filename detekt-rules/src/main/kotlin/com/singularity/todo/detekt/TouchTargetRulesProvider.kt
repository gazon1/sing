package com.singularity.todo.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class TouchTargetRulesProvider : RuleSetProvider {
    override val ruleSetId: String = "touch-target"

    override fun instance(config: Config) = RuleSet(
        ruleSetId,
        listOf(
            NoSmallFixedSizeWithoutTouchTargetRule(config),
        ),
    )
}
