#!/usr/bin/env python3
"""Process-level tests for `python -m emulator`, driven by stub binaries on PATH.

Run with: python3 -m unittest discover -s scripts/tests

Why a subprocess test
---------------------
Every other emulator test drives Click through ``CliRunner``. That is enough for
the logic and not enough for the *shell contract*, and one bug lived exactly in
the gap: Click 8's standalone mode discards a callback's return value and exits
0, so ``ensure-emulator`` and ``run-maestro`` exited 0 on every failure. Under
``set -e`` a caller could not tell. ``CliRunner`` does surface it, but only once
the command raises ``ctx.exit`` — before that fix, nothing asserted the exit code
at the boundary a caller actually observes.

These tests put scripted ``adb`` / ``emulator`` stubs on PATH and run the real
entry point as a child process, so what is asserted is stdout, the exit status,
and the argv the stubs received. No emulator, no SDK, no network.
"""
from __future__ import annotations

import os
import pathlib
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SRC = REPO_ROOT / "scripts" / "src"

# A serial no real qemu will ever claim, so /proc scans find nothing.
_STUB_AVD = "ContractTest_Avd_NotRunning"

# Answers every readiness probe positively for SERIAL.
_ADB_READY = textwrap.dedent(f"""\
    #!/bin/sh
    echo "$@" >> "$STUB_CALLS"
    case "$*" in
      *"devices"*)
        echo "List of devices attached"
        echo "emulator-5554	device"
        ;;
      *"get-state"*)        echo "device" ;;
      *"sys.boot_completed"*) echo "1" ;;
      *"pm path android"*)  echo "package:/system/framework/framework-res.apk" ;;
      *"dumpsys SurfaceFlinger"*) echo "SurfaceFlinger|GPU mem:" ;;
      *) echo "" ;;
    esac
    exit 0
    """)

# Never lists a device, so acquisition cannot succeed by the fast path.
_ADB_NONE = textwrap.dedent("""\
    #!/bin/sh
    echo "$@" >> "$STUB_CALLS"
    echo "List of devices attached"
    exit 0
    """)

# Exits immediately with a failure, standing in for an emulator that dies.
# Deliberately does NOT print "Failed to get write lock": that string drives the
# 3-attempt retry loop with a 5 s sleep, which would make this process-level
# test cost ~30 s. The retry path is covered in-process by
# test_ensure_emulator.IntegrationTest.test_lock_conflict_one_retry_then_ok;
# this test exists for the shell contract, not for the retry policy.
#
# `-list-avds` is answered, because the module now asks the tool what AVDs
# exist before it boots one — a stub that only ever died would be refused at
# that gate, and this test would measure the refusal instead of the crash.
_EMULATOR_DIES = textwrap.dedent(f"""\
    #!/bin/sh
    echo "$@" >> "$STUB_CALLS"
    case "$*" in
      *-list-avds*)
        echo "Available Android Virtual Devices:"
        echo "    {_STUB_AVD}"
        exit 0
        ;;
    esac
    echo "qemu: fatal: could not initialise" >&2
    exit 1
    """)


class _StubBinaries:
    """A PATH directory of scripted stand-ins, plus the file they log to."""

    def __init__(self, adb_body: str, with_emulator: bool = True):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        self.calls = self.dir / "calls.log"
        self.calls.touch()
        self._write("adb", adb_body, executable=True)
        if with_emulator:
            self._write("emulator", _EMULATOR_DIES, executable=True)

    def _write(self, name: str, body: str, *, executable: bool):
        path = self.dir / name
        path.write_text(body)
        if executable:
            path.chmod(path.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)

    def run(self, *args: str) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env["PATH"] = f"{self.dir}{os.pathsep}{env['PATH']}"
        env["PYTHONPATH"] = str(SRC)
        env["STUB_CALLS"] = str(self.calls)
        # Keep the AVD scan away from any real emulator, and cap every wait so a
        # mis-plumbed path fails in seconds instead of minutes.
        env["AVD"] = _STUB_AVD
        env["ANDROID_AVD_HOME"] = str(self.dir / "avd")
        # 3 s, not 1: start_avd polls the child at most every 2 s, so a 1 s budget
        # expires before poll() can observe the exit and reports EXIT_TIMEOUT
        # instead of EXIT_CRASHED. 3 s lets exactly one sleep-and-reprobe happen.
        env["EMULATOR_TIMEOUT"] = "3"
        env["EMULATOR_LOCK_TIMEOUT"] = "1"
        env["EMULATOR_LOG"] = str(self.dir / "emulator.log")
        env.pop("SERIAL", None)
        env.pop("CI", None)
        return subprocess.run(
            [sys.executable, "-m", "emulator", *args],
            capture_output=True, text=True, env=env, timeout=60,
            cwd=str(REPO_ROOT),
        )

    def recorded(self) -> list[str]:
        return [ln for ln in self.calls.read_text().splitlines() if ln]


class ReadyDeviceContractTest(unittest.TestCase):
    """A ready device: serial on stdout, exit 0, adb scoped with -s."""

    def test_prints_serial_and_exits_zero(self):
        stubs = _StubBinaries(_ADB_READY)
        cp = stubs.run()
        self.assertEqual(cp.returncode, 0, cp.stderr)
        self.assertEqual(cp.stdout.strip(), "emulator-5554")

    def test_readiness_probes_are_scoped_with_dash_s(self):
        stubs = _StubBinaries(_ADB_READY)
        stubs.run()
        probes = [c for c in stubs.recorded() if "boot_completed" in c]
        self.assertTrue(probes, f"no boot probe recorded in {stubs.recorded()}")
        for probe in probes:
            self.assertTrue(
                probe.startswith("-s emulator-5554 "),
                f"probe was not device-scoped: {probe!r}",
            )


class NoDeviceContractTest(unittest.TestCase):
    """No ready device and a dying emulator: a non-zero exit must reach the shell."""

    def test_exit_code_survives_click_standalone_mode(self):
        stubs = _StubBinaries(_ADB_NONE)
        cp = stubs.run()
        self.assertNotEqual(
            cp.returncode, 0,
            "ensure-emulator exited 0 with no device — Click discarded the "
            f"callback's return value (stderr: {cp.stderr[-500:]})",
        )
        self.assertEqual(cp.returncode, 2, "expected EXIT_CRASHED for a dying emulator")

    def test_stdout_carries_nothing_but_the_serial(self):
        """Diagnostics must not land on stdout; `SERIAL=$(...)` depends on it."""
        stubs = _StubBinaries(_ADB_NONE)
        cp = stubs.run()
        self.assertEqual(cp.stdout.strip(), "",
                         "a failed run wrote diagnostics to stdout")
        self.assertIn("emulator", cp.stderr.lower(),
                      "diagnostics should still reach stderr")


if __name__ == "__main__":
    unittest.main()