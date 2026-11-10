"""Tests for `scripts/ci/check-migration-naming.py` — the gate that names the indexes.

The gate reads the Kotlin sources and checks that every `CREATE INDEX` names
its index `index_<table>_<cols>`. The convention exists so that a migration
diff reads as a table: `index_notes_user_id` says which table and which
columns the index serves, and `notes_user_id_idx` says nothing that a reader
can use.

What these tests pin is not the convention but the gate's reach. The gate used
to scan `shared/src`, and the migrations moved to `core-database/` when the
modules were split. The gate kept running, kept finding no index at all, and
kept reporting a clean tree: a gate that reads a directory the migrations are
not in checks nothing and can fail on nothing. So:

  1. an index outside `shared/src` is still read — the regression, written as a
     test because the failure mode was silence;
  2. a conforming name passes, an offending name fails, and the failure names
     the file and the line;
  3. a scan that finds no index statement at all is a finding, not a pass —
     the tree moved again and the gate did not follow.

The script scans relative to the working directory, so every test runs it in a
throwaway tree. That is the fixture, not a mock: the real script, real files.
"""

import pathlib
import subprocess
import sys
import tempfile
import unittest

_GATE = pathlib.Path(__file__).resolve().parent.parent / "ci" / "check-migration-naming.py"

_MIGRATION = """package com.singularity.todo.core.database

class Migration31To32 {
    fun migrate(connection: Connection) {
        connection.execSQL("__STATEMENT__")
    }
}
"""


def _run(tree: pathlib.Path) -> subprocess.CompletedProcess:
    """Run the gate over `tree`, as the registry runs it: with the tree as cwd."""
    return subprocess.run(
        [sys.executable, str(_GATE)],
        cwd=tree,
        capture_output=True,
        text=True,
    )


class ReachTest(unittest.TestCase):
    """The gate must read the migrations wherever they live."""

    def _tree_with(self, statement: str) -> tempfile.TemporaryDirectory:
        directory = tempfile.TemporaryDirectory()
        tree = pathlib.Path(directory.name)
        migration = tree / "core-database" / "src" / "commonMain" / "kotlin" / "com"
        migration.mkdir(parents=True)
        (migration / "Migration31To32.kt").write_text(
            _MIGRATION.replace("__STATEMENT__", statement),
        )
        return directory

    def test_an_offending_index_outside_shared_is_reported(self):
        """The regression: the old scan read `shared/src` and found nothing here."""
        with self._tree_with(
            "CREATE INDEX IF NOT EXISTS `notes_user_id_idx` ON `notes` (`user_id`)"
        ) as tree:
            result = _run(pathlib.Path(tree))

        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("notes_user_id_idx", result.stdout)
        self.assertIn("Migration31To32.kt", result.stdout)

    def test_a_conforming_index_passes(self):
        with self._tree_with(
            "CREATE INDEX IF NOT EXISTS `index_notes_user_id` ON `notes` (`user_id`)"
        ) as tree:
            result = _run(pathlib.Path(tree))

        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("OK", result.stdout)

    def test_the_failure_names_the_line(self):
        with self._tree_with(
            "CREATE INDEX IF NOT EXISTS `notes_user_id_idx` ON `notes` (`user_id`)"
        ) as tree:
            result = _run(pathlib.Path(tree))

        self.assertIn(":5:", result.stdout)


class EmptyScanTest(unittest.TestCase):
    """A scan that finds no index at all is the tree having moved, not a pass."""

    def test_a_tree_without_indexes_is_a_finding(self):
        with tempfile.TemporaryDirectory() as name:
            tree = pathlib.Path(name)
            (tree / "build").mkdir()
            (tree / "build" / "Generated.kt").write_text(
                "CREATE INDEX IF NOT EXISTS `generated_idx` ON `x` (`y`)\n",
            )
            result = _run(tree)

        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("no CREATE INDEX statement found", result.stdout)

    def test_build_output_is_not_scanned(self):
        """A bad name under `build/` is generated output, not a migration."""
        with tempfile.TemporaryDirectory() as name:
            tree = pathlib.Path(name)
            (tree / "build").mkdir()
            (tree / "build" / "Generated.kt").write_text(
                "CREATE INDEX IF NOT EXISTS `generated_idx` ON `x` (`y`)\n",
            )
            migration = tree / "core-database"
            migration.mkdir()
            (migration / "Migration31To32.kt").write_text(
                _MIGRATION.replace(
                    "__STATEMENT__",
                    "CREATE INDEX IF NOT EXISTS `index_notes_user_id` "
                    "ON `notes` (`user_id`)",
                ),
            )
            result = _run(tree)

        self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == "__main__":
    unittest.main()
