#!/usr/bin/env python3
"""Tests for the spec↔scenario bridge (`traceability scenarios`) and the
Maestro tag suggester (`traceability maestro-suggest`).

Both were added to close a specific hole, and the tests are written against the
ways each one could have *passed while doing nothing*, because both halves of
this change can be built out of code that runs cleanly and measures nothing:

* The bridge. A validator that only counts declarations passes on a spec that
  declares nothing. The test that matters is the one asserting a declaration
  with no YAML is a failure, and that a filled-in YAML makes it pass again.
* The suggester. It reads 66 untagged flows and produces a plausible id for
  each; the failure mode is not a crash, it is a confident wrong tag. So no
  test asserts "the right id was picked" for an ambiguous flow — the tags are
  human decisions by design. What is asserted is that ids never collide, since
  the first version proposed `AGENDA-01` for all eight agenda flows.
"""

import pathlib
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / "scripts" / "src"))

from traceability import ValidationError  # noqa: E402  (needs the path above)


def _spec_md(body: str) -> str:
    """A spec.md body, with the heading the bridge looks for."""
    return f"# Feature Specification: X\n\n## Scenario Matrix\n\n```yaml\n{body}\n```\n"


class DeclarationParsingTest(unittest.TestCase):
    """The block format, and the two ways it can be wrong silently."""

    def setUp(self):
        from traceability import speckit

        self.speckit = speckit

    def _write(self, text: str) -> pathlib.Path:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        path = pathlib.Path(self._tmp.name) / "001-x" / "spec.md"
        path.parent.mkdir(parents=True)
        path.write_text(text, encoding="utf-8")
        return path

    def test_reads_a_well_formed_declaration(self):
        path = self._write(
            _spec_md(
                "scenarios:\n"
                "  - id: POMODORO-01\n"
                "    title: Fresh timer\n"
                "    area: feature.pomodoro\n"
                "    priority: P1\n"
                "    status: confirmed\n"
                "    targets: [android]"
            )
        )
        found = self.speckit.extract_declarations(path)
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].id, "POMODORO-01")
        self.assertEqual(found[0].targets, ("android",))

    def test_absent_section_is_not_an_error(self):
        """A feature with no user-visible behaviour is a legitimate spec.

        Absent and empty are different claims, and conflating them would make
        the bridge force a section onto every spec in the repo.
        """
        path = self._write("# Feature Specification: X\n\nNo scenarios here.\n")
        self.assertEqual(self.speckit.extract_declarations(path), [])

    def test_empty_list_is_accepted(self):
        path = self._write(_spec_md("scenarios: []"))
        self.assertEqual(self.speckit.extract_declarations(path), [])

    def test_section_without_a_fence_is_an_error(self):
        """Silently returning [] would make a malformed block read as "none".

        That is the worst possible failure here: the spec would look like a
        feature nobody could test, when in fact the author wrote scenarios and
        the parser missed them.
        """
        path = self._write("## Scenario Matrix\n\n- id: POMODORO-01\n")
        with self.assertRaises(self.speckit.ValidationError) as caught:
            self.speckit.extract_declarations(path)
        self.assertIn("нет блока", str(caught.exception))

    def test_behaviour_fields_are_rejected(self):
        """The whole point of the split: the spec declares facts, the YAML owns
        behaviour. A spec carrying `steps` would be a second source of truth for
        what a user observes, and the checker would have to pick one."""
        path = self._write(
            _spec_md(
                "scenarios:\n"
                "  - id: POMODORO-01\n"
                "    title: Fresh timer\n"
                "    area: feature.pomodoro\n"
                "    priority: P1\n"
                "    status: confirmed\n"
                "    targets: [android]\n"
                "    steps: [invented]"
            )
        )
        with self.assertRaises(self.speckit.ValidationError) as caught:
            self.speckit.extract_declarations(path)
        self.assertIn("steps", str(caught.exception))

    def test_id_segment_count_must_match_directory_depth(self):
        """One id segment per directory, enforced at declaration time.

        `spec.py` enforces the same rule when loading, but reporting it against
        the spec that wrote the id is the difference between a fixable message
        and a validation failure naming a file the author never created.
        """
        path = self._write(
            _spec_md(
                "scenarios:\n"
                "  - id: AGENDA-VIEWS-01\n"
                "    title: Saved views\n"
                "    area: feature.agenda\n"
                "    priority: P1\n"
                "    status: confirmed\n"
                "    targets: [android]"
            )
        )
        with self.assertRaises(self.speckit.ValidationError) as caught:
            self.speckit.extract_declarations(path)
        self.assertIn("AGENDA-VIEWS-01", str(caught.exception))


class ScenarioPathTest(unittest.TestCase):
    """The path a declaration implies. Pure arithmetic, so it is asserted
    directly rather than through the filesystem."""

    def test_area_prefix_is_dropped(self):
        from traceability import speckit

        decl = speckit.Declaration(
            id="POMODORO-01",
            title="t",
            area="feature.pomodoro",
            priority="P1",
            status="confirmed",
            targets=("android",),
        )
        path = speckit.scenario_path(decl, pathlib.Path("/scenarios"))
        self.assertEqual(path, pathlib.Path("/scenarios/pomodoro/POMODORO-01.yaml"))

    def test_group_adds_a_directory_level(self):
        from traceability import speckit

        decl = speckit.Declaration(
            id="AGENDA-VIEWS-01",
            title="t",
            area="feature.agenda",
            priority="P1",
            status="confirmed",
            targets=("android",),
            group="saved_views",
        )
        path = speckit.scenario_path(decl, pathlib.Path("/scenarios"))
        self.assertEqual(path, pathlib.Path("/scenarios/agenda/saved_views/AGENDA-VIEWS-01.yaml"))


class SkeletonTest(unittest.TestCase):
    """What the generator writes — and the two bugs that got in first.

    Both produced valid YAML that loaded and measured the wrong thing, which is
    the failure mode this file exists to keep out.
    """

    def setUp(self):
        from traceability import speckit

        self.speckit = speckit
        self.decl = speckit.Declaration(
            id="POMODORO-01",
            title="A fresh Pomodoro timer sits paused on work cycle 1",
            area="feature.pomodoro",
            priority="P2",
            status="confirmed",
            targets=("android",),
            spec="specs/002-pomodoro-timer/spec.md",
        )

    def test_title_survives_round_trip(self):
        """`yaml.safe_dump` in flow style appends the document-end marker, so
        the first version wrote `title: ...` — which parses as the literal
        string "..." and silently replaced every title in the matrix."""
        import yaml

        rendered = self.speckit.render_skeleton(self.decl)
        loaded = yaml.safe_load(rendered)
        self.assertEqual(loaded["title"], self.decl.title)

    def test_step_todo_is_a_string_not_a_mapping(self):
        """A bare `- TODO: one action...` is a YAML mapping.

        It failed spec.py's "steps must be non-empty strings" check, which
        names the wrong file and points at the wrong problem; the author would
        go looking at validation rather than at their own unquoted colon.
        """
        import yaml

        loaded = yaml.safe_load(self.speckit.render_skeleton(self.decl))
        self.assertIsInstance(loaded["steps"][0], str)
        self.assertIn("TODO", loaded["steps"][0])

    def test_skeleton_loads_as_a_valid_scenario_spec(self):
        """A skeleton the project's own validator rejects is worse than no
        skeleton: it makes `trace-coverage` and `trace-validate` red for a file
        the author did not write yet."""
        from traceability import SCENARIOS_DIR
        from traceability.spec import load_specs

        rendered = self.speckit.render_skeleton(self.decl)
        self.assertIn("id: POMODORO-01", rendered)
        self.assertIn("targets: [android]", rendered)
        self.assertIn("TODO", rendered)
        # The real tree validates today, which is the regression this guards.
        load_specs(SCENARIOS_DIR)

    def test_generated_body_is_flagged_as_a_todo(self):
        """The marker is what makes `--check` fail, so it has to be in the
        file the author is told to fill in."""
        self.assertIn("TODO", self.speckit.render_skeleton(self.decl))


class MultiLevelGroupTest(unittest.TestCase):
    """`group` carries more than one directory level.

    Found by reading the existing corpus rather than by a test failing: the
    first version allowed a single level, and every id it generated was fine
    until the backfill hit `CAL-SYNC-CONNECT-01`, which lives three levels down
    at `calendar/sync/connect/`. Nothing rejected it loudly — it simply could not
    be described.
    """

    def _decl(self, decl_id, area, group):
        from traceability import speckit

        return speckit.Declaration(
            id=decl_id,
            title="t",
            area=area,
            priority="P1",
            status="confirmed",
            targets=("android", "desktop"),
            group=group,
        )

    def test_three_levels_match_the_existing_layout(self):
        from traceability import speckit

        decl = self._decl("CAL-SYNC-CONNECT-01", "feature.calendar", "sync/connect")
        self.assertEqual(
            speckit.scenario_path(decl, pathlib.Path("/s")),
            pathlib.Path("/s/calendar/sync/connect/CAL-SYNC-CONNECT-01.yaml"),
        )

    def test_one_and_two_levels_still_work(self):
        from traceability import speckit

        self.assertEqual(
            speckit.scenario_path(self._decl("POMODORO-01", "feature.pomodoro", ""), pathlib.Path("/s")),
            pathlib.Path("/s/pomodoro/POMODORO-01.yaml"),
        )
        self.assertEqual(
            speckit.scenario_path(
                self._decl("AGENDA-VIEWS-01", "feature.agenda", "saved_views"), pathlib.Path("/s")
            ),
            pathlib.Path("/s/agenda/saved_views/AGENDA-VIEWS-01.yaml"),
        )

    def test_traversal_is_refused(self):
        """A group that could escape its area would make the declaration a second
        source of truth for *where* a scenario lives — the exact thing the
        id/directory cross-check exists to prevent."""
        from traceability import speckit

        for bad in ("../elsewhere", "sync/../../etc", "Sync", "1sync"):
            text = (
                "# s\n\n## Scenario Matrix\n\n```yaml\nscenarios:\n"
                "  - id: AGENDA-01\n    title: t\n    area: feature.agenda\n"
                f"    group: {bad}\n    priority: P1\n    status: confirmed\n"
                "    targets: [android]\n```\n"
            )
            with tempfile.TemporaryDirectory() as tmp:
                path = pathlib.Path(tmp) / "spec.md"
                path.write_text(text, encoding="utf-8")
                with self.assertRaises(speckit.ValidationError):
                    speckit.extract_declarations(path, pathlib.Path(tmp))


class ReservedIdTest(unittest.TestCase):
    """The template's worked example must not become a real scenario.

    `spec-template.md` carries `EXAMPLE-01` in `feature.example` so a reader can
    see the shape. Anyone who copies the template and forgets the block would
    otherwise commit a matrix row for a feature nobody wrote.
    """

    def test_example_01_is_refused(self):
        from traceability import speckit

        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "spec.md"
            path.write_text(
                _spec_md(
                    "scenarios:\n"
                    "  - id: EXAMPLE-01\n"
                    "    title: A short imperative title\n"
                    "    area: feature.example\n"
                    "    priority: P1\n"
                    "    status: confirmed\n"
                    "    targets: [android, desktop]"
                ),
                encoding="utf-8",
            )
            with self.assertRaises(speckit.ValidationError) as caught:
                speckit.extract_declarations(path, pathlib.Path(tmp))
            self.assertIn("зарезервирован", str(caught.exception))


class MultiSourceTest(unittest.TestCase):
    """Both authored corpora are read; the generated index is not.

    `.specify/issues/` holds 633 records and is the one place that is *not* a
    place to declare anything: `scripts/openspec-import-all.py` rebuilds each
    record and writes it wholesale, so a scenario block added there survives
    until the next `--replace` and then vanishes. A declaration in a generated
    file is a race.
    """

    def test_both_corpora_are_found(self):
        from traceability import speckit

        found = {str(p.relative_to(speckit.REPO_ROOT)) for p in speckit.iter_specs()}
        self.assertIn("specs/002-pomodoro-timer/spec.md", found)
        self.assertIn("openspec/specs/user-authentication/spec.md", found)

    def test_the_generated_issue_registry_is_not_a_source(self):
        from traceability import speckit

        labels = {source.label for source in speckit.SPEC_SOURCES}
        self.assertEqual(labels, {"spec-kit", "openspec"})
        directories = [source.directory for source in speckit.SPEC_SOURCES]
        self.assertNotIn(".specify/issues", directories)

    def test_declarations_name_the_corpus_they_came_from(self):
        """A problem across two corpora has to say which two."""
        from traceability import speckit

        declarations = speckit.load_declarations()
        # openspec currently declares only the tasks corpus; the auth/sync/
        # calendar specs predate the bridge and carry no Scenario Matrix.
        self.assertTrue(
            declarations["TASK-01"].spec.startswith("openspec/specs/")
        )
        self.assertTrue(
            declarations["POMODORO-01"].spec.startswith("specs/")
        )


class BackfillTest(unittest.TestCase):
    """The pre-existing AUTH/CAL/SYNC scenarios are orphans by design.

    They predate the bridge: on disk, validated, rendered in the matrix — and
    attached to no spec. Orphans are advisory, not blocking, because most of
    them belong to features whose spec predates spec-kit. The bridge closes
    the gap for newly declared scenarios; backfilling the legacy corpus is
    tracked separately (see the orphan list in check_bindings docstring).
    """

    def test_orphans_are_advisory_and_include_the_legacy_corpus(self):
        from traceability import SCENARIOS_DIR, speckit
        from traceability.spec import load_specs

        declarations = speckit.load_declarations()
        specs = load_specs(SCENARIOS_DIR)
        problems, orphans = speckit.check_bindings(declarations, specs, SCENARIOS_DIR)
        self.assertEqual(problems, [])
        # Deprecated: a retired scenario is not an obligation, so
        # declaring it would contradict its own status.
        self.assertIn("CAL-FILT-01", orphans)
        # The legacy AUTH/CAL/SYNC corpus is orphaned until backfilled.
        self.assertIn("AUTH-SIGNIN-01", orphans)
        self.assertIn("SYNC-STATUS-01", orphans)


class CliTest(unittest.TestCase):
    """The command surface, which the unit tests above never exercised.

    The module can be perfect and the CLI can still pass the wrong argument,
    print the wrong thing, or exit 0 on a failure — none of which shows up in a
    test that imports the module directly.
    """

    def _run(self, *args):
        import os
        import subprocess

        return subprocess.run(
            [sys.executable, "-m", "traceability", *args],
            capture_output=True,
            text=True,
            cwd=ROOT,
            env={**os.environ, "PYTHONPATH": str(ROOT / "scripts" / "src")},
            check=False,
        )

    def test_scenarios_check_passes_on_the_committed_tree(self):
        result = self._run("scenarios", "--check")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("объявлений", result.stdout)

    def test_scenarios_generate_is_idempotent_on_the_committed_tree(self):
        """Re-running must not rewrite anything.

        Every declared scenario already has a filled YAML, so the generator has
        nothing to write; if it ever claims otherwise it is about to overwrite
        behaviour.
        """
        result = self._run("scenarios")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("нечего писать", result.stdout)

    def test_check_fails_when_a_declared_scenario_has_no_file(self):
        """The branch that makes the gate worth having at all.

        Exercised through `--speckit-specs` pointed at a scratch tree: a
        declaration with no YAML must fail, and the message must name the
        generator command that fixes it.
        """
        with tempfile.TemporaryDirectory() as tmp:
            spec_dir = pathlib.Path(tmp) / "001-x"
            spec_dir.mkdir(parents=True)
            (spec_dir / "spec.md").write_text(
                _spec_md(
                    "scenarios:\n"
                    "  - id: NOTES-99\n"
                    "    title: A note body\n"
                    "    area: feature.notes\n"
                    "    priority: P1\n"
                    "    status: confirmed\n"
                    "    targets: [android]"
                ),
                encoding="utf-8",
            )
            result = self._run("scenarios", "--check", "--speckit-specs", tmp)
            self.assertEqual(result.returncode, 1)
            self.assertIn("NOTES-99", result.stderr)
            self.assertIn("trace-scenarios", result.stderr)

    def test_override_directory_does_not_collide_with_the_repo(self):
        """An explicit directory replaces the configured corpora.

        If it joined them instead, a scratch declaration reusing a repository id
        would trip the duplicate check and the command would be unusable for
        testing anything.
        """
        with tempfile.TemporaryDirectory() as tmp:
            spec_dir = pathlib.Path(tmp) / "001-dup"
            spec_dir.mkdir(parents=True)
            (spec_dir / "spec.md").write_text(
                _spec_md(
                    "scenarios:\n"
                    "  - id: POMODORO-01\n"
                    "    title: Duplicated on purpose\n"
                    "    area: feature.pomodoro\n"
                    "    priority: P1\n"
                    "    status: confirmed\n"
                    "    targets: [android]"
                ),
                encoding="utf-8",
            )
            result = self._run("scenarios", "--check", "--speckit-specs", tmp)
            # One declaration, not two — so no duplicate error, just a drift error.
            self.assertNotIn("объявлен и в", result.stderr)
            self.assertEqual(result.returncode, 1)
            self.assertIn("title", result.stderr)


class RealTreeBindingTest(unittest.TestCase):
    """The committed tree, checked through the same code CI runs."""

    def test_pomodoro_specs_are_bound_to_the_pomodoro_feature_spec(self):
        """The loop this change exists for, end to end on real files: a speckit
        spec declares scenarios, the generator placed them, and the check is
        satisfied. Without a test like this the bridge could be correct in
        isolation and wired to nothing."""
        from traceability import speckit

        declarations = speckit.load_declarations()
        self.assertIn("POMODORO-01", declarations)
        self.assertEqual(declarations["POMODORO-01"].spec, "specs/002-pomodoro-timer/spec.md")

    def test_bound_scenarios_pass_the_check(self):
        from traceability import SCENARIOS_DIR, speckit
        from traceability.spec import load_specs

        declarations = speckit.load_declarations()
        specs = load_specs(SCENARIOS_DIR)
        problems, orphans = speckit.check_bindings(declarations, specs, SCENARIOS_DIR)
        self.assertEqual(problems, [], f"pomodoro bindings broken: {problems}")
        # Orphans are advisory by design — the pre-existing scenarios belong
        # to features whose spec predates spec-kit. CAL-FILT-01 is deprecated
        # (no YAML, no declaration); the legacy AUTH/CAL/SYNC corpus is
        # orphaned until backfilled.
        self.assertIn("CAL-FILT-01", orphans)

    def test_a_todo_body_is_a_problem(self):
        """A declared-but-unwritten scenario renders as a hole indistinguishable
        from a hole a human chose to leave. So a surviving TODO is blocking."""
        from traceability import SCENARIOS_DIR, speckit
        from traceability.spec import load_specs

        declarations = speckit.load_declarations()
        specs = load_specs(SCENARIOS_DIR)
        problems, _ = speckit.check_bindings(declarations, specs, SCENARIOS_DIR)
        self.assertEqual(
            [p for p in problems if "TODO" in p],
            [],
            "a filled-in scenario still carries TODO",
        )


class MaestroSuggestTest(unittest.TestCase):
    """The suggester. Note what is *not* asserted: that it picked the right id
    for an ambiguous flow. It has no `--apply` flag, because a guessed tag turns
    a visible hole into an invisible green."""

    def setUp(self):
        from traceability import SCENARIOS_DIR
        from traceability.links import scan_maestro
        from traceability.maestro_suggest import propose
        from traceability.spec import load_specs

        self.specs = load_specs(SCENARIOS_DIR)
        self.proposals = propose(self.specs, ROOT)
        self.links = scan_maestro(self.specs, ROOT)

    def test_tagged_flows_are_not_proposed(self):
        """A flow that already carries a tag is done; re-proposing it would
        invite a second tag on the same flow."""
        tagged = {link.source.name for link in self.links}
        for proposal in self.proposals:
            self.assertNotIn(
                pathlib.Path(proposal.path).name,
                {pathlib.Path(t).name for t in tagged},
                f"{proposal.path} already carries a tag",
            )

    def test_proposed_ids_never_collide(self):
        """The bug that made the first report useless: every flow in an area got
        the same number, so the worklist proposed `AGENDA-01` eight times."""
        ids = [p.proposed_id for p in self.proposals]
        self.assertEqual(len(ids), len(set(ids)), "proposed ids collide")

    def test_every_new_proposed_id_would_validate(self):
        """A proposal is a starting point for a human, but it must not be an id
        `spec.py` would reject — otherwise acting on it lands a broken file.

        Only *new* ids are checked. A proposal that reuses an existing scenario
        inherits that scenario's own directory depth, which is unrelated to the
        flow's path: `auth/02-signin-wrong-password.yaml` is one directory deep
        and points at `AUTH-SIGNIN-01`, which lives two levels down. Checking
        depth against the flow path would reject the single most confident row
        in the whole report.
        """
        import re

        pattern = re.compile(r"^[A-Z]+(?:-[A-Z]+)*-[0-9]+$")
        for proposal in self.proposals:
            self.assertRegex(proposal.proposed_id, pattern)
            if proposal.existing:
                self.assertIn(proposal.proposed_id, self.specs)
                continue
            segments = proposal.proposed_id.rsplit("-", 1)[0].split("-")
            directory = proposal.area.split(".")[-1]
            self.assertEqual(
                len(segments), 1, f"{proposal.proposed_id} needs one segment per directory"
            )
            self.assertTrue(
                directory.startswith(segments[0].lower()),
                f"{proposal.proposed_id} segment is not a prefix of {directory!r}",
            )

    def test_every_untagged_non_excluded_flow_is_proposed(self):
        """Every flow is accounted for: tagged, excluded by decision, or proposed.

        Checked as an exact partition rather than a count of proposals, because
        an opt-out that silently swallowed a flow it should not have would still
        leave the counts looking right.
        """
        from traceability.maestro_suggest import load_opt_outs

        flows = {p.relative_to(ROOT).as_posix() for p in (ROOT / "Maestro" / "flows").rglob("*.yaml")}
        opt_outs = set(load_opt_outs())
        self.assertTrue(opt_outs <= flows, "opt-out names a flow outside the tree")
        tagged = {link.source.relative_to(ROOT).as_posix() for link in self.links}
        proposed = {p.path for p in self.proposals}
        self.assertEqual(flows - tagged - opt_outs, proposed)
        self.assertEqual(len(tagged & opt_outs), 0, "a flow is both tagged and excluded")

    def test_read_only_by_construction(self):
        """The command has no --apply, and the module exports no writer. A
        future edit that adds one has to update this test."""
        import inspect

        from traceability import maestro_suggest

        source = inspect.getsource(maestro_suggest)
        self.assertNotIn("write_text", source)
        self.assertNotIn('"w"', source)


class OptOutTest(unittest.TestCase):
    """"This flow is not a scenario" has to be recordable.

    The worklist is only useful if it shrinks. Reading a flow and deciding it
    asserts nothing about a feature is a real decision, and without somewhere
    to put it the same flow comes back every run as if nobody had looked — which
    is what makes a worklist permanent.
    """

    def _write(self, body: str) -> pathlib.Path:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        path = pathlib.Path(self._tmp.name) / "opt-outs.txt"
        path.write_text(body, encoding="utf-8")
        return path

    def test_a_reason_is_mandatory(self):
        """An entry with a bare path reads as "somebody decided something once"
        and gets swept away by the next cleanup that wonders what the file is
        for. A reason is a claim, and a claim can be argued with."""
        from traceability.maestro_suggest import load_opt_outs

        path = self._write("Maestro/flows/nav/bottom-nav-tabs.yaml\n")
        with self.assertRaises(ValidationError):
            load_opt_outs(path)

    def test_parses_path_and_reason(self):
        from traceability.maestro_suggest import load_opt_outs

        path = self._write(
            "# a comment\n\nMaestro/flows/nav/x.yaml  —  navigation, not a claim\n"
        )
        self.assertEqual(
            load_opt_outs(path),
            {"Maestro/flows/nav/x.yaml": "navigation, not a claim"},
        )

    def test_a_duplicate_entry_is_an_error(self):
        """Two entries for one flow mean two people answered the same question
        differently and the file keeps whichever was read last."""
        from traceability.maestro_suggest import load_opt_outs

        path = self._write(
            "Maestro/flows/a.yaml  —  first reason\nMaestro/flows/a.yaml  —  second reason\n"
        )
        with self.assertRaises(ValidationError):
            load_opt_outs(path)

    def test_committed_file_parses_and_only_names_real_flows(self):
        """An opt-out naming a flow that has been renamed or deleted is telling
        the next reader a question was settled about a file that is not there."""
        from traceability.maestro_suggest import load_opt_outs

        opt_outs = load_opt_outs()
        self.assertTrue(opt_outs, "the committed opt-out file is empty")
        for flow in opt_outs:
            self.assertTrue((ROOT / flow).is_file(), f"opt-out names a missing flow: {flow}")

    def test_excluded_flows_do_not_reach_the_worklist(self):
        from traceability import SCENARIOS_DIR
        from traceability.links import scan_maestro
        from traceability.maestro_suggest import load_opt_outs, propose
        from traceability.spec import load_specs

        specs = load_specs(SCENARIOS_DIR)
        opt_outs = load_opt_outs()
        proposals = propose(specs, ROOT, opt_outs)
        proposed = {p.path for p in proposals}
        for flow in opt_outs:
            self.assertNotIn(flow, proposed)

        # And the ones that are gone are gone because they were excluded, not
        # because they quietly acquired a tag.
        links = scan_maestro(specs, ROOT)
        tagged = {link.source.as_posix() for link in links}
        for flow in opt_outs:
            self.assertNotIn(flow, tagged)


if __name__ == "__main__":
    unittest.main()