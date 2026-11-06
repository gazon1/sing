"""Tests for `hk.pkl` — the git-event checks the project declares.

The file is generated from `mise.toml`, and it is the only place that says
which checks a commit runs. Two defects this file exists for:

  - the generator expanded `$PATH` into the committed file, so the config
    named one machine's tool directories and could not work on any other;
  - the config claimed an isolation it did not enable — `stash = "none"` next
    to documentation that said `stash = "git"` — so a commit was checked
    against the working tree again, which is the exact regression the ADR of
    2026-10-24 fixed.

The text tests are the cheap half. The second class runs the real `hk`: a Pkl
error passes every string test and then blocks every commit, and `hk` answers
that question in milliseconds. `hk` carries its pinned version's config package
inside the binary, so the run needs no network.
"""

import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / 'scripts' / 'src'))

from sgate.gatekit.gitenv import sandbox_env  # noqa: E402

HK_PKL = REPO_ROOT / 'hk.pkl'
GENERATOR = REPO_ROOT / 'scripts' / 'generate-hk-pkl.sh'
MISE = REPO_ROOT / 'mise.toml'
HK = shutil.which('hk')

#: The stand-in for `heavy`: the hook runs its commands through the machine-wide
#: semaphore when it is installed, and a test should not queue behind a build.
FAKE_HEAVY = """#!/usr/bin/env bash
while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do shift; done
[ "$#" -gt 0 ] && shift
exec "$@"
"""


def block(text: str, key: str) -> str:
    """The `{ ... }` block that follows `key` in a Pkl file."""
    start = text.index(key) + len(key)
    depth = 0
    for index in range(start, len(text)):
        char = text[index]
        if char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
    raise AssertionError(f'the block after {key!r} is not closed')


class HkConfigTest(unittest.TestCase):
    """What the committed config declares."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.text = HK_PKL.read_text(encoding='utf-8')
        cls.pre_commit = block(cls.text, '["pre-commit"]')

    def test_the_ktlint_step_reads_the_files_hk_selects(self):
        """hk selects the staged Kotlin files, and ktlint gets exactly those.

        `{{files}}` is hk's template, and it passes each path as one argument —
        the shell never splits a name with a space in it. A step without it
        would lint the whole tree on every commit.
        """
        step = block(self.pre_commit, '["ktlint"]')

        self.assertIn('"**/*.kt"', step)
        self.assertIn('"**/*.kts"', step)
        self.assertIn('scripts/ktlint-lint.py', step)
        self.assertIn('{{files}}', step)

    def test_the_commit_is_checked_on_its_own_tree(self):
        """Unstaged work is held aside while the steps run."""
        self.assertIn('stash = "git"', self.pre_commit)
        self.assertIn('fix = false', self.pre_commit)

    def test_the_compile_follows_the_lint(self):
        step = block(self.pre_commit, '["compile-changed"]')

        self.assertIn('depends = "ktlint"', step)
        self.assertIn('scripts/compile-changed.py', step)

    def test_the_config_names_no_absolute_path_and_no_path_export(self):
        """The config is committed; a path from one machine is a bug for the rest."""
        self.assertNotIn('/home/', self.text)
        self.assertNotIn('export PATH=', self.text)

    def test_hk_pkl_is_what_the_generator_writes(self):
        """A hand-edited config is drift; the generator is the copy that counts."""
        with tempfile.TemporaryDirectory(prefix='hk-pkl-') as tmp:
            root = pathlib.Path(tmp) / 'repo'
            root.mkdir()
            subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=root, check=True, env=sandbox_env(root))
            shutil.copy(MISE, root / 'mise.toml')
            shutil.copy(HK_PKL, root / 'hk.pkl')
            (root / 'scripts').mkdir()
            shutil.copy(GENERATOR, root / 'scripts' / 'generate-hk-pkl.sh')
            subprocess.run(
                ['bash', str(root / 'scripts' / 'generate-hk-pkl.sh')],
                cwd=root,
                check=True,
                capture_output=True,
                env=sandbox_env(root),
            )
            self.assertEqual(self.text, (root / 'hk.pkl').read_text(encoding='utf-8'))


@unittest.skipUnless(HK, 'hk is not installed (mise install)')
class HkRunsTheConfigTest(unittest.TestCase):
    """The real hk evaluates the config, and the declared steps run."""

    def test_the_config_is_valid_pkl(self):
        result = subprocess.run(['hk', 'validate'], cwd=REPO_ROOT, capture_output=True, text=True)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_the_declared_steps_run_on_a_staged_kotlin_file(self):
        """Both steps execute, and ktlint receives the file hk selected."""
        with tempfile.TemporaryDirectory(prefix='hk-run-') as tmp:
            root = pathlib.Path(tmp) / 'repo'
            root.mkdir()
            bin_dir = pathlib.Path(tmp) / 'bin'
            bin_dir.mkdir()
            ktlint_called = pathlib.Path(tmp) / 'ktlint-called.txt'
            compile_called = pathlib.Path(tmp) / 'compile-called.txt'

            subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=root, check=True, env=sandbox_env(root))
            shutil.copy(MISE, root / 'mise.toml')
            shutil.copy(HK_PKL, root / 'hk.pkl')
            (bin_dir / 'heavy').write_text(FAKE_HEAVY, encoding='utf-8')
            (bin_dir / 'heavy').chmod(0o755)
            self._stub(root / 'scripts' / 'ktlint-lint.py', ktlint_called)
            self._stub(root / 'scripts' / 'compile-changed.py', compile_called)
            (root / 'src').mkdir()
            (root / 'src' / 'Foo.kt').write_text('fun foo() {}\n', encoding='utf-8')
            subprocess.run(['git', 'add', '-A'], cwd=root, check=True, env=sandbox_env(root))

            env = sandbox_env(root)
            env['PATH'] = f'{bin_dir}{os.pathsep}{env["PATH"]}'
            result = subprocess.run(['hk', 'run', 'pre-commit'], cwd=root, capture_output=True, text=True, env=env)

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn('src/Foo.kt', ktlint_called.read_text(encoding='utf-8'))
            self.assertTrue(compile_called.is_file(), 'the compile step did not run')

    @staticmethod
    def _stub(path: pathlib.Path, marker: pathlib.Path) -> None:
        """A script the hook can call: it writes down its arguments and passes."""
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            '#!/usr/bin/env python3\n'
            'import pathlib, sys\n'
            f'pathlib.Path({str(marker)!r}).write_text(" ".join(sys.argv[1:]))\n',
            encoding='utf-8',
        )
        path.chmod(0o755)


if __name__ == '__main__':
    unittest.main()
