"""Tests for the generator of the sync allow-list seed.

`infra/gen_sync_allowlist_sql.py` reads the derived sync field lists from
`config/docs/sync-field-allowlist.json` and writes them as the rows of
`supabase/sync_field_allowlist_seed.sql`. The gate
`check-sync-allowlist-regenerated.py` compares the seed with the lists it was
generated from, so the two cannot drift apart.

What went wrong, and what is left of it
----------------------------------------

The generator used to read the Kotlin source with a regular expression: the
body of a `setOf(...)` was taken up to the first `)` in the file, and in
`SyncContract.kt` that `)` sits inside a comment. The seed lost the three
names that came after it — `serverVersion`, `hlc`, `userId` — and nothing said
so, because the gate that checked the file compared it with the same
truncated list. A gate that certifies a file against a broken reader certifies
the breakage.

That reader is gone. The lists are derived in Kotlin, from the models
themselves, and exported to a JSON file, which this generator reads whole.
The property that broke is the one these tests hold now:

* a name the projection holds is a name the seed holds — every one of them,
  in the order the projection declares them;
* the repository's own projection is read whole: the seed carries one row per
  name, and not one name less.

The generator takes the project root as an argument, so everything here runs
against a temporary tree with a synthetic projection, and nothing is written
into the repository.
"""

import json
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
REPO_ROOT = SCRIPTS.parent
GENERATOR = REPO_ROOT / "infra" / "gen_sync_allowlist_sql.py"

#: The projection of the exported lists: per entity type, the names the client
#: sends on the wire, and the names the server may write.
#:
#: The names of the task are given in an order no reader may reorder, and the
#: last of them is the one a reader that cut the list short would lose.
PROJECTION = {
    "version": 1,
    "docTypes": {
        "task": {
            "wire": ["id", "title", "description", "serverVersion", "hlc"],
            "serverWritable": ["id", "title", "description"],
        },
        "note": {
            "wire": ["title", "bodyMarkdown"],
            "serverWritable": ["bodyMarkdown", "title"],
        },
    },
}


def rows_of(seed: str) -> list:
    """The rows of a seed, as `(type, field)` pairs, in the order they stand."""
    return re.findall(r"\('([a-z_]+)',\s*'([A-Za-z]+)',\s*true\)", seed)


class GeneratorTest(unittest.TestCase):
    """The generator, run against a tree of its own."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.project(PROJECTION)

    def project(self, projection: dict) -> None:
        """Put a projection into the tree the generator reads."""
        export = self.root / "config/docs/sync-field-allowlist.json"
        export.parent.mkdir(parents=True, exist_ok=True)
        export.write_text(json.dumps(projection), encoding="utf-8")

    def generate(self) -> str:
        """Run the generator and return the seed it wrote."""
        result = subprocess.run(
            [sys.executable, str(GENERATOR), str(self.root)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        seed = self.root / "supabase/sync_field_allowlist_seed.sql"
        self.assertTrue(seed.is_file(), "the generator wrote no seed")
        return seed.read_text(encoding="utf-8")

    def test_every_name_of_the_projection_is_a_row_of_the_seed(self):
        """The names the projection holds are the rows the seed holds.

        The reader that broke stopped at the first `)` it met, and the names
        after it were not in the seed — while the gate that compared the seed
        with the same reader saw agreement. A name that the projection holds
        and the seed does not is a name nobody can write, and it reads like a
        rule.
        """
        rows = rows_of(self.generate())
        self.assertIn(("task", "description"), rows)
        self.assertIn(("task", "title"), rows)
        self.assertIn(("task", "id"), rows)
        self.assertIn(("note", "bodyMarkdown"), rows)

    def test_the_names_keep_the_order_the_projection_declares(self):
        """The seed lists the names in the order of the projection."""
        rows = rows_of(self.generate())
        self.assertEqual(
            [("note", "bodyMarkdown"), ("note", "title"), ("task", "id"),
             ("task", "title"), ("task", "description")],
            rows,
        )

    def test_the_seed_is_a_description_and_not_a_migration(self):
        """The seed is written outside the migrations.

        A file in `supabase/migrations/` is a migration to apply, and this one
        describes the table the server is expected to hold — the dated
        migrations are what seed the live table. A generator that wrote its
        description into the migrations would offer the server a file to
        apply, and the file would say something about the client rather than
        about the database.
        """
        self.generate()
        self.assertFalse((self.root / "supabase/migrations").exists())
        self.assertTrue((self.root / "supabase/sync_field_allowlist_seed.sql").is_file())

    def test_the_repository_projection_is_read_whole(self):
        """The real projection is read whole: one row per name, and no name less.

        The projection of the repository is the one the models export, and the
        seed beside it is generated from it. A reader that dropped a name
        would leave the seed short of it — and the gate would compare the seed
        with the same reader, so the row that vanished would leave no trace.
        """
        export = json.loads(
            (REPO_ROOT / "config/docs/sync-field-allowlist.json").read_text(encoding="utf-8")
        )
        expected = [
            (entity_type, field)
            for entity_type in sorted(export["docTypes"])
            for field in export["docTypes"][entity_type]["serverWritable"]
        ]

        self.project(export)
        seed = self.generate()
        self.assertEqual(expected, rows_of(seed))


if __name__ == "__main__":
    unittest.main()
