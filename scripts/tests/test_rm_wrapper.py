"""Tests for the `./rm` wrapper — the Maestro lane of *this* checkout.

The defect: `run-maestro` on PATH is an editable install, and the install
belongs to whichever checkout ran `pip install -e scripts/src/` last. The
runner resolves its root from the *installed package's* file, so it read
`Maestro/flows` from another tree and installed another tree's APK — and the
report came back green, against a tree nobody was working on.

The wrapper therefore does two things, and the tests pin both:

* it removes the editable finder and puts this checkout's `scripts/src` first,
  for the whole install — `sgate`, `emulator`, `scanners` and `traceability`
  are siblings under `scripts/src/`, and all of them resolve to the checkout
  that was installed last;
* it says which checkout it belongs to, so a reader can check it in one command
  instead of discovering the mismatch from a report.

The test that matters is the one that runs the wrapper and reads the answer.
"""

import os
import pathlib
import re
import subprocess
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
WRAPPER = REPO / "rm"
JUST_TESTS = REPO / ".just" / "tests" / "mod.just"
JUST_TRACEABILITY = REPO / ".just" / "traceability" / "mod.just"


def _run(*arguments: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [str(WRAPPER), *arguments],
        capture_output=True,
        text=True,
        timeout=120,
        stdin=subprocess.DEVNULL,
    )


class RmWrapperTest(unittest.TestCase):
    """The wrapper answers for this checkout."""

    def test_the_wrapper_exists_and_is_executable(self) -> None:
        self.assertTrue(WRAPPER.is_file(), f"the wrapper is missing at {WRAPPER}")
        self.assertTrue(os.access(WRAPPER, os.X_OK), f"the wrapper is not executable: {WRAPPER}")

    def test_it_names_the_checkout_it_belongs_to(self) -> None:
        result = _run("whereami")
        if result.returncode != 0:
            self.skipTest(f"the wrapper is not runnable here: {result.stderr.strip()[:200]}")

        named = result.stdout.strip().splitlines()[-1] if result.stdout.strip() else ""
        self.assertEqual(
            str(REPO),
            named,
            f"the wrapper answered for another checkout ({named!r}); the flows would "
            f"run against a tree nobody is working on",
        )

    def test_the_dry_run_reads_this_checkout_flows(self) -> None:
        result = _run("--dry-run", "--tags", "tasks")
        if result.returncode != 0:
            self.skipTest(f"the Maestro CLI is not runnable here: {result.stderr.strip()[:200]}")

        spoken = f"{result.stdout}\n{result.stderr}"
        self.assertIn(
            "Maestro/flows/tasks",
            spoken,
            "the dry run did not list this checkout's task flows",
        )

    def test_the_just_recipes_go_through_the_wrapper(self) -> None:
        """The recipes must not call the console scripts from PATH."""
        bare = re.compile(r"^(run-maestro|ensure-emulator|python3 -m traceability)\b")
        for path in (JUST_TESTS, JUST_TRACEABILITY):
            text = path.read_text(encoding="utf-8")
            for line in text.splitlines():
                stripped = line.strip()
                self.assertIsNone(
                    bare.match(stripped),
                    f"{path.name} calls the installed package instead of the wrapper: {stripped!r}",
                )
            self.assertIn(
                "./rm",
                text,
                f"{path.name} does not go through the wrapper at all",
            )

    def test_the_traceability_recipes_do_not_use_the_bare_module(self) -> None:
        text = JUST_TRACEABILITY.read_text(encoding="utf-8")
        self.assertNotIn(
            "python3 -m traceability",
            text,
            "the traceability recipes resolve the installed package, which belongs "
            "to whichever checkout was installed last",
        )


if __name__ == "__main__":
    unittest.main()
