"""Tests for check-console-script-wrappers.py — every console script gets a bound wrapper.

The gate exists because the install's console scripts were covered by accident:
``sgate`` had ``./sg`` and the Maestro lane got ``./rm``, but nothing read the
declaration, so the next script added to it would be exposed to whichever
checkout was installed last — the failure ``run-maestro`` had.

Each test below breaks one part of the rule and expects the gate to say so:

  - a declared script with no wrapper at all;
  - a wrapper that loads the binding and never calls it;
  - a wrapper that calls the binding after the entry point has already run;
  - a declaration that cannot name an entry point;
  - an empty declaration, which would make the check green about nothing;
  - a *mention* of the entry import, or of the binding call, in a comment —
    prose is not a statement, in either direction;
  - two scripts that share one entry module: two claims, not one;
  - two scripts that share one *entry point*: the pair is rejected, and the
    finding names both of them;
  - a wrapper that loads a fixed foreign binding path, which binds that tree;
  - a wrapper that names ``package-binding.py`` in a literal it never hands to
    a loader, which has loaded nothing;
  - a wrapper that loads the binding with ``runpy.run_path``, which is the
    other by-path loader and is covered like ``spec_from_file_location``;
  - a script that lost its executable bit, which is not a script that is gone;
  - an unreadable root-level script, which is an input error, not a finding.

The last test is the control in miniature: the real wrappers with the binding
call removed from ``./rm`` must leave exactly the two scripts it was covering
uncovered.
"""

import importlib.util
import json
import os
import pathlib
import tempfile
import unittest

from click.testing import CliRunner

ROOT = pathlib.Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts" / "check-console-script-wrappers.py"
_spec = importlib.util.spec_from_file_location("check_console_script_wrappers", MODULE_PATH)
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)

DECLARATION = pathlib.Path("scripts") / "src" / "pyproject.toml"

#: The three scripts the real install declares.
INSTALL = {
    "sgate": "sgate.cli:main",
    "ensure-emulator": "emulator._cli:main",
    "run-maestro": "emulator.maestro._cli:main",
}

#: The line the registry's sabotage control removes from ``./rm``.
BIND_STATEMENT = "binding.bind_local_package(root)\n"


def _module(point: str) -> str:
    """The module half of a ``module:attribute`` entry point."""
    return point.split(":", 1)[0]


def _attribute(point: str) -> str:
    """The attribute half of a ``module:attribute`` entry point (``main`` when unspelled)."""
    return point.split(":", 1)[1] if ":" in point else "main"


def wrapper(*entry_points: str, binds: bool = True, before_entry: bool = True) -> str:
    """A wrapper-shaped file that reaches every entry point, optionally binding first."""
    header = (
        "#!/usr/bin/env python3\n"
        "import importlib.util\n"
        "import pathlib\n"
        "root = pathlib.Path(__file__).parent\n"
    )
    load = (
        "spec = importlib.util.spec_from_file_location(\n"
        '    "package_binding", root / "scripts" / "package-binding.py"\n'
        ")\n"
        "binding = importlib.util.module_from_spec(spec)\n"
        "spec.loader.exec_module(binding)\n"
    )
    bind = BIND_STATEMENT if binds else ""
    entry = "".join(
        f"from {_module(point)} import {_attribute(point)}\n" for point in entry_points
    )
    body = f"{load}{bind}{entry}" if before_entry else f"{load}{entry}{bind}"
    return f"{header}{body}main()\n"


class ConsoleScriptWrappersTest(unittest.TestCase):
    """The declaration decides which scripts must be covered, and the shape does the rest."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="check-console-script-wrappers-")
        self.addCleanup(self._tmp.cleanup)
        self.root = pathlib.Path(self._tmp.name)

    def declare(self, scripts: dict[str, str]) -> None:
        declaration = self.root / DECLARATION
        declaration.parent.mkdir(parents=True, exist_ok=True)
        lines = ["[project]", 'name = "singularity-repo-tools"', "", "[project.scripts]"]
        lines.extend(f'{name} = "{target}"' for name, target in scripts.items())
        declaration.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def wrap(self, name: str, text: str) -> None:
        path = self.root / name
        path.write_text(text, encoding="utf-8")
        path.chmod(0o755)

    def findings(self) -> list[str]:
        _scripts, findings = mod.scan(self.root)
        return findings

    def verdict(self) -> list[str]:
        """The findings, asserting that the gate reported *some*."""
        findings = self.findings()
        self.assertTrue(findings, "the gate stayed green about a wrapper that does not bind")
        return findings

    def copy_real_tree(self) -> None:
        """The real declaration and the two real wrappers, in a temp tree."""
        declaration = self.root / DECLARATION
        declaration.parent.mkdir(parents=True, exist_ok=True)
        declaration.write_text((ROOT / DECLARATION).read_text(encoding="utf-8"), encoding="utf-8")
        for name in ("sg", "rm"):
            self.wrap(name, (ROOT / name).read_text(encoding="utf-8"))

    def test_every_declared_script_with_a_bound_wrapper_has_no_findings(self) -> None:
        self.declare(INSTALL)
        self.wrap("sg", wrapper("sgate.cli:main"))
        self.wrap("rm", wrapper("emulator._cli:main", "emulator.maestro._cli:main"))
        self.assertEqual([], self.findings())

    def test_a_declared_script_with_no_wrapper_is_named(self) -> None:
        self.declare(INSTALL)
        self.wrap("sg", wrapper("sgate.cli:main"))
        findings = self.findings()
        text = "\n".join(findings)
        self.assertIn("ensure-emulator", text)
        self.assertIn("run-maestro", text)
        self.assertNotIn("sgate:", text)

    def test_a_wrapper_that_loads_the_binding_and_never_calls_it_is_not_a_wrapper(self) -> None:
        self.declare({"run-maestro": "emulator.maestro._cli:main"})
        self.wrap("rm", wrapper("emulator.maestro._cli:main", binds=False))
        findings = self.verdict()
        self.assertEqual(1, len(findings))
        self.assertIn("run-maestro", findings[0])

    def test_a_wrapper_that_binds_after_the_entry_point_is_not_a_wrapper(self) -> None:
        self.declare({"run-maestro": "emulator.maestro._cli:main"})
        self.wrap("rm", wrapper("emulator.maestro._cli:main", before_entry=False))
        findings = self.verdict()
        self.assertEqual(1, len(findings))
        self.assertIn("run-maestro", findings[0])

    def test_a_declaration_that_names_no_entry_point_is_a_finding(self) -> None:
        self.declare({"not-a-script": "no-colon-here"})
        self.wrap("anything", wrapper("sgate.cli:main"))
        findings = self.findings()
        self.assertEqual(1, len(findings))
        self.assertIn("not-a-script", findings[0])

    def test_an_empty_declaration_is_a_finding(self) -> None:
        self.declare({})
        findings = self.findings()
        self.assertEqual(1, len(findings))
        self.assertIn("declares no console scripts", findings[0])

    def test_a_comment_naming_the_entry_import_does_not_move_the_entry_point(self) -> None:
        """The docstring promises the import statement, not a mention of one.

        ``./rm`` talks about the binding in prose. A wrapper that does the same
        about its entry point — before the binding call — is still a wrapper
        that binds first, and the old text search read the prose as the run.
        """
        self.declare({"run-maestro": "emulator.maestro._cli:main"})
        text = wrapper("emulator.maestro._cli:main").replace(
            BIND_STATEMENT,
            "# nothing is reached yet: from emulator.maestro._cli import main comes later\n"
            f"{BIND_STATEMENT}",
            1,
        )
        self.assertLess(
            text.find("# nothing is reached yet"),
            text.find(BIND_STATEMENT),
            "the fixture has to put the mention *before* the binding call",
        )
        self.wrap("rm", text)
        self.assertEqual([], self.findings())

    def test_an_entry_point_named_only_in_a_comment_has_not_been_reached(self) -> None:
        """The other direction: a name in prose is not a run."""
        self.declare({"run-maestro": "emulator.maestro._cli:main"})
        text = wrapper("emulator.maestro._cli:main").replace(
            "from emulator.maestro._cli import main\n",
            "# from emulator.maestro._cli import main\n",
            1,
        )
        self.wrap("rm", text)
        self.assertIn("run-maestro", "\n".join(self.verdict()))

    def test_a_comment_naming_the_binding_call_is_not_a_call(self) -> None:
        """The registry's control removes the call; a leftover comment must not cover it."""
        self.declare({"run-maestro": "emulator.maestro._cli:main"})
        text = wrapper("emulator.maestro._cli:main", binds=False).replace(
            "spec.loader.exec_module(binding)\n",
            "spec.loader.exec_module(binding)\n"
            "# binding.bind_local_package(root) belongs here, and is missing\n",
            1,
        )
        self.wrap("rm", text)
        findings = self.verdict()
        self.assertEqual(1, len(findings))
        self.assertIn("run-maestro", findings[0])

    def test_two_scripts_that_share_a_module_are_covered_one_at_a_time(self) -> None:
        """Coverage is per script: reaching ``main`` has not reached ``other``."""
        self.declare({"foo": "pkg.cli:main", "bar": "pkg.cli:other"})
        self.wrap("rm", wrapper("pkg.cli:main"))
        findings = self.findings()
        self.assertEqual(1, len(findings), findings)
        self.assertIn("bar", findings[0])
        self.assertNotIn("foo:", findings[0])

    def test_two_scripts_that_share_an_entry_point_are_rejected(self) -> None:
        """Two names on one entry point: the pair is a finding, and both are named.

        A wrapper's shape says which entry point it reaches, not which script
        it serves, so the two names on ``PATH`` cannot be told apart — and the
        second one would answer with whichever checkout was installed last.
        """
        self.declare({"sgate": "sgate.cli:main", "sgate-debug": "sgate.cli:main"})
        self.wrap("sg", wrapper("sgate.cli:main"))
        findings = self.findings()
        self.assertEqual(1, len(findings), findings)
        text = findings[0]
        # The names, not the module: "sgate" is a substring of "sgate-debug" and
        # of "sgate.cli:main", so a plain contains() would pass on either.
        self.assertRegex(text, r"(?<![\w.-])sgate(?![\w.-])")
        self.assertRegex(text, r"(?<![\w.-])sgate-debug(?![\w.-])")
        self.assertIn("sgate.cli:main", text)

    def test_a_shared_entry_point_no_wrapper_reaches_is_still_one_finding(self) -> None:
        """The pair is one finding, not one per script and not one per wrapper."""
        self.declare({"foo": "pkg.cli:main", "bar": "pkg.cli:main"})
        findings = self.findings()
        self.assertEqual(1, len(findings), findings)
        self.assertRegex(findings[0], r"(?<![\w.-])foo(?![\w.-])")
        self.assertRegex(findings[0], r"(?<![\w.-])bar(?![\w.-])")

    def test_a_shared_entry_point_makes_the_gate_red_and_names_both_scripts(self) -> None:
        """The reviewer's case: a second name on a covered entry point."""
        self.declare({"sgate": "sgate.cli:main", "sgate-debug": "sgate.cli:main"})
        self.wrap("sg", wrapper("sgate.cli:main"))
        result = CliRunner().invoke(mod.cli, ["--root", str(self.root)])
        self.assertEqual(1, result.exit_code)
        self.assertRegex(result.output, r"(?<![\w.-])sgate(?![\w.-])")
        self.assertRegex(result.output, r"(?<![\w.-])sgate-debug(?![\w.-])")

    def test_a_fixed_foreign_binding_path_is_not_this_checkout_binding(self) -> None:
        """A wrapper that binds another tree correctly has bound the wrong tree."""
        self.declare({"run-maestro": "emulator.maestro._cli:main"})
        cases = {
            "an absolute path": '"/other-clone/scripts/package-binding.py"',
            "a foreign root joined to a relative path": (
                'os.path.join("/other-clone", "scripts", "package-binding.py")'
            ),
        }
        for description, expression in cases.items():
            with self.subTest(description):
                self.wrap(
                    "rm",
                    "#!/usr/bin/env python3\n"
                    "import importlib.util\n"
                    "import os\n"
                    "spec = importlib.util.spec_from_file_location(\n"
                    f'    "package_binding", {expression}\n'
                    ")\n"
                    "binding = importlib.util.module_from_spec(spec)\n"
                    "spec.loader.exec_module(binding)\n"
                    f"{BIND_STATEMENT}"
                    "from emulator.maestro._cli import main\n"
                    "main()\n",
                )
                self.assertIn("run-maestro", "\n".join(self.verdict()))

    def test_a_module_named_beside_the_loader_is_not_the_path_it_is_handed(self) -> None:
        """The path has to be the literal handed to the load call.

        The decoy names the binding module, is relative, and is built from the
        wrapper's own directory — everything the old whole-file search wanted.
        The loader is handed a foreign absolute path, so nothing here binds this
        checkout, and a gate that reads the first matching literal anywhere in
        the file reports the opposite.
        """
        self.declare({"run-maestro": "emulator.maestro._cli:main"})
        self.wrap(
            "rm",
            "#!/usr/bin/env python3\n"
            "import importlib.util\n"
            "import os\n"
            "root = os.path.dirname(os.path.abspath(__file__))\n"
            'note = os.path.join(root, "scripts", "package-binding.py")\n'
            "spec = importlib.util.spec_from_file_location(\n"
            '    "package_binding", "/other-clone/scripts/package-binding.py"\n'
            ")\n"
            "binding = importlib.util.module_from_spec(spec)\n"
            "spec.loader.exec_module(binding)\n"
            f"{BIND_STATEMENT}"
            "from emulator.maestro._cli import main\n"
            "main()\n",
        )
        self.assertIn("run-maestro", "\n".join(self.verdict()))

    def test_runpy_run_path_is_a_recognised_loader(self) -> None:
        """The other by-path loader is ``runpy.run_path``, and the header says so."""
        self.declare({"run-maestro": "emulator.maestro._cli:main"})
        self.wrap(
            "rm",
            "#!/usr/bin/env python3\n"
            "import pathlib\n"
            "import runpy\n"
            "root = pathlib.Path(__file__).parent\n"
            'namespace = runpy.run_path(str(root / "scripts" / "package-binding.py"))\n'
            'bind_local_package = namespace["bind_local_package"]\n'
            "bind_local_package(root)\n"
            "from emulator.maestro._cli import main\n"
            "main()\n",
        )
        self.assertEqual([], self.findings())

    def test_run_path_from_something_else_is_not_a_by_path_loader(self) -> None:
        """``run_path`` is a loader only when it is ``runpy``'s."""
        self.declare({"run-maestro": "emulator.maestro._cli:main"})
        self.wrap(
            "rm",
            "#!/usr/bin/env python3\n"
            "import pathlib\n"
            "from joblib import run_path\n"
            "root = pathlib.Path(__file__).parent\n"
            'namespace = run_path(str(root / "scripts" / "package-binding.py"))\n'
            f"{BIND_STATEMENT}"
            "from emulator.maestro._cli import main\n"
            "main()\n",
        )
        self.assertIn("run-maestro", "\n".join(self.verdict()))

    def test_the_executable_bit_does_not_decide_coverage(self) -> None:
        """A ``noexec`` mount, or a bit git lost, must not read as 'no wrappers here'."""
        self.declare({"sgate": "sgate.cli:main"})
        path = self.root / "sg"
        path.write_text(wrapper("sgate.cli:main"), encoding="utf-8")
        path.chmod(0o644)
        self.assertEqual([], self.findings())

    def test_a_root_script_that_is_not_a_script_is_not_a_wrapper(self) -> None:
        """Recognition is by content: prose that happens to sit at the root binds nothing."""
        self.declare({"run-maestro": "emulator.maestro._cli:main"})
        (self.root / "NOTES.md").write_text(
            wrapper("emulator.maestro._cli:main").replace("#!/usr/bin/env python3\n", ""),
            encoding="utf-8",
        )
        self.assertIn("run-maestro", "\n".join(self.verdict()))

    def test_a_missing_declaration_is_an_input_error(self) -> None:
        result = CliRunner().invoke(mod.cli, ["--root", str(self.root)])
        self.assertEqual(2, result.exit_code)

    def test_an_unreadable_root_script_is_an_input_error(self) -> None:
        """A wrapper nobody can read is not a wrapper that is missing (exit 1)."""
        if os.geteuid() == 0:
            self.skipTest("root reads every file — the permission is not in the way")
        self.declare({"sgate": "sgate.cli:main"})
        self.wrap("sg", wrapper("sgate.cli:main"))
        unreadable = self.root / "sgate-wrapper"
        unreadable.write_text("#!/usr/bin/env python3\n", encoding="utf-8")
        unreadable.chmod(0o000)
        try:
            result = CliRunner().invoke(mod.cli, ["--root", str(self.root)])
        finally:
            unreadable.chmod(0o644)
        self.assertEqual(2, result.exit_code, result.output)

    def test_an_uncovered_script_makes_the_gate_red_and_names_it(self) -> None:
        self.declare({"run-maestro": "emulator.maestro._cli:main"})
        result = CliRunner().invoke(mod.cli, ["--root", str(self.root)])
        self.assertEqual(1, result.exit_code)
        self.assertIn("run-maestro", result.output)

    def test_the_json_document_says_passed_when_every_script_is_covered(self) -> None:
        self.declare({"sgate": "sgate.cli:main"})
        self.wrap("sg", wrapper("sgate.cli:main"))
        result = CliRunner().invoke(mod.cli, ["--root", str(self.root), "--json"])
        self.assertEqual(0, result.exit_code)
        self.assertTrue(json.loads(result.output)["passed"])

    def test_the_repository_itself_is_covered(self) -> None:
        """The tree this gate ships in must pass its own rule."""
        _scripts, findings = mod.scan(ROOT)
        self.assertEqual([], findings)

    def test_removing_the_binding_call_from_rm_names_the_scripts_it_exposed(self) -> None:
        """The registry's sabotage control, in miniature.

        ``./rm`` covers ``ensure-emulator`` and ``run-maestro``. Take its
        binding call away and those two — and only those two — become findings;
        ``./sg`` still covers ``sgate``.
        """
        self.copy_real_tree()
        rm = (self.root / "rm").read_text(encoding="utf-8")
        self.assertIn(BIND_STATEMENT, rm)
        (self.root / "rm").write_text(rm.replace(BIND_STATEMENT, "", 1), encoding="utf-8")

        text = "\n".join(self.findings())
        self.assertIn("ensure-emulator", text)
        self.assertIn("run-maestro", text)
        self.assertNotIn("sgate:", text)


if __name__ == "__main__":
    unittest.main()
