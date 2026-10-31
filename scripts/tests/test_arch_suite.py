"""Tests for the architecture suite's declaration and its readers.

The suite's package, path fragment, directory segment and report directory were
declared in four places: the build's test filter, the run-floor gate's path
fragments, the slow-population gate's report directories, and the impact map's
package name.

A rename that reached one of them left the others naming a package that no
longer existed — and every one of those gates kept reporting green, because a
gate that looks for a suite that is not there counts nothing instead of failing.
The silence is the shape of the defect: nothing goes red, and the suite
quietly stops being checked.

So the declaration lives in ONE file and the four readers read it. These tests
hold two things: the declaration says what it says, and nobody writes it down
again.
"""

import importlib.util
import pathlib
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
ROOT = SCRIPTS.parent
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import arch_suite  # noqa: E402 — the path entry above is the reason for the late import


class DeclarationTest(unittest.TestCase):
    """The declaration file is the record, and it says what it must say."""

    def test_the_declaration_exists(self):
        self.assertTrue(
            arch_suite.DECLARATION.is_file(),
            f"{arch_suite.DECLARATION} is the architecture suite's declaration; "
            "without it the build and the gates cannot agree on what the suite is",
        )

    def test_it_declares_the_package(self):
        self.assertEqual(arch_suite.package(), "com.singularity.todo.arch")

    def test_it_declares_the_path_fragment(self):
        self.assertEqual(arch_suite.path_fragment(), "com/singularity/todo/arch/")

    def test_it_declares_the_directory_marker(self):
        """With the slashes: a marker without them matches a file whose name
        merely starts with the same letters."""
        self.assertEqual(arch_suite.directory_marker(), "/arch/")

    def test_it_declares_the_report_directory(self):
        self.assertEqual(
            arch_suite.report_dir(), "shared/build/test-results/archTest"
        )


class OneDeclarationTest(unittest.TestCase):
    """Nobody writes the suite's facts down a second time.

    A second copy is a copy that can drift, and the drift is silent: the build
    would filter one package while the gates looked for another, and both would
    report green.
    """

    #: The files that used to carry a copy, and the literals they carried.
    READERS = {
        "shared/build.gradle.kts": ("com.singularity.todo.arch",),
        "scripts/check-test-runs.py": ("com/singularity/todo/arch/",),
        "scripts/check-slow-tests-ran.py": ("build/test-results/archTest",),
        "scripts/test-impact-map.py": ("com.singularity.todo.arch",),
    }

    def test_no_reader_carries_a_copy(self):
        for relative, literals in self.READERS.items():
            text = (ROOT / relative).read_text(encoding="utf-8")
            for literal in literals:
                self.assertNotIn(
                    literal,
                    text,
                    f"{relative} carries a copy of the architecture suite's "
                    f"declaration ('{literal}'). The declaration lives in "
                    "config/docs/arch-suite.properties, and a copy here is a "
                    "copy that can drift from the build.",
                )

    def test_the_build_reads_the_declaration(self):
        text = (ROOT / "shared/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("arch-suite.properties", text)
        self.assertIn("arch.package", text)

    def test_the_gates_read_the_declaration(self):
        for relative in (
            "scripts/check-test-runs.py",
            "scripts/check-slow-tests-ran.py",
            "scripts/test-impact-map.py",
        ):
            text = (ROOT / relative).read_text(encoding="utf-8")
            self.assertIn("arch_suite", text, f"{relative} must read the declaration")


class ReaderAgreementTest(unittest.TestCase):
    """The readers and the build agree about what the suite is.

    The build filters its test tasks by the package; the impact map names the
    task that runs a class of that package. If the two disagreed, the map would
    emit a command for a task that does not own the class, and the command
    would run nothing and report green.
    """

    def test_the_impact_map_names_the_declared_package(self):
        text = (ROOT / "scripts/test-impact-map.py").read_text(encoding="utf-8")
        self.assertIn("arch_suite.package()", text)
        self.assertIn("arch_suite.directory_marker()", text)

    def test_the_run_floor_keeps_the_declared_fragment(self):
        spec = importlib.util.spec_from_file_location(
            "check_test_runs", SCRIPTS / "check-test-runs.py"
        )
        module = importlib.util.module_from_spec(spec)
        sys.modules["check_test_runs"] = module
        spec.loader.exec_module(module)
        self.assertEqual(
            module.INCLUDED_PATHS["shared:archTest"],
            (arch_suite.path_fragment(),),
        )
        self.assertEqual(
            module.EXCLUDED_PATHS["shared:jvmTest"],
            (arch_suite.path_fragment(),),
        )
        self.assertEqual(
            module.SOURCE_SETS["shared:archTest"], arch_suite.report_dir()
        )


if __name__ == "__main__":
    unittest.main()
