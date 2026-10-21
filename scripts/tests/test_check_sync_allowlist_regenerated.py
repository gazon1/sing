"""Tests for check-sync-allowlist-regenerated.py — the seed SQL must be the generated SQL.

`sync_field_allowlist_seed.sql` is generated from
`SyncContract.FIELD_ALLOWLIST`, and it is a migration: whatever it says is what
the database will enforce. When the contract changes and the seed does not, the
database keeps enforcing the old field list — a sync that silently drops
fields, or accepts fields the contract forbids, with no error anywhere. The
gate exists to make that divergence impossible to merge.

The properties asserted here:

* a seed that matches what the generator produces passes;
* a stale seed fails, and the file is left exactly as it was — the gate
  regenerates in place, so a failure that also rewrote the file would destroy
  the evidence of what was stale;
* a generator that cannot run is a failure, not a pass (exit 1, not 0);
* `--self-test` leaves no trace in the seed.

The generator is the repository's own `infra/gen_sync_allowlist_sql.py`, and it
takes the project root as an argument — so the whole check runs against a
temporary tree with a synthetic `SyncContract.kt`, and nothing is written into
the repository.

One observation about `--self-test`, pinned here as it is rather than changed:
the probe the self-test writes into the seed is overwritten by the
regeneration that follows it, and the comparison is between the content before
the probe and the regenerated content. On an up-to-date seed the self-test
therefore passes. The test below asserts that, so the change that makes the
self-test meaningful is a visible one.
"""

import contextlib
import importlib.util
import io
import pathlib
import subprocess
import sys
import tempfile
import unittest
import unittest.mock

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
REPO_ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS / "src"))

_spec = importlib.util.spec_from_file_location(
    "check_sync_allowlist_regenerated", SCRIPTS / "check-sync-allowlist-regenerated.py"
)
allowlist = importlib.util.module_from_spec(_spec)
sys.modules["check_sync_allowlist_regenerated"] = allowlist
_spec.loader.exec_module(allowlist)

CONTRACT = """\
package com.singularity.todo.core.sync

private val TASK_ALLOWLIST = setOf("title", "notes")
private val NOTE_ALLOWLIST = setOf("body")
"""

BROKEN_GENERATOR = "import sys\nsys.stderr.write('boom\\n')\nsys.exit(2)\n"

GENERATED = """\
-- DO NOT EDIT — generated from SyncContract.FIELD_ALLOWLIST
-- Re-run: python3 infra/gen_sync_allowlist_sql.py

insert into sync_field_allowlist (entity_type, field, writable) values
    ('task', 'title', true),
    ('task', 'notes', true),
    ('note', 'body', true)
on conflict do nothing;
"""


class AllowlistTreeTest(unittest.TestCase):
    """A temporary tree the generator and the gate can both work on."""

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
        self.seed = self.root / "supabase/migrations/sync_field_allowlist_seed.sql"
        self.seed.parent.mkdir(parents=True)

    def write_seed(self, text):
        self.seed.write_text(text, encoding="utf-8")

    def run_gate(self, *args, gen_script=None):
        buffer = io.StringIO()
        with (
            unittest.mock.patch.object(allowlist, "ROOT", str(self.root)),
            unittest.mock.patch.object(allowlist, "OUTPUT_FILE", str(self.seed)),
            unittest.mock.patch.object(
                allowlist, "GEN_SCRIPT", str(gen_script or allowlist.GEN_SCRIPT)
            ),
        ):
            with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
                code = allowlist.run(allowlist.cli.main, list(args))
        return code, buffer.getvalue()


class CleanSeedTest(AllowlistTreeTest):
    """A seed that matches the generator passes."""

    def test_an_up_to_date_seed_passes(self):
        self.write_seed(GENERATED)
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertIn("PASS", output)

    def test_a_regenerated_seed_is_byte_identical(self):
        """The generator is deterministic; the gate must not rewrite the file."""
        self.write_seed(GENERATED)
        before = self.seed.read_bytes()
        self.run_gate()
        self.assertEqual(before, self.seed.read_bytes())


class StaleSeedTest(AllowlistTreeTest):
    """A seed that no longer matches the contract fails."""

    def test_a_stale_seed_fails(self):
        self.write_seed(GENERATED.replace("'notes'", "'notes', 'extra'"))
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("is stale", output)

    def test_the_stale_seed_is_left_as_it_was(self):
        """The gate regenerates in place; a failure that rewrote the file would
        destroy the evidence of what was stale."""
        stale = GENERATED.replace("'notes'", "'notes', 'extra'")
        self.write_seed(stale)
        self.run_gate()
        self.assertEqual(stale, self.seed.read_text(encoding="utf-8"))

    def test_the_report_names_the_generator_to_run(self):
        self.write_seed("something else\n")
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("gen_sync_allowlist_sql.py", output)


class GeneratorFailureTest(AllowlistTreeTest):
    """A generator that cannot run is a failure, not a pass."""

    def test_a_failing_generator_fails_the_gate(self):
        self.write_seed(GENERATED)
        with tempfile.TemporaryDirectory() as tmp:
            broken = pathlib.Path(tmp) / "broken_generator.py"
            broken.write_text(BROKEN_GENERATOR, encoding="utf-8")
            code, output = self.run_gate(gen_script=broken)
        self.assertEqual(1, code, output)
        self.assertIn("generator script exited", output)

    def test_a_failing_generator_does_not_touch_the_seed(self):
        self.write_seed(GENERATED)
        with tempfile.TemporaryDirectory() as tmp:
            broken = pathlib.Path(tmp) / "broken_generator.py"
            broken.write_text("import sys\nsys.exit(2)\n", encoding="utf-8")
            self.run_gate(gen_script=broken)
        self.assertEqual(GENERATED, self.seed.read_text(encoding="utf-8"))


class SelfTestTest(AllowlistTreeTest):
    """`--self-test` must leave no trace."""

    def test_the_self_test_leaves_the_seed_as_it_found_it(self):
        self.write_seed(GENERATED)
        self.run_gate("--self-test")
        self.assertEqual(GENERATED, self.seed.read_text(encoding="utf-8"))

    def test_the_self_test_passes_on_an_up_to_date_seed(self):
        """Pins the current behaviour of `--self-test`.

        The probe the self-test writes into the seed is overwritten by the
        regeneration that follows, and the comparison is between the pre-probe
        content and the regenerated content — so on an up-to-date seed the
        self-test passes. The flag's own help text promises the opposite
        ("Assert that the gate correctly fails when the seed is stale"), so
        this test is here to make a fix visible.
        """
        self.write_seed(GENERATED)
        code, output = self.run_gate("--self-test")
        self.assertEqual(0, code, output)


class RealSeedTest(unittest.TestCase):
    """This repository's committed seed, against this repository's contract.

    The generator is run against a temporary copy of the real
    `SyncContract.kt`, so the comparison is exact and nothing in the
    repository is rewritten.
    """

    def test_the_committed_seed_matches_the_committed_contract(self):
        committed_contract = (
            REPO_ROOT
            / "shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt"
        )
        committed_seed = (
            REPO_ROOT / "supabase/migrations/sync_field_allowlist_seed.sql"
        )
        self.assertTrue(committed_contract.is_file())
        self.assertTrue(committed_seed.is_file())

        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            contract = (
                root
                / "shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt"
            )
            contract.parent.mkdir(parents=True)
            contract.write_text(
                committed_contract.read_text(encoding="utf-8"), encoding="utf-8"
            )
            result = subprocess.run(
                ["python3", str(REPO_ROOT / "infra/gen_sync_allowlist_sql.py"), str(root)],
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            generated = (
                root / "supabase/migrations/sync_field_allowlist_seed.sql"
            ).read_text(encoding="utf-8")

        self.assertEqual(
            committed_seed.read_text(encoding="utf-8"),
            generated,
            "the committed seed is stale — run: python3 infra/gen_sync_allowlist_sql.py",
        )


if __name__ == "__main__":
    unittest.main()
