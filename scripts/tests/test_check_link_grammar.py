"""Tests for check-link-grammar.py.

The gate says that the grammar of a link has one home: `TaskLinkSpec` for a
deep link, `core/links/InternalLinks.kt` for an internal one. A second copy
of the format is a link that drifts — the producer writes the old format and
the matcher reads the new one, and the link opens nothing.

The reader is the part worth testing: a link literal contains `//`, and a
reader that does not know about strings reads `singularity://task/abc` as a
comment and throws the scheme away — which is exactly the thing the gate
looks for.

These tests are about that reader, and about what counts as the home.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

MODULE_PATH = SCRIPTS / "check-link-grammar.py"
spec = importlib.util.spec_from_file_location("check_link_grammar", MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules["check_link_grammar"] = mod
spec.loader.exec_module(mod)

PRODUCTION = "shared/src/commonMain/kotlin/com/singularity/todo/feature/thing/Thing.kt"
HOME = "shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/TaskLinkSpec.kt"


def write(root: pathlib.Path, relative: str, text: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class LinkGrammarTest(unittest.TestCase):
    def build(self, files: dict[str, str]) -> list[mod.Finding]:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            for relative, text in files.items():
                write(root, relative, text)
            return mod.check(root)

    def test_a_tree_that_names_no_scheme_has_no_findings(self):
        self.assertEqual(self.build({PRODUCTION: "package x\n\nclass Thing\n"}), [])

    def test_a_hand_written_scheme_outside_the_home_is_reported(self):
        findings = self.build(
            {PRODUCTION: 'package x\n\nprivate val link = "singularity://task/abc"\n'}
        )
        self.assertEqual(len(findings), 1)
        self.assertIn("singularity://", findings[0].message)

    def test_the_home_may_name_the_scheme(self):
        """The home is where the format lives. A gate that flagged it would be
        a gate that says "the format has no home"."""
        findings = self.build(
            {
                HOME: 'package x\n\nobject TaskLinkSpec {\n    const val MARKER = "singularity://task/"\n}\n',
            }
        )
        self.assertEqual(findings, [])

    def test_a_scheme_mentioned_only_in_a_comment_is_not_a_copy(self):
        """Prose about the format is not a second copy of it."""
        findings = self.build(
            {
                PRODUCTION: (
                    "package x\n\n"
                    "// the link looks like `singularity://task/abc`\n"
                    "/* and `task://abc` in an older format */\n"
                    "class Thing\n"
                )
            }
        )
        self.assertEqual(findings, [])

    def test_a_literal_that_merely_contains_a_slash_pair_is_not_a_scheme(self):
        findings = self.build({PRODUCTION: 'package x\n\nprivate val path = "a//b"\n'})
        self.assertEqual(findings, [])


if __name__ == "__main__":
    unittest.main()
