"""maestro._types — constants, exit codes, path roots, and DeviceDeps."""
from __future__ import annotations

import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from emulator._device import DeviceHealth
from emulator._device import emulator_process_alive as _emulator_process_alive
from emulator._device import probe_device_health as _probe_device_health
from emulator._startup import Acquisition

# 5 parents: _types.py at scripts/src/emulator/maestro/ → repo root (limpet/).
ROOT = Path(__file__).resolve().parent.parent.parent.parent.parent
GW = ROOT / "gw"
APP_ID = "com.singularity.todo"

# Exit codes
EXIT_PASS = 0
EXIT_FAIL = 1


def _junit_reports_device_failure(report: Path) -> bool:
    """Late-bound import: `emulator.maestro._device` imports this module."""
    from emulator.maestro._device import junit_reports_device_failure
    return junit_reports_device_failure(report)


def _app_installed(serial: str) -> bool:
    """Whether the app is on the device, for a bundle that does not say.

    The answer is "yes" for a bundle that does not care about the app's
    presence: a fake that is only about the flow's assertions should not have
    to answer a question about the device. The real bundle binds the real
    implementation, and that is the one a run uses.
    """
    return True


def _device_api_level(serial: str) -> int | None:
    """The API level, for a bundle that does not say.

    None means "not known", which is not the same as a known level — a
    measurement whose environment is unknown cannot be compared with a floor,
    and the gate that reads it says so.
    """
    return None


def _prepare_device(serial: str) -> None:
    """Prepare the device, for a bundle that does not say.

    The preparation is an action on a real device — wake it, unlock it, take
    the soft keyboard out of the way — so a bundle that does not care about
    the device gets nothing done to it. A fake that only asserts the flow's
    behaviour has no device to wake, and asking the real adb would wake a
    real one. The real bundle binds the real implementation, and that is the
    one a run uses.
    """
    return None


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
    apk_sha_from_device: Callable[[str], str | None]
    gradle_install_debug: Callable[[str], "subprocess.CompletedProcess"]
    acquire_device: Callable[[str], Acquisition]
    adb: Callable[..., str | None]
    adb_on: Callable[..., str | None]
    reset_adb_server: Callable[[], None]
    wait_for_app_resumed: Callable[[str, int], bool]
    cleanup_run_fixtures: Callable[[str, str, str, "DeviceDeps"], None]
    capture_artifacts: Callable[[str, str, Path, "DeviceDeps"], None]
    git_sha: Callable[["DeviceDeps"], str]

    # The two probes below carry the real implementation as their default, so a
    # bundle that does not care about them does not have to name them. They are
    # here because the recovery path needs to tell a *slow* device from a *gone*
    # one, and a bool cannot say that.
    #
    # * ``probe_device_health`` answers "gone" / "slow" / "alive". A loaded host
    #   makes the adb round trip miss its deadline while the device is working,
    #   and resetting the adb server for that hides the device from every other
    #   client for the seconds it takes to come back.
    # * ``emulator_process_alive`` asks the host, without adb, whether an
    #   emulator process is running at all — which is what decides between
    #   restarting the adb server and booting the AVD again.
    probe_device_health: Callable[[str], DeviceHealth] = _probe_device_health
    emulator_process_alive: Callable[[], bool] = _emulator_process_alive

    # * ``junit_reports_device_failure`` reads the flow's own JUnit report and
    #   says whether the device — not the flow — is what failed. Maestro writes
    #   a dead device into the report, and a runner that reads only the
    #   process's stderr counts that as a broken flow, which is not retried.
    junit_reports_device_failure: Callable[[Path], bool] = _junit_reports_device_failure

    # * ``app_installed`` answers "is the app on the device". A device can lose
    #   the app and keep answering adb, and a runner that does not ask cannot
    #   tell a flow that failed from a flow that was never measured.
    # * ``device_api_level`` is part of the environment a measurement was taken
    #   in, and the floor belongs to one environment.
    app_installed: Callable[[str], bool] = _app_installed
    device_api_level: Callable[[str], int | None] = _device_api_level

    # * ``prepare_device`` wakes the device, unlocks it and takes the soft
    #   keyboard out of the way — the meaning that was written three times,
    #   and the copy that was missing was the one that mattered: the AVD
    #   that had just been restarted came up with the IME on, and every
    #   flow measured after that measured a half-covered screen.
    #   A bundle that does not care about a device gets nothing done to it:
    #   a fake that only asserts the flow's behaviour has no device to wake.
    #   The real bundle binds the real implementation.
    prepare_device: Callable[[str], None] = _prepare_device


def _default_device_deps() -> DeviceDeps:
    """Lazily build the real DeviceDeps to avoid circular imports at module scope."""
    from emulator._device import adb, adb_on, device_alive
    from emulator._startup import acquire_device, prepare_device
    from emulator.maestro._artifacts import capture_artifacts
    from emulator.maestro._device import (
        app_installed,
        device_api_level,
        device_model,
        gradle_install_debug,
        maestro_saw_device_failure,
        reset_adb_server,
        wait_for_app_resumed,
    )
    from emulator.maestro._fixtures import cleanup_run_fixtures
    from emulator.maestro._provenance import (
        apk_sha_from_device as _apk_sha_from_device,
    )
    from emulator.maestro._provenance import (
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
            prepare_device=prepare_device,
            adb=adb,
            adb_on=adb_on,
            reset_adb_server=reset_adb_server,
            wait_for_app_resumed=wait_for_app_resumed,
            cleanup_run_fixtures=cleanup_run_fixtures,
            capture_artifacts=capture_artifacts,
            git_sha=lambda: _git_sha(deps),
            app_installed=app_installed,
            device_api_level=device_api_level,
        )
        return deps

    return make_deps()