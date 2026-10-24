"""Tests for check-git-environment.py — a git command that reads a tree must not inherit
GIT_DIR, and a git config write must land in a file of its own choosing.

Git exports `GIT_DIR` (and `GIT_WORK_TREE`, `GIT_INDEX_FILE`,
`GIT_COMMON_DIR`) to every hook it runs, and those variables override the
directory a git command was pointed at. A digest computed that way is a
digest of a tree nobody asked for, and the gate that reports it is green
about the wrong tree.

Measured on 2026-10-23, and it was not a theoretical damage: one run of the
test suite, with `GIT_DIR` exported, wrote four values into the shared
repository config — the SSH command, the hooks path, the user identity and
the remote URL.

The second rule is the same damage seen from the other side: a plain
`git config <key> <value>` always lands in the SHARED config — the common
directory's `config` — whichever worktree ran it. This repository is one
clone with ~20 worktrees, so one such write changes all of them.

These tests hold the gate to its own claims:

  - a script that reads another tree and does not neutralise the variables
    is a violation;
  - a script that neutralises them is not — and so is a script that calls the
    shared helper, which is where the answer now lives;
  - a script that reads its OWN checkout's root is not a violation — the
    variables name that tree too;
  - a rule that finds nothing proves nothing, so the gate fails when it does;
  - a script that writes a git config value with `--file` or `--worktree` is
    not a violation, and one that writes it plainly is — naming the script,
    the line and the key;
  - a script that only READS a git config (`--get`) is not a violation, and
    that is the case that must not regress;
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
import subprocess

from sgate.gatekit.gitenv import tree_env

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

CONFIG_WITH_FILE = """\
#!/usr/bin/env python3
import subprocess

def set_value(path, key, value):
    return subprocess.run(
        ["git", "config", "--file", path, key, value], check=False,
    )
"""

CONFIG_WITH_WORKTREE = """\
#!/usr/bin/env python3
import subprocess

def set_value(key, value):
    return subprocess.run(
        ["git", "config", "--worktree", key, value], check=False,
    )
"""

CONFIG_PLAIN = """\
#!/usr/bin/env python3
import subprocess

def set_value(key, value):
    return subprocess.run(
        ["git", "config", key, value], check=False,
    )
"""

CONFIG_READ = """\
#!/usr/bin/env python3
import subprocess

def read_value(key):
    return subprocess.run(
        ["git", "config", "--get", key], check=False,
    )
"""

CONFIG_MARKED = """\
#!/usr/bin/env python3
import subprocess

def set_value(key, value):
    # git-config-shared: the throwaway clone has no worktree to damage
    return subprocess.run(
        ["git", "config", key, value], check=False,
    )
"""

SHELL_WITH_FILE = """\
#!/usr/bin/env bash
git config --file "$1" "$2" "$3"
"""

SHELL_WITH_WORKTREE = """\
#!/usr/bin/env bash
git config --worktree "$1" "$2"
"""

SHELL_PLAIN = """\
#!/usr/bin/env bash
git config "$1" "$2"
"""

SHELL_READ = """\
#!/usr/bin/env bash
git config --get "$1"
"""

SHELL_MARKED = """\
#!/usr/bin/env bash
# git-config-shared: the CI clone has no worktree to damage
git config user.email "bot@example.com"
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

    def test_a_script_that_calls_the_shared_helper_is_not_a_violation(self):
        """The helper is the one place the answer is written down."""
        self._write('reads.py', COMPLIANT)
        violations, _sensitive = mod.scan(self.tmp)
        self.assertEqual([], violations)

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


class SharedConfigWriteRuleTest(unittest.TestCase):
    """The second rule: a config write must land in a file of its own choosing.

    Every fixture is a throwaway file in a temporary directory. Nothing here
    runs git, and nothing here writes into any real git config.
    """

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='check-git-config-'))
        self.addCleanup(self._remove)
        (self.tmp / 'scripts').mkdir()

    def _remove(self) -> None:
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write(self, name: str, source: str) -> pathlib.Path:
        path = self.tmp / 'scripts' / name
        path.write_text(textwrap.dedent(source), encoding='utf-8')
        return path

    def _violations(self) -> list[str]:
        violations, _sites = mod.scan_config_writes(self.tmp)
        return violations

    def test_a_write_with_file_names_its_destination(self):
        """`--file <path>` writes exactly that file — the shared config is unreachable."""
        self._write('writes.py', CONFIG_WITH_FILE)
        self.assertEqual([], self._violations())

    def test_a_write_with_worktree_names_its_destination(self):
        """`--worktree` writes this worktree's own config."""
        self._write('writes.py', CONFIG_WITH_WORKTREE)
        self.assertEqual([], self._violations())

    def test_a_plain_write_is_a_violation_and_names_the_script_the_line_and_the_key(self):
        self._write('writes.py', CONFIG_PLAIN)
        violations = self._violations()
        self.assertEqual(1, len(violations), violations)
        message = violations[0]
        self.assertIn('scripts/writes.py', message)
        self.assertIn(':5:', message)  # the line the call sits on
        self.assertIn('key', message)
        self.assertIn('SHARED', message)

    def test_a_read_is_not_a_violation(self):
        """`git config --get key` reads. It must stay exactly as it was."""
        self._write('reads.py', CONFIG_READ)
        self.assertEqual([], self._violations())

    def test_a_shell_write_with_file_names_its_destination(self):
        self._write('writes.sh', SHELL_WITH_FILE)
        self.assertEqual([], self._violations())

    def test_a_shell_write_with_worktree_names_its_destination(self):
        self._write('writes.sh', SHELL_WITH_WORKTREE)
        self.assertEqual([], self._violations())

    def test_a_plain_shell_write_is_a_violation(self):
        self._write('writes.sh', SHELL_PLAIN)
        violations = self._violations()
        self.assertEqual(1, len(violations), violations)
        self.assertIn('scripts/writes.sh', violations[0])
        self.assertIn(':2:', violations[0])

    def test_a_shell_read_is_not_a_violation(self):
        self._write('reads.sh', SHELL_READ)
        self.assertEqual([], self._violations())

    def test_a_marker_comment_declares_a_shared_write_on_purpose(self):
        """The marker is the declaration, in the script that does the write."""
        self._write('writes.sh', SHELL_MARKED)
        self.assertEqual([], self._violations())

    def test_a_marker_comment_above_a_python_call_declares_it_too(self):
        self._write('writes.py', CONFIG_MARKED)
        self.assertEqual([], self._violations())

    def test_the_only_escape_is_the_marker_next_to_the_write(self):
        """A shared write with no marker is reported, whatever else says so.

        The rule used to have a second escape — a registry of "scripts that
        write the shared config on purpose" inside the gate file. That list is
        somebody else's writes kept in a file those writers do not touch, so it
        drifts from the scripts it describes; the marker lives next to the
        write and cannot.
        """
        self.assertFalse(hasattr(mod, 'SHARED_CONFIG_WRITES'),
                         'the registry escape is gone: one escape, one place')
        self._write('writes.sh', SHELL_PLAIN)
        violations = self._violations()
        self.assertEqual(1, len(violations), violations)

    def test_the_gate_fails_on_a_shared_config_write(self):
        """The violation reaches the gate's own report, not only the scanner."""
        self._write('writes.py', CONFIG_PLAIN)
        violations, _sensitive = mod.scan(self.tmp)
        self.assertTrue(any('SHARED' in violation for violation in violations), violations)

    def test_a_config_read_is_not_a_tree_read_violation_either(self):
        """The first rule must not start reporting `git config --get`."""
        self._write('reads.py', CONFIG_READ)
        violations, _sensitive = mod.scan_tree_reads(self.tmp)
        self.assertEqual([], violations)


class RealRepositoryTest(unittest.TestCase):
    """The real repository passes the rules it declares."""

    def test_the_real_repository_passes(self):
        violations, sensitive = mod.scan(ROOT)
        self.assertEqual([], violations)
        self.assertGreaterEqual(len(sensitive), mod.MIN_SITES)

    def test_the_real_repository_writes_no_shared_config_by_accident(self):
        """Every shared-config write in this repository is declared."""
        violations, sites = mod.scan_config_writes(ROOT)
        self.assertEqual([], violations)
        self.assertTrue(sites, 'the rule finds the writes, and each one names its destination')


if __name__ == '__main__':
    unittest.main()
