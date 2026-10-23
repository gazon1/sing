"""Tests for check-gate-tooling-origin.py — the gate CLI must serve THIS checkout.

What this gate reads is the environment, not a committed file: the answer is
where the importable `sgate` package lives. So its control cannot be a
sabotage of the tree — no file in the repository decides that answer.

What it can be is a *synthesised* foreign install: a package on the path that
is not this checkout's, and the gate must say FOREIGN and fail. A gate that
cannot fail is a gate nobody knows can fail, and the way to know is to build the
state it exists to report and watch it catch it.
"""

import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "check-gate-tooling-origin.py"


class ForeignInstallTest(unittest.TestCase):
    """The state the gate exists to report: an install that serves another tree."""

    def _run_with_foreign_install(self) -> subprocess.CompletedProcess:
        with tempfile.TemporaryDirectory() as tmp:
            foreign = pathlib.Path(tmp) / "foreign" / "sgate"
            foreign.mkdir(parents=True)
            (foreign / "__init__.py").write_text("", encoding="utf-8")
            env = dict(os.environ)
            # PYTHONPATH entries are searched before site-packages, so the fake
            # package is the one `find_spec` finds.
            env["PYTHONPATH"] = str(foreign.parent)
            return subprocess.run(
                [sys.executable, str(SCRIPT), "--quiet"],
                capture_output=True,
                text=True,
                env=env,
                cwd=str(ROOT),
                check=False,
            )

    def test_a_foreign_install_is_reported(self):
        result = self._run_with_foreign_install()

        self.assertEqual(
            1,
            result.returncode,
            f"a foreign install must fail the gate: {result.stdout}{result.stderr}",
        )
        self.assertIn("FOREIGN", result.stdout + result.stderr)

    def test_this_checkout_is_reported_as_serving_this_checkout(self):
        """The other half: the state it must *not* report is the state it is in.

        Without it, a gate that always fails would pass this file — and the
        failure it is supposed to catch would be indistinguishable from the
        clean run.
        """
        env = dict(os.environ)
        env["PYTHONPATH"] = str(ROOT / "scripts" / "src")
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--quiet"],
            capture_output=True,
            text=True,
            env=env,
            cwd=str(ROOT),
            check=False,
        )

        self.assertEqual(
            0,
            result.returncode,
            f"this checkout's install must pass the gate: {result.stdout}{result.stderr}",
        )
        self.assertNotIn("FOREIGN", result.stdout + result.stderr)
