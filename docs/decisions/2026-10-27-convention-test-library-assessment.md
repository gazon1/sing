---
title: "Convention test library extraction: premature at this stage"
date: 2026-10-27
status: accepted
tags: [testing, architecture, conventions]
---

# Convention test library extraction: premature at this stage

## Context

After adding `@Tag` enforcement to `SharedHarnessConventionsTest` (retro item 1, PR #705) and the stale-exemption check to `HarnessConventionTest` (retro item 2, PR #705), the question arose of whether the three convention-test files share enough logic to warrant a shared library:

- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SharedHarnessConventionsTest.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/arch/SharedHarnessConventionsTest.kt` (none yet)
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/flows/HarnessConventionTest.kt`

The shared surface would include: regex patterns for `advanceUntilIdle` loops, `runBlocking`, real-time `delay`, and `AutoCloseableCoroutineScope` hand-rolling; the `@Tag` enforcement scanner; and the stale-exemption map pattern.

## Decision

Do not extract a shared convention-test library at this time. Document the assessment findings instead.

## Rationale

**Volume is too low to justify the indirection.** Only three files exist today:
`SharedHarnessConventionsTest` (shared JVM, 6 conventions), `HarnessConventionTest` (desktopApp,
10 conventions). The overlap in logic is the stale-exemption map pattern and the `@Tag`
scanner — roughly 40–60 lines of incidental similarity, not a reusable abstraction.

**The conventions are layer-specific by design.** `HarnessConventionTest` enforces UI-layer
rules (`checkA11y`, scroll-reachability, button-state assertions) that have no meaning outside
the desktop harness. `SharedHarnessConventionsTest` enforces coroutine-test hygiene that has
no meaning in a UI-layer flow test. Merging them into one library would require the library
to know about both layers, defeating the isolation the separate files provide.

**Exemptions are file-specific and not truly shareable.** The exemption maps (`EXEMPT_TAG`,
`EXEMPT_RAW_TAGS`, `EXEMPT_SCROLL`, etc.) are keyed by filename. A shared library would need
to receive the full map from each consumer, at which point the library is only providing the
scanning scaffolding — not the policy. The policy (which files are exempt and why) belongs
with the convention test, not in a generic library.

**The positive-control pattern is trivially copiable.** The `positive control — the scanner
still detects violations` test in `SharedHarnessConventionsTest` is 65 lines of self-contained
regex-and-synthetic-snippet verification. Copying it to a new convention file takes minutes;
introducing a library dependency for it does not.

## Consequences

**Positive:**
- No new inter-module dependency.
- Each convention test remains self-contained and independently readable.
- Exemptions stay close to the conventions that use them.

**Negative:**
- Regex patterns and stale-exemption scaffolding are duplicated across files.
  This is acceptable at a scale of two files; it would become a burden if a third
  or fourth convention test is added.
- If a third convention test (e.g. for `androidApp`) is added, reassess whether the
  pattern has stabilized enough to extract.

## Reassessment Trigger

Revisit this decision when:
- A third convention test file is added to a third module, OR
- The duplicate regex patterns exceed ~100 lines of exact duplication, OR
- A new exemption map type is added that has identical logic in two or more files.

## Links

- PR #705: `SharedHarnessConventionsTest` (6 conventions) + stale-exemption pattern in `HarnessConventionTest`
- `SharedHarnessConventionsTest.kt` — `shared/src/jvmTest/kotlin/com/singularity/todo/arch/`
- `HarnessConventionTest.kt` — `desktopApp/src/jvmTest/kotlin/com/singularity/todo/flows/`
- `TestTagsWiringTest.kt` — source of the `knownUnapplied` exemption-map pattern
