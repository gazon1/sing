"""Tests for maestro-flows.py — the opt-in Maestro UI flows.

The flows are the slowest thing in the repository and they need a device, so
they run only when they are asked for. The cases below are the ways that
"asked for" could go wrong:

  - without `RUN_MAESTRO=1` nothing runs at all — not a slow run, and not a
    failed one;
  - without the `maestro` CLI on PATH the step says so in a sentence, instead
    of failing with a traceback from a missing binary;
  - when the flows do run and fail, the step fails: a green device lane over a
    red suite is the one outcome nobody wants.
"""

import importlib.util
import os
import pathlib
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'maestro-flows.py'
spec = importlib.util.spec_from_file_location('maestro_flows', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class _Environment:
    """The environment the script reads, restored after every test."""

    def __init__(self, **values):
        self.values = values
        self._saved = {}

    def __enter__(self):
        for key, value in self.values.items():
            self._saved[key] = os.environ.get(key)
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        return self

    def __exit__(self, *exc):
        for key, value in self._saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        return False


class NotAskedForTest(unittest.TestCase):
    """Without `RUN_MAESTRO=1` the flows do not run."""

    def setUp(self):
        self._original = mod.run_maestro
        self._called = False

        def _called_maestro(*args, **kwargs):
            self._called = True
            return 0

        mod.run_maestro = _called_maestro

    def tearDown(self):
        mod.run_maestro = self._original

    def test_nothing_runs_without_the_flag(self):
        with _Environment(RUN_MAESTRO=None):
            self.assertEqual(0, mod.main([]))
        self.assertFalse(self._called)

    def test_the_flag_has_to_be_exactly_one(self):
        with _Environment(RUN_MAESTRO='0'):
            self.assertEqual(0, mod.main([]))
        self.assertFalse(self._called)


class MissingCliTest(unittest.TestCase):
    """Without the `maestro` CLI the step explains itself instead of crashing."""

    def setUp(self):
        self._original_which = mod.shutil.which
        self._original_maestro = mod.run_maestro
        self._called = False

        def _called_maestro(*args, **kwargs):
            self._called = True
            return 0

        mod.shutil.which = lambda name: None
        mod.run_maestro = _called_maestro

    def tearDown(self):
        mod.shutil.which = self._original_which
        mod.run_maestro = self._original_maestro

    def test_a_missing_cli_is_skipped_not_a_traceback(self):
        with _Environment(RUN_MAESTRO='1'):
            self.assertEqual(0, mod.main([]))
        self.assertFalse(self._called)


class FailingFlowsTest(unittest.TestCase):
    """When the flows run and fail, the step fails."""

    def setUp(self):
        self._original_which = mod.shutil.which
        self._original_maestro = mod.run_maestro
        mod.shutil.which = lambda name: '/usr/bin/maestro'
        mod.run_maestro = lambda serial, tags, skip_install: 1

    def tearDown(self):
        mod.shutil.which = self._original_which
        mod.run_maestro = self._original_maestro

    def test_a_failing_run_fails_the_step(self):
        with _Environment(RUN_MAESTRO='1'):
            self.assertEqual(1, mod.main([]))

    def test_the_environment_reaches_the_runner(self):
        seen = {}

        def _record(serial, tags, skip_install):
            seen['serial'] = serial
            seen['tags'] = tags
            seen['skip_install'] = skip_install
            return 0

        mod.run_maestro = _record
        with _Environment(RUN_MAESTRO='1', MAESTRO_TAGS='agenda', SKIP_INSTALL='1'):
            self.assertEqual(0, mod.main([]))
        self.assertEqual('agenda', seen['tags'])
        self.assertEqual('1', seen['skip_install'])


if __name__ == '__main__':
    unittest.main()
