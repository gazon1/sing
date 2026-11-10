"""Tests for check-quality-thresholds.py — the page must state what the tree enforces.

The gate exists because the table in `docs/architecture/quality.md` was
hand-written and unchecked, and it drifted twice. So the properties asserted
here are the two directions of that drift:

* a **Here** value the config in force does not enforce is a finding;
* a **default** value the pinned detekt does not ship is a finding;
* a rule the table does not mention is **not** a finding — the page describes
  the rules a reader needs, and the check does not turn it into an inventory;
* a value cell that cannot be read as `option=value` is a finding, because a
  number nobody can check is the failure this gate is about.

The registry's control proves the default column can fail on the real page;
`CheckColumnsTest` proves both columns with fixtures, because one control can
only prove one of them.
"""

import importlib.util
import io
import pathlib
import shutil
import sys
import tempfile
import unittest
import unittest.mock
import zipfile

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent

_spec = importlib.util.spec_from_file_location(
    "check_quality_thresholds", SCRIPTS / "check-quality-thresholds.py"
)
thresholds = importlib.util.module_from_spec(_spec)
sys.modules["check_quality_thresholds"] = thresholds
_spec.loader.exec_module(thresholds)

DEFAULTS = """
complexity:
  active: true
  LongMethod:
    active: true
    allowedLines: 60
  TooManyFunctions:
    active: true
    allowedFunctionsPerFile: 11
style:
  active: true
  WildcardImport:
    active: true
"""


def _page(here: str, default: str, rule: str = "LongMethod") -> str:
    return (
        "# Quality rules\n\n"
        f"{thresholds.SECTION}\n\n"
        "| Rule | Here | Detekt default | Why |\n"
        "|---|---|---|---|\n"
        f"| `{rule}` | {here} | {default} | because. |\n"
    )


def _config(body: str) -> str:
    return "complexity:\n  active: true\n" + body


class ParseTableTest(unittest.TestCase):
    """The table is found by its heading, and only that table is read."""

    def test_the_row_and_its_line_number_are_found(self):
        rows = thresholds.parse_table(_page("`allowedLines=80`", "`allowedLines=60`"))
        self.assertEqual(1, len(rows))
        self.assertEqual("LongMethod", rows[0].rule)
        self.assertEqual(7, rows[0].line)

    def test_a_page_without_the_heading_is_an_error(self):
        with self.assertRaises(ValueError):
            thresholds.parse_table("# Quality rules\n\nno table here\n")

    def test_the_header_and_separator_are_not_rows(self):
        rows = thresholds.parse_table(_page("`allowedLines=80`", "`allowedLines=60`"))
        self.assertEqual(["LongMethod"], [row.rule for row in rows])


class ParseCellTest(unittest.TestCase):
    """A value cell is `option=value` pairs, and prose is refused."""

    def test_booleans_are_normalised(self):
        self.assertEqual(
            {"active": False},
            thresholds.parse_cell("`active=off`"),
        )
        self.assertEqual({"active": True}, thresholds.parse_cell("active=true"))

    def test_numbers_and_multiple_pairs(self):
        self.assertEqual(
            {"allowedFunctionsPerFile": 25, "allowedFunctionsPerClass": 16},
            thresholds.parse_cell("allowedFunctionsPerFile=25, allowedFunctionsPerClass=16"),
        )

    def test_prose_raises_carrying_the_offending_text(self):
        with self.assertRaises(ValueError) as caught:
            thresholds.parse_cell("80 lines")
        self.assertIn("80 lines", str(caught.exception))

    def test_an_empty_cell_raises(self):
        with self.assertRaises(ValueError):
            thresholds.parse_cell("  ")


class CheckColumnsTest(unittest.TestCase):
    """Both columns, each against its own source of truth."""

    def test_a_here_value_the_config_does_not_enforce_is_a_finding(self):
        findings = thresholds.check_documents(
            _page("`allowedLines=70`", "`allowedLines=60`"),
            _config("  LongMethod:\n    active: true\n    allowedLines: 80\n"),
            DEFAULTS,
            "2.0.0-alpha.6",
        )
        self.assertEqual(1, len(findings), findings)
        self.assertIn("Here says allowedLines=70", findings[0])
        self.assertIn("enforces allowedLines=80", findings[0])

    def test_a_config_that_is_silent_falls_back_to_the_shipped_default(self):
        """A rule the config does not mention runs on detekt's default."""
        findings = thresholds.check_documents(
            _page("`allowedLines=60`", "`allowedLines=60`"),
            _config("  Other:\n    active: true\n"),
            DEFAULTS,
            "2.0.0-alpha.6",
        )
        self.assertEqual([], findings)

    def test_a_default_value_the_pinned_detekt_does_not_ship_is_a_finding(self):
        findings = thresholds.check_documents(
            _page("`allowedLines=80`", "`allowedLines=70`"),
            _config("  LongMethod:\n    active: true\n    allowedLines: 80\n"),
            DEFAULTS,
            "2.0.0-alpha.6",
        )
        self.assertEqual(1, len(findings), findings)
        self.assertIn("Detekt default says allowedLines=70", findings[0])
        self.assertIn("ships allowedLines=60", findings[0])

    def test_a_rule_the_table_does_not_mention_is_not_a_finding(self):
        findings = thresholds.check_documents(
            _page("`allowedLines=80`", "`allowedLines=60`"),
            _config("  LongMethod:\n    active: true\n    allowedLines: 80\n"),
            DEFAULTS,
            "2.0.0-alpha.6",
        )
        self.assertEqual([], findings)

    def test_a_rule_neither_source_knows_is_a_finding(self):
        findings = thresholds.check_documents(
            _page("`allowedLines=80`", "`allowedLines=60`", rule="NoSuchRule"),
            _config("  LongMethod:\n    active: true\n    allowedLines: 80\n"),
            DEFAULTS,
            "2.0.0-alpha.6",
        )
        self.assertEqual(1, len(findings), findings)
        self.assertIn("NoSuchRule", findings[0])
        self.assertIn("not a rule", findings[0])

    def test_a_prose_value_cell_is_a_finding(self):
        findings = thresholds.check_documents(
            _page("80 lines", "`allowedLines=60`"),
            _config("  LongMethod:\n    active: true\n    allowedLines: 80\n"),
            DEFAULTS,
            "2.0.0-alpha.6",
        )
        self.assertEqual(1, len(findings), findings)
        self.assertIn("is not an `option=value` list", findings[0])

    def test_a_boolean_option_is_compared_as_its_value_not_its_spelling(self):
        findings = thresholds.check_documents(
            _page("`active=off`", "`active=on`", rule="WildcardImport"),
            "style:\n  active: true\n  WildcardImport:\n    active: false\n",
            DEFAULTS,
            "2.0.0-alpha.6",
        )
        self.assertEqual([], findings)


class TheRepositoryTest(unittest.TestCase):
    """The page in this tree agrees with the configuration it describes."""

    def test_the_page_and_its_configuration_agree(self):
        findings, error = thresholds.check_tree()
        if error is not None:
            self.skipTest(error)
        self.assertEqual([], findings)

    def test_the_page_has_rows(self):
        rows = thresholds.parse_table(
            thresholds.PAGE.read_text(encoding="utf-8")
        )
        self.assertGreater(len(rows), 5)


class VersionTest(unittest.TestCase):
    """The version comes from the catalog, so the check follows the pin."""

    def test_the_pinned_version_is_read(self):
        self.assertEqual(
            "2.0.0-alpha.6",
            thresholds.detekt_version(
                '[versions]\ndetekt = "2.0.0-alpha.6"\n'
            ),
        )

    def test_a_catalog_without_detekt_is_not_guessed(self):
        self.assertIsNone(thresholds.detekt_version("[versions]\nother = \"1\"\n"))


class FetchDetektCoreTest(unittest.TestCase):
    """The gate resolves the pinned jar itself when no Gradle home has it.

    The Static gates CI job restores the Gradle home but not the dependency
    cache, and the detekt Gradle plugin's own classpath does not pull
    `detekt-core` — so a gate that can only read a Gradle cache never passes
    there. These tests pin the resolution: the URL follows the pinned version,
    a jar already on disk is reused, and a failed fetch is not a crash.
    """

    def setUp(self):
        self._cache = thresholds.DETEKT_CORE_CACHE
        self._saved_cache = thresholds.DETEKT_CORE_CACHE
        thresholds.DETEKT_CORE_CACHE = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(self._restore)

    def _restore(self):
        thresholds.DETEKT_CORE_CACHE = self._saved_cache
        shutil.rmtree(thresholds.DETEKT_CORE_CACHE, ignore_errors=True)

    def _jar_bytes(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr(thresholds.DEFAULT_CONFIG_ENTRY, DEFAULTS)
        return buffer.getvalue()

    def test_the_url_follows_the_pinned_version(self):
        seen = {}

        def fake_urlopen(url, timeout=None):
            seen["url"] = url
            raise OSError("offline")

        with unittest.mock.patch.object(thresholds.urllib.request, "urlopen", fake_urlopen):
            self.assertIsNone(thresholds._fetch_detekt_core("2.0.0-alpha.6"))
        self.assertEqual(
            "https://repo1.maven.org/maven2/dev/detekt/detekt-core/"
            "2.0.0-alpha.6/detekt-core-2.0.0-alpha.6.jar",
            seen["url"],
        )

    def test_a_jar_on_disk_is_reused_without_fetching(self):
        jar = thresholds.DETEKT_CORE_CACHE / "2.0.0-alpha.6" / "detekt-core-2.0.0-alpha.6.jar"
        jar.parent.mkdir(parents=True, exist_ok=True)
        jar.write_bytes(self._jar_bytes())

        def never(url, timeout=None):
            raise AssertionError("the jar was already on disk")

        with unittest.mock.patch.object(thresholds.urllib.request, "urlopen", never):
            self.assertEqual(jar, thresholds._fetch_detekt_core("2.0.0-alpha.6"))

    def test_a_fetched_jar_is_cached_and_carries_the_default_config(self):
        payload = self._jar_bytes()

        class FakeResponse:
            def read(self):
                return payload

            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

        with unittest.mock.patch.object(
            thresholds.urllib.request, "urlopen", lambda url, timeout=None: FakeResponse()
        ):
            jar = thresholds._fetch_detekt_core("2.0.0-alpha.6")

        self.assertIsNotNone(jar)
        self.assertTrue(jar.is_file())
        self.assertIn(DEFAULTS, thresholds._default_config_from(jar))

    def test_a_fetch_that_is_not_a_jar_is_not_kept(self):
        class FakeResponse:
            def read(self):
                return b"not a jar"

            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

        with unittest.mock.patch.object(
            thresholds.urllib.request, "urlopen", lambda url, timeout=None: FakeResponse()
        ):
            self.assertIsNone(thresholds._fetch_detekt_core("2.0.0-alpha.6"))

        leftovers = list(thresholds.DETEKT_CORE_CACHE.rglob("*"))
        self.assertEqual([], [p for p in leftovers if p.is_file()])

    def test_a_failed_fetch_is_not_a_crash(self):
        def boom(url, timeout=None):
            raise OSError("offline")

        with unittest.mock.patch.object(thresholds.urllib.request, "urlopen", boom):
            self.assertIsNone(thresholds._fetch_detekt_core("2.0.0-alpha.6"))


if __name__ == "__main__":
    unittest.main()
