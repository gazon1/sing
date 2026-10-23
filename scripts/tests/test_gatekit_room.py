#!/usr/bin/env python3
"""Tests for sgate.gatekit.room — the tables the entity classes declare.

The parser answers one question — *what do the sources say* — and it has to
answer it the way Room would, or the gate that compares the sources with the
export reports differences where there are none. A gate that cries wolf is a
gate that gets ignored, and an ignored gate guards nothing.

The cases below are the shapes the repository actually uses: an explicit
`tableName`, a `@ColumnInfo` that renames a column, a column whose name is the
property's own, an `@Embedded` mixin that Room flattens into the parent, and a
comment that mentions the annotation without declaring it.
"""

import pathlib
import sys
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "src"))

from sgate.gatekit.room import RoomEntity, entities  # noqa: E402


def _tree(files: dict[str, str]) -> tempfile.TemporaryDirectory:
    """A temporary source tree; the context manager yields its root path."""
    tmp = tempfile.TemporaryDirectory()
    root = pathlib.Path(tmp.name)
    for rel, text in files.items():
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    return tmp


ENTITY = "commonMain/kotlin/com/singularity/todo/Thing.kt"


class EntityDeclarationTest(unittest.TestCase):
    """What the annotation says the table is called."""

    def test_the_declared_table_name(self):
        with _tree({ENTITY: '@Entity(tableName = "tasks")\nclass TaskEntity\n'}) as tmp:
            found = entities(pathlib.Path(tmp), "commonMain")
            self.assertEqual(list(found), ["tasks"])
            self.assertEqual(found["tasks"].class_name, "TaskEntity")

    def test_a_table_name_spanning_lines(self):
        source = '@Entity(\n    tableName = "agenda_views",\n    indices = [Index("user_id")],\n)\nclass AgendaViewEntity\n'
        with _tree({ENTITY: source}) as tmp:
            found = entities(pathlib.Path(tmp), "commonMain")
            self.assertEqual(list(found), ["agenda_views"])

    def test_without_a_table_name_the_class_name_is_the_table(self):
        with _tree({ENTITY: "@Entity\nclass TaskEntity\n"}) as tmp:
            found = entities(pathlib.Path(tmp), "commonMain")
            self.assertEqual(list(found), ["TaskEntity"])

    def test_an_entity_in_a_comment_is_not_an_entity(self):
        source = '// @Entity(tableName = "ghost")\nclass NotAnEntity\n'
        with _tree({ENTITY: source}) as tmp:
            self.assertEqual(entities(pathlib.Path(tmp), "commonMain"), {})

    def test_another_source_set_is_not_read(self):
        with _tree({"jvmTest/kotlin/Thing.kt": '@Entity(tableName = "test_only")\nclass TestEntity\n'}) as tmp:
            self.assertEqual(entities(pathlib.Path(tmp), "commonMain"), {})


class ColumnDeclarationTest(unittest.TestCase):
    """What the properties say the columns are called."""

    def test_the_property_name_is_the_column_name(self):
        source = '@Entity(tableName = "t")\nclass T(val id: String, val title: String)\n'
        with _tree({ENTITY: source}) as tmp:
            found = entities(pathlib.Path(tmp), "commonMain")
            self.assertEqual(found["t"].columns, ("id", "title"))

    def test_a_positional_column_info_renames_the_column(self):
        source = '@Entity(tableName = "t")\nclass T(@ColumnInfo("project_id") val projectId: String)\n'
        with _tree({ENTITY: source}) as tmp:
            found = entities(pathlib.Path(tmp), "commonMain")
            self.assertEqual(found["t"].columns, ("project_id",))

    def test_a_named_column_info_renames_the_column(self):
        source = '@Entity(tableName = "t")\nclass T(@ColumnInfo(name = "created_at") val createdAt: Long)\n'
        with _tree({ENTITY: source}) as tmp:
            found = entities(pathlib.Path(tmp), "commonMain")
            self.assertEqual(found["t"].columns, ("created_at",))

    def test_a_column_info_with_only_a_default_keeps_the_property_name(self):
        source = (
            '@Entity(tableName = "t")\n'
            'class T(@ColumnInfo(defaultValue = "[]") val outgoingLinks: String)\n'
        )
        with _tree({ENTITY: source}) as tmp:
            found = entities(pathlib.Path(tmp), "commonMain")
            # Only a default: Room keeps the property's own name.
            self.assertEqual(found["t"].columns, ("outgoingLinks",))

    def test_an_ignored_property_is_not_a_column(self):
        source = '@Entity(tableName = "t")\nclass T(val id: String, @Ignore val scratch: String)\n'
        with _tree({ENTITY: source}) as tmp:
            found = entities(pathlib.Path(tmp), "commonMain")
            self.assertEqual(found["t"].columns, ("id",))

    def test_a_property_with_a_default_value_is_still_a_column(self):
        source = '@Entity(tableName = "t")\nclass T(val id: String, val title: String = "none")\n'
        with _tree({ENTITY: source}) as tmp:
            found = entities(pathlib.Path(tmp), "commonMain")
            self.assertEqual(found["t"].columns, ("id", "title"))


class EmbeddedTest(unittest.TestCase):
    """An `@Embedded` property is not a column — Room flattens it."""

    def test_the_embedded_class_is_flattened_into_the_parent(self):
        files = {
            ENTITY: (
                '@Entity(tableName = "t")\n'
                'class T(val id: String, @Embedded val sync: SyncColumns)\n'
            ),
            "commonMain/kotlin/com/singularity/todo/Sync.kt": (
                'class SyncColumns(\n'
                '    @ColumnInfo("server_version") val serverVersion: Long = 0L,\n'
                '    @ColumnInfo("device_id") val deviceId: String? = null,\n'
                ')\n'
            ),
        }
        with _tree(files) as tmp:
            found = entities(pathlib.Path(tmp), "commonMain")
            self.assertEqual(found["t"].columns, ("id", "server_version", "device_id"))
            self.assertEqual(found["t"].embedded, ("SyncColumns",))

    def test_a_nested_embedding_is_flattened_too(self):
        files = {
            ENTITY: '@Entity(tableName = "t")\nclass T(@Embedded val inner: Inner)\n',
            "commonMain/kotlin/com/singularity/todo/Inner.kt": (
                'class Inner(@Embedded val deep: Deep)\n'
            ),
            "commonMain/kotlin/com/singularity/todo/Deep.kt": (
                'class Deep(val leaf: String)\n'
            ),
        }
        with _tree(files) as tmp:
            found = entities(pathlib.Path(tmp), "commonMain")
            self.assertEqual(found["t"].columns, ("leaf",))

    def test_an_embedding_that_points_at_itself_terminates(self):
        source = '@Entity(tableName = "t")\nclass T(@Embedded val self: T)\n'
        with _tree({ENTITY: source}) as tmp:
            found = entities(pathlib.Path(tmp), "commonMain")
            self.assertEqual(found["t"].columns, ())


class RoomEntityTest(unittest.TestCase):
    """The record the gate compares."""

    def test_the_column_set(self):
        entity = RoomEntity(
            table="t", columns=("a", "b"), class_name="T", path=pathlib.Path("T.kt")
        )
        self.assertEqual(entity.column_set(), {"a", "b"})


if __name__ == "__main__":
    unittest.main()
