---
title: "One copy of each control, one truth about where a gate runs, and a scanner that follows constructor injection"
date: 2026-10-20
status: accepted
tags: ["tooling", "ci", "gates", "kotlin"]
---

## Context

The previous round (ADR `2026-10-20-gate-clusters-and-the-two-registries.md`)
made the two gate registries agree about what exists. It left four kinds of
statement in the tooling that nobody could check.

**A control was declared twice.** Every control lived as data in
`_registry.py` and as a hand-written twin in `check-gate-wiring.py`: the file
it changes, the mutation, the reason. The copies drifted. The registry's
`rule-intent` control named a `detekt.yml` pattern that had not been in the
file since the rules were reorganised — and because the twin was the copy that
ran, the stale one never fired and nothing said so. The `workflow-exit-status`
control had the same defect in a quieter form: its `old` held the two
characters `\n` where a newline belonged, so the sabotage it described could
never have matched.

**A placement was prose.** `NOT_RUN_IN_STATIC` says which registered gates are
not lines in `static-gates.sh` and where they run instead. The reason was a
sentence; a renamed CI step would have left the sentence reading exactly as it
did.

**A command was not a gate, and nothing said which was which.** `sgate --help`
lists the gates and the utilities side by side. One of them was dead:
`unwired_backlog_refs` was a shim for a script that went with its gate in
2026-10-19, so the command could only fail.

**The scanner could not see constructor injection.** A DI binding that asks for
a type without naming it — `single { HlcFactory(deviceIdProvider = get(), …) }`
— has the type in the constructor's signature, and a scan that reads
`get<X>()` sees nothing. Twenty-eight types were in the allowlist because of
it, each with a note saying the scanner could not follow them.

## Idea

A statement the tooling makes about itself should be a pointer somebody can
check. Where that is not possible, the statement should be derivable.

## Decision

**A control is declared once.** The registry holds it; the harness executes it.
`scripts/src/sgate/gate_controls.py` holds the appliers, the gate records and
the tables; `check-gate-wiring.py` is 563 lines shorter and imports them. The
reachability scan moves to `scripts/src/sgate/gate_reachability.py`, because two
callers ask the same question — the wiring gate over every surface, the
registry checker over the gate lines — and the delegation walk was written
twice, free to disagree about what "reachable" means. Both modules are loaded
by path, not as `sgate.<name>`: `sgate` is installed as an editable package, and
a sibling worktree can be what is installed, so a module loaded by path is
always this checkout's. The
twins keep what the registry cannot know — the command a control runs and the
harness flags around it. The appliers learned the two edit kinds the registry
could already express but nothing executed: `hooks_path` and
`strip_suppression_reason`.

The registry's stale `workflow-exit-status` edit is fixed, and the
`script conventions` mode disagreement the merge introduced is settled in the
registry's favour — the shell registry was right: the corpus is green now that
the rule reads the AST, so the gate blocks.

**A placement is a pointer.** Each entry in `NOT_RUN_IN_STATIC` names the step
that runs the gate — `.github/workflows/ci.yml#Gates are alive` — and
`check-waiver-references.py` resolves it. A renamed step breaks the
declaration instead of leaving a sentence that still reads as it did. The
flake-analysis step got a name so it can be pointed at.

**Every command module is classified.** A gate, a utility, a suite, the
driver, or the machinery — and `scripts/check-gate-registries.py` checks the
list against the files on disk in both directions. The dead
`unwired-backlog-refs` command is gone.

**The gate tooling has its own job.** The wiring gate, the liveness probe and
the gate scripts' unit tests used to run in the `tests` job, behind 40 minutes
of Gradle, where they added nothing to the wall time of the job that matters:
they ran *after* it. They are a `meta` job now, parallel with the JVM tests.
The wiring gate stays in the `tests` job — one of its controls reads the JUnit
XML the Gradle step produces, and a control that cannot read its input proves
nothing.

**The scanner follows constructor injection.** The class's primary constructor
says what each argument is; a bare `get()` in that position is a request for
that parameter's type; a named argument names the parameter directly. The
resolution is one-sided on purpose: a type is marked consumed only when the
scan can name the constructor and the parameter, and a miss leaves the finding
where it was.

**The cluster wrappers' `--only` is a contract.** An unknown member is an
error, because a wrapper that selects nothing reports success, and that is
what a typo in a control would look like.

## Rationale

A control that is declared twice is a control that will be wrong twice. The
copy that rotted was never the copy that ran, so no gate failed and no report
said so — the failure mode this repository has been writing ADRs about for a
week. One declaration, exercised by Part B on every run, cannot rot that way:
a stale `old` in the registry is now applied to the real file, and the applier
raises when the text is not there.

A placement in prose is the same failure one level up. The waiver mechanism
already worked this way for exemptions and for declared asymmetries — each
names the file that proves it, and the pointer is resolved. A gate that runs
in another job is the same kind of claim and gets the same treatment.

The dead command is what "a command is not a gate" costs when nothing says so.
`sgate --help` cannot tell the reader; a classification can, and a check can
keep it true.

The scanner's limit was the reason an allowlist existed at all, and the
allowlist had become a place where the debt went instead of being named. With
the limit lifted, 29 of the 33 names resolved themselves. The four that remain
are the ones the scan still cannot see and nobody should: two features that
have no production call site by design, and two seams that are bound and
asked for by nothing. The entries that stood for them named a different type
and files that no longer ask for them — which is exactly why they stayed
invisible.

## Consequences

- **The gate tooling is 563 lines shorter, and the control machinery has a
  home.** `scripts/src/sgate/gate_controls.py` is the place to look for what a
  control does.
- **Part B is now the proof for every control.** It applies the registry's
  edit to the real file; a registry whose edit no longer matches its file says
  so instead of passing.
- **The `meta` job is a separate check.** A broken gate is red while the app's
  own tests are still running, instead of after them.
- **A placement that cannot be resolved fails the waiver gate.** The flake
  step had to be named for it to be pointed at.
- **A new command module has to be classified**, or the registry checker says
  so.
- **The allowlist shrank from 33 names to 4.** The unwired-surfaces floor
  stays at 0.
- **A wrapper asked for a member it does not have fails** instead of running
  nothing and reporting success.

## Links

- `scripts/src/sgate/gate_controls.py` — the appliers, the records, the tables
- `scripts/src/sgate/commands/_registry.py` — the registry, the controls, the
  placements, the module classification
- `scripts/check-gate-registries.py` — the gate that compares the registries
- `scripts/check-waiver-references.py` — the gate that resolves a pointer
- `scripts/find-unwired-surfaces.py` — the scanner that follows constructor
  injection
- `docs/decisions/2026-10-20-gate-clusters-and-the-two-registries.md` — the
  round before this one
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md` — the
  rule these changes apply
