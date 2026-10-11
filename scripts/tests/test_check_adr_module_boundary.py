#!/usr/bin/env python3
"""Tests for check-adr-module-boundary.py — the gate that keeps the CLI light (#542).

A gate that cannot fail is indistinguishable from a gate that passes. So the bulk of
these tests plant a violation and assert it is *found*, including the one shape that
would make the gate vacuous: a runtime dependency that arrives through a middle
module, so a direct-edge check would pass while the 127 MB is back.

The last two tests are the other direction. A parser that finds nothing returns an
empty finding list, and an empty finding list reads as a pass — so "could not
measure" has to be reachable and has to be distinguished from "clean".
"""

import importlib.util
import pathlib
import tempfile
import unittest

SCRIPT = (
    pathlib.Path(__file__).resolve().parent.parent / 'check-adr-module-boundary.py'
)

_spec = importlib.util.spec_from_file_location('check_adr_module_boundary', SCRIPT)
boundary = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(boundary)

WORKSPACE = pathlib.Path(__file__).resolve().parent.parent.parent


class ReachTest(unittest.TestCase):
    """The closure is what reaches the classpath, not the direct edge."""

    def test_direct_edge_to_shared_is_reachable(self):
        edges = {'adr-cli': {'shared'}, 'shared': set()}
        self.assertEqual(boundary.reachable(edges, 'adr-cli'), {'shared'})

    def test_indirect_edge_through_a_middle_module_is_reachable(self):
        """The shape a direct-edge check would miss.

        `:adr-cli -> :new-helper -> :shared` ships exactly the same bytes as
        `:adr-cli -> :shared`. The gate exists to catch the classpath, and the
        classpath is the transitive closure.
        """
        edges = {
            'adr-cli': {'new-helper'},
            'new-helper': {'shared'},
            'shared': {'mcp-server'},
        }
        self.assertEqual(
            boundary.reachable(edges, 'adr-cli'), {'new-helper', 'shared', 'mcp-server'}
        )

    def test_cycle_does_not_hang(self):
        edges = {'a': {'b'}, 'b': {'a'}}
        self.assertEqual(boundary.reachable(edges, 'a'), {'b'})

    def test_start_is_not_in_its_own_reachable_set(self):
        edges = {'adr-cli': {'adr-cli', 'core-base'}}
        self.assertEqual(boundary.reachable(edges, 'adr-cli'), {'core-base'})


class NonRuntimeConfigurationTest(unittest.TestCase):
    """Build-time and test configurations never reach the shipped distribution.

    `detektPlugins(project(":detekt-rules"))` is on every module in this repo. If it
    counted, the gate's verdict would depend on whether `:detekt-rules` happens to
    stay a leaf — a fact about today masquerading as an invariant.
    """

    def _deps(self, text: str) -> set[str]:
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'build.gradle.kts'
            path.write_text(text, encoding='utf-8')
            return boundary.runtime_project_deps(path)

    def test_detekt_plugins_edge_is_ignored(self):
        self.assertEqual(
            self._deps('    detektPlugins(project(":detekt-rules"))'), set()
        )

    def test_test_implementation_edge_is_ignored(self):
        self.assertEqual(
            self._deps('    testImplementation(project(":shared"))'), set()
        )

    def test_android_test_edge_is_ignored(self):
        self.assertEqual(
            self._deps('    androidTestImplementation(project(":androidApp"))'), set()
        )

    def test_runtime_edge_on_a_line_with_a_trailing_comment_is_kept(self):
        """`detektPlugins(...)` appears in a comment on `desktopApp` today.

        A filter that matched anywhere in the line rather than at the declaration
        would drop the `implementation(project(":shared"))` on that same line and
        quietly stop examining `:desktopApp`.
        """
        self.assertEqual(
            self._deps(
                '    detektPlugins(project(":detekt-rules"))  // rule X\n'
                '    implementation(project(":shared"))'
            ),
            {'shared'},
        )

    def test_runtime_and_non_runtime_on_one_line(self):
        self.assertEqual(
            self._deps('    implementation(project(":core-base")); '
                       'detektPlugins(project(":detekt-rules"))'),
            {'core-base'},
        )


class IncludesTest(unittest.TestCase):
    def test_leading_colon_is_stripped(self):
        self.assertEqual(boundary.module_name(':adr-cli'), 'adr-cli')

    def test_nested_module_name_keeps_its_colons(self):
        self.assertEqual(boundary.module_name(':tools:unwritten-properties'),
                         'tools:unwritten-properties')

    def test_include_regex_finds_both_quote_styles(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'settings.gradle.kts'
            path.write_text('include(":a")\ninclude(\':b\')\n', encoding='utf-8')
            self.assertEqual(boundary.read_includes(path), {'a', 'b'})

    def test_missing_settings_file_is_empty_not_an_error(self):
        self.assertEqual(
            boundary.read_includes(pathlib.Path('/nonexistent/settings.gradle.kts')), set()
        )


class RealTreeTest(unittest.TestCase):
    """The gate's verdict on this checkout — the thing CI actually runs."""

    def test_light_modules_are_light(self):
        included = boundary.read_includes(WORKSPACE / 'settings.gradle.kts')
        edges = boundary.graph(WORKSPACE, included)
        for light in boundary.LIGHT_MODULES:
            hit = boundary.reachable(edges, light) & boundary.APPLICATION_MODULES
            self.assertEqual(
                hit, set(),
                f'{light} reaches {sorted(hit)} — the CLI distribution grows back',
            )

    def test_the_gate_would_not_pass_vacuously_here(self):
        """`:adr-cli` really does depend on `:adr-core`, which depends on `:core-base`.

        Without this, the test above also passes if the parser simply stopped
        matching and every module looks isolated.
        """
        included = boundary.read_includes(WORKSPACE / 'settings.gradle.kts')
        edges = boundary.graph(WORKSPACE, included)
        self.assertIn('core-base', boundary.reachable(edges, 'adr-cli'))

    def test_the_measurement_floor_is_satisfied_by_this_tree(self):
        """The `could not measure` floor must be below reality, or the gate is dead.

        11 includes and 11 build files today. If a refactor ever drops the gate into
        permanent exit 2, that reads as a failing gate rather than a broken one.
        """
        included = boundary.read_includes(WORKSPACE / 'settings.gradle.kts')
        self.assertGreaterEqual(len(included), boundary.MIN_INCLUDES)
        present = [m for m in included
                   if (WORKSPACE / m / 'build.gradle.kts').is_file()]
        self.assertGreaterEqual(len(present), boundary.MIN_BUILD_FILES)


if __name__ == '__main__':
    unittest.main()