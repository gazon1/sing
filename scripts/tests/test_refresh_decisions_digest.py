"""`refresh-decisions-digest.sh --quiet` was a flag nobody read (#518).

Both git hooks called it that way and the script never looked at `sys.argv` at all —
it had no argument parser to look with. The flag survived because a dead flag is
invisible: the call succeeds, the output looks suppressed, and the next person to add
a real option assumes there is a parser and writes around it.

These lock down that the script now refuses arguments it does not implement, with the
same exit code `singularity-adr` uses for a mistyped subcommand.
"""

import importlib.util
import pathlib
import unittest

MODULE_PATH = (pathlib.Path(__file__).resolve().parent.parent
               / 'refresh-decisions-digest.py')
spec = importlib.util.spec_from_file_location('refresh_decisions_digest', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class ArgvTest(unittest.TestCase):

    def _exit_code(self, args):
        try:
            mod.parse_argv(args)
        except SystemExit as exc:
            return exc.code or 0
        return None  # returned normally — no error

    def test_no_arguments_is_the_whole_interface(self):
        self.assertIsNone(self._exit_code([]))

    def test_the_dead_quiet_flag_is_now_a_usage_error(self):
        """The flag `.githooks/post-checkout` and `post-merge` used to pass."""
        self.assertEqual(self._exit_code(['--quiet']), 2)

    def test_apply_is_refused_too(self):
        """This script always writes; there is no report mode to opt out of."""
        self.assertEqual(self._exit_code(['--apply']), 2)

    def test_help_exits_zero(self):
        self.assertEqual(self._exit_code(['--help']), 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)