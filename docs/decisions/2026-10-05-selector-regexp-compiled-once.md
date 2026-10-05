---
title: "Selector.Regexp compiles once per selector, and what it actually costs"
date: 2026-10-05
tags: [agenda, performance, selectors, dsl]
status: accepted
---

## Context

`SelectorMatcher` compiled the selector's pattern inside the per-task predicate:

```kotlin
is Selector.Regexp -> query.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(task.title)
```

`AgendaEvaluator.evaluate` asks each section's selector about every task, so the
same pattern was rebuilt thousands of times per emission to produce an identical
result. The sibling `TaskFilter.ByRegexp` does it correctly — it pushes the
pattern to SQL (`watchByRegexp`), where SQLite compiles it once — which is what
made the in-memory version look like an oversight rather than a choice.

I had ranked this as "the top remaining defect". **That ranking was wrong, and
the measurement is why.**

## Idea

Compile once per selector instance, and reuse.

## Decision

`Selector.Regexp` gained a body property:

```kotlin
data class Regexp(val query: String) : Selector {
    val compiled: Regex by lazy { query.toRegex(RegexOption.IGNORE_CASE) }
}
```

and the matcher reads `compiled` instead of `query`.

A **body** property, not a constructor one, so it stays out of `equals`,
`hashCode`, `copy` and the serialised form. That matters more than usual here: a
selector is a *value* that saved agenda views persist, and a memo that leaked
into equality would change how stored rules compare. `Regex` is immutable and
thread-safe, so one instance shared across evaluations is safe.

## Rationale — including the part that does not flatter the change

Measured at n=8000 with warm-up excluded, comparing "compile inside the loop"
against "compile once, then match":

| pattern | per-task compile | match only | ratio |
|---|---|---|---|
| `^Weekly` (anchored literal) | 38.2 ms | 1.99 ms | **19×** |
| `^(fix\|feat\|refactor)(...)?[:\s]+\S+` | 67.8 ms | 14.9 ms | 4.6× |
| `^[A-Z]{2,4}-\d{1,5}\b.*\p{L}+$` | 68.8 ms | 7.2 ms | 9.5× |
| `(\w+)\s+\1` | 114.2 ms | 126.4 ms | ~1× (worse) |

Two things follow, and the second is the honest one.

**The ratio is real** — up to 19× on the common case. Compilation dominated
matching, so removing it removes most of the work.

**The absolute cost was never a stall.** ~68 ms at 8000 tasks, worst realistic
case. Compare the blocking fix in
`2026-10-05-blocking-resolved-per-list-not-per-task`: 8409 ms. This is **124×
smaller**. At realistic task counts it is a few milliseconds and nobody would
ever have noticed it.

So this is a **minor optimisation, not a defect**. I said otherwise before
measuring, on the strength of a structural argument ("same shape as the
quadratic bug") that turned out not to imply a similar magnitude. The shape was
right; the severity was invented.

It is still worth doing — the change is three lines, semantics are provably
identical, and it removes genuine repeated work. But it should not have been
first on the list, and the reason it was is the useful part of this record.

## Consequences

- A saved selector compiles once, on first use, and reuses thereafter. A
  deserialized selector compiles on demand — the memo is not transported.
- `SelectorRegexpCompiledTest` pins the invariant that makes this safe: after
  compiling, two selectors with the same query are still equal, `copy` still
  works, and the serialised form still contains no `compiled` key.
- **A caller constructing a fresh `Selector.Regexp` per task defeats the memo.**
  Nothing prevents that; it is the same shape of mistake as the original, one
  level up. A detekt rule would catch it (see
  `2026-10-05-detekt-rules-for-slow-paths`).
- The backreference pattern got *slower* with hoisting — 114 ms → 126 ms. That
  is noise on a path where matching, not compiling, dominates. It is recorded so
  the next reader does not assume the ratio holds universally.

## Links

- `feature/agenda/domain/model/Selector.kt` — `Regexp.compiled`
- `feature/agenda/domain/selector/SelectorMatcher.kt` — uses it
- `commonTest/.../SelectorRegexpCompiledTest.kt` — value-semantics guard
- `2026-10-05-blocking-resolved-per-list-not-per-task` — the same shape, 124× the cost
- `2026-10-05-detekt-rules-for-slow-paths` — the rule that would make both structural