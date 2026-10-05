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


class AdrSlugAndBuildScriptScanning(unittest.TestCase):
    """Build scripts and dated ADR slugs must be covered.

    Both extensions exist because a real dangling reference survived every
    gate: `shared/build.gradle.kts` cited a deferred-backlog entry under a name
    that exists nowhere in the repo, and the file was not scanned at all — a
    comment in a build script outlives the rename it points at.
    """

    @classmethod
    def setUpClass(cls):
        cls.mod = _load()
        cls.rel_paths, cls.by_name = cls.mod.build_index()

    def _scan(self, text, name="x.kt"):
        return self.mod.scan_text(name, text, self.rel_paths, self.by_name)

    def test_build_scripts_are_scanned_as_targets(self):
        # Reproduce main()'s target list: if a build script is absent, a broken
        # reference inside it is invisible to the gate.
        targets = [REPO_ROOT / n for n in self.mod.GLOB_BUILD
                   if (REPO_ROOT / n).is_file()]
        self.assertIn(REPO_ROOT / "shared/build.gradle.kts", targets)
        self.assertTrue(all(t.is_file() for t in targets),
                        "GLOB_BUILD lists a file that does not exist")

    def test_a_dangling_path_in_a_build_script_is_reported(self):
        text = '// see `docs/decisions/definitely-not-here.md`\n'
        found = self.mod.scan_text("shared/build.gradle.kts", text,
                                  self.__class__.rel_paths, self.__class__.by_name)
        self.assertEqual([ref for _, ref, _ in found],
                         ["docs/decisions/definitely-not-here.md"])

    def test_a_dated_adr_slug_is_checked(self):
        bogus = "2099-01-01-never-written"
        text = f"// see ADR `{bogus}`\n"
        found = self.mod.scan_text("x.kt", text, self.__class__.rel_paths, self.__class__.by_name)
        self.assertEqual([ref for _, ref, _ in found], [bogus])

    def test_an_existing_adr_slug_passes(self):
        adr = next(p for p in sorted((REPO_ROOT / "docs/decisions").glob("*.md"))
                   if p.name != "DIGEST.md")
        text = f"// see ADR `{adr.stem}`\n"
        found = self.mod.scan_text("x.kt", text, self.__class__.rel_paths, self.__class__.by_name)
        self.assertEqual(found, [], f"{adr.stem} exists but was reported dead")

    def test_bare_kebab_tokens_are_not_treated_as_slugs(self):
        # The false positive that killed the wider pattern: a skill name and a
        # Gradle artifact are the same shape as a backlog heading.
        for token in ("singularity-todo-testable-vm", "koin-compose-navigation3",
                      "ai-agent", "fast-slow"):
            with self.subTest(token=token):
                text = f"`{token}`\n"
                found = self.mod.scan_text("x.kt", text, self.__class__.rel_paths,
                                          self.__class__.by_name)
                self.assertEqual(found, [], f"{token} is not an ADR slug")

    def test_the_real_kiwi_adr_slug_in_the_build_script_resolves(self):
        path = REPO_ROOT / "shared/build.gradle.kts"
        found = self.mod.scan_text(str(path), path.read_text(),
                                  self.__class__.rel_paths, self.__class__.by_name)
        self.assertEqual(found, [], "shared/build.gradle.kts has a dangling ref")


if __name__ == "__main__":
    unittest.main()
