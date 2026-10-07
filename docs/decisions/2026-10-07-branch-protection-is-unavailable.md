---
title: "Branch protection is unavailable, so the gates are advisory in fact"
date: 2026-10-07
status: accepted
tags: [ci, process, testing]
---

# Branch protection is unavailable, so the gates are advisory in fact

## Context

`2026-10-06-ci-single-gate-registry-and-leaf-split` ends with the requirement that
branch protection requires **exactly one** check, `CI gate`, and that every leaf
reports into it. That requirement cannot currently be met. Asking GitHub:

```
$ gh api repos/gazon1/sing/branches/main/protection
403 {"message": "Upgrade to GitHub Pro or make this repository public to enable this feature."}
```

Branch protection is a paid-plan feature. This repository is on neither plan, so
the requirement is not merely unmet — it is **unenforceable today**, and nothing in
the repository can change that.

The second enforcement path is dark for an unrelated reason. Since 2026-10-06 no CI
job has run at all, on any branch, including `main`. Every run completes in two
seconds with no runner assigned, and the check-run annotation reads:

> The job was not started because recent account payments have failed or your
> spending limit needs to be increased.

So both halves of the intended arrangement are off at once: the merge gate does not
exist, and the CI it was meant to require does not currently execute. Every red
status in this repository has been uninformative for two days, and an uninformative
red is worse than a known green because it reads as a measurement.

## What still enforces something

`.githooks/pre-push` is version-controlled and shared by every worktree through
`core.hooksPath`. It runs two fast gates before a push — the version-catalog gate
and `Maestro/scripts/check-tags.sh` — and nothing else. It is a typo-catcher, not a
substitute for `CI gate`: it does not run `scripts/ci/static-gates.sh`, so the 28
gates in the registry are not behind it.

It also does not travel. It fires for whoever has hooks installed locally, which is
one person on one machine. That is a real property and not a defect, but it is not
a repository-wide control.

## Decision

**The owner has chosen to make the repository public**, which is the branch of
the fork that enables branch protection without paying. That is a change to the
GitHub repository settings and is **not performed here** — repository visibility is
the owner's action, and no gate in this repository is worth making it for them.

So the requirement stays written, unmet and visible until the visibility change
lands:

- `ci.yml`'s `ci-gate` job keeps `if: always()` and keeps asserting that no leaf is
  `skipped`. When CI runs, it is the correct shape.
- `check.sh` keeps running the full local loop.
- The trace ratchet and the baseline ratchet keep their floors, so **when CI returns,
  it returns against a stated bar** rather than a drifted one.

Two consequences of the choice are worth stating rather than discovering later:

1. **Everything in this repository becomes readable by anyone who looks.** Before the
   flip, confirm there is nothing that should not be published: credentials in history,
   private hostnames or paths, and customer data in fixtures. `check-provenance.py` and
   `check-adr-references.py` do not look for any of that.
2. **Making it public does not retroactively enable anything.** Branch protection
   applies to pushes made *after* it is configured. The required check has to be set
   explicitly afterwards, and that is the moment this entry should be deleted.

## Consequences

- `gates: N passed, 1 failed` in a CI log is currently a claim with no enforcement
  behind it. Any merge made while this is true was made without a gate, and saying
  otherwise would be the "gate that lies" failure ADR
  `2026-10-06-a-gate-that-lies-is-worse-than-no-gate` names.
- The two missing repository settings this also uncovered are now created, so that
  when billing is restored the runs are provable rather than merely green:
  `MAESTRO_VERSION` (repository variable, `2.10.0` — the version
  `docs/testing/android-tier-runbook.md`, `docs/decisions/deferred-backlog.md`, and
  `.agents/skills/singularity-todo-maestro-flows/SKILL.md` all record as measured)
  and `GRADLE_ENCRYPTION_KEY` (secret, generated random).

  Without `MAESTRO_VERSION` the `e2e.yml` install step fails by construction: it
  guards with `: "${MAESTRO_VERSION:?...}"`, so an unset variable is a hard stop, not
  a slow run. That workflow could not have succeeded as written.
- Revisit this entry when billing is restored. The first thing to do then is set the
  required check to `CI gate` and delete this entry, because a documented
  unenforceability is only honest while it is true.

## Links

- `docs/decisions/2026-10-06-ci-single-gate-registry-and-leaf-split.md` — the
  requirement this cannot currently be met against
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md`
- `.githooks/pre-push` — the only enforcement that runs without either plan
- `scripts/ci/static-gates.sh` — the registry that is currently unreachable from CI