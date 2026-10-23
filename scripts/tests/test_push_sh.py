"""Tests for `scripts/push.sh` — a push that keeps git's exit code.

The script exists because the obvious one-liner lies:

    git push "$@" 2>&1 | tail -40

In a pipeline the exit code is the exit code of the LAST command — the
reader's — so the wrapper reports success even when git failed. And the
`git push ... | tail` variant closes the pipe early, git dies of SIGPIPE, and
141 (SIGPIPE) becomes indistinguishable from 141 (a lost SSH connection).

So the script sends git's output to a FILE, captures the exit code
immediately, prints the tail of the file and exits with git's own code.

What is asserted here:

  - a successful push exits 0, says so, and leaves a log file whose path it
    printed — and that log holds git's own output;
  - a failed push exits NON-zero, and with the SAME code git itself returns,
    so a caller can tell "the remote refused the push" from "the connection
    died" from "git could not authenticate".

Everything happens in a temporary directory against a local bare remote: no
network, no ssh, and nothing of the developer's own repository is touched.
The log file the script leaves in /tmp is cleaned up by the test that made it.
"""

import pathlib
import shutil
import subprocess
import tempfile
import unittest
from scripts.tests._git_env import GitEnvTestCase

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = REPO_ROOT / 'scripts' / 'push.sh'


class PushShTest(GitEnvTestCase):
    """The wrapper must not lie about the push."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='push-sh-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)

        self.remote = self.tmp / 'remote.git'
        self.remote.mkdir()
        subprocess.run(
            ['git', 'init', '-q', '--bare', '-b', 'main', str(self.remote)],
            check=True,
        )

        self.clone = self.tmp / 'clone'
        self.clone.mkdir()
        subprocess.run(
            ['git', 'init', '-q', '-b', 'main', str(self.clone)],
            check=True,
        )
        # Local config only — the developer's global git config is not read
        # for identity here, and nothing global is written.
        for key, value in (
            ('user.email', 'push-sh@example.invalid'),
            ('user.name', 'push sh test'),
            ('commit.gpgsign', 'false'),
        ):
            subprocess.run(
                ['git', 'config', '--local', key, value],
                cwd=self.clone,
                check=True,
            )
        subprocess.run(
            ['git', 'remote', 'add', 'origin', str(self.remote)],
            cwd=self.clone,
            check=True,
        )
        (self.clone / 'file.txt').write_text('hello\n', encoding='utf-8')
        subprocess.run(['git', 'add', 'file.txt'], cwd=self.clone, check=True)
        subprocess.run(
            ['git', 'commit', '-q', '-m', 'initial'],
            cwd=self.clone,
            check=True,
        )

    def _run(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ['bash', str(SCRIPT), *args],
            cwd=self.clone,
            capture_output=True,
            text=True,
        )

    def _log_path(self, result: subprocess.CompletedProcess) -> pathlib.Path:
        """The log file the script printed the path of."""
        paths = [
            line.split(':', 1)[1].strip()
            for line in result.stdout.splitlines()
            if line.startswith('push log:')
        ]
        self.assertEqual(
            1, len(paths),
            f"the script did not print exactly one log path: {result.stdout!r}",
        )
        path = pathlib.Path(paths[0])
        # The script owns this file's lifetime only for the duration of the
        # run; the test that produced it removes it.
        self.addCleanup(path.unlink, True)
        return path

    def test_a_successful_push_exits_zero_and_says_so(self):
        result = self._run('origin', 'main')
        self.assertEqual(
            0, result.returncode,
            f"a successful push must exit 0: {result.stdout!r} {result.stderr!r}",
        )
        self.assertIn('git push exit code: 0', result.stdout)

    def test_the_log_file_it_printed_exists_and_holds_git_output(self):
        result = self._run('origin', 'main')
        self.assertEqual(0, result.returncode, result.stdout)
        log = self._log_path(result)
        self.assertTrue(log.exists(), f"the printed log file does not exist: {log}")
        text = log.read_text(encoding='utf-8', errors='replace')
        self.assertTrue(
            text.strip(),
            "the log file is empty: git's output was not captured",
        )
        # The pushed branch is named in git's own output.
        self.assertIn('main', text)

    def test_a_failed_push_exits_non_zero_with_git_own_code(self):
        """The wrapper's code must be git's code, not the reader's."""
        expected = subprocess.run(
            ['git', 'push', 'no-such-remote', 'main'],
            cwd=self.clone,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(
            0, expected.returncode,
            "git unexpectedly succeeded against a remote that does not exist",
        )
        result = self._run('no-such-remote', 'main')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(
            expected.returncode,
            result.returncode,
            f"the wrapper lost git's exit code: {result.stdout!r}",
        )
        self.assertIn(f'git push exit code: {expected.returncode}', result.stdout)

    def test_a_failed_push_still_writes_its_log(self):
        result = self._run('no-such-remote', 'main')
        self.assertNotEqual(0, result.returncode)
        log = self._log_path(result)
        self.assertTrue(log.exists(), f"the printed log file does not exist: {log}")
        self.assertTrue(
            log.read_text(encoding='utf-8', errors='replace').strip(),
            "the log of a failed push is empty: git's complaint was lost",
        )


if __name__ == '__main__':
    unittest.main()
