---
name: singularity-todo-domain-logic-pattern
description: Documents the pattern for pure business-logic classes placed in feature/X/domain/logic/. These are side-effect-free, deterministic, fully unit-tested in commonTest without any mocking infrastructure. Examples: RecurrenceCalculator, RecurrenceParser, DependencyValidator, Computed. Covers: what belongs here, what doesn't, testing conventions, and the fake-friendly architecture.
---

# Pure Domain Logic — `feature/X/domain/logic/` Pattern

## What belongs in `domain/logic/`

Files in `feature/X/domain/logic/` contain **pure functions with no side effects**. They are:

- **Side-effect-free** — no I/O, no network, no database, no `Clock.now()`
- **Deterministic** — same inputs → same outputs, always
- **Fully unit-testable** in `commonTest` without any mocks, fakes, or platform dependencies
- **Thread-safe** — no mutable state (or state is encapsulated and immutable)

```
feature/<feature>/domain/logic/
├── RecurrenceCalculator.kt   — nextOccurrence(), missedCount() — pure date arithmetic
├── RecurrenceParser.kt       — parse(String) → RecurrenceSpec — pure parsing
├── DependencyValidatorImpl.kt — assertNoSelfLoop() — pure validation
├── Computed.kt               — isBlocked(), isCompleted() — pure computed properties
└── AgendaEvaluator.kt        — evaluate() — pure filtering/aggregation
```

## What does NOT belong here

| ❌ NOT here | ✅ Instead |
|---|---|
| Anything that calls `Clock.now()` | Pass `Instant`/`LocalDate` as a parameter |
| Anything that touches a database | Repository interface in `domain/port/` |
| Anything that launches coroutines | `UseCase` in `domain/usecase/` |
| Anything with `@Composable` | `presentation/` |
| Platform-specific logic | `androidMain`/`jvmMain` with expect/actual |

## The canonical pattern

```kotlin
/**
 * Pure [RecurrenceSpec] calculator.
 *
 * @see RecurrenceParser for the inverse operation (string → spec).
 */
object RecurrenceCalculator {

    fun nextOccurrence(spec: RecurrenceSpec, anchor: LocalDate): LocalDate { ... }

    fun missedCount(spec: RecurrenceSpec, anchor: LocalDate, today: LocalDate): Int { ... }
}
```

**No state.** No `Clock`. No `UserId`. No `CoroutineScope`. Everything it needs arrives as a parameter.

## Testing in `commonTest`

Pure logic tests go in `commonTest` alongside the source:

```
shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/domain/logic/
├── RecurrenceParserTest.kt      — 27 test cases
├── RecurrenceCalculatorTest.kt   — 14 test cases
└── ComputedIsBlockedTest.kt    — dependency logic
```

**Test shape — no fakes needed:**

```kotlin
class RecurrenceCalculatorTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate(y, m, day)

    @Test
    fun `Interval WEEK advances by that many weeks`() {
        val spec = Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK)
        assertEquals(d(2026, 1, 22), RecurrenceCalculator.nextOccurrence(spec, d(2026, 1, 15)))
    }

    @Test
    fun `missedCount caps at MAX_MISSED`() {
        val spec = Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.DAY)
        assertEquals(10, RecurrenceCalculator.missedCount(spec, d(2026, 1, 1), d(2026, 12, 31)))
    }
}
```

**Rule**: `commonTest` tests have **zero dependencies** on `test/fakes/`. If you need a fake to test pure logic, the logic is not pure — move it to a `UseCase` or reconsider the architecture.

## RecurrenceParser — a complete example

The parser demonstrates all key aspects of the pattern:

```kotlin
// Grammar rules in order — ordered choice with backtracking
private fun parseRule(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int> {
    parseCatchUp(tokens, pos)?.checkFull() ?: return parseYearlyDate(tokens, pos)?.checkFull()
        ?: return parseEveryWeekday(tokens, pos)?.checkFull() ?: ...
    throw IllegalArgumentException("Unrecognised token at position $pos")
}

private fun Pair<RecurrenceSpec, Int>?.checkFull(size: Int): RecurrenceSpec? =
    this?.takeIf { second == size }?.first
```

**Key design decisions:**
- **Tokenizer** produces a flat `List<Token>` — simple, fast, debuggable
- **Ordered choice** — first rule that consumes all tokens wins (no ambiguity)
- **`Pair<Spec, Int>?` return type** — `null` = rule didn't match, `Int` = new position
- **`IllegalArgumentException`** for parse errors (user-facing, matches `require()` contract)
- **Only `kotlinx.datetime`** — no platform APIs, works in `commonMain`

## FakeTaskRepository — for testing VMs that USE pure logic

When a `ViewModel` depends on a pure-logic object (e.g. `CompleteRecurringTaskUseCase` depends on `RecurrenceCalculator`), the VM test uses `FakeTaskRepository` + a **real** calculator:

```kotlin
class CompleteRecurringTaskUseCaseTest {
    private val repo = FakeTaskRepository()
    private val clock = FakeClock(...)
    private val zone = TimeZone.of("UTC")
    private val calculator = RecurrenceCalculator  // ← real, no fake needed

    private val useCase = CompleteRecurringTaskUseCase(repo, clock, zone, calculator)
}
```

`FakeTaskRepository` doesn't simulate `recurrence` or `dependsOn` loading from extras — it stores `Task` objects directly. For testing tasks with `tags` and `dependsOn`, `TaskExtrasLoadingTest` validates the production flow via `observeAll()`.

## Common pitfalls

### 1. Passing `Clock` into pure logic

**❌ Wrong:**
```kotlin
object RecurrenceCalculator(private val clock: Clock) {
    fun nextOccurrence(spec: RecurrenceSpec): LocalDate {
        val today = clock.now().toLocalDateTime(zone).date  // clock leak
    }
}
```

**✅ Correct:**
```kotlin
object RecurrenceCalculator {
    fun nextOccurrence(spec: RecurrenceSpec, anchor: LocalDate): LocalDate {
        // clock is the caller's responsibility
    }
}
```

The `Clock` goes into the `UseCase` or `ViewModel`, which computes the current time and passes `LocalDate` to pure functions.

### 2. Mutable state in calculators

**❌ Wrong:**
```kotlin
object BadCalculator {
    private var cache = mutableMapOf<...>()  // not thread-safe, not deterministic
}
```

**✅ Correct:**
All state is passed as parameters or is immutable (e.g. `List.copyOf()`, `Map.toMap()`).

### 3. Throwing from inside a tokenizer/parser lambda

```kotlin
// ❌ WRONG — throw inside when expression doesn't work as expected
val x = when (val c = input[i]) { '!' -> true }  // when returns Boolean, not Unit

// ✅ Correct — use explicit if or when with return
when {
    c == '!' -> { tokens.add(Token.Bang(i)); i++ }
}
```

### 4. Partial match accepted by ordered-choice parser

If a rule matches only part of the input (e.g. `every mon` matching "Mon" but leaving ",wed,fri" unconsumed), the ordered-choice parser must **fail** so the next rule tries. Always check `pos == tokens.size` (full consumption) before returning:

```kotlin
private fun parseEveryWeekday(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int>? {
    // ... parse weekdays ...
    return Pair(Weekly(RecurrenceBase.FROM_DUE, days), p)
    // Caller in parseRule checks: if (p == tokens.size) return result
    // Otherwise, this rule failed → try next rule
}
```

## When to use expect/actual vs commonMain

| Situation | Pattern |
|---|---|
| Logic uses only `kotlinx.datetime` or pure Kotlin | `commonMain` — single source |
| Logic needs platform APIs (file I/O, system clock) | `expect`/`actual` — separate per platform |
| Logic is a thin wrapper over a platform API | Port interface in `commonMain`, impl in `androidMain`/`jvmMain` |

`RecurrenceParser` uses only `kotlinx.datetime` + string manipulation → `commonMain`. No `expect`/`actual` needed.

## Related Skills

- `singularity-todo-feature-scaffold` — where `domain/logic/` sits in the layered structure
- `singularity-todo-test-helpers` — `FakeTaskRepository` for VM tests that exercise pure logic
- `singularity-todo-coroutine-scopes` — where to put `Clock` and background scopes
- `singularity-todo-pure-formatters` — pure string formatting (UI-layer pure functions)
