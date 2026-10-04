package com.singularity.todo.detekt

import dev.detekt.api.RuleSetProvider
import org.junit.jupiter.api.Assumptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-checks `config/detekt/detekt.yml` against the registered [RuleSetProvider]s.
 *
 * Two independent failure modes, both of which make a rule dormant while every other
 * check passes:
 *
 * 1. **A configured name that does not exist.** `detekt.yml` says
 *    `no-direct-dispatchers: { NoDirectDispatchers: active: true }` but no provider
 *    supplies `NoDirectDispatchers`. detekt reports nothing and never complains.
 * 2. **A registered name that is never configured.** The provider supplies the rule and
 *    the service file lists the provider, but `detekt.yml` has no block for its rule set,
 *    so detekt never loads it. This is how `no-direct-dispatchers` and
 *    `user-scoped-repository` stayed dormant until 2026-10-05 — both were implemented,
 *    packaged and registered, and neither had ever run.
 *
 * `scripts/check-detekt-registrations.sh` covers the *source* side (every `ruleSetId` has
 * a block). This covers the *config* side, from the other direction and with the actual
 * rule names rather than the rule set ids.
 *
 * The test is skipped rather than failed when the config is absent, so the module still
 * tests standalone.
 */
class DetektConfigWiringTest {

    private val configFile: File? = repoRoot().resolve("config/detekt/detekt.yml").takeIf { it.isFile }

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        return dir
    }

    /** Rule set id -> rule names detekt will look for, parsed from the top-level blocks. */
    private fun configuredRules(): Map<String, Set<String>> {
        val text = configFile!!.readText()
        val result = mutableMapOf<String, Set<String>>()
        var current: String? = null
        for (line in text.lines()) {
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (!line.startsWith(" ") && !line.startsWith("\t") && line.trimEnd().endsWith(":")) {
                current = line.trim().removeSuffix(":")
                result[current] = mutableSetOf()
            } else if (current != null && line.startsWith("  ") && !line.startsWith("   ")) {
                val name = line.trim().removeSuffix(":")
                if (name.isNotEmpty() && !name.contains(" ")) {
                    result[current] = result.getValue(current) + name
                }
            }
        }
        return result
    }

    /** Rule set id -> rule names the registered providers actually supply. */
    private fun providedRules(): Map<String, Set<String>> = PROVIDERS.associate { provider ->
        val set = provider.instance()
        provider.ruleSetId.value to set.rules.keys.map { it.value }.toSet()
    }

    @Test
    fun `every configured rule name is supplied by a registered provider`() {
        Assumptions.assumeTrue(configFile != null, "config/detekt/detekt.yml not present")
        val provided = providedRules()

        val unknown = mutableListOf<String>()
        for ((ruleSetId, ruleNames) in configuredRules()) {
            val supplied = provided[ruleSetId]
            if (supplied == null) {
                // A rule set detekt knows about that is not ours (complexity, style, …).
                continue
            }
            for (name in ruleNames) {
                if (name !in supplied) unknown += "$ruleSetId.$name"
            }
        }
        if (unknown.isNotEmpty()) {
            fail(
                "detekt.yml configures rule(s) that no registered provider supplies: " +
                    "${unknown.joinToString()}. detekt will silently not run them.",
            )
        }
    }

    @Test
    fun `every provided custom rule has a block in detekt yml`() {
        Assumptions.assumeTrue(configFile != null, "config/detekt/detekt.yml not present")
        val configured = configuredRules()

        val unconfigured = mutableListOf<String>()
        for ((ruleSetId, ruleNames) in providedRules()) {
            val block = configured[ruleSetId]
            if (block == null) {
                unconfigured += "$ruleSetId (whole rule set, ${ruleNames.size} rule(s))"
                continue
            }
            for (name in ruleNames) {
                if (name !in block) unconfigured += "$ruleSetId.$name"
            }
        }
        if (unconfigured.isNotEmpty()) {
            fail(
                "registered rule(s) with no detekt.yml block, so detekt never loads them: " +
                    "${unconfigured.joinToString()}. " +
                    "A rule that is implemented, packaged and registered but not configured " +
                    "has never run.",
            )
        }
    }

    @Test
    fun `every provider supplies rules that construct without throwing`() {
        Assumptions.assumeTrue(configFile != null, "config/detekt/detekt.yml not present")
        // Instantiating every provider's rules catches a Rule that throws on construction.
        for (provider in PROVIDERS) {
            val rules = provider.instance().rules
            assertTrue(rules.isNotEmpty(), "${provider.ruleSetId.value} supplies no rules")
            for ((name, factory) in rules) {
                factory(EMPTY_CONFIG)
            }
        }
    }

    private companion object {
        val EMPTY_CONFIG = dev.detekt.test.TestConfig()

        /** Mirrors META-INF/services/dev.detekt.api.RuleSetProvider. */
        val PROVIDERS: List<RuleSetProvider> = listOf(
            AppErrorCodeProvider(),
            KDocEnforcementRulesProvider(),
            MviViewModelRulesProvider(),
            NoCombineSideEffectProvider(),
            NoDirectClockSystemProvider(),
            NoDirectDispatchersRuleProvider(),
            NoEmptyOnClickLambdaProvider(),
            NoFactoryViewModelProvider(),
            NoOpUpdateStateProvider(),
            NoRealDelayInTestRuleProvider(),
            NoRunBlockingProvider(),
            NoRunCatchingInSuspendProvider(),
            NoStateInRuleProvider(),
            NoStaticProfileAwareCurrentUserProvider(),
            NoSwallowedCancellationProvider(),
            NoDivergentScopeAndReporterProvider(),
            NoViewModelScopeInProductionProvider(),
            PassThroughUseCaseProvider(),
            UserScopedRepositoryRulesProvider(),
        )
    }
}
