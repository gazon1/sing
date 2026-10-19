#!/usr/bin/env python3
"""Unit tests for how the Maestro suites are selected.

Run with: python3 -m unittest discover -s scripts/tests

The smoke suite used to be a *directory*: `Maestro/flows/smoke/`. That made
the `smoke` tag on a flow outside that directory a lie — `notes/create-note.yaml`
declares `smoke` and ran in the nightly full suite and nowhere else, so the
tag said one thing and the runner did another. A flow can only be in the smoke
suite if the tag means something, and the tag now selects.

The invariant that keeps the directory honest is here too: every flow under
`Maestro/flows/smoke/` declares the `smoke` tag. A flow that lost the tag
would drop out of the smoke suite silently — the directory would keep
existing, the suite would keep running, and one flow would simply stop.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import pathlib
import tempfile
import unittest
import unittest.mock

from emulator.maestro._flow import (
    SMOKE_TAG,
    collect_flows,
    flow_has_tag,
    smoke_flows,
    suite_selection,
)

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
_FLOWS = _ROOT / "Maestro" / "flows"
_SMOKE_DIR = _FLOWS / "smoke"
_SCRIPT = _ROOT / "scripts" / "run-maestro-e2e.py"


def _load_script():
    """The script has a hyphen in its name, so it is loaded by path."""
    spec = importlib.util.spec_from_file_location("run_maestro_e2e_selection", _SCRIPT)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SuiteSelectionTest(unittest.TestCase):
    """The mapping from a suite name to what the runner selects."""

    def test_smoke_is_selected_by_tag(self):
        target, tags = suite_selection(_FLOWS, "smoke")
        self.assertEqual(target, _FLOWS)
        self.assertEqual(tags, [SMOKE_TAG])

    def test_full_is_every_flow_under_the_root(self):
        target, tags = suite_selection(_FLOWS, "full")
        self.assertEqual(target, _FLOWS)
        self.assertEqual(tags, [], "the full suite must not be filtered by a tag")

    def test_the_smoke_selection_is_not_the_smoke_directory(self):
        """The defect: the selection was `Maestro/flows/smoke/`, so a flow
        tagged `smoke` anywhere else never ran in the smoke suite."""
        target, _tags = suite_selection(_FLOWS, "smoke")
        self.assertNotEqual(target, _SMOKE_DIR)


class SmokeSuiteReachesEveryTaggedFlowTest(unittest.TestCase):
    """The selection, against the flows that are actually in the tree."""

    def setUp(self):
        if not _FLOWS.is_dir():
            self.skipTest(f"no flow directory at {_FLOWS}")

    def test_the_smoke_directory_keeps_its_flows(self):
        """Every flow under `Maestro/flows/smoke/` declares the tag.

        The directory's contents are kept by the tag selection, and this is
        the invariant that keeps that true: a flow that lost its tag would
        leave the smoke suite without a word.
        """
        untagged = [
            str(f.relative_to(_ROOT))
            for f in sorted(_SMOKE_DIR.glob("*.yaml"))
            if not flow_has_tag(f, SMOKE_TAG)
        ]
        self.assertEqual(
            untagged,
            [],
            "flows in the smoke directory that do not declare the smoke tag — "
            "they would drop out of the smoke suite:",
        )

    def test_the_smoke_suite_reaches_flows_outside_the_directory(self):
        selected = smoke_flows(_FLOWS)
        outside = [f for f in selected if _SMOKE_DIR not in f.parents]
        self.assertTrue(
            outside,
            "the smoke suite selects only what lives under "
            f"{_SMOKE_DIR.relative_to(_ROOT)} — a flow tagged smoke elsewhere "
            "never runs in it",
        )

    def test_the_smoke_suite_keeps_the_directory_flows(self):
        selected = smoke_flows(_FLOWS)
        missing = [
            str(f.relative_to(_ROOT))
            for f in sorted(_SMOKE_DIR.glob("*.yaml"))
            if f not in selected
        ]
        self.assertEqual(
            missing,
            [],
            "flows in the smoke directory that the smoke suite does not select:",
        )

    def test_the_selection_is_not_vacuous(self):
        selected = smoke_flows(_FLOWS)
        self.assertGreater(
            len(selected),
            len(list(_SMOKE_DIR.glob("*.yaml"))),
            "the smoke suite selects no more than the smoke directory — the "
            "tag selection is not reaching the flows outside it",
        )


class RunnerBuildsTheSelectionTest(unittest.TestCase):
    """The runner passes the selection on to the Maestro CLI."""

    def setUp(self):
        self.mod = _load_script()
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)

    def _argv_for(self, suite: str) -> list[str]:
        captured: list[str] = []

        def fake_run(argv, **kwargs):
            captured.extend(argv)
            return unittest.mock.MagicMock(returncode=0)

        with unittest.mock.patch.object(self.mod.subprocess, "run", fake_run):
            with contextlib.redirect_stdout(io.StringIO()):
                self.mod.run_maestro(
                    serial="emulator-5554",
                    shard=None,
                    shards=None,
                    suite=suite,
                    junit_dir=pathlib.Path(self._tmp.name),
                )
        return captured

    def test_the_smoke_suite_passes_the_tag(self):
        argv = self._argv_for("smoke")
        self.assertIn("--tags", argv)
        self.assertEqual(argv[argv.index("--tags") + 1], SMOKE_TAG)

    def test_the_smoke_suite_passes_the_flows_root(self):
        argv = self._argv_for("smoke")
        self.assertEqual(argv[argv.index("--flow") + 1], str(_FLOWS))

    def test_the_full_suite_passes_no_tag(self):
        argv = self._argv_for("full")
        self.assertNotIn("--tags", argv)
        self.assertEqual(argv[argv.index("--flow") + 1], str(_FLOWS))


class CollectFlowsStillSeesEverythingTest(unittest.TestCase):
    """The tag filter is applied on top of the collection, not instead of it."""

    def test_collecting_the_root_finds_the_smoke_directory_too(self):
        if not _FLOWS.is_dir():
            self.skipTest(f"no flow directory at {_FLOWS}")
        collected = collect_flows(_FLOWS)
        self.assertTrue(
            any(f in collected for f in _SMOKE_DIR.glob("*.yaml")),
            "collecting the flow root no longer reaches the smoke directory",
        )


if __name__ == "__main__":
    unittest.main()
