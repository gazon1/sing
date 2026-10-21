"""emulator — Android emulator lifecycle management for singularity-todo.

Public API:
    main              → the CLI entry point
    start_avd, startup_lock, clear_stale_emulator
    device_is_ready, find_ready, first_ready_device, popen, try_run
    cfg               (the mutable configuration singleton)
    EXIT_*           (exit code constants)

**What is not here is as deliberate as what is.** A name that nothing imports
is not an API — it is a second copy of the thing it re-exports, and the copy is
the one that drifts. The configuration lives in `cfg` and only in `cfg`; there
used to be UPPER_CASE aliases of its fields here, and the tests that patched
them patched a copy nobody read: the code reads `cfg.avd`, so
`emulator.AVD = 'Pixel_7'` changed nothing at all. A stale alias is not a
harmless extra name; it is a patch target that silently does nothing.
"""
from __future__ import annotations

from emulator._cli import main
# Also available as `.cli` for callers that prefer not to shadow the name `main`.
cli = main
from emulator._device import (
    device_is_ready,
    find_ready,
    first_ready_device,
    popen,
    try_run,
)
from emulator._startup import (
    _avd_is_running,
    clear_stale_emulator,
    start_avd,
    startup_lock,
)
from emulator._types import (
    EXIT_CRASHED,
    EXIT_LOCK,
    EXIT_NOT_READY,
    EXIT_OK,
    EXIT_TIMEOUT,
    cfg,
)

__all__ = [
    # CLI entry point
    "main",
    # Device primitives
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
    # Configuration
    "cfg",
]
