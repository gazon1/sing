# ADR: Python Testing Idioms for This Project

**Date:** 2026-10-13
**Status:** accepted

## 1. loguru Handler Idempotency

### Problem
`loguru` adds a default handler to `sys.stderr` at import time (handler id `0`).
Any module that calls `_logger.remove(0)` assumes no prior setup. When modules are
imported in a different order, the second `remove(0)` raises `ValueError: There is
no existing handler with id 0`.

This causes silent test failures (tests that import the package fail on import)
and production crashes if import order changes.

### Rule
Always wrap `_logger.remove(0)` in `try/except ValueError`:

```python
try:
    _logger.remove(0)
except ValueError:
    pass
```

Do **not** assume a particular import order. The default handler may or may not
already be removed by the time your module is imported.

---

## 2. Click CLI Testing — Use CliRunner

### Problem
Calling `_cli.main([...])` directly in a test raises `click.exceptions.Exit`
with `exit_code=0` from `ctx.exit()`. This is **not** a `SystemExit` raised by
Python's `sys.exit()` — it is Click's own exception that propagates through the
call stack and is invisible to plain `try/except SystemExit`.

### Rule
Always use `click.testing.CliRunner` for testing Click-based CLIs:

```python
from click.testing import CliRunner

runner = CliRunner()
result = runner.invoke(cli.main, ["--arg", "value"])
# result.exit_code  — int
# result.output     — str (captured stdout/stderr)
# result.exception  — Exception if unexpected error
assert result.exit_code == 0, f"output={result.output}"
```

Do **not** call `cli.main([...])` directly in tests.

---

## 3. FakeMaestro: No `field()` Outside Dataclass Context

### Problem
`FakeMaestro.__init__` originally contained:
```python
from dataclasses import dataclass, field
...
self.calls: list[Path] = field(default_factory=list)
```
`field()` is a `dataclasses` descriptor. When used outside a `@dataclass`-decorated
`__init__`, it returns a `Field` object, not a list. Any call to
`self.calls.append(...)` fails with `AttributeError: 'Field' object has no
attribute 'append'`.

### Rule
Never use `field()` or other dataclass descriptors outside `@dataclass` bodies:

```python
# WRONG
class Foo:
    def __init__(self):
        self.items: list[int] = field(default_factory=list)

# RIGHT
class Foo:
    def __init__(self):
        self.items: list[int] = []
```

---

## 4. Python Closure Capture Anti-Pattern → Use DeviceDeps

See ADR `2026-10-13-python-closure-capture-testability`.

Summary: functions that use `from X import Y` at module scope capture `Y` as a
direct object reference. Patching `module.Y` later has no effect on the captured
binding inside the function. Fix: pass test dependencies via an explicit struct
parameter (e.g., `DeviceDeps`) rather than patching module globals.

---

## 5. Subprocess Mocking — Always Return Mock Objects with `.returncode`

### Problem
`subprocess.run(...)` returns a `CompletedProcess` object. Tests that return `None`
from a mocked `subprocess.run` or `try_run` can cause `AttributeError` if the
caller accesses `cp.returncode` on a `None` value.

### Rule
Always return a properly-configured `unittest.mock.Mock` (or `MagicMock`) when
mocking subprocess calls:

```python
cp = unittest.mock.Mock()
cp.returncode = 0
cp.stdout = ""
cp.stderr = ""
return cp   # NOT None (unless the real impl also returns None on OSError)
```

The real `try_run` returns `None` only on `OSError`; `run` always returns
`CompletedProcess`. Match the real contract.

---

## Related

- ADR `2026-10-13-python-closure-capture-testability`
- Skill: `singularity-todo-test-helpers`
