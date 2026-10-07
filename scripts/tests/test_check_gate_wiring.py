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

import contextlib
import importlib.util
import pathlib
import sys
import tempfile
import unittest


@contextlib.contextmanager
def _tmpdir():
    with tempfile.TemporaryDirectory() as d:
        yield d

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

    def test_a_sabotage_that_cannot_apply_fails_loudly(self):
        """A control whose premise no longer holds must not pass as a control.

        The `test-runs` sabotage once named the floor's literal value, so raising
        the floor turned it into a no-op: `str.replace` matched nothing, the gate
        received an unmodified file, and the entry still read as a working
        control. Every sabotage that rewrites a value must therefore assert that
        it actually changed something, and this pins that they do.
        """
        import re as _re

        gate = next(g for g in gw.SABOTAGE_ONLY_GATES if g.name == 'test-runs')
        original = (gw.ROOT / gate.sabotage_path).read_text(encoding='utf-8')
        # Move the floor to a value the original literal could not have matched.
        moved = _re.sub(r'^(shared:jvmTest )\d+( \d+)$', r'\g<1>4242\g<2>',
                        original, count=1, flags=_re.M)
        self.assertNotEqual(original, moved, 'fixture did not move the floor')

        target = gw.ROOT / gate.sabotage_path
        try:
            target.write_text(moved, encoding='utf-8')
            ns = {'p': target}
            exec(gate.sabotage, ns)
            sabotaged = target.read_text(encoding='utf-8')
        finally:
            target.write_text(original, encoding='utf-8')

        self.assertNotEqual(
            moved, sabotaged,
            'the sabotage must apply at any floor value, not only the one it was written against',
        )
        self.assertIn('99999', sabotaged)

    def test_a_sabotage_runs_against_the_repository_as_it_is(self):
        """Each sabotage must actually apply to today's file.

        The defect this pins was found because the clean-tree guard fired, and
        the message pointed at the gate rather than at the control. Asserting the
        substitution lands makes the failure legible on its own.
        """
        for gate in gw.SABOTAGE_ONLY_GATES:
            target = gw.ROOT / gate.sabotage_path
            if gate.target_is_dir:
                continue
            before = target.read_text(encoding='utf-8')
            try:
                exec(gate.sabotage, {'p': target})
                after = target.read_text(encoding='utf-8')
            finally:
                target.write_text(before, encoding='utf-8')
            self.assertNotEqual(
                before, after,
                f"{gate.name}: sabotage is a no-op against {gate.sabotage_path} as it stands",
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


class PartGTest(unittest.TestCase):
    """The shared registry is itself wired, and its soft-exit rule is scoped.

    Both properties are about the same thing: the registry is a load-bearing file
    that no reader of `ci.yml` ever sees, so a change to it can disable the gate
    suite without anything failing — unless something checks the registry itself.
    """

    def _registry_in(self, body: str) -> None:
        """Point the module at a temporary registry holding `body`."""
        tmp = pathlib.Path(self._tmp)
        tmp.write_text(body, encoding='utf-8')
        original = gw.GATE_REGISTRY
        gw.GATE_REGISTRY = tmp
        gw.GATE_FILES[:] = [p for p in gw.GATE_FILES if p != original] + [tmp]
        self.addCleanup(lambda: (setattr(gw, 'GATE_REGISTRY', original),
                                 gw.GATE_FILES.__setitem__(
                                     slice(None),
                                     [p for p in gw.GATE_FILES if p != tmp] + [original])))

    def setUp(self) -> None:
        self._tmp = pathlib.Path(self.enterContext(_tmpdir())) / 'static-gates.sh'

    def test_a_gate_invocation_ending_in_or_true_is_rejected(self):
        self._registry_in(
            'gate blocking "thing" python3 scripts/check-thing.py || true\n'
        )
        findings = gw.registry_soft_exits()
        self.assertTrue(findings, 'a `gate` line whose verdict is forced to 0 must be reported')
        self.assertIn('advisory', findings[0], 'the message must name the honest alternative')

    def test_an_interior_or_true_in_a_helper_is_not_reported(self):
        # `grep -c` exits 1 when nothing matches, and the count IS the answer. That
        # idiom is not a suppressed verdict, and flagging it would push an editor
        # into deleting the `|| true` and breaking the helper.
        self._registry_in(
            'n() {\n'
            '  local n\n'
            '  n=$(grep -c pattern file || true)\n'
            '}\n'
            'gate blocking "thing" python3 scripts/check-thing.py\n'
        )
        self.assertEqual(gw.registry_soft_exits(), [])

    def test_set_plus_e_on_a_gate_line_is_rejected(self):
        self._registry_in('gate blocking "thing" set +e; python3 scripts/check-thing.py\n')
        self.assertTrue(gw.registry_soft_exits())

    def test_a_commented_out_line_is_not_a_finding(self):
        self._registry_in('# gate blocking "thing" python3 scripts/check-thing.py || true\n')
        self.assertEqual(gw.registry_soft_exits(), [])

    def test_the_real_registry_has_no_soft_gate_invocation(self):
        if not gw.GATE_REGISTRY.is_file():
            self.skipTest('registry not present in this checkout')
        self.assertEqual(gw.registry_soft_exits(), [])

    def test_both_callers_invoke_the_registry(self):
        if not gw.GATE_REGISTRY.is_file():
            self.skipTest('registry not present in this checkout')
        self.assertEqual(gw.check_registry_wiring(), [],
                         'ci.yml and check.sh must both call scripts/ci/static-gates.sh')

    def test_a_missing_registry_is_reported_by_path(self):
        if gw.GATE_REGISTRY.is_file():
            self.skipTest('registry exists in this checkout')
        errors = gw.check_registry_wiring()
        self.assertTrue(errors)
        self.assertIn('static-gates.sh', errors[0])

    def test_a_caller_that_bypasses_the_registry_is_reported(self):
        # Rewrites check.sh's call away and restores it. The point is that dropping
        # ONE of the two callers is caught; a gate suite that runs in CI only, or
        # locally only, is an asymmetry nobody would notice.
        #
        # The rewrite targets the *invocation*, not the first occurrence of the
        # path: `check_registry_wiring` reads comment-stripped text, so an explanatory
        # comment mentioning the registry does not satisfy it — which is the strictness
        # that makes this test meaningful.
        if not gw.GATE_REGISTRY.is_file():
            self.skipTest('registry not present in this checkout')
        check_sh = gw.ROOT / 'check.sh'
        original = check_sh.read_text(encoding='utf-8')
        invocation = 'bash scripts/ci/static-gates.sh'
        self.assertIn(invocation, original, 'check.sh must invoke the registry to test this')
        check_sh.write_text(original.replace(invocation, 'bash scripts/ci/other.sh', 1),
                            encoding='utf-8')
        self.addCleanup(lambda: check_sh.write_text(original, encoding='utf-8'))
        errors = gw.check_registry_wiring()
        self.assertTrue(any('check.sh' in e for e in errors),
                        f'removing the check.sh call must be reported; got {errors}')


class PartETest(unittest.TestCase):
    """The registry counts on BOTH sides, because it runs in both."""

    def test_the_registry_is_a_gate_surface_in_part_e(self):
        self.assertIn(gw.GATE_REGISTRY, gw.GATE_FILES,
                      'the registry must be scanned, or gates in it are invisible to Part E')

    def test_both_sides_include_the_registry(self):
        for side in ('ci', 'local'):
            self.assertIn('static-gates.sh', gw._gate_texts(side),
                          f'the {side} side must see the registry')


if __name__ == '__main__':
    unittest.main()


class PartHTest(unittest.TestCase):
    """A gate-shaped script nobody reaches reports success forever.

    Parts A–G all begin from a gate somebody already decided to run, so a script
    that exists, can fail, and is named nowhere is invisible to every one of them.
    Each test here is a positive control: it constructs the situation and asserts
    the detector reports it, because "found nothing" and "looked in the wrong
    place" are otherwise the same result.
    """

    def _repo_with(self, files: dict[str, str]) -> pathlib.Path:
        """Build a throwaway repo, point gw at it, restore on teardown."""
        tmp = pathlib.Path(self.enterContext(_tmpdir()))
        for rel, body in files.items():
            p = tmp / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(body, encoding='utf-8')
        original_root = gw.ROOT
        original_surfaces = gw._REACH_SURFACES
        gw.ROOT = tmp
        gw._REACH_SURFACES = (
            [tmp / 'check.sh', tmp / 'scripts/ci/static-gates.sh', tmp / 'justfile']
            + sorted(tmp.glob('.just/**/*.just'))
            + sorted(tmp.glob('.github/workflows/*.yml'))
            + sorted(tmp.glob('.github/actions/*/action.yml'))
        )
        self.addCleanup(lambda: (setattr(gw, 'ROOT', original_root),
                                 setattr(gw, '_REACH_SURFACES', original_surfaces)))
        return tmp

    def test_a_gate_named_by_no_surface_is_reported(self):
        self._repo_with({
            'scripts/check-orphan.py': '#!/usr/bin/env python3\n',
            'scripts/ci/static-gates.sh': 'gate blocking "real" python3 scripts/check-real.py\n',
            'scripts/check-real.py': '#!/usr/bin/env python3\n',
        })
        errors = gw.check_gate_reachability()
        self.assertEqual(len(errors), 1, errors)
        self.assertIn('check-orphan.py', errors[0],
                      'the message must name the unreachable script, or the reader '
                      'has to go looking for it')

    def test_the_real_repository_has_no_unreachable_gate(self):
        self.assertEqual(gw.check_gate_reachability(), [],
                         'every scripts/check* script must be reachable from '
                         'check.sh, the registry, a just recipe, a workflow or a '
                         'composite action')

    def test_a_shim_delegated_gate_is_reachable(self):
        # `check-skill-frontmatter.sh` execs the .py. Reading only surface text
        # calls the .py an orphan, and then a future editor "fixes" a gate that
        # was never broken.
        self._repo_with({
            'scripts/check-skill-frontmatter.sh':
                '#!/usr/bin/env bash\nset -euo pipefail\nROOT="$PWD"\n'
                'exec python3 "$ROOT/scripts/check_skill_frontmatter.py" "$@"\n',
            'scripts/check_skill_frontmatter.py': '#!/usr/bin/env python3\n',
            'scripts/ci/static-gates.sh':
                'gate blocking "skill frontmatter" ./scripts/check-skill-frontmatter.sh\n',
        })
        self.assertEqual(gw.check_gate_reachability(), [])

    def test_both_name_spellings_are_candidates(self):
        # Two of the three gates whose name uses an underscore would be invisible
        # to a hyphen-only pattern, which would then report a short list and call
        # it complete.
        self._repo_with({
            'scripts/check_adr_status.py': '#!/usr/bin/env python3\n',
            'scripts/check-skill-frontmatter.sh': '#!/usr/bin/env bash\n',
        })
        candidates = gw.gate_candidate_scripts()
        self.assertIn('scripts/check_adr_status.py', candidates)
        self.assertIn('scripts/check-skill-frontmatter.sh', candidates)

    def test_a_gate_reachable_only_from_ci_is_reachable(self):
        self._repo_with({
            'scripts/check-flaky-tests.py': '#!/usr/bin/env python3\n',
            '.github/workflows/ci.yml': 'jobs:\n  t:\n    steps:\n'
                                        '      - run: python3 scripts/check-flaky-tests.py \\\n'
                                        '          --current DIR\n',
        })
        self.assertEqual(gw.check_gate_reachability(), [],
                         'a gate named only by a workflow is invoked — ci.yml is a '
                         'gate surface, not an afterthought')

    def test_a_gate_reachable_only_from_a_just_recipe_is_reachable(self):
        self._repo_with({
            'scripts/check-gate-honesty.py': '#!/usr/bin/env python3\n',
            '.just/tests/mod.just': 'honest:\n    python3 scripts/check-gate-honesty.py {{args}}\n',
        })
        self.assertEqual(gw.check_gate_reachability(), [],
                         '.just/**/*.just is nested; a non-recursive glob misses it '
                         'and reports a reachable gate as an orphan')

    def test_a_data_file_with_a_gate_like_name_is_not_a_candidate(self):
        # `scripts/check-dead-settings-baseline.txt` is real and is read by gates.
        self._repo_with({
            'scripts/check-dead-settings-baseline.txt': 'key=value\n',
            'scripts/ci/static-gates.sh': 'gate blocking "x" python3 scripts/check-x.py\n',
            'scripts/check-x.py': '#!/usr/bin/env python3\n',
        })
        self.assertNotIn('scripts/check-dead-settings-baseline.txt',
                         gw.gate_candidate_scripts())

    def test_two_shims_delegating_to_each_other_terminate(self):
        self._repo_with({
            'scripts/check-a.py': '#!/usr/bin/env bash\nexec bash "$PWD/scripts/check-b.sh"\n',
            'scripts/check-b.sh': '#!/usr/bin/env bash\nexec bash "$PWD/scripts/check-a.py"\n',
            'scripts/ci/static-gates.sh': 'gate blocking "x" python3 scripts/check-a.py\n',
        })
        # The point is that it returns at all.
        self.assertEqual(gw.check_gate_reachability(), [])
