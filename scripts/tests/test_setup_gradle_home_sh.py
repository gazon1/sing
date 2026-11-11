"""Tests for `scripts/setup-gradle-home.sh` — the worktree's private Gradle home.

The script builds a per-worktree `GRADLE_USER_HOME` and links back into the
real `~/.gradle` for everything that is safe to share. The split exists because
the daemon registry is per worktree — that is what makes `./gradlew --stop`
local — while the distribution, the module cache and the toolchain JDKs are
expensive and not written in a way that two worktrees could corrupt.

One thing stays private inside the shared caches: `caches/<version>/cc-keystore/`.
Gradle 9 locks that file, and two worktrees sharing one cache would fight over
it. So the caches are shared down to the last entry except the keystore.

The script has no test until now: it was verified by hand in a sandbox. The
properties pinned here are the ones a rewrite would break first:

  - the private home is created and its path is printed on stdout, so
    `GRADLE_USER_HOME="$(scripts/setup-gradle-home.sh)"` keeps working;
  - `wrapper`, `jdks` and the shared entries of `caches/` are links, not copies;
  - `cc-keystore` is a real local directory, never a link, even when the shared
    home carries one;
  - the script is idempotent — a hook runs it on every checkout;
  - the old layout (`caches/` itself a link, or a version directory a link) is
    migrated by removing the link, and the shared directory it pointed at is
    left alone;
  - state this worktree owns (a real directory at a link position) is kept, not
    replaced;
  - content that cannot be moved across a filesystem boundary stays local
    instead of turning a git hook into a gigabyte copy.

The fixture is a throwaway layout, not a mock: the real script, copied into a
throwaway tree so that its `root` is that tree, and a real `HOME` next to it.
The repository's own `.gradle-user-home` is never the subject of a run; one
test says so explicitly.
"""

import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = REPO_ROOT / 'scripts' / 'setup-gradle-home.sh'

#: The private home the script builds inside its own tree.
PRIVATE_DIR_NAME = '.gradle-user-home'

#: A Gradle version directory, as the script names it.
VERSION = '8.14'


def tree_snapshot(root: pathlib.Path) -> dict[str, tuple[str, str | None]]:
    """Describe a tree: every path, whether it is a link, and where it points.

    Symlinks are recorded, not followed — a link into the shared home and a
    real directory with the same name are different things, and the difference
    is the whole point of the script.
    """
    snapshot: dict[str, tuple[str, str | None]] = {}

    def walk(path: pathlib.Path) -> None:
        for child in sorted(path.iterdir()):
            key = str(child.relative_to(root))
            if child.is_symlink():
                snapshot[key] = ('link', os.readlink(child))
            elif child.is_dir():
                snapshot[key] = ('dir', None)
                walk(child)
            else:
                snapshot[key] = ('file', None)

    walk(root)
    return snapshot


#: The directories a running Gradle rewrites on its own. The checkout's own
#: private home is live: a build writes its daemon registry and its scratch
#: files there while the suite runs. Comparing them would compare two unrelated
#: moments, so they are left out of the comparison.
VOLATILE_DIRS = frozenset({'.tmp', 'daemon', 'native', 'workers', 'notifications'})


def structural_snapshot(root: pathlib.Path) -> dict[str, str]:
    """Describe the shape of a tree: every directory and every link.

    Files are left out on purpose — their names and contents change while a
    build runs. What matters for "was this tree touched" is which paths exist
    and where the links point.
    """
    snapshot: dict[str, str] = {}

    def walk(path: pathlib.Path) -> None:
        for child in sorted(path.iterdir()):
            key = str(child.relative_to(root))
            if child.is_symlink():
                snapshot[key] = f'link -> {os.readlink(child)}'
            elif child.is_dir():
                snapshot[key] = 'dir'
                if key not in VOLATILE_DIRS:
                    walk(child)

    walk(root)
    return snapshot


def other_filesystem_home() -> pathlib.Path | None:
    """A writable directory on a filesystem other than the temporary one.

    The script refuses to move cache content across a filesystem boundary: a
    `mv` that becomes a gigabyte copy is a git hook that hangs. The case can
    only be tested where a second filesystem exists, so the directory is
    looked for at run time and the test skips when there is none.
    """
    try:
        base = os.stat(tempfile.gettempdir()).st_dev
    except OSError:
        return None
    for candidate in ('/dev/shm', '/run/shm', '/var/tmp'):
        path = pathlib.Path(candidate)
        if not path.is_dir() or not os.access(path, os.W_OK):
            continue
        try:
            if os.stat(path).st_dev != base:
                return path
        except OSError:
            continue
    return None


OTHER_FILESYSTEM = other_filesystem_home()


class SetupGradleHomeTest(unittest.TestCase):
    """The private home is built, shared where it may be, private where it must."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='setup-gradle-home-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)

        # The script derives its tree from its own location, so the script is
        # copied into a throwaway tree. The repository's own
        # `.gradle-user-home` is then out of reach.
        self.root = self.tmp / 'worktree'
        (self.root / 'scripts').mkdir(parents=True)
        self.script = self.root / 'scripts' / 'setup-gradle-home.sh'
        self.script.write_text(SCRIPT.read_text(encoding='utf-8'), encoding='utf-8')
        self.script.chmod(0o755)

        # A real HOME next to it: the shared home is `$HOME/.gradle`.
        self.home = self.tmp / 'home'
        (self.home / '.gradle').mkdir(parents=True)
        self.shared = self.home / '.gradle'
        self.private = self.root / PRIVATE_DIR_NAME

    # ── helpers ────────────────────────────────────────────────────────────

    def _run(self, *, home: pathlib.Path | None = None) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env['HOME'] = str(home if home is not None else self.home)
        return subprocess.run(
            ['bash', str(self.script)],
            cwd=str(self.root),
            capture_output=True,
            text=True,
            env=env,
            check=False,
        )

    def _make_shared(self, relative: str) -> pathlib.Path:
        """Create a directory in the shared home and return it."""
        path = self.shared / relative
        path.mkdir(parents=True, exist_ok=True)
        return path

    def _write(self, path: pathlib.Path, text: str) -> pathlib.Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')
        return path

    def _assert_links_to(self, link: pathlib.Path, target: pathlib.Path) -> None:
        self.assertTrue(link.is_symlink(), f'{link} is not a link')
        self.assertEqual(
            os.path.realpath(link),
            os.path.realpath(target),
            f'{link} does not point at {target}',
        )

    # ── the fresh layout ───────────────────────────────────────────────────

    def test_the_fresh_home_links_the_shared_entries_and_keeps_the_keystore_private(self):
        """A new worktree shares the heavy caches and owns the keystore."""
        self._make_shared('wrapper')
        self._make_shared('jdks')
        self._make_shared('caches/modules-2')
        self._make_shared(f'caches/{VERSION}/jars')

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(self.private.is_dir(), 'the private home was not created')

        self._assert_links_to(self.private / 'wrapper', self.shared / 'wrapper')
        self._assert_links_to(self.private / 'jdks', self.shared / 'jdks')
        self._assert_links_to(self.private / 'caches/modules-2', self.shared / 'caches/modules-2')

        version_dir = self.private / 'caches' / VERSION
        self.assertTrue(version_dir.is_dir(), 'the version directory is missing')
        self.assertFalse(version_dir.is_symlink(), 'the version directory is a link')
        self._assert_links_to(version_dir / 'jars', self.shared / f'caches/{VERSION}/jars')

        keystore = version_dir / 'cc-keystore'
        self.assertTrue(keystore.is_dir(), 'the keystore directory is missing')
        self.assertFalse(keystore.is_symlink(), 'the keystore is a link — it must be private')

    def test_the_private_home_path_is_printed_on_stdout(self):
        """`GRADLE_USER_HOME="$(scripts/setup-gradle-home.sh)"` must keep working."""
        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        printed = result.stdout.strip()
        self.assertTrue(printed, 'the script printed nothing on stdout')
        self.assertEqual(
            os.path.realpath(self.private),
            os.path.realpath(pathlib.Path(printed)),
            'stdout does not carry the path of the private home',
        )

    # ── idempotence ────────────────────────────────────────────────────────

    def test_running_the_script_twice_changes_nothing(self):
        """A hook runs this on every checkout; the second run must be a no-op."""
        self._make_shared('wrapper')
        self._make_shared('caches/modules-2')
        self._make_shared(f'caches/{VERSION}/jars')

        first = self._run()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        before = tree_snapshot(self.private)

        second = self._run()

        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        self.assertEqual(before, tree_snapshot(self.private))

    # ── what is already at a link position ─────────────────────────────────

    def test_an_existing_link_at_a_link_position_is_refreshed(self):
        """A link from an earlier run may point at the wrong place."""
        self._make_shared('caches/modules-2')
        stale = self._make_shared('caches/old-modules-2')
        (self.private / 'caches').mkdir(parents=True)
        (self.private / 'caches/modules-2').symlink_to(stale)

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self._assert_links_to(self.private / 'caches/modules-2', self.shared / 'caches/modules-2')

    def test_a_real_directory_at_a_link_position_is_kept_and_reported(self):
        """A real directory there is state this worktree owns."""
        self._make_shared('wrapper')
        local_wrapper = self.private / 'wrapper'
        local_wrapper.mkdir(parents=True)
        self._write(local_wrapper / 'mine.txt', 'mine\n')

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(local_wrapper.is_symlink(), 'the local directory was replaced by a link')
        self.assertEqual('mine\n', (local_wrapper / 'mine.txt').read_text(encoding='utf-8'))
        self.assertIn('left 1 existing entries alone', result.stderr)

    def test_a_broken_link_at_a_link_position_is_replaced_by_a_working_one(self):
        """A dangling link is still a link: it is replaced, not kept."""
        self._make_shared('wrapper')
        self.private.mkdir(parents=True)
        (self.private / 'wrapper').symlink_to(self.tmp / 'gone')

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        link = self.private / 'wrapper'
        self.assertTrue(link.is_symlink(), 'the broken link was not replaced by a link')
        self.assertTrue(link.exists(), 'the link still points at nothing')
        self._assert_links_to(link, self.shared / 'wrapper')

    # ── the old layout ─────────────────────────────────────────────────────

    def test_the_old_linked_caches_directory_is_migrated_without_touching_the_shared_one(self):
        """The old layout linked `caches/` itself; the link goes, the target stays."""
        self._write(self.shared / 'caches/modules-2/keep.txt', 'keep\n')
        self.private.mkdir(parents=True)
        (self.private / 'caches').symlink_to(self.shared / 'caches')

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        local_caches = self.private / 'caches'
        self.assertFalse(local_caches.is_symlink(), 'the old link to caches/ was kept')
        self.assertTrue(local_caches.is_dir(), 'the local caches/ directory was not created')
        self.assertEqual(
            'keep\n',
            (self.shared / 'caches/modules-2/keep.txt').read_text(encoding='utf-8'),
            'the shared directory the link pointed at was changed',
        )
        self._assert_links_to(local_caches / 'modules-2', self.shared / 'caches/modules-2')

    def test_the_old_linked_version_directory_is_migrated_without_touching_the_shared_one(self):
        """The old layout linked a version directory whole; the link goes."""
        self._make_shared(f'caches/{VERSION}/jars')
        (self.private / 'caches').mkdir(parents=True)
        (self.private / 'caches' / VERSION).symlink_to(self.shared / f'caches/{VERSION}')

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        local_version = self.private / 'caches' / VERSION
        self.assertFalse(
            local_version.is_symlink(),
            'the old link to the version directory was kept',
        )
        self.assertTrue(local_version.is_dir(), 'the local version directory was not created')
        self.assertTrue(
            (self.shared / f'caches/{VERSION}/jars').is_dir(),
            'the shared version directory was changed',
        )
        self._assert_links_to(local_version / 'jars', self.shared / f'caches/{VERSION}/jars')
        keystore = local_version / 'cc-keystore'
        self.assertTrue(keystore.is_dir(), 'the keystore directory is missing')
        self.assertFalse(keystore.is_symlink(), 'the keystore is a link — it must be private')

    def test_the_old_linked_caches_directory_is_migrated_even_when_the_shared_one_is_gone(self):
        """The shared cache was cleaned away; the old link to it still goes.

        The old layout linked `caches/` itself into the shared home. A link is a
        link whether or not its target still exists: as long as it is there,
        the keystore lands in the shared home the day that directory is back.
        """
        self.private.mkdir(parents=True)
        (self.private / 'caches').symlink_to(self.shared / 'caches')

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        local_caches = self.private / 'caches'
        self.assertFalse(local_caches.is_symlink(), 'the old link to caches/ was kept')
        self.assertTrue(local_caches.is_dir(), 'the local caches/ directory was not created')
        self.assertFalse(
            (self.shared / 'caches').exists(),
            'the shared home was given a caches/ directory it does not have',
        )

    def test_the_old_linked_version_directory_is_migrated_even_when_the_shared_one_is_gone(self):
        """A version link into a shared cache that is gone goes as well."""
        (self.private / 'caches').mkdir(parents=True)
        (self.private / 'caches' / VERSION).symlink_to(self.shared / f'caches/{VERSION}')

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        local_caches = self.private / 'caches'
        self.assertEqual(
            [],
            [child.name for child in local_caches.iterdir() if child.is_symlink()],
            'a link into the shared home survived — the keystore would land in it',
        )

    # ── a version the shared home does not have yet ────────────────────────

    def test_a_local_only_version_directory_is_moved_into_the_shared_home(self):
        """What a build wrote locally before the shared home had the version is shared."""
        self._make_shared('caches')
        local_version = self.private / 'caches' / '9.0'
        self._write(local_version / 'gradle-tools/keep.txt', 'keep\n')

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        shared_version = self.shared / 'caches/9.0'
        self.assertTrue(shared_version.is_dir(), 'the shared home did not gain the version')
        self.assertEqual(
            'keep\n',
            (shared_version / 'gradle-tools/keep.txt').read_text(encoding='utf-8'),
            'the content was not moved into the shared home',
        )
        self._assert_links_to(local_version / 'gradle-tools', shared_version / 'gradle-tools')

    # ── the keystore ───────────────────────────────────────────────────────

    def test_the_keystore_is_private_even_when_the_shared_home_has_one(self):
        """A stale keystore in the shared home must not become the local one."""
        self._make_shared(f'caches/{VERSION}/cc-keystore')
        self._write(self.shared / f'caches/{VERSION}/cc-keystore/stale.bin', 'stale\n')

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        keystore = self.private / 'caches' / VERSION / 'cc-keystore'
        self.assertTrue(keystore.is_dir(), 'the keystore directory is missing')
        self.assertFalse(keystore.is_symlink(), 'the keystore is a link — it must be private')
        self.assertEqual(
            [],
            sorted(p.name for p in keystore.iterdir()),
            'the local keystore carries content of the shared one',
        )

    def test_what_the_keystore_holds_survives_a_second_run(self):
        """The keystore is the state Gradle locks: the script must not disturb it."""
        self._make_shared(f'caches/{VERSION}')

        first = self._run()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)

        keystore = self.private / 'caches' / VERSION / 'cc-keystore'
        self._write(keystore / 'keystore.bin', 'secret\n')

        second = self._run()

        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        self.assertTrue(keystore.is_dir(), 'the keystore directory is gone')
        self.assertFalse(keystore.is_symlink(), 'the keystore is a link — it must be private')
        self.assertEqual(
            'secret\n',
            (keystore / 'keystore.bin').read_text(encoding='utf-8'),
            'the keystore content did not survive the second run',
        )

    # ── across a filesystem boundary ───────────────────────────────────────

    @unittest.skipUnless(
        OTHER_FILESYSTEM is not None,
        'no second writable filesystem is available on this machine',
    )
    def test_content_stays_local_when_the_shared_home_is_on_another_filesystem(self):
        """A cross-device `mv` is a copy: the content stays local instead."""
        other = OTHER_FILESYSTEM
        assert other is not None
        home = other / f'setup-gradle-home-home-{os.getpid()}'
        (home / '.gradle/caches').mkdir(parents=True)
        self.addCleanup(shutil.rmtree, home, True)

        local_version = self.private / 'caches' / '9.0'
        self._write(local_version / 'gradle-tools/keep.txt', 'keep\n')

        result = self._run(home=home)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('another filesystem', result.stderr)
        local_child = local_version / 'gradle-tools'
        self.assertTrue(local_child.is_dir(), 'the local content is gone')
        self.assertFalse(local_child.is_symlink(), 'the local content was linked away')
        self.assertEqual(
            'keep\n',
            (local_child / 'keep.txt').read_text(encoding='utf-8'),
            'the local content did not survive',
        )
        self.assertFalse(
            (home / '.gradle/caches/9.0/gradle-tools').exists(),
            'the content was copied across the filesystem boundary',
        )

    # ── two processes at once ───────────────────────────────────────────────

    def test_two_processes_may_initialise_the_same_tree_at_once(self):
        """Two worktrees can start at the same moment; the layout must still hold."""
        self._make_shared('wrapper')
        self._make_shared('caches/modules-2')
        self._make_shared(f'caches/{VERSION}/jars')

        env = dict(os.environ)
        env['HOME'] = str(self.home)
        processes = [
            subprocess.Popen(
                ['bash', str(self.script)],
                cwd=str(self.root),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                env=env,
            )
            for _ in range(2)
        ]
        outputs = [process.communicate() for process in processes]

        for process, (stdout, stderr) in zip(processes, outputs):
            self.assertEqual(
                0, process.returncode, f'stdout={stdout}\nstderr={stderr}',
            )

        self._assert_links_to(self.private / 'wrapper', self.shared / 'wrapper')
        self._assert_links_to(self.private / 'caches/modules-2', self.shared / 'caches/modules-2')
        local_caches = self.private / 'caches'
        self.assertTrue(local_caches.is_dir(), 'the local caches/ directory is missing')
        self.assertFalse(local_caches.is_symlink(), 'the local caches/ directory is a link')
        version_dir = local_caches / VERSION
        self.assertTrue(version_dir.is_dir(), 'the version directory is missing')
        self.assertFalse(version_dir.is_symlink(), 'the version directory is a link')
        keystore = version_dir / 'cc-keystore'
        self.assertTrue(keystore.is_dir(), 'the keystore directory is missing')
        self.assertFalse(keystore.is_symlink(), 'the keystore is a link — it must be private')

    # ── the repository's own home ──────────────────────────────────────────

    def test_the_repositorys_own_private_home_is_not_touched(self):
        """The fixture is a copy: the checkout's own `.gradle-user-home` is not a subject."""
        repo_private = REPO_ROOT / PRIVATE_DIR_NAME
        before = structural_snapshot(repo_private) if repo_private.is_dir() else {}

        self._make_shared('wrapper')
        self._make_shared('caches/modules-2')
        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        after = structural_snapshot(repo_private) if repo_private.is_dir() else {}
        self.assertEqual(before, after, "the repository's own private home was changed")
        self.assertNotEqual(
            os.path.realpath(self.private),
            os.path.realpath(repo_private),
            'the fixture ran against the repository',
        )
        self.assertNotIn(
            str(repo_private),
            result.stdout,
            "the script printed the repository's own private home",
        )


if __name__ == '__main__':
    unittest.main()
