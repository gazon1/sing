#!/usr/bin/env python3
"""Tests for check-gate-wiring.py's Parts C and D.

Parts C and D answer the question Parts A and B leave open. A asks whether a
Gradle check task is invoked; B proves a *script* can fail. Neither asks whether
an invoked Gradle task can fail, or whether a CI step that cannot fail says so.

Every test below is about proving the check still *sees* something. A gate that
has only ever reported zero is indistinguishable from a gate that cannot fail,
which is the defect class the whole file exists to catch — including in the two
parts added here.
"""

import importlib.util
import pathlib
import sys
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / 'check-gate-wiring.py'

# The module registers `@dataclass` types, and the dataclass machinery looks the
# defining module up in `sys.modules` by name. Loading it through importlib
# without registering it first makes every dataclass in the file raise
# AttributeError on a None module — so the registration is not optional.
_spec = importlib.util.spec_from_file_location('check_gate_wiring', SCRIPT)
gw = importlib.util.module_from_spec(_spec)
sys.modules['check_gate_wiring'] = gw
_spec.loader.exec_module(gw)


class PartCTest(unittest.TestCase):
    """A `detekt { }` block that sets `ignoreFailures = true` cannot fail."""

    DETEKT_ENFORCING = '''
detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    ignoreFailures = false   // enforcing
    source.setFrom("src/main/kotlin")
}
'''

    DETEKT_SOFT = '''
detekt {
    config.setFrom(rootProject.file("config/detekt/detekt-minimal.yml"))
    ignoreFailures = true
    source.setFrom("src/main/kotlin")
}
'''

    def test_a_soft_block_is_recognised(self):
        self.assertTrue(
            gw._DETEKT_BLOCK.search(self.DETEKT_SOFT),
            'the block regex must find the detekt block at all',
        )
        blocks = gw._DETEKT_BLOCK.findall(self.DETEKT_SOFT)
        self.assertTrue(
            any('ignoreFailures = true' in b for b in blocks),
        )

    def test_an_enforcing_block_is_not_matched(self):
        blocks = gw._DETEKT_BLOCK.findall(self.DETEKT_ENFORCING)
        self.assertTrue(blocks, 'fixture must contain a detekt block')
        self.assertFalse(any('ignoreFailures = true' in b for b in blocks))

    def test_ignore_failures_in_another_block_does_not_leak(self):
        """`ignoreFailures` outside the detekt block is somebody else's flag."""
        src = self.DETEKT_ENFORCING + '''
tasks.withType<Test>().configureEach {
    ignoreFailures = true
}
'''
        blocks = gw._DETEKT_BLOCK.findall(src)
        self.assertFalse(any('ignoreFailures = true' in b for b in blocks))

    def test_the_repository_is_clean(self):
        """No module in the tree may opt out of enforcement right now."""
        self.assertEqual([], gw.non_enforcing_gradle_tasks())


class SoftStepTest(unittest.TestCase):
    """`|| true` on the last command softens a step; an interior one does not."""

    def test_trailing_or_true_softens_the_step(self):
        self.assertTrue(gw._softens_the_step('python3 scripts/check.py || true\n'))

    def test_interior_or_true_does_not_soften_the_step(self):
        """The maestro shard case: `grep . || true` inside a function body.

        GitHub runs a run-block under `bash -eo pipefail`, so an interior
        `|| true` is a defensive idiom. Flagging it would report a correct
        workflow as broken, and the tempting fix — deleting the `|| true` —
        would break the shard loop that needs it.
        """
        src = '''FLOWS=$(find Maestro/flows -name '*.yaml' | tr '\\n' ' ')
nth_shard() {
  echo "$FLOWS" | cut -d' ' -f"$1-$2" | tr '\\n' '\\n' | grep . || true
}
echo "total=$FLOW_COUNT" >> $GITHUB_OUTPUT
'''
        self.assertFalse(gw._softens_the_step(src))

    def test_trailing_comment_after_a_command_does_not_hide_a_soft_tail(self):
        self.assertTrue(
            gw._softens_the_step('python3 scripts/check.py || true\n# a trailing note\n')
        )

    def test_a_block_with_no_commands_is_not_soft(self):
        self.assertFalse(gw._softens_the_step('   \n\n'))


class AdvisoryDeclarationTest(unittest.TestCase):
    """Part D's whole contract: a soft step has to say so."""

    def test_name_containing_advisory_counts(self):
        self.assertTrue(gw._ADVISORY_MARKER.search('Check ADR frontmatter (advisory)'))

    def test_comment_containing_advisory_counts(self):
        self.assertTrue(
            gw._ADVISORY_MARKER.search('# Advisory, and deliberately so: ...')
        )

    def test_a_comment_that_says_something_else_does_not_count(self):
        """The vocabulary is fixed on purpose.

        A step whose comment reads "Annotation, not a gate" explains itself and
        is still undeclared. Rather than widen the pattern until it accepts
        whatever prose happens to be there — which is how a check stops proving
        anything — the convention is one word, and the comment is edited to use
        it. The two ci.yml steps that were caught this way were fixed by saying
        "advisory", not by teaching the check new synonyms.
        """
        self.assertFalse(gw._ADVISORY_MARKER.search('# Annotation, not a gate'))

    def test_an_unrelated_name_is_not_declared(self):
        self.assertFalse(gw._ADVISORY_MARKER.search('Enforce DIGEST size budget'))


if __name__ == '__main__':
    unittest.main()
