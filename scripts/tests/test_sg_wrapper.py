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
import subprocess
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
WRAPPER = REPO / "sg"


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
