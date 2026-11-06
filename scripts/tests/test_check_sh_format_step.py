"""The local loop judges the formatting of the Kotlin sources.

`check.sh` used to run the gate registry, the test suite and detekt, and to say
nothing about formatting. Three other surfaces judged it — CI's `detekt` job, the
pre-push hook, and `scripts/lint-changed.py` over the changed files only — and
none of them was the loop a developer runs before merging. A formatting defect
that arrived on `main` and was carried by a later branch was invisible to that
loop; the CI job was the first surface to see it, minutes into a run.

The step lives in `hk.pkl` now (the `check` hook), and these tests hold it there
and prove it can fail:

* the step runs in EVERY lane — it has no profile — and hk times every step it
  runs, so its price lands in the published cost of the loop
  (`docs/testing/gate-costs.md`);
* a planted formatting defect is reported with the name of the file that carries
  it — and where ktlint is not installed, the check fails with the command that
  installs it instead. It never passes silently: a check that could not run looks
  exactly like a check that had nothing to report.

The defect is judged by the command `hk.pkl` runs, read out of `hk.pkl` itself:
a test that spelled that command out in Python would keep proving the script
fails on a defect long after the step had stopped running that script.
"""

import pathlib
import re
import shlex
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
HK_PKL = ROOT / "hk.pkl"
EDITORCONFIG = ROOT / ".editorconfig"
COSTS_PAGE = ROOT / "docs" / "testing" / "gate-costs.md"
BASELINE = ROOT / "config" / "docs" / "gate-benchmarks.json"

#: The step's name in the config, and therefore in the cost report: hk records
#: the wall time of every step under its own name.
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

#: The command, read from the step's `check` string. `{{files}}` is hk's
#: template — hk appends the file arguments — so it is dropped here and the
#: file is appended by the test.
_STEP_COMMAND = re.compile(r"(python3 scripts/ktlint-lint\.py --require(?: \{\{files\}\})?)")


def _step_block(text: str) -> str:
    """The `{ ... }` block of the ktlint step in `hk.pkl`."""
    key = f'["{STEP_NAME}"]'
    start = text.index(key) + len(key)
    depth = 0
    for index in range(start, len(text)):
        char = text[index]
        if char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
    raise AssertionError(f"the block after {key!r} is not closed")


def _step_command(text: str) -> list[str]:
    """The command the step runs, read out of `hk.pkl` itself."""
    match = _STEP_COMMAND.search(_step_block(text))
    if match is None:
        raise AssertionError(f"hk.pkl does not run the {STEP_NAME!r} step through ktlint")
    return shlex.split(match.group(1).replace("{{files}}", ""))


class FormatStepTest(unittest.TestCase):
    """The step is in every lane, and it can fail."""

    def test_the_format_check_is_required(self):
        """The step judges the tree, and a missing ktlint fails it."""
        block = _step_block(HK_PKL.read_text(encoding="utf-8"))
        self.assertIn(
            "scripts/ktlint-lint.py",
            block,
            "the format check must run the ktlint CLI, not a hand-rolled formatter",
        )
        self.assertIn(
            "--require",
            block,
            "a missing ktlint must fail the check, not skip it",
        )

    def test_every_lane_runs_the_format_check(self):
        """No profile on the step: the fast lane judges the formatting too."""
        block = _step_block(HK_PKL.read_text(encoding="utf-8"))
        self.assertNotIn(
            "profiles",
            block,
            "the format check must run in every lane: a stray trailing space is a defect "
            "the fast lane can introduce too",
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
        command = _step_command(HK_PKL.read_text(encoding="utf-8"))
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
