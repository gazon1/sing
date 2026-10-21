#!/usr/bin/env python3
"""Unit tests for the cleanup scope: what the runner may sweep.

Run with: python3 -m unittest discover -s scripts/tests

The narrow scope is gone. It removed the rows whose title started with the run
id or with a fixed title, and the fixed titles were a list somebody had to keep
up to date by hand: a title the list forgot survived the run, and the failure
that caused was silent — a flow that asserts an empty list fails on the
*previous* flow's data, and a flow that asserts its own task fails because
another flow's task is in the way.

These tests pin what is left: two scopes, the wide one by default, and no
sweep that names titles.
"""
from __future__ import annotations

import pathlib
import tempfile
import unittest
import unittest.mock

from click.testing import CliRunner

import emulator.maestro._cli as _cli
import emulator.maestro._fixtures as _fixtures

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


def _recording_deps(calls: list[str]) -> "object":
    """A `DeviceDeps` whose adb calls are recorded."""
    from emulator._startup import Acquisition
    from emulator._types import EXIT_OK
    from emulator.maestro._types import DeviceDeps

    return DeviceDeps(
        try_run=lambda cmd, **kw: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
        device_alive=lambda s: True,
        device_model=lambda s: "Pixel_8",
        maestro_saw_device_failure=lambda stderr: False,
        apk_sha_from_device=lambda s: "abc123def456",
        gradle_install_debug=lambda s: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
        acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
        adb=lambda *a, **kw: "",
        adb_on=lambda serial, *a, **kw: calls.append(" ".join(a)),
        reset_adb_server=lambda: None,
        wait_for_app_resumed=lambda s, timeout=20: True,
        cleanup_run_fixtures=lambda serial, run_id, mode, deps: None,
        capture_artifacts=lambda serial, name, artifact_dir, deps: None,
        git_sha=lambda deps: "abc123def456",
    )


class TheScopeListTest(unittest.TestCase):
    """The CLI offers two scopes, and the narrow one is not one of them."""

    def test_the_narrow_scope_is_refused(self) -> None:
        result = CliRunner().invoke(
            _cli.main, ["--dry-run", "--maestro-cleanup", "run"]
        )
        self.assertEqual(2, result.exit_code)
        self.assertIn("run", result.output)

    def test_the_two_surviving_scopes_are_accepted(self) -> None:
        for scope in ("all", "off"):
            with self.subTest(scope=scope):
                result = CliRunner().invoke(
                    _cli.main, ["--dry-run", "--maestro-cleanup", scope]
                )
                self.assertEqual(0, result.exit_code, f"output={result.output}")

    def test_the_help_lists_only_the_two_scopes(self) -> None:
        result = CliRunner().invoke(_cli.main, ["--help"])
        self.assertEqual(0, result.exit_code)
        self.assertIn("[all|off]", result.output)


class NoSweepByTitleTest(unittest.TestCase):
    """No surviving scope names a title."""

    def _links_for(self, mode: str) -> list[str]:
        calls: list[str] = []
        deps = _recording_deps(calls)
        with unittest.mock.patch.object(_fixtures.time, "sleep", lambda _s: None):
            _fixtures.cleanup_run_fixtures("emulator-5554", "r1", mode, deps)
        return calls

    def test_the_wide_scope_wipes_the_content(self) -> None:
        links = self._links_for("all")
        self.assertEqual(1, len(links))
        self.assertIn("todo-debug://seed?reset=content", links[0])

    def test_the_off_scope_sends_nothing(self) -> None:
        self.assertEqual([], self._links_for("off"))

    def test_no_scope_sweeps_by_title(self) -> None:
        """The link that named titles is gone, and must not come back.

        `cleanup=` took a comma-separated list of title prefixes — a list
        somebody had to keep up to date by hand, and every title the list
        forgot survived the run.
        """
        for mode in ("all", "off"):
            with self.subTest(mode=mode):
                for link in self._links_for(mode):
                    self.assertNotIn("cleanup=", link)

    def test_the_source_carries_no_title_list(self) -> None:
        """The hand-written list of fixed titles is gone from the implementation."""
        source = (_fixtures.__file__ or "")
        text = pathlib.Path(source).read_text(encoding="utf-8")
        self.assertNotIn("Buy milk", text)


class UnknownScopeTest(unittest.TestCase):
    """A scope the runner does not know is a bug, and it says so."""

    def test_an_unknown_scope_is_refused(self) -> None:
        calls: list[str] = []
        deps = _recording_deps(calls)
        with self.assertRaises(ValueError):
            _fixtures.cleanup_run_fixtures("emulator-5554", "r1", "narrow", deps)
        self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
