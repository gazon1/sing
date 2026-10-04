#!/usr/bin/env python3
"""Tests for check-test-task-inputs.py.

The gate's own failure mode is the one it exists to prevent: a parser that stops
matching yields an empty finding list, and an empty finding list reads as a pass. So
these tests are mostly about proving the gate still *sees* things — each fixture is a
small build tree, and the assertions are on the checked count and the findings, not on
the exit code alone.
"""

import pathlib
import re
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / 'check-test-task-inputs.py'

GOOD = '''
// no external roots: the module only points at its own source
tasks.withType<Test>().configureEach {
    systemProperty(
        "commonMain.root",
        layout.projectDirectory.dir("src/commonMain/kotlin").asFile.absolutePath,
    )
}
'''

UNDECLARED_EXTERNAL_ROOT = '''
tasks.withType<Test>().configureEach {
    systemProperty(
        "maestroRoot",
        layout.projectDirectory.dir("../Maestro/flows").asFile.absolutePath,
    )
}
'''

DECLARED_EXTERNAL_ROOT = '''
tasks.withType<Test>().configureEach {
    systemProperty(
        "maestroRoot",
        layout.projectDirectory.dir("../Maestro/flows").asFile.absolutePath,
    )
    inputs.dir(layout.projectDirectory.dir("../Maestro"))
        .withPropertyName("maestroFlows")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
'''

# An ancestor directory covers the root: declaring `../Maestro` satisfies a root that
# points at `../Maestro/flows`.
ANCESTOR_COVERS_ROOT = '''
tasks.withType<Test>().configureEach {
    systemProperty(
        "maestroRoot",
        layout.projectDirectory.dir("../Maestro/flows").asFile.absolutePath,
    )
    inputs.dir(rootProject.projectDirectory.dir("Maestro"))
        .withPropertyName("maestro")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
'''

# Sibling path: declared, but not the tree the root resolves to. Must still fail, or the
# gate would pass on any input existing at all.
DECLARED_SIBLING = '''
tasks.withType<Test>().configureEach {
    systemProperty(
        "maestroRoot",
        layout.projectDirectory.dir("../Maestro/flows").asFile.absolutePath,
    )
    inputs.dir(layout.projectDirectory.dir("../build-logic"))
        .withPropertyName("wrongTree")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
'''


def run_gate(files: dict[str, str]) -> tuple[int, str]:
    """Materialise a fake repo — script included — and run the gate against it.

    The script derives its root from its own location (`__file__/../..`), which is what
    makes it un-bypassable in CI. So the fixture has to *contain* the script rather than
    merely be the working directory: an earlier version of this test set `cwd` and got a
    clean run against the real repository for every fixture, which is a test that passes
    without testing anything.
    """
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        script_dest = root / 'scripts' / 'check-test-task-inputs.py'
        script_dest.parent.mkdir(parents=True, exist_ok=True)
        script_dest.write_text(SCRIPT.read_text())
        script_dest.chmod(0o755)
        for rel, content in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        proc = subprocess.run(
            [sys.executable, str(script_dest)],
            capture_output=True, text=True, cwd=root, env={'PATH': '/usr/bin:/bin'},
        )
        return proc.returncode, proc.stdout + proc.stderr


class RootIsADeclaredInput(unittest.TestCase):
    """The rule: a root outside the module must be covered by a declared input."""

    def test_external_root_without_a_declared_input_fails(self):
        code, out = run_gate({'shared/build.gradle.kts': UNDECLARED_EXTERNAL_ROOT})
        self.assertEqual(code, 1, out)
        self.assertIn('maestroRoot', out)
        self.assertIn('Maestro/flows', out)

    def test_external_root_with_a_declared_input_passes(self):
        code, out = run_gate({'shared/build.gradle.kts': DECLARED_EXTERNAL_ROOT})
        self.assertEqual(code, 0, out)

    def test_ancestor_directory_counts_as_covering(self):
        code, out = run_gate({'shared/build.gradle.kts': ANCESTOR_COVERS_ROOT})
        self.assertEqual(code, 0, out)

    def test_a_declared_but_unrelated_tree_does_not_count(self):
        code, out = run_gate({'shared/build.gradle.kts': DECLARED_SIBLING})
        self.assertEqual(code, 1, out)
        self.assertIn('maestroRoot', out)

    def test_a_root_inside_the_module_needs_no_declaration(self):
        code, out = run_gate({'shared/build.gradle.kts': GOOD})
        self.assertEqual(code, 0, out)


class TheGateIsNotVacuous(unittest.TestCase):
    """A gate that checks nothing must not report success."""

    def test_the_real_repository_has_external_roots_to_check(self):
        """Guards against the gate silently checking zero roots forever.

        This is the assertion that would have caught the first version of the parser,
        which matched no path expression, skipped every root as "no literal path", and
        printed OK.
        """
        proc = subprocess.run([sys.executable, str(SCRIPT)], capture_output=True, text=True)
        out = proc.stdout + proc.stderr
        self.assertEqual(proc.returncode, 0, out)
        self.assertNotIn('checked 0 external roots', out)
        match = re.search(r'OK — (\d+) external root', out)
        self.assertIsNotNone(match, out)
        self.assertGreaterEqual(int(match.group(1)), 1, out)

    def test_a_build_file_the_parser_cannot_read_does_not_pass(self):
        """A build file with no recognisable path property must not report success.

        This is the fixture for a broken parser: the declarations exist, the gate simply
        stops matching them. It has to fail rather than report a clean tree.
        """
        code, out = run_gate({'shared/build.gradle.kts': 'tasks.withType<Test>() {}\n'})
        self.assertEqual(code, 1, out)
        self.assertIn('proves nothing', out)

    def test_a_non_path_system_property_is_not_treated_as_a_root(self):
        """A boolean or tag property is not this gate's business."""
        code, out = run_gate({
            'shared/build.gradle.kts': (
                'systemProperty("junit.jupiter.execution.parallel.enabled", "true")\n'
            ),
        })
        # No path properties at all → the guard fires, because the gate has nothing to say
        # about this tree and must not pretend otherwise.
        self.assertEqual(code, 1, out)
        self.assertIn('proves nothing', out)


if __name__ == '__main__':
    unittest.main()
