"""Tests for check-gate-liveness.py — the gate that catches gates (#529).

The premise: a gate that dies is indistinguishable from a gate that passes for every
consumer that reads only the exit code. So this one runs the other ones and looks at
how they end rather than whether they end green.
"""

import importlib.util
import pathlib
import unittest

MODULE_PATH = (pathlib.Path(__file__).resolve().parent.parent
               / 'check-gate-liveness.py')
spec = importlib.util.spec_from_file_location('check_gate_liveness', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class SlowIsNotDeadTest(unittest.TestCase):
    """A timeout and a crash are different failures.

    The first version of `probe()` returned `dead` for a timeout. In CI that turned
    `declared dependencies are used` — which reads `/tmp/_resolved.tsv` and otherwise
    runs `:shared:printResolvedArtifacts` — into a red job: the file existed locally
    (1.9 s) and did not on a GitHub runner with a cold daemon. The gate was healthy
    and the verdict was wrong.

    These two tests are the pair that matters: a gate that merely takes too long must
    be reported, and a gate that crashes must still be caught. Keeping them apart is
    the fix; blurring them again is the regression.
    """

    def test_a_gate_that_outlives_its_budget_is_slow_not_dead(self):
        verdict, detail = mod.probe(
            'sleepy', 'python3 -c "import time; time.sleep(30)"', budget=2,
        )
        self.assertEqual(verdict, 'slow')
        self.assertIn('2s', detail)

    def test_a_crash_is_still_dead(self):
        verdict, detail = mod.probe(
            'crashy', 'python3 -c "raise AttributeError(\'boom\')"',
        )
        self.assertEqual(verdict, 'dead')
        # The reported detail is the first traceback marker, not the exception name:
        # `TRACEBACK_MARKERS` is ordered with the header first on purpose, since a
        # traceback header is the common case and the name is decoration.
        self.assertIn('Traceback', detail)


class SelfProbeTest(unittest.TestCase):
    """The liveness checker must not probe itself, and must say so rather than guess."""

    def test_it_excludes_its_own_file_from_the_target_list(self):
        gates = [('blocking', 'gates are alive',
                  f'python3 {mod.SELF}'),
                 ('blocking', 'something else', 'python3 scripts/check-other.py')]
        targets = [g for g in gates if mod.SELF not in g[2]]
        self.assertEqual([g[1] for g in targets], ['something else'])


class StdinTest(unittest.TestCase):
    """A gate that reads stdin hangs forever on a runner whose stdin never closes.

    `subprocess.run` inherits the parent's stdin unless told otherwise. On a developer
    machine that is a terminal or `/dev/null`; on a GitHub runner it is a pipe with no
    writer, and `sys.stdin.read()` blocks until the probe's timeout — which would then
    be reported as a dead gate, blaming the wrong script. Probes pass `DEVNULL`, so a
    read returns EOF immediately and the command finishes on its own merits.
    """

    def test_a_command_that_reads_stdin_completes_instead_of_hanging(self):
        verdict, _ = mod.probe(
            'reader', 'python3 -c "import sys; sys.stdin.read()"', budget=5,
        )
        self.assertEqual(verdict, 'ok')


if __name__ == '__main__':
    unittest.main(verbosity=2)