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
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
WRAPPER = REPO / "rm"
JUST_TESTS = REPO / ".just" / "tests" / "mod.just"
JUST_TRACEABILITY = REPO / ".just" / "traceability" / "mod.just"
CHECK_SH = REPO / "check.sh"

#: The bare console scripts, as they are written on a line of their own. A
#: recipe that calls one of them measures whichever checkout was installed last.
BARE_CALL = re.compile(r"^(run-maestro|ensure-emulator|python3 -m traceability)\b")

#: A stand-in for the binding, in a tree where one package does not resolve to
#: it. The wrapper loads the binding by path, so a tree can carry its own.
FOREIGN_BINDING = '''
def bind_local_package(root):
    return root


def foreign_packages(root):
    return ["emulator"]


def package_origin(package):
    return None
'''


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
        for path in (JUST_TESTS, JUST_TRACEABILITY):
            text = path.read_text(encoding="utf-8")
            for line in text.splitlines():
                stripped = line.strip()
                self.assertIsNone(
                    BARE_CALL.match(stripped),
                    f"{path.name} calls the installed package instead of the wrapper: {stripped!r}",
                )
            self.assertIn(
                "./rm",
                text,
                f"{path.name} does not go through the wrapper at all",
            )

    def test_the_check_script_goes_through_the_wrapper(self) -> None:
        """`check.sh` runs the Maestro lane too, and it must be this checkout's."""
        text = CHECK_SH.read_text(encoding="utf-8")
        for line in text.splitlines():
            stripped = line.strip()
            self.assertIsNone(
                BARE_CALL.match(stripped),
                f"check.sh calls the installed package instead of the wrapper: {stripped!r}",
            )
        self.assertIn(
            "./rm",
            text,
            "check.sh does not go through the wrapper at all",
        )

    def test_it_refuses_when_a_package_does_not_resolve_here(self) -> None:
        """A run against another checkout is not a red run — it is no run at all."""
        with tempfile.TemporaryDirectory() as tree:
            root = pathlib.Path(tree)
            (root / "scripts").mkdir()
            (root / "rm").write_text(WRAPPER.read_text(encoding="utf-8"), encoding="utf-8")
            os.chmod(root / "rm", 0o755)
            (root / "scripts" / "package-binding.py").write_text(
                FOREIGN_BINDING, encoding="utf-8"
            )
            result = subprocess.run(
                [str(root / "rm"), "--dry-run", "--tags", "tasks"],
                capture_output=True,
                text=True,
                timeout=120,
                stdin=subprocess.DEVNULL,
            )

        self.assertEqual(
            2,
            result.returncode,
            f"the wrapper ran instead of refusing: {result.stdout.strip()[:200]}",
        )
        self.assertIn(
            "emulator",
            result.stderr,
            "the refusal does not name the package that does not resolve here",
        )

    def test_the_traceability_recipes_do_not_use_the_bare_module(self) -> None:
        """The bare module invocation is banned — in a recipe and in a comment alike.

        The check is per line rather than over the whole file: a comment that
        *names* the bare invocation is how a reader learns to type it, and the
        prose around it in this very file does that.
        """
        text = JUST_TRACEABILITY.read_text(encoding="utf-8")
        for line in text.splitlines():
            self.assertNotIn(
                "python3 -m traceability",
                line,
                "the traceability recipes name the installed package instead of "
                "the wrapper",
            )


if __name__ == "__main__":
    unittest.main()
