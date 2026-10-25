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
  * an entry that names an `object`, which the compiler judges stable on its own;
  * an entry that names a `typealias`, which resolves to no class at all;
  * an implementation that breaks the promise of the interface its entry names;
  * a module that does not read the contract;
  * a module that names the contract but never hands it to the compiler;
  * a contract with no entries;
  * a site the register of debt names that no source declares any more;
  * a site the register of debt names that only a namesake in another package
    or in a sub-package still declares;
  * a parameter the register of debt names that no declaration of the site has.

and three positive controls: the contract as committed must pass, so must a
site that really is declared in its own package, and so must an entry that
names an interface whose implementations keep the promise.

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

    def run_gate(
        self,
        root: pathlib.Path,
        conf: pathlib.Path,
        register: pathlib.Path | None = None,
        baseline: str | None = None,
    ) -> int:
        register_path = register if register is not None else root / "no-register.kt"
        if register is None:
            # An empty register, not a missing one: the gate reads the file it
            # is pointed at, and a file that is not there is a finding of its
            # own — the debt is not measured at all.
            write(register_path, "val KNOWN = mapOf()\n")
        baseline_path = root / "debt-baseline.conf"
        if baseline is None:
            # The recorded size follows the fixture, so that the ratchet is
            # only a finding when the test says it is one.
            debt = gate.read_register(register_path)
            baseline = (
                f"sites = {len(debt)}\n"
                f"parameters = {sum(len(p) for p in debt.values())}\n"
            )
        write(baseline_path, baseline)
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
            str(register_path),
            "--debt-baseline",
            str(baseline_path),
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
        """The positive control for the register: every committed site is real.

        The committed register is empty — the debt it named is paid — and an
        empty register makes the first half of this control vacuous: a reader
        that could not read a register at all would pass it. So the control
        keeps a second half that reads a register that *does* name a site, and
        the reader has to find it.
        """
        sites = gate.read_register_sites(gate.DEBT_REGISTER)
        findings = gate.check_register_sites(sites, gate.source_roots(gate.ROOT))
        self.assertEqual(findings, [], f"the committed register names sites that are gone: {findings}")

        with tempfile.TemporaryDirectory() as tmp:
            register = pathlib.Path(tmp) / "register.kt"
            write(
                register,
                'val KNOWN = mapOf(\n'
                f'    "{PACKAGE}.Task" to\n'
                '        mapOf("titles" to "List<String>"),\n'
                ')\n',
            )
            self.assertEqual(
                gate.read_register_sites(register),
                [f"{PACKAGE}.Task"],
                "the register reader cannot read a register that names a site",
            )

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

    def test_a_contract_type_that_declares_a_var_fails(self):
        """The contract says the type never changes; a `var` says otherwise.

        The compiler believes the contract: it compares every instance of the
        type by `equals` and skips the recomposition. A `var` means there is a
        second version of the value, and the comparison is of the wrong one.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\ndata class Task(var id: String)\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_contract_type_with_a_private_constructor_is_checked(self):
        """A private constructor is still a constructor, and its class a class.

        The gate walks the header of a class to find the constructor and the
        body. A visibility modifier in front of `constructor` used to stop
        the walk, so the body was never found — and a `var` inside a class
        the contract vouches for went unseen.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\n"
                "class Task private constructor(val id: String) {\n"
                "    var name: String = \"\"\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_contract_type_whose_type_is_inferred_from_a_mutable_factory_fails(self):
        """An inferred type says nothing — but the initializer says a lot.

        `val tags = mutableListOf<String>()` has no declared type, so the
        declared type cannot be read. The initializer is what says the
        collection can change, and the contract promises it cannot.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\n"
                "class Task(val id: String) {\n"
                "    val tags = mutableListOf<String>()\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_class_without_a_body_does_not_take_the_next_class_body(self):
        """A class without a body has no body.

        The class after it has one, and the search for a body must not run
        into it: a class that promises to be immutable is failed for a `var`
        it does not have, and a class that has one is passed for a `var` it
        does.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\n"
                "data class Task(val id: String)\n\n"
                "data class Other(val name: String) {\n"
                "    var mutable: String = \"\"\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 0)

    def test_a_nested_contract_type_is_found_under_its_outer_class(self):
        """A nested class is named `Outer.Nested`, the way the compiler names it.

        The gate builds the name of a class from the classes around it. In the
        wrong order the name never matches the contract, and the class the
        contract vouches for is not checked at all.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task.Nested\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\n"
                "class Task {\n"
                "    class Nested {\n"
                "        var mutable: String = \"\"\n"
                "    }\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_property_behind_a_modifier_or_an_annotation_is_checked(self):
        """A property with a modifier or a use-site annotation is a property.

        `override lateinit var` and `@get:JvmName(...) var` are properties the
        gate must see. A property it does not see is a promise it does not
        keep.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\n"
                "class Task {\n"
                "    @get:JvmName(\"name\")\n"
                "    override lateinit var name: String\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_wildcard_entry_covers_the_types_under_it(self):
        """A wildcard entry speaks about every type under it.

        The entry check accepts a wildcard for a package, and the honesty
        check must follow it there: a `var` in a class under the wildcard is
        exactly what the wildcard vouches for.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.*\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\n"
                "class Task {\n"
                "    var mutable: String = \"\"\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_single_star_entry_covers_one_package_level(self):
        """A single star reaches one package level, the way the compiler reads it.

        The double star reaches everything under it. A check that treated both
        the same would hold a type in a sub-package to a promise the compiler
        never made it.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.*\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "sub" / "Deep.kt",
                "package com.singularity.todo.feature.tasks.domain.model.sub\n\n"
                "class Deep {\n"
                "    var mutable: String = \"\"\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 0)

    def test_a_declaration_without_a_parameter_list_takes_no_body(self):
        """A declaration without a parameter list has no body either.

        `interface Api` on one line and `class Impl { … }` on the next is two
        declarations. The search for a body must stop at the line break: a
        `var` that belongs to the second declaration is not the first one's,
        and the gate must not report it twice — or report it against the
        wrong type.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Repo\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\n"
                "interface Repo\n\n"
                "class TaskRepo {\n"
                "    var mutable: String = \"\"\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 0)

    def test_a_qualified_supertype_of_another_package_is_not_ours(self):
        """A supertype written in full is that exact type.

        `com.other.pkg.Thing` is not the `Thing` of our package, even when our
        package declares one with the same simple name — and the class that
        carries it does not carry our promise.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Repo\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\ninterface Repo\n",
            )
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "core" / "other" / "Other.kt",
                "package com.singularity.todo.core.other\n\n"
                "interface Repo\n\n"
                "class OtherRepo : com.singularity.todo.core.other.Repo {\n"
                "    var mutable: String = \"\"\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 0)

    def test_an_explicit_import_shadows_the_package_of_the_file(self):
        """An explicit import wins over a declaration of the same package.

        That is how Kotlin resolves it: an explicit import has a higher
        priority than a declaration of the file's own package. The gate that
        resolved it the other way round would hold a foreign class to our
        promise.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Repo\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\ninterface Repo\n",
            )
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "core" / "other" / "Other.kt",
                f"import {PACKAGE}.Repo\n\n"
                "package com.singularity.todo.core.other\n\n"
                "class OtherRepo : Repo {\n"
                "    var mutable: String = \"\"\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_nested_type_is_found_through_an_import(self):
        """A nested type is written `Outer.Nested`, and `Outer` may be imported.

        The name of the nested type is not in the imports — the name of its
        outer type is. Cutting the name to its last segment loses that.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Outer.Leaf\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\ninterface Outer {{\n"
                "    interface Leaf\n"
                "}\n",
            )
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "core" / "other" / "Other.kt",
                f"import {PACKAGE}.Outer\n\n"
                "package com.singularity.todo.core.other\n\n"
                "class OtherLeaf : Outer.Leaf {\n"
                "    var mutable: String = \"\"\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_namesake_in_another_package_is_not_an_implementation(self):
        """A simple name says nothing about which one it is.

        Two packages may declare an interface with the same simple name. The
        one in another package is a different interface, and a class that
        carries it does not carry ours — so the promise of our entry does not
        reach it.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Repo\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\ninterface Repo\n",
            )
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "core" / "other" / "Other.kt",
                "package com.singularity.todo.core.other\n\n"
                "interface Repo\n\n"
                "class OtherRepo : Repo {\n"
                "    var mutable: String = \"\"\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 0)

    def test_an_implementation_in_another_package_is_found(self):
        """The implementation is found when the file imports the interface."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Repo\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\ninterface Repo\n",
            )
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "core" / "other" / "Other.kt",
                f"import {PACKAGE}.Repo\n\n"
                "package com.singularity.todo.core.other\n\n"
                "class OtherRepo : Repo {\n"
                "    var mutable: String = \"\"\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_use_site_annotation_on_the_same_line_is_checked(self):
        """`@get:JvmName("x") var x: String` is a property.

        The use-site target has a colon in it, and a pattern that does not
        allow the colon does not see the property at all — so the `var` behind
        it goes unchecked.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\n"
                "class Task {\n"
                "    @get:JvmName(\"name\") var name: String = \"\"\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_fun_interface_is_a_declaration(self):
        """`fun interface` is an interface, and the gate must see it."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Repo\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\n"
                "fun interface Repo {\n"
                "    fun call()\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 0)

    def test_an_inner_class_is_a_declaration(self):
        """An inner class is a class, and the gate must see it."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task.Nested\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\n"
                "class Task {\n"
                "    inner class Nested {\n"
                "        var mutable: String = \"\"\n"
                "    }\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_missing_register_is_not_a_shrunk_debt(self):
        """A register that was not read is not a register that shrank.

        The message about a shrunk debt asks for a number to be lowered, and
        lowering it to zero switches the measurement off — the debt would then
        grow unnoticed.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            self.assertEqual(
                self.run_gate(root, conf, register=root / "no-such-register.kt"),
                1,
            )

    def test_an_entry_covers_the_subclass_below_it(self):
        """The promise of a class covers the classes below it.

        The compiler matches the entry against the supertypes of a class, so an
        entry for a sealed class reaches its subclasses — and an instance of
        the sealed class may be one of them. A `var` in a subclass breaks the
        promise the contract makes for the class.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Node\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\n"
                "sealed class Node {\n"
                "    abstract val id: String\n"
                "}\n\n"
                "class Leaf : Node() {\n"
                "    override val id: String = \"\"\n"
                "    var mutable: String = \"\"\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_an_entry_that_names_an_interface_passes(self):
        """An interface is something the compiler can use.

        The compiler matches the entry against the supertypes of a class as
        well, so an entry for an interface reaches the implementations that
        carry it. Measured on 2026-10-24: an entry for `UiNode` made
        `UiNode.Checkbox` and `UiNode.TextField` stable, and the marker
        appeared in their class files.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Repo\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\ninterface Repo\n",
            )
            self.assertEqual(self.run_gate(root, conf), 0)

    def test_an_entry_that_names_an_object_fails(self):
        """An object is stable without the contract.

        The compiler judges an object stable on its own — an object is a
        singleton, so there is nothing to compare. An entry for one says
        nothing that was not already said, and it makes the reader believe the
        object needs vouching for.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Holder\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\nobject Holder\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_an_entry_that_names_a_typealias_fails(self):
        """A typealias is not a class, and the compiler matches class names.

        The entry resolves to nothing, and the contract then says less than it
        appears to.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.TaskAlias\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\ntypealias TaskAlias = Task\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_an_interface_entry_covers_the_implementation_it_reaches(self):
        """The promise of an interface covers the implementations it reaches.

        The compiler applies the entry for an interface to the classes that
        carry it, so a `var` in an implementation breaks the promise the
        contract makes for the interface. A check that looked only at the
        interface's own declarations would pass a contract that lies.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Repo\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\n"
                "interface Repo {\n"
                "    val id: String\n"
                "}\n\n"
                "class TaskRepo : Repo {\n"
                "    override val id: String = \"\"\n"
                "    var mutable: String = \"\"\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_an_interface_entry_passes_when_the_implementations_keep_it(self):
        """The positive control for the implementations: they keep the promise."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Repo\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\n"
                "interface Repo {\n"
                "    val id: String\n"
                "}\n\n"
                "class TaskRepo : Repo {\n"
                "    override val id: String = \"\"\n"
                "}\n",
            )
            self.assertEqual(self.run_gate(root, conf), 0)

    def test_an_entry_that_names_a_class_passes(self):
        """The positive control for the kind of the entry: a class is a class."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\ndata class Task(val id: String)\n",
            )
            self.assertEqual(self.run_gate(root, conf), 0)

    def test_a_contract_type_that_holds_a_mutable_collection_fails(self):
        """A collection that can change breaks the promise the contract makes.

        The compiler compares the instance by `equals`, and `equals` of a
        collection that changed is a lie about "the same content": the screen
        keeps showing the old contents.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\ndata class Task(val tags: MutableList<String>)\n",
            )
            self.assertEqual(self.run_gate(root, conf), 1)

    def test_a_contract_type_that_holds_a_read_only_collection_passes(self):
        """A read-only collection is what the contract relies on.

        `Task` holds a `List<TagId>` on purpose: the promise there is that the
        list is rebuilt on every read and never mutated afterwards. That the
        list is never mutated is not a thing this check can see — but the
        mutable spellings are, and those it rejects.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            conf = build_tree(root, f"{PACKAGE}.Task\n")
            write(
                root / "shared" / "src" / "commonMain" / "kotlin" / "com" / "singularity"
                / "todo" / "feature" / "tasks" / "domain" / "model" / "Task.kt",
                f"package {PACKAGE}\n\ndata class Task(val tags: List<String>)\n",
            )
            self.assertEqual(self.run_gate(root, conf), 0)

    def test_the_register_growing_past_the_recorded_size_fails(self):
        """The debt may not grow without somebody deciding to raise the bar."""
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
            self.assertEqual(
                self.run_gate(root, conf, register, baseline="sites = 0\nparameters = 0\n"),
                1,
            )

    def test_the_register_shrinking_below_the_recorded_size_fails(self):
        """The debt was paid — the recorded size has to say so.

        A recorded size that stays above the real one is a number nobody
        reads: the next person who reads it believes the debt is bigger than
        it is.
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
                '        mapOf("blockerTitles" to "List<String>"),\n'
                ')\n',
            )
            self.assertEqual(
                self.run_gate(root, conf, register, baseline="sites = 5\nparameters = 9\n"),
                1,
            )

    def test_the_recorded_size_matches_the_committed_register(self):
        """The positive control: the committed numbers are the committed ones."""
        debt = gate.read_register(gate.DEBT_REGISTER)
        baseline = gate.read_debt_baseline(gate.DEBT_BASELINE)
        self.assertEqual(
            gate.check_debt_size(debt, baseline),
            [],
            "the recorded size of the register of debt does not match the register",
        )

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
