---
title: "An empty regex replacement is a deletion, and sgate refused to import because of it"
date: 2026-10-19
status: open
tags: ["tooling", "python", "ci", "gates"]
---

## Context

CI's `static` job on `main` was red. The log ends in `import sgate`:

```
File "/home/runner/work/sing/sing/scripts/src/sgate/commands/_registry.py", line 504
  'log-interpolation': Control(..., edit=Edit(kind='regex',
      pattern=r'    "com\.singularity\.todo\.feature\.tasks\.domain\.model\.TaskId",',
      repl='', flags='MULTILINE'), ...)
ValueError: edit 'regex' needs `repl`
```

`scripts/src/sgate/controls.py` validated the replacement with truthiness:

```python
if self.kind == "regex" and not self.repl:
    raise ValueError("edit 'regex' needs `repl`")
```

`not ""` is `True`, so `repl=''` was refused. But `repl=''` is not a *missing*
replacement — it is `re.sub(pattern, '', ...)`, a **deletion**, and it is exactly
what the `log-interpolation` control declares: it removes one line from the
allowlist so the gate reports that type as unexpected.

The check fires inside `Edit.__post_init__`, so it fires while `_registry.py`'s
module-level dict literal is being built. Importing the registry raised, and
`sgate run-all` therefore never dispatched a single gate. This is the failure the
module's own docstring names: *a gate that dies instead of reporting is
indistinguishable from a gate that passes* — except inverted, and worse. The whole
gate surface reported one verdict, and that verdict was "cannot start".

The stated intent of the check survives in the docstring: "an edit with nothing to
find is not representable". That intent is about `pattern`, not `repl`. A regex edit
with an empty pattern still cannot be declared, and the applier in
`scripts/check-gate-wiring.py` still asserts the substitution matched at least
once:

```python
new, n = re.subn(pattern, repl, text, count=count, flags=flags)
assert n >= 1, f"sabotage would be a no-op: {pattern!r} matched nothing in {p}"
```

So the loudness the design wants was never dependent on `repl` being non-empty. The
check was redundant with `pattern` and wrong about what `''` means.

## Idea

Make `repl` distinguish *unset* from *empty*, the way `flags: str = ""` already has
to be distinguished from "declared but meaningless":

```python
repl: str | None = None
...
if self.kind == "regex" and self.repl is None:
    raise ValueError("edit 'regex' needs `repl`")
```

`Edit(kind='regex', pattern=p)` — a declaration that forgot to say what to write —
still raises. `Edit(kind='regex', pattern=p, repl='')` — a deletion — constructs.
Both halves of the original intent are kept, and the crash stops.

## Decision

Adopt `repl: str | None = None` with the `is None` check. Reject the two obvious
alternatives:

- **Drop the check entirely.** Then a regex edit that forgets `repl` silently
  deletes the match instead of replacing it — a control that still passes for the
  wrong reason, which is the class of defect this module exists to prevent.
- **Keep `repl: str = ""` and special-case deletion with a flag.** A second field
  meaning "empty but deliberate" is a distinction the type already expresses. The
  `None` default costs nothing and makes the omission visible in the declaration.

## Rationale

The defect was not the falsy check alone; it was a falsy check guarding a value
whose empty case is meaningful, sitting on the import path of every gate. Both
facts had to be true for one `Edit` declaration to take the entire gate surface
down. The fix therefore has two halves, and only the second is interesting:

1. `repl` becomes `str | None`, checked with `is None`.
2. `scripts/tests/test_sgate_controls.py` imports `GATE_CONTROLS` and asserts the
   registry assembles.

The second half is the one that would have caught this earlier. A unit test that
only built an `Edit` in isolation stayed green while the module that assembles all
of them could not be loaded. `log-interpolation`'s `repl=''` is pinned by name, so
a future edit to that control — the most likely way the same crash returns — fails
in a test rather than in CI.

## Consequences

- `sgate` imports; `static` dispatches its gates. Measured on this tree after the
  fix: 26 blocking gates, 23 pass.
- **Fixing the import unmasked a second layer.** `scripts/tests` went from "cannot
  run" to 1004 tests with 3 failures and 12 errors. All 15 are pre-existing drift
  from the argparse→Click migration — `check-flaky-tests.py` is a
  `@click.command()` exposing `cli`, while its test still calls `cft.main()`, and
  `check-supabase-schema-integrity.py` exits `2` where the test expects argparse's
  `1`. They were invisible, not absent: the import crash meant they had not run in
  either state. They are a separate piece of work and are **not** fixed here.
- The environment carried a second copy of the same class of fault. A stale
  `sgate-0.1.0` editable install pointed `sys.path` at another worktree, so gate
  subprocesses — which run with `env={'PATH': ...}` and inherit no `PYTHONPATH` —
  kept importing the broken module regardless of the fix. Removed in favour of the
  current `singularity-repo-tools` distribution, which provides the same `sgate`
  console script.

## Links

- `scripts/src/sgate/controls.py` — `Edit.repl` and the `__post_init__` check
- `scripts/src/sgate/commands/_registry.py` — the `log-interpolation` control
- `scripts/check-gate-wiring.py` — `_regex_sub`, the only applier, and its `n >= 1` assert
- `scripts/tests/test_sgate_controls.py` — regression tests
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md` — the rule this broke
- `docs/decisions/2026-10-07-android-maintenance-needs-a-ci-gate.md` — the same shape seen from the other end