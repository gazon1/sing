---
title: "A module split buys the median edit and costs the tail, so it is not done on this evidence"
date: 2026-11-05
status: accepted
tags: ["build", "performance", "kotlin", "modularization"]
---

# A module split buys the median edit and costs the tail, so it is not done on this evidence

## Context

`2026-11-05-kt-62686-is-kmp-jvm-compilation` established that a KMP module's JVM compilation falls back to a full
recompile on any edit (KT-62686, unresolved upstream, affected since 2.2.21). A split cannot remove the fallback, so the
question became whether it could shorten it enough to be worth a module boundary.

Three gates were pre-registered before the numbers were seen (`build-investigation/split-plan/`):

- **H9** — is the fallback tied to the Kotlin version? Measured on 2.4.10, 2.4.20, 2.4.21, 2.5.0-Beta2: **all four fall
  back**. The version is not a lever.
- **H1/H2** — the cost model. `T = a + b·N` holds on every plugin mix (R² 1.000); `b` is 17–20 ms per file; Compose adds
  14 %, Koin another 4 %. The price is the compiler's, not our plugins'.
- **Replay** — 798 resolvable commits over six months replayed against three hypothetical splits, with ABI cascades
  classified from the diffs.

## Idea

Judge a split by the distribution of edit cost, not by one number. A split multiplies modules, and a module split pays the
fixed cost per module on every edit that touches several — including every ABI edit, which rebuilds the modules that depend
on the edited one.

## Decision

**No module split on this evidence, and no layer refactor sized by the split's promise.** The measured result is a
trade, not a win:

- the **median** edit drops by ~85 % (a 108-file module recompiles in seconds, not 60);
- the **p80** edit does not: +8 % for a 38-module split, −24 % only in the most partitioned variant, and +28 % if every
  edit were an ABI change.

`core/ui`, the second hottest cluster (108 files, 261 commits), is five import lines away from being liftable — three of
them misplaced types (`ThemeMode` in `core/settings`, `OnboardingSettingsRepository` in `core/ui`). That change is cheap on
its own merits and is not blocked by this decision.

## Rationale

- **The model does not survive its own validation.** `Gate E` measured a two-module build: the two compile tasks ran
  **sequentially** (wall 6.0 s against a 2.76 s makespan — a dependency chain serialises the compilers), and each task cost
  ~40 % more than the same module compiled alone. The replay's optimistic aggregation does not exist in a real split, and
  its pessimistic one under-prices every module.
- **The tail is where the pain is.** 42 % of commits touch a non-private declaration, and an ABI edit after a split
  rebuilds the dependents that used to be the same module.
- **The split does not cure the fallback**, only shortens one occurrence of it; the numbers above are the only thing it
  buys.

## Consequences

- `:shared` stays one module. `gradle.properties` is untouched; nothing in the build changes.
- The next measurement, if anyone wants one, is **one real two-module split measured end to end** — not another model. The
  `core/ui` import moves produce exactly that experiment, and it would replace the synthetic stand with two numbers.
- The Android target is unmeasured (H8): a commit touching only `androidMain` costs nothing in the JVM metric used here and
  something real in the Android compilation.
- The cost model itself (a linear per-file price, dominated by the compiler rather than by Compose or Koin) is reusable for
  any future modularization question.

## Links

- `build-investigation/split-plan/` — `kotlin-versions.md` (H9), `cost-model.md` (H1/H2, Gate E), `replay.md`, `cluster-map.md`, `cut-plan.txt`
- `docs/decisions/2026-11-05-kt-62686-is-kmp-jvm-compilation.md` — why the fallback exists
- Issue #2600
