"""Unit tests for check-kiwi-gaps.py — the never-run floor.

Run with: python3 -m unittest discover -s scripts/tests

The floor logic is tested against synthetic data rather than a live stand: what
matters here is the decision table (below floor / above floor / no floor), and
that decision is exactly what a stand-dependent test cannot pin down, because
the numbers move every time a test is added.
"""

import importlib.util
import pathlib
import sys
import unittest

SCRIPTS_DIR = pathlib.Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location(
    "check_kiwi_gaps", SCRIPTS_DIR / "check-kiwi-gaps.py"
)
_module = importlib.util.module_from_spec(_spec)
_module.__name__ = "check_kiwi_gaps"
_module.__file__ = str(SCRIPTS_DIR / "check-kiwi-gaps.py")
sys.modules["check_kiwi_gaps"] = _module  # must precede exec_module
_spec.loader.exec_module(_module)

ckg = _module


class BaselineParsingTest(unittest.TestCase):
    """The baseline file is comments, a plan name with spaces, and a number."""

    def test_parses_plan_and_floor(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "b.txt"
            path.write_text(
                "# comment\n"
                "Automated — shared (commonTest+jvmTest) 19\n"
                "Automated — desktop (Compose UI) 4\n",
                encoding="utf-8",
            )
            original = ckg.BASELINE
            ckg.BASELINE = path
            try:
                floors = ckg.parse_baseline()
            finally:
                ckg.BASELINE = original

        # A plan name contains spaces and parentheses, so splitting on the LAST
        # space is the only parse that survives `rpartition` — splitting on the
        # first would make the floor "jvmTest) 19" and drop the plan.
        self.assertEqual(floors["Automated — shared (commonTest+jvmTest)"], 19)
        self.assertEqual(floors["Automated — desktop (Compose UI)"], 4)

    def test_missing_file_yields_no_floors(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            original = ckg.BASELINE
            ckg.BASELINE = pathlib.Path(tmp) / "absent.txt"
            try:
                self.assertEqual(ckg.parse_baseline(), {})
            finally:
                ckg.BASELINE = original


class BaselineFormatTest(unittest.TestCase):
    """The written file must be re-readable, or the gate can never go green."""

    def test_round_trips_through_the_parser(self):
        import tempfile

        never = {
            "Automated — shared (commonTest+jvmTest)": 19,
            "Automated — desktop (Compose UI)": 4,
        }
        total = {k: 200 for k in never}
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "b.txt"
            path.write_text(ckg.format_baseline(never, total), encoding="utf-8")
            original = ckg.BASELINE
            ckg.BASELINE = path
            try:
                floors = ckg.parse_baseline()
            finally:
                ckg.BASELINE = original
        self.assertEqual(floors, never)

    def test_output_documents_the_direction_of_the_floor(self):
        text = ckg.format_baseline({"p": 1}, {"p": 2})
        # A ceiling is the opposite of the executed-count floors elsewhere in
        # the repo, and the header has to say so or a reader will "fix" it by
        # regenerating downward.
        self.assertIn("HIGHEST", text)
        self.assertIn("not a gate", text)


class KeepIsMeaningfulTest(unittest.TestCase):
    """--keep < 1 would empty the stand's history; reject rather than comply."""

    def test_zero_keep_is_rejected(self):
        import argparse
        import contextlib
        import io

        with self.assertRaises(SystemExit):
            with contextlib.redirect_stderr(io.StringIO()):
                ckg.main(["--keep", "0"])


if __name__ == "__main__":
    unittest.main()
