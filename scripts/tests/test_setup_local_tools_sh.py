"""Tests for `scripts/ci/setup-local-tools.sh` — the gate tooling comes from mise.

The script does not install the external binaries: mise does, from the pins in
`mise.toml`. What the script does is make them reachable from the checkout's
own venv — one PATH that serves both the gate suite (`sgate`) and the tools
the gates shell out to (`actionlint`, `shellcheck`, `yamllint`, `lychee`,
`ktlint`, `rumdl`).

Two properties matter, and both are about the venv rather than the tools:

* a missing tool is a loud failure, not a skip — a gate that cannot run looks
  exactly like a gate that has nothing to report;
* the venv's copy is a *link* to the tool mise installed, not a copy of it.
  The binaries are large (`lychee` alone is 303 MB) and identical in every
  checkout, so a copy per checkout is a machine full of the same bytes.

The fixture is a throwaway layout, not a mock: the real script, a real
`HOME`, and stand-ins for `uv` and for the six binaries mise would have put on
`PATH`.
"""

import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = REPO_ROOT / 'scripts' / 'ci' / 'setup-local-tools.sh'

#: The binaries the script expects on PATH. mise installs them; the script
#: only checks that they are there and links them into the venv.
TOOLS = ('actionlint', 'shellcheck', 'yamllint', 'lychee', 'ktlint', 'rumdl')

#: The PATH the script runs under: the stubs, and the system directories the
#: shell needs (`#!/usr/bin/env bash`, `env` itself). The developer's own PATH
#: is deliberately not inherited — mise's shims live there, and a test that
#: finds the real `lychee` cannot see what the script would see on a machine
#: that has not installed it.
SYSTEM_PATH = '/usr/bin:/bin'

#: The stand-in for uv. It does what the script needs of it and nothing more:
#: make the venv (with a bin/ inside it) and accept the editable install.
STUB_UV = """#!/usr/bin/env bash
set -euo pipefail
command="${1:-}"
shift || true
case "$command" in
  venv)
    mkdir -p "${@: -1}/bin"
    ;;
  pip)
    :
    ;;
esac
"""


class SetupLocalToolsTest(unittest.TestCase):
    """The venv links the tools mise installed, and says so when one is missing."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='setup-local-tools-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.home = self.tmp / 'home'
        self.home.mkdir()
        self.stubs = self.tmp / 'stubs'
        self.stubs.mkdir()
        self._write_stub('uv', STUB_UV)
        for tool in TOOLS:
            self._write_stub(tool, f'#!/usr/bin/env bash\necho "{tool} from mise"\n')

    def _write_stub(self, name: str, body: str) -> None:
        stub = self.stubs / name
        stub.write_text(body, encoding='utf-8')
        stub.chmod(0o755)

    def _run(self, *, without: str | None = None) -> subprocess.CompletedProcess:
        if without is None:
            path = f"{self.stubs}:{SYSTEM_PATH}"
        else:
            # A PATH without one of the tools: the file is still there, the
            # shell just cannot find it. The other tools and uv stay
            # reachable, so the failure is about the missing tool and nothing
            # else.
            hidden = self.tmp / 'hidden'
            hidden.mkdir(exist_ok=True)
            (hidden / 'uv').symlink_to(self.stubs / 'uv')
            for tool in TOOLS:
                if tool != without:
                    (hidden / tool).symlink_to(self.stubs / tool)
            path = f"{hidden}:{SYSTEM_PATH}"
        env = dict(os.environ)
        env['HOME'] = str(self.home)
        env['PATH'] = path
        return subprocess.run(
            ['bash', str(SCRIPT)],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            env=env,
            check=False,
        )

    def _venv_bin(self, tool: str) -> pathlib.Path:
        venv = self.home / '.venvs' / f'singularity-todo-{REPO_ROOT.name}'
        return venv / 'bin' / tool

    def test_a_missing_tool_is_a_loud_failure(self):
        """A gate that cannot run must not look like a gate with nothing to report."""
        result = self._run(without='lychee')
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn('lychee', result.stderr)
        self.assertIn('mise install', result.stderr)

    def test_the_venvs_tools_are_links_to_the_ones_mise_installed(self):
        """The binaries are shared across checkouts; a copy per checkout is not."""
        result = self._run()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

        for tool in TOOLS:
            with self.subTest(tool=tool):
                link = self._venv_bin(tool)
                self.assertTrue(link.is_symlink(), f'{tool} was copied, not linked')
                self.assertEqual(
                    os.path.realpath(link),
                    os.path.realpath(self.stubs / tool),
                    f'{tool} in the venv is not the tool on PATH',
                )

    def test_the_linked_tool_is_the_one_that_runs(self):
        """The link is not decoration: the tool behind it is the shared copy."""
        self._run()
        link = self._venv_bin('lychee')
        ran = subprocess.run(
            [str(link)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, ran.returncode, ran.stdout + ran.stderr)
        self.assertEqual('lychee from mise', ran.stdout.strip())


if __name__ == '__main__':
    unittest.main()
