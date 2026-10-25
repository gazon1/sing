"""Tests for `scripts/ci/setup-local-tools.sh` — the shared store of pinned binaries.

The pinned binaries are the same bytes in every checkout, and they are the
expensive part: `lychee` alone is 303 MB, and one copy per checkout was 8.9 GB
of identical binaries on one machine (measured: thirty venvs). So they live in
one store, keyed by version, and each venv's copy is a symlink into it.

What matters here is the property, not the layout: two checkouts must end up
with the SAME file behind `bin/lychee`, and a different pinned version must get
a copy of its own. A copy per checkout would satisfy the first and miss the
point; a shared copy that ignored the version would satisfy the first and break
the second — the pins are what make the sharing safe.

The fixture is a throwaway layout, not a mock: the real script, a real pins
file, and a stand-in for the package manager (it creates the file the wheel
would ship, in the venv it is told about) and for the release download.
"""

import io
import os
import pathlib
import shutil
import subprocess
import tarfile
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = REPO_ROOT / 'scripts' / 'ci' / 'setup-local-tools.sh'
PINS_FILE = REPO_ROOT / 'config' / 'toolchain-versions.env'

#: The stand-in for uv. It does what the script needs of it and nothing more:
#: make the venv, and put the binary a wheel ships into the venv it is told
#: about (`VIRTUAL_ENV` is how uv is told, and how the script tells it).
STUB_UV = """#!/usr/bin/env bash
set -euo pipefail
command="${1:-}"
shift || true
case "$command" in
  venv)
    mkdir -p "${@: -1}/bin"
    ;;
  pip)
    for argument in "$@"; do
      case "$argument" in
        *lychee-bin==*) name=lychee ;;
        *shellcheck-py==*) name=shellcheck ;;
        *) continue ;;
      esac
      printf '#!/usr/bin/env bash\\necho "%s from the shared store"\\n' "$name" \\
        > "${VIRTUAL_ENV:?}/bin/$name"
      chmod +x "${VIRTUAL_ENV:?}/bin/$name"
    done
    ;;
esac
"""

#: The stand-in for the release download: it emits the archive the actionlint
#: release ships — one file named `actionlint`, which is the member the script
#: extracts.
STUB_CURL = """#!/usr/bin/env bash
set -euo pipefail
cat "${STUB_RELEASE_ARCHIVE:?}"
"""


class SharedToolStoreTest(unittest.TestCase):
    """The pinned binaries are shared by version, and the code is not."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='setup-local-tools-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.home = self.tmp / 'home'
        self.home.mkdir()
        self.stubs = self.tmp / 'stubs'
        self.stubs.mkdir()
        self._write_stub('uv', STUB_UV)
        self._write_stub('curl', STUB_CURL)
        self.archive = self.tmp / 'actionlint.tar.gz'
        self._write_release_archive(self.archive)

    def _write_stub(self, name: str, body: str) -> None:
        stub = self.stubs / name
        stub.write_text(body, encoding='utf-8')
        stub.chmod(0o755)

    def _write_release_archive(self, path: pathlib.Path) -> None:
        """The archive the actionlint release ships: one file, named actionlint."""
        payload = b'#!/usr/bin/env bash\necho "actionlint from the shared store"\n'
        with tarfile.open(path, 'w:gz') as archive:
            info = tarfile.TarInfo('actionlint')
            info.size = len(payload)
            info.mode = 0o755
            archive.addfile(info, io.BytesIO(payload))

    def _checkout(self, name: str, pins: str | None = None) -> pathlib.Path:
        """A throwaway checkout: the real script, a pins file, and a package root."""
        root = self.tmp / name
        (root / 'scripts' / 'ci').mkdir(parents=True)
        (root / 'scripts' / 'src').mkdir()
        (root / 'config').mkdir()
        target = root / 'scripts' / 'ci' / 'setup-local-tools.sh'
        target.write_text(SCRIPT.read_text(encoding='utf-8'), encoding='utf-8')
        target.chmod(0o755)
        (root / 'config' / 'toolchain-versions.env').write_text(
            pins if pins is not None else PINS_FILE.read_text(encoding='utf-8'),
            encoding='utf-8',
        )
        return root

    def _install(self, root: pathlib.Path) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env['HOME'] = str(self.home)
        env['PATH'] = f"{self.stubs}:{env['PATH']}"
        env['STUB_RELEASE_ARCHIVE'] = str(self.archive)
        return subprocess.run(
            ['bash', str(root / 'scripts' / 'ci' / 'setup-local-tools.sh')],
            cwd=root,
            capture_output=True,
            text=True,
            env=env,
            check=False,
        )

    def _venv_bin(self, root: pathlib.Path, tool: str) -> pathlib.Path:
        return self.home / '.venvs' / f'singularity-todo-{root.name}' / 'bin' / tool

    # ── the property ─────────────────────────────────────────────────────

    def test_two_checkouts_share_one_copy_of_a_pinned_binary(self):
        """The bytes are the same in every checkout, so they are stored once."""
        first = self._checkout('checkout-a')
        second = self._checkout('checkout-b')

        for root in (first, second):
            result = self._install(root)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

        for tool in ('lychee', 'shellcheck', 'actionlint'):
            with self.subTest(tool=tool):
                first_copy = self._venv_bin(first, tool)
                second_copy = self._venv_bin(second, tool)
                self.assertTrue(first_copy.is_symlink(), f'{tool} was copied, not linked')
                self.assertEqual(
                    os.path.realpath(first_copy),
                    os.path.realpath(second_copy),
                    f'{tool} was installed twice',
                )
                # One file on disk, not two that happen to be equal.
                self.assertEqual(
                    os.stat(first_copy).st_ino,
                    os.stat(second_copy).st_ino,
                    f'{tool} exists twice on disk',
                )

    def test_the_store_is_keyed_by_the_pinned_version(self):
        """Sharing is safe because the pin is part of the name."""
        pins = PINS_FILE.read_text(encoding='utf-8').replace(
            'LYCHEE_BIN_VERSION=0.24.2', 'LYCHEE_BIN_VERSION=0.24.3'
        )
        older = self._checkout('checkout-a')
        newer = self._checkout('checkout-b', pins=pins)

        self._install(older)
        self._install(newer)

        older_copy = os.path.realpath(self._venv_bin(older, 'lychee'))
        newer_copy = os.path.realpath(self._venv_bin(newer, 'lychee'))
        self.assertNotEqual(
            older_copy,
            newer_copy,
            'two pinned versions share one file — the pin stopped meaning anything',
        )
        self.assertIn('0.24.2', older_copy)
        self.assertIn('0.24.3', newer_copy)

    def test_the_shared_binary_is_the_one_that_runs(self):
        """The link is not decoration: the tool behind it is the shared copy."""
        root = self._checkout('checkout-a')
        self._install(root)

        linked = self._venv_bin(root, 'lychee')
        self.assertTrue(linked.is_symlink())
        ran = subprocess.run(
            [str(linked)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, ran.returncode, ran.stdout + ran.stderr)
        self.assertEqual('lychee from the shared store', ran.stdout.strip())


if __name__ == '__main__':
    unittest.main()
