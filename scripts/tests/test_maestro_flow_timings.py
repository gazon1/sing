"""Tests for scripts/maestro-flow-timings.py — how long each Maestro flow takes.

The shard plan balances the suite by FLOW COUNT, and a count is a proxy for a
cost: a shard of seven flows that each finish in five seconds is treated as
equal to a shard of seven flows that each hit the 300-second timeout.

The times live in `config/docs/maestro-flow-timings.txt`, and that file was
EMPTY — with no flow measured — while the gate that compares the shards still
spoke about cost. Two things had to be true:

* the empty state is said out loud, because the comparison falls back to
  counting flows and a reader who does not know that takes a count for a
  cost;
* `--record` writes only what a run measured, and never rewrites the file
  with what it already holds — a floor that changed itself would be a floor
  nobody decided.
"""

import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent / "maestro-flow-timings.py"
)
spec = importlib.util.spec_from_file_location("maestro_flow_timings", MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def _report(flow: str, seconds: float) -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        "<testsuite name=\"maestro\" tests=\"1\" failures=\"0\">\n"
        f'  <testcase name="{flow}" classname="{flow}" file="{flow}" '
        f'time="{seconds}"/>\n'
        "</testsuite>\n"
    )


class EmptyStateTest(unittest.TestCase):
    """The empty state is a state, and it is said out loud."""

    def _list(self, results: pathlib.Path) -> str:
        """`--list` against an empty floor file and the given results directory.

        Both inputs are temporary. The repository's own
        `build/maestro-results/` holds whatever the last local Maestro run
        wrote, so a test that reads it has two different answers on two
        different machines — it fails where Maestro has run and passes where
        it has not. The answer has to depend on what the test handed the
        script, and on nothing else.
        """
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            floor = tmp_path / "maestro-flow-timings.txt"
            floor.write_text("# no floors\n", encoding="utf-8")
            result = subprocess.run(
                [
                    sys.executable,
                    str(MODULE_PATH),
                    "--list",
                    "--floor",
                    str(floor),
                    "--results",
                    str(results),
                ],
                cwd=mod.ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertEqual(result.returncode, 0)
        return result.stdout

    def test_the_list_names_the_fallback(self):
        """A reader who does not know the fallback takes a count for a cost."""
        with tempfile.TemporaryDirectory() as tmp:
            out = self._list(pathlib.Path(tmp) / "no-reports-here")
        self.assertIn(
            "falls back to counting flows",
            out,
            "the empty state has to name what the comparison does without it",
        )

    def test_an_empty_results_directory_is_the_same_empty_state(self):
        """A directory that exists but holds no report is still the empty state."""
        with tempfile.TemporaryDirectory() as tmp:
            results = pathlib.Path(tmp) / "results"
            results.mkdir()
            out = self._list(results)
        self.assertIn(
            "falls back to counting flows",
            out,
            "a directory with no report in it is the empty state, not a run",
        )

    def test_a_run_is_listed_instead_of_the_empty_state(self):
        """A measured run is a list, and the empty state's line is not in it."""
        with tempfile.TemporaryDirectory() as tmp:
            results = pathlib.Path(tmp) / "results"
            results.mkdir()
            (results / "a.xml").write_text(
                _report("Maestro/flows/a.yaml", 3.5), encoding="utf-8"
            )
            out = self._list(results)
        self.assertIn("Maestro/flows/a.yaml", out)
        self.assertNotIn("falls back to counting flows", out)

    def test_the_recorded_floors_are_empty_on_this_tree(self):
        """The file is a record, and an empty record is what it holds today."""
        # Not an assertion about the future — a floor file that is not empty
        # is fine, and this test only says that the readers agree with the
        # file.
        floors = mod._floors()
        self.assertIsInstance(floors, dict)
        for flow, seconds in floors.items():
            self.assertGreater(seconds, 0, f"{flow} has a floor of {seconds}")


class RecordTest(unittest.TestCase):
    """`--record` folds a run into the floor, and nothing else does."""

    def _floor(self, tmp: pathlib.Path) -> pathlib.Path:
        path = tmp / "maestro-flow-timings.txt"
        path.write_text(
            "# a floor\nMaestro/flows/a.yaml 10\n", encoding="utf-8"
        )
        return path

    def _run(self, floor: pathlib.Path, results: pathlib.Path) -> int:
        old = mod.FLOOR_FILE
        mod.FLOOR_FILE = floor
        try:
            return mod.main(["--record", "--results", str(results)])
        finally:
            mod.FLOOR_FILE = old

    def test_a_record_without_a_run_changes_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            floor = self._floor(tmp_path)
            before = floor.read_text(encoding="utf-8")
            code = self._run(floor, tmp_path / "no-reports-here")
            self.assertEqual(code, 0)
            self.assertEqual(
                floor.read_text(encoding="utf-8"),
                before,
                "a run that measured nothing must not rewrite the floor file: "
                "the diff would look like a recorded run",
            )

    def test_a_faster_run_lowers_the_floor(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            floor = self._floor(tmp_path)
            results = tmp_path / "results"
            results.mkdir()
            (results / "a.xml").write_text(
                _report("Maestro/flows/a.yaml", 7.5), encoding="utf-8"
            )
            self._run(floor, results)
            self.assertIn("Maestro/flows/a.yaml 7.5", floor.read_text(encoding="utf-8"))

    def test_a_slower_run_does_not_raise_the_floor(self):
        """The floor is a LOWER BOUND: a run that was slow once does not make
        every later plan worse."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            floor = self._floor(tmp_path)
            results = tmp_path / "results"
            results.mkdir()
            (results / "a.xml").write_text(
                _report("Maestro/flows/a.yaml", 20.0), encoding="utf-8"
            )
            self._run(floor, results)
            self.assertIn("Maestro/flows/a.yaml 10", floor.read_text(encoding="utf-8"))
            self.assertNotIn("Maestro/flows/a.yaml 20", floor.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
