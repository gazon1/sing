---
name: speckit-engineering-repeat-check
description: "At the REPEAT gate, suggest the next logical improvement or refactoring step from the artifact corpus. Use as input to the REPEAT gate decision."
disable-model-invocation: true
---

## Purpose

At the REPEAT gate of the engineering SDD workflow, this skill inspects the
completed change's artifacts (and optionally the broader corpus) and surfaces
the most valuable next logical step for a follow-up pass.

It is not the gate itself — it feeds the gate's decision. The gate is a human
decision; this skill provides the options and evidence.

## Inputs

| Field | Description |
|---|---|
| `slug` | kebab-case change id of the completed pass |
| `scan_corpus` | `true` to also scan the broader openspec corpus for related debt | default: `false` |

## Process

### 1 — Read the completed pass artifacts

Read in order of decreasing specificity:

1. `openspec/changes/<slug>/when-done.md` — audit findings and next-step candidates
2. `openspec/changes/<slug>/results.md` — verification results and divergences
3. `openspec/changes/<slug>/design.md` — what was built and any open questions
4. `openspec/changes/<slug>/verification.md` — what was verified and what was not

Build a list of candidate next steps from these sources, keeping the source
location for each.

### 2 — Score and rank candidates (optional: scan corpus)

If `scan_corpus=true`, also scan the broader corpus:

```
openspec/changes/*/when-done.md
openspec/changes/*/results.md
docs/decisions/*.md
```

Look for:
- Patterns that appear in multiple changes (indicating a systemic problem)
- ADRs that were recorded but not acted on
- Skills that were recommended in prior WHEN DONE audits but not yet authored

Score each candidate:

| Score | Meaning |
|---|---|
| `high` | Directly follows from completed work; next logical step |
| `medium` | Valuable but requires more scope than a direct follow-on |
| `low` | Would help but is speculative or deferred |

### 3 — Present findings for the REPEAT gate

Format the output as a decision input for the REPEAT gate:

```
## REPEAT Gate Input — {{ slug }}

### Candidates (ranked)

1. **[high] <title>**
   From: `openspec/changes/<slug>/when-done.md`
   What: <description>
   Why next: <rationale>

2. **[medium] <title>**
   From: `docs/decisions/<adr>.md`
   What: <description>
   Why next: <rationale>

### Recommended REPEAT direction
<title of the highest-scored candidate>

### Rationale
<2-3 sentence justification for continuing vs. closing>
```

## Outputs

| Output | Description |
|---|---|
| REPEAT gate input | Structured markdown presented to the human at the REPEAT gate |

## Notes

- This skill does NOT decide whether to REPEAT — it informs the decision.
- Candidates from `when-done.md` take priority; they were already vetted by the
  agent as worth surfacing.
- If no candidates are found, return `REPEAT: not recommended — no further
  logical steps found in the artifact corpus.` and let the gate recommend closing.
- `scan_corpus=true` is expensive; use it only on major milestones or when the
  change revealed cross-cutting patterns.
