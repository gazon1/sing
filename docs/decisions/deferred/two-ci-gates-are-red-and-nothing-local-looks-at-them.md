---
title: "Two Ci Gates Are Red And Nothing Local Looks At Them"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `check-doc-sizes.py` and `check-doc-dead-refs.py` both exit 0, both run in `check.sh:128-135` with a hard `exit 1` and no `|| true`, and both appear in `ci.yml` without `continue-on-error`. The 38 findings are covered by the dead-refs baseline.

**Found in:** 2026-10-04, the "what next" sweep — while adding four lines to
`AGENTS.md` and running `just docs-audit` to see whether they broke anything.

Two blocking CI steps in `.github/workflows/ci.yml` are red, and **both were
red before this branch touched anything**:

1. `Check doc sizes` — `AGENTS.md` was 253 lines against a 250 limit at HEAD;
   `DIGEST.md` was 1255 against 1250. Fixed here (ADR
   `2026-10-04-doc-size-budget-was-already-red`).
2. `Check dead doc references` — `check-doc-dead-refs.py` exits 1. Verified by
   stashing this branch's work and re-running: identical findings at HEAD, so
   none of them are ours. The live ones are `DEAD` references to files that do
   not exist at all — `GLOSSARY.md` in five skills, `NOTES.md`, `package.json`,
   `CLAUDE.md`, `CODING_STANDARDS.md` in `retro` — plus two `DRIFT` entries
   (`AGENTS.md`, `PROGRESS.md` → `openspec/config.yaml`).

**Why nobody noticed:** neither gate runs in `check.sh`, which is what every
local loop uses. They fire only on push, and the push that broke them was a
while back. The same shape as `maestro-gate-can-test-a-stale-apk`: a gate that
only exists in one place is a gate whose failure nobody sees.

**Do this first:** the dead-refs list is the cheap one — six skill files point
at documents that were never created (`GLOSSARY.md` especially, referenced five
times). Either write the files or drop the references; the skill docs are
agent-facing, so a reference to a file that is not there is an agent going
looking for something that does not exist. The two `openspec/config.yaml`
DRIFT entries need the real path, which the script can report with `--strict`.

**Then:** add both to `just gate` (`2026-10-04-one-gate-recipe.md`). They are
fast, they are already CI, and this episode is the argument for putting every
gate somewhere a local run will meet it.

---
