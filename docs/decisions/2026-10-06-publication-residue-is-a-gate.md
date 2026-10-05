---
title: Publication is a snapshot, and the residue it carries is a gate
date: 2026-10-06
status: accepted
tags: [publication, open-core, gates, documentation, tooling]
---

# Publication is a snapshot, and the residue it carries is a gate

## Context

The provenance audit (`2026-10-05-provenance-audit`) cleared the licence half of
publication and left two items: rewriting history so the author's real email never
reaches a public tree, and adding the licence files. The history item is settled by
construction — publish a squashed snapshot rather than flipping the private
repository's visibility — and needs no code.

What the audit did not cover is the other direction: not *what licence* the code is
under, but *what else* the snapshot carries. A scan of the tree for that question
found 33 occurrences of one developer's home directory, the repository's internal
clone name sitting in the Android launcher label, an instruction in a skill template
naming a `localhost:3000` this app does not serve, and four README numbers that had
drifted from the code they claimed to describe.

None of that is a secret. All of it is unrecoverable: a public commit cannot be
recalled, only replaced by a force-push that leaves the original in every clone.

## Idea

Two options, and the difference is not cosmetic.

**A. Clean once.** A script or a manual pass over the 33 sites. The tree is clean
today and dirty again after the next `cd ~/...` copied into a skill.

**B. Make the residue a gate.** Write the rules into `scripts/check-publication-hygiene.py`
with the same positive-control discipline the project already applies to provenance
and licence boundaries, so the invariant survives the cleanup instead of decaying
after it.

B is the only one that makes the cleanup permanent, and the project has already paid
twice for choosing the cheaper option — once for a provenance regex that produced 30
false positives, once for a licence gate that verified `LICENSE.pro` and never
`LICENSE`. Both times the failure was an invariant nobody was checking.

## Decision

**Adopt B.** Two gates, registered in `check-gate-wiring.py` with sabotage controls,
covered by `scripts/tests/`, and runnable as `--self-test`.

`check-publication-hygiene.py` scans tracked text files for personal email
addresses, absolute home paths, the internal clone name, and localhost ports the app
does not serve. Its allowlist lives in `config/docs/publication-allowlist.tsv` as
data, and the gate fails if the prefixes enforced in code and the prefixes recorded
there diverge in either direction — an exception that exists only in one of the two
is a claim nobody is tracking.

The allowlist is the interesting part, and it is small. `docs/decisions/` and
`openspec/changes/` are exempt, because an ADR that records "worktree:
`/home/<user>/worktrees/epic2`" or quotes a user's bug report verbatim is the
decision log being honest; replacing it with a placeholder makes the record vaguer
and no more private. Test sources are exempt for the same reason a redactor test
holds a credential-shaped string: the fixture *is* the test.

`infra/kiwi/scenarios/` was **not** exempt, and that asymmetry is the decision worth
recording. Those files carried the same `Source:` header as the OpenSpec proposals,
but a Kiwi scenario is a living specification someone reads to write a test, not a
record of a past decision, so the path carried no lasting value. Same string,
different reason, different answer — which is why the allowlist has to justify each
entry rather than pattern-match on the shape of the string.

`check-readme-claims.py` is the second half of the same problem. Each README number
is recomputed from the tree — the MCP tool count from the `listOf(...)` inside the
Koin binding that `ToolRegistrar` registers verbatim, the schema version from
`SCHEMA_VERSION`, the ADR and skill counts from the directory listing — and any
disagreement fails. `N+` is accepted as a lower bound for the two counts that grow,
because a document that must be edited whenever a decision is recorded goes stale in
a different way.

## Rationale

**Why the tool count is parsed, not counted.** `ToolRegistrar` gets its list from
Koin (`koin.get()`), so the number is not visible in any single place. Parsing the
`listOf(...)` that feeds the binding is the only derivation that is a measurement
rather than an estimate. The gate additionally requires the JVM and Android modules
to declare the same set — a single README number cannot honestly describe two
platforms, and this is the assertion that makes the number meaningful.

**Why `N+` is not required to be exact.** Both counts grow continuously. A gate that
demanded an exact figure would be firing on ordinary work, and a gate that fires on
ordinary work is a gate whose failures get ignored. The lower bound still catches the
failure that matters: a claim *above* the truth.

**Why the SSH carve-out needs a path tail.** `git@github.com:gazon1/sing.git` parses
as an email address. An early version excluded `user@host:` with nothing after the
colon, which meant `a.person@corp-mail.example.net: owns sync` was silently dropped —
the gate blind to exactly the address it exists to catch. Requiring `owner/repo` after
the colon distinguishes a remote from prose. The regression is pinned by
`test_email_followed_by_colon_is_still_reported`.

## Consequences

**The gates found more than the scan did.** Two things the manual pass missed:

- Six `singularity_cllone_kmp` paths in the worktree-isolation skill. The scan had
  counted four path hits across all skills; the rule matched the name independently
  of whether it sat inside a path.
- A functional defect. The Android DI module never registered `ListProjectsTool` and
  `DeleteProjectTool`, so the AI agent on Android could create and update a project
  but had no tool to read the existing ones or delete one. Both take only `commonMain`
  dependencies, so there was never a platform reason for the gap. The tool-count
  parity assertion is what surfaced it, and it is now the reason that assertion
  exists: it was written to catch a documentation lie and caught a product bug.

**The self-tests caught four bugs in the gates themselves**, each recorded in the
code at the point it was fixed: a clean corpus judged through raw detectors instead
of through the suppression the real check applies; an SSH exclusion wide enough to
swallow any address followed by a colon; counting helpers that ignored the root they
were handed, so the self-test measured the real repository instead of its own
fixture; and a `+` flag recovered by searching the whole README for `"<n>+"`, which
let one claim vouch for another. Each would have shipped as a gate that reported
success having verified nothing.

**ADRs are not rewritten.** Where an ADR quotes a path or a user's bug report, it
keeps it. A gate that forced the decision log to be vaguer would be a gate whose
exceptions get widened until it reports nothing — and the non-vacuity rule exists to
make that visible rather than to prevent it.

**Publication remains a manual snapshot.** The gate guarantees the *tree* is
publishable. It cannot reach into git history, which is why the history rule stays a
step rather than becoming one: `git log --all --format='%ae'` on the snapshot is the
check, and it must happen before anything is pushed to a public remote, not after.

## Links

- `docs/decisions/2026-10-05-provenance-audit.md` — the licence half, and §4 on why
  every rule here carries a control
- `scripts/check-publication-hygiene.py`, `config/docs/publication-allowlist.tsv`
- `scripts/check-readme-claims.py`
- `scripts/tests/test_check_publication_hygiene.py`, `test_check_readme_claims.py`
- `docs/legal/PROVENANCE.md` — the derived-code registry this sits beside