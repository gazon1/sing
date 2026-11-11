"""Tests for build-version-catalog-gate.py — the versions' single source of truth.

Two checks are asserted here, and both of them exist because of a recorded
incident rather than because they look tidy:

1. **gradle.properties must not repeat a catalog version.** The versions used
   to live in the catalog *and* in gradle.properties. A dependabot bump moves
   the catalog and leaves the property behind, and the two then disagree — that
   mismatch is what red-lined main. The catalog owns the versions now, so the
   property is a duplicate, not a second source.

2. **The catalog's Kotlin version must be one its detekt was compiled with.**
   detekt refuses to run against any other Kotlin ("detekt was compiled with
   Kotlin X but is currently running with Y"), so a Kotlin bump the table does
   not cover has to be rejected while the message is still actionable — at gate
   time, not deep inside the detekt task.

The tree is temporary: the gate takes the workspace as an argument, so it is
pointed at a tempdir and nothing is written into the repository.
"""

import contextlib
import importlib.util
import io
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent

_spec = importlib.util.spec_from_file_location(
    "build_version_catalog_gate", SCRIPTS / "build-version-catalog-gate.py"
)
gate = importlib.util.module_from_spec(_spec)
sys.modules["build_version_catalog_gate"] = gate
_spec.loader.exec_module(gate)

CATALOG = """[versions]
kotlin = "2.4.10"
kotlin-serialization = "1.12.0"
kotlinxCollectionsImmutable = "0.5.2"
detekt = "2.0.0-alpha.6"
"""

PROPERTIES = """android.nonTransitiveRClass=true
"""


class CatalogTree(unittest.TestCase):
    """A temporary tree with a catalog and a gradle.properties."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.write_catalog(CATALOG)
        self.write_properties(PROPERTIES)
        # The gate scans *.gradle.kts and needs enough of them to clear its
        # own positive control: five is the minimum it accepts, and six are
        # created so the scan is not exactly at the boundary.
        for i in range(6):
            (self.root / f"module{i}").mkdir()
            (self.root / f"module{i}" / "build.gradle.kts").write_text(
                "plugins { id(\"org.jetbrains.kotlin.jvm\") }\n", encoding="utf-8"
            )

    def write_catalog(self, text):
        path = self.root / "gradle" / "libs.versions.toml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def write_properties(self, text):
        (self.root / "gradle.properties").write_text(text, encoding="utf-8")


class DuplicatedVersionProperty(CatalogTree):
    """gradle.properties must not carry a version the catalog owns."""

    def violations(self):
        return gate._gradle_properties_duplication(self.root)

    def test_a_clean_properties_file_has_no_violation(self):
        self.assertEqual(self.violations(), [])

    def test_a_duplicated_kotlin_property_is_a_violation(self):
        self.write_properties(PROPERTIES + "version.kotlin=2.4.10\n")
        violations = self.violations()
        self.assertEqual(len(violations), 1)
        self.assertIn("version.kotlin", violations[0][1])
        self.assertIn("single source of truth", violations[0][1])

    def test_a_duplicated_property_is_a_violation_even_when_the_values_agree(self):
        """Two places that hold the same value are still two places."""
        self.write_properties(PROPERTIES + "version.kotlinSerialization=1.12.0\n")
        violations = self.violations()
        self.assertEqual(len(violations), 1)
        self.assertIn("version.kotlinSerialization", violations[0][1])

    def test_an_unrelated_property_is_not_a_violation(self):
        self.write_properties(PROPERTIES + "org.gradle.caching=true\n")
        self.assertEqual(self.violations(), [])

    def test_a_version_property_the_rule_has_never_seen_is_a_violation(self):
        """The rule is the prefix, not a list of the three versions that were there."""
        self.write_properties(PROPERTIES + "version.somethingNew=1.2.3\n")
        violations = self.violations()
        self.assertEqual(len(violations), 1)
        self.assertIn("version.somethingNew", violations[0][1])

    def test_a_property_that_only_looks_like_a_version_is_not_a_violation(self):
        """`versionCode` is the Android version, and the catalog does not own it."""
        self.write_properties(PROPERTIES + "versionCode=42\n")
        self.assertEqual(self.violations(), [])


class MainEntryPoint(CatalogTree):
    """The gate's own entry point — the checks have to be wired into it.

    A check that is written but not called is a check that passes: the tests
    above call the checks directly, so only this one proves that `main()`
    reaches them.
    """

    def run_main(self):
        argv = ["build-version-catalog-gate.py", "--quiet", str(self.root)]
        out = io.StringIO()
        with mock.patch.object(sys, "argv", argv), contextlib.redirect_stdout(out):
            code = gate.main()
        return code, out.getvalue()

    def test_a_clean_tree_passes(self):
        code, _ = self.run_main()
        self.assertEqual(code, 0)

    def test_a_duplicated_property_fails_the_gate(self):
        self.write_properties(PROPERTIES + "version.kotlin=2.4.10\n")
        code, _ = self.run_main()
        self.assertEqual(code, 1)

    def test_a_kotlin_bump_detekt_cannot_take_fails_the_gate(self):
        self.write_catalog(CATALOG.replace('kotlin = "2.4.10"', 'kotlin = "2.4.21"'))
        code, _ = self.run_main()
        self.assertEqual(code, 1)


class DetektKotlinCompatibility(CatalogTree):
    """The catalog's Kotlin version must be one its detekt was compiled with."""

    def violations(self):
        return gate._detekt_kotlin_compatibility(self.root)

    def test_matching_versions_have_no_violation(self):
        self.assertEqual(self.violations(), [])

    def test_a_kotlin_bump_detekt_cannot_take_is_rejected(self):
        self.write_catalog(CATALOG.replace('kotlin = "2.4.10"', 'kotlin = "2.4.21"'))
        violations = self.violations()
        self.assertEqual(len(violations), 1)
        message = violations[0][1]
        self.assertIn("2.4.21", message)
        self.assertIn("2.4.10", message)
        self.assertIn("detekt", message)

    def test_a_detekt_version_the_table_does_not_know_is_rejected(self):
        self.write_catalog(CATALOG.replace('detekt = "2.0.0-alpha.6"', 'detekt = "2.0.0-alpha.7"'))
        violations = self.violations()
        self.assertEqual(len(violations), 1)
        self.assertIn("DETEKT_KOTLIN_COMPATIBILITY", violations[0][1])

    def test_a_missing_kotlin_version_is_a_violation(self):
        """One version without the other is a check that never ran."""
        self.write_catalog(CATALOG.replace('kotlin = "2.4.10"\n', ""))
        violations = self.violations()
        self.assertEqual(len(violations), 1)
        self.assertIn("kotlin", violations[0][1])

    def test_a_missing_detekt_version_is_a_violation(self):
        self.write_catalog(CATALOG.replace('detekt = "2.0.0-alpha.6"\n', ""))
        violations = self.violations()
        self.assertEqual(len(violations), 1)
        self.assertIn("detekt", violations[0][1])


class CatalogEntryForms(CatalogTree):
    """The catalog's [versions] table has two legal forms, and both are read.

    `key = "value"` is one of them. The other is the table form
    `key = { strictly = "value" }` — legal TOML, legal for Gradle, and
    invisible to a gate that only reads the plain form.
    """

    def versions(self):
        return gate._catalog_versions(self.root)

    def test_the_plain_form_is_read(self):
        self.assertEqual(self.versions()["kotlin"], "2.4.10")

    def test_the_table_form_is_read(self):
        self.write_catalog(CATALOG.replace('kotlin = "2.4.10"', 'kotlin = { strictly = "2.4.10" }'))
        self.assertEqual(self.versions()["kotlin"], "2.4.10")

    def test_the_table_form_is_compared_like_the_plain_form(self):
        """A table form that disagrees with detekt is still a disagreement."""
        self.write_catalog(CATALOG.replace('kotlin = "2.4.10"', 'kotlin = { strictly = "2.4.21" }'))
        violations = gate._detekt_kotlin_compatibility(self.root)
        self.assertEqual(len(violations), 1)
        self.assertIn("2.4.21", violations[0][1])

    def test_a_require_constraint_is_read(self):
        self.write_catalog(CATALOG.replace('kotlin = "2.4.10"', 'kotlin = { require = "2.4.10" }'))
        self.assertEqual(self.versions()["kotlin"], "2.4.10")

    def test_the_strict_constraint_wins_over_a_soft_prefer(self):
        self.write_catalog(
            CATALOG.replace(
                'kotlin = "2.4.10"',
                'kotlin = { prefer = "2.4.99", strictly = "2.4.10" }',
            )
        )
        self.assertEqual(self.versions()["kotlin"], "2.4.10")

    def test_the_other_sections_are_not_versions(self):
        """A library entry is a table too — it is not a version of anything."""
        self.write_catalog(
            CATALOG
            + '\n[libraries]\nkotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }\n'
        )
        self.assertNotIn("kotlin-stdlib", self.versions())

    def test_a_catalog_without_a_versions_table_has_no_versions(self):
        self.write_catalog('[libraries]\nkotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib" }\n')
        self.assertEqual(self.versions(), {})


if __name__ == "__main__":
    unittest.main()
