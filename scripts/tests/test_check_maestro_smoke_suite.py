"""Tests for scripts/check-maestro-smoke-suite.py.

The gate exists because the size of the suite a pull request runs was
fiction. `Maestro/CONVENTIONS.md` said six smoke flows; four of the six
paths it named did not exist, and the `smoke` tag had meanwhile spread to
27 flows. Nothing was red, because the tag is what selects the suite and
the prose is what people plan against.

Two properties matter, and both are about the promise rather than the flows:

  - the count in the conventions is the count the tag selects;
  - a smoke shard does not cost more than a full-suite shard — otherwise the
    "minimal" suite a pull request runs is the slow path, and it is the path
    every pull request takes.

A gate that only checked the first would let the tag grow past the shard
count without anyone noticing, which is how the suite got into this state.
"""

import importlib.util
import pathlib
import tempfile
import types
import unittest

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent / "check-maestro-smoke-suite.py"
)
spec = importlib.util.spec_from_file_location("check_maestro_smoke_suite", MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class DocumentedCountTest(unittest.TestCase):
    """The number in the conventions is read out of the conventions."""

    def test_the_count_is_read(self):
        self.assertEqual(
            mod.documented_count("Current smoke flows (45):\n"),
            45,
        )

    def test_a_document_without_the_line_has_no_count(self):
        self.assertIsNone(mod.documented_count("nothing to see here\n"))

    def test_the_line_must_start_the_line(self):
        """A number in the middle of a sentence is prose, not a promise."""
        self.assertIsNone(mod.documented_count("see Current smoke flows (45):\n"))


class RepositoryStateTest(unittest.TestCase):
    """The gate passes on this repository, as it stands."""

    def test_the_conventions_match_the_tag(self):
        findings = mod.scan()
        self.assertEqual(
            findings,
            [],
            "the smoke suite and the number written down for it disagree; "
            "update Maestro/CONVENTIONS.md in the same commit that changed "
            "the tag",
        )

    def test_a_smoke_shard_costs_no_more_than_a_full_shard(self):
        plan = mod._load_shard_plan().read_plan()
        self.assertLessEqual(
            mod.smoke_shard_size(plan["smoke"]),
            mod.full_shard_size(plan["full"]),
            "a pull request would cost more than the nightly",
        )


class FindingTest(unittest.TestCase):
    """The gate says which way it failed, and it fails."""

    def test_a_wrong_documented_count_is_a_finding(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "CONVENTIONS.md"
            path.write_text("Current smoke flows (0):\n")
            old = mod.CONVENTIONS
            mod.CONVENTIONS = path
            try:
                findings = mod.scan()
            finally:
                mod.CONVENTIONS = old
        self.assertEqual([f["kind"] for f in findings], ["size does not match"])

    def test_a_document_without_a_count_is_a_finding(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "CONVENTIONS.md"
            path.write_text("# Smoke Subset\n\nThe tag is the selection.\n")
            old = mod.CONVENTIONS
            mod.CONVENTIONS = path
            try:
                findings = mod.scan()
            finally:
                mod.CONVENTIONS = old
        self.assertEqual([f["kind"] for f in findings], ["no documented size"])


class CostFindingTest(unittest.TestCase):
    """A smoke shard heavier than a full-suite shard is a finding.

    The plan is supplied rather than read, so the rule is exercised whatever
    the suite's size: a full suite cut into more shards than it has flows puts
    one flow on a shard, and one smoke shard cannot cost less than that.
    """

    def _kinds(self, plan: dict[str, int]) -> list[str]:
        old = mod._load_shard_plan
        mod._load_shard_plan = lambda: types.SimpleNamespace(read_plan=lambda: plan)
        try:
            return [f["kind"] for f in mod.scan()]
        finally:
            mod._load_shard_plan = old

    def test_a_heavy_smoke_shard_is_a_finding(self):
        self.assertEqual(
            self._kinds({"smoke": 1, "full": 1000}),
            ["smoke shard costs more than a full shard"],
        )

    def test_a_plan_without_both_suites_is_a_finding(self):
        self.assertEqual(self._kinds({"smoke": 1}), ["no shard plan"])


class ExitCodeTest(unittest.TestCase):
    """The exit code is the gate's verdict."""

    def _run(self, *args) -> int:
        import subprocess
        import sys

        result = subprocess.run(
            [sys.executable, str(MODULE_PATH), *args],
            cwd=mod.ROOT,
            capture_output=True,
            text=True,
        )
        return result.returncode

    def test_a_clean_tree_passes(self):
        self.assertEqual(self._run(), 0)

    def test_the_dry_run_exits_zero_whatever_it_found(self):
        """`--check` is a report, and the registry must not invoke it that way."""
        self.assertEqual(self._run("--check"), 0)


if __name__ == "__main__":
    unittest.main()
