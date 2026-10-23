#!/usr/bin/env python3
"""Tests for sgate.gatekit.gradle — the shared reader of the Gradle build.

Three facts are at stake, and each of them was once re-implemented per gate:

  * which modules the build includes;
  * where a module's build file is;
  * what is inside a named block of a build script.

The third is where the copy was wrong rather than merely duplicated: the
`detekt` block was read with a regex that cannot balance braces, so a nested
block ended it early and the body handed to the rule was truncated. The tests
below pin the *correct* behaviour, not the behaviour that was copied.
"""

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / 'src'))

from sgate.gatekit.gradle import (  # noqa: E402
    build_files_of,
    blocks,
    has_block,
    included_modules,
    module_build_files,
    modules,
)


class IncludedModulesTest(unittest.TestCase):
    """The module list comes from `settings.gradle.kts`, not from a hand list."""

    def test_reads_include_calls(self):
        text = 'include(":shared")\ninclude(":androidApp")\n'
        self.assertEqual(included_modules(text), ["shared", "androidApp"])

    def test_keeps_a_guarded_include(self):
        """`:pro` is behind `if (withPro)`, and it is still a module."""
        text = 'include(":shared")\nif (withPro) {\n    include(":pro")\n}\n'
        self.assertEqual(included_modules(text), ["shared", "pro"])

    def test_keeps_a_nested_module_name(self):
        self.assertEqual(
            included_modules('include(":tools:unwritten-properties")'),
            ["tools:unwritten-properties"],
        )

    def test_names_a_module_once(self):
        self.assertEqual(included_modules('include(":a")\ninclude(":a")'), ["a"])

    def test_ignores_a_comment(self):
        self.assertEqual(included_modules('// include(":ghost")'), [])

    def test_reads_the_settings_file(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / "settings.gradle.kts").write_text(
                'include(":shared")\ninclude(":pro")\n', encoding="utf-8"
            )
            self.assertEqual(modules(root), ["shared", "pro"])

    def test_a_missing_settings_file_yields_no_modules(self):
        """A gate that cannot see the module list must not fall back to its own."""
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(modules(pathlib.Path(d)), [])


class ModuleBuildFilesTest(unittest.TestCase):
    """The walk finds the build files that exist, not the modules that are named."""

    def test_finds_top_level_build_files(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            for name in ("shared", "androidApp"):
                (root / name).mkdir()
                (root / name / "build.gradle.kts").write_text("plugins { }\n", encoding="utf-8")
            (root / "tools" / "proc").mkdir(parents=True)
            (root / "tools" / "proc" / "build.gradle.kts").write_text(
                "plugins { }\n", encoding="utf-8"
            )
            found = [p.parent.name for p in module_build_files(root)]
            self.assertEqual(found, ["androidApp", "shared"])

    def test_a_module_without_a_build_file_is_not_a_build_file(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / "shared").mkdir()
            (root / "shared" / "build.gradle.kts").write_text("plugins { }\n", encoding="utf-8")
            (root / "pro").mkdir()
            self.assertEqual([p.parent.name for p in module_build_files(root)], ["shared"])

    def test_build_files_of_named_modules(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / "shared").mkdir()
            (root / "shared" / "build.gradle.kts").write_text("plugins { }\n", encoding="utf-8")
            (root / "tools" / "proc").mkdir(parents=True)
            (root / "tools" / "proc" / "build.gradle.kts").write_text(
                "plugins { }\n", encoding="utf-8"
            )
            found = build_files_of(root, ["shared", "tools:proc", "absent"])
            self.assertEqual(
                [p.relative_to(root).as_posix() for p in found],
                ["shared/build.gradle.kts", "tools/proc/build.gradle.kts"],
            )


class BlocksTest(unittest.TestCase):
    """A block is read by balancing braces, not by guessing where it ends."""

    def test_reads_a_block_body(self):
        self.assertEqual(blocks("detekt { ignoreFailures = true }", "detekt"),
                         [" ignoreFailures = true "])

    def test_reads_a_nested_block_to_its_end(self):
        """The regex this replaced stopped at the first line that starts with `}`."""
        text = """
detekt {
    baseline = "detekt-rules/baseline.xml"
    config {
        validation = true
    }
    ignoreFailures = true
}
"""
        body = blocks(text, "detekt")
        self.assertEqual(len(body), 1)
        self.assertIn("ignoreFailures = true", body[0])
        self.assertIn("validation = true", body[0])

    def test_finds_every_block_with_that_name(self):
        text = "kover { a { b = 1 } }\nkover { c = 2 }"
        self.assertEqual(len(blocks(text, "kover")), 2)

    def test_a_block_in_a_comment_is_not_a_block(self):
        """The comment above a real block quotes the API by name."""
        text = "// reports { total { verify { rule { bound {\nkover { minValue = 60 }"
        self.assertEqual(len(blocks(text, "kover")), 1)

    def test_a_comment_with_unbalanced_braces_does_not_derail_the_matcher(self):
        text = "/* { { { */\nkover { minValue = 60 }"
        self.assertEqual(len(blocks(text, "kover")), 1)
        self.assertIn("minValue = 60", blocks(text, "kover")[0])

    def test_the_name_is_matched_on_a_word_boundary(self):
        text = "subprojects { detekt { } }\nkoverReports { }"
        self.assertEqual(len(blocks(text, "detekt")), 1)
        self.assertEqual(blocks(text, "kover"), [])

    def test_an_unclosed_block_yields_nothing(self):
        self.assertEqual(blocks("detekt { ignoreFailures = true", "detekt"), [])

    def test_has_block(self):
        self.assertTrue(has_block("detekt { }", "detekt"))
        self.assertFalse(has_block("plugins { }", "detekt"))


if __name__ == "__main__":
    unittest.main()
