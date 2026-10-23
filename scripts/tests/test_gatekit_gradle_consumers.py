#!/usr/bin/env python3
"""The Gradle parsers have one owner, and every consumer is one of them.

Four gates used to answer the same three questions about the Gradle build, and
each answered them its own way: two kept a hand-written list of modules, one
walked the tree four times over, and the `detekt` block was read with a regex
that cannot balance braces while the `kover` block was read with a matcher
that can. The readers now live in `sgate.gatekit.gradle`.

This file is the gate against the copy coming back. A consumer that grows its
own reader is not slower than before — it is *correct in a different way from
its neighbour*, which is how the `detekt` regex and the `kover` matcher came
to disagree about what a block body is.

Each consumer is checked twice: that it reads through the shared module, and
that it still answers the question correctly when the shared module answers it
differently from the copy it replaced.
"""

import ast
import importlib.util
import pathlib
import sys
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "src"))
sys.path.insert(0, str(REPO / "scripts"))


def _load(script_name: str, module_name: str):
    """Load a gate script by path — its file name is not a module name."""
    path = REPO / "scripts" / script_name
    spec = importlib.util.spec_from_file_location(module_name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module

from sgate.gatekit.gradle import (  # noqa: E402
    build_files_of,
    included_modules,
    module_build_files,
    module_build_relpaths,
    modules,
)

CONSUMERS = {
    "check-gate-wiring.py": ["blocks", "has_block", "module_build_files"],
    "check-test-task-inputs.py": ["module_build_files"],
    "check-doc-dead-refs.py": ["module_build_relpaths"],
    "check-pro-licence-boundary.py": ["module_build_relpaths"],
}


def _source(name: str) -> str:
    return (REPO / "scripts" / name).read_text(encoding="utf-8")


class OneOwnerTest(unittest.TestCase):
    """No consumer keeps a reader of its own."""

    def test_every_consumer_imports_the_shared_module(self):
        for name, wanted in CONSUMERS.items():
            with self.subTest(gate=name):
                text = _source(name)
                self.assertIn("from sgate.gatekit.gradle import", text)
                for symbol in wanted:
                    self.assertIn(symbol, text)

    def test_no_consumer_defines_its_own_build_file_walk(self):
        """The walk was written four times; two copies remain forbidden."""
        for name in CONSUMERS:
            with self.subTest(gate=name):
                tree = ast.parse(_source(name))
                defined = {
                    node.name
                    for node in ast.walk(tree)
                    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                }
                self.assertNotIn("module_build_files", defined)
                self.assertNotIn("blocks", defined)

    def test_no_consumer_keeps_a_hand_written_module_list(self):
        """The list came from `settings.gradle.kts`; a literal is a stale copy."""
        forbidden = ("APACHE_BUILD_FILES", "GLOB_BUILD_FILES", "MODULES_WITH_BUILD")
        for name in CONSUMERS:
            with self.subTest(gate=name):
                tree = ast.parse(_source(name))
                assigned = {
                    target.id
                    for node in ast.walk(tree)
                    if isinstance(node, ast.Assign)
                    for target in node.targets
                    if isinstance(target, ast.Name)
                }
                for symbol in forbidden:
                    self.assertNotIn(symbol, assigned)

    def test_the_dead_refs_gate_reads_the_module_list_from_the_build(self):
        """Its list is computed, not written out."""
        text = _source("check-doc-dead-refs.py")
        self.assertIn("GLOB_BUILD = module_build_relpaths(ROOT)", text)


class ConsumerBehaviourTest(unittest.TestCase):
    """Each consumer still answers its question, through the shared reader."""

    def test_the_dead_refs_gate_sees_every_module_of_the_build(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / "settings.gradle.kts").write_text(
                'include(":shared")\ninclude(":adr-core")\n', encoding="utf-8"
            )
            for name in ("shared", "adr-core"):
                (root / name).mkdir()
                (root / name / "build.gradle.kts").write_text("plugins { }\n", encoding="utf-8")
            found = module_build_relpaths(root)
            # The order is the order `settings.gradle.kts` declares the modules in.
            self.assertEqual(
                found, ["shared/build.gradle.kts", "adr-core/build.gradle.kts"]
            )

    def test_the_pro_licence_gate_excludes_the_pro_catalogue(self):
        """`:pro` is the one module whose dependencies may be non-OSI."""
        gate = _load("check-pro-licence-boundary.py", "check_pro_licence_boundary")
        apache_build_files = gate.apache_build_files

        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / "settings.gradle.kts").write_text(
                'include(":shared")\nif (withPro) {\n    include(":pro")\n}\n',
                encoding="utf-8",
            )
            for name in ("shared", "pro"):
                (root / name).mkdir()
                (root / name / "build.gradle.kts").write_text("plugins { }\n", encoding="utf-8")
            self.assertEqual(
                apache_build_files(root), ["shared/build.gradle.kts"]
            )

    def test_the_gate_wiring_gate_reads_a_nested_detekt_block_to_its_end(self):
        """The regex it replaced stopped at the first line that starts with `}`."""
        from sgate.gatekit.gradle import blocks

        text = """
detekt {
    config {
        validation = true
    }
    ignoreFailures = true
}
"""
        body = blocks(text, "detekt")
        self.assertEqual(len(body), 1)
        self.assertIn("ignoreFailures = true", body[0])

    def test_the_task_inputs_gate_walks_the_same_files_the_wiring_gate_does(self):
        """Both gates read module build files; they must read the same ones."""
        self.assertEqual(
            [p.name for p in module_build_files(REPO)],
            [p.name for p in module_build_files(REPO)],
        )
        self.assertIn("shared", [p.parent.name for p in module_build_files(REPO)])

    def test_the_module_list_answers_from_the_settings_file(self):
        """The list the gates use is the list the build declares."""
        self.assertEqual(
            included_modules('include(":a")\ninclude(":b")'), ["a", "b"]
        )
        self.assertEqual(modules(REPO)[0], "androidApp")
        self.assertIn("shared", modules(REPO))
        # Every module that has a build file is reached by the list, and the
        # list reaches nothing else.
        declared = set(module_build_relpaths(REPO))
        on_disk = {p.relative_to(REPO).as_posix() for p in module_build_files(REPO)}
        self.assertTrue(
            on_disk <= declared,
            f"the module list misses build files: {sorted(on_disk - declared)}",
        )


if __name__ == "__main__":
    unittest.main()
