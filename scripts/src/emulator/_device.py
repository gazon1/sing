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

import os
import shutil
import subprocess
import time
from pathlib import Path
from typing import Callable, NamedTuple

# ── subprocess helpers ─────────────────────────────────────────────────────────

class CommandOutcome(NamedTuple):
    """What one command produced, and — the part that matters — *why* it failed.

    A timeout is not a failure. Under a loaded host a five-second adb call can
    miss its deadline while the device is perfectly well, and a runner that
    reads "no answer" as "the device is gone" then resets the adb server and
    makes the device disappear for everyone for four seconds. Keeping the two
    apart is what lets the runner retry a slow device instead of killing it.
    """

    ok: bool
    stdout: str
    stderr: str
    code: int | None
    timed_out: bool


def try_run_detailed(args: list[str], *, timeout: float = 5) -> CommandOutcome:
    """Run a command and report what happened, keeping a timeout distinguishable.

    This is the seam the tests fake: it returns what happened rather than
    whether the command succeeded, because "it did not answer in time" and
    "it failed" are different facts and the runner acts on the difference.
    """
    try:
        cp = subprocess.run(
            args,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            text=True,
        )
    except subprocess.TimeoutExpired as exc:
        return CommandOutcome(
            False,
            _as_text(exc.stdout),
            _as_text(exc.stderr),
            None,
            True,
        )
    except OSError as exc:
        return CommandOutcome(False, "", str(exc), None, False)
    return CommandOutcome(
        cp.returncode == 0,
        cp.stdout.strip(),
        cp.stderr.strip(),
        cp.returncode,
        False,
    )


def _as_text(value: object) -> str:
    """TimeoutExpired carries bytes or None depending on how it was raised."""
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace").strip()
    return str(value).strip()


def try_run(args: list[str], *, timeout: float = 5) -> subprocess.CompletedProcess | None:
    """Run a command, return None on non-zero exit or timeout."""
    outcome = try_run_detailed(args, timeout=timeout)
    if not outcome.ok or outcome.code is None:
        return None
    return subprocess.CompletedProcess(args, outcome.code, outcome.stdout, outcome.stderr)


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

#: How many times a timed-out adb call is retried, and how long it waits first.
#:
#: The retry exists because the host, not the device, is usually what missed the
#: deadline: a loaded machine queues the adb round trip behind everything else.
#: The second attempt finds the queue shorter. A call that *failed* is not
#: retried — a device that is gone will still be gone, and retrying only makes
#: the runner slower to notice.
ADB_TIMEOUT_RETRIES = 2
ADB_RETRY_BACKOFF_SEC = 0.5


def adb_outcome(
    *args: str,
    timeout: float = 5,
    retries: int = ADB_TIMEOUT_RETRIES,
) -> CommandOutcome:
    """Run `adb <args>` and report the outcome, retrying a timeout."""
    attempt = 0
    while True:
        outcome = try_run_detailed(["adb", *args], timeout=timeout)
        if not outcome.timed_out or attempt >= retries:
            return outcome
        attempt += 1
        time.sleep(ADB_RETRY_BACKOFF_SEC * attempt)


def adb(*args: str, timeout: float = 5, retries: int = ADB_TIMEOUT_RETRIES) -> str | None:
    """Run `adb <args>` for a device-independent command; stdout on success, else None.

    Do not put a device serial in here.  Use :func:`adb_on`.
    """
    outcome = adb_outcome(*args, timeout=timeout, retries=retries)
    return outcome.stdout if outcome.ok else None


def adb_on(
    serial: str,
    *args: str,
    timeout: float = 5,
    retries: int = ADB_TIMEOUT_RETRIES,
) -> str | None:
    """Run `adb -s <serial> <args>`; stdout on success, None on failure or timeout.

    Raises ``ValueError`` on an empty serial rather than running `adb shell …`
    against whatever single device happens to be attached.  An unscoped command is
    the defect this function removes, so it must fail loudly instead of guessing.
    """
    if not serial:
        raise ValueError(
            "adb_on requires a serial; use adb() for device-independent commands"
        )
    outcome = adb_outcome("-s", serial, *args, timeout=timeout, retries=retries)
    return outcome.stdout if outcome.ok else None


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
    """True when the device answers a shell round trip.

    Not ``get-state``: after the host's gfxstream bug kills the render thread,
    adb keeps reporting the serial as ``device`` for a while before it flips to
    ``offline``, so a state check sees a healthy device that cannot serve a
    single command (ADR 2026-09-29-emulator-crash-recovery-runner). The round
    trip is the question that actually matters — can the device do work.
    """
    if not serial:
        return False
    return probe_device_health(serial).state == "alive"


class DeviceHealth(NamedTuple):
    """Is the device gone, or merely slow?

    The distinction is the whole point. A loaded host makes the adb round trip
    miss its deadline while the device is fine; reading that as "gone" makes the
    runner reset the adb server, and the reset makes the device disappear for
    every other client as well.
    """

    state: str  # "alive" | "slow" | "gone"
    detail: str


def probe_device_health(serial: str, *, timeout: float = 15.0) -> DeviceHealth:
    """Answer whether the device is gone, slow, or answering."""
    if not serial:
        return DeviceHealth("gone", "no serial was given")
    outcome = adb_outcome(
        "-s", serial, "shell", "echo", "ping",
        timeout=timeout,
        retries=ADB_TIMEOUT_RETRIES,
    )
    if outcome.ok:
        return DeviceHealth("alive", "the device answered a shell round trip")
    if outcome.timed_out:
        return DeviceHealth(
            "slow",
            f"the round trip did not answer within {timeout}s — the device is "
            "busy or the host is loaded, which is not the same as absent",
        )
    return DeviceHealth("gone", outcome.stderr or outcome.stdout or "the device did not answer")


def emulator_process_alive(proc_root: Path | None = None) -> bool:
    """Whether an emulator process is running on this host.

    Args:
        proc_root: Where to look for processes. Only the tests pass anything
            other than the real ``/proc``.

    Independent of adb on purpose. The adb server can be reset while the
    emulator keeps running, and telling those two apart is the difference
    between "retry the flow" and "relaunch the AVD".

    The ``/proc`` exe link is the source of truth here, not ``pgrep``: a pattern
    search matches the command line of the shell running the search, so it
    reports a running emulator when there is none (ADR
    2026-09-29-emulator-crash-recovery-runner).
    """
    try:
        entries = list((proc_root or Path("/proc")).iterdir())
    except OSError:
        return False
    for entry in entries:
        if not entry.name.isdigit():
            continue
        try:
            exe = os.readlink(entry / "exe")
        except OSError:
            continue
        if "qemu-system" in exe:
            return True
    return False