"""emulator._device — adb wrappers and device readiness detection.

Two rules this module exists to enforce:

* ``adb`` is for **device-independent** commands only (``devices``, ``start-server``,
  ``kill-server``).  Anything scoped to a device goes through ``adb_on``, which
  injects ``-s <serial>`` itself.  Passing a bare serial as the first argument is
  not a shorter spelling of ``adb_on`` — it produces ``adb <serial> shell …``,
  which adb rejects as an unknown command, and the call silently produces no
  evidence.  That failure shipped once; see ADR 2026-10-13-ensure-device-acquires.
* Readiness is reported with the name of the probe that failed, so a wedged
  device is distinguishable from an unauthorized one in the logs.
"""
from __future__ import annotations

import shutil
import subprocess
from typing import Callable, NamedTuple

# ── subprocess helpers ─────────────────────────────────────────────────────────

def try_run(args: list[str], *, timeout: float = 5) -> subprocess.CompletedProcess | None:
    """Run a command, return None on non-zero exit or timeout."""
    try:
        return subprocess.run(
            args,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            text=True,
        )
    except (subprocess.TimeoutExpired, OSError):
        return None


def popen(args: list[str], stdout_fd: int | None = None) -> subprocess.Popen:
    """Start a background process; stdout goes to stdout_fd if given."""
    devnull = None
    if stdout_fd is None:
        devnull = subprocess.DEVNULL
    return subprocess.Popen(
        args,
        stdout=stdout_fd if stdout_fd is not None else devnull,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )


# ── AVD primitives ─────────────────────────────────────────────────────────────

#: The header `emulator -list-avds` prints above the names.
_LIST_AVDS_HEADER = "Available Android Virtual Devices"


def list_avds() -> list[str] | None:
    """The AVDs this machine can boot, as ``emulator -list-avds`` reports them.

    ``None`` means the ``emulator`` binary is not on PATH — a different failure
    from "the AVD you asked for is not among them", and one the caller has to
    name rather than fold into an empty list.

    The AVD home can hold a directory the tool refuses to boot (a system image
    that is gone, a device profile that no longer resolves), and the configured
    name is a claim, not a fact. This is the only authority on what exists.
    """
    exe = shutil.which("emulator")
    if exe is None:
        return None
    result = try_run([exe, "-list-avds"], timeout=30)
    if result is None or result.returncode != 0:
        return []
    names = []
    for line in result.stdout.splitlines():
        line = line.strip()
        if not line or line.startswith(_LIST_AVDS_HEADER):
            continue
        names.append(line)
    return names


# ── adb primitives ─────────────────────────────────────────────────────────────

def adb(*args: str, timeout: float = 5) -> str | None:
    """Run `adb <args>` for a device-independent command; stdout on success, else None.

    Do not put a device serial in here.  Use :func:`adb_on`.
    """
    cp = try_run(["adb", *args], timeout=timeout)
    return cp.stdout.strip() if cp and cp.returncode == 0 else None


def adb_on(serial: str, *args: str, timeout: float = 5) -> str | None:
    """Run `adb -s <serial> <args>`; stdout on success, None on failure or timeout.

    Raises ``ValueError`` on an empty serial rather than running `adb shell …`
    against whatever single device happens to be attached.  An unscoped command is
    the defect this function removes, so it must fail loudly instead of guessing.
    """
    if not serial:
        raise ValueError(
            "adb_on requires a serial; use adb() for device-independent commands"
        )
    return adb("-s", serial, *args, timeout=timeout)


# ── readiness ──────────────────────────────────────────────────────────────────


class ReadyResult(NamedTuple):
    """Outcome of a readiness sweep, naming the probe that rejected the device.

    ``failed_probe`` is ``None`` when the device is ready, otherwise one of the
    names in :data:`_READY_PROBES`, or ``"serial"`` when no serial was supplied.
    """

    ok: bool
    failed_probe: str | None


#: Probes, in order, that a device must pass before it is considered usable.
#: Each entry is (name, adb args, timeout, accept predicate).
#:
#: The last two exist because of the host's gfxstream bug: the device reports
#: ``sys.boot_completed == 1`` while its render thread has already died
#: (ADR 2026-09-29-emulator-crash-recovery-runner).  ``get-state`` alone stays
#: ``device`` for a while after that, so it cannot be the readiness signal.
_READY_PROBES: tuple[tuple[str, tuple[str, ...], float, Callable[[str], bool]], ...] = (
    ("get-state", ("get-state",), 5.0, lambda out: out == "device"),
    ("boot_completed", ("shell", "getprop", "sys.boot_completed"), 5.0, lambda out: out == "1"),
    # pm path android resolves to "package:<path>" only once packages are indexed;
    # a booted-but-still-indexing emulator returns empty or times out.
    ("package_manager", ("shell", "pm", "path", "android"), 10.0,
     lambda out: out.startswith("package:")),
    ("surfaceflinger", ("shell", "dumpsys", "SurfaceFlinger"), 10.0,
     lambda out: "SurfaceFlinger" in out),
)


def probe_device_ready(serial: str) -> ReadyResult:
    """Run each readiness probe in order; report the first that fails."""
    if not serial:
        return ReadyResult(False, "serial")
    for name, args, timeout, accept in _READY_PROBES:
        if not accept(adb_on(serial, *args, timeout=timeout) or ""):
            return ReadyResult(False, name)
    return ReadyResult(True, None)


def device_is_ready(serial: str) -> bool:
    """True when adb knows the device, it is booted, and key services answer.

    Thin bool wrapper over :func:`probe_device_ready`; call that directly when the
    reason for a rejection matters (recovery paths, diagnostics).
    """
    return probe_device_ready(serial).ok


def first_ready_device() -> str | None:
    """Return the first device adb lists that is ready, or None."""
    for line in (adb("devices") or "").splitlines()[1:]:
        parts = line.split()
        if len(parts) == 2 and parts[1] == "device" and device_is_ready(parts[0]):
            return parts[0]
    return None


def find_ready(pin: str) -> str | None:
    """Return pin if it is ready; otherwise find any ready device (when pin is empty)."""
    if pin:
        return pin if device_is_ready(pin) else None
    return first_ready_device()


def device_alive(serial: str) -> bool:
    """True when adb can reach the device (get-state == device), without full boot checks."""
    if not serial:
        return False
    return adb_on(serial, "get-state") == "device"