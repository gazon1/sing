"""maestro._types — constants, exit codes, path roots, and DeviceDeps."""
from __future__ import annotations

import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from emulator._startup import Acquisition

# 5 parents: _types.py at scripts/src/emulator/maestro/ → repo root (limpet/).
ROOT = Path(__file__).resolve().parent.parent.parent.parent.parent
GW = ROOT / "gw"
APP_ID = "com.singularity.todo"

# Exit codes
EXIT_PASS = 0
EXIT_FAIL = 1


@dataclass(frozen=True)
class DeviceDeps:
    """Explicit device-layer dependencies for run_one_flow and CLI main.

    Passed as a parameter so tests can substitute fakes without patching module
    globals or dealing with stale closure captures from ``from X import Y``.

    Two fields encode contracts that were previously only conventions:

    * ``adb`` is for device-independent commands only (``devices``,
      ``start-server``, ``kill-server``).  Every device-scoped call goes through
      ``adb_on``, which injects ``-s <serial>`` itself.  Passing a bare serial to
      ``adb`` builds ``adb <serial> shell …``, which adb rejects as an unknown
      command — so the failure is silent and produces no artifact.
    * ``acquire_device`` returns an :class:`Acquisition` carrying the exit code,
      so a caller can tell a crashed emulator from a boot timeout from a lock
      timeout instead of logging one generic "could not obtain a device".

    All callables match the signatures of the real implementations in
    ``emulator.maestro._device``, ``emulator.maestro._subprocess``,
    ``emulator.maestro._provenance``, ``emulator.maestro._fixtures``, and
    ``emulator.maestro._artifacts``.  ``scripts/tests/test_device_contracts.py``
    enforces that by comparing ``inspect.signature`` against these annotations.
    """

    try_run: Callable[..., "subprocess.CompletedProcess | None"]
    device_alive: Callable[[str], bool]
    device_model: Callable[[str], str]
    maestro_saw_device_failure: Callable[[str], bool]
    apk_sha_from_device: Callable[[str, "DeviceDeps"], str | None]
    gradle_install_debug: Callable[[str], "subprocess.CompletedProcess"]
    acquire_device: Callable[[str], Acquisition]
    adb: Callable[..., str | None]
    adb_on: Callable[..., str | None]
    reset_adb_server: Callable[[], None]
    wait_for_app_resumed: Callable[[str, int], bool]
    cleanup_run_fixtures: Callable[[str, str, str, "DeviceDeps"], None]
    capture_artifacts: Callable[[str, str, Path, "DeviceDeps"], None]
    git_sha: Callable[["DeviceDeps"], str]


def _default_device_deps() -> DeviceDeps:
    """Lazily build the real DeviceDeps to avoid circular imports at module scope."""
    from emulator._startup import acquire_device
    from emulator._device import adb, adb_on, device_alive
    from emulator.maestro._artifacts import capture_artifacts
    from emulator.maestro._device import (
        device_model,
        gradle_install_debug,
        maestro_saw_device_failure,
        reset_adb_server,
        wait_for_app_resumed,
    )
    from emulator.maestro._fixtures import cleanup_run_fixtures
    from emulator.maestro._provenance import (
        apk_sha_from_device as _apk_sha_from_device,
        git_sha as _git_sha,
    )
    from emulator.maestro._subprocess import try_run

    def make_deps() -> DeviceDeps:
        # NOTE: closure captures 'make_deps' reference — safe because deps is
        # built once and returned, never re-bound.
        nonlocal adb, adb_on, device_alive, try_run
        deps = DeviceDeps(
            try_run=try_run,
            device_alive=device_alive,
            device_model=device_model,
            maestro_saw_device_failure=maestro_saw_device_failure,
            apk_sha_from_device=lambda serial: _apk_sha_from_device(serial, deps),
            gradle_install_debug=gradle_install_debug,
            acquire_device=acquire_device,
            adb=adb,
            adb_on=adb_on,
            reset_adb_server=reset_adb_server,
            wait_for_app_resumed=wait_for_app_resumed,
            cleanup_run_fixtures=cleanup_run_fixtures,
            capture_artifacts=capture_artifacts,
            git_sha=lambda: _git_sha(deps),
        )
        return deps

    return make_deps()