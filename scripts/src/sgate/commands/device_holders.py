"""sgate.commands.device_holders — who holds a device right now?

Why this exists
---------------
A device is a shared resource, and the repository already knows it: a Maestro
run takes an exclusive lock on the serial it drives, one lock file per serial,
naming the PID that holds it (`emulator.maestro._lock.DeviceLock`).

But the lock answers a question only when you try to take it. There is no
way to *ask* — and asking is the question that matters before a run, not
after a collision.

On 2026-10-24 that cost two deliverables. The physical phone
(`0O65321F32204DD5`) and the AVD (`emulator-5554`) were busy with another
session's runs, and the answer had to be found by trying: a run started, the
lock said `already in use by Maestro run pid N`, and the work stopped. The
question "is a device free?" had no command, so it was answered by a failure
that cost a run.

So the answer gets a command:

    sgate device-holders              # every lock, and every connected device
    sgate device-holders --json       # the same, as JSON
    sgate device-holders --serial emulator-5554   # one device

A lock whose owner is gone is **stale**: the run died, and the device is free
even though the file says otherwise. The command says so, because "the file
exists" and "the device is held" are different facts.

What it does not do
-------------------
It does not take a device, and it does not interrupt anybody. It reads lock
files and asks `adb` what is connected — the two halves of the same question:
who claims it, and is it there.

Usage:
    sgate device-holders              # the report
    sgate device-holders --json       # {"locks": [...], "devices": [...]}
    sgate device-holders --serial X   # only X

Exit codes:
    0 = the report was printed
    1 = with --serial: that device is held by a live run
    3 = the lock directory could not be read
"""

from __future__ import annotations

import errno
import json as _json
import os
import pathlib
import subprocess

import click

from sgate.commands.base import CLEAN, CONFIG_ERROR, register

#: How long `adb devices` may take before the command gives up on it.
ADB_TIMEOUT_SECONDS = 10

def _lock_dir() -> pathlib.Path:
    """Where the device locks live.

    The directory is read from the environment **now**, not from the constant
    the emulator package captured when it was imported: the lock itself
    resolves its directory at access time, and a report that read a different
    directory than the lock writes to would say every device is free while a
    device is held.
    """
    from emulator.maestro._lock import LOCK_DIR

    return pathlib.Path(os.environ.get("MAESTRO_LOCK_DIR", str(LOCK_DIR)))


def _lock_names() -> tuple[str, str]:
    """The prefix and the suffix of a lock file's name.

    They are the emulator package's own: the lock that takes a device and the
    report that says who holds it have to agree on which file is the lock. A
    second copy of the name is a second answer to "which file holds this
    device?" — and when the two disagree, the report says every device is free
    while a device is held.
    """
    from emulator.maestro._lock import LOCK_PREFIX, LOCK_SUFFIX

    return LOCK_PREFIX, LOCK_SUFFIX


def _pid_alive(pid: int) -> bool:
    """Whether a process with this pid is running.

    This is **pid liveness, not identity**: after a pid is reused, a lock
    whose owner is gone is reported as held. The report says the pid, and the
    owner's command line, so a reader can see the mismatch — the same
    weakness the lock itself has, and the same answer it gives.
    """
    if pid <= 0:
        # `os.kill(0, 0)` would test the caller's own process group.
        return False
    try:
        os.kill(pid, 0)
    except OSError as exc:
        # ESRCH is the "no such process" answer; anything else (a permission
        # error, say) means the process is there and may not be ours to ask.
        return exc.errno != errno.ESRCH
    return True


def _command_line(pid: int) -> str:
    """The command line of a process, or an empty string.

    Read from `/proc`, so it is the command as it is running — which is what
    names the session that holds the device. It is the argv of a process of
    the same user, and it can carry tokens: the report prints the beginning
    of it, and the reader is the person who owns the machine.
    """
    try:
        raw = pathlib.Path(f"/proc/{pid}/cmdline").read_bytes()
    except OSError:
        return ""
    parts = [part for part in raw.decode("utf-8", "replace").split("\0") if part]
    # The program and the script, and nothing else: the arguments of another
    # session's command line can carry tokens, and the report lands in a
    # terminal's scrollback and in logs.
    return " ".join(parts[:2])


def _locks(lock_dir: pathlib.Path) -> list[dict[str, object]]:
    """Every lock in the directory, and what each one says.

    The directory is read with `os.scandir`, not with `glob`: from Python 3.13
    on, `glob` swallows the `OSError` of a directory it cannot read and
    returns an empty list — so an unreadable directory would be reported as
    "no device is claimed", which is the opposite of what happened.
    """
    prefix, suffix = _lock_names()
    found: list[dict[str, object]] = []
    try:
        with os.scandir(lock_dir) as entries:
            names = sorted(
                entry.name
                for entry in entries
                if entry.name.startswith(prefix) and entry.name.endswith(suffix)
            )
    except OSError as exc:
        raise FileNotFoundError(str(exc)) from exc
    for name in names:
        entry = lock_dir / name
        serial = name[len(prefix): -len(suffix)]
        owner: int | None = None
        try:
            text = entry.read_text(encoding="utf-8").strip()
            owner = int(text.split()[0])
        except (OSError, ValueError, IndexError):
            owner = None
        alive = owner is not None and _pid_alive(owner)
        found.append(
            {
                "serial": serial,
                "owner": owner,
                "alive": alive,
                "held": alive,
                "command": _command_line(owner) if alive and owner is not None else "",
                "lock": str(entry),
            }
        )
    return found


def _connected_devices() -> list[dict[str, str]] | None:
    """The devices `adb` sees, and their state — or None when adb could not answer.

    `adb devices` is asked with a timeout: a wedged adb server must not hang
    the command forever. The header is skipped by what it says rather than by
    its position — adb prints a version-mismatch notice on stdout sometimes,
    and a device dropped because of it is a device the report does not have.
    """
    try:
        result = subprocess.run(
            ["adb", "devices"],
            capture_output=True,
            text=True,
            check=False,
            timeout=ADB_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.TimeoutExpired):
        # adb could not be asked. That is not the same as "no device is
        # attached", and the report says which one it is.
        return None
    devices: list[dict[str, str]] = []
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[0] == "List":
            continue
        if len(parts) >= 2 and parts[1] in ("device", "offline", "unauthorized"):
            devices.append({"serial": parts[0], "state": parts[1]})
    return devices


def _report(
    locks: list[dict[str, object]],
    devices: list[dict[str, str]] | None,
) -> str:
    """The report, as text."""
    held = {str(entry["serial"]) for entry in locks if entry["held"]}
    stale = {str(entry["serial"]) for entry in locks if not entry["held"]}
    lines: list[str] = []
    if locks:
        lines.append("locks:")
        for entry in locks:
            serial = entry["serial"]
            if entry["held"]:
                owner = entry["owner"]
                command = entry.get("command", "")
                detail = f"held by pid {owner} (alive)"
                if command:
                    detail += f"  {command[:120]}"
            elif entry["owner"] is None:
                detail = "unreadable — the owner is unknown, so the device is free"
            else:
                detail = f"stale — pid {entry['owner']} is gone, the device is free"
            lines.append(f"  {serial:<24} {detail}")
    else:
        lines.append("locks: none — no device is claimed")

    lines.append("")
    if devices is None:
        lines.append(
            "connected devices (adb): adb did not answer — this is not the same as "
            "'no device is attached'"
        )
        return "\n".join(lines)
    if not devices:
        lines.append("connected devices (adb): none attached")
        return "\n".join(lines)
    lines.append("connected devices (adb):")
    for device in devices:
        serial = device["serial"]
        if serial in held:
            state = "HELD by another run"
        elif serial in stale:
            state = "free (a stale lock names it)"
        else:
            state = "free"
        lines.append(f"  {serial:<24} {device['state']:<10} {state}")
    return "\n".join(lines)


@click.command("device-holders")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.option("--serial", default=None, help="Ask about one device only.")
def device_holders(emit_json: bool, serial: str | None) -> None:
    """Print who holds a device right now, and which devices are free."""
    lock_dir = _lock_dir()
    try:
        locks = _locks(lock_dir)
    except FileNotFoundError as exc:
        message = f"the lock directory {lock_dir} could not be read: {exc}"
        if emit_json:
            click.echo(_json.dumps({"error": message}))
        else:
            click.echo(message)
        raise SystemExit(CONFIG_ERROR)

    if serial is not None:
        locks = [entry for entry in locks if entry["serial"] == serial]
    devices = _connected_devices()
    if serial is not None:
        devices = [device for device in devices if device["serial"] == serial]

    unknown: str | None = None
    if serial is not None:
        known = any(entry["serial"] == serial for entry in locks) or any(
            device["serial"] == serial for device in (devices or [])
        )
        if not known:
            unknown = serial

    if emit_json:
        payload: dict[str, object] = {
            "lockDir": str(lock_dir),
            "locks": locks,
            "devices": devices,
            "adbAnswered": devices is not None,
        }
        if unknown is not None:
            payload["unknown"] = unknown
        click.echo(_json.dumps(payload, ensure_ascii=False))
    else:
        click.echo(_report(locks, devices))
        if unknown is not None:
            click.echo(
                f"no such device: {unknown} — it is neither locked nor connected"
            )
    if serial is not None and any(entry["held"] for entry in locks):
        raise SystemExit(1)
    raise SystemExit(CLEAN)


register(device_holders)
