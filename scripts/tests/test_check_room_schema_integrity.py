"""Tests for check-room-schema-integrity.py.

The gate exists because this project shipped a build that crashed for every user
with an existing database. `SyncColumns.server_version` was added to
`sync_shadow`; the exported schema moved to 37 while `SCHEMA_VERSION` stayed at
36 and no migration existed. Room's identity-hash check then failed at first
query. No test saw it, because every test creates its own database and a fresh
database has no identity to mismatch.

That failure mode — a gate that passes while the thing it guards is broken — is
the one these tests exist to prevent, so the emphasis is not on "the rules
return violations" but on the parsers still reading the shapes they were
written against. A regex that stops matching reports success having checked
nothing, and that is invisible from the outside: the gate goes green on a tree
where every migration entry has been deleted.

The parsers are therefore tested against text that differs from the real files
in whitespace, ordering and formatting, because the real files are the only
sample that happens to be lying around and a parser fitted to one file is a
parser that breaks on the next edit.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-room-schema-integrity.py'
spec = importlib.util.spec_from_file_location('check_room_schema_integrity', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
# `@dataclass` resolves annotations through `sys.modules[cls.__module__]`, which
# is None when the module was never registered. Without this line `exec_module`
# raises AttributeError before a single test runs.
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)


def write(path: pathlib.Path, text: str) -> pathlib.Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')
    return path


class SchemaVersionParsingTest(unittest.TestCase):
    """`SCHEMA_VERSION` is the anchor every other rule hangs off."""

    def test_named_constant(self):
        self.assertEqual(mod.parse_schema_version('const val SCHEMA_VERSION = 37\n'), 37)

    def test_public_constant(self):
        self.assertEqual(mod.parse_schema_version('public const val SCHEMA_VERSION = 4\n'), 4)

    def test_inline_literal_in_database_annotation(self):
        # A tree that inlines the number must still be checked, not silently
        # treated as unparseable — "cannot read the version" and "the version
        # is fine" must never produce the same outcome.
        source = '@Database(\n    entities = [],\n    version = 12,\n)\nabstract class AppDatabase\n'
        self.assertEqual(mod.parse_schema_version(source), 12)

    def test_named_constant_wins_over_inline(self):
        source = 'const val SCHEMA_VERSION = 41\n@Database(version = 40)\n'
        self.assertEqual(mod.parse_schema_version(source), 41)

    def test_absent_version_is_none(self):
        self.assertIsNone(mod.parse_schema_version('class AppDatabase\n'))


class AutoMigrationParsingTest(unittest.TestCase):
    def test_single_line_form(self):
        source = 'AutoMigration(from = 36, to = 37, spec = Migration36To37::class),'
        self.assertEqual(mod.parse_auto_migrations(source), {(36, 37)})

    def test_multiline_form(self):
        source = 'AutoMigration(\n    from = 12,\n    to = 13,\n    spec = Migration12To13::class,\n),'
        self.assertEqual(mod.parse_auto_migrations(source), {(12, 13)})

    def test_many_entries(self):
        source = '\n'.join(
            f'        AutoMigration(from = {n}, to = {n + 1}, spec = Migration{n}To{n + 1}::class),'
            for n in range(5, 10)
        )
        self.assertEqual(mod.parse_auto_migrations(source), {(n, n + 1) for n in range(5, 10)})

    def test_spec_class_recovered(self):
        source = 'AutoMigration(from = 31, to = 32, spec = Migration31To32::class),'
        self.assertEqual(mod.parse_spec_classes(source)[(31, 32)], 'Migration31To32')

    def test_non_matching_text_ignored(self):
        self.assertEqual(mod.parse_auto_migrations('// AutoMigration is a Room annotation\n'), set())


class ManualMigrationParsingTest(unittest.TestCase):
    """The `addMigrations` arguments contain nested calls.

    A `[^)]*` capture reads the argument list as empty, because it stops at the
    paren of `Migration31To32()`. That bug shipped in the first draft of this
    gate: the rule reported "not registered in addMigrations (found: none)" for
    a factory that did register it, which would have trained everyone to ignore
    the finding.
    """

    FACTORY = (
        'package com.singularity.todo.core.database\n'
        'object AppDatabaseFactory {\n'
        '    fun build() = Room.databaseBuilder<AppDatabase>(name = "x")\n'
        '        .addMigrations(Migration31To32())\n'
        '        .build()\n'
        '}\n'
    )

    def test_single_argument_with_nested_parens(self):
        self.assertEqual(mod.parse_manual_migrations(self.FACTORY), {'Migration31To32'})

    def test_multiple_arguments(self):
        source = self.FACTORY.replace('Migration31To32()', 'Migration31To32(), Migration5To6()')
        self.assertEqual(mod.parse_manual_migrations(source), {'Migration31To32', 'Migration5To6'})

    def test_arguments_spanning_lines(self):
        source = 'Room.databaseBuilder<AppDatabase>(name = "x")\n    .addMigrations(\n        Migration31To32(),\n    )\n    .build()\n'
        self.assertEqual(mod.parse_manual_migrations(source), {'Migration31To32'})

    def test_absent_add_migrations(self):
        self.assertEqual(mod.parse_manual_migrations('fun build() = Room.databaseBuilder<X>("x").build()\n'), set())


class ExportedSchemaTest(unittest.TestCase):
    def test_versions_from_filenames(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            for n in (4, 5, 37):
                (d / f'{n}.json').write_text('{}')
            (d / 'notes.json').write_text('{}')
            self.assertEqual(sorted(mod.exported_versions(d)), [4, 5, 37])

    def test_missing_directory(self):
        self.assertEqual(mod.exported_versions(pathlib.Path('/nonexistent-schema-dir')), {})

    def test_reads_version_and_tables(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp) / '37.json'
            p.write_text('{"database": {"version": 37, "entities": [{"tableName": "tasks"}]}}')
            self.assertEqual(mod.read_schema_version(p), 37)
            self.assertEqual(mod.read_schema_tables(p), {'tasks'})

    def test_malformed_json_is_none_not_an_exception(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp) / '37.json'
            p.write_text('{ not json')
            self.assertIsNone(mod.read_schema_version(p))
            self.assertIsNone(mod.read_schema_tables(p))

    def test_schema_without_entities_is_none(self):
        # A JSON file that parses but is not a Room schema must not read as
        # "zero tables", which would satisfy the equality check vacuously.
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp) / '37.json'
            p.write_text('{"database": {"version": 37}}')
            self.assertIsNone(mod.read_schema_tables(p))


class SourceTableNamesTest(unittest.TestCase):
    def test_reads_table_name_declarations(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write(
                root / 'shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/TaskEntity.kt',
                '@Entity(tableName = "tasks")\nclass TaskEntity\n',
            )
            self.assertEqual(mod.source_table_names(root), {'tasks'})

    def test_multiple_files_union(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            base = root / 'shared/src/commonMain/kotlin/com/singularity/todo'
            write(base / 'a/TaskEntity.kt', '@Entity(tableName = "tasks")\n')
            write(base / 'b/SyncShadowEntity.kt', '@Entity(tableName = "sync_shadow")\n')
            self.assertEqual(mod.source_table_names(root), {'tasks', 'sync_shadow'})

    def test_absent_source_dir(self):
        self.assertEqual(mod.source_table_names(pathlib.Path('/nonexistent-src')), set())


class FloorConsistencyTest(unittest.TestCase):
    """The floor is an assertion about history, so it must be checkable.

    `SUPPORTED_FROM_VERSION` exists because v4 was never distributed: the app is
    0.1.0 with no release tag. The cost of that declaration is that it can go
    stale — once someone writes the 4 -> 5 migration it is false. These tests
    pin both directions of that drift.
    """

    def setUp(self):
        self.saved = mod.get_floor()

    def tearDown(self):
        mod.set_floor(self.saved)

    def test_matching_floor_is_clean(self):
        mod.set_floor(5)
        self.assertEqual(mod.check_floor_is_consistent({(5, 6), (6, 7)}, 7), [])

    def test_migration_below_floor_is_stale(self):
        mod.set_floor(5)
        found = mod.check_floor_is_consistent({(4, 5), (5, 6)}, 6)
        self.assertEqual([v.rule for v in found], ['floor-consistent'])

    def test_floor_above_chain_leaves_a_step_unchecked(self):
        mod.set_floor(7)
        found = mod.check_floor_is_consistent({(5, 6), (6, 7)}, 7)
        self.assertEqual([v.rule for v in found], ['floor-consistent'])

    def test_no_migrations_is_not_a_floor_finding(self):
        # An empty chain is rule 2's business. Reporting it here too would be
        # two findings for one defect.
        mod.set_floor(5)
        self.assertEqual(mod.check_floor_is_consistent(set(), 7), [])


class RegistrySanityTest(unittest.TestCase):
    """The constants that encode the project's history must be self-consistent."""

    def test_every_manual_migration_is_a_real_step(self):
        for (frm, to) in mod.MANUAL_MIGRATIONS:
            self.assertEqual(to, frm + 1, f'MANUAL_MIGRATIONS has a non-adjacent pair {(frm, to)}')

    def test_floor_is_a_positive_integer(self):
        self.assertIsInstance(mod.get_floor(), int)
        self.assertGreater(mod.get_floor(), 0)

    def test_min_exports_allows_a_chain(self):
        self.assertGreaterEqual(mod.MIN_EXPORTS, 2)


class RealTreeTest(unittest.TestCase):
    """The gate must be clean against the repository it ships in.

    Without this, every control above could pass while the real tree drifted
    into a state the gate would reject — the failure this project already paid
    for once.
    """

    def test_repository_is_clean(self):
        found = mod.run_all(mod.ROOT)
        self.assertEqual([str(v) for v in found], [], f'gate reports violations on the real tree: {found}')

    def test_repository_head_matches_its_exports(self):
        source = (mod.ROOT / 'shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt').read_text()
        version = mod.parse_schema_version(source)
        exports = mod.exported_versions(mod.SCHEMA_DIR)
        self.assertIsNotNone(version)
        self.assertGreaterEqual(max(exports), version)
        self.assertEqual(max(exports), version, 'head export and SCHEMA_VERSION must be the same version')


if __name__ == '__main__':
    unittest.main()
