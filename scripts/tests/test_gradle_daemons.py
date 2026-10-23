"""Tests for `scripts/gradle-daemons.sh` — a table, not a dump.

`ps -eo pid,etime,args | grep GradleDaemon` prints ~40 KB of JVM command
lines (every `--add-opens`, every jar, every path), and `pgrep -f` is worse:
it matches the very shell command that does the grepping. The script reads
the daemon's OWN /proc/<pid>/cmdline instead and prints one short line per
daemon.

So the properties worth pinning down here are:

  - the script exits 0 (it is a diagnostic; a failure to diagnose must not
    look like a failure of the thing being diagnosed);
  - it prints the table header — the line that says how many daemons there
    are, or that there are none;
  - it does NOT dump the JVM command lines it read: no classpath, no
    `--add-opens`, no jar paths.

The script reads the real /proc, so it reports this machine's real daemons.
The test accepts either answer — a machine with daemons or a machine
without — because the point is the shape of the output, not the count.
"""

import pathlib
import shutil
import subprocess
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = REPO_ROOT / 'scripts' / 'gradle-daemons.sh'

#: Fragments of a JVM command line. Their presence means the script dumped
#: the raw cmdline instead of a one-line summary.
VERBOSE_FRAGMENTS = (
    'add-opens',
    '-cp ',
    'classpath',
    '.jar',
    'java.home',
    'Xmx',
)


class GradleDaemonsTest(unittest.TestCase):

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='gradle-daemons-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def _run(self) -> subprocess.CompletedProcess:
        return subprocess.run(
            ['bash', str(SCRIPT)],
            cwd=self.tmp,
            capture_output=True,
            text=True,
        )

    def test_it_exits_zero(self):
        result = self._run()
        self.assertEqual(
            0,
            result.returncode,
            f"a diagnostic that fails is a failure of the thing it diagnoses: "
            f"{result.stdout!r} {result.stderr!r}",
        )

    def test_it_prints_the_table_header(self):
        result = self._run()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(
            'total Gradle daemons:' in result.stdout
            or 'no Gradle daemons' in result.stdout,
            f"the table header is missing: {result.stdout!r}",
        )

    def test_it_does_not_dump_the_jvm_command_lines(self):
        result = self._run()
        self.assertEqual(0, result.returncode, result.stderr)
        for fragment in VERBOSE_FRAGMENTS:
            self.assertNotIn(
                fragment,
                result.stdout,
                f"the script dumped the raw command line ({fragment!r}): "
                f"{result.stdout!r}",
            )

    def test_every_daemon_line_is_one_short_line(self):
        """One daemon, one line: the table is meant to be read at a glance."""
        result = self._run()
        self.assertEqual(0, result.returncode, result.stderr)
        for line in result.stdout.splitlines():
            self.assertLess(
                len(line), 200,
                f"a daemon line is too long to be a summary: {line!r}",
            )


if __name__ == '__main__':
    unittest.main()
