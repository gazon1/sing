---
title: "Docs Audit Workflow Was Never Valid Yaml"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED 2026-10-05.** The general lesson is now enforced, not
remembered: `check.sh` step 14 parses every `.github/workflows/*.yml` and
fails the build on one that will not parse. Proven by sabotage rather than
assumed. The `"Try next"` below is kept for the record of how the gap was
found; its closing sentence is superseded by this status.

**Found in:** 2026-10-04 verifiability change, while replacing the `|| true`
steps in `docs-audit.yml` with real exits.

**Symptom:** line 17 read `- 'openspec/**''` — a stray trailing apostrophe.
`yaml.safe_load` rejected the file outright, which means GitHub Actions could
not have run the workflow at all. Every step in it was advisory
(`|| true`, `echo "Warning:"`) *and* the file could not load. Two independent
reasons the documentation audit never happened, neither of them visible from
reading the YAML.

**Already ruled out:** not a GitHub tolerance for trailing quotes — the parser
fails on the unbalanced scalar, the same as any YAML reader.

**Resolved in the 2026-10-04 change:** the quote is fixed and the file parses.
The advisory steps were then made real, and `Enforce DIGEST size budget` now
fails the build.

**Try next, and note the general lesson:** nothing in this repo parses
`.github/workflows/*.yml`. A malformed workflow is invisible — it is not a test
failure, not a lint error, just a workflow that silently does not exist.

**CLOSED 2026-10-04 — the class is gated, verified by sabotage.** `check.sh`
step 14 (`workflow YAML parses`) runs `yaml.safe_load` over every
`.github/workflows/*.yml` and exits non-zero on the first that will not parse.
Proved it can fail rather than assuming it: appending an unterminated quoted
scalar to `ci.yml` makes the gate exit 1 with a line and column, and removing
it returns the gate to green. So the entry's "it has not been added yet" is no
longer true, and the general lesson it names is now enforced rather than
remembered. Kept for the record of how the gap was found.

---
