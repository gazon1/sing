"""The cluster wrappers' `--only` contract.

A cluster gate runs several checks and reports every one that failed. `--only`
runs a single member, and it exists so a control can drive one member on its
own — the control that proves a check can fail needs to fail *that* check, not
the aggregate.

Which makes the failure mode important: a typo in a member's name must not
select nothing and report success. "Ran zero checks, all of them passed" is the
answer a wrapper gives when it is asked for a member it does not have, and it
is indistinguishable from a wrapper whose members are all green.

So the contract is: an unknown member is an error, and a known member runs
exactly one check.
"""
from __future__ import annotations

import pathlib
import subprocess
import sys
import unittest

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
WRAPPERS = [
    ("scripts/check-ci-workflow-shape.py", "test_task_external_inputs_declared"),
    ("scripts/check-detekt-governance.py", "detekt_rules_config_is_current"),
]


class OnlyMemberTest(unittest.TestCase):
    def _run(self, script: str, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, script, *args],
            capture_output=True,
            text=True,
            cwd=REPO,
            timeout=300,
        )

    def test_an_unknown_member_is_an_error(self):
        """A name the wrapper does not have must fail, not select nothing."""
        for script, _known in WRAPPERS:
            with self.subTest(script=script):
                result = self._run(script, "--only", "no-such-member")
                self.assertNotEqual(
                    result.returncode, 0,
                    "an unknown member must fail: a wrapper that selects nothing "
                    "reports success, which is what a typo in a control would look "
                    "like",
                )

    def test_a_known_member_runs_exactly_one_check(self):
        """The member's own check runs, and the others do not."""
        for script, member in WRAPPERS:
            with self.subTest(script=script):
                result = self._run(script, "--only", member)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("1 check(s)", result.stdout)
                self.assertNotIn("2 check(s)", result.stdout)
                self.assertNotIn("6 check(s)", result.stdout)

    def test_without_the_flag_every_member_runs(self):
        """The default is the whole cluster — the gate CI dispatches."""
        for script, _known in WRAPPERS:
            with self.subTest(script=script):
                result = self._run(script)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertNotIn("1 check(s)", result.stdout)


if __name__ == "__main__":
    unittest.main()
