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


class PartFTest(unittest.TestCase):
    """The registry of positive controls is derived, not trusted.

    Part B proves each registered gate can fail. Part F asks the question that
    makes Part B mean anything: whether the registry covers the gates that exist.
    Without it the list is a hand-written claim about what has been verified,
    which is the defect this file exists to catch, one level up.

    The derivation is the part worth pinning, because a derivation that matches
    a third of its input still produces a confident, short, complete-looking
    list. Two versions of that happened while writing this: a `\./?` that
    required a literal dot and saw 3 of 18 gates, and no word boundary, which
    turned `Maestro/scripts/check-tags.sh` into a path that does not exist and
    then demanded a control for it.
    """

    def test_the_repository_registers_the_gates_it_thinks_it_does(self):
        """The derivation must see every gate surface, not just check.sh.

        Four gates are reachable only through a `just` recipe; a derivation that
        read only `check.sh` would report the registry complete while three of
        the run-evidencing gates were invisible to it.
        """
        registered = gw.registered_gate_scripts()
        self.assertIn('scripts/check-test-runs.py', registered)
        self.assertIn('scripts/check-coverage.py', registered)
        self.assertIn('scripts/check-flaky-tests.py', registered)
        # just-recipe-only gates
        self.assertIn('scripts/check-kiwi-gaps.py', registered)
        self.assertIn('scripts/check-coverage-measurement.py', registered)

    def test_a_python3_invocation_without_a_dot_slash_is_registered(self):
        """`python3 scripts/x.py` is the commonest spelling in check.sh."""
        registered = gw.registered_gate_scripts()
        self.assertIn('scripts/check-backlog-status.py', registered)

    def test_a_shell_gate_is_registered(self):
        self.assertIn('scripts/check-detekt-registrations.sh',
                      gw.registered_gate_scripts())

    def test_a_path_prefix_is_not_truncated(self):
        """`Maestro/scripts/check-tags.sh` is not `scripts/check-tags.sh`.

        A match that drops the leading directory invents a gate, and Part F then
        asks for a control for something that does not exist — a false finding
        that trains the reader to ignore the gate.

        Asserted as "every registered path is a real file" rather than as
        "`check-tags.sh` is absent", because the absence held even with the
        boundary removed: the invented path was `scripts/check-tags.sh`, which
        does not exist, so the old assertion passed on the broken pattern. The
        property is that no entry can be a fiction.
        """
        registered = gw.registered_gate_scripts()
        for script in registered:
            self.assertTrue(
                (gw.ROOT / script).is_file(),
                f"{script} is registered but does not exist — the pattern invented it",
            )

    def test_the_real_tags_gate_is_not_registered_under_a_truncated_path(self):
        self.assertNotIn('scripts/check-tags.sh', gw.registered_gate_scripts())
        # The genuine one lives under Maestro/ and is not a `scripts/` gate at all.
        self.assertNotIn('Maestro/scripts/check-tags.sh', gw.registered_gate_scripts())

    def test_every_registered_gate_is_controlled_or_exempt(self):
        """The property Part F exists to assert, on the real repository."""
        self.assertEqual(gw.check_registry_completeness(), [])

    def test_an_uncontrolled_gate_is_reported_by_name(self):
        """The negative control, without touching the repository.

        A gate with no control must produce a finding naming it. A registry that
        cannot report a gap is a registry nobody will trust to report a pass.

        Every mutated global is restored in a `finally`: an earlier version reset
        only `GATE_EXEMPTIONS`, so the three control lists stayed empty for every
        test that ran afterwards and the suite reported failures that had nothing
        to do with what was being tested.
        """
        saved = (gw.GATE_EXEMPTIONS, gw.SCRIPT_GATES, gw.SABOTAGE_ONLY_GATES, gw.FIXTURE_GATES)
        try:
            gw.GATE_EXEMPTIONS = {}
            gw.SCRIPT_GATES = []
            gw.SABOTAGE_ONLY_GATES = []
            gw.FIXTURE_GATES = []
            errors = gw.check_registry_completeness()
        finally:
            (gw.GATE_EXEMPTIONS, gw.SCRIPT_GATES,
             gw.SABOTAGE_ONLY_GATES, gw.FIXTURE_GATES) = saved
        self.assertTrue(errors, "an empty registry must report every gate as uncontrolled")
        joined = ' '.join(errors)
        self.assertIn('scripts/check-test-runs.py', joined)
        self.assertIn('scripts/check-coverage.py', joined)
        # And the registry must be whole again — the negative control has to be
        # a measurement, not a permanent change to the thing being measured.
        self.assertEqual(gw.check_registry_completeness(), [])

    def test_an_exemption_must_name_a_reason(self):
        """An exemption with an empty string is an exemption with no defence."""
        for script, reason in gw.GATE_EXEMPTIONS.items():
            self.assertTrue(reason.strip(), f"{script} is exempt with no reason")

    def test_every_control_is_measured_against_a_real_target(self):
        """Each sabotage entry must name a path that exists.

        A control pointing at a file that is not there is skipped by `check_can_fail`
        with an error, but only once that part runs; pinning it here means the
        registry cannot accumulate entries that were never exercised.
        """
        for gate in [*gw.SCRIPT_GATES, *gw.SABOTAGE_ONLY_GATES]:
            target = gw.ROOT / gate.sabotage_path
            self.assertTrue(
                target.is_dir() if gate.target_is_dir else target.is_file(),
                f"{gate.name}: sabotage target {gate.sabotage_path} does not exist",
            )

    def test_a_fixture_gate_declares_whether_a_clean_run_is_possible(self):
        """The weaker control must be declared, never inferred.

        Three fixture gates take a required argument, so no invocation of them
        means "the clean repository". That is a real limitation and it is
        recorded per entry; a gate that quietly skipped the guard would be
        reporting a check that cannot run as one that passed.
        """
        for gate in gw.FIXTURE_GATES:
            self.assertIn(gate.needs_clean_run, (True, False))
        names = {g.name for g in gw.FIXTURE_GATES}
        self.assertIn('flaky-tests', names)
        self.assertFalse(
            next(g for g in gw.FIXTURE_GATES if g.name == 'flaky-tests').needs_clean_run
        )

    def test_the_two_control_kinds_do_not_cover_the_same_gate(self):
        """A gate in both lists would be sabotaged twice, and one entry's
        failure would be reported under the other's name."""
        sabotage_scripts = set()
        for gate in [*gw.SCRIPT_GATES, *gw.SABOTAGE_ONLY_GATES]:
            sabotage_scripts.update(p for p in gate.cmd if p.endswith('.py') or p.endswith('.sh'))
        for gate in gw.FIXTURE_GATES:
            for part in gate.cmd:
                self.assertNotIn(part, sabotage_scripts, f"{gate.name} is in both registries")


if __name__ == '__main__':
    unittest.main()
