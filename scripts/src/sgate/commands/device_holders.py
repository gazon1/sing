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
    2 = the lock directory could not be read
"""

from __future__ import annotations

import json as _json
import os
import pathlib
import subprocess

import click

from sgate.commands.base import CLEAN, CONFIG_ERROR, register

#: How a lock file is named, and where the locks live. Both are the emulator
#: package's own — read from it, not written out here: a second copy of the
#: naming is a second answer to "which file holds this device?".
LOCK_PREFIX = "singularity-maestro-"
LOCK_SUFFIX = ".lock"


def _lock_dir() -> pathlib.Path:
    """Where the device locks live."""
    from emulator.maestro._lock import LOCK_DIR

    return pathlib.Path(LOCK_DIR)


def _pid_alive(pid: int) -> bool:
    """Whether a process is still running."""
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _command_line(pid: int) -> str:
    """The command line of a process, or an empty string.

    Read from `/proc`, so it is the command as it is running — which is what
    names the session that holds the device.
    """
    try:
        raw = pathlib.Path(f"/proc/{pid}/cmdline").read_bytes()
    except OSError:
        return ""
    return " ".join(part for part in raw.decode("utf-8", "replace").split("\0") if part)


def _locks(lock_dir: pathlib.Path) -> list[dict[str, object]]:
    """Every lock in the directory, and what each one says."""
    found: list[dict[str, object]] = []
    try:
        entries = sorted(lock_dir.glob(f"{LOCK_PREFIX}*{LOCK_SUFFIX}"))
    except OSError as exc:
        raise FileNotFoundError(str(exc)) from exc
    for entry in entries:
        serial = entry.name[len(LOCK_PREFIX): -len(LOCK_SUFFIX)]
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


def _connected_devices() -> list[dict[str, str]]:
    """The devices `adb` sees, and their state."""
    try:
        result = subprocess.run(
            ["adb", "devices"],
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return []
    devices: list[dict[str, str]] = []
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            devices.append({"serial": parts[0], "state": parts[1]})
    return devices


def _report(locks: list[dict[str, object]], devices: list[dict[str, str]]) -> str:
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
            else:
                detail = f"stale — pid {entry['owner']} is gone, the device is free"
            lines.append(f"  {serial:<24} {detail}")
    else:
        lines.append("locks: none — no device is claimed")

    if devices:
        lines.append("")
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
    else:
        lines.append("")
        lines.append("connected devices (adb): none seen")
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

    if emit_json:
        click.echo(
            _json.dumps(
                {"lockDir": str(lock_dir), "locks": locks, "devices": devices},
                ensure_ascii=False,
            )
        )
    else:
        click.echo(_report(locks, devices))

    if serial is not None and any(entry["held"] for entry in locks):
        raise SystemExit(1)
    raise SystemExit(CLEAN)


register(device_holders)
