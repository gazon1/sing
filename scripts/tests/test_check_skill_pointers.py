"""Tests for check-skill-pointers.py.

A pointer does not fail when it is written twice, and a heading that repeats
the title it points at reads like a section of its own. Both are shapes a
clean corpus hides, so the gate has to be right in the cases a real tree does
not currently show:

  - the same section reached twice from one skill body is a finding;
  - two different sections, and a plain document link beside them, are not;
  - a heading that repeats a pointed-at title and carries only the pointer is
    a finding;
  - a heading that repeats a pointed-at title and carries its own content —
    the case `singularity-todo-test-helpers` is — is NOT a finding;
  - a link that does not resolve is `check-doc-links.sh`'s business, and this
    gate says nothing about it;
  - a link outside the skill's own directory is not a sibling, and is not read;
  - a corpus that yields too few pointers is a finding: a check that finds
    nothing has proved nothing.

The unit tests write their own tree. The smoke tests run the gate in this
checkout, because the gate's own corpus is an acceptance criterion of the
change that added it.
"""

import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / 'scripts' / 'check-skill-pointers.py'
spec = importlib.util.spec_from_file_location('check_skill_pointers', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_skill_pointers'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


def run_gate(*args: str) -> subprocess.CompletedProcess:
    """Run the gate against the tree it lives in, or one named by `--skills`."""
    return subprocess.run(
        [sys.executable, str(MODULE_PATH), *args],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )


class SkillPointersTest(unittest.TestCase):
    """The two findings, and the case that must stay quiet."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.skills = self.root / '.agents' / 'skills'
        self.skill_dir = self.skills / 'demo-skill'
        self.skill_dir.mkdir(parents=True)
        (self.skill_dir / 'runbook.md').write_text(
            '# Runbook\n\n## A section\n\nthe runbook body\n\n## Other section\n\nmore\n',
            encoding='utf-8',
        )

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _skill(self, body: str) -> pathlib.Path:
        """Write the demo skill with `body` after a frontmatter block."""
        skill = self.skill_dir / 'SKILL.md'
        skill.write_text(f'---\nname: demo-skill\n---\n\n# Demo\n\n{body}', encoding='utf-8')
        return skill

    def _findings(self) -> list[dict]:
        findings, _, _ = mod.scan(self.skills)
        return findings

    def test_a_section_reached_twice_is_a_finding(self) -> None:
        self._skill(
            'See [A section](runbook.md#a-section).\n\n'
            'Later, see [A section again](runbook.md#a-section).\n'
        )
        findings = self._findings()
        self.assertEqual([finding['kind'] for finding in findings], ['duplicate-pointer'])
        detail = findings[0]['detail']
        self.assertIn('demo-skill', detail)
        self.assertIn('runbook.md#a-section', detail)
        self.assertIn('2 times', detail)

    def test_two_different_sections_are_not_a_finding(self) -> None:
        self._skill(
            'See [A section](runbook.md#a-section), then '
            '[Other section](runbook.md#other-section).\n'
        )
        self.assertEqual(self._findings(), [])

    def test_a_heading_that_only_points_at_its_title_is_a_finding(self) -> None:
        self._skill('## A section\n\n[A section](runbook.md#a-section)\n')
        findings = self._findings()
        self.assertEqual([finding['kind'] for finding in findings], ['shadow-heading'])
        detail = findings[0]['detail']
        self.assertIn('demo-skill', detail)
        self.assertIn("'A section'", detail)
        self.assertIn('runbook.md#a-section', detail)

    def test_a_heading_that_carries_its_own_content_is_not_a_finding(self) -> None:
        # The shape `singularity-todo-test-helpers` has: the heading repeats a
        # sibling's title, and the table under it is material the sibling does
        # not hold. Reporting it would be a gate that cries wolf.
        self._skill(
            '## A section\n\n'
            '| shape | when |\n|---|---|\n| one | here |\n\n'
            'See [A section](runbook.md#a-section) for the rest.\n'
        )
        self.assertEqual(self._findings(), [])

    def test_a_broken_link_is_not_this_gate_s_business(self) -> None:
        self._skill('See [A section](missing.md#a-section).\n')
        self.assertEqual(self._findings(), [])

    def test_a_link_outside_the_skill_directory_is_not_a_sibling(self) -> None:
        (self.skills / 'other-skill').mkdir()
        (self.skills / 'other-skill' / 'runbook.md').write_text(
            '## A section\n\nbody\n', encoding='utf-8'
        )
        self._skill(
            '## A section\n\n[A section](../other-skill/runbook.md#a-section)\n'
        )
        self.assertEqual(self._findings(), [])

    def test_a_same_document_anchor_is_not_a_sibling_pointer(self) -> None:
        self._skill('See [A section](#a-section) above.\n')
        self.assertEqual(self._findings(), [])


class TheGateSmokeTest(unittest.TestCase):
    """The gate exists, and its own corpus is clean."""

    def test_the_gate_script_exists(self) -> None:
        self.assertTrue(MODULE_PATH.is_file())

    def test_the_skills_in_this_repository_pass(self) -> None:
        result = run_gate('--check', '--quiet')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_a_corpus_with_too_few_pointers_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            skills = pathlib.Path(temporary) / '.agents' / 'skills'
            skill_dir = skills / 'demo-skill'
            skill_dir.mkdir(parents=True)
            (skill_dir / 'SKILL.md').write_text(
                '---\nname: demo-skill\n---\n\n# Demo\n\nNo pointers at all.\n',
                encoding='utf-8',
            )
            result = run_gate('--skills', str(skills), '--check')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('proves nothing', result.stdout)


if __name__ == '__main__':
    unittest.main()
