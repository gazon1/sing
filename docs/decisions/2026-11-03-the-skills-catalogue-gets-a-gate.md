---
title: The skills catalogue gets a gate
date: 2026-11-03
status: accepted
tags: [tooling, gates, skills, docs]
---

# The skills catalogue gets a gate

## Context

`docs/SKILLS-CATALOG.md` is generated from the frontmatter of every
`SKILL.md`: it is the index an agent chooses a skill from, so a stale row — a
line count or a description that no longer matches the file — sends it to the
wrong file.

The generator grew a `--check` mode for exactly that, and nothing called it.
The correction was a convention instead of a check: the file said "do not edit
this by hand", and "do not edit by hand" discourages the wrong edit without
catching the edit nobody made. The catalogue was committed stale on a clean
tree — three skills recorded 20 to 22 lines below their real size after the
quality-tools skill was split — and the check mode would have failed on that
tree.

The registry agreed with the convention rather than with the tree. It carried
an exemption for `scripts/regen-skills-catalog.sh` that said the script had no
positive control, and the reason it gave named a `skills-catalog` gate that did
not exist.

## Idea

A gate is a declaration, not a script. The generator already knows how to
compare the catalogue with what it would write, and how to refuse when a skill
body stops parsing. What was missing was the call.

## Decision

- **The gate is declared in the one registry**, the way every other gate is:
  a `GateSpec` and a `Control` in `scripts/src/sgate/commands/_registry.py`,
  the `ScriptGate` twin in `scripts/src/sgate/gate_controls.py`, and the
  `Placement` in `scripts/src/sgate/gate_manifest.py`. The shell registry and
  the gate-registry document are rendered from that declaration and go into the
  commit with it.
- **The gate runs the generator's `--check` mode.** It does not re-implement
  the comparison, and it does not re-implement the refusal: the generator
  owns both, and a second copy of either would be a second thing to keep
  right.
- **The gate's line in the shell registry runs the script directly, not through
  `sgate`.** `sgate run-all` asks every `sgate`-dispatched gate for `--json`,
  and that wrapper keeps only the child's stdout — while the generator's
  verdict, the fix to run or the refusal, is on stderr. Dispatched through
  `sgate`, the failure would reach CI as a bare `exit 1` and the reader would
  not know what to do about it. Run directly, the failure says what to run.
  (`sgate the_skills_catalogue_is_current` still exists — the registry builds
  it from the `GateSpec` — and it is the way to run the gate by hand.)
- **The positive control is a sabotage of the generated file itself**, the
  shape the other registered gates use: `docs/SKILLS-CATALOG.md` is changed,
  the gate must fail, and the harness puts the file back. The exemption that
  said the script needed no control is gone — it was true when it was written
  and false now, and a reason that outlived its gate is a hole wearing a
  reason.

## Rationale

The stale catalogue was invisible for the same reason a stale generated table
was invisible twice before it: the correction was a habit, and a habit is not
checked. The generator could already answer the question; the gate is the
question being asked.

The refusal is a verdict of its own and is kept as one. A skill whose
frontmatter stops parsing would vanish from the index, and an index that
silently omits an entry is worse than a failed run — so the generator refuses
to write. A gate that flattened that refusal into "the catalogue differs" would
send the reader to re-run a generator that refuses again, and the second
failure would look like the first.

The dispatch form is not a style preference. It decides whether the failure
names its own fix: the JSON contract keeps the child's stdout, and the
generator's message is not on stdout.

## Consequences

- A change to a skill's frontmatter has to regenerate the catalogue in the same
  commit. The catalogue is generated, and the generator is now asked to check
  that the committed file is what it would write.
- A skill whose frontmatter stops parsing fails the static suite, naming the
  file — before it can disappear from the index.
- The gate is a line in the shell registry, so it runs from CI's static job and
  from the local check script, and it carries a control that proves it can
  fail.
- The generator is unchanged. It grew the check mode; this decision only
  calls it.

## Links

- `scripts/regen-skills-catalog.sh` — the generator, and the `--check` mode
- `scripts/src/sgate/commands/_registry.py` — the `GateSpec` and the `Control`
- `scripts/src/sgate/gate_manifest.py` — the `Placement`
- `scripts/src/sgate/gate_controls.py` — the control's twin
- `scripts/tests/test_regen_skills_catalog.py` — pass, stale, and refusal
- `docs/architecture/registering-a-gate.md` — how a gate is registered
- ADR `2026-10-21-one-manifest-and-one-placement-table.md` — one manifest, one
  placement table
- #1844
