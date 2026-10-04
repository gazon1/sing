---
name: singularity-todo-detekt-rules-authoring
description: How to write and register custom detekt rules in the Singularity Todo project. Covers the provider-as-inner-class pattern, ServiceLoader registration, AST-based rule writing, and testing. Use when adding a new custom lint rule or modifying existing rules.
---

# Detekt Rules Authoring

## Architecture

Custom rules live in the `detekt-rules/` module (a separate Gradle subproject). Rules are loaded via Java ServiceLoader — `META-INF/services/dev.detekt.api.RuleSetProvider` file registers all rule sets.

**Key convention:** The `RuleSetProvider` is an **inner class** inside the rule `.kt` file, not a separate file. This keeps rule + registration together and ensures they stay in sync.

```
detekt-rules/src/main/kotlin/com/singularity/todo/detekt/
  NoRealDelayInTestRule.kt       ← rule class + provider (inner class)
  NoViewModelScopeInProductionRule.kt  ← rule class + provider (inner class)
  NoRunBlockingRule.kt           ← rule class + provider (inner class)
META-INF/services/dev.detekt.api.RuleSetProvider  ← lists all provider class names
```

## The Provider-as-Inner-Class Pattern

```kotlin
package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtCallExpression

/** Your rule docstring */
class MyCustomRule(config: Config) : Rule(config, "", null) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        // Rule logic here
    }
}

/** Registers [MyCustomRule] in the custom rule set. */
class MyCustomRuleProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("my-custom-rules")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("MyCustom") to { cfg: Config -> MyCustomRule(cfg) },
        ),
    )
}
```

**Key points:**
- Provider is inside the same `.kt` file as the rule
- Provider class name ends with `Provider`
- Provider implements `RuleSetProvider`
- `ruleSetId` is a string identifier for the rule set (kebab-case recommended)
- `RuleName` is the individual rule name within the set

## Registering in ServiceLoader

Add the provider's **fully qualified class name** to:
`detekt-rules/src/main/resources/META-INF/services/dev.detekt.api.RuleSetProvider`

One class name per line:
```
com.singularity.todo.detekt.NoRealDelayInTestRuleProvider
com.singularity.todo.detekt.NoViewModelScopeInProductionRuleProvider
com.singularity.todo.detekt.NoRunBlockingRuleProvider
com.singularity.todo.detekt.MyCustomRuleProvider
```

**Important:** When you add a new provider, you must add the line here. If the line is missing, the rule set is silently not loaded.

## Activation checklist (all three, or the rule silently does nothing)

A rule that compiles but is not registered/activated produces **zero findings and
zero errors** — it looks working. Every new rule must pass all three steps:

1. **ServiceLoader entry** (see above).
2. **detekt.yml ruleset block** — detekt skips custom rulesets that have no config
   block, even when ServiceLoader loads them:
   ```yaml
   no-runblocking:        # ← must match the provider's ruleSetId exactly
     NoRunBlocking:       # ← must match the RuleName exactly
       active: true
   ```
3. **Positive control** — create a temp file containing one intentional violation,
   run `./gradlew :shared:detekt`, confirm exactly 1 finding in
   `shared/build/reports/detekt/detekt.xml` (`source="detekt.<RuleName>"`), then
   delete the temp file.
4. **A permanent test in `detekt-rules/src/test/`.** Step 3 is a one-time check and
   it decays: the temp file is gone, and the next person has nothing to break. Every
   rule class needs a test that asserts the rule fires on a violation *and* stays
   quiet on the non-violations. Run it with `./gradlew :detekt-rules:test`.

Historical: `no-runblocking` + `no-viewmodel-scope` sat inactive for days because
step 2 was missed (see `2026-09-26-preflight-retro-findings`, R1).

**The KDoc is a claim, not evidence.** Every rule defect found in the 2026-10-04
audit (see `2026-10-04-rule-verifiability-inventory`) was invisible to code review
because the KDoc was plausible and the implementation was quietly *narrower* than
the prose. A rule can compile, be registered, be activated, report zero findings,
and have never once looked at the code it claims to police. When the prose and the
implementation disagree, the test run decides — not your reading of the PSI.

Five traps this repository has actually fallen into:

| Trap | Symptom | Correct shape |
|---|---|---|
| Selector assumed to be a call | `Dispatchers.IO` is a *property*; `as? KtCallExpression` returns null and the rule never fires | handle both `KtNameReferenceExpression` and `KtCallExpression` |
| Primary-constructor property | `private val repo: Repo` in the constructor is a `KtParameter`, not a `KtProperty` — `declarations.filterIsInstance<KtProperty>()` misses it | check `primaryConstructorParameters` too |
| `root.declarations` is top-level only | a class nested in an `object` is never visited | walk the tree (`KtTreeVisitorVoid`) |
| Name filter excludes the subclass | `endsWith("Repository")` never matches `RepoImpl` | match both suffixes |
| Documented opt-in that does not exist | an annotation that appears nowhere in the repo reads to an agent as permission, then fails to compile | use a visible `@Suppress("RuleName")` with a reason |

5. **`./gradlew --stop` after editing an existing rule.** The Gradle daemon caches the
   resolved detekt plugin classpath, so a change to a rule that already runs is invisible
   until the daemon restarts. A marker string added to a finding message kept printing the
   old text across `--rerun-tasks` and `--no-configuration-cache`, and appeared on the
   first try under `--no-daemon`. Without this, "the rule does not work" and "the daemon
   is serving the old class" are indistinguishable. See
   `2026-09-28-detekt-daemon-and-crashing-rule`.

6. **Assert the exact count, not just "> 0".** `NoRealDelayInTest` reported
   `Thread.sleep` from both `visitCallExpression` and `visitDotQualifiedExpression`,
   double-counting every occurrence. A test asserting `assertTrue(findings.isNotEmpty())`
   would have passed. Use `assertEquals(1, findings.size)`.

7. **An inactive rule is not a broken rule.** `NoRunCatchingInSuspend` is
   `active: false` on purpose, pending a 239-site migration. Before "fixing" a rule
   that reports nothing, check `active:` in `config/detekt/detekt.yml` and read the
   KDoc — an audit once listed it as vacuous when it was merely switched off.

**Never use inline Kotlin-compiler PSI helpers in a detekt plugin.**
`psiUtil.collectDescendantsOfType` and friends are `inline`, so the synthetic
`$inlined$…` class fails to load inside detekt's classloader and throws
`NoClassDefFoundError`. Because the exception escapes the rule, `:shared:detekt` fails
**and writes no report**, leaving the previous run's file on disk — which reads as a
clean pass. Walk `node.children` explicitly instead; see
`2026-09-28-detekt-daemon-and-crashing-rule`.

**A failed build task's report is not evidence.** When a rule throws, or the task fails
for any other reason, the report on disk is the previous run's. Check the task outcome
before reading it.

## Registering the same rule twice

Steps 1–3 above assume nobody else registered the rule while you worked. In parallel
work — a worktree, a second agent, two branches off the same base — this is the failure
that gets through review:

- both changes add **the same single line** in **different places** in the file, so the
  diff is two innocuous insertions and reads as fine;
- the merge produces a duplicate `RuleSetProvider` line and a duplicate
  `no-<rule>:` block in `detekt.yml`;
- the build then fails with `found duplicate key <rule-set>` — a YAML error pointing at
  the config file, minutes into a Gradle run, with nothing in it about the real cause.

It happened here on 2026-09-28: `NoFactoryViewModel` was wired independently in the
`doc-and-skills` sprint and in `fix/mvi-no-op-update-state`, and the merge broke
`./gradlew :shared:detekt`.

So, before committing a registration:

```bash
./scripts/check-detekt-registrations.sh
```

It fails on a duplicated provider, a duplicated `detekt.yml` key, a provider listed but
not present in the source, and a provider declared but not listed. It runs in
`check.sh` (step 0, before Gradle) and as the first line of `just lint`, because a
seconds-long check is worth far more here than a multi-minute failure with a misleading
message.

If it reports a duplicate, keep **one** registration and move any explanatory comment
onto the survivor — the comment is usually the only record of why a rule was dormant.

## Writing AST-Based Rules

### Common PSI visitors

```kotlin
// Visit a function call (e.g., delay(100))
override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    val callee = expression.calleeExpression as? KtNameReferenceExpression
    if (callee?.text == "delay") {
        // inspect arguments
    }
}

// Visit a dot-qualified expression (e.g., viewModelScope.launch { ... })
override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
    super.visitDotQualifiedExpression(expression)
    val selector = expression.selectorExpression as? KtCallExpression
    // inspect
}

// Visit a reference expression (e.g., viewModelScope)
override fun visitNameReferenceExpression(expression: KtNameReferenceExpression) {
    super.visitNameReferenceExpression(expression)
    if (expression.text == "viewModelScope") {
        // report
    }
}
```

### Reporting a finding

```kotlin
report(
    Finding(
        entity = Entity.from(expression),
        message = "Descriptive message explaining the violation.",
        references = emptyList(),
        suppressReasons = emptyList(),
    ),
)
```

### Checking arguments

```kotlin
val argument = expression.valueArguments.firstOrNull()
val valueText = argument?.getArgumentExpression()?.text
val value = valueText?.toLongOrNull()
```

## Testing Rules

### Use the `Path` overload of `compileContentForTest`

This is the single most expensive trap in this module, measured 2026-10-05:

```kotlin
compileContentForTest(code, "com.example")          // KtFile.declarations == [KtScript]
compileContentForTest(code, Path.of("Fixture.kt"))  // KtFile.declarations == [KtClass]
```

The string overload wraps the content in a `KtScript`, so `root.declarations` is
`[KtScript]` and **no `KtClass` is ever seen**. Every rule that iterates
`root.declarations` — both KDoc rules, `MviViewModelExt`, `ProhibitUserIdInObserve` —
reports nothing under it.

The failure is asymmetric, which is what makes it dangerous: a *positive* test fails
loudly, which is only annoying, but a *negative* test passes **vacuously**, because "no
findings" is exactly what a mis-compiled fixture produces. Use the `Path` overload always.

The `Path` overload also **discards the directory component** — `virtualFilePath` comes
back as `/X.kt` for any input. So a rule that branches on file path cannot be tested
through the PSI layer at all. Extract its decision into a pure function and test that:

```kotlin
internal object MyRulePolicy {
    fun violation(filePath: String, ...): String? = ...
}
```

Both fixed rules (`NoDirectDispatchersPolicy`, `NoStaticProfileAwareCurrentUserPolicy`)
use this shape, as does `NoDirectClockSystemRule`'s `isAllowedPath`.

### Every rule needs one positive test

See Common Mistake 2b. A rule with only negative tests cannot be distinguished from a
rule that never runs.

### Rules declared `private` are tested through their provider

Several rules are `private` in their own file, so a test cannot construct them. Go
through the `RuleSetProvider` by name, which also verifies the name detekt looks up is
actually wired to a rule:

```kotlin
val rule = KDocEnforcementRulesProvider().instance().rules[RuleName("ViewModelMustHaveKDoc")]!!.invoke(TestConfig())
```

### Writing the fixture

Match the rule's real preconditions or you will "discover" a working rule is broken.
Observed while writing these tests: a class must be named `…Repository` (not `…Impl`); a
property read via `typeReference` needs an explicit type (inference gives `null`); a
receiver resolved from `declarations.filterIsInstance<KtProperty>()` must be a declared
property, not a primary-constructor `val`.

```kotlin
class NoRealDelayInTestRuleTest {
    private val rule = NoRealDelayInTestRule(Config.empty)

    @Test
    fun `delay 100 reports finding`() {
        val code = """
            import kotlinx.coroutines.delay
            fun test() = runTest {
                delay(100)
            }
        """.trimIndent()

        val findings = rule.compileAndVisit(code)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("delay(100)"))
    }

    @Test
    fun `delay 0 does not report`() {
        val code = """
            import kotlinx.coroutines.delay
            fun test() = runTest {
                delay(0)
            }
        """.trimIndent()

        val findings = rule.compileAndVisit(code)
        assertEquals(0, findings.size)
    }
}
```

**Helper for compilation:**
```kotlin
fun Rule.compileAndVisit(code: String): List<Finding> {
    val psiFile = compile(code)
    val findings = mutableListOf<Finding>()
    rule.visit(psiFile, findings)
    return findings
}

fun Rule.visit(file: PsiFile, findings: MutableList<Finding>) {
    file.accept(object : PsiElementVisitor() {
        // Walk PSI tree and call rule's visit methods
    })
}
```

**Simpler approach:** Use the detekt test utils from `dev.detekt.test`:

```kotlin
import dev.detekt.test.TestConfig
import dev.detekt.test.lint

@Test
fun `reports violation`() {
    val findings = lint(
        """
            import kotlinx.coroutines.delay
            fun test() = runTest { delay(100) }
        """,
        config = TestConfig(),
        ruleSetId = "no-real-delay-in-test",
    )
    assertEquals(1, findings.size)
}
```

## Rule Severity

By default, rules are **warning** severity. To promote to **error** (fails the build):

1. Set `ignoreFailures = false` in `shared/build.gradle.kts`
2. Run the baseline generation: `./gradlew :shared:detektBaseline`
3. Commit the updated baseline
4. Any NEW violations will now fail the build

**Recommended approach:** Start at warning severity. Only promote after the baseline is stable and intentional violations are the exception, not the norm.

## File Location

All custom rules go in:
```
detekt-rules/src/main/kotlin/com/singularity/todo/detekt/
```

**Do NOT put custom rules in `shared/src/`** — they belong in the dedicated `detekt-rules` module so they can be tested in isolation and applied to any module that uses detekt.

## `NoRealDelayInTestRule` — Threshold Guidance

The rule bans `delay(N > 1)` in test sources. The threshold is **500ms**:

| Delay value | Action |
|---|---|
| `delay(0)` | Always allowed — yield point, not real time |
| `delay(1..499)` | Allowed — review if necessary |
| `delay(500+)` | **Reports** — use `advanceUntilIdle()` or `advanceTimeBy()` |

**Why 500ms?** `stateIn(WhileSubscribed(5000))` uses a 5000ms debounce. Any test needing `delay(500+)` to wait for a collector is not using virtual time correctly.

**Legitimate exception (document with a comment):**
```kotlin
// Fan-out: FakeTextGen dispatches on Default
delay(50)
```

**Fix (no comment needed):**
```kotlin
// Before: delay(100)
// After:
advanceUntilIdle()  // drains all pending coroutines
// or for debounce:
advanceTimeBy(300L)  // advances virtual time by 300ms
```

## Existing Rules (as of 2026-10-04)

| Rule | File | RuleSet ID | What it checks | Test |
|------|------|------------|----------------|------|
| `NoRealDelayInTestRule` | `NoRealDelayInTestRule.kt` | `no-real-delay-in-test` | `delay(N>500)` and `Thread.sleep`, including `1_000` / `1000L` spellings | `NoRealDelayInTestRuleTest` |
| `NoDirectDispatchersRule` | `NoDirectDispatchersRule.kt` | `no-direct-dispatchers` | `Dispatchers.IO/Default/Main` in commonMain; whitelists `core/log/FileLogWriter.kt` | `NoDirectDispatchersRuleTest` |
| `NoEmptyOnClickLambdaRule` | `NoEmptyOnClickLambdaRule.kt` | `no-empty-onclick-lambda` | `onClick = {}` at call sites and `onClick ?: { }` elvis fallbacks | `NoEmptyOnClickLambdaRuleTest` |
| `NoViewModelScopeInProductionRule` | `NoViewModelScopeInProductionRule.kt` | `no-viewmodel-scope` | `viewModelScope.launch/async/cancel` in production | `NoViewModelScopeInProductionRuleTest` |
| `NoRunBlockingRule` | `NoRunBlockingRule.kt` | `no-runblocking` | `runBlocking` in production | `NoRunBlockingRuleTest` |
| `NoStateInRule` | `NoStateInRule.kt` | `no-state-in` | `.stateIn(...)` in production VMs. **No opt-in hatch** — use `@Suppress("NoStateIn")` with a reason. | `NoStateInRuleTest` |
| `NoStaticProfileAwareCurrentUserRule` | `NoStaticProfileAwareCurrentUserRule.kt` | `no-static-profile-aware-current-user` | static/global `ProfileAwareCurrentUser` | — |
| `NoCombineSideEffectRule` | `NoCombineSideEffectRule.kt` | `no-combine-side-effect` | `.value =`, `seed()`, `Channel.send`, `launchIn` inside a `combine { }` transform. Restored 2026-09-27 after the 2026-09-26 orphan cleanup. | `NoCombineSideEffectRuleTest` |
| `PassThroughUseCaseRule` | `PassThroughUseCaseRule.kt` | `pass-through-use-case` | `UseCase` method whose body is a single `repo.x()` call. Resolves the receiver through **both** body properties and primary-constructor `val`s. | `PassThroughUseCaseRuleTest` |
| `KDocEnforcementRules` | `KDocEnforcementRules.kt` | `kdoc-enforcement` | `ViewModelMustHaveKDoc`, `RepositoryInterfaceMustHaveKDoc`. Walks the full PSI tree, so nested classes count. | **none** |
| `NoFactoryViewModelRule` | `NoFactoryViewModelRule.kt` | `no-factory-viewmodel` | `factory { *ViewModel(...) }` / `factoryOf(::*ViewModel)` | `NoFactoryViewModelRuleTest` |
| `NoOpUpdateStateRule` | `NoOpUpdateStateRule.kt` | `no-op-update-state` | `updateState { }` whose lambda returns the receiver unchanged | `NoOpUpdateStateRuleTest` |
| `MviViewModelRulesProvider` | `MviViewModelRulesProvider.kt` | `mvi-viewmodel` | `VmScopePosition`, `VmCloseable`, `ShadowedState` | `MviViewModelRulesTest` |
| `NoRunCatchingInSuspend` | `NoRunCatchingInSuspend.kt` | `no-run-catching-in-suspend` | `runCatching` in suspend. **`active: false` on purpose** — do not "fix"; the 239-site migration comes first. | `NoRunCatchingInSuspendTest` |
| `NoSwallowedCancellation` | `NoSwallowedCancellation.kt` | `no-swallowed-cancellation` | `catch` blocks that swallow `CancellationException` | `NoSwallowedCancellationTest` |
| `ProhibitUserIdInObserve` | `UserScopedRepositoryRulesProvider.kt` | `user-scoped-repository` | `userId`/`scopedUserId` on `watch*`/`observe*` returning `Flow`. Matches `…Repository` **and** `…RepositoryImpl`. | — |
| `NoDirectClockSystemRule` | `NoDirectClockSystemRule.kt` | `no-direct-clock-system` | direct `Clock.System` references | `NoDirectClockSystemRuleTest` |

Run them all with `./gradlew :detekt-rules:test` (76 tests). The `-- Test`
column is the honest measure of which rules you can trust without writing a
violating file first — and it is what the 2026-10-04 inventory used to find six
defects. See `2026-10-04-rule-verifiability-inventory`.

`NoCombineSideEffectRule` and `NoGlobalScopeLaunchRule` existed at one point and were
removed in `2026-09-26-detekt-rules-activation-audit`; do not re-add them without a
finding the existing `mvi-viewmodel` rules do not cover.

> **This table is a claim, not a guarantee.** It was stale for a day: it still listed
> `NoCombineSideEffectRule` after the 2026-09-26 activation audit had deleted that file as an
> orphan, and listed `NoGlobalScopeLaunchRule`, which was deleted in the same pass and has not
> been restored. Before planning around a rule, confirm the `.kt` file, the ServiceLoader
> entry, and the `detekt.yml` block all exist — and grep `docs/decisions/` for a removal first.

## Common Mistakes

### 1. Provider not in ServiceLoader
**Symptom:** Rule doesn't fire, no errors. Run with `--debug` to see loaded providers.

### 1b. Provider registered twice (the mirror-image failure)
**Symptom:** `found duplicate key <rule-set>` during a Gradle run — a YAML error that
points at `config/detekt/detekt.yml` and says nothing about the cause. Happens when the
rule was wired in two branches in parallel: the diff shows the same line added twice in
different places, which reads as harmless.
**Fix:** `./scripts/check-detekt-registrations.sh`, then keep one registration and move
any explanatory comment onto it. See *Registering the same rule twice* above.

### 2. Wrong PSI class for the node type
Use `KtNameReferenceExpression` for bare names, `KtCallExpression` for function calls with `()`, `KtDotQualifiedExpression` for `receiver.member()`.

### 2b. A guard that can never be satisfied — the rule is a no-op

The worst version of the above: the cast is *correct for the code you imagined* and wrong
for the code that exists. The rule compiles, registers, packages, gets a `detekt.yml`
block, and is cited in a backlog entry — and reports nothing, ever. Two real instances,
both fixed 2026-10-05:

- `NoDirectDispatchersRule` required `selectorExpression as? KtCallExpression`. But
  `Dispatchers.IO`'s selector is a `KtNameReferenceExpression`, because `IO` is a
  *property*, not a call. And in `Dispatchers.IO.limitedParallelism(1)` the receiver is
  itself dot-qualified. Both possible shapes hit an early `return`.
- `NoStaticProfileAwareCurrentUserRule` compared a `KtNameReferenceExpression`'s text to a
  **dotted fully-qualified name**. A bare identifier never contains dots, so it can never
  match; and the two spellings that would match a dotted name have a
  `KtDotQualifiedExpression` receiver and returned even earlier.

The defence is one positive test — feed a known violation, require a finding:

```kotlin
@Test fun `MyRule fires on the thing it bans`() {
    val found = rule.visitFile(compileContentForTest(code, Path.of("Fixture.kt")), settings)
    assertTrue(found.isNotEmpty(), "rule reported nothing for a known violation")
}
```

Rules whose only tests are negative assertions (`assertEquals(0, findings.size)`) cannot
distinguish "correctly ignores this" from "never runs" — a rule that never fires passes
every one of them. At least one positive assertion per rule is the minimum, not a nicety.

If a guard looks unsatisfiable, **print the PSI and check**, do not reason about it:

```kotlin
println("PROBE recv=${e.receiverExpression::class.simpleName} text='${e.receiverExpression.text}'")
```

Reading the source and guessing the tree shape is what produced both misses.

### 3. Reporting outside test sources
Rules that should only apply to test sources must check the file path via `Entity.from(expression).file.path`. Alternatively, configure path filters in `detekt.yml`.

### 4. Mutable Rule state
Rules are instantiated once and reused across many files. Do not store mutable state in the rule instance that persists across files.

## See Also

- `singularity-todo-quality-tools` — how to run detekt, auto-fix, and generate baselines
- `docs/decisions/2026-09-25-detekt-test-rules.md` — ADR for the first two test rules

### Gradle daemon caches detekt plugin classloaders

After editing a rule, a live Gradle daemon may keep executing the OLD rule classes —
detekt reports stale findings and debug code never runs. Always run
`./gradlew --stop` (or `--no-daemon`) after rebuilding `detekt-rules`, then rerun.
Symptom: jar contains new logic (verify with `strings`), report unchanged.
