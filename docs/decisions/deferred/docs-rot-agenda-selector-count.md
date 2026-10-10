---
title: "Docs Rot Agenda Selector Count"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `Selector.kt` declares exactly 14 variants and both `SelectorDescriptor.kt:16` and `SelectorMatcher.kt:25` say "All 14" (`92357b6f`). `grep -rn testIncludes` now hits only this backlog file.

**Found in:** MR-0, кодовая разведка Selector.kt.

**Symptom:** KDoc в `SelectorDescriptor.kt:16` и `SelectorMatcher.kt:25` говорит «All 13 Selector variants». Реальное количество: **14** (Selector.kt: 11 leaf + 3 composite). ADR `2026-09-17-selector-serializer-plain-kserializer.md:10` говорит «15 concrete subtypes» (тоже stale). Docs-decision `2026-10-01-post-mr-10-findings.md:75`, `post-mr-14-findings.md:117`, `post-mr-11-findings.md:63` упоминают `-PtestIncludes` — флаг **не существует** в build scripts (Gradle silently ignores unknown `-P` flags).

**Status: RESOLVED** (2026-10-04). The KDoc now says 14 in both places
(`SelectorDescriptor.kt:16`, `SelectorMatcher.kt:25`), verified against
`Selector.kt`, which declares 14 `data class`/`data object` variants. The ADR's
"15 concrete subtypes" and the three `-PtestIncludes` references were stale doc
claims, not live instructions: `grep -rn testIncludes` over the build scripts
returns nothing, and the only remaining mention of the flag anywhere in `docs/`
is the line quoted above.

**Residue worth keeping in mind:** the count was wrong because it was a hand-
maintained number in prose. The `SelectorTemplate` catalogue added in MR-6
(`feature/agenda/domain/selector/SelectorTemplate.kt`) is the thing to point at
instead — it is code, so a new variant shows up as a compile error at the
catalogue rather than as a number that quietly goes stale.

---
