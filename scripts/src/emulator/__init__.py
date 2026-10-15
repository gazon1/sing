"""emulator — Android emulator lifecycle management for singularity-todo.

Public API:
    ensure_device(pin)  → str | None   (serial of a ready device; acquires one)
    acquire_device(pin) → Acquisition  (serial + exit code, never raises)
    Config                          (mutable configuration singleton)
    EXIT_*                          (exit code constants)
    AVD, LOCK_FILE, EXTRA_FLAGS, BOOT_TIMEOUT, LOCK_TIMEOUT
"""
from __future__ import annotations

from emulator._cli import main as cli
from emulator._device import (
    adb,
    adb_on,
    device_is_ready,
    find_ready,
    first_ready_device,
    popen,
    probe_device_ready,
    try_run,
)
from emulator._startup import (
    Acquisition,
    _avd_is_running,
    clear_stale_emulator,
    ensure_device,
    start_avd,
    startup_lock,
)
from emulator._types import (
    EXIT_CRASHED,
    EXIT_LOCK,
    EXIT_NOT_READY,
    EXIT_OK,
    EXIT_TIMEOUT,
    Config,
    cfg,
)

# Re-export UPPER_CASE config names from the cfg singleton so that
# `emulator.AVD` and `_emu.AVD` keep working without __getattr__.
# Tests patch these directly:  emulator.AVD = 'Pixel_7'.
AVD = cfg.AVD
LOCK_FILE = cfg.LOCK_FILE
EXTRA_FLAGS = cfg.EXTRA_FLAGS
BOOT_TIMEOUT = cfg.BOOT_TIMEOUT
LOCK_TIMEOUT = cfg.LOCK_TIMEOUT

__all__ = [
    # CLI entry point
    "main",
    # Device primitives
    "_avd_is_running",
    "adb",
    "adb_on",
    "device_is_ready",
    "find_ready",
    "first_ready_device",
    "popen",
    "probe_device_ready",
    "try_run",
    # AVD lifecycle
    "Acquisition",
    "acquire_device",
    "clear_stale_emulator",
    "ensure_device",
    "startup_lock",
    "start_avd",
    # Exit codes
    "EXIT_OK",
    "EXIT_NOT_READY",
    "EXIT_CRASHED",
    "EXIT_TIMEOUT",
    "EXIT_LOCK",
    # Configuration — UPPER_CASE aliases of cfg properties
    "cfg",
    "Config",
    "AVD",
    "LOCK_FILE",
    "EXTRA_FLAGS",
    "BOOT_TIMEOUT",
    "LOCK_TIMEOUT",
]