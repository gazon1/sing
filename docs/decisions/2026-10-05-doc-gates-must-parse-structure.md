---
title: Doc gates must parse structure, not just check key presence
date: 2026-10-05
status: accepted
tags: [ci, tooling]
---

## Context

A spec-governance sweep (2026-10-05) audited this repo's documentation and tooling
gates. The KMP/Kotlin architecture came out clean — expect/actual 32/32/32 with zero
orphans, 28/28 ViewModels on the injected `AutoCloseableCoroutineScope`, 0 uses of
`stateIn` or `viewModelScope`, 0 `GlobalScope`, and a Konsist suite of 11 rules with
every allowlist entry ADR-annotated. The documentation layer did not.

Ten defects were confirmed and fixed. Six share one root cause, which is what this ADR
records.

**Every gate validated key *presence* but not *structure*, and two gates were disabled
exactly where developers look.**

`check-skill-frontmatter.sh` grepped for `^name:` and `^description:`. It reported all
112 skills valid while 15 were unparseable YAML — their descriptions contained an
unquoted `": "`, so the key was present and the document did not parse. Every skill
loader had been silently dropping that metadata.

`check-detekt-registrations.sh` checked for duplicate service-file entries, duplicate
`detekt.yml` blocks, and provider/class agreement. It had no invariant tying a
`ruleSetId` in source to a config block, so `no-direct-dispatchers` and
`user-scoped-repository` were implemented, packaged, registered in
`META-INF/services` — and had never executed. `ProhibitUserIdInObserveRule` guards
profile isolation and was dormant.

`normalize-adr-frontmatter.py --apply` could destroy an ADR. `parse_frontmatter` treated
any `:`-bearing line as a key, and when no closing `---` was found it left `body_start`
as `None`, so `body` fell back to the *whole file* and the file's own body was re-emitted
as frontmatter. Reproduced on an isolated copy: 2489 → 3107 bytes, body duplicated, prose
like `> **Superseded in part (2026-09-29):**` promoted to a pseudo-key. This is a
data-loss bug in a script the docs instruct agents to run.

`just docs-audit` ran the size budget with `--warn-only`, so `AGENTS.md` at 264 lines
against a 250 budget was red in CI and green locally. `.github/workflows/docs-audit.yml`,
titled "Documentation Audit", ended every meaningful step in `|| true` and skipped
OpenSpec validation whenever the CLI was absent.

## Idea

Two candidate framings:

1. Fix the ten instances. Each is small; together they are a day of work.
2. Fix the class: make every gate a *parser* rather than a *presence checker*, and make
   every gate capable of failing.

Option 1 leaves the next malformed frontmatter invisible, because the check that missed
it is unchanged. Option 2 costs a bit more now and is the only one that changes the
outcome for defects not yet written.

## Decision

Adopt option 2. Four rules, applied to every gate in the docs layer:

1. **Parse, don't grep.** A gate over a structured format must parse that format. If
   PyYAML is unavailable the gate fails loudly rather than degrading to a substring
   match — a gate that cannot do its job must not pretend to have passed.
2. **Refuse rather than guess.** A normalizer that cannot confidently round-trip a file
   reports it and leaves it byte-identical. Silence is safer than a plausible rewrite.
3. **Refusals are classified.** "This input is malformed" and "this valid input is
   outside what I can represent" are different findings. Only the first fails the gate;
   conflating them makes a gate permanently red, which is how `--warn-only` was
   introduced in the first place.
4. **A gate that cannot fail is not a gate.** No `|| true`, no `command -v X || skip`,
   no `--warn-only` on a check whose result someone acts on.

Concretely, in this change:

- `check_skill_frontmatter.py` replaces the shell grep; parses YAML; also asserts
  `name` matches the directory. `--fix` only touches files that actually fail, so the
  diff is 15 files rather than 107.
- `normalize-adr-frontmatter.py` gains a known-key vocabulary derived from the corpus
  (not invented), a separate `UnrepresentableValue` for list and block-scalar values it
  cannot round-trip, and exit 3 reserved for genuine malformation.
- `check-detekt-registrations.sh` gains invariant 4: every `ruleSetId` in source has a
  `detekt.yml` block.
- `docs-audit.yml` becomes blocking end to end, invokes OpenSpec through
  `npx @fission-ai/openspec` so the step cannot silently skip, and uses the same 1250
  DIGEST budget as `check-doc-sizes.py`.

## Rationale

The audit's most useful finding was not any single defect but the pattern: a gate that
answers a narrower question than the one being asked of it. "Is `description:` present?"
was silently answering for "is this frontmatter valid?" "Are there duplicate
registrations?" was answering for "does this rule run?" "Does the digest have a
double-suffix bug?" was answering for "is the digest within budget?" Each is a
reasonable question; each was standing in for a stronger one.

Two failures recur in the same shape, and both were found by reading rather than
grepping:

- The normalizer corrupted an ADR on a *value* the model could not represent — an
  issue no presence check would ever surface, found only by running the fixed tool over
  the real corpus and reading the diff.
- A naive `^expect` grep finds 30 of 32 `expect`/`actual` declarations, missing
  `@Composable expect fun`, `expect suspend fun` and `expect inline fun`. Anchoring a
  declaration pattern at line start is the same error as checking for a key at line
  start.

Rule 3 exists because the first version of the normalizer fix conflated the two refusal
classes. That made `--dry-run` exit 3 over six long-standing valid ADRs, which would
have blocked CI permanently and given the team a reason to disable the gate again —
reproducing the original defect through a fix for it. A gate that is always red is
indistinguishable from no gate, because it trains people to ignore it.

`KNOWN_KEYS` is derived by walking the corpus rather than written from memory, for the
same reason: an invented vocabulary rejected 35 valid ADRs on the first run. The
corpus is the specification.

## Consequences

- Three ADRs were hand-repaired before the tool was trusted again:
  `2026-09-29-archive-has-no-restore-ui` (frontmatter never closed; the superseded
  blockquote moved into the body and `superseded-by` added),
  `2026-09-07-task-detail-document-style` and `2026-09-08-projects-ux-rework` (whole
  body written inside the frontmatter block; `Links` relocated to a real `## Links`
  section, since the body had none and the links existed only as metadata).
- Six ADRs are permanently "not normalizable" — their frontmatter is valid YAML with
  list or block-scalar values. They are reported, skipped, and do not fail the gate.
  Making the normalizer structure-aware enough to rewrite them is a separate change.
- Two detekt rules began executing. `:shared:detekt` passes with 0 findings across
  109k lines, confirming `FileLogWriter.kt` is the only `Dispatchers` call site and the
  existing whitelist is correct. Two rules that had never run are now a real gate, so
  a future violation will fail the build.
- `--update-baseline` on `check-doc-dead-refs.py` now refuses to drop existing accepted
  entries without `--force`. An unguarded regeneration reduced that file from 328
  entries to 39, erasing the record of accepted debt — a gate tool silently destroying
  the ledger it is meant to maintain.
- `check-adr-references.py` is new: it resolves dated-ADR slug tokens in prose, which
  `check-doc-dead-refs.py` cannot see because those references carry no path. This is
  the gate that makes ADR deletion safe in a later phase.
- `openspec validate --all --strict` passes 4/4. The repo's only capability spec parses
  to 8 requirements and now validates; the invalid `jvm-coroutine-diagnostics` change is
  marked `skip_specs` after confirming it has no `specs/` directory (the marker and a
  `specs/` directory are mutually exclusive).
- `AGENTS.md` is back to exactly 250 lines, and the gate is blocking again locally and
  in CI. The trims removed prose duplicated in skills and ADRs, not instructions.
- `just os-validate` no longer depends on a hardcoded absolute path into one
  developer's nvm installation, so it works on any machine and in CI.
- The repo's only spec lives at `openspec/specs/nav/nav3-entry-dispatch/spec.md`,
  demonstrating that nested `area/capability` ids work in the CLI's layout.
  `openspec/specs/MODULE-INDEX.md` records covered *and* uncovered modules, so the
  absence of a spec reads as a decision rather than an oversight.

## Links

- `docs/decisions/2026-09-28-detekt-daemon-and-crashing-rule.md` — the earlier
  `NoFactoryViewModelRule` dormancy that established the registration pattern
- `docs/decisions/2026-09-26-detekt-baseline-established.md` — baseline discipline
- `docs/decisions/2026-09-27-doc-and-skills-sprint-findings.md` — the doc debt this
  sweep inherited
- `docs/decisions/deferred-backlog.md` — `test-doubles-in-commonmain-source`,
  `no-direct-dispatchers-rule-one-whitelisted-case`
- `docs/doc-maintenance.md` — ADR and digest policy
- `.agents/skills/singularity-todo-monthly-doc-audit/SKILL.md` — §4 checklist updated
  with the config-block check and the `^expect` anchoring caveat
- `.agents/skills/singularity-todo-openspec-workflow/SKILL.md` — module-index read
  step, anti-backfill warning, `verify` vs `validate` distinction
