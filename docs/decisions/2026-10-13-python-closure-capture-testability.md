# ADR: Python Testability — Closure Capture Anti-Pattern

**Date:** 2026-10-13
**Status:** accepted

## Context

`scripts/src/emulator/maestro/_runner.py` uses top-level imports:

```python
from emulator.maestro._device import device_alive, maestro_saw_device_failure, ...
from emulator.maestro._subprocess import try_run
from emulator.maestro._provenance import apk_sha_from_device
```

These bindings are **direct function references** captured at import time. When a test
patches `module.attr = fake_fn`, the binding inside `run_one_flow()` is **not updated**
because Python's `from X import Y` creates a local alias, not a indirection.

This makes `run_one_flow()` impossible to test without patching **both**:
- `module.X` (the source of the binding)
- `runner.X` (the captured alias inside the function)

The same problem affects any function that uses `from X import Y` for testable
dependencies.

## Decision

Introduce an explicit **dependency struct** (`DeviceDeps`) passed as a parameter.
All testable primitives are accessed through this struct, so tests patch one object.

### Before

```python
from emulator.maestro._device import device_alive, maestro_saw_device_failure
from emulator.maestro._subprocess import try_run
from emulator.maestro._provenance import apk_sha_from_device

def run_one_flow(...):
    ...
    if not maestro_saw_device_failure(stderr) and device_alive(serial):
        ...
    cp = try_run(cmd, ...)
```

### After

```python
from dataclasses import dataclass
from typing import Callable

@dataclass(frozen=True)
class DeviceDeps:
    try_run: Callable[[list[str], int], CompletedProcess | None]
    device_alive: Callable[[str], bool]
    maestro_saw_device_failure: Callable[[str], bool]
    apk_sha_from_device: Callable[[str], str | None]
    gradle_install_debug: Callable[[str], CompletedProcess]
    ensure_device: Callable[[str], str | None]
    adb: Callable[..., str | None]
    reset_adb_server: Callable[[], None]

# Default platform implementation (lazily imported to avoid import cycles).
def _default_deps() -> DeviceDeps:
    from emulator.maestro._device import device_alive, adb, ensure_device, gradle_install_debug, maestro_saw_device_failure, reset_adb_server
    from emulator.maestro._provenance import apk_sha_from_device
    from emulator.maestro._subprocess import try_run
    return DeviceDeps(
        try_run=try_run,
        device_alive=device_alive,
        maestro_saw_device_failure=maestro_saw_device_failure,
        apk_sha_from_device=apk_sha_from_device,
        gradle_install_debug=gradle_install_debug,
        ensure_device=ensure_device,
        adb=adb,
        reset_adb_server=reset_adb_server,
    )

def run_one_flow(..., deps: DeviceDeps | None = None) -> tuple[bool, str]:
    if deps is None:
        deps = _default_deps()
    ...
    if not deps.maestro_saw_device_failure(stderr) and deps.device_alive(serial):
        ...
    cp = deps.try_run(cmd, ...)
```

### Test impact

```python
fake_deps = DeviceDeps(
    try_run=fake_try_run,
    device_alive=fake_device_alive,
    ...
)
ok, run_id = run_one_flow(..., deps=fake_deps)
```

**One patch point. No stale closures.**

## Consequences

- All callers of `run_one_flow` that don't pass `deps` get the real platform
  implementation — backward-compatible.
- `_runner.try_run`, `_runner.device_alive`, etc. remain for emergency patching
  but tests should migrate to the `deps` parameter.
- The same pattern should be applied to any new Python code that needs testing
  and currently uses `from X import Y` for testable dependencies.
- A detekt custom rule (Python) could flag `from X import Y` in modules that
  also contain functions calling those imports, as a testability warning.

## Related

- ADR `2026-10-04-python-test-helpers` (if exists)
- Skill: `singularity-todo-test-helpers`
