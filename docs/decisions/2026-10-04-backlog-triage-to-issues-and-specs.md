---
title: "Backlog triage: what becomes an issue, what stays backlog, what gets a spec"
date: 2026-10-04
status: accepted
tags: [process, testing, maestro, ci, tooling]
---

# Backlog triage: what becomes an issue, what stays backlog, what gets a spec

## Context

`docs/decisions/deferred-backlog.md` held 52 entries at the end of the agenda
views work. 48 GitHub issues already existed, created in earlier sessions, and
covered a large share of them. The question was what to do with the entries
that no issue covered, and — the harder half — whether each of those is really
still open.

The entry that prompted the sweep is
`an-open-backlog-entry-does-not-mean-the-work-is-still-open`: nine entries
carried `Status: OPEN` and described work that had shipped, two of them
misleadingly. So "not already an issue" is not the same as "not already done",
and the triage could not be a mechanical diff against the issue list.

## Idea

Three destinations instead of one, decided per entry:

- **Issue** — a finding with remaining work that a person or an agent can pick
  up. The issue carries the finding, what was already ruled out, and what to try
  first, so the next reader does not repeat the dead ends.
- **OpenSpec change** — a subset of the issues, namely those that change
  observable behavior. The project's own rule already says this: OpenSpec is for
  behavior changes, not for bug fixes, test-only work, dependency updates, or
  doc-only edits. A spec for a refactor with no contract change is an
  architectural preference with a validation step attached.
- **Backlog only** — anything already resolved, plus findings that were measured
  and deliberately closed, plus process lessons that have no code to change.

## Decision

Sixteen issues were created (#77–#92). Four OpenSpec changes were proposed.

**Stayed in the backlog, not issues:**

- Entries whose work shipped. These are closed in place, with what actually
  fixed them recorded — including where the fix was not one of the options the
  entry offered, which is the information a future reader most needs.
- `cross-user-write-rule-measured-and-rejected`. The rule was written, measured,
  and found to be 7/8 false positives. Shipping a registry instead is a settled
  answer, not an open task.
- `kover-full-jvmtest-run-unmeasured`'s original question. The measurement was
  run; the flag it defended turned out to be a workaround for a workaround. Only
  the residue became an issue (#85).
- `notification-text-null-invisible`'s latent design hazard and
  `a-flow-can-be-unrunnable-and-every-check-still-pass`'s general form. Both
  describe conditions nothing currently produces. The first is folded into the
  snackbar work (#80); the second into the CI job issue (#87).

**OpenSpec changes, and why only four:**

`backup-include-remaining-tables`, `delete-safety-feedback`,
`agenda-tags-entry-point`, `bulk-import-port`. The first three change what the
user sees. The fourth changes no behavior at all and still gets a spec, because
its deliverable *is* a contract — what the new import boundary guarantees and
what it explicitly does not — and a change with neither spec nor behavior reads
in a year as an unexplained architectural preference.

**Two entries were split rather than taken whole.** The backup entry is 7 row
data sets that need a mechanical fix and a profile-container question that needs
a product decision; the OpenSpec change separates them so the 80% does not wait
on the 20%. The delete entry mixes coverage, reliability and visibility, and its
tasks are ordered reliability-first: applying reversal offers to ten sites while
the reversal path is still known to fail silently would multiply the defect by
ten.

## Rationale

**An issue is a work item, and a backlog entry is a record of a finding.** They
are not the same artifact and they have different lifetimes. Collapsing them
means either the issue tracker fills with records nobody will act on, or the
backlog stops being the place where dead ends are written down — and the dead
ends are the most valuable part of it. `bulk-import-port` is the clearest case:
what the next person needs is that a syntactic rule was tried and rejected, and
no issue tracker rewards that.

**The 52→36 reduction is the real result.** Most entries were already done, and
two of them were wrong in a way that would have cost a debugging session. An
issue created from a resolved entry is worse than no issue, because it is a live
commitment to work that does not need doing.

**The "unconfirmed" label is load-bearing.** Five of the six red smoke flows
have no diagnosis. They are recorded as a table of what is *known*, with the
hypothesis about the deep-link wait marked as a hypothesis, and a note that the
cheap test is to add the wait and see which flows move. Writing a plausible
cause would have made the issue readable and the diagnosis wrong.

## Consequences

- The backlog carries `**Tracked as:**` links on the sixteen entries that have
  an issue, so the mapping is readable from either side.
- Issue numbers are written into the ADR and will drift if issues are closed and
  renumbered. GitHub does not renumber, so this is stable in practice.
- The four OpenSpec changes are proposals, not specs. Two carry an unresolved
  decision recorded in `design.md` (the orphan policy, and transient-versus-
  persisted for a tag-driven agenda). Both are listed as Phase 0 tasks so the
  decision is not made implicitly by whoever implements first.
- `openspec validate` could not be run: the CLI is not installed in this
  environment. The artifacts follow the structure of the existing
  `add-log-export` change and the spec artifacts are written without type names,
  per the project's own spec rules — but that is a claim about the file's shape,
  not a validation result.

## Links

- `docs/decisions/deferred-backlog.md`
- `docs/decisions/2026-10-04-make-the-rules-not-the-sweeps.md`
- `openspec/config.yaml` — the rule that separates behavior changes from refactors
- Issues [#77](https://github.com/gazon1/singularity-clone-kmp/issues/77)–[#92](https://github.com/gazon1/singularity-clone-kmp/issues/92)
