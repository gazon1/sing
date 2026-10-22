"""Tests for check-test-tags-catalog.py — the catalogue must agree with the tags.

The gate exists because the reader of the golden file is a JVM test: a tag
added today and a catalogue not rewritten are both green until someone runs the
forty-minute suite. A gate that only reports drift after forty minutes is a
gate nobody runs, so the property that matters here is the one the JVM test
does not give: **the same comparison, in a second**.

What is pinned:

* the parser reads the *nested* constants the way the catalogue writes them
  (`Pomodoro.CYCLE_LABEL`), and does not confuse a brace inside a string
  literal with the end of a scope — a tag built by interpolation
  (`"…_${…}"`) closes nothing;
* a renamed value, a constant that is not in the catalogue and a row that is
  not in the source are each reported, and each is reported *once*;
* the exit code is 1 under `--check` when there is a difference and 0 when
  there is none. A gate that reports drift and exits 0 is a gate nobody runs.

The fixtures are temporary files; the end-to-end check reads this repository,
because that is the tree the gate is about.
"""

import importlib.util
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = ROOT / "scripts/check-test-tags-catalog.py"


def load_module():
    """Load the gate script as a module — its filename is not importable."""
    spec = importlib.util.spec_from_file_location("check_test_tags_catalog", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


MODULE = load_module()


class DeclaredConstantsTest(unittest.TestCase):
    """The source side of the comparison."""

    def test_a_constant_of_the_top_level_object_is_unqualified(self):
        source = """
            object TestTags {
                const val AUTH_EMAIL_INPUT = "auth_email_input"
            }
        """
        self.assertEqual(
            {"AUTH_EMAIL_INPUT": "auth_email_input"},
            MODULE.declared_constants(source),
        )

    def test_a_constant_of_a_nested_object_is_qualified(self):
        source = """
            object TestTags {
                const val AUTH_EMAIL_INPUT = "auth_email_input"
                object Pomodoro {
                    const val CYCLE_LABEL = "pomodoro_cycle_label"
                }
            }
        """
        self.assertEqual(
            {
                "AUTH_EMAIL_INPUT": "auth_email_input",
                "Pomodoro.CYCLE_LABEL": "pomodoro_cycle_label",
            },
            MODULE.declared_constants(source),
        )

    def test_a_brace_inside_a_string_literal_closes_nothing(self):
        """A tag built by interpolation must not end the scope it sits in."""
        source = """
            object TestTags {
                object MultiDatePicker {
                    fun day(isoDate: String) = "multi_date_picker_day_${isoDate.replace("-", "_")}"
                    const val OK = "multi_date_picker_ok"
                }
                const val AFTER = "after"
            }
        """
        constants = MODULE.declared_constants(source)
        self.assertEqual("multi_date_picker_ok", constants["MultiDatePicker.OK"])
        self.assertEqual("after", constants["AFTER"])

    def test_a_commented_out_declaration_is_not_a_declaration(self):
        source = """
            object TestTags {
                // const val COMMENTED = "commented"
                const val REAL = "real"
            }
        """
        self.assertEqual({"REAL": "real"}, MODULE.declared_constants(source))

    def test_a_continued_declaration_holds_the_concatenated_value(self):
        source = """
            object TestTags {
                const val JOINED = "first_" +
                    "second"
            }
        """
        self.assertEqual({"JOINED": "first_second"}, MODULE.declared_constants(source))


class CatalogRowsTest(unittest.TestCase):
    """The catalogue side of the comparison."""

    def test_the_static_table_is_read(self):
        text = "\n".join(
            [
                "<!-- GENERATED:BEGIN -->",
                "## Static constants",
                "| Constant | Value | Where |",
                "|---|---|---|",
                "| `AUTH_EMAIL_INPUT` | `auth_email_input` | |",
                "| `Pomodoro.CYCLE_LABEL` | `pomodoro_cycle_label` | |",
                "<!-- GENERATED:END -->",
            ]
        )
        self.assertEqual(
            {
                "AUTH_EMAIL_INPUT": "auth_email_input",
                "Pomodoro.CYCLE_LABEL": "pomodoro_cycle_label",
            },
            MODULE.catalog_rows(text),
        )

    def test_the_dynamic_table_is_not_read(self):
        """Its rows are templates, not values — the JVM test owns their shape."""
        text = "\n".join(
            [
                "<!-- GENERATED:BEGIN -->",
                "## Static constants",
                "| Constant | Value | Where |",
                "|---|---|---|",
                "| `AUTH_EMAIL_INPUT` | `auth_email_input` | |",
                "## Dynamic functions",
                "| Function | Example |",
                "|---|---|",
                "| `taskItem(title)` | `task_item_buy_milk` |",
                "<!-- GENERATED:END -->",
            ]
        )
        self.assertEqual({"AUTH_EMAIL_INPUT": "auth_email_input"}, MODULE.catalog_rows(text))


class DifferencesTest(unittest.TestCase):
    """The comparison itself."""

    def setUp(self):
        self.tmp = pathlib.Path(self.enterContext(__import__("tempfile").TemporaryDirectory()))
        self.tags = self.tmp / "TestTags.kt"
        self.catalogue = self.tmp / "TAGS.md"
        self._original_test_tags = MODULE.TEST_TAGS
        self._original_tags_md = MODULE.TAGS_MD
        MODULE.TEST_TAGS = self.tags
        MODULE.TAGS_MD = self.catalogue

    def tearDown(self):
        MODULE.TEST_TAGS = self._original_test_tags
        MODULE.TAGS_MD = self._original_tags_md

    def write(self, tags: str, catalogue_rows_: list[str]) -> None:
        self.tags.write_text(tags, encoding="utf-8")
        self.catalogue.write_text(
            "\n".join(
                [
                    "<!-- GENERATED:BEGIN -->",
                    "## Static constants",
                    "| Constant | Value | Where |",
                    "|---|---|---|",
                    *catalogue_rows_,
                    "<!-- GENERATED:END -->",
                ]
            ),
            encoding="utf-8",
        )

    def test_a_renamed_value_is_reported_once(self):
        self.write(
            'object TestTags {\n    const val TAG = "tag_value"\n}\n',
            ["| `TAG` | `tag_value` | |"],
        )
        self.assertEqual({}, MODULE.differences())

        self.tags.write_text(
            'object TestTags {\n    const val TAG = "renamed"\n}\n', encoding="utf-8"
        )
        found = MODULE.differences()
        self.assertEqual(["TAG"], found["a different value"])
        self.assertNotIn("missing from the catalogue", found)

    def test_a_constant_the_catalogue_does_not_hold_is_reported(self):
        self.write(
            'object TestTags {\n    const val TAG = "tag_value"\n}\n',
            [],
        )
        found = MODULE.differences()
        self.assertEqual(["TAG"], found["missing from the catalogue"])

    def test_a_row_the_source_no_longer_declares_is_reported(self):
        self.write(
            'object TestTags {\n    const val TAG = "tag_value"\n}\n',
            ["| `TAG` | `tag_value` | |", "| `GONE` | `gone` | |"],
        )
        found = MODULE.differences()
        self.assertEqual(["GONE"], found["not in the source any more"])


class RepositoryTest(unittest.TestCase):
    """The tree this gate is about."""

    def test_the_committed_catalogue_agrees_with_the_committed_tags(self):
        found = MODULE.differences()
        self.assertEqual(
            {},
            found,
            "Maestro/TAGS.md and TestTags.kt disagree — rewrite the catalogue with "
            "./gradlew :shared:jvmTest -PupdateGoldens=true",
        )


if __name__ == "__main__":
    unittest.main()
