---
created: 2026-10-13
tags: [python, testing, architecture, emulator]
---

# ADR: DeviceDeps — explicit parameter injection for device-layer primitives

## Context

The `emulator/` Python package manages Android emulator lifecycle and Maestro flow
execution.  Four internal modules each needed access to the same set of device
primitives:

- `adb(...)`                     — run `adb <args>`; return stdout on success
- `device_alive(serial)`         — true when the device is booted and responsive
- `try_run(args, timeout)`        — subprocess with timeout, returns `CompletedProcess`
- `ensure_device(serial)`         — recover or start a device, return serial
- `gradle_install_debug(serial)`  — run `./gw :androidApp:installDebug`
- `apk_sha_from_device(serial)`  — read git SHA suffix from the installed APK
- `git_sha()`                    — git rev-parse of the current checkout
- `capture_artifacts(...)`         — screenshot + logcat at flow failure
- `cleanup_run_fixtures(...)`      — send cleanup deeplink to the app

The naive approach — importing these directly at module level — broke testing.
A test that wanted to substitute a fake `adb` had to either (a) patch
`sys.modules`, (b) rebind the name in the module's `__dict__`, or (c) use
`unittest.mock.patch.object`.  All three approaches are fragile:

```python
# Fragile: patching sys.modules or __dict__ before the import runs
with patch.dict(sys.modules, {"emulator._device": fake_device}):
    from emulator._device import adb   # may already be cached
```

Python's module-level names are resolved once at import time.  A stale closure —
where a function captured the original binding before a test could patch it —
was the root cause of at least two silent test failures during the first batch
of emulator refactoring.

## Decision

Introduce a single frozen dataclass `DeviceDeps` that bundles all device-layer
callables.  Every callable that touches the device, subprocess, or file system
in the hot path (`run_one_flow`, `main`) accepts `deps: DeviceDeps` as a
parameter.  The real implementation is built lazily by `_default_device_deps()`
to avoid circular-import problems.  Tests construct a `DeviceDeps` with all
fakes and pass it directly:

```python
fake_deps = DeviceDeps(
    try_run=fake_try_run,
    device_alive=lambda s: True,
    adb=fake_adb,
    git_sha=lambda: "abc123def456",          # no subprocess needed
    capture_artifacts=lambda *a, **kw: None,  # no-op in tests
    ...
)
ok, run_id = run_one_flow(..., deps=fake_deps)
```

### Field signatures

`DeviceDeps` is defined in `emulator/maestro/_types.py`.  Each field is a
`Callable`.  Field names match the conceptual operation, not the underlying
implementation:

| Field | Signature |
|---|---|
| `try_run` | `Callable[[list[str], int?], CompletedProcess \| None]` |
| `device_alive` | `Callable[[str], bool]` |
| `device_model` | `Callable[[str], str]` |
| `maestro_saw_device_failure` | `Callable[[str], bool]` |
| `apk_sha_from_device` | `Callable[[str, DeviceDeps], str \| None]` |
| `gradle_install_debug` | `Callable[[str], CompletedProcess]` |
| `ensure_device` | `Callable[[str], str \| None]` |
| `adb` | `Callable[..., str \| None]` |
| `reset_adb_server` | `Callable[[], None]` |
| `wait_for_app_resumed` | `Callable[[str, int], bool]` |
| `cleanup_run_fixtures` | `Callable[[str, str, str, DeviceDeps], None]` |
| `capture_artifacts` | `Callable[[str, str, Path, DeviceDeps], None]` |
| `git_sha` | `Callable[[DeviceDeps], str]` |

Functions that need to call other `DeviceDeps` fields receive `deps` as the
last parameter so they can forward it.  This avoids a frozen-dataclass
self-reference problem: `apk_sha_from_device(serial)` calls `deps.adb(...)`
and `git_sha(deps)` calls `deps.try_run(...)`.

### Lazy construction to avoid circular imports

`_default_device_deps()` is called once in `_cli.main()` / `run_one_flow()`.
It lives in `_types.py` and imports the real implementations lazily:

```python
def _default_device_deps() -> DeviceDeps:
    from emulator._device import adb, device_alive, ensure_device
    from emulator.maestro._device import device_model, gradle_install_debug, ...
    from emulator.maestro._provenance import apk_sha_from_device, git_sha
    ...
    def make_deps() -> DeviceDeps:
        nonlocal adb, device_alive, ...  # captures from outer scope
        deps = DeviceDeps(..., apk_sha_from_device=lambda s: _fn(s, deps), ...)
        return deps
    return make_deps()
```

The `nonlocal` + `make_deps` pattern lets `deps` reference itself in closures
without rebinding the outer-scope variable before the object is constructed.

## Consequences

**Positive:**
- Tests are fully isolated; no `sys.modules` patching, no `__dict__` rebinding.
- The set of device-layer operations is exhaustively documented in one place.
- Adding a new device primitive is a one-line addition to `DeviceDeps` and a
  one-line update to `_default_device_deps` — the call site is immediately visible.
- The `DeviceDeps` fields serve as a natural seam for measuring coverage: a test
  that exercises every field can be written as a simple matrix.

**Negative:**
- Call signatures grow by one parameter (`deps`).  Updating 14 call sites was a
  one-time migration cost (2026-10-13, PR #593).
- Functions that need to delegate to other `DeviceDeps` fields require the
  `make_deps()` / `nonlocal` pattern to avoid premature closure capture.  This is
  a known pattern, documented above.
- `emulator._device` and `emulator._startup` (the cold path, outside of
  `run_one_flow`) still use module-level imports.  These are intentionally
  exempt: the DI contract covers the hot path (`main` → `run_one_flow` → per-flow
  logic).  Adding `DeviceDeps` to the cold path would require threading it
  through `ensure_device()` calls in startup, which is out of scope.

## Rationale for the frozen dataclass

A frozen `dataclass` was chosen over a `Protocol` or `TypedDict` because:
- It is hashable (useful if `DeviceDeps` ever needs to be a dict key or set member).
- It is immutable at runtime, preventing accidental field mutation during a flow.
- It is constructable with `DeviceDeps(field1=fn1, ...)` inline in tests.
- `frozen=True` at construction time means typos in field names surface as
  `TypeError` at the call site, not silent wrong-function calls.

## See also

- ADR `2026-10-13-python-closure-capture-testability` — documents the
  module-level import problem and why `from X import Y` at module level creates
  stale closures.
- ADR `2026-10-13-python-testing-idioms` — documents the test helper patterns
  (`FakeMaestro`, `AdbCall`, `Clock`, `FakeProc`, `FakeAdb`) built on top of
  this DI layer.
- Skill `singularity-todo-device-deps-pattern` (pending: to be created).
