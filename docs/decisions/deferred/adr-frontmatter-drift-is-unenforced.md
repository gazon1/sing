---
title: "Adr Frontmatter Drift Is Unenforced"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Found in:** 2026-10-04 verifiability change, while making
`docs-audit.yml` steps real.

**Status: OPEN**

**Tracked as:** #453

**Symptom:** `normalize-adr-frontmatter.sh --dry-run` exits **2** when any ADR's
frontmatter drifts from the schema, and **8 ADRs** currently do — mostly
`created:` where the schema wants `date:`, plus a few with no `status:` or
`title:`. The workflow step was `... || true`, so the exit code was discarded
and the drift accumulated unnoticed.

**Deliberately still advisory.** Flipping it to blocking in the same change that
makes other gates blocking would fail the build on pre-existing debt that has
nothing to do with those gates, and the failure would be a wall of unrelated
noise. That is the "enabling a gate reddens the build" hazard — real, and worth
absorbing for a gate whose debt is *in scope*, not for one whose debt is a
20-minute mechanical fix sitting next door.

**Try next — this is small and self-contained:**

```bash
./scripts/normalize-adr-frontmatter.sh     # no --dry-run: rewrites in place
git add docs/decisions/
```

Then delete the `continue-on-error: true` from the
`Check ADR frontmatter` step in `docs-audit.yml`. New ADRs are already
compliant — the one written for the 2026-10-04 change passes clean — so this only
ever drains.

---
