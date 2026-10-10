---
title: "Digest Line Limit Pressure"
date: 2000-01-01
status: RESOLVED
tags: ["deferred"]
---

**Tracked as:** #52

**Status: RESOLVED (2026-10-04).** Two changes, both applied:

1. `MAX_BULLETS_PER_ADR = 3` in `scripts/refresh-decisions-digest.py`. A verbose ADR
   used to fill every tag section it was tagged with, so the digest grew with the
   wordiest author rather than with the number of decisions. The Critical section is
   exempt — `**Always**` / `**Never**` rules are what a reader came for. The digest
   went 1260 → 1190 lines, back under the 1250 budget with headroom.
2. The CI doc-sizes step now regenerates the digest before measuring it. DIGEST.md is
   gitignored, so on a fresh checkout it does not exist and the budget check silently
   skipped the only document whose size is generated. The budget was exceeded locally
   for an unknown stretch precisely because `check.sh` did not run the gate at all.
**Tracked as:** #52

**Symptom:** the digest indexes every Consequences bullet and creates a
section per tag, so it grows with every ADR while the limit is fixed. The
next author who writes a verbose ADR gets a failed `docs-audit` with no
obvious remedy and will either trim content (bad) or raise the limit (worse).

Remaining pressure is the "Active entries" index — one line per ADR, 421 lines and
growing by one per decision. It is the lowest-value section in the file (a title
list, one `ls` away). If the warning returns, cut that section before raising the
limit.

Also resolved on this branch: the budget was found **already red** before either
side touched it — the digest sat at 1255 against a 1250 limit and `AGENTS.md` at
253 against 250. The two caps above brought it back under. ADR:
`2026-10-04-doc-size-budget-was-already-red.md`.

---
