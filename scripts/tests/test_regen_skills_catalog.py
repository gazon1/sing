"""Tests for the skills-catalogue gate — `scripts/regen-skills-catalog.sh --check`.

The catalogue is the index an agent chooses a skill from, so a stale row — a
line count or a description that no longer matches the file — sends it to the
wrong file. The generator grew a `--check` mode for exactly that, and nothing
called it: the catalogue was committed stale on a clean tree and nothing
noticed.

These tests run the generator against a tree of their own. The script roots
itself at the directory it lives in, so a copy of it in a temporary directory
is a generator over that directory's skills — and the repository's own
catalogue is never touched.

Three properties, and the third is the one that is easy to lose:

  - a catalogue that matches what the generator writes passes;
  - a catalogue that does not match fails, and the failure says what to run;
  - a skill whose body stops parsing makes the generator *refuse*, instead of
    writing a catalogue that silently omits the skill. A gate that reported
    that as a plain content difference would be reporting the wrong thing.
"""

import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = ROOT / "scripts" / "regen-skills-catalog.sh"

SKILL = """---
name: demo-skill
description: A demo skill. It exists so the catalogue has something to say.
---

Body.
"""

# A skill whose frontmatter lost its description: the parser gives up on the
# file, and the file would vanish from the catalogue.
BROKEN_SKILL = """---
name: broken-skill
---

No description, so there is nothing to index.
"""


class CatalogueGateTest(unittest.TestCase):
    """The gate, over a tree of its own."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = pathlib.Path(self._tmp.name)
        (self.root / "scripts").mkdir(parents=True)
        self.script = self.root / "scripts" / SCRIPT.name
        shutil.copy2(SCRIPT, self.script)

    def write_skill(self, body=SKILL, directory="demo-skill"):
        skill_dir = self.root / ".agents" / "skills" / directory
        skill_dir.mkdir(parents=True, exist_ok=True)
        (skill_dir / "SKILL.md").write_text(body, encoding="utf-8")

    def run_generator(self, *args):
        return subprocess.run(
            ["bash", str(self.script), *args],
            cwd=self.root,
            capture_output=True,
            text=True,
        )

    def test_a_catalogue_that_matches_what_the_generator_writes_passes(self):
        self.write_skill()
        written = self.run_generator()
        self.assertEqual(written.returncode, 0, written.stdout + written.stderr)

        before = (self.root / "docs" / "SKILLS-CATALOG.md").read_text(encoding="utf-8")
        checked = self.run_generator("--check")
        self.assertEqual(checked.returncode, 0, checked.stdout + checked.stderr)
        # A check mode that rewrote the file would pass this test too, and
        # would repair the very drift it exists to report.
        after = (self.root / "docs" / "SKILLS-CATALOG.md").read_text(encoding="utf-8")
        self.assertEqual(before, after)

    def test_a_catalogue_that_does_not_match_fails_and_says_what_to_run(self):
        self.write_skill()
        self.run_generator()
        catalogue = self.root / "docs" / "SKILLS-CATALOG.md"
        catalogue.write_text(
            catalogue.read_text(encoding="utf-8").replace(
                "# Skills Catalog", "# Skills Catalog (edited by hand)"
            ),
            encoding="utf-8",
        )

        checked = self.run_generator("--check")
        self.assertEqual(checked.returncode, 1, checked.stdout + checked.stderr)
        self.assertIn("stale", checked.stderr)
        # The message names the fix. A failure that says only "differs" sends
        # the reader looking for the difference instead of running the one
        # command that ends it.
        self.assertIn("regen-skills-catalog.sh", checked.stderr)

    def test_a_skill_that_no_longer_parses_makes_the_generator_refuse(self):
        self.write_skill()
        self.run_generator()
        self.write_skill(BROKEN_SKILL, directory="broken-skill")

        checked = self.run_generator("--check")
        self.assertEqual(checked.returncode, 1, checked.stdout + checked.stderr)
        self.assertIn("refusing to write", checked.stderr)
        # The refusal is its own verdict. Reporting it as a plain content
        # difference would tell the reader to re-run the generator, and the
        # generator refuses again.
        self.assertNotIn("stale", checked.stderr)


if __name__ == "__main__":
    unittest.main()
