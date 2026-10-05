"""Tests for check-readme-claims.py.

The gate's claim is that a number printed in the README is the number in the
tree. Three ways it could pass while printing nothing useful, and each has a test.

  - a README that contradicts the tree goes unreported (SourceOfTruthTest)
  - a README that matches the tree is reported anyway (false positives get a
    gate switched off, and a switched-off gate protects nothing)
  - the `+` lower bound is read from the wrong occurrence, which would make every
    `N+` claim unenforceable while looking correct (PlusSuffixTest)

That last one is not hypothetical. The first implementation recovered the `+` by
searching the whole README for the literal `"<digits>+"`, so a `270+` on one line
laundered an unrelated `90+` on another into "this is a lower bound" — and an
inflated `9+ ADRs` claim passed while the tree held 5. Worse, the self-test that
should have caught it silently counted the *real* repository's ADRs, because two
of the counting helpers ignored the root they were handed. Both are pinned here.
"""

import importlib.util
import pathlib
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-readme-claims.py'
spec = importlib.util.spec_from_file_location('check_readme_claims', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def corpus(readme: str, tools: int = 4, schema: int = 20,
           adrs: int = 6, skills: int = 3) -> dict[str, str]:
    return mod._corpus(readme, tools=tools, schema=schema, adrs=adrs, skills=skills)


GOOD = (
    "# T\n\n"
    "| AI | 4 Koog-powered tools |\n"
    "| MCP | 4 read/write/list tools |\n"
    "Auto-generated index of 6+ ADRs\n"
    "Auto-generated index of 3+ agent skills\n"
    "Room with auto-migrations (schema v20)\n"
)


class SourceOfTruthTest(unittest.TestCase):
    """Each claim is derived from the tree, and a wrong README is reported."""

    def test_consistent_readme_is_silent(self):
        with mod._tree(corpus(GOOD)) as root:
            self.assertEqual(mod.run_checks(root)[0], [])

    def test_every_claim_type_is_reported_when_wrong(self):
        bad = (GOOD.replace('4 Koog-powered', '9 Koog-powered')
                  .replace('schema v20', 'schema v11')
                  .replace('6+ ADRs', '40+ ADRs')
                  .replace('3+ agent skills', '99+ agent skills'))
        with mod._tree(corpus(bad)) as root:
            violations = mod.run_checks(root)[0]
        self.assertEqual(len(violations), 4, violations)

    def test_badge_claim_is_checked_too(self):
        # The `32` badge appears in a URL-encoded form that no prose regex would
        # match; it is the number a reader sees first.
        bad = GOOD.replace('| AI | 4 Koog-powered tools |',
                           '| AI | 4 Koog-powered tools |\n'
                           '<img src="https://x/badge/MCP%20Server-9%20tools.svg">')
        with mod._tree(corpus(bad)) as root:
            self.assertTrue(mod.run_checks(root)[0])

    def test_missing_claim_is_reported(self):
        bare = "# T\n\nNothing numeric here.\n"
        with mod._tree(corpus(bare)) as root:
            violations = mod.run_checks(root)[0]
        self.assertEqual(len(violations), 4, violations)

    def test_schema_version_is_read_from_the_constant(self):
        self.assertEqual(mod.schema_version(mod.DB_FILE), 38)


class PlusSuffixTest(unittest.TestCase):
    """`N+` means "at least N", and the flag must belong to its own occurrence."""

    def test_plus_below_the_truth_is_accepted(self):
        # Understating is not a false claim; it is how a growing count is written.
        modest = GOOD.replace('6+ ADRs', '2+ ADRs')
        with mod._tree(corpus(modest, adrs=6)) as root:
            self.assertEqual(mod.run_checks(root)[0], [])

    def test_plus_above_the_truth_is_rejected(self):
        inflated = GOOD.replace('6+ ADRs', '40+ ADRs')
        with mod._tree(corpus(inflated, adrs=6)) as root:
            violations = mod.run_checks(root)[0]
        self.assertTrue(any('ADRs' in v for v in violations), violations)

    def test_plus_flag_does_not_leak_between_occurrences(self):
        """The regression this whole test class exists for.

        `270+` and an unrelated `90+` in the same README must be judged
        independently. Searching the raw text for `"<n>+"` made any `+` on the
        page vouch for every claim, which is how an inflated lower bound passed.
        """
        text = GOOD.replace('6+ ADRs', '40+ ADRs') \
                   .replace('3+ agent skills', '3 agent skills')
        claims = mod.parse_claims(text)
        self.assertIn((40, True), claims['adr_count'])
        self.assertIn((3, False), claims['skill_count'])


class ToolParsingTest(unittest.TestCase):
    """The tool count is parsed from the DI list, not guessed from source text."""

    def test_counts_every_entry(self):
        tools = mod.mcp_tools(mod.MCP_JVM)
        self.assertEqual(len(tools), len(set(tools)), 'duplicate registration')
        self.assertGreater(len(tools), 30)

    def test_jvm_and_android_declare_the_same_tools(self):
        self.assertEqual(
            set(mod.mcp_tools(mod.MCP_JVM)), set(mod.mcp_tools(mod.MCP_ANDROID)),
            'the two platform DI modules must register the same tool set',
        )

    def test_platform_divergence_is_reported(self):
        """A README number cannot describe both platforms if they differ.

        This is the defect the check was built to surface: the Android list was
        missing two project tools, so the AI agent on Android could create and
        update a project but never read or delete one.
        """
        files = corpus(GOOD)
        files["shared/src/androidMain/kotlin/com/singularity/todo/core/di/AiToolsModule.android.kt"] \
            = mod._mcp_module(4, offset=100)
        with mod._tree(files) as root:
            violations = mod.run_checks(root)[0]
        self.assertTrue(any('Android' in v for v in violations), violations)

    def test_unparseable_binding_is_an_error_not_a_pass(self):
        with self.assertRaises(mod.Violation):
            mod.mcp_tools(mod.ROOT / 'README.md')


class SelfTestContractTest(unittest.TestCase):
    """The gate's own self-test is run from `just docs-audit`."""

    def test_self_test_exits_zero(self):
        self.assertEqual(mod.run_self_test(), 0)


if __name__ == '__main__':
    unittest.main()