"""Unit tests for `sgate.gate_manifest` — one table of placements for every gate.

Run with: python3 -m unittest discover -s scripts/tests

The gates used to be declared twice: once in `_registry.GATES` (name, mode,
dispatch) and once in `scripts/ci/static-gates.sh` (name, mode, command) — with
`check-gate-registries.py` comparing the two so that the drift was visible
rather than impossible. The shell file is now rendered from one table, and these
tests pin what the rendering must satisfy:

- every registered gate is placed, and every placement names a real gate;
- the rendered region is exactly what the shell file holds;
- the cluster membership is declared, so a member cannot fall out of the
  covered set because a line inside a wrapper was renamed.
"""

import pathlib
import unittest

from sgate.commands._registry import GATES
from sgate.commands.render_static_gates import BEGIN_MARKER, END_MARKER
from sgate.gate_manifest import (
    CLUSTERS,
    comment_lines,
    NOT_RUN_IN_STATIC,
    PLACEMENTS,
    PlacementKind,
    render_static_registry,
    static_placements,
)

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
_STATIC_GATES_SH = _ROOT / "scripts" / "ci" / "static-gates.sh"

_MODES = {spec.name: spec.mode.value for spec in GATES}


def _generated_region() -> str:
    """The generated region of the shell file, as it is on disk."""
    text = _STATIC_GATES_SH.read_text(encoding="utf-8")
    begin = text.find(BEGIN_MARKER)
    end = text.find(END_MARKER)
    assert begin != -1 and end != -1, "the generated markers are missing"
    return text[begin + len(BEGIN_MARKER) : end].strip("\n")


class TestTheTableIsComplete(unittest.TestCase):
    """Every gate is placed, and every placement names a gate."""

    def test_every_registered_gate_has_a_placement(self):
        """A gate with no placement is a gate nobody can find."""
        registered = {spec.name for spec in GATES}
        missing = sorted(registered - set(PLACEMENTS))
        self.assertEqual(
            missing, [], f"registered gates with no placement: {missing}"
        )

    def test_every_placement_names_a_registered_gate(self):
        """A placement whose gate is gone is a declaration that outlived it."""
        registered = {spec.name for spec in GATES}
        stale = sorted(set(PLACEMENTS) - registered)
        self.assertEqual(stale, [], f"placements with no registered gate: {stale}")

    def test_every_gate_is_placed_exactly_once(self):
        """One placement per gate — the table is the answer, not a vote."""
        static = [name for name, _ in static_placements()]
        cluster = [
            name
            for name, placement in PLACEMENTS.items()
            if placement.kind is PlacementKind.CLUSTER
        ]
        elsewhere = list(NOT_RUN_IN_STATIC)
        self.assertEqual(
            len(static) + len(cluster) + len(elsewhere),
            len(PLACEMENTS),
            "a gate is placed more than once, or not at all",
        )

    def test_elsewhere_placements_say_where_and_why(self):
        """A gate that runs elsewhere names the place and the reason."""
        for name, placement in NOT_RUN_IN_STATIC.items():
            self.assertTrue(
                placement.reason, f"'{name}' has no reason for not being a gate line"
            )
            self.assertTrue(
                placement.tracked_by,
                f"'{name}' does not say where it runs instead",
            )

    def test_not_run_in_static_is_a_view_and_not_a_second_table(self):
        """The exemption list is derived from the table, not maintained beside it."""
        for name, placement in NOT_RUN_IN_STATIC.items():
            self.assertIs(
                PLACEMENTS[name],
                placement,
                "NOT_RUN_IN_STATIC holds a different object than the table does",
            )
            self.assertIs(placement.kind, PlacementKind.ELSEWHERE)


class TestTheRenderedRegistry(unittest.TestCase):
    """The shell file is rendered from the table."""

    def test_the_rendered_region_is_what_the_file_holds(self):
        """The freshness gate's question, asked here so a failure names the cause."""
        self.assertEqual(
            _generated_region(),
            render_static_registry(_MODES).strip("\n"),
            "the generated region of static-gates.sh is not what the manifest renders",
        )

    def test_every_static_placement_becomes_a_gate_line(self):
        """Each static placement is a `gate <mode> "<name>" <command>` line."""
        rendered = render_static_registry(_MODES)
        for name, placement in static_placements():
            self.assertIn(
                f'gate {_MODES[name]} "{name}" {placement.command}',
                rendered,
                f"'{name}' is not a gate line in the rendered registry",
            )

    def test_the_modes_come_from_the_manifest_of_gates(self):
        """The mode is the gate's, not the placement's — so it cannot differ."""
        rendered = render_static_registry(_MODES)
        for name, _placement in static_placements():
            self.assertIn(f'gate {_MODES[name]} "{name}"', rendered)

    def test_the_file_keeps_its_hand_written_parts(self):
        """The generated region is a region, not the whole file."""
        text = _STATIC_GATES_SH.read_text(encoding="utf-8")
        self.assertIn(BEGIN_MARKER, text)
        self.assertIn(END_MARKER, text)
        self.assertIn("gate() {", text)
        self.assertIn("collect_and_report", text)

    def test_the_render_is_stable(self):
        """Rendering twice yields the same text — the generator has no state."""
        first = render_static_registry(_MODES)
        second = render_static_registry(_MODES)
        self.assertEqual(first, second)

    def test_every_prose_line_of_the_registry_is_a_comment(self):
        """The prose above a gate line is a comment, and the renderer says so.

        The renderer used to write the note as it was, which left the `#` to
        the author of the note. A note that forgot it was not a comment but a
        command: the shell registry is a shell file, and the shell read the
        prose above a gate line as much as a person did. The registry printed
        `The: command not found` and still reported every gate as passed.
        """
        rendered = render_static_registry(_MODES)
        for line in rendered.splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("gate "):
                continue
            self.assertTrue(
                stripped.startswith("#"),
                f"the registry holds a line that the shell would run: {line!r}",
            )

    def test_a_note_carries_no_marker_of_its_own(self):
        """The marker is the renderer's business — the note holds prose."""
        for name, placement in static_placements():
            for line in placement.note.split("\n"):
                self.assertFalse(
                    line.startswith("#"),
                    f"the note of '{name}' carries a comment marker: {line!r}",
                )

    def test_the_marker_is_added_to_every_line_of_a_note(self):
        """One place adds the marker, and it adds it to every line."""
        self.assertEqual(
            comment_lines("first line\n\nsecond line"),
            ["# first line", "#", "# second line"],
        )


class TestTheClusterMembership(unittest.TestCase):
    """Membership is declared, and the wrappers agree with the declaration."""

    def test_every_cluster_names_registered_gates(self):
        """A cluster of gates that do not exist is a cluster of nothing."""
        registered = {spec.name for spec in GATES}
        for cluster, members in CLUSTERS.items():
            self.assertIn(cluster, registered, f"'{cluster}' is not a registered gate")
            for member in members:
                self.assertIn(
                    member, registered, f"'{member}' is not a registered gate"
                )

    def test_the_wrappers_declare_the_members_the_table_declares(self):
        """The wrapper runs what the table says it runs."""
        import importlib.util

        by_name = {spec.name: spec for spec in GATES}
        for cluster, members in CLUSTERS.items():
            script = str(by_name[cluster].script)
            path = _ROOT / script
            self.assertTrue(path.is_file(), f"the cluster wrapper {script} is missing")
            spec = importlib.util.spec_from_file_location(
                f"_wrapper_{path.stem}", path
            )
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            declared = [member.name for member in module.MEMBERS]
            self.assertEqual(
                sorted(declared),
                sorted(members),
                f"'{cluster}': the wrapper and the manifest disagree about the members",
            )

    def test_a_member_is_covered_because_it_is_declared(self):
        """Renaming a line inside a wrapper cannot drop a gate from the covered set.

        The inference this replaces read the wrapper's source for the file names
        it mentioned. A wrapper whose source mentions nothing is exactly the
        case where the old inference lost the member — and the member still
        runs, because the cluster runs it.
        """
        from sgate.gate_reachability import covered_scripts

        by_name = {spec.name: spec for spec in GATES}
        # A shell registry whose only gate is the cluster, and a wrapper whose
        # source names none of its members.
        shell_gates = [("blocking", cluster, "python3 " + str(by_name[cluster].script))
                       for cluster in CLUSTERS]
        covered = covered_scripts(_ROOT, shell_gates, by_name)
        for cluster, members in CLUSTERS.items():
            for member in members:
                member_script = str(by_name[member].script)
                self.assertIn(
                    member_script,
                    covered,
                    f"'{member}' is a declared member of '{cluster}' but is not covered",
                )


if __name__ == "__main__":
    unittest.main()
