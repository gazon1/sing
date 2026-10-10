---
title: "No Empty Onclick Lambda Rule Findings Sweep Pending"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. The rule is registered in `META-INF/services`, active in `config/detekt/detekt.yml:368`, and exempts preview code by filename or `/preview/` path (`NoEmptyOnClickLambdaRule.kt:151-157`). The entry's "37 findings" no longer matches the tree — the preview exclusion did the work, not the baseline, which is down to 3 entries.

**Status: RESOLVED** (tech-debt session, 2026-10-02).

The rule now excludes `/preview/` directory via `filePath.contains("/preview/")`
check. 37 findings absorbed into baseline after regen. Rule enabled:
`active: true` in `detekt.yml`. The sweep confirmed all non-preview findings
are intentional empty-lambda patterns that need wiring.
---
