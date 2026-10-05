"""Tests for check-publication-hygiene.py.

The gate stops one thing: machine-specific residue reaching a public tree. Three
ways it could pass while measuring nothing, and each has a test here.

  - a rule that stopped matching its violation. Covered by MarkerPatternTest,
    which runs every rule against a corpus that contains the violation.
  - a rule so broad it reports ordinary English, which gets the gate switched
    off — and a gate that is switched off protects nothing. Covered by
    NearMissTest, which pins the shapes that must stay quiet.
  - an allowlist that grew to cover everything, which is a gate with the rules
    deleted. Covered by AllowlistTest, in both directions.

The third one is not hypothetical. An earlier version of this gate's own
self-test judged its "clean" corpus through the raw detectors instead of through
the suppression the real check applies, and failed on an allowlisted ADR line —
correctly, and for the wrong reason. The tests below assert the clean corpus
through the same suppression path the gate reports from.
"""

import importlib.util
import pathlib
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-publication-hygiene.py'
spec = importlib.util.spec_from_file_location('check_publication_hygiene', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class RuleFiresTest(unittest.TestCase):
    """Each rule must catch the violation it was written for."""

    def _rules_for(self, files: dict[str, str]) -> set[str]:
        # Raw detectors, with no allowlist applied: these cases assert the rule
        # itself, so nothing may suppress them. The `NearMissTest` counterparts
        # go through `_is_allowed` instead, because "must stay quiet" is a claim
        # about what the gate *reports*, not about what the regex can see.
        with mod.tempfile_tree(files) as root:
            return {rule for rule, _, _, _ in mod.scan(root)}

    def test_home_path(self):
        rules = self._rules_for({'README.md': 'cd /home/someone/app\n'})
        self.assertIn('home-path', rules)

    def test_home_path_requires_a_user_segment(self):
        # `/home/` alone is prose about the directory, not a person's home.
        rules = self._rules_for({'README.md': 'Copy it under /home/ before building.\n'})
        self.assertNotIn('home-path', rules)

    def test_macos_home_path(self):
        rules = self._rules_for({'notes.md': 'cd /Users/someone/app\n'})
        self.assertIn('home-path', rules)

    def test_windows_home_path(self):
        rules = self._rules_for({'notes.md': 'sdk.dir=C:\\Users\\someone\\Sdk\n'})
        self.assertIn('home-path', rules)

    def test_clone_name(self):
        rules = self._rules_for({'settings.gradle.kts': 'rootProject.name = "app_cllone_kmp"\n'})
        self.assertIn('clone-name', rules)

    def test_personal_email(self):
        rules = self._rules_for({'notes.md': 'Maintainer: a.person@corp-mail.example.net\n'})
        self.assertIn('personal-email', rules)

    def test_foreign_port(self):
        rules = self._rules_for({'notes.md': 'Open http://localhost:3000 to sign in.\n'})
        self.assertIn('foreign-port', rules)

    def test_email_followed_by_colon_is_still_reported(self):
        """The SSH carve-out must not become "any address with a colon nearby".

        This is the case that decides whether the exclusion is a shape or a
        guess. `owner@host:` is a remote; `owner@host:` at the start of a line in
        prose is still an address, and the real tree has one of each.
        """
        rules = self._rules_for({'notes.md': 'a.person@corp-mail.example.net: owns sync\n'})
        self.assertIn('personal-email', rules)


class NearMissTest(unittest.TestCase):
    """Shapes that must stay quiet. A noisy gate is worse than no gate."""

    def _rules_for(self, files: dict[str, str]) -> set[str]:
        # Through the allowlist, because that is what `run_checks` reports on.
        # Judged raw, the test-source exemption below would fail: the fixture
        # does match the email regex, and it is *suppression* that keeps the
        # gate quiet. Asserting the raw match would test the regex, not the gate.
        with mod.tempfile_tree(files) as root:
            return {rule for rule, _, rel, _ in mod.scan(root) if not mod._is_allowed(rel)}

    def test_ssh_remote_is_not_a_mailbox(self):
        rules = self._rules_for({
            'docs/agents/issue-tracker.md': 'Repository: git@github.com:owner/repo.git\n',
            'scripts/push.sh': 'git push git@github.com:owner/repo.git main\n',
        })
        self.assertNotIn('personal-email', rules)

    def test_reserved_email_domains(self):
        rules = self._rules_for({'notes.md': 'user@mail.com user@example.com u@x.test\n'})
        self.assertNotIn('personal-email', rules)

    def test_ollama_default_port_is_known(self):
        # The LLM provider enum names this endpoint on purpose.
        rules = self._rules_for({'notes.md': 'Default is http://localhost:11434/v1\n'})
        self.assertNotIn('foreign-port', rules)

    def test_test_fixtures_are_exempt(self):
        """A redactor test must contain the string it redacts."""
        rules = self._rules_for({
            'shared/src/jvmTest/kotlin/RedactorTest.kt':
                'val url = "https://user:s3cr3t@abc.supabase.co/rest/v1/tasks"\n',
        })
        self.assertEqual(rules, set())

    def test_placeholder_path_is_clean(self):
        rules = self._rules_for({'.agents/skills/x/SKILL.md': 'cd /absolute/path/to/your/clone\n'})
        self.assertEqual(rules, set())


class AllowlistTest(unittest.TestCase):
    """The allowlist is data; both halves of it must agree."""

    def test_allowlist_file_matches_enforced_prefixes(self):
        self.assertEqual(mod.check_allowlist_matches_prefixes(), [])

    def test_missing_allowlist_is_an_error(self):
        with self.assertRaises(mod.Violation):
            mod.read_allowlist(mod.ROOT / 'config' / 'docs' / 'no-such-allowlist.tsv')

    def test_adr_path_is_suppressed(self):
        with mod.tempfile_tree({
            'docs/decisions/2026-01-01-note.md': 'Worked at /home/someone/work/app.\n',
        }) as root:
            raw = mod.scan(root)
            self.assertTrue(raw, 'the fixture must produce a raw finding')
            self.assertEqual([f for f in raw if not mod._is_allowed(f[2])], [])

    def test_unexpected_prefix_in_the_file_is_reported(self):
        with mod.tempfile_tree({'README.md': 'hello\n'}) as root:
            fake = root / 'allowlist.tsv'
            fake.write_text('src/generated/\n', encoding='utf-8')
            out = mod.check_allowlist_matches_prefixes(fake)
            self.assertTrue(any('src/generated/' in v for v in out), out)

    def test_prefix_missing_from_the_file_is_reported(self):
        with mod.tempfile_tree({'README.md': 'hello\n'}) as root:
            fake = root / 'allowlist.tsv'
            fake.write_text('docs/decisions/\n', encoding='utf-8')
            out = mod.check_allowlist_matches_prefixes(fake)
            self.assertTrue(any('openspec/changes/' in v for v in out), out)


class SelfTestContractTest(unittest.TestCase):
    """The gate's own self-test must pass; it is run from `just docs-audit`."""

    def test_self_test_exits_zero(self):
        self.assertEqual(mod.run_self_test(), 0)


if __name__ == '__main__':
    unittest.main()