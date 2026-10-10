---
title: "Androidapp Debug Source Set Unlinted"
date: 2000-01-01
status: CLOSED.
tags: ["deferred", "androidapp-debug-lint-policy", "androidapp-debug-lint-policy`"]
---

**Found in:** 2026-10-05, while moving `androidApp` off `detekt-minimal.yml`. The module's
`detekt.source` never listed `src/debug`, so `DebugSeedActivity.kt` has never been linted.
It is also the only androidApp source set the module does not scan.

**Tracked as:** #99
**OpenSpec change:** `openspec/changes/androidapp-debug-lint-policy/`

**Status: CLOSED.** 2026-10-05, by `openspec/changes/androidapp-debug-lint-policy` and
ADR `2026-10-05-debug-source-set-is-linted.md`. `src/debug` is now scanned; 9 findings
were auto-corrected and 6 are baselined with a reason each. **The recorded split below was
wrong** — measurement found 15 findings, not 16, and 6 to baseline rather than 4 — which is
why the change's first task was to measure before changing anything. The policy was not:
lint it, rather than exempt the source set, because "not linted" and "linted with
everything suppressed" are the same hiding place with a different badge.

The original entry:

> **Status:** OPEN — a decision, not a mechanical fix. Linting it produces 16 findings, and
> every one is in `DebugSeedActivity.kt`:
- `NoRunBlocking` (1) and `NoDirectClockSystem` (4) — a one-shot debug seeder blocks a
  background thread and stamps seed timestamps; both are the point of the tool
- `TooGenericExceptionCaught` (1) — a seeding tool that must not crash the app
- `BlankLineBetweenWhenConditions` (5), `ClassSignature` (2), and 3 more formatting
  findings, which are auto-correctable

**Now resolved.** Half of these needed a suppression, because the rules are correct for
production and wrong for a debug seeder — and each suppression says so in terms of the tool
rather than of debug code, so a seventh finding has to be a decision instead of joining the
pile. The decision generalises to every future
`src/debug` file.

**Try next:** decide the policy first, then wire it. The cheapest policy is to lint it
with a baseline carrying the four intentional suppressions, which keeps the formatting
findings enforced from day one. Do not simply add `src/debug/kotlin` to `source.setFrom`
and baseline the lot: that would accept the 16 without deciding whether debug code should
be governed at all.

**Note:** the `source.setFrom` list also contained `src/androidAndroidTest/kotlin`, a
source set that does not exist — a typo, silently ignored. Removed.

---

---

---
