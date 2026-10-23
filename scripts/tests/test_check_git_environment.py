"""Tests for check-git-environment.py — a git command that reads a tree must not inherit GIT_DIR.

Git exports `GIT_DIR` (and `GIT_WORK_TREE`, `GIT_INDEX_FILE`,
`GIT_COMMON_DIR`) to every hook it runs, and those variables override the
directory a git command was pointed at. A digest computed that way is a
digest of a tree nobody asked for, and the gate that reports it is green
about the wrong tree.

Measured on 2026-10-23, and it was not a theoretical damage: one run of the
test suite, with `GIT_DIR` exported, wrote four values into the shared
repository config — the SSH command, the hooks path, the user identity and
the remote URL.

These tests hold the gate to its own claims:

  - a script that reads another tree and does not neutralise the variables
    is a violation;
  - a script that neutralises them is not;
  - a script that reads its OWN checkout's root is not a violation — the
    variables name that tree too;
  - a rule that finds nothing proves nothing, so the gate fails when it does;
  - the real repository passes.
"""

import importlib.util
import pathlib
import tempfile
import textwrap
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / 'scripts' / 'check-git-environment.py'
spec = importlib.util.spec_from_file_location('check_git_environment', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


VIOLATING = """\
#!/usr/bin/env python3
import subprocess

def digest(repo):
    return subprocess.run(
        ["git", "ls-files"], cwd=repo, capture_output=True, text=True, check=False,
    )
"""

COMPLIANT = """\
#!/usr/bin/env python3
import os
import subprocess

GIT_VARS = ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR")

def tree_env():
    env = dict(os.environ)
    for name in GIT_VARS:
        env.pop(name, None)
    return env

def digest(repo):
    return subprocess.run(
        ["git", "ls-files"], cwd=repo, capture_output=True, text=True, check=False,
        env=tree_env(),
    )
"""

OWN_TREE = """\
#!/usr/bin/env python3
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent

def listing():
    return subprocess.run(
        ["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=False,
    )
"""


class GitEnvironmentRuleTest(unittest.TestCase):
    """The rule, on trees built for it."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='check-git-environment-'))
        self.addCleanup(self._remove)
        (self.tmp / 'scripts').mkdir()

    def _remove(self) -> None:
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write(self, name: str, source: str) -> pathlib.Path:
        path = self.tmp / 'scripts' / name
        path.write_text(textwrap.dedent(source), encoding='utf-8')
        return path

    def test_a_script_that_reads_another_tree_without_neutralising_is_a_violation(self):
        self._write('reads.py', VIOLATING)
        violations, sensitive = mod.scan(self.tmp)
        self.assertEqual(1, len(violations), violations)
        self.assertIn('reads.py', violations[0])
        self.assertTrue(sensitive)

    def test_a_script_that_neutralises_the_variables_is_not_a_violation(self):
        self._write('reads.py', COMPLIANT)
        violations, sensitive = mod.scan(self.tmp)
        self.assertEqual([], violations)
        self.assertTrue(sensitive, 'the site is still a site — it just controls its environment')

    def test_a_script_that_reads_its_own_checkout_is_not_a_violation(self):
        self._write('own.py', OWN_TREE)
        violations, _sensitive = mod.scan(self.tmp)
        self.assertEqual([], violations)

    def test_a_tree_with_nothing_to_read_is_reported_as_proving_nothing(self):
        """The gate fails when the rule finds nothing — a rule that finds nothing proves nothing."""
        self._write('quiet.py', "print('nothing to read')\n")
        violations, sensitive = mod.scan(self.tmp)
        self.assertEqual([], violations)
        self.assertLess(len(sensitive), mod.MIN_SITES)


class RealRepositoryTest(unittest.TestCase):
    """The real repository passes the rule it declares."""

    def test_the_real_repository_passes(self):
        violations, sensitive = mod.scan(ROOT)
        self.assertEqual([], violations)
        self.assertGreaterEqual(len(sensitive), mod.MIN_SITES)


if __name__ == '__main__':
    unittest.main()
