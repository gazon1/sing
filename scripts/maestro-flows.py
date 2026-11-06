#!/usr/bin/env python3
"""maestro-flows.py — run the Maestro UI flows of this checkout.

Why
---
The Maestro flows need a booted device, and the emulator cold-start path is not
reliable enough to gate a local check on. So they are opt-in: `RUN_MAESTRO=1`
asks for them, and without it nothing runs at all — not a slow run, not a
failed one.

`./rm`, not `run-maestro`: the console script is an editable install and
belongs to whichever checkout was installed last, so it would run this
checkout's flows against somebody else's tree. `SKIP_INSTALL` is the caller's
decision: it defaults to installing, and suppressing that can green a run
against a binary the device already held.

The device is the one the instrumentation ran on: the serial is read from the
file that step wrote, so the flows do not pick a device of their own.

Usage
-----
    RUN_MAESTRO=1 python3 scripts/maestro-flows.py

Environment:
    RUN_MAESTRO=1   run the flows (without it, nothing happens)
    MAESTRO_TAGS    the tags to run (default: smoke)
    SKIP_INSTALL    1 keeps the binary the device already has
    SERIAL          overrides the device the instrumentation step found

Exit code: 0 when the flows were skipped, 1 when they ran and failed.
"""

from __future__ import annotations

import os
import pathlib
import shutil
import subprocess
import sys

#: The repository root: this script lives in `scripts/`.
ROOT = pathlib.Path(__file__).resolve().parent.parent

#: Where the instrumentation step recorded the device it used.
SERIAL_FILE = ROOT / "build" / "diagnostics" / "device-serial.txt"

#: The tags the flows run when the caller names none.
DEFAULT_TAGS = "smoke"


def read_serial() -> str:
    """The serial of the device the instrumentation ran on, or an empty string.

    An empty serial is not a pinned device: the Maestro runner picks one itself,
    which is what happened before the two steps were split apart.
    """
    pinned = os.environ.get("SERIAL")
    if pinned is not None:
        return pinned
    try:
        return SERIAL_FILE.read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def run_maestro(serial: str, tags: str, skip_install: str) -> int:
    """The flows of this checkout; their exit code is returned, not raised."""
    environment = dict(os.environ)
    environment["SERIAL"] = serial
    environment["TAGS"] = tags
    environment["SKIP_INSTALL"] = skip_install
    try:
        completed = subprocess.run(
            [str(ROOT / "rm")],
            cwd=str(ROOT),
            env=environment,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        print(f"could not run the Maestro flows: {exc}")
        return 1
    return completed.returncode


def main(argv: list[str]) -> int:
    if os.environ.get("RUN_MAESTRO") != "1":
        print("RUN_MAESTRO is not set — the Maestro flows are skipped.")
        return 0

    if shutil.which("maestro") is None:
        print("maestro CLI not on PATH — skipping")
        return 0

    serial = read_serial()
    tags = os.environ.get("MAESTRO_TAGS") or DEFAULT_TAGS
    skip_install = os.environ.get("SKIP_INSTALL", "0")

    if run_maestro(serial, tags, skip_install) != 0:
        print("Maestro flows FAILED")
        return 1

    print("Maestro flows completed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
