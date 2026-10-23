---
title: "The gates are this checkout, and a rule that matters is a gate that can fail"
date: 2026-10-22
status: open
tags: [gates, tooling, verification, ai]
---

# The gates are this checkout, and a rule that matters is a gate that can fail

## Context

The gate tooling is an editable-installed Python package: `sgate` lives in
`scripts/src/sgate` and is installed into a shared virtualenv. On a host with
several worktrees, that install points at whichever checkout ran the installer
last. Measured on 2026-10-22, `import sgate` resolved to a *sibling* worktree, and
the difference was invisible: the gate suite ran that checkout's registry — a
different set of gates — and reported them green.

Two consequences followed, and neither was a crash.

The first was a gate reading the wrong checkout. `scripts/check-gate-registries.py`
imported `sgate` before putting this checkout's `scripts/src` on the path, so the
script that exists to check the *registries* was checking the other worktree's
registries. The shell registry had the same shape: `scripts/ci/static-gates.sh`
invoked `python3 scripts/*.py` without a `PYTHONPATH`, so every python gate in the
suite resolved its imports the same way — from whatever was installed. The
preflight that was supposed to catch this asked a weaker question ("is `sgate`
importable?") than the one that mattered ("is `sgate` *this* checkout's?").

The second class of failure was a gate that was right about the question and
useless in the answer.

- The tool catalogue — `config/docs/tool-surface.json`, the exported description of
  every tool the model can call — was derived from the source and checked by
  nothing. It could drift from the code for months.
- When the catalogue *was* checked, a description written as a raw string (`"""…"""`)
  made the reader report that "the gate is reading the source wrongly", several
  hundred lines away from the place that broke. The literal's *form* was the cause,
  and the message did not say so.
- The write-path rule — a tool that writes a user's data must propose it and let
  the user decide — lived in a document. A document does not fail a build. At the
  time of this decision eight tools still wrote directly (`create_task`,
  `update_task`, `create_project`, `update_project`, `create_note`, `update_note`,
  `create_tag`, `decompose_and_create`), while the delete tools and
  `write_task_proposal` proposed; the same act got two different treatments,
  decided by which tool the model happened to pick. The conversion of those tools
  is the continuation of this decision, in
  `2026-10-22-the-write-path-has-one-shape.md`.
- A gate run produced a wall of output and, when something failed, no one-line
  answer to "what broke and what do I do".
- The rules an agent is judged by were scattered: some in `AGENTS.md`, some in
  skills, some nowhere.

## Idea

A gate has to belong to the checkout it is run in, and a rule that matters has to
be a gate that can fail — with a message that names the cause at the place it
broke.

## Decision

**A gate reads the checkout it runs in.** The python gates put this checkout's
`scripts/src` on the path before they import the gate tooling, and the shell
registry puts the same path in `PYTHONPATH` for every python gate it runs. The
preflight stops asking whether the gate CLI is importable and starts asking where
it came from; when it is another checkout's, the message says which path it
resolved to. The supported way to run the gates on a multi-worktree host is a
per-checkout virtualenv (`SINGULARITY_TOOLS_VENV`), so the editable install points
at the checkout being verified.

**A catalogue that can go stale is a catalogue that has to be checked.** The tool
surface has a verification task that exports the catalogue to a scratch file and
compares it with the committed one; a mismatch fails with the one-line command
that regenerates it. The check runs *before* the long test task, so a stale
catalogue is a fast failure instead of a twenty-minute one.

**A failure message names the cause where it broke.** A description that the
catalogue reader cannot parse is reported by the reader, with the form of the
literal named, instead of being reported downstream as a misreading.

**The write rule is a gate, and the gate holds a registry.**
`scripts/check-ai-write-tools.py` measures the state of the write path:
a tool that writes a user's data and is not listed in
`config/docs/ai-direct-writes.txt` fails the gate; a listed tool that no longer
writes directly fails it too (a stale entry hides the work); a tool that both
proposes and writes directly fails it (one tool, two treatments, and the user
cannot tell which one they are looking at). Each entry carries the reason the tool
writes directly, so the next reader can tell a missing proposal kind from a
decision to leave the tool alone.

**The registry is data, not a constant.** The list lives in a committed text file
under `config/docs/`, because a registry nobody can read without running a program
is a registry nobody reads, and because only a committed file can be mutated to
prove the gate can fail. The rule the list encodes is that the list gets
*shorter*: converting a tool to the proposal path removes its entry, and a list
that only grows is a list nobody is working through.

**A gate that cannot fail is not a gate.** The new gate has a positive control: a
registered control removes one entry from the registry and the gate must fail. The
control is part of the gate registry, so the wiring check fails if it disappears.

**The gates run as one command with a summary.** `sgate run-all --summary` prints
one line per failed gate — the gate's name and why it failed — and one line for
the result. The registry of gates stays the single source of truth, and the
summary is produced by the code that already knows each gate's result.

**The judgement rules live in a file.** `CODING_STANDARDS.md` holds the rules a
reviewing agent applies — read the change, not the diff; a write tool proposes;
a gate that cannot fail reports success forever; a message says what happened;
one implementation per question — with the table of checkable conventions and the
gate that enforces each one.

**`AGENTS.md` points; it does not restate.** The cheatsheet now says to use the
worktree-safe gradle wrapper, where the tool catalogue is exported, that the
catalogue is derived and must not be edited by hand, that the gate tooling needs a
per-checkout environment, that the desktop UI tests need a display and say so when
there is none, and that a long run is one background task with a uniquely named log
rather than a loop of polling.

## Rationale

A gate that measures another checkout's registry is worse than no gate: it is
green, and it is green about the wrong thing. The failure mode is invisible
precisely because the suite looks healthy — 29 gates checked where this checkout
declares 40, all of them passing, none of them the ones that were asked for. The
only defence is to make the origin of the tooling part of what the suite checks
before it runs, and to say out loud which path it resolved to when the answer is
not this checkout.

The write-path rule is the clearest case of a rule that has to become a gate. It
was written down, it was true, and eight tools kept not obeying it — because
nothing failed. A registry plus a gate turns "we agreed the write path asks first"
into "the build fails until the write path asks first", and the registry's
shrinking is the measure of how much of the work is left. The gate deliberately
does not decide which tools are right: that is a decision, and it belongs to the
change that converts a tool. The gate only measures the state, in both
directions — so a tool that was converted and left behind in the list cannot hide
that the work was done.

The catalogue needed the same treatment for the same reason. A derived file that
nothing checks is a file that becomes wrong; and the check that found the drift
was reporting the drift in the wrong place, so the person reading it had to go
and find the real cause themselves. A message that names the form of the literal
turns a hunt into a one-line fix.

Putting the summary inside the gate runner rather than in a wrapper script keeps
one source of truth: a wrapper would have to re-derive "which gate failed and why"
from the runner's output, which is the second implementation of a thing the runner
already knows.

## Consequences

- The gate tooling has to be installed per checkout, or the preflight fails. This
  is deliberate friction: a shared editable install across worktrees is a silent
  wrong-answer generator. The preflight names the fix instead of leaving the
  failure to be discovered as a mystery.
- A new tool that writes a user's data directly fails the build until somebody
  writes down why it does. That is the point: the reason is the artefact, and the
  artefact is what a later reader needs.
- The list of direct writers is expected to shrink. A change that adds an entry
  without moving a tool onto the proposal path has made the debt larger and said
  so.
- A change to the gate registry has to regenerate the shell registry and the
  registry document. Both are generated; the generator is the only supported way
  to change them.
- `CODING_STANDARDS.md` is a document, so it can go stale the way documents do.
  Its rules are paired with the gate that enforces each one, so a rule that
  matters can be checked rather than believed.
- The catalogue verification adds one fast step to `check.sh`. It runs before the
  long test task on purpose.

## Links

- `scripts/check-ai-write-tools.py` — the gate that measures the write path.
- `config/docs/ai-direct-writes.txt` — the registry of direct writers and reasons.
- `scripts/src/sgate/commands/_registry.py` — the gate registry and its controls.
- `scripts/src/sgate/gate_manifest.py` — where each gate is placed.
- `scripts/src/sgate/commands/run_all.py` — the gate runner and its summary mode.
- `scripts/ci/preflight-gate-tools.sh` — the origin check.
- `scripts/ci/static-gates.sh` — the generated shell registry.
- `scripts/render-adr-tools-section.py` — the catalogue reader that names the
  literal's form.
- `shared/build.gradle.kts` — the catalogue verification task.
- `CODING_STANDARDS.md` — the judgement rules for a reviewing agent.
- `docs/decisions/2026-10-22-the-write-path-asks-first.md` — the decision that
  started the write path.
- `docs/decisions/2026-10-28-a-registry-is-checked-not-written.md` — why the
  registry is data.
