#!/usr/bin/env python3
"""A report tree belongs to the execution that wrote it.

Run with: python3 -m unittest discover -s scripts/tests

The runner's report tree names the element a failed command addressed, and
the failure message is rewritten in the CLI's wording with that element in
it — the cause registry is keyed on the result. The tree used to live in a
directory that every execution of the same flow shared, so a run that failed
before it wrote a tree of its own read the previous run's selector: the
message named an element the run never touched, and the registry classified
a cause the run never had.

The tree is now written into a directory of the execution's own, and this
file holds the tests that keep it that way.
"""
from __future__ import annotations

import json
import pathlib
import tempfile
import unittest
import unittest.mock
import xml.etree.ElementTree as ET

from emulator._startup import Acquisition
from emulator._types import EXIT_OK
from emulator.maestro._engine import ENGINE_MAESTRO_RUNNER, normalise_junit
from emulator.maestro._runner import run_one_flow
from emulator.maestro._types import DeviceDeps

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent

#: The runner's wording for a command whose element it could not find. The
#: message names no element, so the tree is the only thing that can.
_UNNAMED_FAILURE = (
    "Element not found: handler_error: Element not found in any of 2 strategies "
    "(cause: timed out: handler_error: Element not found in any of 2 strategies)"
)

#: The element the tree of the first execution names.
_STALE_SELECTOR = "task_item_stale_element"

#: What the runner writes into the JUnit file of a failed flow.
_FAILED_REPORT = """<?xml version='1.0' encoding='UTF-8'?>
<testsuites>
  <testsuite name="Test Suite" device="RMX3938" tests="1" failures="1">
    <testcase id="flow" name="flow" status="ERROR">
      <failure>""" + _UNNAMED_FAILURE + """</failure>
    </testcase>
  </testsuite>
</testsuites>
"""

#: The tree the runner writes for the command that failed.
_REPORT_TREE = {
    "commands": [
        {
            "type": "assertVisible",
            "status": "failed",
            "params": {"selector": {"type": "id", "value": _STALE_SELECTOR}},
        }
    ]
}

#: The CLI takes the device as ``--device`` and writes its report to
#: ``--output``; maestro-runner writes the same report and puts the tree of
#: the attempt under ``--test-output-dir``.
_FIXTURE_DIR = ROOT / "build" / "maestro-test-flows"


def _flow(name: str) -> pathlib.Path:
    """A flow file inside the repository, where the CLI expects one."""
    _FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    path = _FIXTURE_DIR / f"{name}.yaml"
    path.write_text("appId: com.singularity.todo\nname: " + name + "\n", encoding="utf-8")
    return path


def _deps(*, with_tree: bool, report_dirs: list[pathlib.Path]) -> DeviceDeps:
    """A bundle whose runner answers the test's script.

    ``with_tree`` says whether the execution writes a report tree of its own.
    Every ``--test-output-dir`` the runner is given is recorded, so a test can
    see which directory an execution was pointed at.
    """

    def fake_try_run(cmd, **kwargs):
        if not cmd or cmd[0] != "maestro-runner":
            return unittest.mock.Mock(returncode=0, stdout="", stderr="")
        out = pathlib.Path(cmd[cmd.index("--output") + 1])
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(_FAILED_REPORT, encoding="utf-8")
        report_dir = pathlib.Path(cmd[cmd.index("--test-output-dir") + 1])
        report_dirs.append(report_dir)
        if with_tree:
            attempt = report_dir / "2026-11-18_03-00-00"
            (attempt / "flows").mkdir(parents=True, exist_ok=True)
            (attempt / "flows" / "flow-000.json").write_text(
                json.dumps(_REPORT_TREE), encoding="utf-8"
            )
        return unittest.mock.Mock(returncode=1, stdout="", stderr="")

    return DeviceDeps(
        try_run=fake_try_run,
        device_alive=lambda s: True,
        device_model=lambda s: "RMX3938",
        maestro_saw_device_failure=lambda stderr: False,
        apk_sha_from_device=lambda s: "abc123def456",
        gradle_install_debug=lambda s: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
        acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
        adb=lambda *a, **kw: "",
        adb_on=lambda s, *a, **kw: "",
        reset_adb_server=lambda: None,
        wait_for_app_resumed=lambda s, timeout=20: True,
        wait_for_main_activity_resumed=lambda s, timeout=30: True,
        cleanup_run_fixtures=lambda *a, **kw: None,
        capture_artifacts=lambda *a, **kw: None,
        git_sha=lambda deps: "abc123def456",
        app_installed=lambda s: True,
        device_api_level=lambda s: 35,
    )


def _run(flow: pathlib.Path, junit: pathlib.Path, deps: DeviceDeps):
    """Run the flow once, without a retry: one execution, one report."""
    return run_one_flow(
        flow_path=flow,
        serial="emulator-5554",
        junit_dir=junit,
        skip_install=True,
        allow_stale=True,
        max_retries=0,
        expected_sha="",
        deps=deps,
        engine=ENGINE_MAESTRO_RUNNER,
    )


def _failure_message(junit_file: pathlib.Path) -> str:
    failure = ET.parse(junit_file).getroot().iter("testcase").__next__().find("failure")
    return failure.text or ""


class TheReportDirectoryIsTheExecutionsOwnTest(unittest.TestCase):
    """Two executions of the same flow may not share a report directory."""

    def test_two_executions_of_the_same_flow_use_different_directories(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            flow = _flow("report-isolation")
            junit = pathlib.Path(tmp) / "reports"
            report_dirs: list[pathlib.Path] = []

            _run(flow, junit, _deps(with_tree=True, report_dirs=report_dirs))
            _run(flow, junit, _deps(with_tree=False, report_dirs=report_dirs))

            self.assertEqual(len(report_dirs), 2, "the flow did not run twice")
            self.assertNotEqual(
                report_dirs[0],
                report_dirs[1],
                "the two executions shared one report directory",
            )

    def test_a_stale_report_cannot_name_the_element_of_a_later_execution(self) -> None:
        """The second execution failed before it wrote a tree of its own."""
        with tempfile.TemporaryDirectory() as tmp:
            flow = _flow("report-isolation")
            junit = pathlib.Path(tmp) / "reports"
            report_dirs: list[pathlib.Path] = []

            _run(flow, junit, _deps(with_tree=True, report_dirs=report_dirs))
            first = _failure_message(junit / "report-isolation.xml")
            self.assertIn(
                _STALE_SELECTOR,
                first,
                "the tree of the first execution did not name the element",
            )

            _run(flow, junit, _deps(with_tree=False, report_dirs=report_dirs))
            second = _failure_message(junit / "report-isolation.xml")

            self.assertNotIn(
                _STALE_SELECTOR,
                second,
                "the message of the second execution was rewritten with the "
                "element of the first one",
            )
            self.assertEqual(
                second,
                _UNNAMED_FAILURE,
                "a message that names no element must be left as it is",
            )


class AMissingTreeIsNoTreeTest(unittest.TestCase):
    """A tree that is not there says nothing about the element."""

    def test_a_missing_report_tree_leaves_the_failure_message_alone(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            work = pathlib.Path(tmp)
            report = work / "create-task.xml"
            report.write_text(_FAILED_REPORT, encoding="utf-8")

            normalise_junit(
                report,
                ROOT / "Maestro/flows/tasks/create-task.yaml",
                report_dir=work / "no-such-tree",
                run_id="r1",
            )

            self.assertEqual(
                _failure_message(report),
                _UNNAMED_FAILURE,
                "a message that names no element must be left as it is",
            )


if __name__ == "__main__":
    unittest.main()
