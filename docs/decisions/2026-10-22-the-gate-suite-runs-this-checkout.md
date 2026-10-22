---
date: 2026-10-22
status: accepted
---

# The gate suite runs this checkout's code, and a gate is built from shared pieces

## Context

Two problems, both of which cost a debugging session each, and both of which
were invisible until the machine had more than one checkout of this
repository on it.

**The gates ran somebody else's code.** `sgate run-all` dispatched every gate
that goes through the sgate CLI through `shutil.which("sgate")` — the binary
on PATH. On a machine with several checkouts, that binary is an editable
install of *one* of them, and the gate suite then certified that checkout's
code while reporting the verdict as this checkout's. The ADR of 2026-10-21
already named the hazard for the JSON-contract probe and fixed it *there*;
the runner itself kept the hole.

**A gate was five files and four names.** Adding a gate meant a script, a
`GateSpec` in `_registry.py`, a `Control` (the change that must make it fail),
a `ScriptGate` in `gate_controls.py`, a `Placement` in `gate_manifest.py`, a
test, and four spellings of one name: the display name, the slug, the command
and the file. A name mistyped in one of them is a gate that does not run, or a
control that proves nothing. Forty-one such scripts had grown to fifteen
thousand lines, each with its own reader for a markdown table, its own reader
for the Kotlin sources, and its own idea of what `--check` and `--json` mean.

## Idea

Two changes, one per problem.

*The runner runs this checkout.* `sgate run-all` puts this checkout's
`scripts/src` on the import path of every gate it dispatches, and runs the
module rather than the binary. `sgate whereami` prints the checkout the
running sgate belongs to, and the preflight asks it — so a person whose
`sgate` on PATH belongs to another checkout is told, instead of being misled.

*A gate is built from shared pieces.* `sgate.gatekit` holds the four answers
a gate keeps re-deriving: what it found (`findings`), how to read the tables
a document claims things in (`markdown`), what the sources declare and mention
(`kotlin`), and the flags and the exit code every gate has (`cli`). A rule
that says *which* table of *which* document is data — a YAML file under
`config/gates/` — so a second table that follows the same convention needs a
file and no program.

And the ritual goes away: `scripts/ci/new-gate.py` writes the script's
entries into all three registries, derives the names, creates the test, and
checks that the registry still imports.

## Decision

1. **`sgate run-all` dispatches through the module of this checkout.** The
   environment of a gate subprocess carries this checkout's `scripts/src`
   ahead of anything else; the path comes from `sgate.repo`, not from a
   string written out in the caller.

2. **`sgate whereami` exists, and the preflight asks it.** The answer to
   "whose code is this?" can only come from the package that is running.

3. **`sgate.gatekit` is the shared vocabulary of a gate.** A gate that uses it
   reports `Finding`s with a severity, and only a **blocking** finding fails a
   `--check` run. A fact the code contradicts blocks; a claim read out of
   prose is printed and does not.

4. **A document-against-the-code rule is a YAML file.** The comparison is
   code, written once; the table, the columns and the naming convention are
   data.

5. **`scripts/ci/new-gate.py` registers a gate in one command.** It appends to
   the end of each collection — the order of the entries is a fact other
   things depend on, and a new gate inserted at the top of a list changes it
   for no reason.

## Rationale

- **The runner's job is to run *this* code.** A gate suite that certifies
  another checkout is worse than no gate suite: it is green, and it is about
  something else. The fix is not to trust the binary on PATH; the fix is not
  to use it.

- **A manual `PYTHONPATH` is not the arrangement.** The gate scripts' own
  unit tests run them with a minimal environment (`env={'PATH': …}`), and a
  package that is only on `PYTHONPATH` is invisible to them. The repository
  installs the package (`scripts/ci/setup-local-tools.sh`, an editable
  install into a uv venv) — the same arrangement CI uses — and the gate
  scripts put their own `scripts/src` in front of the import path, the way
  `scripts/check-gate-wiring.py` already does for `workflow_gradle`.

- **A guess about prose does not fail a build.** The navigation gate reads a
  claim of a push out of a sentence; a sentence that mentions the task stack
  and the word "push" is not always a claim. Blocking on that would make the
  build red for a sentence nobody has to fix, and the cost of that is a gate
  people learn to ignore.

- **The rule is data because the rule is what changes.** A document moves, a
  convention changes, a column is added — none of those is a change of
  program. The comparison, on the other hand, is the same for every table of
  that shape, and it is written once.

## Consequences

- Every gate dispatched by `sgate run-all` runs this checkout's code, on any
  machine, whatever `sgate` on PATH points at.

- `sgate whereami` is the answer to "whose code is this?" and the preflight
  asks it before a gate suite runs.

- Four gates (navigation doc, DI fixture, tools catalogue, content key, link
  grammar) are built from the shared pieces and report findings with
  severities; two of them are new.

- The local log level is INFO; `SGATE_LOG_LEVEL=DEBUG` asks for the rest.
  Thirty lines of "loaded sgate.commands.x" preceded every command's output.

- The sabotage harness restores atomically, keeps the original until the
  restore succeeded, repairs an interrupted run on the next run, and says what
  it would touch under `--dry-run`.

## Links

- `scripts/src/sgate/gatekit/` — the shared pieces.
- `scripts/ci/new-gate.py` — the one-command registration.
- `docs/tools/README.md` — the tool surface, in prose.
- `docs/decisions/2026-10-21-one-owner-of-stdout.md` — the hazard this closes.
- `openspec/changes/the-gates-run-on-every-machine/` — why the package is
  installed rather than put on `PYTHONPATH`.
