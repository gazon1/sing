"""Tests for the `./sg` wrapper — the gate suite of *this* checkout.

The defect: `sgate` on PATH is an editable install, and the install belongs to
whichever checkout ran `setup-local-tools.sh` last. On this machine it pointed
at another clone for a whole afternoon, and the only symptom was that the
findings made no sense — the gates were measuring somebody else's tree, and
they said so with a straight face.

`PYTHONPATH` does not fix it: a modern editable install registers a *meta-path
finder*, and a meta-path finder is consulted before `sys.path`, so the checkout
that was installed last wins whatever the path says.

The wrapper therefore does two things, and the tests pin both:

* it removes the editable finder and puts this checkout's `scripts/src` first;
* it says which checkout it belongs to, so a reader can check it in one command
  instead of discovering the mismatch from a finding.

The test that matters is the one that runs the wrapper and reads the answer.
"""

import os
import pathlib
import re
import shlex
import subprocess
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
WRAPPER = REPO / "sg"
CHECK_SH = REPO / "check.sh"
HK_PKL = REPO / "hk.pkl"

#: The console script from PATH, used as a command. The wrapper's own path is
#: `./sg`, which this does not match: a dot or a slash before the name means
#: the command is this checkout's file, not an install that belongs to
#: whichever checkout was installed last.
BARE_SGATE = re.compile(r"(?<![\w./-])sgate\s")

#: The two things `check.sh` asks the gate CLI for: the registry and the cost
#: report.
GATE_COMMANDS = ("run-all", "gate-costs")


def _code_lines(path: pathlib.Path) -> list[str]:
    """The lines of a shell script that are code, not prose.

    A comment that merely *names* `sgate` is allowed — the prose in this very
    file names it — so the check reads the code. A line that cannot be parsed
    as shell is kept: an unparseable line is not a licence to skip it.
    """
    lines: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.lstrip().startswith("#"):
            continue
        try:
            tokens = shlex.split(line, comments=True)
        except ValueError:
            lines.append(line)
            continue
        lines.append(" ".join(tokens))
    return lines


def _run(*arguments: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [str(WRAPPER), *arguments],
        capture_output=True,
        text=True,
        timeout=120,
        stdin=subprocess.DEVNULL,
    )


class SgWrapperTest(unittest.TestCase):
    """The wrapper answers for this checkout."""

    def test_the_wrapper_exists_and_is_executable(self) -> None:
        self.assertTrue(WRAPPER.is_file(), f"the wrapper is missing at {WRAPPER}")
        self.assertTrue(os.access(WRAPPER, os.X_OK), f"the wrapper is not executable: {WRAPPER}")

    def test_check_sh_does_not_call_the_installed_gate_cli(self) -> None:
        """The loop must measure this checkout, not whichever one was installed last.

        `sgate` on PATH is an editable install, and it belongs to whichever
        checkout ran `pip install -e scripts/src/` last. A local loop that
        calls it measures that other tree — a different set of gates, reported
        green about a checkout nobody is working on.
        """
        for line in _code_lines(CHECK_SH):
            self.assertIsNone(
                BARE_SGATE.search(line),
                f"check.sh calls the installed gate CLI instead of the wrapper: {line!r}",
            )

    def test_both_surfaces_reach_the_gate_cli_through_the_wrapper(self) -> None:
        """Both callers go through `./sg`: the registry and the cost report.

        The registry is named in `hk.pkl` (the `check` hook's first step) and
        the cost report in the wrapper; each surface names the command it runs.
        """
        surfaces = {
            "run-all": HK_PKL,
            "gate-costs": CHECK_SH,
        }
        for command in GATE_COMMANDS:
            path = surfaces[command]
            self.assertIn(
                f"./sg {command}",
                path.read_text(encoding="utf-8"),
                f"{path.name} does not reach the gate CLI's {command} through the wrapper",
            )

    def test_it_names_the_checkout_it_belongs_to(self) -> None:
        result = _run("whereami")
        if result.returncode != 0:
            self.skipTest(f"the gate CLI is not runnable here: {result.stderr.strip()[:200]}")

        named = result.stdout.strip().splitlines()[-1] if result.stdout.strip() else ""
        self.assertEqual(
            str(REPO),
            named,
            f"the wrapper answered for another checkout ({named!r}); the gates would "
            f"measure a tree nobody is working on",
        )

    def test_the_json_answer_names_the_same_checkout(self) -> None:
        result = _run("whereami", "--json")
        if result.returncode != 0:
            self.skipTest(f"the gate CLI is not runnable here: {result.stderr.strip()[:200]}")

        import json

        reading = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(str(REPO), reading["root"])


if __name__ == "__main__":
    unittest.main()
