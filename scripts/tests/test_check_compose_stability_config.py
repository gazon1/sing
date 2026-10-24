#!/usr/bin/env python3
"""Tests for check-compose-stability-config.py — the stability contract must be real.

The gate's own failure mode is the one it exists to prevent: a check that reads
the contract, finds nothing to complain about, and exits 0 — while the contract
names a type that does not exist, or while no module reads the file at all.
Either of those leaves the repository *looking* as though the stability
contract is in force while the compiler has never been told anything.

So the assertions are planted violations, each of which must produce a failure:

  * an entry that names no type in the shared sources;
  * an entry that is not a fully-qualified name at all;
  * a module that does not read the contract;
  * a module that names the contract but never hands it to the compiler;
  * a contract with no entries.

and one positive control: the contract as committed must pass.

The tree is temporary. The gate takes `--conf`, `--source-root` and `--root`,
so the test points it at a fixture tree instead of the repository — the gate
never reads the repository's own files in these tests, and the repository is
never modified.
"""

import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

import importlib.util

_spec = importlib.util.spec_from_file_location(
    "check_compose_stability_config", SCRIPTS / "check-compose-stability-config.py"
)
gate = importlib.util.module_from_spec(_spec)
sys.modules["check_compose_stability_config"] = gate
_spec.loader.exec_module(gate)


PACKAGE = "com.singularity.todo.feature.tasks.domain.model"


def write(path: pathlib.Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def build_tree(
    root: pathlib.Path,
    conf: str,
    wired: bool = True,
    hand_to_compiler: bool = True,
) -> pathlib.Path:
    """A fixture tree: a contract, one declared type, and two build files.

    `wired` says whether the build file *names* the contract; `hand_to_compiler`
    says whether it actually hands it to the compiler. They are separate
    failures and the gate reports them separately.
    """
    sources = root / "shared" / "src" / "commonMain" / "kotlin"
    write(
        sources / "feature" / "tasks" / "domain" / "model" / "Task.kt",
        f"package {PACKAGE}\n\ndata class Task(val id: String)\n",
    )
    write(
        sources / "feature" / "notes" / "Note.kt",
        "package com.singularity.todo.feature.notes\n\nimport kotlinx.datetime.LocalDate\n"
        "\ndata class Note(val at: LocalDate)\n",
    )

    conf_path = root / "config" / "compose" / "stability_config.conf"
    write(conf_path, conf)

    body = ""
    if wired:
        body += 'val contract = file("config/compose/stability_config.conf")\n'
    if hand_to_compiler:
        body += "stabilityConfigurationFiles.addAll(contract)\n"
    write(root / "shared" / "build.gradle.kts", body)
    write(root / "desktopApp" / "build.gradle.kts", body)
    return conf_path


class ComposeStabilityConfigTest(unittest.TestCase):

    def run_gate(self, root: pathlib.Path, conf: pathlib.Path) -> int:
        argv = [
            "--conf",
            str(conf),
            "--source-root",
            str(root / "shared" / "src" / "commonMain" / "kotlin"),
            "--root",
            str(root),
        ]
        return gate.run(gate.cli.main, argv)

    def test_the_committed_contract_passes(self):
        """The positive control: the repository's own contract is clean."""
        self.assertEqual(gate.run(gate.cli.main, []), 0)

    def test_an_entry_that_names_no_type_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(
                root,
                f"{PACKAGE}.Task\ncom.singularity.todo.feature.tasks.domain.model.TaskThatIsGone\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_library_entry_that_is_never_imported_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, "kotlinx.datetime.LocalTime\n")
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_library_entry_that_is_imported_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, "kotlinx.datetime.LocalDate\n")
            self.assertEqual(self.run_gate(root, conf), 0)

    def test_an_entry_that_is_not_a_qualified_name_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, "Task\n")
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_hash_comment_is_not_a_comment_fails(self):
        """The compiler's parser knows `//` and nothing else.

        A `#` line is read as a class name, so a contract written with `#`
        comments silently loses every entry after the first one. The gate must
        refuse it rather than accept a file the compiler cannot read.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"# a comment\n{PACKAGE}.Task\n")
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_module_that_does_not_read_the_contract_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n", wired=False)
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_module_that_never_hands_the_contract_to_the_compiler_fails(self):
        """Naming the file is not the same as applying it."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(
                root,
                f"{PACKAGE}.Task\n",
                wired=True,
                hand_to_compiler=False,
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_an_empty_contract_fails(self):
        """An empty contract declares everything unstable — the state before it."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, "// only a comment\n")
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_missing_contract_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            missing = root / "config" / "compose" / "stability_config.conf"
            self.assertEqual(self.run_gate(root, missing), 1)


if __name__ == "__main__":
    unittest.main()
