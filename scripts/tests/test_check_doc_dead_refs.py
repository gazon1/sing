"""Regression tests for `check-doc-dead-refs.py`.

The bug these guard against is not hypothetical: it made `test-and-check` red in CI
and green on every developer machine, which is the worst shape a gate can have. The
gate reported a gitignored, hook-generated file as a dead reference *only* when it
was referenced by basename, because a gitignore pattern containing `/` is anchored
at the repo root.
"""

import importlib.util
import pathlib
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "check-doc-dead-refs.py"


def _load():
    spec = importlib.util.spec_from_file_location("check_doc_dead_refs", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class GeneratedReferenceDetection(unittest.TestCase):
    """`is_generated` must recognise both the full path and the bare basename."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def test_full_path_of_a_root_anchored_pattern(self):
        # The pattern is `docs/decisions/DIGEST.md`; the slash anchors it to the
        # repo root, so this is the form the original literal test handled.
        self.assertTrue(self.mod.is_generated("docs/decisions/DIGEST.md"))

    def test_bare_basename_of_the_same_generated_file(self):
        # The regression. Three skills reference the generated digest as plain
        # `DIGEST.md`, and references are resolved by basename elsewhere in the
        # script — so the literal-path test missed them and CI went red on a fresh
        # checkout while every developer's tree passed.
        self.assertTrue(self.mod.is_generated("DIGEST.md"))

    def test_a_real_dead_reference_is_not_reported_as_generated(self):
        # The guard on the guard: the basename rule must not swallow genuine debt,
        # or the gate stops measuring anything.
        self.assertFalse(self.mod.is_generated("GLOSSARY.md"))
        self.assertFalse(self.mod.is_generated("docs/decisions/ADR.md"))
        self.assertFalse(self.mod.is_generated("shared/src/Foo.kt"))

    def test_gitignore_directory_names_do_not_create_false_positives(self):
        # `.gitignore` contains directory entries. A directory name must not make an
        # unrelated file of the same name look generated, which is why the basename
        # set only takes entries that carry an extension.
        for pattern in self.mod._GITIGNORE_BASENAMES:
            self.assertIn(".", pattern, f"{pattern!r} should not be a bare directory name")

    def test_every_gitignored_file_basename_is_covered(self):
        # If someone adds a new generated artifact to .gitignore, the basename set
        # picks it up automatically — this asserts the set is actually populated, so
        # an empty or accidentally-cleared set cannot pass silently.
        self.assertTrue(
            self.mod._GITIGNORE_BASENAMES,
            "no gitignored file basenames collected — is_generated would never "
            "recognise a bare reference to a generated file",
        )
        self.assertIn("DIGEST.md", self.mod._GITIGNORE_BASENAMES)


class GitignorePatternCompilation(unittest.TestCase):
    def test_comment_and_negation_lines_are_skipped(self):
        mod = _load()
        for pattern in mod._GITIGNORE:
            self.assertFalse(pattern.pattern.startswith("!"))

    def test_trailing_slash_is_stripped(self):
        mod = _load()
        for pattern in mod._GITIGNORE:
            self.assertFalse(pattern.pattern.endswith("/"))


if __name__ == "__main__":
    unittest.main()
