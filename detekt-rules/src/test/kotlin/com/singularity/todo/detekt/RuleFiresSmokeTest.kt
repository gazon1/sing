package com.singularity.todo.detekt

import dev.detekt.api.RuleName
import java.nio.file.Path
import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * "Can this rule fire at all?" smoke tests for the seven rules that had no test file.
 *
 * ## Why this file exists
 *
 * `NoDirectDispatchersRule` shipped registered, packaged, given a `detekt.yml` block, and
 * referenced by a backlog entry — and could not report a single finding for any input.
 * It required the dot-qualified selector to be a `KtCallExpression`, but in
 * `Dispatchers.IO` the selector is a `KtNameReferenceExpression` (`IO` is a property).
 * Both possible PSI shapes hit an early `return`.
 *
 * Two of its four tests had been failing since they were written. The two that passed
 * asserted the rule *stays quiet*, which a rule that never fires satisfies trivially.
 * Nobody read the result, because no CI job ran `:detekt-rules:test`.
 *
 * The lesson is not specific to that rule: **a rule with no positive test is an
 * unverified claim that it works.** These tests each feed one canonical violating snippet
 * and require a finding. A filter with no positive assertion cannot distinguish "correct"
 * from "never runs".
 *
 * Each test is intentionally minimal — it answers one question (does the rule fire?) and
 * nothing more. Branch coverage for these rules is still owed; see the ADR.
 *
 * @see 2026-10-05-no-direct-dispatchers-rule-was-a-no-op
 */
class RuleFiresSmokeTest {

    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    /**
     * Compile with the **Path** overload, never the `(content, packageName)` one.
     *
     * Measured on 2026-10-05: the string overload wraps the content in a `KtScript`, so
     * `KtFile.declarations` is `[KtScript]` and any rule that iterates `root.declarations`
     * — ViewModelMustHaveKDoc, RepositoryInterfaceMustHaveKDoc, MviViewModelExt,
     * ProhibitUserIdInObserve — sees no classes and reports nothing.
     *
     * That failure mode is the dangerous one: a *negative* test on such a rule passes
     * vacuously, because "no findings" is exactly what a mis-compiled fixture produces.
     * Only a positive assertion distinguishes a working rule from a broken fixture, which
     * is the entire reason this file exists.
     */
    private fun <T : dev.detekt.api.Rule> findings(rule: T, code: String) =
        rule.visitFile(
            compileContentForTest(code.trimIndent(), Path.of("Fixture.kt")),
            languageSettings,
        )

    /**
     * Resolve a rule through its [dev.detekt.api.RuleSetProvider] by name.
     *
     * Several rules are declared `private` in their own file, so a test cannot construct
     * them directly. Going through the provider is the correct public path, and it checks
     * one more thing for free: that the name detekt will look up is actually wired to a
     * rule. A rule registered under a name detekt never asks for is as dormant as a rule
     * that cannot fire.
     */
    private fun rule(
        provider: dev.detekt.api.RuleSetProvider,
        name: dev.detekt.api.RuleName,
    ): dev.detekt.api.Rule {
        val rules = provider.instance().rules
        val factory = requireNotNull(rules[name]) {
            "rule set '${provider.ruleSetId}' does not provide '$name'; it has ${rules.keys}"
        }
        return factory(TestConfig())
    }

    private fun assertFires(
        rule: dev.detekt.api.Rule,
        code: String,
        label: String,
    ) {
        val found = findings(rule, code)
        assertTrue(
            found.isNotEmpty(),
            "$label reported nothing for a known violation. Either the snippet is wrong " +
                "or the rule cannot fire — check its PSI guards before assuming the former. " +
                "Findings: $found",
        )
    }

    // ── NoStateInRule ─────────────────────────────────────────────────────────────

    @Test
    fun `NoStateIn fires on stateIn in a production VM`() {
        assertFires(
            NoStateInRule(TestConfig()),
            """
            package com.example

            class AgendaViewModel {
                val state = flow.stateIn(scope, SharingStarted.Eagerly, UiState.Loading)
            }
            """,
            "NoStateIn",
        )
    }

    // ── NoViewModelScopeInProductionRule ──────────────────────────────────────────

    @Test
    fun `NoViewModelScopeInProduction fires on viewModelScope launch`() {
        assertFires(
            NoViewModelScopeInProductionRule(TestConfig()),
            """
            package com.example

            class AgendaViewModel {
                fun init() {
                    viewModelScope.launch { load() }
                }
            }
            """,
            "NoViewModelScopeInProduction",
        )
    }

    // ── PassThroughUseCaseRule ────────────────────────────────────────────────────

    @Test
    fun `PassThroughUseCase fires on a thin Repository wrapper`() {
        assertFires(
            PassThroughUseCaseRule(TestConfig()),
            """
            package com.example

            class GetTasksUseCase {
                private val repository: TaskRepository = TaskRepository()

                suspend fun execute(): List<Task> = repository.getAll()
            }
            """,
            "PassThroughUseCase",
        )
    }

    // ── NoRealDelayInTestRule ─────────────────────────────────────────────────────

    @Test
    fun `NoRealDelayInTest fires on delay above the threshold`() {
        assertFires(
            NoRealDelayInTestRule(TestConfig()),
            """
            package com.example

            suspend fun waitForSomething() {
                delay(1000)
            }
            """,
            "NoRealDelayInTest",
        )
    }

    // ── NoStaticProfileAwareCurrentUserRule ───────────────────────────────────────

    @Test
    fun `NoStaticProfileAwareCurrentUser fires on the companion accessor`() {
        // All three spellings of the banned access must be caught. Before 2026-10-05 the
        // rule compared a KtNameReferenceExpression's text to a dotted FQN, so all three
        // were excluded: the bare-name form never equalled the FQN, and the FQN and
        // `.Companion` forms have dot-qualified receivers and returned even earlier.
        assertFires(
            NoStaticProfileAwareCurrentUserRule(TestConfig()),
            """
            package com.example

            import com.singularity.todo.feature.profile.ProfileAwareCurrentUser

            fun userId(): UserId = ProfileAwareCurrentUser.current
            """,
            "NoStaticProfileAwareCurrentUser (simple name)",
        )
    }

    @Test
    fun `NoStaticProfileAwareCurrentUser fires on the fully-qualified form`() {
        assertFires(
            NoStaticProfileAwareCurrentUserRule(TestConfig()),
            """
            package com.example

            fun userId(): UserId =
                com.singularity.todo.feature.profile.ProfileAwareCurrentUser.scopedUserId
            """,
            "NoStaticProfileAwareCurrentUser (fully qualified)",
        )
    }

    @Test
    fun `NoStaticProfileAwareCurrentUser fires on the Companion form`() {
        assertFires(
            NoStaticProfileAwareCurrentUserRule(TestConfig()),
            """
            package com.example

            import com.singularity.todo.feature.profile.ProfileAwareCurrentUser

            fun userId(): UserId = ProfileAwareCurrentUser.Companion.scopedUserId
            """,
            "NoStaticProfileAwareCurrentUser (.Companion)",
        )
    }

    @Test
    fun `NoStaticProfileAwareCurrentUser allows constructor injection`() {
        val rule = NoStaticProfileAwareCurrentUserRule(TestConfig())
        val found = findings(
            rule,
            """
            package com.example

            class Screen {
                private val currentUser: ProfileAwareCurrentUser = ProfileAwareCurrentUser()
            }
            """,
        )
        assertTrue(
            found.isEmpty(),
            "constructor injection is the sanctioned form, but got $found",
        )
    }

    // ── KDocEnforcementRules ──────────────────────────────────────────────────────

    @Test
    fun `ViewModelMustHaveKDoc fires on an undocumented ViewModel class`() {
        assertFires(
            rule(KDocEnforcementRulesProvider(), RuleName("ViewModelMustHaveKDoc")),
            """
            package com.example

            class AgendaViewModel {
                fun load() {}
            }
            """,
            "ViewModelMustHaveKDoc",
        )
    }

    @Test
    fun `RepositoryInterfaceMustHaveKDoc fires on an undocumented Repository interface`() {
        assertFires(
            rule(KDocEnforcementRulesProvider(), RuleName("RepositoryInterfaceMustHaveKDoc")),
            """
            package com.example

            interface TaskRepository {
                suspend fun getAll(): List<Task>
            }
            """,
            "RepositoryInterfaceMustHaveKDoc",
        )
    }

    // ── MviViewModelRulesProvider ─────────────────────────────────────────────────

    @Test
    fun `MviViewModelExt fires on a MviViewModel missing the mvi extension`() {
        assertFires(
            rule(MviViewModelRulesProvider(), RuleName("MviViewModelExt")),
            """
            package com.example

            // Explicit type references are required: the rule reads
            // prop.typeReference?.text, which is null under type inference, so an
            // inferred `= MutableStateFlow(...)` escapes the rule entirely.
            class AgendaViewModel {
                private val _state: MutableStateFlow<UiState> = MutableStateFlow(UiState.Loading)
                private val _effects: MutableSharedFlow<Effect> = MutableSharedFlow()
            }
            """,
            "MviViewModelExt",
        )
    }

    // ── UserScopedRepositoryRulesProvider ─────────────────────────────────────────

    @Test
    fun `ProhibitUserIdInObserve fires on a userId parameter of observe`() {
        assertFires(
            ProhibitUserIdInObserveRule(TestConfig()),
            """
            package com.example

            interface TaskRepository {
                fun observeTasks(userId: UserId): Flow<List<Task>>
            }
            """,
            "ProhibitUserIdInObserve",
        )
    }
}

// ── The four MviViewModel rules that had no test at all ──────────────────────────
//
// Kover put these at 0% (38, 32, 18 and 17 uncovered lines) while the audit reported
// "28/28 ViewModels use the injected AutoCloseableCoroutineScope" — the rules that
// enforce exactly that were entirely unverified. They are the four that guard the
// project's canonical VM shape, so a no-op here would be the most expensive kind.

class MviViewModelRuleFiresTest {

    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun rule(name: dev.detekt.api.RuleName) =
        MviViewModelRulesProvider().instance().rules[name]!!.invoke(TestConfig())

    private fun findings(rule: dev.detekt.api.Rule, code: String) =
        rule.visitFile(
            compileContentForTest(code.trimIndent(), Path.of("Fixture.kt")),
            languageSettings,
        )

    private fun assertFires(rule: dev.detekt.api.Rule, code: String, label: String) {
        val found = findings(rule, code)
        assertTrue(found.isNotEmpty(), "$label reported nothing for a known violation: $found")
    }

    @Test
    fun `IntentMethodName fires on a misnamed intent handler`() {
        assertFires(
            rule(RuleName("IntentMethodName")),
            """
            package com.example

            class AgendaViewModel {
                fun onWhatever(intent: AgendaIntent) = when (intent) {
                    is Load -> 1
                    else -> 2
                }
            }
            """,
            "IntentMethodName",
        )
    }

    @Test
    fun `IntentMethodName allows onIntent`() {
        val found = findings(
            rule(RuleName("IntentMethodName")),
            """
            package com.example

            class AgendaViewModel {
                fun onIntent(intent: AgendaIntent) = when (intent) {
                    is Load -> 1
                    else -> 2
                }
            }
            """,
        )
        assertTrue(found.isEmpty(), "onIntent is the sanctioned name, got $found")
    }

    @Test
    fun `VmScopePosition fires when scope is not the last constructor parameter`() {
        assertFires(
            rule(RuleName("VmScopePosition")),
            """
            package com.example

            class AgendaViewModel(
                private val scope: CoroutineScope,
                private val deps: Deps,
            )
            """,
            "VmScopePosition",
        )
    }

    @Test
    fun `VmScopePosition allows scope last`() {
        val found = findings(
            rule(RuleName("VmScopePosition")),
            """
            package com.example

            class AgendaViewModel(
                private val deps: Deps,
                private val scope: CoroutineScope,
            )
            """,
        )
        assertTrue(found.isEmpty(), "scope last is the canonical shape, got $found")
    }

    @Test
    fun `VmCloseable fires when a scope parameter is never closed`() {
        assertFires(
            rule(RuleName("VmCloseable")),
            """
            package com.example

            class AgendaViewModel(
                private val deps: Deps,
                private val scope: CoroutineScope,
            ) {
                init {
                    load()
                }
            }
            """,
            "VmCloseable",
        )
    }

    @Test
    fun `VmCloseable allows addCloseable in the init block`() {
        val found = findings(
            rule(RuleName("VmCloseable")),
            """
            package com.example

            class AgendaViewModel(
                private val deps: Deps,
                private val scope: CoroutineScope,
            ) {
                init {
                    addCloseable(scope)
                }
            }
            """,
        )
        assertTrue(found.isEmpty(), "addCloseable(scope) is the canonical shape, got $found")
    }

    @Test
    fun `ShadowedState fires on a VM that redeclares MviViewModel state`() {
        assertFires(
            rule(RuleName("ShadowedState")),
            """
            package com.example

            class AgendaViewModel : MviViewModel<UiState, Intent>(initialState = UiState.Loading) {
                private val uiState: MutableStateFlow<UiState> = MutableStateFlow(UiState.Loading)
            }
            """,
            "ShadowedState",
        )
    }

    @Test
    fun `ShadowedState allows updateState usage`() {
        val found = findings(
            rule(RuleName("ShadowedState")),
            """
            package com.example

            class AgendaViewModel : MviViewModel<UiState, Intent>(initialState = UiState.Loading) {
                fun refresh() {
                    updateState { it.copy(loading = false) }
                }
            }
            """,
        )
        assertTrue(found.isEmpty(), "updateState is the sanctioned form, got $found")
    }
}
