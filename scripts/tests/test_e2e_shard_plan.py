#!/usr/bin/env python3
"""Tests for scripts/e2e-shard-plan.py — the plan and the runner must agree.

The shard is `index % shards` over the runner's flow list, so a plan that
disagrees with the runner about either the count or the order reproduces
*different flows* and says nothing about the shard that failed. These tests
pin both agreements.

The plan script asks the runner for its flow list and its suite selection
rather than re-implementing them, so most of these tests are about the
*plan file* and the *workflow* — the two places where a value can still be
written down twice.
"""
from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


def _load_plan_script():
    path = ROOT / "scripts" / "e2e-shard-plan.py"
    spec = importlib.util.spec_from_file_location("e2e_shard_plan", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


plan = _load_plan_script()


class TestPlanFile(unittest.TestCase):
    def test_the_plan_names_both_suites(self):
        suites = plan.read_plan()
        self.assertIn("full", suites)
        self.assertIn("smoke", suites)

    def test_every_suite_has_a_positive_shard_count(self):
        for suite, count in plan.read_plan().items():
            self.assertGreater(count, 0, suite)

    def test_an_unreadable_plan_is_an_error_not_a_default(self):
        with self.assertRaises(ValueError):
            plan.read_plan(ROOT / "does-not-exist.txt")

    def test_a_malformed_line_is_an_error(self):
        with self.assertRaises(ValueError):
            plan.read_plan(ROOT / "scripts" / "e2e-shard-plan.py")


class TestThePlanAndTheRunnerAgree(unittest.TestCase):
    """The nightly's matrix and the local reproduction must be the same run."""

    def test_the_shard_counts_are_the_ones_the_nightly_uses(self):
        # The matrix in .github/workflows/e2e.yml is built from this file, so
        # the two cannot disagree — but only if the workflow really reads it.
        workflow = (ROOT / ".github" / "workflows" / "e2e.yml").read_text()
        self.assertIn("scripts/e2e-shard-plan.py", workflow)
        self.assertNotIn("shards='[1,2,3,4]'", workflow)

    def test_the_script_asks_the_runner_for_its_flows(self):
        # The selection and the order are the runner's facts. A plan that
        # re-implemented them would be a second place for them to be wrong,
        # and the disagreement would show up as a green reproduction of a red
        # shard.
        from emulator.maestro._flow import collect_flows, suite_selection

        target, tags = suite_selection(ROOT / "Maestro" / "flows", "full")
        self.assertEqual(
            [str(p) for p in plan.collect_flows(target)],
            [str(p) for p in collect_flows(target)],
        )
        self.assertEqual(
            suite_selection(ROOT / "Maestro" / "flows", "smoke")[1],
            ["smoke"],
            tags,
        )

    def test_every_flow_belongs_to_exactly_one_shard(self):
        flows = plan.collect_flows(ROOT / "Maestro" / "flows")
        shards = plan.read_plan()["full"]
        seen: list[pathlib.Path] = []
        for shard in range(1, shards + 1):
            seen.extend(plan.shard_flows(flows, shard, shards))
        self.assertEqual(sorted(seen), sorted(flows))


class TestTheCommandLine(unittest.TestCase):
    def _run(self, argv):
        import contextlib
        import io

        out = io.StringIO()
        err = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = plan.main(argv)
        return code, out.getvalue(), err.getvalue()

    def test_the_matrix_is_the_github_spelling(self):
        code, out, _ = self._run(["--suite", "full", "--matrix"])
        self.assertEqual(code, 0)
        self.assertEqual(out.strip(), "[1,2,3,4]")

    def test_an_out_of_range_shard_is_refused(self):
        code, _, err = self._run(["--suite", "full", "--shard", "9"])
        self.assertEqual(code, 1)
        self.assertIn("out of range", err)

    def test_an_unknown_suite_is_refused(self):
        code, _, err = self._run(["--suite", "nope"])
        self.assertEqual(code, 1)
        self.assertIn("no plan for suite", err)

    def test_a_shard_prints_its_flows_and_the_reproduction(self):
        code, out, _ = self._run(["--suite", "full", "--shard", "2"])
        self.assertEqual(code, 0)
        self.assertIn("Maestro/flows", out)
        self.assertIn("run-maestro-e2e.py", out)

    def test_the_smoke_shard_marks_the_flows_the_suite_selects(self):
        code, out, _ = self._run(["--suite", "smoke", "--shard", "1"])
        self.assertEqual(code, 0)
        self.assertIn("selects", out)
        self.assertIn("smoke", out)


if __name__ == "__main__":
    unittest.main()
