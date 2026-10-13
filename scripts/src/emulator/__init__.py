"""emulator — Android emulator lifecycle management for singularity-todo.

Public API:
    ensure()    → str | None   (serial of a ready device)
    Config                          (mutable configuration singleton)
    EXIT_*                          (exit code constants)
    AVD, LOCK_FILE, EXTRA_FLAGS, BOOT_TIMEOUT  (runtime configuration)
"""
from __future__ import annotations

import sys as _sys
from emulator._cli import main as cli
from emulator._device import (
    adb,
    device_is_ready,
    find_ready,
    first_ready_device,
    popen,
    try_run,
)
from emulator._startup import (
    _avd_is_running,
    clear_stale_emulator,
    startup_lock,
    start_avd,
)
from emulator._types import (
    EXIT_OK,
    EXIT_NOT_READY,
    EXIT_CRASHED,
    EXIT_TIMEOUT,
    EXIT_LOCK,
    cfg,
    Config,
)

# Canonical fields on cfg that tests patch via module-level attribute access.
# NO module-level AVD/LOCK_FILE/etc. variables here — only __getattr__.
# This ensures that:
#   1. emulator.AVD always goes through __getattr__ (or finds the patched dict entry)
#   2. When a test does emulator.AVD = 'Pixel_7', it writes to emulator.__dict__
#   3. _startup.py accesses emulator.AVD, which finds the patched value in __dict__
_CFG_FORWARD = frozenset({
    "AVD",
    "LOCK_FILE",
    "EXTRA_FLAGS",
    "BOOT_TIMEOUT",
    "main",
})


def __getattr__(name: str):
    if name == "main":
        return cli
    if name in _CFG_FORWARD:
        return getattr(cfg, name.lower())
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


def __setattr__(name: str, value) -> None:
    if name in _CFG_FORWARD:
        _sys.modules[__name__].__dict__[name] = value
    else:
        object.__setattr__(_sys.modules[__name__], name, value)


__all__ = [
    # CLI entry point (forwarded via __getattr__ to avoid a top-level import cycle)
    "main",
    # Device primitives
    "_avd_is_running",
    "adb",
    "device_is_ready",
    "find_ready",
    "first_ready_device",
    "popen",
    "try_run",
    # AVD lifecycle
    "clear_stale_emulator",
    "startup_lock",
    "start_avd",
    # Exit codes
    "EXIT_OK",
    "EXIT_NOT_READY",
    "EXIT_CRASHED",
    "EXIT_TIMEOUT",
    "EXIT_LOCK",
    # Configuration (forwarded via __getattr__ to cfg; supports test patching)
    "cfg",
    "Config",
    "AVD",
    "LOCK_FILE",
    "EXTRA_FLAGS",
    "BOOT_TIMEOUT",
]
