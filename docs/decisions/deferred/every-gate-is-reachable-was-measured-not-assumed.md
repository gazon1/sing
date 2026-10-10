---
title: "Every Gate Is Reachable Was Measured Not Assumed"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN**

**Tracked as:** none — the work landed with
`docs/decisions/2026-10-07-branch-protection-is-unavailable.md`; this entry is the
remainder, not the whole.

**Found in:** auditing what actually enforces anything on 2026-10-07, while
recording that branch protection is unavailable on the current plan.

**Situation.** Parts A–G of `scripts/check-gate-wiring.py` all start from a gate
somebody already decided to run. A `scripts/check*` file that can fail and is named
by nobody is invisible to every one of them: not "invoked" (A), not "registered" (F),
not asymmetric (E). Part H now covers that gap.

**Measured, not assumed — and the measurement corrected the assumption.** A first
pass reported three unreachable gates: `check-adr-references.py`, `check_adr_status.py`
and `check_skill_frontmatter.py`. All three were reachable:

- `check-adr-references.py` and `check_adr_status.py` are named in `.just/tests/mod.just`
  (lines 253 and 283), which a scan of `justfile` alone never opens.
- `check-flaky-tests.py`, which a second pass also flagged, is named in `ci.yml:191`.
- `check_skill_frontmatter.py` is the target of `check-skill-frontmatter.sh`, which
  `exec`s it. It is reached through the shim, not through surface text.

**Result: 29 candidates, 29 reachable, 0 unreachable.** Part H therefore passes
immediately, which makes it a ratchet against the next one, not a fix for anything
existing.

**Already ruled out:** not a claim that nothing is wrong. Two derivations that
reported a short list and called it complete were the actual defect, and both are
now encoded as tests: `test_a_gate_reachable_only_from_a_just_recipe_is_reachable`
(a non-recursive `.just/*` glob misses every nested recipe) and
`test_a_shim_delegated_gate_is_reachable` (reading surface text alone calls a
delegated gate an orphan).

**Try next, in this order:**

1. Nothing to fix. The gap is closed. Keep the gate green rather than lowering it.
2. If a future `scripts/check-*.py` is added and CI is red on Part H, the answer is
   to name it in `scripts/ci/static-gates.sh` or delete it as superseded — not to
   widen the scan. A gate that a scan cannot find is usually a gate that is genuinely
   not run.
3. If a legitimate third spelling of a gate name appears, add it to `_GATE_CANDIDATE`
   *and* add the test that proves the new spelling is a candidate.

---
