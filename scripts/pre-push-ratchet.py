#!/usr/bin/env python3
# no-sgate: this script compares two captured outputs and one test report — it
# judges nothing about the tree it sits in, it only says whether a failure the
# push carries is the push's own.
"""pre-push-ratchet.py — is this failure the push's own, or was it already there?

The pre-push hook judges the tree that is being pushed. A repository's trees
are not green: the trunk itself can carry findings that have nothing to do with
the branch being pushed, and a hook that reads the whole tree and blocks on
everything it finds then blocks every push, forever, on somebody else's debt.

The rule this tool implements is one sentence:

    **A push must not make a gate redder than the base it starts from.**

The base is the commit the branch forked from (the merge-base with the trunk),
and it is judged with the same gate, in the same way, on the same machine. A
finding that is already there is *pre-existing*: it is the trunk's, and the
trunk's CI run is where it is reported. A finding that is not there is the
push's, and the push stops.

Two things can be compared, and they are compared here:

  - `outputs` — the text of two runs of the same gate. The findings of the
    pushed tree are compared with the findings of the base tree, and what is
    left over — what the base does not have — is the push's own.
  - `tests` — the JUnit report of the gate suite's own tests. The tests that
    failed on the pushed tree are re-run on the base tree, one by one, and a
    test that fails on the base as well is pre-existing.

The comparison is deliberately coarse about *where* a finding is and exact
about *what* it is: a finding's position in a file moves when the file moves,
so the position is normalised away, while the file, the rule and the message
are what identify it.

Usage:
    python3 scripts/pre-push-ratchet.py outputs --pushed FILE --base FILE \\
        [--pushed-tree PATH] [--base-tree PATH]
    python3 scripts/pre-push-ratchet.py tests --report FILE --base-tree PATH

Exit codes:
    0 — the push carries nothing of its own
    1 — the push carries findings of its own (they are printed)
    2 — the comparison could not be made (the base is missing, the report is
        unreadable) — the caller must treat this as "the push carries findings"
"""

from __future__ import annotations

import argparse
import collections
import os
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

#: The scripts directory — the loader below lives beside this file, and a
#: script run by path has it on the path already, but a test that loads this
#: file by path does not.
_SCRIPTS_DIR = str(pathlib.Path(__file__).resolve().parent)
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)

from gitenv_loader import load_gitenv  # noqa: E402

#: The environment a git command runs in when its caller is a hook. It is the
#: answer the repository owns (scripts/src/sgate/gatekit/gitenv.py), loaded by
#: path, because the package that holds it cannot be imported by the ambient
#: interpreter the hook runs.
tree_env = load_gitenv().tree_env


#: The colour codes a tool writes into its output. They say nothing about the
#: finding, and they differ between two runs of the same gate when one of them
#: is not a terminal.
ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")

#: A wall-clock timestamp. Two runs of the same gate are minutes apart, and the
#: timestamp is the one thing that is guaranteed to differ.
TIME_RE = re.compile(r"\d{2}:\d{2}:\d{2}\.\d+")

#: A position inside a file — `File.kt:44:7`. The position is where the
#: finding is, not what it is: the same finding moves when the file above it
#: grows, and a finding that moved is not a new finding.
#:
#: The position is only read as a position when it follows a name with a
#: dot in it — a file. A colon followed by digits in the middle of a sentence
#: is something else (`sha256:1234…`), and turning that into a position
#: would throw away the very thing that identifies the finding.
POSITION_RE = re.compile(r"([\w./-]*\.[A-Za-z0-9]+):\d+(?::\d+)?")

#: A count of files — "checking 2471 file(s)". The pushed tree has more files
#: than the base whenever the push adds any, and that is not a finding.
COUNT_RE = re.compile(r"\b\d+ file")

#: The failure lines `unittest` prints: `FAIL: test_x (module.Class.test_x)`.
#: The id in the parentheses is the test's own name.
#:
#: A sub-test prints its parameters after the id — `FAIL: test_x
#: (module.Class.test_x) (consumer=setup-local-tools.sh)` — and the id is still
#: the test's own. The line is therefore read up to the first parentheses: a
#: line that does not match is a line whose failure cannot be attributed to a
#: test, and a failure that cannot be attributed is not evidence that the base
#: tree passes it.
UNITTEST_FAILURE_RE = re.compile(r"^(?:FAIL|ERROR): .+?\((?P<id>[^()]+)\)")

#: The setup failures `unittest` reports for a whole class or a whole module.
#: They are not findings of one test: they are findings of the class.
_SETUP_SUFFIXES = (
    ".setUpClass",
    ".tearDownClass",
    ".setUpModule",
    ".tearDownModule",
)


def canonical_test_id(test_id: str) -> str:
    """The id of a test, without the setup failure it may have been reported as.

    A class whose `setUpClass` fails is reported by `unittest` as
    `module.Class.setUpClass`, and by the JUnit report as the class
    `module.Class`. The two have to name the same thing, or a class that fails
    on the base tree looks like a class that does not exist on it.
    """
    for suffix in _SETUP_SUFFIXES:
        if test_id.endswith(suffix):
            return test_id[: -len(suffix)]
    return test_id


def covers(pushed_id: str, base_id: str) -> bool:
    """Does a failure on the base tree account for a failure on the pushed tree?

    A module that fails to set up is reported as the module; the individual
    tests inside it are then reported as failures of that module. So a failure
    of a module on the base accounts for a failure of the same module on the
    pushed tree, whatever the test inside it was. The same goes for a class
    whose `setUpClass` failed.
    """
    if pushed_id == base_id:
        return True
    # The base failed a class or a module; the pushed tree failed a test
    # inside it. The names have to meet at a dot, so `mod` does not account
    # for `module.test_x`.
    return pushed_id.startswith(f"{base_id}.")


def normalize(text: str, tree: str | None = None) -> list[str]:
    """The findings of one run, in a form two runs can be compared in.

    What is normalised away is what says *where* the finding is (the tree's
    path, the line, the column, the timestamp, the file count). What is left
    is what says *what* the finding is.
    """
    text = ANSI_RE.sub("", text)
    if tree:
        text = text.replace(tree, "<TREE>")
    text = TIME_RE.sub("<TIME>", text)
    text = POSITION_RE.sub(r"\1", text)
    text = COUNT_RE.sub("<N> file", text)
    return [line.strip() for line in text.splitlines() if line.strip()]


def read_text(path: str) -> str:
    """The text of a file, or an empty string when it cannot be read."""
    try:
        return pathlib.Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def compare_outputs(
    pushed: str,
    base: str,
    pushed_tree: str | None,
    base_tree: str | None,
) -> list[str]:
    """The findings the pushed tree has and the base tree does not."""
    pushed_lines = collections.Counter(normalize(read_text(pushed), pushed_tree))
    base_lines = collections.Counter(normalize(read_text(base), base_tree))
    return list((pushed_lines - base_lines).elements())


def failing_test_ids(report: str) -> list[str]:
    """The ids of the tests that failed, from a JUnit report.

    The report is the authority, not the console: the console of a run of the
    suite carries the output of the runs the suite itself makes, so the failing
    lines in it are not only the failures of that run.
    """
    try:
        root = ET.parse(report).getroot()
    except (OSError, ET.ParseError) as exc:
        raise ValueError(f"could not read the report {report}: {exc}") from exc

    ids: list[str] = []
    for case in root.iter("testcase"):
        if not [child for child in case if child.tag in ("failure", "error")]:
            continue
        classname = case.attrib.get("classname", "")
        name = case.attrib.get("name", "")
        if name in ("setUpClass", "tearDownClass", "setUpModule", "tearDownModule"):
            ids.append(classname)
        else:
            ids.append(f"{classname}.{name}")
    return [canonical_test_id(test_id) for test_id in ids]


def run_tests_on_base(base_tree: str, test_ids: list[str]) -> set[str]:
    """The ids of the tests that fail on the base tree.

    The tests are run one run of `unittest` for all of them: a run that names
    a test which does not exist on the base reports that test as absent, which
    is the answer the caller needs — a test that is not there cannot be
    pre-existing.
    """
    tests_dir = pathlib.Path(base_tree) / "scripts" / "tests"
    if not tests_dir.is_dir():
        return set()

    env = tree_env()
    env["PYTHONPATH"] = os.pathsep.join(
        [
            str(pathlib.Path(base_tree) / "scripts" / "src"),
            str(tests_dir),
        ],
    )
    # The marker the hook sets: the suite's own test that drives the hook
    # through a real push must not push again from inside a hook.
    env["GATE_SUITE_INSIDE_HOOK"] = "1"

    result = subprocess.run(
        [sys.executable, "-m", "unittest", *test_ids],
        cwd=str(tests_dir),
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )
    failed: set[str] = set()
    for line in (result.stdout + result.stderr).splitlines():
        match = UNITTEST_FAILURE_RE.match(line.strip())
        if match:
            failed.add(canonical_test_id(match.group("id")))
    return failed


def cmd_outputs(args: argparse.Namespace) -> int:
    """Compare two captured outputs of the same gate."""
    new = compare_outputs(args.pushed, args.base, args.pushed_tree, args.base_tree)
    if not new:
        return 0
    for line in new:
        print(line)
    return 1


def cmd_tests(args: argparse.Namespace) -> int:
    """Compare the failing tests of the pushed tree with the base tree."""
    try:
        pushed_ids = failing_test_ids(args.report)
    except ValueError as exc:
        print(f"pre-push-ratchet: {exc}")
        return 2

    if not pushed_ids:
        return 0

    base_failed = run_tests_on_base(args.base_tree, pushed_ids)
    new = [test_id for test_id in pushed_ids if not any(covers(test_id, f) for f in base_failed)]
    if not new:
        return 0
    for test_id in new:
        print(test_id)
    return 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    outputs = sub.add_parser("outputs", help="compare two captured outputs of one gate")
    outputs.add_argument("--pushed", required=True, help="the output of the run on the pushed tree")
    outputs.add_argument("--base", required=True, help="the output of the run on the base tree")
    outputs.add_argument("--pushed-tree", default=None, help="the path of the pushed tree")
    outputs.add_argument("--base-tree", default=None, help="the path of the base tree")
    outputs.set_defaults(func=cmd_outputs)

    tests = sub.add_parser("tests", help="compare the failing tests with the base tree")
    tests.add_argument("--report", required=True, help="the JUnit report of the pushed tree's run")
    tests.add_argument("--base-tree", required=True, help="the base tree the tests are re-run in")
    tests.set_defaults(func=cmd_tests)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
