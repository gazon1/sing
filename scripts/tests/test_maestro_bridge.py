#!/usr/bin/env python3
"""Unit tests for the Maestro host bridge.

Run with: python3 -m unittest discover -s scripts/tests

The bridge is the only channel a Maestro script has to the host, so what it
refuses matters as much as what it runs: a bridge that executed whatever it was
asked to would turn every flow into a remote shell.
"""
from __future__ import annotations

import json
import unittest
import urllib.error
import urllib.request

import emulator.maestro._bridge as _bridge
from emulator.maestro._bridge import (
    BRIDGE_ENV_NAME,
    Bridge,
    CommandResult,
    is_allowed,
)
from emulator.maestro._types import APP_ID


def _get(url: str) -> tuple[int, dict]:
    """Fetch a URL and return its status and the decoded JSON body."""
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode("utf-8"))


class FakeRunner:
    """Records what the bridge asked the device to do."""

    def __init__(self, result: CommandResult | None = None) -> None:
        self.calls: list[tuple[str, str]] = []
        self.result = result or CommandResult(code=0, stdout="", stderr="")

    def __call__(self, serial: str, command: str) -> CommandResult:
        self.calls.append((serial, command))
        return self.result


class BridgeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.runner = FakeRunner(CommandResult(code=0, stdout="hello\n", stderr=""))
        self.bridge = Bridge("emulator-5554", run_device_command=self.runner)
        self.base = self.bridge.start()
        self.addCleanup(self.bridge.stop)

    def test_health_answers(self) -> None:
        status, body = _get(f"{self.base}/health")
        self.assertEqual(200, status)
        self.assertEqual({"ok": True}, body)

    def test_an_allowed_command_runs_on_the_device(self) -> None:
        command = f"run-as {APP_ID} sh -c 'ls shared_prefs'"
        status, body = _get(f"{self.base}/shell?cmd={_quote(command)}")

        self.assertEqual(200, status)
        self.assertEqual("hello\n", body["stdout"])
        self.assertEqual(0, body["code"])
        self.assertEqual([("emulator-5554", command)], self.runner.calls)

    def test_the_command_reaches_the_device_verbatim(self) -> None:
        """The quoting has to survive, or `sh -c` receives the wrong words."""
        command = f"run-as {APP_ID} sh -c 'echo not-a-keyset > shared_prefs/x.xml'"
        _get(f"{self.base}/shell?cmd={_quote(command)}")

        self.assertEqual(command, self.runner.calls[0][1])

    def test_a_command_outside_the_whitelist_is_refused(self) -> None:
        status, body = _get(f"{self.base}/shell?cmd={_quote('rm -rf /data')}")

        self.assertEqual(400, status)
        self.assertIn("refused", body["error"])
        self.assertEqual([], self.runner.calls)

    def test_a_run_as_for_another_package_is_refused(self) -> None:
        status, _ = _get(f"{self.base}/shell?cmd={_quote('run-as com.other.app ls')}")

        self.assertEqual(400, status)
        self.assertEqual([], self.runner.calls)

    def test_a_failing_command_reports_its_code(self) -> None:
        self.runner.result = CommandResult(code=1, stdout="", stderr="no such file")
        command = f"run-as {APP_ID} sh -c 'ls missing'"
        status, body = _get(f"{self.base}/shell?cmd={_quote(command)}")

        self.assertEqual(500, status)
        self.assertEqual(1, body["code"])
        self.assertEqual("no such file", body["stderr"])

    def test_an_unknown_endpoint_is_not_a_shell(self) -> None:
        status, _ = _get(f"{self.base}/exec")

        self.assertEqual(404, status)
        self.assertEqual([], self.runner.calls)

    def test_a_missing_command_parameter_is_refused(self) -> None:
        status, body = _get(f"{self.base}/shell")

        self.assertEqual(400, status)
        self.assertIn("cmd", body["error"])
        self.assertEqual([], self.runner.calls)


class WhitelistTest(unittest.TestCase):
    def test_the_allowed_shapes(self) -> None:
        self.assertTrue(is_allowed(f"run-as {APP_ID} sh -c 'ls'"))
        self.assertTrue(is_allowed("setprop debug.stub_auth.signin_failure true"))
        self.assertTrue(is_allowed("getprop debug.stub_auth.signin_failure"))

    def test_everything_else_is_out(self) -> None:
        for command in (
            "",
            "ls",
            f"run-as {APP_ID}",
            "run-as com.other.app ls",
            "shell rm -rf /",
            f"run-as {APP_ID} ; rm -rf /",
        ):
            with self.subTest(command=command):
                self.assertFalse(is_allowed(command))


class RunnerWiringTest(unittest.TestCase):
    def test_the_flow_is_told_where_the_bridge_is(self) -> None:
        """Maestro exposes each --env entry as a bare global, so the flow needs
        the address under exactly the name the bridge exports."""
        self.assertEqual("E2E_BRIDGE_URL", BRIDGE_ENV_NAME)


def _quote(value: str) -> str:
    import urllib.parse

    return urllib.parse.quote(value, safe="")


if __name__ == "__main__":
    unittest.main()
