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
  * a contract with no entries;
  * a site the register of debt names that no source declares any more;
  * a site the register of debt names that only a namesake in another package
    or in a sub-package still declares;
  * a parameter the register of debt names that no declaration of the site has.

and two positive controls: the contract as committed must pass, and so must a
site that really is declared in its own package.

The tree is temporary. The gate takes `--conf`, `--source-root`, `--root` and
`--register`, so the test points it at a fixture tree instead of the repository
— the gate never reads the repository's own files in these tests, and the
repository is never modified.
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
    # The sources live under the path of their package, as the repository lays
    # them out: the gate resolves a registered site by its package.
    sources = root / "shared" / "src" / "commonMain" / "kotlin"
    write(
        sources / "com" / "singularity" / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
        f"package {PACKAGE}\n\ndata class Task(val id: String)\n",
    )
    write(
        sources / "com" / "singularity" / "todo" / "feature" / "notes" / "Note.kt",
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

    def run_gate(self, root: pathlib.Path, conf: pathlib.Path, register: pathlib.Path | None = None) -> int:
        argv = [
            "--conf",
            str(conf),
            "--source-root",
            str(root / "shared" / "src" / "commonMain" / "kotlin"),
            "--root",
            str(root),
            # The register of debt lives in the repository, not in the fixture
            # tree, so the fixture run points at a path that holds no sites —
            # unless the test is about the register itself.
            "--register",
            str(register if register is not None else root / "no-register.kt"),
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

    def test_a_registered_site_that_no_longer_exists_fails(self):
        """The register of debt must not name a composable that is gone.

        The detekt rule exempts by name, so an entry whose composable was
        deleted exempts nothing — and the debt it was accounting for has
        vanished from the count without anybody deciding to pay it.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            register = root / "register.kt"
            write(
                register,
                'val KNOWN = mapOf(\n'
                '    "com.singularity.todo.feature.tasks.presentation.GoneDialog" to\n'
                '        mapOf("titles" to "List<String>"),\n'
                ')\n',
            )
            self.assertEqual(self.run_gate(root, conf, register), 1)

    def test_a_registered_site_that_exists_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            register = root / "register.kt"
            write(
                register,
                'val KNOWN = mapOf(\n'
                f'    "{PACKAGE}.Task" to\n'
                '        mapOf("titles" to "List<String>"),\n'
                ')\n',
            )
            self.assertEqual(self.run_gate(root, conf, register), 0)

    def test_a_registered_nested_site_is_found(self):
        """A composable nested in a class is named class-first, like the compiler names it."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            sources = (
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "nav"
            )
            write(
                sources / "Nav3State.kt",
                "package com.singularity.todo.feature.nav\n\n"
                "class Nav3State {\n"
                "    fun toDecoratedEntries(decorators: List<String>) {}\n"
                "}\n",
            )
            register = root / "register.kt"
            write(
                register,
                'val KNOWN = mapOf(\n'
                '    "com.singularity.todo.feature.nav.Nav3State.toDecoratedEntries" to\n'
                '        mapOf("decorators" to "List<String>"),\n'
                ')\n',
            )
            self.assertEqual(self.run_gate(root, conf, register), 0)

    def test_the_committed_register_names_sites_that_exist(self):
        """The positive control for the register: every committed site is real."""
        sites = gate.read_register_sites(gate.DEBT_REGISTER)
        self.assertGreater(len(sites), 0, "the register names no site at all")
        findings = gate.check_register_sites(sites, gate.source_roots(gate.ROOT))
        self.assertEqual(findings, [], f"the committed register names sites that are gone: {findings}")

    def test_a_site_deleted_while_another_package_has_the_same_name_fails(self):
        """A name that still exists elsewhere must not stand in for the site.

        `ListPickerSheet` is declared in two packages in this repository. A
        check that looked for the name anywhere in the tree would pass a tree
        where the registered one was deleted — and the gate exists for exactly
        that deletion.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            other = root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity" / "todo" / "core" / "ui"
            write(
                other / "PickerDsl.kt",
                "package com.singularity.todo.core.ui\n\n"
                "fun <T> ListPickerSheet(items: List<T>) {}\n",
            )
            register = root / "register.kt"
            write(
                register,
                'val KNOWN = mapOf(\n'
                '    "com.singularity.todo.core.ui.sheet.ListPickerSheet" to\n'
                '        mapOf("items" to "List<T>"),\n'
                ')\n',
            )
            self.assertEqual(self.run_gate(root, conf, register), 1)

    def test_a_registered_parameter_that_was_removed_fails(self):
        """The register must not name a parameter the code no longer declares.

        The rule cannot answer this one: a parameter that is gone has no
        declared type to compare, and a composable may have overloads. The gate
        reads the whole repository, so it can.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            sources = (
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "presentation"
            )
            write(
                sources / "Dialog.kt",
                "package com.singularity.todo.feature.tasks.presentation\n\n"
                "fun Dialog(blockerTitles: List<String>) {}\n",
            )
            register = root / "register.kt"
            write(
                register,
                'val KNOWN = mapOf(\n'
                '    "com.singularity.todo.feature.tasks.presentation.Dialog" to\n'
                '        mapOf("titles" to "List<String>"),\n'
                ')\n',
            )
            self.assertEqual(self.run_gate(root, conf, register), 1)

    def test_a_registered_parameter_that_still_exists_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            sources = (
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "presentation"
            )
            write(
                sources / "Dialog.kt",
                "package com.singularity.todo.feature.tasks.presentation\n\n"
                "fun Dialog(blockerTitles: List<String>) {}\n",
            )
            register = root / "register.kt"
            write(
                register,
                'val KNOWN = mapOf(\n'
                '    "com.singularity.todo.feature.tasks.presentation.Dialog" to\n'
                '        mapOf("blockerTitles" to "List<String>"),\n'
                ')\n',
            )
            self.assertEqual(self.run_gate(root, conf, register), 0)

    def test_a_qualified_name_quoted_in_the_register_prose_is_not_a_site(self):
        """The register's own documentation must not become a registered site."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            register = root / "register.kt"
            write(
                register,
                '// The compiler prints "com.singularity.todo.feature.tasks.presentation.Dialog"\n'
                '// in its report, and the register is keyed by that name.\n'
                'val KNOWN = mapOf(\n'
                f'    "{PACKAGE}.Task" to\n'
                '        mapOf("titles" to "List<String>"),\n'
                ')\n',
            )
            self.assertEqual(self.run_gate(root, conf, register), 0)


    def test_a_registered_parameter_removed_from_a_parameter_less_composable_fails(self):
        """The debt paid by deleting the last parameter of a composable is still debt.

        The composable still exists — it just has no parameters left. A check
        that reads "no parameters declared" as "nothing to check" skips the site
        exactly here, and the register keeps naming a parameter the code no
        longer has. The rule cannot catch it either: a parameter that is gone
        has no declared type to compare.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            sources = (
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "presentation"
            )
            write(
                sources / "Dialog.kt",
                "package com.singularity.todo.feature.tasks.presentation\n\n"
                "fun Dialog() {}\n",
            )
            register = root / "register.kt"
            write(
                register,
                'val KNOWN = mapOf(\n'
                '    "com.singularity.todo.feature.tasks.presentation.Dialog" to\n'
                '        mapOf("titles" to "List<String>"),\n'
                ')\n',
            )
            self.assertEqual(self.run_gate(root, conf, register), 1)

    def test_a_site_deleted_while_a_sub_package_has_the_same_name_fails(self):
        """A namesake in a sub-package must not stand in for the site.

        The package directory holds the sub-packages as well, and a name in one
        of them belongs to another package. The gate compares the package the
        file *declares*, not the directory the file sits in.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            sources = (
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "presentation"
            )
            write(
                sources / "sub" / "Other.kt",
                "package com.singularity.todo.feature.tasks.presentation.sub\n\n"
                "fun Dialog(blockerTitles: List<String>) {}\n",
            )
            register = root / "register.kt"
            write(
                register,
                'val KNOWN = mapOf(\n'
                '    "com.singularity.todo.feature.tasks.presentation.Dialog" to\n'
                '        mapOf("titles" to "List<String>"),\n'
                ')\n',
            )
            self.assertEqual(self.run_gate(root, conf, register), 1)

    def test_a_site_declared_in_its_own_package_passes(self):
        """The positive control for the package filter: the site is really there."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            sources = (
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "presentation"
            )
            write(
                sources / "Dialog.kt",
                "package com.singularity.todo.feature.tasks.presentation\n\n"
                "fun Dialog(blockerTitles: List<String>) {}\n",
            )
            register = root / "register.kt"
            write(
                register,
                'val KNOWN = mapOf(\n'
                '    "com.singularity.todo.feature.tasks.presentation.Dialog" to\n'
                '        mapOf("blockerTitles" to "List<String>"),\n'
                ')\n',
            )
            self.assertEqual(self.run_gate(root, conf, register), 0)

    def test_a_source_with_a_header_before_its_package_is_read(self):
        """The package a file declares is the package line, wherever the line sits.

        A file-level annotation or a licence header may come first. A check
        that anchors the package to the start of the file reads such a file as
        having no package at all — and then declares every site in it gone.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            sources = (
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "presentation"
            )
            write(
                sources / "Dialog.kt",
                "// Copyright (c) the repository.\n"
                "@file:Suppress(\"unused\")\n\n"
                "package com.singularity.todo.feature.tasks.presentation\n\n"
                "fun Dialog(blockerTitles: List<String>) {}\n",
            )
            register = root / "register.kt"
            write(
                register,
                'val KNOWN = mapOf(\n'
                '    "com.singularity.todo.feature.tasks.presentation.Dialog" to\n'
                '        mapOf("blockerTitles" to "List<String>"),\n'
                ')\n',
            )
            self.assertEqual(self.run_gate(root, conf, register), 0)


if __name__ == "__main__":
    unittest.main()
