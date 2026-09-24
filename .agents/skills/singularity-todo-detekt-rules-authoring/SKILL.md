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

Create a test file in `detekt-rules/src/test/kotlin/`:

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

## Existing Rules (for reference)

| Rule | File | RuleSet ID | What it checks |
|------|------|------------|----------------|
| `NoRealDelayInTestRule` | `NoRealDelayInTestRule.kt` | `no-real-delay-in-test` | `delay(N>1)` in test sources |
| `NoViewModelScopeInProductionRule` | `NoViewModelScopeInProductionRule.kt` | `no-viewmodel-scope` | `viewModelScope.launch/async/cancel` in production |
| `NoRunBlockingRule` | `NoRunBlockingRule.kt` | `no-run-blocking` | `runBlocking` in production |

## Common Mistakes

### 1. Provider not in ServiceLoader
**Symptom:** Rule doesn't fire, no errors. Run with `--debug` to see loaded providers.

### 2. Wrong PSI class for the node type
Use `KtNameReferenceExpression` for bare names, `KtCallExpression` for function calls with `()`, `KtDotQualifiedExpression` for `receiver.member()`.

### 3. Reporting outside test sources
Rules that should only apply to test sources must check the file path via `Entity.from(expression).file.path`. Alternatively, configure path filters in `detekt.yml`.

### 4. Mutable Rule state
Rules are instantiated once and reused across many files. Do not store mutable state in the rule instance that persists across files.

## See Also

- `singularity-todo-quality-tools` — how to run detekt, auto-fix, and generate baselines
- `docs/decisions/2026-09-25-detekt-test-rules.md` — ADR for the first two test rules
