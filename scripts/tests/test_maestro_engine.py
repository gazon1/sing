#!/usr/bin/env python3
"""Unit tests for the two Maestro engines and the report shape they leave.

Run with: python3 -m unittest discover -s scripts/tests

The suite can be run by the Maestro CLI or by maestro-runner, a Go binary
that reads the same YAML without a JVM. The two are not interchangeable in
one place: the CLI writes the flow's path into the `file` attribute of the
JUnit `testcase`, and maestro-runner writes it as a `property`. Every gate,
the traceability layer and the GitHub annotations read the attribute, so the
report a run leaves has to be in the same shape whichever engine wrote it.

The other invariant here is the command itself. The device is pinned with
`--device` on both engines, and the bridge URL travels as the same global on
both — a flow that talks to the host has to reach the same bridge no matter
which engine is running it.
"""
from __future__ import annotations

import os
import pathlib
import shutil
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
_SRC = _ROOT / "scripts" / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from emulator.maestro._engine import (  # noqa: E402
    ENGINE_MAESTRO,
    ENGINE_MAESTRO_RUNNER,
    ENGINES,
    MAESTRO_RUNNER_HOME_ENV,
    MAESTRO_RUNNER_NO_TELEMETRY_ENV,
    MAESTRO_RUNNER_NO_UPDATE_CHECK_ENV,
    engine_available,
    engine_env,
    flow_command,
    normalise_junit,
    runner_home,
)

_FLOW = pathlib.Path("Maestro/flows/smoke/01-launch-today.yaml")


class FlowCommandTest(unittest.TestCase):
    """The command each engine runs a flow with."""

    def _command(self, engine: str, **kwargs) -> list[str]:
        return flow_command(
            engine=engine,
            flow_path=_ROOT / _FLOW,
            serial="emulator-5554",
            junit_file=pathlib.Path("/tmp/flow.xml"),
            run_id="r123",
            **kwargs,
        )

    def test_both_engines_pin_the_device(self) -> None:
        for engine in ENGINES:
            cmd = self._command(engine)
            self.assertIn("--device", cmd, engine)
            self.assertEqual(cmd[cmd.index("--device") + 1], "emulator-5554", engine)

    def test_both_engines_pass_the_run_id(self) -> None:
        for engine in ENGINES:
            cmd = self._command(engine)
            self.assertIn("RUN_ID=r123", cmd, engine)

    def test_both_engines_pass_the_bridge_url(self) -> None:
        for engine in ENGINES:
            cmd = self._command(engine, bridge_url="http://127.0.0.1:1234")
            self.assertIn("E2E_BRIDGE_URL=http://127.0.0.1:1234", cmd, engine)

    def test_the_cli_keeps_its_own_flags(self) -> None:
        cmd = self._command(ENGINE_MAESTRO)
        self.assertEqual(cmd[0], "maestro")
        self.assertIn("JUNIT", cmd)

    def test_the_go_runner_writes_junit_to_the_output_file(self) -> None:
        cmd = self._command(ENGINE_MAESTRO_RUNNER)
        self.assertEqual(cmd[0], "maestro-runner")
        self.assertIn("junit", cmd)
        self.assertEqual(cmd[cmd.index("--output") + 1], "/tmp/flow.xml")

    def test_the_go_runner_keeps_its_report_tree_out_of_the_results(self) -> None:
        cmd = self._command(
            ENGINE_MAESTRO_RUNNER,
            report_dir=pathlib.Path("/tmp/reports/flow"),
        )
        self.assertEqual(cmd[cmd.index("--test-output-dir") + 1], "/tmp/reports/flow")

    def test_an_unknown_engine_is_refused(self) -> None:
        with self.assertRaises(ValueError):
            self._command("nope")


class NormaliseJUnitTest(unittest.TestCase):
    """The report shape the gates read, whichever engine wrote it."""

    def _write(self, body: str) -> pathlib.Path:
        tmp = tempfile.NamedTemporaryFile(suffix=".xml", delete=False)
        tmp.write(body.encode("utf-8"))
        tmp.close()
        return pathlib.Path(tmp.name)

    def test_the_property_becomes_the_attribute(self) -> None:
        report = self._write(
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<testsuites><testsuite name="maestro-runner">'
            '<testcase id="f" name="f" classname="f" status="ERROR">'
            '<properties><property name="file" value="Maestro/flows/tasks/x.yaml"/>'
            '</properties>'
            '<failure message="boom">boom</failure>'
            "</testcase></testsuite></testsuites>\n"
        )
        normalise_junit(report, _ROOT / _FLOW)
        case = ET.parse(report).getroot().iter("testcase").__next__()
        self.assertEqual(case.get("file"), "Maestro/flows/tasks/x.yaml")

    def test_a_report_that_already_has_the_attribute_is_left_alone(self) -> None:
        report = self._write(
            '<testsuites><testsuite name="s">'
            '<testcase id="f" name="f" file="Maestro/flows/a.yaml" status="SUCCESS"/>'
            "</testsuite></testsuites>"
        )
        normalise_junit(report, _ROOT / _FLOW)
        case = ET.parse(report).getroot().iter("testcase").__next__()
        self.assertEqual(case.get("file"), "Maestro/flows/a.yaml")

    def test_a_flow_without_a_property_falls_back_to_the_flow_path(self) -> None:
        report = self._write(
            '<testsuites><testsuite name="s">'
            '<testcase id="f" name="f" status="SUCCESS"/>'
            "</testsuite></testsuites>"
        )
        normalise_junit(report, _ROOT / _FLOW)
        case = ET.parse(report).getroot().iter("testcase").__next__()
        self.assertEqual(case.get("file"), str(_ROOT / _FLOW))

    def test_a_missing_report_is_not_an_error(self) -> None:
        missing = pathlib.Path(tempfile.gettempdir()) / "no-such-flow-report.xml"
        normalise_junit(missing, _ROOT / _FLOW)
        self.assertFalse(missing.exists())

    def test_a_malformed_report_is_left_alone(self) -> None:
        report = self._write("not xml at all")
        normalise_junit(report, _ROOT / _FLOW)
        self.assertEqual(report.read_text(), "not xml at all")


class RunnerHomeTest(unittest.TestCase):
    """Where maestro-runner's driver binaries are found.

    The binary on its own is a runner that cannot drive anything: the driver
    APKs live in `drivers/android` next to it. A run that started with the
    binary but no drivers would look like a broken flow rather than a broken
    installation, so the installation is checked before the first flow runs.
    """

    def setUp(self) -> None:
        self._env = dict(os.environ)
        self._which = shutil.which
        self.addCleanup(self._restore)

    def _restore(self) -> None:
        os.environ.clear()
        os.environ.update(self._env)
        shutil.which = self._which

    def _distribution(self) -> pathlib.Path:
        root = pathlib.Path(tempfile.mkdtemp(prefix="mr-home-"))
        (root / "drivers" / "android").mkdir(parents=True)
        (root / "bin").mkdir()
        binary = root / "bin" / "maestro-runner"
        binary.write_text("", encoding="utf-8")
        return root

    def test_the_explicit_home_wins(self) -> None:
        root = self._distribution()
        os.environ["MAESTRO_RUNNER_HOME"] = str(root)
        shutil.which = lambda name: str(root / "bin" / "maestro-runner")
        self.assertEqual(runner_home(), root)

    def test_the_home_is_found_next_to_the_binary(self) -> None:
        root = self._distribution()
        os.environ.pop("MAESTRO_RUNNER_HOME", None)
        shutil.which = lambda name: str(root / "bin" / "maestro-runner")
        self.assertEqual(runner_home(), root)

    def test_a_binary_without_drivers_has_no_home(self) -> None:
        lonely = pathlib.Path(tempfile.mkdtemp(prefix="mr-lonely-"))
        binary = lonely / "maestro-runner"
        binary.write_text("", encoding="utf-8")
        os.environ.pop("MAESTRO_RUNNER_HOME", None)
        shutil.which = lambda name: str(binary)
        self.assertIsNone(runner_home())
        self.assertFalse(engine_available(ENGINE_MAESTRO_RUNNER))

    def test_a_home_without_drivers_is_not_a_home(self) -> None:
        empty = pathlib.Path(tempfile.mkdtemp(prefix="mr-empty-"))
        os.environ["MAESTRO_RUNNER_HOME"] = str(empty)
        self.assertIsNone(runner_home())

    def test_the_environment_keeps_the_run_private(self) -> None:
        root = self._distribution()
        os.environ.pop("MAESTRO_RUNNER_HOME", None)
        shutil.which = lambda name: str(root / "bin" / "maestro-runner")
        env = engine_env(ENGINE_MAESTRO_RUNNER)
        self.assertEqual(env["MAESTRO_RUNNER_HOME"], str(root))
        self.assertEqual(env[MAESTRO_RUNNER_NO_TELEMETRY_ENV], "1")
        self.assertEqual(env[MAESTRO_RUNNER_NO_UPDATE_CHECK_ENV], "1")

    def test_the_cli_engine_gets_no_environment_of_its_own(self) -> None:
        self.assertEqual(engine_env(ENGINE_MAESTRO), {})


if __name__ == "__main__":
    unittest.main()
