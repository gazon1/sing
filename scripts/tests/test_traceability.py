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
    CellState,
    CoverageCell,
    Outcome,
    build_coverage,
    build_results,
    classify,
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
from traceability.links import (  # noqa: E402
    Carrier,
    Link,
    _scenario_from_prefix_token,
    scan_all,
)
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

class ResultMatrixScopeLine(unittest.TestCase):
    """A `⌛` column must not read as "not automated" (#150 option 3).

    The Android flows run in a different workflow from the one that builds the
    matrix, so their column is legitimately empty. Left unexplained, an empty
    column looks exactly like a coverage hole, and a reader who believes that
    will not look for the flows that do exist.
    """

    def _render(self, with_android: bool) -> str:
        link = _link()
        flow = Link(
            scenario=link.scenario,
            target=Target.ANDROID,
            level=link.level,
            carrier=Carrier.MAESTRO,
            source=REPO_ROOT / "Maestro/flows/tasks/17-create-daily-recurring.yaml",
            key=None,
        )
        links = [link] + ([flow] if with_android else [])
        results = [(link, "passed", "")]
        if with_android:
            results.append((flow, "passed", ""))
        return render_result_matrix(
            build_results(build_coverage({"TASK-REC-01": _spec()}, links), results, "abc1234")
        )

    def test_missing_android_is_explained_not_just_empty(self):
        rendered = self._render(with_android=False)
        self.assertIn("android did not report", rendered)
        self.assertIn("not \"not automated\"", rendered)
        # The line must name the workflow, or the reader still has nowhere to go.
        self.assertIn("maestro-smoke.yml", rendered)

    def test_a_full_run_does_not_claim_a_caveat(self):
        # The caveat is about the CI wiring, not about Android as a target. With
        # both columns filled it would be noise, and noise in a generated header
        # is how a real note stops being read.
        rendered = self._render(with_android=True)
        self.assertIn("Every target reported", rendered)
        self.assertNotIn("maestro-smoke.yml", rendered)

    def test_reported_targets_are_named(self):
        self.assertIn("**Reported here:** desktop", self._render(with_android=False))
        self.assertIn("**Reported here:** android, desktop", self._render(with_android=True))

    def test_a_run_with_nothing_at_all_says_so(self):
        matrix = build_results(build_coverage({"TASK-REC-01": _spec()}, []), [], "abc1234")
        rendered = render_result_matrix(matrix)
        self.assertIn("No target reported a result", rendered)
        # Otherwise a fully-empty matrix reads as a table full of not-run cells
        # that someone forgot to fill, rather than a run that never happened.
        self.assertNotIn("**Reported here:**", rendered)


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
            # The invariant is "the key is the display name, which begins with
            # the scenario id" — not "the only scenario is TASK-REC-01". The
            # second was true when this was written and broke on the next
            # carrier, which is the signal that it was encoding an incidental
            # fact as a rule.
            self.assertTrue(
                link.key.method.startswith(link.scenario + " "),
                f"key must be the display name for {link.scenario}, "
                f"got {link.key.method!r}",
            )
            self.assertIn(" ", link.key.method, "a method name carries no space")
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


class PartialRunScope(unittest.TestCase):
    """A tag-filtered local run must not be reported as a missing result.

    Every scenario carrier is `@Tag("slow")` and the default local run excludes
    `slow` (see `desktopApp/build.gradle.kts`), so the documented fast cycle
    produces exactly the shape the per-scenario rule exists to catch — while
    having done nothing wrong. The distinction is not derivable from the XML, so
    the caller states it, and these tests pin both the behaviour and the two
    wirings that decide who states it.
    """

    def _other_scenario(self) -> Link:
        """A second link with its own carrier, so the target reports something.

        Needed to isolate the per-scenario rule: with one scenario the
        zero-testcase rule fires first, and a test that passes for the wrong
        reason is worse than no test.
        """
        return Link(
            scenario="TASK-REC-02",
            target=Target.DESKTOP,
            level=Level.E2E,
            carrier=Carrier.KOTLIN,
            source=REPO_ROOT / "shared/src/jvmTest/kotlin/Bar.kt",
            key=TestKey("com.example.Bar", "does_another_thing"),
        )

    def _run_reporting_only_one(self, partial: bool):
        d = pathlib.Path(tempfile.mkdtemp())
        _junit(d, '<testcase classname="com.example.Foo" name="does_a_thing()"/>')
        return normalise(
            {"TASK-REC-01": _spec(), "TASK-REC-02": _spec("TASK-REC-02")},
            [_link(), self._other_scenario()],
            {Target.DESKTOP: [d]},
            "abc",
            partial=partial,
        )

    def test_complete_run_reports_the_missing_scenario(self):
        # The rule's own case: one scenario reported, the other silent, on a
        # complete run. Without partial it must fail.
        with self.assertRaises(NoResultsError) as ctx:
            self._run_reporting_only_one(partial=False)
        self.assertIn("TASK-REC-02/desktop", str(ctx.exception))

    def test_partial_run_does_not(self):
        report = self._run_reporting_only_one(partial=True)
        self.assertEqual(report.kept, 1)

    def test_partial_flag_also_silences_the_zero_testcase_rule(self):
        # Inverted from the first version of this test, which asserted the
        # opposite on the assumption that the per-target rule was immune to tag
        # filtering. A real run disproved it: the target executes, writes ten
        # unrelated XMLs, and no *scenario* testcase appears — so the per-target
        # rule fires on exactly the subset `--partial` was asked to accept. Both
        # rules are absence-based, and on a partial run absence carries no
        # information.
        d = pathlib.Path(tempfile.mkdtemp())
        report = normalise(
            {"TASK-REC-01": _spec()},
            [_link()],
            {Target.DESKTOP: [d]},
            "abc",
            partial=True,
        )
        self.assertEqual(report.kept, 0)

    def test_a_complete_run_with_no_results_at_all_still_fails(self):
        # The guard on the guard: `--partial` must not become a way to switch
        # the gate off, only a way to declare that a run is a subset.
        d = pathlib.Path(tempfile.mkdtemp())
        with self.assertRaises(NoResultsError):
            normalise(
                {"TASK-REC-01": _spec()},
                [_link()],
                {Target.DESKTOP: [d]},
                "abc",
                partial=False,
            )

    def _workflow_and_recipe(self):
        ci = REPO_ROOT / ".github/workflows/ci.yml"
        recipe = REPO_ROOT / ".just/kiwi/mod.just"
        return ci.read_text(encoding="utf-8"), recipe.read_text(encoding="utf-8")

    def test_ci_does_not_pass_partial(self):
        # CI runs `-Ptest.tags=fast,slow`, so the rule is accurate there and
        # passing the flag on purpose would be a real loss of coverage.
        ci, _ = self._workflow_and_recipe()
        self.assertNotIn("--partial", ci)

    def test_local_recipe_does_pass_partial(self):
        # The counterpart: a developer on the fast cycle must not be told their
        # build is broken because they skipped slow tests on purpose.
        _, recipe = self._workflow_and_recipe()
        self.assertIn("results --partial", recipe)

    def test_the_two_wirings_stay_distinguishable(self):
        # If both stopped carrying the flag the drift would be invisible: CI
        # would keep enforcing by accident while local runs started failing.
        ci, recipe = self._workflow_and_recipe()
        self.assertNotEqual("--partial" in ci, "--partial" in recipe)


class DeprecatedScenarioIsNotAHole(unittest.TestCase):
    """A retired scenario is a third state, and neither old glyph was right.

    Found with a five-line probe while answering what to do next, not by a
    failing test: the system has one spec and it is `confirmed`, so the branch
    was never rendered. #156 asks for a deprecated scenario among its batch,
    which would have made this visible in the committed matrix — as a gap the
    system exists to close.
    """

    def _deprecated(self):
        return _spec("TASK-OLD-01", status=SpecStatus.DEPRECATED, id_prefix="TASK-OLD")

    def test_it_renders_as_its_own_glyph(self):
        coverage = build_coverage({"TASK-OLD-01": self._deprecated()}, [])
        row = render_coverage_matrix(coverage)
        self.assertIn("| ⊘ | ⊘ |", row)
        # `○` is the hole glyph. Asserting its absence is the point: the row
        # must not read as an automation gap.
        self.assertNotIn("| ○ | ○ |", row)

    def test_it_is_not_counted_as_a_hole(self):
        coverage = build_coverage({"TASK-OLD-01": self._deprecated()}, [])
        self.assertEqual(coverage.holes(), [])

    def test_it_is_excluded_from_the_automated_ratio(self):
        # A retired scenario is not an obligation, so it is not a denominator.
        # Including it produced "0/2 claimed cells automated · 0 holes", which
        # reads as a contradiction the reader has to resolve by guessing.
        coverage = build_coverage({"TASK-OLD-01": self._deprecated()}, [])
        row = render_coverage_matrix(coverage)
        self.assertIn("0/0 claimed cells automated", row)

    def test_the_legend_explains_the_glyph(self):
        # A glyph the legend does not define is a glyph nobody can act on.
        row = render_coverage_matrix(build_coverage({"TASK-OLD-01": self._deprecated()}, []))
        self.assertIn("deprecated", row)

    def test_a_live_scenario_beside_it_is_still_a_hole(self):
        # The point of the fix is not to mute the report. A real hole next to a
        # retired scenario must still be reported, or the fix hides gaps.
        coverage = build_coverage(
            {"TASK-OLD-01": self._deprecated(), "TASK-REC-01": _spec()},
            [],
        )
        self.assertEqual(
            coverage.holes(),
            [("TASK-REC-01", Target.ANDROID), ("TASK-REC-01", Target.DESKTOP)],
        )
        self.assertIn("2 holes", render_coverage_matrix(coverage))

    def test_an_automated_scenario_is_unaffected(self):
        coverage = build_coverage({"TASK-REC-01": _spec()}, [_link()])
        self.assertIn("| ● |", render_coverage_matrix(coverage))


class AbsenceRulesAreDisjoint(unittest.TestCase):
    """Which of the two absence rules fires, and why both are needed (#186).

    Measured on 2026-10-05 with the repository's own carrier key rather than
    reasoned about, after reading the code twice gave two wrong answers about
    whether one rule subsumes the other. It does not: they are disjoint, and the
    per-scenario rule is the only one that can see a *partially* silent target.
    """

    def _link_for(self, key_class: str, scenario: str) -> Link:
        return Link(
            scenario=scenario,
            target=Target.DESKTOP,
            level=Level.E2E,
            carrier=Carrier.KOTLIN,
            source=REPO_ROOT / f"shared/src/jvmTest/kotlin/{key_class}.kt",
            key=TestKey(f"com.example.{key_class}", "does_a_thing"),
        )

    def _run(self, links, reporting):
        d = pathlib.Path(tempfile.mkdtemp())
        cases = "".join(
            f'<testcase classname="com.example.{name}" name="does_a_thing()"/>'
            for name in reporting
        )
        _junit(d, cases)
        try:
            normalise(
                {"TASK-A-01": _spec("TASK-A-01", id_prefix="TASK-A"),
                 "TASK-B-01": _spec("TASK-B-01", id_prefix="TASK-B")},
                links,
                {Target.DESKTOP: [d]},
                "abc",
            )
            return None
        except NoResultsError as e:
            return str(e)

    def test_one_silent_carrier_on_a_quiet_target_fires_the_target_rule(self):
        # Nothing on the target reported at all. The per-target rule owns this.
        message = self._run([self._link_for("A", "TASK-A-01")], reporting=[])
        self.assertIn("ни одного тесткейса", message)

    def test_one_silent_carrier_on_a_busy_target_fires_the_scenario_rule(self):
        # The case the target rule cannot see: the target reported, so its
        # per-target count is non-zero, and only the per-scenario rule notices
        # that one specific scenario is missing. This is #149's whole point.
        links = [self._link_for("A", "TASK-A-01"), self._link_for("B", "TASK-B-01")]
        message = self._run(links, reporting=["A"])
        self.assertIn("TASK-B-01/desktop", message)
        self.assertNotIn("ни одного тесткейса", message)

    def test_both_silent_fires_the_target_rule_not_the_scenario_rule(self):
        # Proves the rules do not overlap: when nothing reports, the per-target
        # rule fires and the per-scenario one is silent. Had they been merged
        # into one function, the target case would have reported every scenario
        # as missing, and a single absent class would have produced a list of
        # unrelated failures.
        links = [self._link_for("A", "TASK-A-01"), self._link_for("B", "TASK-B-01")]
        message = self._run(links, reporting=[])
        self.assertIn("ни одного тесткейса", message)
        # The target rule names *every* claimed pair, so the ids being present
        # proves nothing — the two rules are told apart by their wording. An
        # earlier version of this test asserted on the ids and failed, having
        # assumed the target rule would name only the one scenario.
        self.assertNotIn("не дал результата на этом коммите", message)

    def test_a_fully_reported_target_is_silent(self):
        links = [self._link_for("A", "TASK-A-01"), self._link_for("B", "TASK-B-01")]
        self.assertIsNone(self._run(links, reporting=["A", "B"]))


class CoverageMatrixCarriesTheProse(unittest.TestCase):
    """The matrix must answer "what is unverified", not only "how much".

    It printed `id | title | ● | ○` and nothing else, so a reader could count
    holes but not learn which behaviour each one is. #156's acceptance criterion
    is that a reader uses the matrix to decide what to test next, and four
    glyphs do not support that decision. The specs already carried the prose;
    nothing printed it.
    """

    def _render(self) -> str:
        specs = {
            "TASK-REC-01": _spec(
                steps=("Open the composer.", "Choose Daily."),
                expected="The next occurrence is dated one day after completion.",
            ),
            "TASK-OLD-01": _spec(
                "TASK-OLD-01", status=SpecStatus.DEPRECATED, id_prefix="TASK-OLD"
            ),
        }
        return render_coverage_matrix(build_coverage(specs, [_link()]))

    def test_the_table_has_a_what_we_verify_column(self):
        self.assertIn("| What we verify |", self._render())

    def test_the_expected_result_reaches_the_row(self):
        self.assertIn(
            "dated one day after completion",
            self._render(),
        )

    def test_every_scenario_has_a_details_section(self):
        rendered = self._render()
        self.assertIn("## Scenario details", rendered)
        for scenario_id in ("TASK-REC-01", "TASK-OLD-01"):
            self.assertIn(f"#### `{scenario_id}`", rendered)

    def test_details_carry_preconditions_steps_and_expected(self):
        rendered = self._render()
        self.assertIn("Steps:", rendered)
        self.assertIn("1. Open the composer.", rendered)
        self.assertIn("2. Choose Daily.", rendered)
        self.assertIn("**Expected:**", rendered)

    def test_a_deprecated_scenario_is_described_too(self):
        # A retired scenario is exactly the one a reader needs the history for —
        # "why is this here and why is nothing done about it".
        self.assertIn("**deprecated**", self._render())

    def test_a_pipe_in_the_prose_does_not_add_a_column(self):
        # A `|` in a table cell starts a new column, and the specs are hand-written
        # prose: a scenario about filtering by "project | tag" would render a table
        # with one column too many and no error anywhere.
        specs = {"TASK-A-01": _spec("TASK-A-01", id_prefix="TASK-A", expected="project | tag | status")}
        row = [line for line in render_coverage_matrix(build_coverage(specs, [])).splitlines() if "TASK-A-01` |" in line][0]
        self.assertIn("project \\| tag \\| status", row)

    def test_a_long_expected_is_truncated_in_the_cell_but_not_in_the_details(self):
        # The column makes a row readable in a diff; the details are where the
        # full text lives. Truncating both would lose the sentence.
        long_text = "word " * 80
        specs = {"TASK-A-01": _spec("TASK-A-01", id_prefix="TASK-A", expected=long_text)}
        rendered = render_coverage_matrix(build_coverage(specs, []))
        row = [line for line in rendered.splitlines() if "TASK-A-01` |" in line][0]
        self.assertIn("…", row)
        self.assertIn(long_text.strip(), rendered)

    def test_rendering_stays_deterministic(self):
        self.assertEqual(self._render(), self._render())


class PerScenarioRuleIsExercisable(unittest.TestCase):
    """The per-scenario rule must stay reachable, proved on the real corpus.

    For the whole session the rule from #149 never fired on real data: with one
    scenario per target, the per-target rule always caught the case first. It
    became reachable only when a second desktop carrier existed — two carriers
    on one target is the precondition, and the auth/sync tranche that added
    fifteen specs added none.

    So the property is asserted against the repository, not against a fixture
    with two hand-made links. A fixture proves the rule works; this proves the
    rule is *reachable*, which is the part that silently stops being true.
    """

    def _corpus(self):
        from traceability import SCENARIOS_DIR
        from traceability.links import scan_all

        specs = load_specs(SCENARIOS_DIR)
        return specs, scan_all(specs, REPO_ROOT)

    def test_some_target_carries_two_linked_scenarios(self):
        specs, links = self._corpus()
        by_target: dict[Target, list[str]] = {}
        for link in links:
            by_target.setdefault(link.target, []).append(link.scenario)
        busy = {t: v for t, v in by_target.items() if len(set(v)) >= 2}
        self.assertTrue(
            busy,
            "no target has two linked scenarios, so the per-scenario rule "
            "(REQ-13) is unreachable: the per-target rule catches every case "
            "first. Add a carrier for an existing spec rather than a new spec.",
        )

    def test_the_real_rule_fires_when_one_of_those_two_is_filtered_out(self):
        # A genuine `Authenticated`-free result set built from the repository's
        # own carrier keys, with one desktop carrier reported and the other
        # silent. This is the exact shape a tag filter produces.
        specs, links = self._corpus()
        by_target: dict[Target, list[str]] = {}
        for link in links:
            by_target.setdefault(link.target, []).append(link)
        target, carriers = next(
            (t, v) for t, v in by_target.items() if len({l.scenario for l in v}) >= 2
        )
        # Report exactly one and assert on every other, rather than picking
        # carriers[1]. The first version did pick carriers[1] and passed while
        # the desktop carried two scenarios; the moment a third landed it picked
        # the reporting one and failed. That is not a flake — it is the test
        # asserting an accident of ordering, which is how the `--partial` test in
        # this same file shipped a wrong belief earlier. The rule says nothing
        # about order: report one, and every other scenario on that target must
        # be named.
        scenarios = sorted({l.scenario for l in carriers})
        reporting = next(l for l in carriers if l.scenario == scenarios[0])
        silent = [s for s in scenarios if s != scenarios[0]]

        d = pathlib.Path(tempfile.mkdtemp())
        key = reporting.key
        _junit(
            d,
            f'<testcase classname="{key.fqcn}" name="{key.method}()"/>',
        )
        with self.assertRaises(NoResultsError) as ctx:
            normalise(specs, links, {target: [d]}, "abc")
        message = str(ctx.exception)
        for scenario in silent:
            self.assertIn(
                f"{scenario}/{target.value}",
                message,
                f"{scenario} produced no result on {target.value} but the rule did not name it",
            )
        self.assertNotIn(f"{reporting.scenario}/{target.value}", message)
        self.assertNotIn("ни одного тесткейса", message)


class UnreachableCellsTest(unittest.TestCase):
    """The fifth glyph, and the two mistakes it was added to stop being confusable.

    A hole was ambiguous, and both readings were acted on in one session.
    `TASK-TIME-01` was narrowed to `targets: [android]` because a reachability
    probe found no time-tracking node on desktop — wrong, the feature was in
    `commonMain` and the desktop screen had silently stopped rendering it.
    `SYNC-OFFLINE-01` claims both targets and needs a second device and a
    flapping network — true, and unsupplyable by any single-device harness.

    Both drew as `○`. So each mistake looked like the other's remedy, and the
    only record of the intent was a comment above a `targets:` list, which no
    tooling read.
    """

    def _corpus(self):
        specs = load_specs(REPO_ROOT / "infra/kiwi/scenarios")
        return specs, build_coverage(specs, scan_all(specs, REPO_ROOT))

    def test_an_unreachable_cell_is_still_a_hole(self):
        # The whole point of keeping it in the count: otherwise the cheap move is
        # to reclassify every unsupplied claim as unreachable, and the ratchet
        # stops measuring anything while the matrix looks more informative.
        specs, coverage = self._corpus()
        unreachable = set(coverage.unreachable_holes())
        self.assertTrue(unreachable, "fixture assumption: the corpus has unreachable cells")
        for cell in unreachable:
            self.assertIn(cell, coverage.holes())

    def test_unreachable_is_a_subset_of_claimed(self):
        specs, coverage = self._corpus()
        for scenario, target in coverage.unreachable_holes():
            self.assertTrue(
                target in specs[scenario].targets,
                f"{scenario}/{target.value} is unreachable but not claimed",
            )

    def test_the_glyph_differs_from_a_plain_hole(self):
        specs, coverage = self._corpus()
        scenario, target = coverage.unreachable_holes()[0]
        unreachable = coverage.glyph(scenario, target)
        plain = next(
            coverage.glyph(s, t)
            for s, t in coverage.holes()
            if t not in specs[s].unreachable
        )
        self.assertNotEqual(unreachable, plain)
        self.assertEqual(unreachable, "◇")
        self.assertEqual(plain, "○")

    def test_the_field_is_read_from_the_spec_and_never_inferred(self):
        # A probe that fails must not be able to set this flag. Inferring
        # reachability from an observed miss is precisely how TASK-TIME-01 got
        # narrowed: the probe measured one screen and the conclusion was written
        # as a statement about the platform.
        specs, _ = self._corpus()
        marked = {s for s, spec in specs.items() if spec.unreachable}
        self.assertTrue(marked)
        for name in marked:
            self.assertTrue(
                (specs[name].unreachable),
                f"{name} marked unreachable with an empty tuple",
            )

    def test_a_target_outside_targets_is_rejected(self):
        # The invariant that stops the flag being used as a disguised narrowing:
        # a target nobody claimed owes nothing and cannot be unreachable.
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / "SYN-STATUS-01.yaml"
            path.write_text(
                "id: SYN-STATUS-01\n"
                "title: x\npriority: P1\nstatus: confirmed\n"
                "targets: [android]\n"
                "unreachable: [android, desktop]\n"
                "preconditions: x\nsteps: [x]\nexpected: x\n",
                encoding="utf-8",
            )
            with self.assertRaises(ValidationError) as ctx:
                load_specs(pathlib.Path(d))
            self.assertIn("не входит в targets", str(ctx.exception))


class CarrierRefusesUnreachableTest(unittest.TestCase):
    """The generator must not write a probe that cannot pass."""

    def test_the_cli_refuses_an_unreachable_target_by_name(self):
        import os
        import subprocess

        result = subprocess.run(
            [sys.executable, "-m", "traceability", "carrier", "SYNC-OFFLINE-01",
             "--target", "desktop", "--dry-run"],
            capture_output=True,
            text=True,
            cwd=REPO_ROOT,
            env={**os.environ, "PYTHONPATH": str(_KIWI_DIR)},
            check=False,
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("недостижимый", result.stdout)
        self.assertNotIn("dry run, не записан", result.stdout)

    def test_a_reachable_neighbour_of_an_unreachable_row_still_generates(self):
        # The classification is per target, not per scenario: SYNC-STATUS-01
        # stays fully probe-able while its two-device neighbours do not.
        import os
        import subprocess

        result = subprocess.run(
            [sys.executable, "-m", "traceability", "carrier", "SYNC-STATUS-01", "--dry-run"],
            capture_output=True,
            text=True,
            cwd=REPO_ROOT,
            env={**os.environ, "PYTHONPATH": str(_KIWI_DIR)},
            check=False,
        )
        self.assertEqual(result.returncode, 0)
        self.assertIn("dry run", result.stdout)


class CellStatePrecedence(unittest.TestCase):
    """The five states, and the one function that decides between them.

    `CoverageCell` used to carry four independent booleans. That let a caller
    build a cell nothing could render — `claimed=False, automated=True` most
    plainly — and the glyph cascade quietly fell through and printed the
    unclaimed dash, so an impossible cell was indistinguishable from an ordinary
    one. These tests pin the replacement: one value, a fixed precedence, and no
    way to say something the matrix has no column for.
    """

    def test_there_are_exactly_five_states(self):
        # A sixth state is a change of model, not an addition, and the glyph
        # table has to grow with it.
        self.assertEqual(len(list(CellState)), 5)

    def test_every_state_has_its_own_glyph(self):
        glyphs = [state.glyph for state in CellState]
        self.assertEqual(len(set(glyphs)), len(glyphs), glyphs)

    def test_a_retired_scenario_outranks_a_carrier(self):
        # The case the booleans could not express without contradiction: a
        # scenario retired *after* it was automated. Rendering it `●` would keep
        # it in the matrix forever, looking supplied.
        self.assertIs(
            classify(claimed=True, automated=True, deprecated=True, reachable=True),
            CellState.RETIRED,
        )

    def test_reachability_only_distinguishes_two_kinds_of_hole(self):
        self.assertIs(
            classify(claimed=True, automated=False, deprecated=False, reachable=True),
            CellState.HOLE,
        )
        self.assertIs(
            classify(claimed=True, automated=False, deprecated=False, reachable=False),
            CellState.UNREACHABLE,
        )

    def test_an_unclaimed_target_is_never_a_hole(self):
        self.assertIs(
            classify(claimed=False, automated=False, deprecated=False, reachable=True),
            CellState.UNCLAIMED,
        )

    def test_the_unsatisfiable_input_folds_to_something_renderable(self):
        # `claimed and not automated` is the state the old shape could not
        # refuse. It now reads as unclaimed, which is the only claim about the
        # target that is actually true.
        self.assertIs(
            classify(claimed=False, automated=True, deprecated=False, reachable=True),
            CellState.UNCLAIMED,
        )

    def test_hole_predicates_cover_exactly_the_unsupplied_claims(self):
        holes = {state for state in CellState if state.is_hole}
        self.assertEqual(holes, {CellState.HOLE, CellState.UNREACHABLE})
        unreachable = {state for state in CellState if not state.is_claimed}
        self.assertEqual(unreachable, {CellState.UNCLAIMED})

    def test_the_cell_no_longer_accepts_the_old_booleans(self):
        # The point of the refactor, stated as a test: the four facts are
        # consumed once, by `classify`. A caller that still has them cannot hand
        # them over, so the impossible combinations cannot be constructed at all
        # rather than being constructed and quietly rendered wrong.
        with self.assertRaises(TypeError):
            CoverageCell(claimed=True, automated=False)  # type: ignore[call-arg]

    def test_a_retired_scenario_is_recorded_but_not_owed(self):
        # `is_claimed` keeps the row, `is_obligation` drops it from the
        # denominator. render.py depends on that split to avoid the
        # "0/2 claimed cells automated · 0 holes" contradiction.
        self.assertTrue(CellState.RETIRED.is_claimed)
        self.assertFalse(CellState.RETIRED.is_obligation)
        self.assertFalse(CellState.UNCLAIMED.is_claimed)
        self.assertTrue(CellState.AUTOMATED.is_obligation)
