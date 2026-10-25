"""Tests for check-sync-allowlist-regenerated.py — the seed SQL must be the generated SQL,
and the server's field table must agree with the derived lists.

`sync_field_allowlist_seed.sql` is generated from the derived field lists, and it is a
DESCRIPTION of the table the database enforces: whatever it says is what the database
enforces. When the models change and the seed does not, the database keeps enforcing the
old field list — a sync that silently drops fields, or accepts fields the contract
forbids, with no error anywhere. The gate exists to make that divergence impossible to
merge.

The gate checks two things:

* the committed seed is what the generator produces from the derived lists;
* the server's table names no field outside the derived server list. Such a name is a
  field the server may write and nothing ever writes, and it reads like a rule.

The rows of the table are read out of the migrations statement by statement: the reader
takes the column list the statement names and maps the values onto it. A row is read the
same way however it was written — the columns in any order, the flag as `true`, as
`'t'`, as `'yes'`, as `TRUE::boolean`, the name as `'task'`, as `'task'::text`, as `$$task$$`.

What the reader cannot read, it REPORTS. It does not pass over it: a row it did not see is
a field nobody sends, which is the direction the gate exists for. Twenty-four review rounds
found such a hole in every one of them, and every hole was a row the reader dropped
in silence.

The properties asserted here:

* a seed that matches what the generator produces passes;
* a stale seed fails, and the file is left exactly as it was — the gate
  regenerates in place, so a failure that also rewrote the file would destroy
  the evidence of what was stale;
* a generator that cannot run is a failure, not a pass (exit 1, not 0);
* `--self-test` leaves no trace in the seed, and it fails when the probe it writes
  does not make the seed stale — a check that cannot tell a stale seed from a current
  one cannot fail;
* a server table naming a field outside the derived server list fails, and the
  report names it;
* a row is a row however it was written: a schema-qualified table, an `only`,
  a row inside a function body, the columns in any order, the flag spelled
  twelve ways — the reader reads the values, not the shape;
* a row the reader cannot read is REPORTED, not passed over: a row from a query, a row
  without a column list, a row whose values do not match its columns;
* a statement that renames a row, empties the table or merges into it is REPORTED,
  because the reader cannot see what such a statement does — and such a statement
  about ANOTHER table is not the gate's business;
* a row of a type neither the client nor the server has is reported, and a type one
  spelling away from the client's is reported too;
* two rows that disagree about the same field are reported: one of them says the
  server may not write the field, and the server honours it;
* a field the models have and the server has not been migrated for is reported, not
  failed — that is a deployment gap, and it is tracked by an issue.

The generator is the repository's own `infra/gen_sync_allowlist_sql.py`, and it
takes the project root as an argument — so the whole check runs against a
temporary tree with a synthetic export, and nothing is written into the
repository.
"""

import contextlib
import importlib.util
import io
import json
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

EXPORT = {
    "version": 1,
    "docTypes": {
        "task": {
            "wire": ["id", "title", "notes"],
            "serverWritable": ["title", "notes"],
        },
        "note": {
            "wire": ["id", "body"],
            "serverWritable": ["body"],
        },
    },
}

BROKEN_GENERATOR = "import sys\nsys.stderr.write('boom\\n')\nsys.exit(2)\n"

GENERATOR = REPO_ROOT / "infra/gen_sync_allowlist_sql.py"


def generated_seed(export: dict) -> str:
    """The seed the repository's own generator produces for `export`.

    The expected file is produced by the generator rather than written out here:
    a copy of it in this test would be a third copy of the header, and the
    point of the change is that there is one.
    """
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        export_file = root / "config/docs/sync-field-allowlist.json"
        export_file.parent.mkdir(parents=True)
        export_file.write_text(json.dumps(export, indent=4), encoding="utf-8")
        result = subprocess.run(
            ["python3", str(GENERATOR), str(root)],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise AssertionError(f"the generator failed: {result.stderr}")
        return (
            root / "supabase/sync_field_allowlist_seed.sql"
        ).read_text(encoding="utf-8")


GENERATED = generated_seed(EXPORT)

SERVER_MIGRATION = """\
-- The server's schema, with the table that says which field a patch may write.
insert into sync_field_allowlist (entity_type, field, writable) values
    ('task', 'title', true),
    ('note', 'body', true)
on conflict (entity_type, field) do nothing;
"""


class AllowlistTreeTest(unittest.TestCase):
    """A temporary tree the generator and the gate can both work on."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        export = self.root / "config/docs/sync-field-allowlist.json"
        export.parent.mkdir(parents=True)
        export.write_text(json.dumps(EXPORT, indent=4), encoding="utf-8")
        self.export = export
        self.migrations = self.root / "supabase/migrations"
        self.migration = self.migrations / "2026-10-07-sync_schema.sql"
        self.migrations.mkdir(parents=True, exist_ok=True)
        self.migration.write_text(SERVER_MIGRATION, encoding="utf-8")
        self.seed = self.root / "supabase/sync_field_allowlist_seed.sql"
        self.seed.parent.mkdir(parents=True, exist_ok=True)

    def write_seed(self, text):
        self.seed.write_text(text, encoding="utf-8")

    def run_gate(self, *args, gen_script=None):
        buffer = io.StringIO()
        with (
            unittest.mock.patch.object(allowlist, "ROOT", str(self.root)),
            unittest.mock.patch.object(allowlist, "OUTPUT_FILE", str(self.seed)),
            unittest.mock.patch.object(allowlist, "EXPORT_FILE", str(self.export)),
            unittest.mock.patch.object(allowlist, "MIGRATIONS_DIR", str(self.migrations)),
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


class StaleSeedTest(AllowlistTreeTest):
    """A seed the generator would change is a failure — and the file survives it."""

    def test_a_stale_seed_fails(self):
        self.write_seed(GENERATED.replace("'title'", "'title_changed'"))
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("stale", output)

    def test_a_stale_seed_is_left_as_it_was(self):
        """The gate regenerates in place; it must not leave the rewrite behind.

        A failure that also rewrote the file would destroy the evidence of
        what was stale.
        """
        stale = GENERATED.replace("'title'", "'title_changed'")
        self.write_seed(stale)
        self.run_gate()
        self.assertEqual(stale, self.seed.read_text(encoding="utf-8"))

    def test_a_generator_that_cannot_run_is_a_failure(self):
        """Exit 1, not exit 0: a check that could not run did not pass."""
        self.write_seed(GENERATED)
        with tempfile.TemporaryDirectory() as tmp:
            broken = pathlib.Path(tmp) / "broken_generator.py"
            broken.write_text(BROKEN_GENERATOR, encoding="utf-8")
            code, output = self.run_gate(gen_script=broken)
        self.assertEqual(1, code, output)
        self.assertIn("generator", output.lower())


class SelfTestTest(AllowlistTreeTest):
    """`--self-test` must leave no trace, and it must be able to fail."""

    def test_the_self_test_leaves_the_seed_as_it_found_it(self):
        self.write_seed(GENERATED)
        self.run_gate("--self-test")
        self.assertEqual(GENERATED, self.seed.read_text(encoding="utf-8"))

    def test_the_self_test_passes_on_an_up_to_date_seed(self):
        """The probe makes the seed stale, and the gate sees that it is stale."""
        self.write_seed(GENERATED)
        code, output = self.run_gate("--self-test")
        self.assertEqual(0, code, output)
        self.assertIn("PASS", output)

    def test_the_self_test_fails_when_the_probe_changes_nothing(self):
        """A self-test that cannot fail is a check that cannot fail.

        The generator is replaced by one that writes the seed back unchanged, so
        the probe the self-test writes is what the file holds afterwards — and
        the self-test has to notice that it cannot tell a stale seed from a
        current one.
        """
        self.write_seed(GENERATED)
        with tempfile.TemporaryDirectory() as tmp:
            identity = pathlib.Path(tmp) / "identity_generator.py"
            identity.write_text(
                "import sys, pathlib\n"
                "root = pathlib.Path(sys.argv[1])\n"
                "target = root / 'supabase/sync_field_allowlist_seed.sql'\n"
                "target.parent.mkdir(parents=True, exist_ok=True)\n"
                "target.write_text(target.read_text())\n",
                encoding="utf-8",
            )
            code, output = self.run_gate("--self-test", gen_script=identity)
        self.assertEqual(1, code, output)
        self.assertIn("cannot tell a stale seed from a current one", output)

    def test_the_self_test_fails_on_a_stale_committed_seed(self):
        """A self-test that says the gate works while the gate would fail is wrong.

        The committed seed is stale; the gate fails on it. The self-test has to
        say so rather than report a working gate over a broken tree.
        """
        self.write_seed(GENERATED + "    --stale-field text,\n")
        code, output = self.run_gate("--self-test")
        self.assertEqual(1, code, output)
        self.assertIn("the committed seed is stale", output)


class ServerTableTest(AllowlistTreeTest):
    """The server's table must agree with the derived server list."""

    def test_a_field_the_client_does_not_send_fails(self):
        """A row naming a field no model has is a field nobody sends."""
        self.write_seed(GENERATED)
        self.migration.write_text(
            SERVER_MIGRATION
            + "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', true)\n"
            "on conflict (entity_type, field) do nothing;\n",
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_field_the_server_owns_fails(self):
        """The four names the server owns are not in the server's list either.

        A row naming one of them says the server may write the document's own
        bookkeeping.
        """
        self.write_seed(GENERATED)
        self.migration.write_text(
            SERVER_MIGRATION
            + "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'id', true)\n"
            "on conflict (entity_type, field) do nothing;\n",
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("task.id", output)

    def test_a_type_the_server_does_not_know_fails(self):
        """A type the client syncs and the server has no row for is refused."""
        self.write_seed(GENERATED)
        self.migration.write_text(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'title', true)\n"
            "on conflict (entity_type, field) do nothing;\n",
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("has no row for a type the client syncs", output)
        self.assertIn("note", output)

    def test_a_row_of_a_type_the_server_has_of_its_own_is_the_servers_own(self):
        """The server's profile table is the server's business."""
        self.write_seed(GENERATED)
        self.migration.write_text(
            SERVER_MIGRATION
            + "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('profile', 'ownerId', true)\n"
            "on conflict (entity_type, field) do nothing;\n",
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(0, code, output)

    def test_a_row_that_refuses_a_field_the_client_expects_fails(self):
        """The server may not refuse a field the client expects it to write."""
        self.write_seed(GENERATED)
        self.migration.write_text(
            SERVER_MIGRATION
            + "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'title', false)\n"
            "on conflict (entity_type, field) do nothing;\n",
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("task.title", output)

    def test_two_rows_that_disagree_about_a_field_are_reported(self):
        """One of them says the server may not write the field.

        The table holds one row per type and field, so the one applied first
        is the one the table keeps — but a row that refuses a field the
        client sends is a row the server honours, and it is reported.
        """
        self.write_seed(GENERATED)
        self.migration.write_text(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'title', true),\n"
            "    ('task', 'title', false);\n",
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("task.title", output)

    def test_a_field_the_server_has_not_been_migrated_for_is_reported(self):
        """That is a deployment gap, and it is tracked by an issue."""
        self.write_seed(GENERATED)
        self.migration.write_text(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'title', true),\n"
            "    ('note', 'body', true)\n"
            "on conflict (entity_type, field) do nothing;\n",
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertIn("task.notes", output)
        self.assertIn("NOTE", output)


class RowIsANameTest(AllowlistTreeTest):
    """A row is a row however it was written."""

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_schema_qualified_table_is_read(self):
        code, output = self._rows(
            "insert into public.sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', true)\n"
            "on conflict (entity_type, field) do nothing;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_an_only_is_read(self):
        code, output = self._rows(
            "insert into only sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', true)\n"
            "on conflict (entity_type, field) do nothing;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_an_insert_or_replace_is_reported(self):
        """`insert or replace` is not PostgreSQL's spelling.

        The migration would not apply, and the gate says it cannot read the
        write rather than reading a row the database would never hold.
        """
        code, output = self._rows(
            "insert or replace into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("did not read", output)

    def test_a_row_read_back_with_returning_is_read(self):
        """`returning *` is a finished statement, not an unfinished expression."""
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', true)\n"
            "returning *;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_row_inside_a_function_body_is_read(self):
        """A function body's row is a row the file holds.

        The reader compares names, and a name inside a body is a name. Whether
        the body ever runs is the server's business; a field nobody sends is
        this gate's.
        """
        code, output = self._rows(
            "create or replace function public.__p() returns void as $fn$\n"
            "begin\n"
            "  insert into sync_field_allowlist (entity_type, field, writable)\n"
            "  values ('task', 'ghostField', true);\n"
            "end;\n"
            "$fn$ language plpgsql;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_row_in_a_later_file_is_read(self):
        """Every migration is read, not only the first one."""
        self.write_seed(GENERATED)
        (self.migrations / "2026-10-08-more.sql").write_text(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', true)\n"
            "on conflict (entity_type, field) do nothing;\n",
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_boolean_written_as_a_number_is_reported(self):
        """PostgreSQL does not take an unquoted `1` for a boolean column.

        The column is boolean and the expression is an integer, so the
        migration would not apply — and the gate says it cannot read the
        row rather than guessing what it means.
        """
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', 1)\n"
            "on conflict (entity_type, field) do nothing;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot read", output)

    def test_a_row_of_another_table_is_not_ours(self):
        """A row belongs to the table the statement names."""
        code, output = self._rows(
            SERVER_MIGRATION
            + "insert into sync_field_allowlist_log (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', true)\n"
            "on conflict (entity_type, field) do nothing;\n"
        )
        self.assertEqual(0, code, output)

    def test_a_row_of_another_table_before_ours_is_not_ours(self):
        """The nearest insert before a row names the table the row belongs to."""
        code, output = self._rows(
            "insert into sync_events (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', true);\n"
            + SERVER_MIGRATION
        )
        self.assertEqual(0, code, output)

    def test_a_row_with_its_type_and_field_swapped_fails(self):
        """A field named after a type is a row written the wrong way round.

        The swap hides a field under a type of its own: the client would never
        send it, and the gate would not see it — unless it notices that no
        field is named after a type.
        """
        code, output = self._rows(
            "insert into sync_field_allowlist (field, entity_type, writable) values\n"
            "    ('ghostField', 'task', true)\n"
            "on conflict (entity_type, field) do nothing;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("ghostField", output)


class RowSpellingTest(AllowlistTreeTest):
    """A row is read however it was written."""

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_the_columns_may_be_named_in_any_order(self):
        """The values are mapped by the column list, not by their position."""
        code, output = self._rows(
            "insert into sync_field_allowlist (writable, entity_type, field) values\n"
            "    (true, 'task', 'ghostField');\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_the_flag_may_be_written_as_a_letter(self):
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', 't');\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_the_flag_may_be_written_as_a_word(self):
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', 'yes');\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_the_flag_may_be_cast(self):
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', TRUE::boolean);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_name_may_be_dollar_quoted(self):
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ($$task$$, $$ghostField$$, $$t$$);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_value_may_be_written_in_parentheses(self):
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    (('task'), ('ghostField'), (true));\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_name_may_be_cast(self):
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task'::text, 'ghostField'::text, true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_comment_may_sit_inside_a_value(self):
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', true /* a comment */);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_cast_may_sit_inside_parentheses(self):
        """The parentheses and the cast are both part of the value."""
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    (('task'::text), ('ghostField'::text), (true));\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)


class UnreadableRowTest(AllowlistTreeTest):
    """What the reader cannot read, it reports — a row it did not see is a row
    it cannot vouch for."""

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_row_that_comes_from_a_query_is_reported(self):
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable)\n"
            "select 'task', 'ghostField', true;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot read", output)

    def test_a_row_without_a_column_list_is_reported(self):
        """Without the column list the reader cannot tell which value is which."""
        code, output = self._rows(
            "insert into sync_field_allowlist values ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("does not name its columns", output)

    def test_a_row_whose_values_do_not_match_its_columns_is_reported(self):
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field) values\n"
            "    ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("columns", output)

    def test_a_value_the_reader_does_not_know_is_reported(self):
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', now());\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot read", output)


class UnknownTypeTest(AllowlistTreeTest):
    """A row of a type nobody has is a row the server never reads."""

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_type_nobody_has_is_reported(self):
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('1', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("neither the client nor the server has", output)

    def test_a_type_one_spelling_away_is_reported(self):
        """A near-miss of a client's type is a row that was meant for it."""
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('Task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("Task.ghostField", output)
        self.assertIn("did you mean 'task'", output)


class CopyTest(AllowlistTreeTest):
    """A copy writes rows without naming them, so it is reported."""

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_copy_into_the_table_is_reported(self):
        code, output = self._rows(
            "copy sync_field_allowlist (entity_type, field, writable) from stdin;\n"
            "task\tghostField\tt\n"
            "\\.\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("does not model", output)

    def test_a_binary_copy_into_the_table_is_reported(self):
        """`copy binary` is the same copy with a keyword in front."""
        code, output = self._rows(
            "copy binary sync_field_allowlist (entity_type, field, writable) from stdin;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("does not model", output)

    def test_a_copy_of_another_table_is_not_ours(self):
        code, output = self._rows("copy sync_events from stdin;\n")
        self.assertEqual(0, code, output)

    def test_a_copy_out_of_the_table_is_not_a_write(self):
        """`copy ... to stdout` reads the table; it writes nothing into it."""
        code, output = self._rows("copy sync_field_allowlist to stdout;\n")
        self.assertEqual(0, code, output)


class DynamicSqlTest(AllowlistTreeTest):
    """An insert written as text and run by the migration is a row the file holds."""

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_an_insert_written_inside_a_do_block_is_read(self):
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  execute 'insert into sync_field_allowlist (entity_type, field, writable) "
            "values (''task'', ''ghostField'', true)';\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_an_insert_written_as_a_function_body_is_read(self):
        code, output = self._rows(
            "create function f() returns void as "
            "'insert into sync_field_allowlist (entity_type, field, writable) "
            "values (''task'', ''ghostField'', true)' language sql;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_an_insert_assembled_by_a_format_is_reported(self):
        """The table's name is an argument, so no spelling names the write.

        The server's own migrations write rows this way — but the allowlist is
        not seeded that way: the seeding migration writes a plain list of
        names, and a migration that seeds it through a computed name is
        exactly the shape the reader cannot place.
        """
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  execute format("
            "'insert into %I (entity_type, field, writable) values (%L, %L, %L)', "
            "'sync_field_allowlist', 'task', 'ghostField', true);\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot read", output)

    def test_an_insert_assembled_by_a_concatenation_is_read(self):
        """The text the pieces spell is the text the migration runs.

        The reader folds the statement before it reads it, so a row the file
        holds is a row the file holds however it was written — the row is
        read and named, not merely reported as unreadable.
        """
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  execute 'insert into ' || 'sync_field_allowlist' || "
            "' (entity_type, field, writable) values ($q$task$q$, $q$ghostField$q$, true)';\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_name_split_across_two_pieces_is_read(self):
        """The name shares its literal with the rest of the statement.

        `'insert into sync_' || 'field_allowlist (…)'` is one text, and the
        text names the table — the row is read and named.
        """
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  execute 'insert into sync_' || 'field_allowlist (entity_type, field, "
            "writable) values (''task'', ''ghostField'', true)';\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_write_assembled_by_a_call_is_read(self):
        """`concat` builds the text, and the text names the table."""
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  execute concat('insert into sync_field', '_allowlist (entity_type, field, "
            "writable) values (''task'', ''ghostField'', true)');\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_write_assembled_by_a_call_argument_is_reported(self):
        """A call that runs its argument is a call the reader cannot follow.

        The argument is folded before it is read, so a name split across two
        pieces is one argument, and it spells one write.
        """
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  perform dblink_exec('conn', 'insert into sync_' || 'field_allowlist "
            "(entity_type, field, writable) values (''task'', ''ghostField'', true)');\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("handed to a call", output)

    def test_an_insert_written_in_a_dollar_quoted_literal_is_read(self):
        """A dollar-quoted literal in an executable position is code."""
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  execute $q$insert into sync_field_allowlist "
            "(entity_type, field, writable) values ('task', 'ghostField', true)$q$;\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_an_insert_written_in_a_dollar_quoted_template_is_reported(self):
        """The name is an argument of the format call, beside the template."""
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  execute format($f$insert into %I (entity_type, field, writable) "
            "values (%L, %L, %L)$f$, "
            "'sync_field_allowlist', 'task', 'ghostField', true);\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot tell", output)

    def test_an_insert_written_after_do_is_read(self):
        """`do '...'` is a body written as a string."""
        code, output = self._rows(
            "do 'insert into sync_field_allowlist (entity_type, field, writable) "
            "values (''task'', ''ghostField'', true)';\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_an_insert_written_with_the_name_in_a_variable_is_reported(self):
        """The name is held in a variable, and the variable is in the same body."""
        code, output = self._rows(
            "do $$\n"
            "declare tbl text := 'sync_field_allowlist';\n"
            "begin\n"
            "  execute format("
            "'insert into %I (entity_type, field, writable) values (%L, %L, %L)', "
            "tbl, 'task', 'ghostField', true);\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot tell", output)

    def test_an_insert_written_with_the_name_in_pieces_is_reported(self):
        """A name assembled from pieces is the name it spells."""
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  execute format("
            "'insert into %I (entity_type, field, writable) values (%L, %L, %L)', "
            "'sync_' || 'field_allowlist', 'task', 'ghostField', true);\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot tell", output)

    def test_a_value_that_merely_mentions_an_insert_is_not_ours(self):
        """A string in a value position is a value, whatever it says."""
        code, output = self._rows(
            "insert into sync_events (note) values\n"
            "    ('insert into sync_field_allowlist values (1)');\n"
        )
        self.assertEqual(0, code, output)

    def test_a_format_that_names_another_table_is_not_ours(self):
        """The write points at another table; the argument is another table's."""
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  execute format("
            "'insert into %I (a, b) values (%L, %L)', "
            "'sync_events', 'x', 'y');\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(0, code, output)

    def test_a_name_assembled_by_a_format_call_is_folded(self):
        """A name handed to a call in pieces is the name the call spells.

        The pieces are literals, so the name is in the file — and a name the
        reader can see is a name the reader can place.
        """
        code, output = self._rows(
            "do $$\n"
            "declare\n"
            "  tbl text := format('%s%s', 'sync_field_', 'allowlist');\n"
            "begin\n"
            "  execute format("
            "'insert into %I (entity_type, field, writable) values (%L, %L, %L)', "
            "tbl, 'task', 'ghostField', true);\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot tell", output)

    def test_a_name_assembled_by_a_concat_call_is_folded(self):
        """`concat` spells a name out of its arguments, like `||` does."""
        code, output = self._rows(
            "do $$\n"
            "declare\n"
            "  tbl text := concat('sync_field_', 'allowlist');\n"
            "begin\n"
            "  execute format("
            "'insert into %I (entity_type, field, writable) values (%L, %L, %L)', "
            "tbl, 'task', 'ghostField', true);\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot tell", output)

    def test_an_alias_that_holds_the_words_of_an_insert_is_a_value(self):
        """`as` is an alias where it is not a body's — and an alias is a value.

        The words of an insert in a column's name are a column's name. A
        reader that reads them as code reports a row the database never
        holds, and blocks an honest migration.
        """
        code, output = self._rows(
            "select id as 'insert into sync_field_allowlist (entity_type, field) "
            "values (''task'', ''ghostField'')' from sync_events;\n"
        )
        self.assertEqual(0, code, output)

    def test_a_format_call_nothing_runs_is_a_value(self):
        """A format call in a value position builds a string, not a statement."""
        code, output = self._rows(
            "insert into sync_events (note) values\n"
            "    (format('insert into sync_field_allowlist (entity_type, field) "
            "values (%L,%L)', 'task', 'x'));\n"
        )
        self.assertEqual(0, code, output)

    def test_the_servers_own_dynamic_sql_is_not_reported(self):
        """The server writes its own tables through computed names.

        `sync_apply` inserts into the table `sync_table_for` names, and the
        name is computed at run time. The reader must not report that: it
        points at another table, and a report here would fail the
        repository's own history over a shape the server uses on purpose.
        """
        code, output = self._rows(
            "create or replace function public.sync_apply(p_type text)\n"
            "returns void language plpgsql security definer as $fn$\n"
            "declare v_table text;\n"
            "begin\n"
            "  v_table := public.sync_table_for(p_type);\n"
            "  execute format('insert into %1$I (id, owner_id, doc) values ($1, $2, $3)', "
            "v_table) using p_type, p_type, '{}'::jsonb;\n"
            "end;\n"
            "$fn$;\n"
        )
        self.assertEqual(0, code, output)

    def test_a_name_computed_at_run_time_is_beyond_the_reader(self):
        """The one case the reader cannot see, pinned on purpose.

        A name the file never spells is a name no reader of that file can
        see: the reader reads SQL, and the name is nowhere in the SQL. The
        gate does not report it, and the rule — the field table is a contract,
        not data — is what refuses such a migration, in review. This test
        pins the limit so that it stays a stated limit and does not become an
        unnoticed hole.
        """
        code, output = self._rows(
            "do $$\n"
            "declare\n"
            "  tbl text;\n"
            "begin\n"
            "  tbl := public.pick_table_name();\n"
            "  execute format("
            "'insert into %I (entity_type, field, writable) values (%L, %L, %L)', "
            "tbl, 'task', 'ghostField', true);\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(0, code, output)


class AssembledNameTest(AllowlistTreeTest):
    """A name assembled out of pieces is the name it spells.

    A migration may write its text the way it likes — pieces put together
    with `||`, pieces handed to a call, a piece written in another
    spelling of a literal. The reader folds the statement before it reads
    it, so the text the pieces spell is the text the migration runs: the
    row is read and named, not passed over.

    And where the reader cannot fold the name — the piece it cannot fold
    is the piece where the name goes missing — the write is reported
    rather than passed over.
    """

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_name_split_by_a_dollar_piece_is_read(self):
        """`$$…$$` is a literal too, and it is a piece of a name."""
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  execute 'insert into sync_' || $q$field_allowlist$q$ || "
            "' (entity_type, field, writable) values ($q$task$q$, $q$ghostField$q$, true)';\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_name_split_by_an_escaped_piece_is_read(self):
        """`E'…'` is a literal too, and it is a piece of a name."""
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  execute 'insert into sync_' || E'field_allowlist' || "
            "' (entity_type, field, writable) values ($q$task$q$, $q$ghostField$q$, true)';\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_name_split_by_a_cast_is_read(self):
        """A cast between two pieces does not break the chain."""
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  execute 'insert into sync_'::text || 'field_allowlist' || "
            "' (entity_type, field, writable) values ($q$task$q$, $q$ghostField$q$, true)';\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_name_the_reader_cannot_fold_is_reported(self):
        """The piece the reader cannot fold is where the name goes missing.

        `'sync_' || v_suffix` — the suffix is a variable, so the reader
        cannot spell the name. The write points at a piece of the name, and
        the scope holds what the piece may be completed with: the reader
        cannot rule out that the write reaches the field table, and says so.
        """
        code, output = self._rows(
            "do $$\n"
            "declare\n"
            "  suffix text;\n"
            "begin\n"
            "  suffix := 'field_allowlist';\n"
            "  execute 'insert into sync_' || suffix;\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("piece of the field table's name", output)

    def test_a_piece_alone_is_not_the_name(self):
        """A word that is a piece of the name is not the name.

        `'field'` is a word, and a file may hold it without meaning the
        table. A write into a piece of the name is reported only when the
        scope holds what the piece may be completed with.
        """
        code, output = self._rows(
            "insert into sync_events (note) values ('field');\n"
        )
        self.assertEqual(0, code, output)

    def test_a_write_into_a_piece_of_our_own_is_not_reported(self):
        """Another table's name is another table's business."""
        code, output = self._rows(
            "insert into sync_field (id) values ('x');\n"
        )
        self.assertEqual(0, code, output)

    def test_a_statement_whose_value_says_insert_is_not_a_write(self):
        """A literal is a value, and reading it as a deed would be wrong.

        The body of a `do` block is code, and the values inside it are
        values of that code: the reader reads them where the body is read.
        """
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  perform 'insert into sync_field_allowlist (entity_type, field) "
            "values (1, 2)';\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(0, code, output)


class UnfinishedStatementTest(AllowlistTreeTest):
    """A statement the database cannot run is reported, not passed over.

    `do $$ begin execute 'insert into ' || $$name$$ …` — the inner `$$`
    closes the outer block, and what is left is a statement the database
    cannot run. The migration does not apply, so the rows it holds are not
    rows the server will have.

    The check reads the statement as the migration runs it, with its values
    in place: in the mask every value is blank, and an assignment whose
    value is blanked looks like an expression nobody finished.
    """

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_statement_that_ends_in_the_middle_of_an_expression_is_reported(self):
        code, output = self._rows(
            "do $$ begin execute 'insert into ' || $$name$$ ; end $$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("middle of an expression", output)

    def test_an_assignment_with_a_value_is_not_an_unfinished_expression(self):
        """`using errcode = '42501'` is a finished statement.

        The value is there, and the reader reads the statement as the
        migration runs it. A mask would blank the value, and the assignment
        would look unfinished — which would fail the repository's own
        migrations over a shape PostgreSQL writes every day.
        """
        code, output = self._rows(
            "do $$ begin raise exception 'not authenticated' using errcode = '42501'; end $$;\n"
        )
        self.assertEqual(0, code, output)

    def test_a_write_whose_target_is_a_string_is_reported(self):
        """`insert into 'sync_field_allowlist'` cannot run.

        The database does not accept a string where a table's name stands, so
        the migration does not apply — and the file spells the name all the
        same. The reader says so rather than passes the statement over.
        """
        code, output = self._rows(
            "insert into 'sync_field_allowlist' (entity_type, field, writable) "
            "values ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("written as a string", output)

    def test_a_write_whose_target_is_a_string_assembled_from_pieces_is_reported(self):
        code, output = self._rows(
            "insert into 'sync_field_' || 'allowlist' (entity_type, field, writable) "
            "values ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("written as a string", output)

    def test_another_table_written_as_a_string_is_not_reported(self):
        code, output = self._rows("insert into 'other_table' (id) values (1);\n")
        self.assertEqual(0, code, output)


class ExportShapeTest(AllowlistTreeTest):
    """An export the gate cannot read is reported, not a crash."""

    def test_an_export_without_the_field_lists_is_reported(self):
        self.write_seed(GENERATED)
        self.export.write_text(json.dumps({"version": 1}), encoding="utf-8")
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("cannot be read", output)

    def test_an_export_that_is_not_json_is_reported(self):
        self.write_seed(GENERATED)
        self.export.write_text("{not json", encoding="utf-8")
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("cannot be read", output)

    def test_an_entry_without_a_server_list_is_reported(self):
        self.write_seed(GENERATED)
        self.export.write_text(
            json.dumps({"version": 1, "docTypes": {"task": {"wire": ["id"]}}}),
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("cannot be read", output)


class PrefixedLiteralTest(AllowlistTreeTest):
    """A literal prefix does not change what a block of text is.

    `execute E'…'`, `do E'…'`, `prepare p as E'…'` — the `E` stands between
    the keyword and the quote, and the block is a statement the migration runs
    all the same. The prefix is part of the literal's spelling, not of the
    text around it.
    """

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_block_the_migration_runs_is_read_through_an_escaped_prefix(self):
        code, output = self._rows(
            "execute E'insert into sync_field_allowlist (entity_type, field, writable) "
            "values (''task'', ''ghostField'', true)';\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_body_the_migration_runs_is_read_through_an_escaped_prefix(self):
        code, output = self._rows(
            "do E'begin insert into sync_field_allowlist (entity_type, field, writable) "
            "values (''task'', ''ghostField'', true); end';\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_function_body_is_read_through_an_escaped_prefix(self):
        code, output = self._rows(
            "create function f() returns void language plpgsql as "
            "E'begin insert into sync_field_allowlist (entity_type, field, writable) "
            "values (''task'', ''ghostField'', true); end';\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_prepared_statement_is_read_through_an_escaped_prefix(self):
        code, output = self._rows(
            "prepare p as E'insert into sync_field_allowlist (entity_type, field, "
            "writable) values (''task'', ''ghostField'', true)';\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_row_written_by_an_escaped_string_is_read(self):
        """An E-string's quotes are backslash-escaped, and the reader reads them.

        `E'insert into … values (\\'task\\',\\'ghostField\\')'` is the only way
        to write a row of text in an E-string, so a reader that does not
        decode the escapes blocks an honest migration.
        """
        code, output = self._rows(
            "do $$ begin execute E'insert into sync_field_allowlist (entity_type, "
            "field, writable) values (\\'task\\', \\'ghostField\\', true)'; end $$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_prefixed_literal_in_a_value_position_is_a_value(self):
        """The prefix is the literal's spelling, and a value is a value."""
        code, output = self._rows(
            "insert into sync_events (note) values "
            "(E'insert into sync_field_allowlist values (1)');\n"
        )
        self.assertEqual(0, code, output)

    def test_a_bit_string_that_is_not_a_bit_string_is_reported(self):
        """`B'…'` is a bit string, and the migration does not apply.

        The text is read as text all the same — a reader that reads less
        than it can sees less than there is — and the literal is reported,
        because the migration that holds it does not run.
        """
        code, output = self._rows(
            "execute B'insert into sync_field_allowlist (entity_type, field, writable) "
            "values (''task'', ''ghostField'', true)';\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot read", output)


class StringTargetSpellingTest(AllowlistTreeTest):
    """A write whose target is a string is reported in every spelling.

    `insert into 'sync_field_allowlist'` cannot run: the database does not
    accept a string where a table's name stands, so the migration does not
    apply — and the file spells the name all the same. The same statement in
    another spelling of a string — `E'…'`, `B'…'`, `N'…'`, `X'…'`, `U&'…'`,
    `$$…$$`, `$tag$…$tag$` — cannot run either, and it spells the name all
    the same. A reader that reads one spelling reads one way of writing a
    name down, and every other way is a hole in it.
    """

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def _assert_reported(self, spelling: str):
        code, output = self._rows(
            f"insert into {spelling} (entity_type, field, writable) "
            "values ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("written as a string", output)

    def test_a_plain_quoted_target_is_reported(self):
        self._assert_reported("'sync_field_allowlist'")

    def test_an_escaped_target_is_reported(self):
        self._assert_reported("E'sync_field_allowlist'")

    def test_a_bit_string_target_is_reported(self):
        self._assert_reported("B'sync_field_allowlist'")

    def test_a_national_character_target_is_reported(self):
        self._assert_reported("N'sync_field_allowlist'")

    def test_a_hexadecimal_target_is_reported(self):
        self._assert_reported("X'sync_field_allowlist'")

    def test_a_unicode_target_is_reported(self):
        self._assert_reported("U&'sync_field_allowlist'")

    def test_a_dollar_quoted_target_is_reported(self):
        self._assert_reported("$$sync_field_allowlist$$")

    def test_a_tagged_dollar_quoted_target_is_reported(self):
        self._assert_reported("$name$sync_field_allowlist$name$")

    def test_another_table_in_another_spelling_is_not_reported(self):
        code, output = self._rows(
            "insert into E'other_table' (entity_type, field, writable) "
            "values ('task', 'ghostField', true);\n"
        )
        self.assertEqual(0, code, output)

    def test_an_escaped_target_is_read_the_way_the_database_reads_it(self):
        """`E'sync\\_field_allowlist'` holds `sync_field_allowlist`.

        PostgreSQL drops the backslash of an escape it does not know, so the
        string holds the table's name — and the reader that compared the
        spelling would compare a name the database never sees.
        """
        code, output = self._rows(
            "insert into E'sync\\_field_allowlist' (entity_type, field, writable) "
            "values ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("written as a string", output)

    def test_a_unicode_escaped_target_is_read_the_way_the_database_reads_it(self):
        """`U&'\\0073ync_field_allowlist'` holds `sync_field_allowlist`.

        The unicode spelling writes a letter as four hex digits, and the
        reader decodes them the way the database does.
        """
        code, output = self._rows(
            "insert into U&'\\0073ync_field_allowlist' (entity_type, field, writable) "
            "values ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("written as a string", output)

    def test_a_stored_string_that_holds_the_words_of_a_write_is_not_reported(self):
        """A column holds words, and the words are not a write.

        `values ('example: insert into sync_field_allowlist …')` stores the
        words of a write in a column. The verb is inside the string there,
        not before it, and the statement runs.
        """
        code, output = self._rows(
            "insert into audit_log (msg) values "
            "('example: insert into $$sync_field_allowlist$$ (x)');\n"
        )
        self.assertEqual(0, code, output)

    def test_a_stored_string_that_holds_a_quoted_write_is_not_reported(self):
        code, output = self._rows(
            "insert into audit_log (msg) values "
            "('example: insert into ''sync_field_allowlist'' (x)');\n"
        )
        self.assertEqual(0, code, output)


class InheritanceDoorTest(AllowlistTreeTest):
    """A table that inherits from the field table is a second door into it.

    PostgreSQL returns the rows of a child when the parent is queried, so a
    row written into the child is a row the server's own lookup finds — the
    server reads `sync_field_allowlist`, and the child's rows stand in it.
    The reader never sees that row: it is written under the child's name.
    """

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_table_that_inherits_from_the_field_table_is_reported(self):
        code, output = self._rows(
            "create table sync_field_allowlist_child () "
            "inherits (sync_field_allowlist);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("inherits", output)

    def test_a_table_that_inherits_from_the_qualified_field_table_is_reported(self):
        code, output = self._rows(
            "create table sync_field_allowlist_child () "
            "inherits (public.sync_field_allowlist);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("inherits", output)

    def test_a_partition_of_the_field_table_is_reported(self):
        code, output = self._rows(
            "create table sync_field_allowlist_child partition of sync_field_allowlist;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("inherits", output)

    def test_a_table_given_the_field_table_as_a_parent_is_reported(self):
        code, output = self._rows(
            "alter table sync_field_allowlist_child inherit sync_field_allowlist;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("inherits", output)

    def test_a_table_that_inherits_from_another_table_is_not_reported(self):
        code, output = self._rows(
            "create table other_child () inherits (other_table);\n"
        )
        self.assertEqual(0, code, output)

    def test_a_table_that_stops_inheriting_is_not_reported(self):
        code, output = self._rows(
            "alter table sync_field_allowlist_child no inherit sync_field_allowlist;\n"
        )
        self.assertEqual(0, code, output)

    def test_a_table_shaped_like_the_field_table_is_not_reported(self):
        """A `like` copies the shape of a table and not its rows.

        The server looks the field table up by its own name, so a table that
        holds the same columns under another name is another table's
        business.
        """
        code, output = self._rows(
            "create table sync_field_allowlist_copy (like sync_field_allowlist "
            "including all);\n"
        )
        self.assertEqual(0, code, output)


class WriteTextInAVariableTest(AllowlistTreeTest):
    """A write text carried in a variable and run later is reported.

    `v_sql := 'insert into sync_field_allowlist …'; execute v_sql;` — the
    reader cannot follow the variable, so it reports the statement the
    migration runs. The scope that holds the write text is a scope where the
    table's name is in play: the text may be the text that is run.
    """

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_write_text_carried_in_a_variable_is_reported(self):
        code, output = self._rows(
            "do $$\n"
            "declare\n"
            "  v_sql text;\n"
            "begin\n"
            "  v_sql := 'insert into sync_field_allowlist (entity_type, field, writable) "
            "values (''task'', ''ghostField'', true)';\n"
            "  execute v_sql;\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("whose target the gate cannot tell", output)

    def test_a_write_text_assembled_in_a_variable_is_reported(self):
        code, output = self._rows(
            "do $$\n"
            "declare\n"
            "  v_sql text;\n"
            "begin\n"
            "  v_sql := 'insert into ' || 'sync_field_allowlist' || "
            "' (entity_type, field, writable) values (''task'', ''ghostField'', true)';\n"
            "  execute v_sql;\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("whose target the gate cannot tell", output)

    def test_a_read_text_carried_in_a_variable_is_not_reported(self):
        """A scope that only READS the table spells no write.

        This is the shape the server's own migrations use: the text of the
        check the server runs is held in a variable, and the table is only
        read there.
        """
        code, output = self._rows(
            "do $$\n"
            "declare\n"
            "  v_allowed text;\n"
            "begin\n"
            "  v_allowed := 'exists (select 1 from sync_field_allowlist a "
            "where a.entity_type = $2 and a.field = op ->> ''field'' and a.writable)';\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(0, code, output)

    def test_a_write_text_held_in_the_enclosing_body_is_reported(self):
        """A nested body sees the scope it is nested in.

        A name the enclosing body holds is a name the nested body can write
        with, and a write text the enclosing body holds is a write text the
        nested body may run.
        """
        code, output = self._rows(
            "do $$\n"
            "declare\n"
            "  v_sql text;\n"
            "begin\n"
            "  v_sql := 'insert into sync_field_allowlist (entity_type, field, writable) "
            "values (''task'', ''ghostField'', true)';\n"
            "  do $inner$ begin execute v_sql; end $inner$;\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("whose target the gate cannot tell", output)

    def test_a_nested_body_that_only_reads_is_not_reported(self):
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  do $inner$ begin perform (select count(*) from sync_field_allowlist); "
            "end $inner$;\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(0, code, output)

    def test_a_write_text_run_through_a_parenthesised_target_is_reported(self):
        """`execute (v_sql)` runs the text as surely as `execute v_sql` does."""
        code, output = self._rows(
            "do $$\n"
            "declare\n"
            "  v_sql text;\n"
            "begin\n"
            "  v_sql := 'insert into sync_field_allowlist (entity_type, field, writable) "
            "values (''task'', ''ghostField'', true)';\n"
            "  execute (v_sql);\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("whose target the gate cannot tell", output)

    def test_a_write_text_stored_in_a_row_and_run_later_is_reported(self):
        """The text may live in a row and be read back at run time.

        The reader cannot follow the row into the variable, so the statement
        that runs the variable is reported: a file that holds the text of a
        write into the table is a file where the table's name is in play.
        """
        code, output = self._rows(
            "insert into sync_events (data) values ('{\"sql\": \"insert into "
            "sync_field_allowlist (entity_type, field, writable) values "
            "(''task'', ''ghostField'', true)\"}');\n"
            "do $$\n"
            "declare\n"
            "  v text;\n"
            "begin\n"
            "  select data->>'sql' into v from sync_events limit 1;\n"
            "  execute v;\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("whose target the gate cannot tell", output)

    def test_a_write_text_handed_to_a_call_through_a_variable_is_reported(self):
        """A call may run what it is handed, and the reader cannot know."""
        code, output = self._rows(
            "create function my_exec(t text) returns void language plpgsql as $$\n"
            "begin execute t; end $$;\n"
            "do $$\n"
            "declare\n"
            "  v text;\n"
            "begin\n"
            "  v := 'insert into sync_field_allowlist (entity_type, field, writable) "
            "values (''task'', ''ghostField'', true)';\n"
            "  perform my_exec(v);\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot read", output)

    def test_a_column_list_is_not_a_call(self):
        """`insert into t (a, b)` is a table and its columns, not a call."""
        code, output = self._rows(
            "insert into sync_events (id, note) values ('x', 'y');\n"
        )
        self.assertEqual(0, code, output)


class LanguageClauseTest(AllowlistTreeTest):
    """`DO [ LANGUAGE lang_name ] code` — the code is a body all the same.

    PostgreSQL's grammar puts an optional language clause between the keyword
    and the code. The code runs, so the reader reads it.
    """

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_body_after_a_language_clause_is_read(self):
        code, output = self._rows(
            "DO LANGUAGE plpgsql $$ BEGIN INSERT INTO sync_field_allowlist "
            "(entity_type, field) VALUES ('task', 'ghostField'); END $$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_string_body_after_a_language_clause_is_read(self):
        code, output = self._rows(
            "DO LANGUAGE plpgsql 'BEGIN INSERT INTO sync_field_allowlist "
            "(entity_type, field) VALUES (''task'', ''ghostField''); END';\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_quoted_language_name_is_a_body(self):
        code, output = self._rows(
            "DO LANGUAGE 'plpgsql' $$ BEGIN INSERT INTO sync_field_allowlist "
            "(entity_type, field) VALUES ('task', 'ghostField'); END $$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_body_after_a_language_clause_that_only_reads_is_not_reported(self):
        code, output = self._rows(
            "DO LANGUAGE plpgsql $$ BEGIN PERFORM "
            "(select count(*) from sync_field_allowlist); END $$;\n"
        )
        self.assertEqual(0, code, output)


class UnfinishedBlockTest(AllowlistTreeTest):
    """A body whose block was never closed is reported.

    `create function f() … as $$ … ; do $$` — the inner `$$` closes the
    outer one, and what is left is a body whose block was never closed. The
    migration does not apply, so the rows it holds are not rows the server
    will have.
    """

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_body_that_ends_in_the_middle_of_a_block_is_reported(self):
        code, output = self._rows(
            "create function f() returns void as $$ declare v text; begin "
            "v := 'insert into sync_field_allowlist (entity_type, field) "
            "values (''task'', ''ghostField'')'; do $$ begin execute v; end $$; "
            "end $$ language plpgsql;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("middle of a block", output)

    def test_a_body_that_ends_cleanly_is_not_reported(self):
        code, output = self._rows(
            "create function f() returns void as $$ begin perform "
            "(select count(*) from sync_field_allowlist); end $$ language plpgsql;\n"
        )
        self.assertEqual(0, code, output)


class SecondDoorTest(AllowlistTreeTest):
    """The doors into the table that are not the table itself."""

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_view_over_the_table_is_reported(self):
        """A view is a second door into the table.

        PostgreSQL writes through an automatically updatable view, so a row
        inserted into the view lands in the table the server reads — under a
        name the reader cannot see, because the row names the view.
        """
        code, output = self._rows(
            "create view sync_allowlist_v as select * from sync_field_allowlist;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("view over the field table", output)

    def test_a_read_only_view_over_the_table_is_not_reported(self):
        """A view PostgreSQL does not update is no door into the table.

        A view over an aggregate, or over a join, is read-only as far as the
        database is concerned: nothing can be inserted through it, so it is
        another table's business.
        """
        code, output = self._rows(
            "create view sync_allowlist_count as "
            "select entity_type, count(*) from sync_field_allowlist group by entity_type;\n"
        )
        self.assertEqual(0, code, output)

    def test_a_write_into_a_view_is_reported(self):
        """The view is reported, and the write into it does not slip by."""
        code, output = self._rows(
            "create view sync_allowlist_v as select * from sync_field_allowlist;\n"
            "insert into sync_allowlist_v (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("view over the field table", output)

    def test_a_write_through_a_view_in_a_do_block_is_reported(self):
        """The same view, reached through dynamic SQL."""
        code, output = self._rows(
            "create view sync_allowlist_v as select * from sync_field_allowlist;\n"
            "do $$\n"
            "begin\n"
            "  execute 'insert into sync_allowlist_v (entity_type, field, writable) "
            "values (''task'', ''ghostField'', true)';\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("view over the field table", output)

    def test_a_string_that_spells_a_write_and_is_handed_to_a_call_is_reported(self):
        """A call may run the text it is handed.

        `dblink_exec` runs the statement it is given, in another database —
        and the row it writes lands in the table the server reads.
        """
        code, output = self._rows(
            "select dblink_exec('conn', 'insert into sync_field_allowlist (entity_type, "
            "field, writable) values (''task'', ''ghostField'', true)');\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("handed to a call", output)

    def test_a_string_that_spells_a_write_and_is_stored_is_not_reported(self):
        """A column holds words, and the words are not a row.

        The reader does not read the string as a statement — there is no row
        the database would hold — and it does not report it either: nothing
        runs it.
        """
        code, output = self._rows(
            "insert into sync_events (note) values\n"
            "    ('insert into sync_field_allowlist values (1)');\n"
        )
        self.assertEqual(0, code, output)

    def test_a_message_that_mentions_the_table_is_not_reported(self):
        """A name inside a text that holds more than the name is not a name."""
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  raise notice 'table sync_field_allowlist';\n"
            "  execute 'select 1';\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(0, code, output)

    def test_a_comparison_with_the_name_is_not_reported(self):
        """A name compared with something is a comparison, not a name in play."""
        code, output = self._rows(
            "do $$\n"
            "begin\n"
            "  if tg_table_name = 'sync_field_allowlist' then execute 'select 1'; end if;\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(0, code, output)

    def test_a_name_read_into_a_variable_is_reported(self):
        """The name is spelled in the file, so it is a name in play.

        A read OUT of the table says nothing about where a write points; a
        read of the NAME is the name itself, and a write through it is a
        write the gate cannot rule out.
        """
        code, output = self._rows(
            "do $$\n"
            "declare t text;\n"
            "begin\n"
            "  select 'sync_field_allowlist' into t;\n"
            "  execute 'insert into ' || t || ' (entity_type, field, writable) values "
            "(''task'', ''ghostField'', true)';\n"
            "end\n"
            "$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot tell", output)

    def test_a_dollar_quoted_write_handed_to_a_call_is_reported(self):
        """The other spelling of a string: `$$...$$`.

        The reader can decode both, so it reports both — the spelling is not
        what decides whether the call runs the text.
        """
        code, output = self._rows(
            "select dblink_exec('conn', $q$insert into sync_field_allowlist (entity_type, "
            "field, writable) values ('task','ghost',true)$q$);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("handed to a call", output)

    def test_a_name_held_in_another_statement_of_the_file_is_reported(self):
        """The name is data the file hands around, and a write may take it.

        The file spells the name as a literal — in a row it inserts — and a
        write in another statement reads that row back at run time. The
        reader cannot follow the data, so the write is reported.
        """
        code, output = self._rows(
            "insert into deploy_config (key, value) values "
            "('allowlist_table', 'sync_field_allowlist');\n"
            "execute 'insert into ' || (select value from deploy_config where "
            "key = 'allowlist_table') || ' (entity_type, field, writable) values "
            "(''task'', ''ghost'', true)';\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot tell", output)

    def test_a_name_held_in_another_statement_is_reported_in_a_body(self):
        """The same, in a body — the body does not narrow the file."""
        code, output = self._rows(
            "insert into deploy_config (key, value) values "
            "('allowlist_table', 'sync_field_allowlist');\n"
            "do $$\nbegin\n  execute 'insert into ' || (select value from deploy_config) || "
            "' (entity_type, field, writable) values (''task'', ''ghost'', true)';\nend\n$$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot tell", output)

    def test_the_table_renamed_is_reported(self):
        """A renamed table is a table the server no longer looks up."""
        code, output = self._rows(
            "alter table sync_field_allowlist rename to sync_field_allowlist_old;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("renamed or moved", output)

    def test_the_table_moved_to_another_schema_is_reported(self):
        """A table moved out of `public` is the same thing by another name."""
        code, output = self._rows("alter table sync_field_allowlist set schema private;\n")
        self.assertEqual(1, code, output)
        self.assertIn("renamed or moved", output)

    def test_the_table_owner_changed_is_not_reported(self):
        """An owner is the server's business; the table is still the table."""
        code, output = self._rows("alter table sync_field_allowlist owner to service_role;\n")
        self.assertEqual(0, code, output)

    def test_a_value_written_as_a_concatenation_is_read(self):
        """`'task' || ''` is `task`, and the row is the row it spells."""
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task'||'', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_an_unplaceable_write_beside_a_readable_one_is_reported(self):
        """One statement may hold two writes, and one of them may be a row.

        Reading a row of the table says nothing about where the other write
        points, so the other write is reported on its own.
        """
        code, output = self._rows(
            "with a as (insert into sync_field_allowlist (entity_type, field, writable) "
            "values ('task', 'title', true)), b as (insert into $tbl (entity_type, field, "
            "writable) values ('task', 'ghostField', true)) select 1;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot tell", output)


class RowLiteralSpellingTest(AllowlistTreeTest):
    """The spellings PostgreSQL gives a string."""

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_string_continued_across_a_newline_is_one_name(self):
        """PostgreSQL continues a string constant across a newline.

        The whitespace between the parts is dropped, so `'ti'` on the next
        line `'tle'` is `title` — and the reader that keeps the two apart
        reads a name the database never stores, and rejects an honest
        migration.
        """
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ti'\n    'tle', true);\n"
        )
        self.assertEqual(0, code, output)

    def test_a_continued_string_is_reported_under_the_name_it_holds(self):
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghost'\n    'Field', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_an_e_string_continued_across_a_newline_is_one_name(self):
        """The prefix of the first part governs the whole constant.

        `E'ghost'` and the next line `'Field'` is one E-string, and it holds
        `ghostField`.
        """
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', E'ghost'\n    'Field', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_newline_inside_one_pair_of_quotes_is_kept(self):
        """A newline inside the quotes is another thing: PostgreSQL keeps it.

        `'ti` and a newline and `tle'` is one constant of six characters,
        and the name it holds is not a name any model has.
        """
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ti\ntle', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ti\ntle", output)

    def test_a_doubled_quote_belongs_to_its_own_part(self):
        """`''` is a quote inside its part, and never across two parts.

        PostgreSQL reads the two parts as two strings, and each part's quote
        is its own — so the name holds two quotes and not one.
        """
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'a'''\n    '''b', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.a''b", output)

    def test_a_line_comment_between_the_parts_keeps_the_constant_one(self):
        """A line comment may stand between the parts of a constant.

        PostgreSQL reads the two parts as one constant, and the comment is
        not part of it — so the name is the one name the two spell.
        """
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghost' -- a comment\n    'Field', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_block_comment_between_the_parts_keeps_them_apart(self):
        """A block comment may not: PostgreSQL reads that as an error.

        The two parts are two constants then, and the row holds a value the
        server never holds — so the reader says it cannot read the row.
        """
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ti' /* a comment */\n    'tle', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot read", output)

    def test_a_concatenation_with_a_comment_is_folded(self):
        """A comment between two pieces does not stop the folding.

        `'ghost' /* a comment */ || 'Field'` is one name, and the reader
        reads it as the name it spells.
        """
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghost' /* a comment */ || 'Field', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_continued_table_name_is_reported(self):
        """`'sync_field_'` and the next line `'allowlist'` is one name.

        The write cannot run — the database does not accept a string where a
        table's name stands — and the file spells the name all the same.
        """
        code, output = self._rows(
            "insert into 'sync_field_'\n    'allowlist' "
            "(entity_type, field, writable) values ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("written as a string", output)

    def test_a_hex_spelling_of_the_table_name_is_reported(self):
        """`X'...'` spells the name in hex, and the reader reads it.

        A hex string spells four bits with every digit, and the bytes it
        spells are the name — so the write names the table all the same.
        """
        code, output = self._rows(
            "insert into X'73796e635f6669656c645f616c6c6f776c697374' "
            "(entity_type, field, writable) values ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("written as a string", output)

    def test_a_hex_string_is_read(self):
        """`X'...'` holds bits, and PostgreSQL stores those bits as they are.

        A hex string spells four bits with every digit, and a text column
        holds the bits — `X'67686F737446'` is the forty-eight bits, and not
        the six bytes. A reader that read the bytes would read a name the
        server never holds.
        """
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', X'67686F737446', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn(
            "task.011001110110100001101111011100110111010001000110", output
        )

    def test_a_hex_string_of_no_whole_byte_is_read(self):
        """`X'4'` is four bits, and four bits are a text of their own."""
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', X'4', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.0100", output)

    def test_an_e_string_is_read(self):
        """`E'...'` is a string, and a legal row must not block the gate."""
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', E'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_unicode_string_is_read(self):
        """`U&'...'` is a string too."""
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', U&'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_unicode_string_in_lower_case_is_read(self):
        """`u&'...'` is the same spelling, and PostgreSQL reads it."""
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', u&'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_bit_string_is_read(self):
        """`B'01000001'` is eight bits, and a text column holds those bits."""
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', B'01000001', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.01000001", output)

    def test_a_bit_string_of_no_whole_byte_is_read(self):
        """`B'101'` is three bits, and three bits are a text of their own."""
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', B'101', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.101", output)

    def test_an_e_string_that_escapes_a_quote_is_read(self):
        """The scanner has to know the prefix, or the string ends too early."""
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', E'ghost\\'Field', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghost'Field", output)

    def test_a_bit_string_spelling_of_the_table_name_is_reported(self):
        """A bit string spells bytes, and the bytes spell the name.

        The pieces of a name are folded into the name they spell, and a bit
        string spells BYTES — so `'sync_field_' || B'0110…'` spells the
        table's name, and the write the file spells is the write the reader
        sees. The write cannot run — the database does not accept a string
        where a table's name stands — and the file spells the name all the
        same.
        """
        code, output = self._rows(
            "insert into 'sync_field_' || B'01100001011011000110110001101111"
            "0111011101101100011010010111001101110100' "
            "(entity_type, field, writable) values ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("written as a string", output)

    def test_a_hex_string_spelling_of_the_table_name_is_reported(self):
        """A hex string spells bytes as well, and the bytes spell the name."""
        code, output = self._rows(
            "insert into 'sync_field_' || X'616C6C6F776C697374' "
            "(entity_type, field, writable) values ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("written as a string", output)

    def test_a_call_that_builds_the_name_in_hex_is_reported(self):
        """`concat('sync_field_', X'616C…')` builds the name all the same."""
        code, output = self._rows(
            "insert into concat('sync_field_', X'616C6C6F776C697374') "
            "(entity_type, field, writable) values ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("written as a string", output)

    def test_the_value_of_a_row_is_the_value_the_pieces_hold(self):
        """A row holds a value, and a bit string holds bits.

        The folding answers what NAME a run of pieces spells; a row holds a
        VALUE, and the value a bit string holds is the bits — the text the
        database stores. A reader that read the bytes of a row's value would
        read a value the server never holds.
        """
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', B'01100001011011000110110001101111', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.01100001011011000110110001101111", output)
        self.assertNotIn("task.allo", output)

    def test_a_value_joined_with_a_bit_string_is_not_a_value(self):
        """`||` on a bit string joins bits, and a text is not bits.

        PostgreSQL reads `'ghost' || B'0110…'` as a concatenation of bits and
        refuses the text that is not bits — so the row holds no value the
        server stores, and the reader says it cannot read the row.
        """
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghost' || B'01100001011011000110110001101111', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("cannot read", output)

    def test_a_statement_written_as_a_bit_string_is_reported(self):
        """The text the migration runs is bits, and the bits are not a statement.

        PostgreSQL runs the bits of a bit string: `execute X'…'` runs the bits
        the hex string holds, and that is not a statement — so the migration
        does not apply, and the rows it holds are not rows the server will
        have. The reader says so rather than reads the bytes the same literal
        spells.
        """
        code, output = self._rows(
            "do $$ begin execute X'696e7365727420696e746f2073796e635f6669656c64"
            "5f616c6c6f776c697374'; end $$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("the database runs the bits", output)

    def test_a_statement_written_as_a_bit_string_is_not_a_row(self):
        """The reader does not read a row out of the bytes a bit string spells."""
        code, output = self._rows(
            "do $$ begin execute B'01100001011011000110110001101111'; end $$;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("the database runs the bits", output)
        self.assertNotIn("task.allo", output)

    def test_a_unicode_prefix_that_is_part_of_a_name_is_not_a_prefix(self):
        """`abcu&'…'` is a name and a string, not a U& string.

        PostgreSQL reads `abcu` as an identifier and the `&` as an operator,
        so the string is a plain one and its escapes are not decoded:
        `u&'sync\\005Ffield_allowlist'` is the table's name, and
        `abcu&'sync\\005Ffield_allowlist'` is not.
        """
        code, output = self._rows(
            "insert into abcu&'sync\\005Ffield_allowlist' "
            "(entity_type, field, writable) values ('task', 'ghostField', true);\n"
        )
        self.assertEqual(0, code, output)

    def test_a_unicode_string_is_read_through_its_prefix(self):
        """`u&'…'` is the same spelling, and PostgreSQL decodes its escapes."""
        code, output = self._rows(
            "insert into u&'sync\\005Ffield_allowlist' "
            "(entity_type, field, writable) values ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("written as a string", output)

    def test_an_escaped_backslash_in_a_unicode_string_is_read(self):
        """`\\\\` is a backslash, and what follows it is not an escape.

        PostgreSQL reads `U&'evil\\\\0046ield'` as `evil\\0046ield`: the
        doubled backslash is one backslash, and the digits behind it are
        text. A reader that decoded them would read a value the server does
        not hold.
        """
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', U&'evil\\\\0046ield', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.evil\\0046ield", output)

    def test_an_insert_under_an_alias_is_read(self):
        """`insert into sync_field_allowlist as s (…)` writes the same rows.

        The alias names the table for the statement and says nothing about
        the rows — so a reader that stopped at the alias would report a row
        it could read.
        """
        code, output = self._rows(
            "insert into sync_field_allowlist as s (entity_type, field, writable) "
            "values ('task', 'title', true);\n"
        )
        self.assertEqual(0, code, output)

    def test_a_value_with_two_casts_is_read(self):
        """`'task'::text::text` is a text, and the reader reads it as one."""
        code, output = self._rows(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task'::text::text, 'title'::text, true);\n"
        )
        self.assertEqual(0, code, output)

    def test_a_format_that_quotes_its_arguments_is_read(self):
        """`format('%L', …)` builds a literal of its argument.

        PostgreSQL quotes the argument — `format('… %L', 'task')` builds
        `'task'` — and a reader that read the bare word would read a text
        the server never runs.
        """
        code, output = self._rows(
            "execute format('insert into sync_field_allowlist "
            "(entity_type, field, writable) values (%L, %L, %L)', "
            "'task', 'title', 'true');\n"
        )
        self.assertEqual(0, code, output)


class TableItselfTest(AllowlistTreeTest):
    """Statements that make the table, rather than write rows into it."""

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_drop_of_the_table_is_reported(self):
        code, output = self._rows("drop table if exists sync_field_allowlist;\n")
        self.assertEqual(1, code, output)
        self.assertIn("does not model", output)

    def test_a_table_created_out_of_a_query_is_reported(self):
        """The rows come from the query, and the statement names no field."""
        code, output = self._rows(
            "create table sync_field_allowlist as\n"
            "select 'task', 'ghostField', true;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("does not model", output)

    def test_a_with_clause_in_front_of_an_insert_is_not_a_table_from_a_query(self):
        """The `into` belongs to the insert."""
        code, output = self._rows(
            "with src as (select 1)\n"
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)

    def test_a_default_with_a_cast_is_the_same_default(self):
        code, output = self._rows(
            "create table if not exists sync_field_allowlist (\n"
            "    entity_type text not null,\n"
            "    field text not null,\n"
            "    writable boolean not null default true::boolean\n"
            ");\n"
        )
        self.assertEqual(0, code, output)

    def test_a_table_created_with_a_column_list_is_not_a_write(self):
        """An empty table, and `if not exists` makes it nothing at all."""
        code, output = self._rows(
            "create table if not exists sync_field_allowlist (\n"
            "    entity_type text not null,\n"
            "    field text not null,\n"
            "    writable boolean not null default true\n"
            ");\n"
        )
        self.assertEqual(0, code, output)

    def test_a_drop_of_the_schema_is_reported(self):
        """The schema takes the table with it."""
        code, output = self._rows("drop schema public cascade;\n")
        self.assertEqual(1, code, output)
        self.assertIn("does not model", output)

    def test_a_change_of_the_column_default_is_reported(self):
        """The reader takes the column's default for a row that omits the flag.

        An ALTER that changes that default changes what such a row means, and
        the reader has to be told.
        """
        code, output = self._rows(
            "alter table sync_field_allowlist alter column writable set default false;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("does not model", output)

    def test_a_table_declared_with_another_default_is_reported(self):
        """The reader takes the column's default for a row that omits the flag."""
        code, output = self._rows(
            "create table if not exists sync_field_allowlist (\n"
            "    entity_type text not null,\n"
            "    field text not null,\n"
            "    writable boolean not null default false\n"
            ");\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("does not model", output)

    def test_a_select_into_the_table_is_reported(self):
        """`select ... into` is the third way PostgreSQL makes a table."""
        code, output = self._rows(
            "select 'task', 'ghostField', true\n"
            "into sync_field_allowlist;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("does not model", output)

    def test_an_alter_that_changes_nothing_is_not_reported(self):
        code, output = self._rows(
            "alter table sync_field_allowlist enable row level security;\n"
        )
        self.assertEqual(0, code, output)


class TableNameTest(AllowlistTreeTest):
    """The table of the gate is one table, and the reader knows which one."""

    def _rows(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_a_table_of_the_same_name_in_another_schema_is_not_ours(self):
        code, output = self._rows(
            "insert into other.sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', true);\n"
        )
        self.assertEqual(0, code, output)

    def test_a_quoted_name_with_another_case_is_not_ours(self):
        """PostgreSQL keeps a quoted name as it is written, so this is another table."""
        code, output = self._rows(
            'insert into "SYNC_FIELD_ALLOWLIST" (entity_type, field, writable) values\n'
            "    ('task', 'ghostField', true);\n"
        )
        self.assertEqual(0, code, output)

    def test_a_quoted_name_in_our_own_case_is_ours(self):
        code, output = self._rows(
            'insert into "sync_field_allowlist" (entity_type, field, writable) values\n'
            "    ('task', 'ghostField', true);\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("task.ghostField", output)


class UnmodelledStatementTest(AllowlistTreeTest):
    """A statement that renames a row or empties the table is reported."""

    def _with(self, statement: str):
        self.write_seed(GENERATED)
        self.migration.write_text(SERVER_MIGRATION + statement, encoding="utf-8")
        code, output = self.run_gate()
        return code, output

    def test_an_update_of_the_table_is_reported(self):
        code, output = self._with(
            "update sync_field_allowlist set field = 'renamed' where entity_type = 'task';\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("does not model", output)

    def test_a_truncate_of_the_table_is_reported(self):
        code, output = self._with("truncate table sync_field_allowlist;\n")
        self.assertEqual(1, code, output)
        self.assertIn("does not model", output)

    def test_a_merge_into_the_table_is_reported(self):
        code, output = self._with(
            "merge into sync_field_allowlist using sync_field_allowlist on true\n"
            "when matched then update set field = 'renamed';\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("does not model", output)

    def test_an_upsert_that_renames_a_row_is_reported(self):
        code, output = self._with(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'title', true)\n"
            "on conflict (entity_type, field) do update set field = 'renamed';\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("does not model", output)

    def test_an_upsert_that_renames_a_row_is_reported_however_it_is_laid_out(self):
        """The ordinary two-line spelling is the same statement."""
        code, output = self._with(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'title', true)\n"
            "on conflict (entity_type, field)\n"
            "do update set writable = false;\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("does not model", output)

    def test_an_upsert_of_another_table_is_not_the_gate_s_business(self):
        """The pattern is scoped to the table, like the other four."""
        code, output = self._with(
            "insert into sync_events (owner_id, patch_id) values ('a', 'b')\n"
            "on conflict (owner_id, patch_id) do update set result = 1;\n"
        )
        self.assertEqual(0, code, output)

    def test_an_upsert_of_another_table_in_the_same_statement_is_not_ours(self):
        """A data-modifying CTE writes another table; our insert is ours."""
        code, output = self._with(
            "with e as (insert into sync_events (a, b) values (1, 2)\n"
            "on conflict (a) do update set b = 3)\n"
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'title', true);\n"
        )
        self.assertEqual(0, code, output)

    def test_a_delete_of_the_table_is_reported(self):
        """A delete takes a field away from the server, and the reader cannot see it."""
        code, output = self._with(
            "delete from sync_field_allowlist where entity_type = 'task' and field = 'title';\n"
        )
        self.assertEqual(1, code, output)
        self.assertIn("does not model", output)


class MigrationNameTest(AllowlistTreeTest):
    """A file the deploy tooling cannot order is a file the gate must not skip."""

    def test_a_migration_file_without_a_date_is_reported(self):
        self.write_seed(GENERATED)
        (self.migrations / "add_a_field.sql").write_text(
            "insert into sync_field_allowlist (entity_type, field, writable) values\n"
            "    ('task', 'ghostField', true)\n"
            "on conflict (entity_type, field) do nothing;\n",
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("carries no date", output)


if __name__ == "__main__":
    unittest.main()
