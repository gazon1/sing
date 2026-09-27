---
name: singularity-todo-monthly-doc-audit
description: Monthly or pre-release sweep of the documentation ecosystem in this repo. Use when docs have drifted from the code, when asked to "audit the docs", when DIGEST.md is oversized, when a skill is suspected of being stale or unused, or before a release. Runs the mechanical checks, then the judgement calls the scripts cannot make.
---

# Monthly doc audit

`just docs-audit` catches what is mechanically wrong. This skill is the slower pass over
what is merely *wrong*: rules that no longer match the code, skills nobody has used in
months, decisions nobody can find.

Run it monthly, before a release, or whenever someone says the docs have drifted. Ten
mechanical findings are worth an hour; the judgement calls are the point.

## 1 — Mechanical pass

```bash
just docs-audit
```

Triage each finding by where it bites:

| Finding | Cost of leaving it | Fix |
|---|---|---|
| dead ref in `AGENTS.md` / `ARCHITECTURE.md` | every agent hits it | now |
| dead ref in a skill body | only on the path that uses it | now if the skill is one agents load, else backlog |
| dead ref inside a supersedure banner | none — that is the point of a banner | leave it |
| `SKILL.md` over 500 lines | the agent may skip it | split into router + leaves |
| ADR missing `title` / `date` | digest renders it as `_(no title)_` | `./scripts/normalize-adr-frontmatter.sh --apply` |

## 2 — The digest is the file that matters most

`docs/decisions/DIGEST.md` is what every agent reads before a non-trivial task. When it is
wrong, it is wrong everywhere at once.

- **Oversized?** (>1500 lines) — the generator's selection needs tightening, not manual
  pruning. Read `scripts/refresh-decisions-digest.py` and see what it is pulling in.
- **A rule that the code no longer follows?** The digest is generated from ADRs'
  `## Consequences`, so this means the consequence is stale. Fix the ADR or supersede it —
  do not edit the digest directly, it will come back.
- **A rule that is right but not followed?** Promote it. If it can be checked, make it a
  detekt rule or a Konsist test; the skill `singularity-todo-detekt-rules-authoring`
  covers the wiring, including the service-file step that is easy to forget.

## 3 — Skills nobody has used

```bash
find .agents/skills -name SKILL.md -mtime +90 | sed 's|.agents/skills/||;s|/SKILL.md||'
```

A skill untouched for 90 days is not automatically dead — some are seasonal. Three
questions:

1. Is the **subject** still true? A skill about a removed class is a trap: it reads
   authoritative and sends the next agent to a file that is gone.
2. Does another skill or `AGENTS.md` point at it? If so, fix or retire both.
3. Would the knowledge be missed? If the pattern is real and still recommended, keep it
   and fix the paths. If it was one decision that has since been reversed, retire it —
   leave a stub pointing at the ADR that superseded it, so a grep for the old name still
   finds the answer.

## 4 — Rules that outgrew their enforcement

A rule that lives only in prose is followed until someone is in a hurry. Check the
high-traffic rules against reality:

- Is the detekt rule that backs it actually **registered**? A rule file without its
  provider in `META-INF/services/dev.detekt.api.RuleSetProvider` never runs. This is not
  hypothetical: `NoFactoryViewModelRule` sat dormant for weeks, and an ADR credited it
  with a catch it could not have made.
- Does the script that backs it still run in `check.sh`, and does that step still fail on
  a violation? `check.sh` used to swallow detekt failures with a `|| echo` and no
  `exit 1`, while the docs described lint as report-only.
- Does `just docs-audit` exit non-zero on a finding, or only print it?

## 5 — Vocabulary and structure

- `docs/CONTEXT.md` — do the domain terms still mean what they say? A term that drifted is
  worse than a missing one, because it will be used correctly-looking.
- `ARCHITECTURE.md` §1 source tree — regenerate with
  `./scripts/print-source-tree.sh --output /tmp/source-tree.md` and diff.
- `docs/SKILLS-CATALOG.md` — regenerate with `just docs-regen`; a stale catalog is worse
  than none, because it looks current.

## 6 — Write it down

Every finding that is not fixed becomes a line in
`docs/decisions/2026-09-27-doc-and-skills-sprint-findings.md` (or a new sprint-findings ADR
if this is a later audit), tagged with the section it came from. The point is that the next
audit starts from the last one's leftovers rather than from zero.

## Related

- `singularity-todo-decisions-workflow` — how to write the ADR when a fix needs one
- `writing-for-agents` / `SKILL-MECHANICS.md` — the size budgets and split conventions
- `singularity-todo-scheduled-maintenance` — the runtime-measurement counterpart
