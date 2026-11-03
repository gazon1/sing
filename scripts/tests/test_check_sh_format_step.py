"""The local loop judges the formatting of the Kotlin sources.

`check.sh` used to run the gate registry, the test suite and detekt, and to say
nothing about formatting. Three other surfaces judged it — CI's `detekt` job, the
pre-push hook, and `scripts/lint-changed.py` over the changed files only — and
none of them was the loop a developer runs before merging. A formatting defect
that arrived on `main` and was carried by a later branch was invisible to that
loop; the CI job was the first surface to see it, minutes into a run.

These tests hold the step in place and prove it can fail:

* the step runs in BOTH lanes and goes through `timed`, so its price lands in the
  published cost of the loop (`docs/testing/gate-costs.md`);
* a planted formatting defect is reported with the name of the file that carries
  it — and where ktlint is not installed, the check fails with the command that
  installs it instead. It never passes silently: a check that could not run looks
  exactly like a check that had nothing to report.

The defect is judged by the command `check.sh` runs, read out of `check.sh`
itself: a test that spelled that command out in Python would keep proving the
script fails on a defect long after the step had stopped running that script.
"""

import pathlib
import re
import shlex
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
CHECK_SH = ROOT / "check.sh"
EDITORCONFIG = ROOT / ".editorconfig"
COSTS_PAGE = ROOT / "docs" / "testing" / "gate-costs.md"
BASELINE = ROOT / "config" / "docs" / "gate-benchmarks.json"

#: The step's name in the cost report. It is the name `timed` records, so the
#: loop's price and the published page speak of the same step.
STEP_NAME = "ktlint (formatting)"

#: The Kotlin the planted defect goes into. The trailing space is the defect:
#: `no-trailing-spaces` is a standard ktlint rule, and the repository's own
#: `.editorconfig` asks for the trailing whitespace to be trimmed. The space is
#: written out rather than left at the end of a line, where it would be
#: invisible — and invisible in a file that is about an invisible defect.
DEFECTIVE_SOURCE = (
    "package probe\n"
    "\n"
    "class Probe {\n"
    "    fun answer(): Int {\n"
    "        val answer = 42\n"
    "        return answer" + " " + "\n"
    "    }\n"
    "}\n"
)


def _text_between(text: str, start: str, end: str = "\n}\n") -> str:
    """The text between two markers — the body of a shell function."""
    begin = text.index(start)
    finish = text.index(end, begin)
    return text[begin:finish]


def _step_command(text: str) -> list[str]:
    """The command the step runs, read out of `check.sh` itself.

    The test runs THIS command, not a copy of it: a test that spelled the
    command out in Python would keep proving the script fails on a defect long
    after the step had stopped running that script.
    """
    match = re.search(rf'timed "{re.escape(STEP_NAME)}" (.+?) \|\| \{{', text)
    if match is None:
        raise AssertionError(f"check.sh does not run a step timed {STEP_NAME!r}")
    return shlex.split(match.group(1))


class FormatStepTest(unittest.TestCase):
    """The step is in the loop, in both lanes, and it can fail."""

    def test_the_format_check_is_timed_and_required(self):
        """The step is measured, and a missing ktlint fails it."""
        text = CHECK_SH.read_text(encoding="utf-8")
        self.assertIn(
            "run_format_check() {",
            text,
            "check.sh must define the format check",
        )
        body = _text_between(text, "run_format_check() {")
        self.assertIn(
            f'timed "{STEP_NAME}"',
            body,
            "the format check must be timed, or its price never reaches the cost report",
        )
        self.assertIn(
            "scripts/ktlint-lint.py",
            body,
            "the format check must run the ktlint CLI, not a hand-rolled formatter",
        )
        self.assertIn(
            "--require",
            body,
            "a missing ktlint must fail the check, not skip it",
        )

    def test_both_lanes_run_the_format_check(self):
        """The fast lane judges the formatting too — the check is unconditional."""
        text = CHECK_SH.read_text(encoding="utf-8")
        self.assertIn(
            "fast_lane() {",
            text,
            "check.sh must define the fast lane",
        )
        fast_lane = _text_between(text, "fast_lane() {")
        self.assertIn(
            "run_format_check",
            fast_lane,
            "the fast lane must judge the formatting: a stray trailing space is a defect "
            "this change can introduce",
        )
        full_loop = text[text.index("=== [1/$TOTAL] static gates") :]
        self.assertIn(
            "run_format_check",
            full_loop,
            "the full loop must judge the formatting",
        )

    def test_the_step_is_in_the_published_cost_report(self):
        """The step's price is published, like every other step of the loop."""
        self.assertIn(
            f"Step: {STEP_NAME}",
            COSTS_PAGE.read_text(encoding="utf-8"),
            "the published cost of the loop must name the format step",
        )
        self.assertIn(
            STEP_NAME,
            BASELINE.read_text(encoding="utf-8"),
            "the recorded baseline must carry the format step",
        )

    def test_a_planted_defect_is_reported_with_its_file(self):
        """A formatting defect fails the check and names the file that carries it."""
        command = _step_command(CHECK_SH.read_text(encoding="utf-8"))
        self.assertEqual(
            command[0],
            "python3",
            "the step must run a Python script, so the test can run it with this interpreter",
        )
        with tempfile.TemporaryDirectory() as directory:
            tree = pathlib.Path(directory)
            # The repository's own rules judge the file: ktlint reads the
            # .editorconfig of the tree it runs in.
            (tree / ".editorconfig").write_bytes(EDITORCONFIG.read_bytes())
            source = tree / "Sample.kt"
            source.write_text(DEFECTIVE_SOURCE, encoding="utf-8")

            result = subprocess.run(
                [sys.executable, *command[1:], str(source)],
                capture_output=True,
                text=True,
                cwd=ROOT,
                check=False,
            )
            output = result.stdout + result.stderr

            self.assertNotEqual(
                result.returncode,
                0,
                "the format check must fail on a formatting defect",
            )
            if "ktlint is not installed" in output:
                # The other half of the contract: a missing ktlint is a failure
                # that names the command that installs it.
                self.assertIn(
                    "setup-local-tools.sh",
                    output,
                    "a missing ktlint must be reported with the command that installs it",
                )
            else:
                self.assertIn(
                    source.name,
                    output,
                    "the report must name the file that carries the defect",
                )
