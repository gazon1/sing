---
title: "The Maestro lane measures this checkout, not the one that happens to be installed"
date: 2026-11-04
status: accepted
tags: ["tooling", "maestro", "worktrees"]
---

## Context

`run-maestro` and `ensure-emulator` are console scripts of the same editable
install as `sgate`:

```
scripts/src/pyproject.toml
[project.scripts]
sgate = "sgate.cli:main"
ensure-emulator = "emulator._cli:main"
run-maestro = "emulator.maestro._cli:main"
```

and the Maestro runner resolves its root from the *installed package's* file:

```
scripts/src/emulator/maestro/_types.py
ROOT = Path(__file__).resolve().parent.parent.parent.parent.parent
```

Five parents up is the repository root — of the checkout that was
`pip install -e scripts/src/`-ed. The `just` recipes called `run-maestro`
straight from `PATH`, so the flows they ran and the APK they installed were
another checkout's, and the report came back green against the tree in front of
the reader.

The repository already knew this failure mode. It is why `./sg` exists
(`scripts/package-binding.py` — "the one place that knows how to bind `sgate` to
a checkout"), and `scripts/check-gate-wiring.py` records the same accident for
the gate package: `import sgate` resolved into `…/sprat/scripts/src/sgate`
while the gate under test was `…/noddy`.

`package-binding.py` rebound **`sgate` only**. The `emulator`, `scanners` and
`traceability` packages are installed from the same place and had no equivalent
wrapper, so the Maestro lane was the one part of the worktree loop that did not
measure its own tree.

## Idea

Bind the packages the way `sgate` is bound, and call them through a wrapper
that names the current checkout.

`package-binding.py` already loads a package by path and puts this checkout's
`scripts/src` first — and the four packages are siblings under `scripts/src/`,
so one binding owns all of them. What was missing was a wrapper that used it
for the Maestro lane, and recipes that went through the wrapper.

## Decision

1. `scripts/package-binding.py` names the packages of the install
   (`PACKAGES = ("emulator", "scanners", "sgate", "traceability")`) and can say
   which of them do not resolve to this checkout (`foreign_packages`). The
   binding itself is unchanged: the editable finders go, this checkout's
   `scripts/src` goes first on `sys.path` and first in `PYTHONPATH`.
2. A new `./rm` beside `./sg` binds the packages and then execs the entry point
   in the same process: `./rm` is the Maestro runner, `./rm emulator` is
   `ensure-emulator`, `./rm traceability` is the traceability layer, and
   `./rm whereami` names the checkout the wrapper belongs to.
3. The wrapper refuses to run when a package does not resolve to this checkout,
   and says which one. A run that measures the wrong tree is not a red run — it
   is no run at all.
4. The callers call the wrapper: `ui-maestro` and `gate-maestro` use `./rm`,
   the traceability recipes use `./rm traceability` instead of
   `python3 -m traceability`, and `check.sh`'s opt-in Maestro lane uses `./rm`
   instead of the `run-maestro` console script.

## Rationale

- **The report was green and the flows were not the ones in the tree.** A
  measurement of somebody else's checkout is worse than a failure: it looks
  like a pass. The binding makes the tree the runner reads the tree the report
  is about.
- **One binding, four packages.** They are installed together from
  `scripts/src/`, so they are exposed together; binding them separately would
  have been four copies of the same accident.
- **The refusal is loud on purpose.** `./rm whereami` names the checkout in one
  command, so a mismatch is a fact the reader can see instead of something
  inferred from a report about flows nobody wrote.
- **The wrapper is in the same shape as `./sg`.** It loads the binding module
  *by path* — the packages may be the thing that is broken, so the thing that
  repairs them cannot live inside them — and it drops the editable finders
  rather than trusting `PYTHONPATH` to outrank them.

## Consequences

- `just ui-maestro`, `just gate-maestro` and the `just trace-*` recipes run
  this checkout's flows, install this checkout's APK and read this checkout's
  `build/maestro-results`.
- `check.sh`'s opt-in Maestro lane (`RUN_MAESTRO=1`) runs this checkout's
  flows too, instead of the flows of whichever checkout was installed last.
- The Maestro lane joins the gate lane: the checkout is named by the tool that
  runs it (`./rm whereami`), not only by the tool that gates it (`./sg
  whereami`).
- The console scripts stay on `PATH` — they are the install, and the install
  still belongs to whichever checkout was installed last. The wrapper is what
  the recipes use, so the environment no longer decides what is measured.
- The skills that tell an agent to run `ensure-emulator` from `PATH` still do:
  the wrapper exists, but the documentation has not been swept yet.

## Links

- `docs/decisions/2026-10-25-the-suite-measures-this-checkout.md` — the same
  accident for the gate package, and the binding that answers it.
- `scripts/package-binding.py` — the binding every entry point uses.
- `rm` — the Maestro lane of this checkout.
- `sg` — the gate suite of this checkout.
- `scripts/tests/test_rm_wrapper.py` — the wrapper, as tests.
- `scripts/tests/test_package_binding.py` — the binding the wrapper uses, as tests.
