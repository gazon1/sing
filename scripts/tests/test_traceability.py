"""Unit tests for the traceability pure core.

No stand, no network, no emulator — the whole point of the inversion is that
this layer is testable in milliseconds. Run with::

    python3 -m unittest discover -s scripts/tests

The traceability package lives in ``infra/kiwi/traceability``, which is imported
by name rather than as a path-relative package: ``infra/kiwi`` is a flat script
directory, so there is no installed package to resolve against. The bootstrap
below mirrors what ``test_kiwi_sync.py`` does for ``sync.py``.
"""

from __future__ import annotations

import json
import pathlib
import sys
import tempfile
import unittest
from dataclasses import asdict

_KIWI_DIR = pathlib.Path(__file__).resolve().parent.parent.parent / "infra" / "kiwi"
if str(_KIWI_DIR) not in sys.path:
    sys.path.insert(0, str(_KIWI_DIR))

from traceability import ValidationError  # noqa: E402
from traceability.coverage import (  # noqa: E402
    ALL_TARGETS,
    Outcome,
    build_coverage,
    build_results,
)
from traceability.junit_xml import parse_junit  # noqa: E402
from traceability.kiwi_publish import OUTCOME_TO_KIWI, build_runs, run_publish  # noqa: E402
from traceability.normalize import (  # noqa: E402
    NoResultsError,
    NormalisedResult,
    read_results,
)
from traceability.keys import (  # noqa: E402
    TestKey,
    normalise_classname,
    normalise_test_name,
)
from traceability.links import Carrier, Link, _scenario_from_prefix_token  # noqa: E402
from traceability.normalize import normalise  # noqa: E402
from traceability.render import render_coverage_matrix, render_result_matrix  # noqa: E402
from traceability.spec import (  # noqa: E402
    Level,
    ScenarioSpec,
    SpecStatus,
    Target,
    load_specs,
    parse_spec,
)

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


def _spec(scenario_id: str = "TASK-REC-01", **overrides) -> ScenarioSpec:
    """A minimal valid spec, for tests that are not about spec validation."""
    base = {
        "id": scenario_id,
        "title": "A scenario",
        "priority": "P1",
        "status": SpecStatus.CONFIRMED,
        "targets": (Target.ANDROID, Target.DESKTOP),
        "preconditions": "",
        "steps": ("do the thing",),
        "expected": "it happened",
        "area": "feature.tasks",
        "id_prefix": "TASK-REC",
    }
    base.update(overrides)
    return ScenarioSpec(**base)


def _link(target: Target = Target.DESKTOP, scenario: str = "TASK-REC-01") -> Link:
    return Link(
        scenario=scenario,
        target=target,
        level=Level.E2E,
        carrier=Carrier.KOTLIN,
        source=REPO_ROOT / "shared/src/jvmTest/kotlin/Foo.kt",
        key=TestKey("com.example.Foo", "does_a_thing"),
    )


def _write(directory: pathlib.Path, name: str, body: str) -> pathlib.Path:
    target = directory / name
    target.write_text(body, encoding="utf-8")
    return target


def _junit(directory: pathlib.Path, cases: str, name: str = "TEST-x.xml") -> pathlib.Path:
    return _write(
        directory,
        name,
        '<?xml version="1.0" encoding="UTF-8"?>'
        f'<testsuite name="s" tests="1">{cases}</testsuite>',
    )


class TestKeyNormalisation(unittest.TestCase):
    """The join key. Both consumers must agree on it, so it is pinned per shape."""

    def test_strips_trailing_parens(self):
        # Desktop shape: no platform suffix, parens present.
        self.assertEqual(normalise_test_name("shell_boots_into_the_today_agenda()"), "shell_boots_into_the_today_agenda")

    def test_strips_platform_suffix_and_parens(self):
        # shared/KMP shape: BOTH the suffix and the parens.
        self.assertEqual(normalise_test_name("scanner_flags_a_read()[jvm]"), "scanner_flags_a_read")

    def test_suffix_stripped_before_parens(self):
        # Order matters: stripping parens first leaves "foo()[jvm]" untouched.
        self.assertEqual(normalise_test_name("foo()[android]"), "foo")

    def test_bare_name_is_unchanged(self):
        self.assertEqual(normalise_test_name("foo"), "foo")

    def test_normalisation_is_idempotent(self):
        # Applied twice must equal applied once, or a re-normalised key would
        # stop matching the index the scanner wrote.
        for raw in ("foo()", "foo()[jvm]", "foo", "SPIKE-01 some prose"):
            once = normalise_test_name(raw)
            self.assertEqual(normalise_test_name(once), once, raw)

    def test_display_name_survives_normalisation(self):
        # The whole linkage rides on this: a @DisplayName reaches the XML as
        # `name`, so normalisation must not eat the id out of it.
        self.assertEqual(normalise_test_name("TASK-REC-01 a daily task"), "TASK-REC-01 a daily task")

    def test_parameterised_name_keeps_its_arguments(self):
        # Only a trailing *empty* paren pair is noise. A real argument list is
        # part of the test's identity and must survive.
        self.assertEqual(normalise_test_name("evaluates(a, b)"), "evaluates(a, b)")

    def test_nested_classname_is_dotted(self):
        # JUnit writes @Nested as Outer$Inner; the scanner must meet it there.
        self.assertEqual(normalise_classname("a.b.Outer$Inner"), "a.b.Outer.Inner")

    def test_plain_classname_unchanged(self):
        self.assertEqual(normalise_classname("a.b.Foo"), "a.b.Foo")

    def test_both_xml_shapes_yield_one_key(self):
        # The property the join depends on: the same test, described by the two
        # different XML shapes Gradle emits, produces ONE key.
        shared = TestKey.from_xml("com.example.FooTest", "some_test()[jvm]")
        desktop = TestKey.from_xml("com.example.FooTest", "some_test()")
        self.assertEqual(shared, desktop)

    def test_different_tests_do_not_collide(self):
        a = TestKey.from_xml("com.example.Foo", "one()")
        b = TestKey.from_xml("com.example.Foo", "two()")
        self.assertNotEqual(a, b)


class PrefixToken(unittest.TestCase):
    """The id must be the first token, or it is prose that happens to mention one."""

    def test_leading_id_is_extracted(self):
        self.assertEqual(_scenario_from_prefix_token("TASK-REC-01 create a daily task"), "TASK-REC-01")

    def test_bare_id_is_extracted(self):
        self.assertEqual(_scenario_from_prefix_token("TASK-REC-01"), "TASK-REC-01")

    def test_id_not_first_is_not_extracted(self):
        # The rule: a scanner that accepts an id mid-string eventually claims a
        # scenario for a test that merely discusses it.
        self.assertIsNone(_scenario_from_prefix_token("creates a TASK-REC-01 daily task"))

    def test_ordinary_prose_is_not_an_id(self):
        self.assertIsNone(_scenario_from_prefix_token("evaluates the agenda buckets"))

    def test_longer_id_does_not_match_shorter_prefix(self):
        # TASK-REC-011 must not be read as TASK-REC-01.
        self.assertEqual(_scenario_from_prefix_token("TASK-REC-011 x"), "TASK-REC-011")


class JunitReader(unittest.TestCase):
    def test_outcomes_from_child_elements(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _junit(
                d,
                '<testcase classname="C" name="a()"><failure message="boom"/></testcase>'
                '<testcase classname="C" name="b()"><error message="infra"/></testcase>'
                '<testcase classname="C" name="c()"><skipped/></testcase>'
                '<testcase classname="C" name="d()"/>',
            )
            results = {r.name: r.status for r in parse_junit([d])}
        self.assertEqual(results, {"a()": "failed", "b()": "error", "c()": "skipped", "d()": "passed"})

    def test_file_attribute_is_read(self):
        # Maestro identifies a flow by its path; without this the flow join has
        # no key at all.
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _junit(d, '<testcase classname="C" name="n" file="flows/a.yaml"/>')
            self.assertEqual(parse_junit([d])[0].file, "flows/a.yaml")

    def test_malformed_xml_is_skipped_not_raised(self):
        # One bad artifact must not abort a whole sync.
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _write(d, "TEST-bad.xml", "<testsuite><not-closed>")
            self.assertEqual(parse_junit([d]), [])

    def test_missing_directory_yields_nothing(self):
        self.assertEqual(parse_junit([pathlib.Path("/nonexistent-xyz")]), [])


class SpecValidation(unittest.TestCase):
    """Specs are the source of truth, so their rules are hard errors."""

    def _parse(self, data, path="tasks/recurrence/TASK-REC-01.yaml"):
        scenarios = REPO_ROOT / "infra/kiwi/scenarios"
        return parse_spec(data, scenarios / path, scenarios)

    def _minimal(self, **overrides):
        data = {
            "id": "TASK-REC-01",
            "title": "t",
            "priority": "P1",
            "status": "confirmed",
            "targets": ["android", "desktop"],
            "steps": ["do it"],
        }
        data.update(overrides)
        return data

    def test_valid_spec_parses(self):
        spec = self._parse(self._minimal())
        self.assertEqual(spec.id, "TASK-REC-01")
        self.assertEqual(spec.targets, (Target.ANDROID, Target.DESKTOP))

    def test_area_is_derived_from_path_not_declared(self):
        spec = self._parse(self._minimal())
        self.assertEqual(spec.area, "feature.tasks")

    def test_declared_area_must_match_the_path(self):
        # Stated, then cross-checked — not a third free-floating copy.
        with self.assertRaises(ValidationError) as ctx:
            self._parse(self._minimal(area="feature.notes"))
        self.assertIn("area", str(ctx.exception))

    def test_matching_declared_area_is_accepted(self):
        self.assertEqual(self._parse(self._minimal(area="feature.tasks")).area, "feature.tasks")

    def test_p0_is_rejected(self):
        # Kiwi has no P0; mapping it onto a neighbour would be a silent lie.
        with self.assertRaises(ValidationError) as ctx:
            self._parse(self._minimal(priority="P0"))
        self.assertIn("P0", str(ctx.exception))

    def test_status_must_be_a_lifecycle_value(self):
        # passed/failed are EXECUTION status and must never appear in a spec.
        with self.assertRaises(ValidationError):
            self._parse(self._minimal(status="passed"))

    def test_automation_reference_is_rejected(self):
        # Linkage lives in code; a spec listing tests would be a second truth.
        with self.assertRaises(ValidationError) as ctx:
            self._parse(self._minimal(tests=["FooTest"]))
        self.assertIn("tests", str(ctx.exception))

    def test_kiwi_reference_is_rejected(self):
        with self.assertRaises(ValidationError):
            self._parse(self._minimal(kiwi_case=42))

    def test_unknown_field_is_rejected(self):
        with self.assertRaises(ValidationError) as ctx:
            self._parse(self._minimal(colour="blue"))
        self.assertIn("colour", str(ctx.exception))

    def test_bad_id_format_is_rejected(self):
        with self.assertRaises(ValidationError) as ctx:
            self._parse(self._minimal(id="task-rec-01"), path="tasks/recurrence/task-rec-01.yaml")
        self.assertIn("шаблон", str(ctx.exception))

    def test_filename_must_match_id(self):
        with self.assertRaises(ValidationError) as ctx:
            self._parse(self._minimal(), path="tasks/recurrence/OTHER-01.yaml")
        self.assertIn("имя файла", str(ctx.exception))

    def test_id_prefix_must_agree_with_path(self):
        # A scenario claiming to be about tasks inside notes/ is a copy-paste.
        with self.assertRaises(ValidationError) as ctx:
            self._parse(self._minimal(), path="notes/recurrence/TASK-REC-01.yaml")
        self.assertIn("каталог", str(ctx.exception))

    def test_prefix_match_tolerates_the_abbreviation(self):
        # TASK-REC in tasks/recurrence/ must pass: ids are deliberately terser
        # than directories. A strict equality check would reject the project's
        # own canonical example.
        self.assertEqual(self._parse(self._minimal()).id_prefix, "TASK-REC")

    def test_missing_steps_is_rejected(self):
        # A scenario with no steps cannot be verified.
        with self.assertRaises(ValidationError) as ctx:
            self._parse(self._minimal(steps=[]))
        self.assertIn("steps", str(ctx.exception))

    def test_all_problems_reported_together(self):
        # One error per run turns a three-line fix into three runs.
        with self.assertRaises(ValidationError) as ctx:
            self._parse(self._minimal(priority="P0", status="passed", targets=[]))
        message = str(ctx.exception)
        self.assertIn("P0", message)
        self.assertIn("status", message)
        self.assertIn("targets", message)

    def test_duplicate_ids_are_an_error(self):
        # Last-wins would make the matrix a function of glob order, and a
        # matrix that changes with directory order is not checkable in CI.
        #
        # Two directories whose names differ only in case both pass the
        # case-insensitive id-prefix check, so this is the only shape that
        # reaches the guard — which is the point: the guard is a backstop
        # behind the stricter rules, not the primary defence.
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            body = (
                "id: TASK-REC-01\ntitle: t\npriority: P1\nstatus: confirmed\n"
                "targets: [android]\nsteps: [do it]\n"
            )
            (d / "tasks/recurrence").mkdir(parents=True)
            (d / "Tasks/Recurrence").mkdir(parents=True)
            _write(d / "tasks/recurrence", "TASK-REC-01.yaml", body)
            _write(d / "Tasks/Recurrence", "TASK-REC-01.yaml", body)
            with self.assertRaises(ValidationError) as ctx:
                load_specs(d)
            self.assertIn("дубликат", str(ctx.exception).lower())


class NormaliseRules(unittest.TestCase):
    def _run(self, cases_xml, links, result_dirs, commit="abc1234", **kw):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _junit(d, cases_xml)
            return normalise({"TASK-REC-01": _spec()}, links, {Target.DESKTOP: [d]}, commit, **kw)

    def test_keeps_linked_testcases_only(self):
        # The 259 legacy tests must not become cases just by being in the XML.
        report = self._run('<testcase classname="com.example.Foo" name="does_a_thing()"/>', [_link()], None)
        self.assertEqual(report.kept, 1)
        self.assertEqual(report.dropped, 0)

        report = self._run(
            '<testcase classname="com.example.Other" name="unrelated()"/>'
            '<testcase classname="com.example.Foo" name="does_a_thing()"/>',
            [_link()],
            None,
        )
        self.assertEqual(report.kept, 1)
        self.assertEqual(report.dropped, 1)

    def test_failure_message_and_time_are_preserved(self):
        report = self._run(
            '<testcase classname="com.example.Foo" name="does_a_thing()" time="1.5">'
            '<failure message="boom"/></testcase>',
            [_link()],
            None,
        )
        row = report.results[0]
        self.assertEqual(row.outcome, Outcome.FAILED.value)
        self.assertAlmostEqual(row.time, 1.5)

    def test_error_is_a_failing_outcome(self):
        # An infrastructure error must never be laundered into a pass.
        report = self._run(
            '<testcase classname="com.example.Foo" name="does_a_thing()"><error message="x"/></testcase>',
            [_link()],
            None,
        )
        self.assertEqual(report.results[0].outcome, Outcome.FAILED.value)

    def test_empty_target_is_a_hard_error(self):
        # The "quietly green" case: a Gradle task that ran nothing.
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _junit(d, '<testcase classname="com.example.Nobody" name="x()"/>')
            with self.assertRaises(ValidationError) as ctx:
                normalise({"TASK-REC-01": _spec()}, [_link()], {Target.DESKTOP: [d]}, "abc1234")
            self.assertIn("ни одного тесткейса", str(ctx.exception))

    def test_stale_commit_is_rejected(self):
        # Decided by commit identity, not by file age.
        with self.assertRaises(ValidationError) as ctx:
            self._run(
                '<testcase classname="com.example.Foo" name="does_a_thing()"/>',
                [_link()],
                None,
                commit="old123",
                expected_commit="new456",
            )
        self.assertIn("устарел", str(ctx.exception))

    def test_matching_commit_is_accepted(self):
        report = self._run(
            '<testcase classname="com.example.Foo" name="does_a_thing()"/>',
            [_link()],
            None,
            commit="abc1234",
            expected_commit="abc1234",
        )
        self.assertEqual(report.results[0].commit, "abc1234")

    def test_missing_commit_is_rejected(self):
        with self.assertRaises(ValidationError):
            self._run('<testcase classname="com.example.Foo" name="does_a_thing()"/>', [_link()], None, commit="")

    def test_two_tests_for_one_scenario_is_an_error_not_a_merge(self):
        # A merged cell hides exactly the disagreement the matrix exists to show.
        links = [
            _link(),
            Link(
                scenario="TASK-REC-01",
                target=Target.DESKTOP,
                level=Level.E2E,
                carrier=Carrier.KOTLIN,
                source=REPO_ROOT / "shared/src/jvmTest/kotlin/Bar.kt",
                key=TestKey("com.example.Bar", "also_a_thing"),
            ),
        ]
        with self.assertRaises(ValidationError) as ctx:
            self._run(
                '<testcase classname="com.example.Foo" name="does_a_thing()"/>'
                '<testcase classname="com.example.Bar" name="also_a_thing()"/>',
                links,
                None,
            )
        self.assertIn("несколько результатов", str(ctx.exception))

    def test_result_in_the_wrong_target_section_is_an_error(self):
        # A test claimed for android cannot be satisfied by desktop's XML.
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _junit(d, '<testcase classname="com.example.Foo" name="does_a_thing()"/>')
            with self.assertRaises(ValidationError) as ctx:
                normalise({"TASK-REC-01": _spec()}, [_link(Target.ANDROID)], {Target.DESKTOP: [d]}, "abc1234")
            self.assertIn("android", str(ctx.exception))


class CoverageAndResults(unittest.TestCase):
    def test_three_glyphs(self):
        spec = _spec()
        coverage = build_coverage({"TASK-REC-01": spec}, [_link(Target.DESKTOP)])
        self.assertEqual(coverage.glyph("TASK-REC-01", Target.DESKTOP), "●")
        # Claimed but unbuilt — a hole, and the thing the matrix exists to show.
        self.assertEqual(coverage.glyph("TASK-REC-01", Target.ANDROID), "○")
        self.assertEqual(coverage.holes(), [("TASK-REC-01", Target.ANDROID)])

    def test_unclaimed_target_is_a_dash(self):
        # Only desktop is claimed, so android is not an obligation — and
        # crucially not a hole either.
        spec = _spec(targets=(Target.DESKTOP,))
        coverage = build_coverage({"TASK-REC-01": spec}, [])
        self.assertEqual(coverage.glyph("TASK-REC-01", Target.ANDROID), "—")
        self.assertEqual(coverage.holes(), [("TASK-REC-01", Target.DESKTOP)])

    def test_android_pass_and_desktop_fail_coexist(self):
        # The one thing the previous "one case per test class" model could not
        # express: a scenario that is fine on one target and broken on another.
        spec = _spec()
        coverage = build_coverage({"TASK-REC-01": spec}, [_link(Target.ANDROID), _link(Target.DESKTOP)])
        matrix = build_results(
            coverage,
            [
                (_link(Target.ANDROID), Outcome.PASSED.value, ""),
                (_link(Target.DESKTOP), Outcome.FAILED.value, ""),
            ],
            "abc1234",
        )
        self.assertEqual(matrix.glyph("TASK-REC-01", Target.ANDROID), "✅")
        self.assertEqual(matrix.glyph("TASK-REC-01", Target.DESKTOP), "❌")

    def test_claimed_target_without_a_result_is_not_run(self):
        matrix = build_results(build_coverage({"TASK-REC-01": _spec()}, []), [], "abc1234")
        self.assertEqual(matrix.glyph("TASK-REC-01", Target.DESKTOP), "⌛")

    def test_rendering_is_deterministic(self):
        # The committed matrix is compared byte-for-byte in CI, so a timestamp
        # or an unordered iteration would make every run a diff.
        coverage = build_coverage({"TASK-REC-01": _spec(), "TASK-REC-02": _spec("TASK-REC-02")}, [_link()])
        self.assertEqual(render_coverage_matrix(coverage), render_coverage_matrix(coverage))

    def test_coverage_render_carries_the_generated_banner(self):
        rendered = render_coverage_matrix(build_coverage({"TASK-REC-01": _spec()}, []))
        self.assertIn("GENERATED", rendered)
        self.assertIn("do not edit", rendered)

    def test_result_render_names_its_commit(self):
        # A result matrix that does not say which commit is a riddle.
        matrix = build_results(build_coverage({"TASK-REC-01": _spec()}, []), [], "deadbee")
        self.assertIn("deadbee", render_result_matrix(matrix))

    def test_every_scenario_appears_exactly_once_in_the_table(self):
        # Exactly once per table: a duplicate row means a scenario was counted
        # twice, which would inflate the coverage ratio.
        specs = {sid: _spec(sid) for sid in ("TASK-REC-01", "TASK-REC-02")}
        rendered = render_coverage_matrix(build_coverage(specs, []))
        table = [line for line in rendered.splitlines() if line.startswith("| `")]
        self.assertEqual(len(table), 2, table)
        for sid in specs:
            self.assertEqual(sum(1 for line in table if line.startswith(f"| `{sid}`")), 1, sid)

    def test_targets_iterate_in_a_fixed_order(self):
        self.assertEqual([t.value for t in ALL_TARGETS], ["android", "desktop"])


class LinkInvariants(unittest.TestCase):
    def test_kotlin_link_key_is_normalised(self):
        # The scanner writes the key; the reader must be able to reproduce it
        # from XML, or the join silently finds nothing.
        link = _link()
        xml_key = TestKey.from_xml("com.example.Foo", "does_a_thing()[jvm]")
        self.assertEqual(link.key, xml_key)

    def test_link_carries_its_level_and_target(self):
        link = _link(Target.ANDROID)
        self.assertEqual(link.level, Level.E2E)
        self.assertEqual(link.target, Target.ANDROID)


class RealRepositoryInvariants(unittest.TestCase):
    """Assertions about the actual repo, so drift is caught rather than assumed."""

    def test_the_committed_specs_load(self):
        specs = load_specs(REPO_ROOT / "infra/kiwi/scenarios")
        self.assertIn("TASK-REC-01", specs)

    def test_coverage_matrix_is_committed_and_current(self):
        # The CI check in miniature: the committed copy must equal the freshly
        # generated one.
        from traceability import COVERAGE_MATRIX_PATH
        from traceability.links import scan_all

        specs = load_specs(REPO_ROOT / "infra/kiwi/scenarios")
        links = scan_all(specs, REPO_ROOT)
        self.assertEqual(
            COVERAGE_MATRIX_PATH.read_text(encoding="utf-8"),
            render_coverage_matrix(build_coverage(specs, links)),
            "coverage matrix is stale — run: just trace-coverage",
        )

    def test_task_rec_01_is_automated_on_both_targets(self):
        from traceability.links import scan_all

        specs = load_specs(REPO_ROOT / "infra/kiwi/scenarios")
        links = scan_all(specs, REPO_ROOT)
        claimed = {(link.scenario, link.target) for link in links}
        for target in (Target.ANDROID, Target.DESKTOP):
            self.assertIn(("TASK-REC-01", target), claimed, f"TASK-REC-01/{target.value} lost its automation")

    def test_scenario_namespace_does_not_collide_with_ui_tags(self):
        from traceability.links import assert_namespace_disjoint

        assert_namespace_disjoint(REPO_ROOT)


if __name__ == "__main__":
    unittest.main()


class DisplayNameKeying(unittest.TestCase):
    """Regression: the join key must be the DISPLAY name, not the method name.

    JUnit writes `<testcase name>` as the `@DisplayName` when one is present, so
    a link keyed on `fun` matches nothing — and it looks entirely correct while
    doing so, because the scanner and the source agree with each other. The only
    symptom is an empty result matrix.
    """

    def test_link_key_is_the_display_name(self):
        from traceability import SCENARIOS_DIR
        from traceability.links import scan_all

        specs = load_specs(SCENARIOS_DIR)
        links = [link for link in scan_all(specs, REPO_ROOT) if link.carrier is Carrier.KOTLIN]
        self.assertTrue(links, "expected a Kotlin scenario carrier in the repo")
        for link in links:
            self.assertTrue(
                link.key.method.startswith("TASK-REC-"),
                f"key must be the display name, got {link.key.method!r}",
            )
            self.assertIn("fun ", link.detail)

    def test_fallback_key_is_the_method_name(self):
        from traceability import SCENARIOS_DIR
        from traceability.links import scan_all

        specs = load_specs(SCENARIOS_DIR)
        for link in scan_all(specs, REPO_ROOT):
            if link.fallback_key is not None:
                self.assertNotIn(" ", link.fallback_key.method)

    def test_both_key_forms_reach_the_index(self):
        from traceability import SCENARIOS_DIR
        from traceability.coverage import link_index
        from traceability.links import scan_all

        specs = load_specs(SCENARIOS_DIR)
        for link in scan_all(specs, REPO_ROOT):
            index = link_index([link])
            if link.key is not None:
                self.assertIn(link.key, index)
            if link.fallback_key is not None:
                self.assertIn(link.fallback_key, index)


class MaestroFlowJoin(unittest.TestCase):
    """The Android half joins on the flow path, not on a class+method pair."""

    def test_flow_joins_by_its_file_attribute(self):
        flow = REPO_ROOT / "Maestro/flows/tasks/17-create-daily-recurring.yaml"
        link = Link(
            scenario="TASK-REC-01",
            target=Target.ANDROID,
            level=Level.E2E,
            carrier=Carrier.MAESTRO,
            source=flow,
            key=None,
            detail="scenario:TASK-REC-01",
        )
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _junit(
                d,
                f'<testcase classname="tasks-create-daily-recurring" name="flow" '
                f'file="flows/tasks/17-create-daily-recurring.yaml"/>',
            )
            report = normalise(
                {"TASK-REC-01": _spec()},
                [link],
                {Target.ANDROID: [d]},
                "abc1234",
            )
        self.assertEqual(report.kept, 1)
        self.assertEqual(report.results[0].target, "android")
        self.assertEqual(report.results[0].carrier, "maestro")

    def test_flow_without_a_file_attribute_is_unmapped_not_guessed(self):
        # A classname-only flow result must not be attached by substring: that
        # would put one flow's outcome on another flow's scenario.
        flow = REPO_ROOT / "Maestro/flows/tasks/17-create-daily-recurring.yaml"
        link = Link(
            scenario="TASK-REC-01",
            target=Target.ANDROID,
            level=Level.E2E,
            carrier=Carrier.MAESTRO,
            source=flow,
            key=None,
        )
        # The spec claims desktop only, so the zero-testcase rule does not fire
        # on the android section and the counters are what this test observes.
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _junit(d, '<testcase classname="17-create-daily-recurring" name="flow"/>')
            report = normalise(
                {"TASK-REC-01": _spec(targets=(Target.DESKTOP,))},
                [link],
                {Target.ANDROID: [d]},
                "abc1234",
            )
        self.assertEqual(report.kept, 0)
        # With no `file` attribute there is no key at all, so the row is simply
        # unlinked — not "unmapped", which is reserved for a result that carried
        # a flow key and still matched no scenario.
        self.assertEqual(report.unmapped, 0)
        self.assertEqual(report.dropped, 1)

    def test_flow_result_pointing_at_an_unknown_flow_is_unmapped(self):
        # A flow ran, carried its path, and no scenario claims that path. That
        # is the counter a person actually needs when Kiwi grows unexpected
        # cases, so it must be counted rather than lumped in with unlinked tests.
        flow = REPO_ROOT / "Maestro/flows/tasks/17-create-daily-recurring.yaml"
        link = Link(
            scenario="TASK-REC-01",
            target=Target.ANDROID,
            level=Level.E2E,
            carrier=Carrier.MAESTRO,
            source=flow,
            key=None,
        )
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _junit(d, '<testcase classname="who-knows" name="flow" file="flows/tasks/99-orphan.yaml"/>')
            report = normalise(
                {"TASK-REC-01": _spec(targets=(Target.DESKTOP,))},
                [link],
                {Target.ANDROID: [d]},
                "abc1234",
            )
        self.assertEqual(report.kept, 0)
        self.assertEqual(report.unmapped, 1)


class PublishGrouping(unittest.TestCase):
    """One run per (commit, target) — the rule the whole Kiwi model rests on."""

    def _row(self, target: str, outcome: str, commit: str = "abc") -> NormalisedResult:
        return NormalisedResult(
            scenario="TASK-REC-01",
            target=target,
            level="e2e",
            outcome=outcome,
            commit=commit,
            carrier="kotlin",
            source="x.kt",
        )

    def test_android_and_desktop_become_two_runs(self):
        # Two executions in ONE run would be one observation reported twice, and
        # a "last execution wins" reader would lose the per-tier status.
        batches = build_runs(
            [self._row("android", "passed"), self._row("desktop", "failed")], "abc"
        )
        self.assertEqual(len(batches), 2)
        self.assertEqual({b.target.value for b in batches}, {"android", "desktop"})

    def test_run_summary_names_the_commit_and_target(self):
        batch = build_runs([self._row("android", "passed", "deadbee")], "deadbee")[0]
        self.assertIn("deadbee", batch.summary)
        self.assertIn("android", batch.summary)

    def test_different_commits_are_different_runs(self):
        # Grouped separately, each on its own commit.
        self.assertEqual(len(build_runs([self._row("android", "passed", "one")], "one")), 1)
        self.assertEqual(len(build_runs([self._row("android", "failed", "two")], "two")), 1)

    def test_mixed_commit_file_is_rejected(self):
        # Publishing a mixed-vintage file under one name would make the run
        # summary lie about half its executions.
        with self.assertRaises(ValueError):
            build_runs([self._row("android", "passed", "one"), self._row("desktop", "passed", "two")], "one")

    def test_not_run_is_never_published(self):
        # Absence in Kiwi is the honest representation; a missing run must not
        # be written as a pass.
        self.assertEqual(build_runs([self._row("android", "not-run")], "abc"), [])

    def test_skipped_maps_to_waived(self):
        # The one recorded divergence: the official plugin says WAIVED, sync.py
        # says IDLE. It lives in exactly one table so a reader can see it.
        self.assertEqual(OUTCOME_TO_KIWI[Outcome.SKIPPED.value], "WAIVED")

    def test_failure_maps_to_failed(self):
        self.assertEqual(OUTCOME_TO_KIWI[Outcome.FAILED.value], "FAILED")

    def test_rejected_commit_stops_publication(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "results.json"
            path.write_text(
                json.dumps({"commit": "aaa", "results": [asdict(self._row("android", "passed"))]}),
                encoding="utf-8",
            )
            self.assertEqual(run_publish(path, commit="bbb"), 1)

    def test_missing_results_file_is_reported(self):
        self.assertEqual(run_publish(pathlib.Path("/nonexistent/results.json")), 1)


class ExitCodeContract(unittest.TestCase):
    """CI acts on these codes, so they are a contract, not an implementation detail."""

    def test_zero_testcase_exits_two(self):
        from traceability.normalize import EXIT_NO_RESULTS, NoResultsError

        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _junit(d, '<testcase classname="com.example.Nobody" name="x()"/>')
            with self.assertRaises(NoResultsError):
                normalise({"TASK-REC-01": _spec()}, [_link()], {Target.DESKTOP: [d]}, "abc")
        self.assertEqual(EXIT_NO_RESULTS, 2)

    def test_no_results_error_is_a_validation_error(self):
        # It must still be catchable as a ValidationError, or a caller that
        # handles the general case would stop handling this one.
        from traceability.normalize import NoResultsError

        self.assertTrue(issubclass(NoResultsError, ValidationError))


class MalformedResultsFile(unittest.TestCase):
    """A truncated or hand-edited artifact must not surface as a traceback."""

    def _write(self, body: str):
        d = pathlib.Path(tempfile.mkdtemp())
        return _write(d, "results.json", body)

    def test_unparseable_json_is_a_validation_error(self):
        with self.assertRaises(ValidationError):
            read_results(self._write("not json at all"))

    def test_non_object_payload_is_rejected(self):
        with self.assertRaises(ValidationError):
            read_results(self._write("[1, 2, 3]"))

    def test_row_that_does_not_match_the_schema_is_rejected(self):
        with self.assertRaises(ValidationError):
            read_results(self._write('{"commit": "a", "results": [{"scenario": "X"}]}'))

    def test_well_formed_file_round_trips(self):
        row = NormalisedResult(
            scenario="TASK-REC-01",
            target="desktop",
            level="e2e",
            outcome="passed",
            commit="abc",
            carrier="kotlin",
            source="x.kt",
        )
        path = self._write(json.dumps({"commit": "abc", "results": [asdict(row)]}))
        commit, rows = read_results(path)
        self.assertEqual(commit, "abc")
        self.assertEqual(rows, [row])


class RetestAtTheSameCommit(unittest.TestCase):
    """Regression: a re-tested commit must not keep its old PASSED.

    This is the most dangerous failure mode in the whole system, because it is
    silent in the one direction that matters: Kiwi holds PASSED, the result
    matrix honestly holds FAILED, and nothing reports a conflict. The obvious
    implementation (skip a case that already has an execution in the run) is
    exactly the wrong one.
    """

    def _row(self, outcome: str) -> NormalisedResult:
        return NormalisedResult(
            scenario="TASK-REC-01",
            target="desktop",
            level="e2e",
            outcome=outcome,
            commit="same123",
            carrier="kotlin",
            source="x.kt",
        )

    def test_pass_then_fail_at_one_commit_is_one_run_not_two(self):
        from traceability.kiwi_publish import RunBatch

        batch = RunBatch(commit="same123", target=Target.DESKTOP, rows=(self._row("failed"),))
        self.assertEqual(batch.summary, "traceability same123 desktop")

    def test_unknown_outcome_is_rejected_not_silently_skipped(self):
        # A typo, a renamed Outcome member or a hand-edited results.json used
        # to publish nothing and still report success.
        with self.assertRaises(ValueError) as ctx:
            build_runs([self._row("flaky")], "same123")
        self.assertIn("неизвестный исход", str(ctx.exception))

    def test_wrong_case_is_rejected(self):
        row = NormalisedResult(
            scenario="TASK-REC-01",
            target="desktop",
            level="e2e",
            outcome="PASSED",
            commit="same123",
            carrier="kotlin",
            source="x.kt",
        )
        with self.assertRaises(ValueError):
            build_runs([row], "same123")


class SeedDriftDetection(unittest.TestCase):
    """`--check` must detect what a person can actually change in the UI."""

    def test_status_table_covers_the_lifecycle(self):
        from traceability.kiwi_seed import STATUS_TO_KIWI

        self.assertEqual(STATUS_TO_KIWI[SpecStatus.PROPOSED], "PROPOSED")
        self.assertEqual(STATUS_TO_KIWI[SpecStatus.CONFIRMED], "CONFIRMED")
        # A deprecated scenario is disabled in Kiwi, not deleted — the history
        # stays readable.
        self.assertEqual(STATUS_TO_KIWI[SpecStatus.DEPRECATED], "DISABLED")

    def test_case_summary_leads_with_the_immutable_id(self):
        from traceability.kiwi_seed import _case_summary

        summary = _case_summary(_spec("TASK-REC-01", title="Create a daily recurring task"))
        self.assertTrue(summary.startswith("TASK-REC-01 "), summary)

    def test_spec_path_is_repo_relative(self):
        # An absolute path would make the stand describe one developer's
        # checkout and render the link useless for the next person.
        from traceability.kiwi_seed import _spec_path

        spec = load_specs(REPO_ROOT / "infra/kiwi/scenarios")["TASK-REC-01"]
        self.assertEqual(_spec_path(spec), "infra/kiwi/scenarios/tasks/recurrence/TASK-REC-01.yaml")


class SpecReportsEveryProblem(unittest.TestCase):
    """One error per run turns a five-line fix into a five-run loop."""

    def _parse(self, data, path="tasks/recurrence/TASK-REC-01.yaml"):
        scenarios = REPO_ROOT / "infra/kiwi/scenarios"
        return parse_spec(data, scenarios / path, scenarios)

    def test_missing_title_is_reported_with_the_others(self):
        with self.assertRaises(ValidationError) as ctx:
            self._parse(
                {
                    "id": "TASK-REC-01",
                    "priority": "P0",
                    "status": "passed",
                    "targets": [],
                    "steps": [],
                }
            )
        message = str(ctx.exception)
        for expected in ("title", "P0", "status", "targets", "steps"):
            self.assertIn(expected, message, expected)


class ClaimedScenarioWithoutResult(unittest.TestCase):
    """"Ran the target, and this scenario still has no result" is a bug.

    The per-target zero-testcase rule above cannot see this: a target that
    produced 200 testcases passes it even when the single testcase carrying a
    scenario id was filtered out. That is the shape a tag filter produces, and
    the build is green.
    """

    def _desktop_run(self, cases: str):
        d = pathlib.Path(tempfile.mkdtemp())
        _junit(d, cases)
        return d

    def test_scenario_filtered_out_of_a_run_that_produced_others_fails(self):
        # The exact blind spot: a healthy-looking suite where the scenario's own
        # class is missing. Without the per-scenario rule this is a green build
        # and a permanently not-run cell.
        d = self._desktop_run(
            '<testcase classname="com.example.Other" name="unrelated()"/>'
            '<testcase classname="com.example.More" name="also_unrelated()"/>'
        )
        with self.assertRaises(NoResultsError) as ctx:
            normalise({"TASK-REC-01": _spec()}, [_link()], {Target.DESKTOP: [d]}, "abc")
        self.assertIn("TASK-REC-01/desktop", str(ctx.exception))

    def test_the_scenarios_own_result_is_enough(self):
        d = self._desktop_run('<testcase classname="com.example.Foo" name="does_a_thing()"/>')
        report = normalise({"TASK-REC-01": _spec()}, [_link()], {Target.DESKTOP: [d]}, "abc")
        self.assertEqual(report.kept, 1)

    def test_a_failing_scenario_counts_as_reported(self):
        # The rule is about *presence*, not outcome. A red scenario has a
        # perfectly good result and must not be reported as missing.
        d = self._desktop_run(
            '<testcase classname="com.example.Foo" name="does_a_thing()"><failure msg="boom"/></testcase>'
        )
        report = normalise({"TASK-REC-01": _spec()}, [_link()], {Target.DESKTOP: [d]}, "abc")
        self.assertEqual(report.kept, 1)
        self.assertEqual(report.results[0].outcome, Outcome.FAILED)

    def test_a_skipped_scenario_counts_as_reported(self):
        # Also presence: a skipped run is a real answer about the code. Treating
        # it as missing would make a quarantined test indistinguishable from a
        # class that silently vanished, and the two warrant different actions.
        d = self._desktop_run(
            '<testcase classname="com.example.Foo" name="does_a_thing()"><skipped/></testcase>'
        )
        report = normalise({"TASK-REC-01": _spec()}, [_link()], {Target.DESKTOP: [d]}, "abc")
        self.assertEqual(report.results[0].outcome, Outcome.SKIPPED)

    def test_target_never_run_is_not_enforced(self):
        # Same scope rule as the per-target rule: a desktop-only local run must
        # not fail over the Android half it was never asked to cover.
        d = self._desktop_run('<testcase classname="com.example.Foo" name="does_a_thing()"/>')
        report = normalise(
            {"TASK-REC-01": _spec()},
            [_link()],
            {Target.DESKTOP: [d], Target.ANDROID: []},
            "abc",
        )
        self.assertEqual(report.kept, 1)

    def test_an_unclaimed_target_with_a_carrier_is_a_hole_not_a_failure(self):
        # The link exists but the spec does not claim that target: the matrix
        # renders it as not-claimed, and failing would make a deliberately
        # narrow scenario impossible to declare.
        d = self._desktop_run('<testcase classname="com.example.Foo" name="does_a_thing()"/>')
        report = normalise(
            {"TASK-REC-01": _spec(targets=(Target.ANDROID,))},
            [_link(Target.DESKTOP)],
            {Target.DESKTOP: [d]},
            "abc",
        )
        self.assertEqual(report.kept, 1)

    def test_a_deprecated_scenario_is_not_an_obligation(self):
        d = self._desktop_run('<testcase classname="com.example.Other" name="unrelated()"/>')
        report = normalise(
            {"TASK-REC-01": _spec(status=SpecStatus.DEPRECATED)},
            [_link()],
            {Target.DESKTOP: [d]},
            "abc",
        )
        self.assertEqual(report.kept, 0)

    def test_one_missing_scenario_names_only_itself(self):
        # Two scenarios on one target, one of them filtered out. The message
        # must name the missing one only, or the fix is guesswork. The second
        # scenario needs its own carrier: two scenarios on one file is rejected
        # earlier by the duplicate-key guard, which is its own rule.
        d = self._desktop_run('<testcase classname="com.example.Foo" name="does_a_thing()"/>')
        second = _link(scenario="TASK-REC-02")
        second = Link(
            scenario=second.scenario,
            target=second.target,
            level=second.level,
            carrier=second.carrier,
            source=REPO_ROOT / "shared/src/jvmTest/kotlin/Bar.kt",
            key=TestKey("com.example.Bar", "does_another_thing"),
        )
        with self.assertRaises(NoResultsError) as ctx:
            normalise(
                {
                    "TASK-REC-01": _spec(),
                    "TASK-REC-02": _spec("TASK-REC-02"),
                },
                [_link(), second],
                {Target.DESKTOP: [d]},
                "abc",
            )
        message = str(ctx.exception)
        self.assertIn("TASK-REC-02/desktop", message)
        self.assertNotIn("TASK-REC-01/desktop", message)


class ZeroTestcaseRuleScope(unittest.TestCase):
    """"ran and produced nothing" is a bug; "was never run" is not."""

    def _xml(self, body: str):
        d = pathlib.Path(tempfile.mkdtemp())
        return _junit(d, body), d

    def test_target_with_results_but_no_linked_testcase_fails(self):
        # The quiet-green case: a Gradle task ran, produced testcases, and none
        # of them is a scenario. The build reported green.
        body, d = self._xml('<testcase classname="com.example.Nobody" name="x()"/>')
        with self.assertRaises(NoResultsError):
            normalise({"TASK-REC-01": _spec()}, [_link()], {Target.DESKTOP: [d]}, "abc")

    def test_target_with_no_result_directory_is_not_enforced(self):
        # A desktop-only run, or a CI job where the Android flows are a
        # different job: nothing ran, so there is nothing to be quietly green
        # about. The matrix reports not-run, which is the truth.
        report = normalise(
            {"TASK-REC-01": _spec()},
            [_link()],
            {Target.ANDROID: []},
            "abc",
        )
        self.assertEqual(report.kept, 0)


class FlowAttributeBaseAgnostic(unittest.TestCase):
    """Maestro's `file` base must not be guessed.

    Verified in Maestro 2.10.0's `JUnitTestSuiteReporter$TestCase`, which does
    declare a `file: String` field — so the attribute exists. What its *base* is
    depends on the reporter's workspace resolution, and guessing wrong would drop
    every Android flow result while looking like "no automation ran".
    """

    FLOW = REPO_ROOT / "Maestro/flows/tasks/17-create-daily-recurring.yaml"

    def _link(self) -> Link:
        return Link(
            scenario="TASK-REC-01",
            target=Target.ANDROID,
            level=Level.E2E,
            carrier=Carrier.MAESTRO,
            source=self.FLOW,
            key=None,
        )

    def _run(self, file_attr: str):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _junit(d, f'<testcase classname="x" name="flow" file="{file_attr}"/>')
            return normalise(
                {"TASK-REC-01": _spec(targets=(Target.DESKTOP,))},
                [self._link()],
                {Target.ANDROID: [d]},
                "abc1234",
            )

    def test_relative_to_the_maestro_dir(self):
        report = self._run("flows/tasks/17-create-daily-recurring.yaml")
        self.assertEqual(report.kept, 1)

    def test_relative_to_the_repository_root(self):
        report = self._run("Maestro/flows/tasks/17-create-daily-recurring.yaml")
        self.assertEqual(report.kept, 1)

    def test_absolute_path(self):
        report = self._run(str(self.FLOW))
        self.assertEqual(report.kept, 1)

    def test_a_path_that_names_no_flow_stays_unmapped(self):
        report = self._run("flows/tasks/99-nope.yaml")
        self.assertEqual(report.kept, 0)
        self.assertEqual(report.unmapped, 1)
