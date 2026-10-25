---
title: "The suite measures this checkout, not the one that happens to be installed"
date: 2026-10-25
status: accepted
tags: ["tooling", "ci", "gates"]
---

## Context

The gate suite failed in 72 places — in *every* tree, including a clean
`origin/main`. The failures were not about this repository's code. They were
about a file that is not in it.

The cause was in the environment. A user-site install of `singularity-repo-tools`
— the package behind the `sgate` CLI — was an **editable** install pointing at a
neighbouring checkout that no longer existed:

```
~/.local/lib/python3.14/site-packages/__editable__.singularity_repo_tools-0.1.0.pth
  → /home/max/orca/workspaces/singularity_cllone_kmp/gulper/scripts/src/sgate
```

An editable install registers an `_EditableFinder` on `sys.meta_path`, and the
finder is **appended** — so any ordinary `sys.path` entry resolves first. The
neighbouring tree was gone, the mapping still answered for it, and
`~/.local/bin/sgate` (a console script for that same dead path) could not even
be imported. Every tree that ran the suite got the dead tree's package: a
different registry, a different set of gate commands, a different set of
helpers. The tests that exercise those failed on assertions about code the
checkout does not contain. The number 72 says nothing; the diagnostics point in
every direction at once.

The suite did not fail on this tree and it did not measure this tree. It
measured another one, and the failures were the other tree's shape.

The same shape had a second face in the pre-commit hook: it compiled the
**working tree**, not the tree of the commit being made, so on a branch with one
commit per idea the hook refused the next idea's commit for the previous idea's
breakage — or, worse, let a commit through that did not compile on its own.
The hook now holds the tree at the index while it checks (`scripts/staged-tree.py`),
and the end-to-end case that was missing — a branch with an earlier commit plus
an uncommitted, non-compiling half — is a test of its own.

Two things made the failure hard to read rather than hard to fix.

**The measurement was silent.** A run that measured the wrong tree looked like a
run that found a lot. Nothing named the tree that was actually read, so the
report could not be compared with the tree in front of the reader — the same
defect ADR `2026-10-21-one-owner-of-stdout.md` records for the stdout channel:
two owners, and the wrong one wins quietly.

**The venv and the checkout were the same thing.** The gate CLI is installed
editable, so the venv does not merely *supply libraries* to the gate — it
supplies the gate's **code**. `scripts/ci/setup-local-tools.sh` nevertheless
named one venv for the whole machine (`~/.venvs/singularity-todo`), contradicting
its own advice, `scripts/setup-worktree.sh`, the preflight and the
`build-and-gates` skill, all of which said that a venv belongs to a checkout.
Two checkouts, one venv, one measured tree.

## Idea

The environment may supply **libraries**; the checkout supplies **code**. A
measurement has to be about the tree it runs in, so the code half of that
division belongs to the checkout, and the environment must not be able to
override it by being installed last.

That is a binding, not a prohibition. The developer's machine may hold a dozen
checkouts and a dozen installs; the suite has to measure the one it runs in and
say which one it measured.

## Decision

**The runner binds the package to its own checkout, and measures that.** Before
the suite starts, `scripts/run-gate-tests.py` drops the editable finders from
`sys.meta_path`, puts this checkout's `scripts/src` first on `sys.path`, and
exports the same path first in `PYTHONPATH` so the children the suite spawns
inherit the binding. The origin the environment *was* serving is reported in
stderr next to the tree that was actually measured:

```
run-gate-tests: the environment's sgate serves …/gulper/scripts/src/sgate; this run measured …/jellyfish/scripts/src/sgate
run-gate-tests: measured …/jellyfish/scripts/src/sgate
```

**Refusal is reserved for the case where there is nothing to measure.** If this
checkout's own package cannot be imported, the suite refuses (exit 2) and says
so — a run that would measure whatever else answers `import sgate` is not a red
run, it is no run at all. A foreign install in reach is *not* a reason to
refuse: the whole point is that the run still measures the right tree. The
origin report runs as a subprocess **with the environment as it was**, because a
report run under the binding would answer about the binding instead.

**One binding rule for every script.** Any script that imports `sgate.*` binds
the package to its own tree *before* the import:

```python
_SCRIPTS_SRC = str(pathlib.Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)
```

One rule, not two: the earlier rule had a special case for the package's public
API, and a special case is a second rule that drifts. The only exemption is a
`# no-sgate:` marker in the header, and it belongs to the scripts that do not
import `sgate` at all — `check-gate-tooling-origin.py` cannot ask where the
package came from by importing it. The rule is enforced by
`scripts/check-script-conventions.py` (finding R2.5), which reports a binding
placed *after* the import exactly as it reports a missing one.

**The venv is named after the checkout, and the installer refuses to take over a
foreign one.** `scripts/ci/setup-local-tools.sh` defaults to
`~/.venvs/singularity-todo-$(basename "$ROOT")`, and a venv there that serves
another checkout is refused rather than recreated — recreating it silently
repoints that checkout's gates at this one, which is the same shadowing one
step earlier. A path named explicitly (`SINGULARITY_TOOLS_VENV`) is the
caller's decision and is still taken over, with a warning. `scripts/setup-worktree.sh`
no longer hands the path to the installer, so the refusal is reachable there too,
and the installer's own message reaches the caller instead of being swallowed.

**The venv holds libraries; the checkout holds code.** The pinned binaries
(`lychee`, `shellcheck`, `actionlint`) are the same bytes in every checkout, so
they live in one shared store, keyed by version
(`~/.venvs/_tools/<tool>-<version>/bin/<tool>`), and each venv's copy is a
symlink into it: one copy per version on the machine instead of one per
checkout — `lychee` alone is 303 MB, and thirty checkouts were 8.9 GB of
identical bytes. The split is the one the private Gradle home already makes
(ADR `2026-10-04-gradle-daemon-isolation`): what is read-mostly is shared,
what belongs to a checkout stays private. The checkout's own code — the
editable install of `scripts/src/` — is the private half, and it is the half
that must never be shared.

**The two entry points share one module.** `./sg` and `scripts/run-gate-tests.py`
each bind the package before anything else runs, and they used to bind it in two
spellings: the same finder-scrubbing logic written out twice, in two files
that had to agree. It now lives in `scripts/package-binding.py`, which both
load **by path**. It cannot be imported: the runner has to work when the
package is missing — that is the case it refuses — and `./sg` has to work when
the package is broken, so the thing that repairs the package cannot live
inside it.

**A control's clean run is a command, not a template.** Before each control the
harness asks whether the gate it proves is green on a clean tree: a gate that
is already red proves nothing about the sabotage. For a fixture control that
question has to name a command, and the command cannot be the fixture's own —
the fixture command is a template (`deno test {tmp}/function`), and running a
template literally asks a command about a path that was never there. The
answer is "failed", and the harness read that answer as "this gate is already
red": a finding about a path that did not exist, filed against the wrong
cause, and pointing at the gate instead of at the harness. `FixtureGate`
therefore carries a `clean_cmd` — the gate's own command on the real input
(`deno test supabase/functions/google-oauth-exchange/`). A control that
declares a clean run and names no clean command is refused where it is
declared.

**A command that is not on PATH is not a verdict.** The same harness reported a
missing binary as a failing gate: on a machine without `deno` the control said
the gate was already red — a finding about the machine, filed as a finding
about the gate. A missing command now has its own answer (127, the shell's
"command not found"), and the harness names the command that is missing instead
of guessing what its absence meant.

## Rationale

- **A pin is a measurement; a refusal is an absence.** Refusing to run because
  the environment holds a foreign install leaves the developer with nothing —
  and the suite's job is to measure this tree, which it can do. `sgate run-all`
  already measured this checkout regardless of what was installed
  (ADR `2026-10-21-one-owner-of-stdout.md`), and `./sg` does the same; the
  suite now agrees with them instead of being the one surface that does not.
- **The name of the measured tree belongs in the output.** A verdict that does
  not say what it is about cannot be compared with the tree in front of the
  reader. "Measured" is the same rule as the stdout contract: one owner for the
  channel, and the document says which tree it describes.
- **One rule beats a rule and an exception.** Two rules (public API here,
  internals there) drift, and the drift is invisible until a script lands in
  the wrong half. The exemption that survives is not "this script looks
  different" but "this script does not import the package" — a fact, checkable
  by reading the imports.
- **Libraries from the environment, code from the checkout.** The venv is the
  right home for `junitparser`, `lychee`, `yamllint`, `shellcheck` and
  `actionlint` — they are the same everywhere. The `sgate` package is not: it
  is this repository's code, installed editable. A rule that keeps code out of
  the environment entirely would also keep the libraries out; the rule that
  actually holds the line is that the environment may not decide which tree is
  measured.
- **The installer contradicted the rest of the repository.** One shared venv
  name was the root of the shadowing: whichever checkout installed last owned
  the code for all of them. The name is now per-checkout, and the installer
  stops rather than taking a venv that serves another checkout — a takeover is
  a decision, and an unnamed one is not a decision.

## Consequences

- The suite measures this checkout whatever the environment holds. A foreign
  install is named in stderr instead of deciding the result.
- 42 scripts gained the binding — 36 gate scripts and 6 test modules — and the
  rule that requires it is checked by a gate: a script that loses its binding
  is red before it is run.
- The gate scripts can be run directly (`python3 scripts/check-*.py`) without
  the environment deciding which package answers `import sgate`.
- The installer refuses to take over a foreign venv and says both ways out; a
  worktree setup that used to hand the path over no longer does.
- The stale user-site install was removed from the host. That is a change
  outside the repository: the rollback is `uv pip install --editable scripts/src`
  into this checkout's own venv.
- A refusal now means exactly one thing — this checkout's package cannot be
  imported — so a red suite and a refused suite are not confused.

## Links

- ADR `2026-10-21-one-owner-of-stdout.md` — the same rule for the stdout
  channel: one owner, and the document says what it is about.
- `scripts/run-gate-tests.py` — `pin_local_package`, `refuse_unmeasurable_tooling`.
- `scripts/package-binding.py` — the binding both entry points use.
- `scripts/check-script-conventions.py` — rule R2.5, the binding rule.
- `scripts/ci/setup-local-tools.sh` — the per-checkout venv, the shared store of
  pinned binaries, and the refusal.
- `scripts/check-gate-tooling-origin.py` — the origin question, asked without
  importing the package.
- `scripts/src/sgate/gate_controls.py` — the controls, and the clean command
  each one names.
- `scripts/check-gate-wiring.py` — the harness that runs them, and what a
  missing command is allowed to mean.
- `scripts/tests/test_check_gate_wiring.py`,
  `scripts/tests/test_run_gate_tests.py`,
  `scripts/tests/test_check_script_conventions.py` — the rules as tests.
- `scripts/tests/test_pre_commit_hook.py` — the hook checks the commit's tree,
  not the working tree.
