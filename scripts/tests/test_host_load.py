"""Tests for the `host-load` command — is this host busy enough to matter?

The defect this answers: the cost of a Gradle run on this repository is not a
property of the run. The same `:shared:jvmTest` took 8 minutes on a host at
load 16 and 24 minutes on a host at load 42–52. That knowledge existed as a
sentence in a guidance file, and a sentence cannot be consulted by a tool — so
the decision "run the whole suite now, or the targeted set?" was made by
remembering, at the moment when remembering fails.

The tests pin the two things that make the answer usable:

* the verdict follows the measurement — a busy host is called busy, an idle one
  idle, and the threshold is the one the command's own documentation quotes;
* the answer is machine-readable — `--json` says the same thing the prose says,
  because a rule that only a human can read is a rule that only a human can
  forget.

The load is mocked, so the tests do not depend on how busy the machine running
them happens to be.
"""

import json
import os
import sys
import unittest
from unittest import mock

SCRIPTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
sys.path.insert(0, os.path.join(SCRIPTS, "src"))

from click.testing import CliRunner  # noqa: E402

from sgate.commands import host_load  # noqa: E402


class HostLoadTest(unittest.TestCase):
    """The measurement and the verdict it produces."""

    def test_the_reading_carries_the_load_and_the_cores(self) -> None:
        with mock.patch.object(host_load.os, "getloadavg", return_value=(12.0, 10.0, 9.0)):
            with mock.patch.object(host_load.os, "cpu_count", return_value=16):
                reading = host_load.measure()

        self.assertEqual(12.0, reading["load1m"])
        self.assertEqual(10.0, reading["load5m"])
        self.assertEqual(9.0, reading["load15m"])
        self.assertEqual(16, reading["cores"])
        self.assertEqual(0.75, reading["loadPerCore"])

    def test_a_host_at_the_threshold_is_busy(self) -> None:
        # 16 cores, load 32 — two runnable tasks per core.
        with mock.patch.object(host_load.os, "getloadavg", return_value=(32.0, 32.0, 32.0)):
            with mock.patch.object(host_load.os, "cpu_count", return_value=16):
                reading = host_load.measure()

        self.assertEqual("busy", reading["state"])

    def test_a_host_below_the_threshold_is_idle(self) -> None:
        # 16 cores, load 16 — one runnable task per core: the 8-minute run.
        with mock.patch.object(host_load.os, "getloadavg", return_value=(16.0, 16.0, 16.0)):
            with mock.patch.object(host_load.os, "cpu_count", return_value=16):
                reading = host_load.measure()

        self.assertEqual("idle", reading["state"])

    def test_the_threshold_is_the_one_the_documentation_quotes(self) -> None:
        self.assertEqual(2.0, host_load.BUSY_LOAD_PER_CORE)
        self.assertIn("2.0 per", host_load.__doc__ or "")

    def test_a_host_without_a_load_average_says_so(self) -> None:
        with mock.patch.object(
            host_load.os,
            "getloadavg",
            side_effect=OSError("not available"),
        ):
            with self.assertRaises(SystemExit) as raised:
                host_load.measure()

        self.assertEqual(2, raised.exception.code)


class HostLoadCommandTest(unittest.TestCase):
    """The command as the caller meets it."""

    def test_the_json_carries_the_verdict(self) -> None:
        runner = CliRunner()
        with mock.patch.object(host_load.os, "getloadavg", return_value=(40.0, 40.0, 40.0)):
            with mock.patch.object(host_load.os, "cpu_count", return_value=16):
                result = runner.invoke(host_load.host_load, ["--json"])

        self.assertEqual(0, result.exit_code, result.output)
        reading = json.loads(result.output)
        self.assertEqual("busy", reading["state"])
        self.assertEqual(2.5, reading["loadPerCore"])

    def test_the_prose_names_the_state_and_the_advice(self) -> None:
        runner = CliRunner()
        with mock.patch.object(host_load.os, "getloadavg", return_value=(40.0, 40.0, 40.0)):
            with mock.patch.object(host_load.os, "cpu_count", return_value=16):
                result = runner.invoke(host_load.host_load)

        self.assertEqual(0, result.exit_code, result.output)
        self.assertIn("busy", result.output)
        self.assertIn("targeted set", result.output)

    def test_an_idle_host_says_idle(self) -> None:
        runner = CliRunner()
        with mock.patch.object(host_load.os, "getloadavg", return_value=(8.0, 8.0, 8.0)):
            with mock.patch.object(host_load.os, "cpu_count", return_value=16):
                result = runner.invoke(host_load.host_load, ["--json"])

        reading = json.loads(result.output)
        self.assertEqual("idle", reading["state"])
        self.assertEqual(0.5, reading["loadPerCore"])


if __name__ == "__main__":
    unittest.main()
