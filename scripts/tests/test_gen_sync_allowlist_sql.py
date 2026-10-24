"""Tests for the generator of the sync allow-list seed.

`infra/gen_sync_allowlist_sql.py` reads the field allow-lists out of
`SyncContract.kt` and writes them as the rows of
`supabase/migrations/sync_field_allowlist_seed.sql`. The gate
`check-sync-allowlist-regenerated.py` compares the two, so the generator and
the seed can never drift apart — and that is exactly why the generator's own
mistake was invisible: the seed it wrote was wrong in the same way, and the
gate saw agreement.

The mistake was in how the list was read. The body of a `setOf(...)` was
taken up to the first `)` in the file, and in `SyncContract.kt` that `)` sits
inside a comment — so the fields after it were dropped. For the task that
meant `serverVersion`, `hlc` and `userId`: three fields the contract
declares, absent from the seed, and nobody could see it because the file and
the generator agreed.

The properties asserted here:

* an unbalanced `(` inside a comment does not open a level — the list still
  ends at the parenthesis that closes the call, and the fields after the
  comment are still fields of the list;
* the fields are the ones the contract declares, in the order it declares
  them.

The generator takes the project root as an argument, so everything here runs
against a temporary tree with a synthetic `SyncContract.kt`, and nothing is
written into the repository.
"""

import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
REPO_ROOT = SCRIPTS.parent
GENERATOR = REPO_ROOT / "infra" / "gen_sync_allowlist_sql.py"

# A contract whose comment holds the parenthesis that used to end the list.
# The parenthesis in the comment is *unbalanced* — there is no closing one —
# so a reader that counts parentheses over a source it has not blanked out
# loses the level and never finds the end of the list.
CONTRACT = """\
package com.singularity.todo.core.sync

object SyncContract {

    private val TASK_ALLOWLIST = setOf(
        // The client-side sync metadata the server stores without enforcing
        // (serverVersion=0, hlc=null for a local row
        "id",
        "title",
        "serverVersion",
    )
}
"""


class GeneratorTest(unittest.TestCase):
    """The generator, run against a tree of its own."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        contract = (
            self.root
            / "shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt"
        )
        contract.parent.mkdir(parents=True)
        contract.write_text(CONTRACT, encoding="utf-8")

    def generate(self) -> str:
        """Run the generator and return the seed it wrote."""
        result = subprocess.run(
            [sys.executable, str(GENERATOR), str(self.root)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        seed = self.root / "supabase/migrations/sync_field_allowlist_seed.sql"
        self.assertTrue(seed.is_file(), "the generator wrote no seed")
        return seed.read_text(encoding="utf-8")

    def test_a_parenthesis_in_a_comment_does_not_end_the_list(self):
        """The fields after the comment are still fields of the list.

        The comment above holds an *unbalanced* `(` — it opens and never
        closes — and a reader that counts parentheses over a source it has
        not blanked out loses a level there and never finds the end of the
        list. A reader that stops at the wrong place stops silently, and with
        a seed that agrees with the generator that stopped there too.
        """
        seed = self.generate()
        self.assertIn("('task', 'id', true)", seed)
        self.assertIn("('task', 'title', true)", seed)
        self.assertIn("('task', 'serverVersion', true)", seed)

    def test_the_fields_keep_the_order_the_contract_declares(self):
        """The seed lists the fields in the order of the contract."""
        seed = self.generate()
        self.assertLess(seed.index("'id'"), seed.index("'title'"))
        self.assertLess(seed.index("'title'"), seed.index("'serverVersion'"))

    def test_the_repository_contract_is_read_whole(self):
        """The real contract's task list is read whole.

        The list of the task ends with the three client-side sync metadata
        fields, and the `)` that used to cut it short sits in a comment
        between `updatedAt` and `serverVersion`. The contract is copied into
        the tree of the test, so the repository's own seed is not written by a
        test.
        """
        contract = (
            REPO_ROOT
            / "shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt"
        )
        copied = (
            self.root
            / "shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt"
        )
        copied.parent.mkdir(parents=True, exist_ok=True)
        copied.write_text(contract.read_text(encoding="utf-8"), encoding="utf-8")

        seed = self.generate()
        for field in ("serverVersion", "hlc", "userId"):
            self.assertIn(f"('task', '{field}', true)", seed)


if __name__ == "__main__":
    unittest.main()
