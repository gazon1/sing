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
  - an empty declaration, which would make the check green about nothing.

The last test is the control in miniature: the real wrappers with the binding
call removed from ``./rm`` must leave exactly the two scripts it was covering
uncovered.
"""

import importlib.util
import json
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


def wrapper(*modules: str, binds: bool = True, before_entry: bool = True) -> str:
    """A wrapper-shaped file that reaches every module, optionally binding first."""
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
    bind = "binding.bind_local_package(root)\n" if binds else ""
    entry = "".join(f"from {module} import main\n" for module in modules)
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

    def copy_real_tree(self) -> None:
        """The real declaration and the two real wrappers, in a temp tree."""
        declaration = self.root / DECLARATION
        declaration.parent.mkdir(parents=True, exist_ok=True)
        declaration.write_text((ROOT / DECLARATION).read_text(encoding="utf-8"), encoding="utf-8")
        for name in ("sg", "rm"):
            self.wrap(name, (ROOT / name).read_text(encoding="utf-8"))

    def test_every_declared_script_with_a_bound_wrapper_has_no_findings(self) -> None:
        self.declare(INSTALL)
        self.wrap("sg", wrapper("sgate.cli"))
        self.wrap("rm", wrapper("emulator._cli", "emulator.maestro._cli"))
        self.assertEqual([], self.findings())

    def test_a_declared_script_with_no_wrapper_is_named(self) -> None:
        self.declare(INSTALL)
        self.wrap("sg", wrapper("sgate.cli"))
        findings = self.findings()
        text = "\n".join(findings)
        self.assertIn("ensure-emulator", text)
        self.assertIn("run-maestro", text)
        self.assertNotIn("sgate:", text)

    def test_a_wrapper_that_loads_the_binding_and_never_calls_it_is_not_a_wrapper(self) -> None:
        self.declare({"run-maestro": "emulator.maestro._cli:main"})
        self.wrap("rm", wrapper("emulator.maestro._cli", binds=False))
        self.assertEqual(1, len(self.findings()))
        self.assertIn("run-maestro", self.findings()[0])

    def test_a_wrapper_that_binds_after_the_entry_point_is_not_a_wrapper(self) -> None:
        self.declare({"run-maestro": "emulator.maestro._cli:main"})
        self.wrap("rm", wrapper("emulator.maestro._cli", before_entry=False))
        self.assertEqual(1, len(self.findings()))
        self.assertIn("run-maestro", self.findings()[0])

    def test_a_declaration_that_names_no_entry_point_is_a_finding(self) -> None:
        self.declare({"not-a-script": "no-colon-here"})
        self.wrap("anything", wrapper("sgate.cli"))
        findings = self.findings()
        self.assertEqual(1, len(findings))
        self.assertIn("not-a-script", findings[0])

    def test_an_empty_declaration_is_a_finding(self) -> None:
        self.declare({})
        findings = self.findings()
        self.assertEqual(1, len(findings))
        self.assertIn("declares no console scripts", findings[0])

    def test_a_missing_declaration_is_an_input_error(self) -> None:
        result = CliRunner().invoke(mod.cli, ["--root", str(self.root)])
        self.assertEqual(2, result.exit_code)

    def test_an_uncovered_script_makes_the_gate_red_and_names_it(self) -> None:
        self.declare({"run-maestro": "emulator.maestro._cli:main"})
        result = CliRunner().invoke(mod.cli, ["--root", str(self.root)])
        self.assertEqual(1, result.exit_code)
        self.assertIn("run-maestro", result.output)

    def test_the_json_document_says_passed_when_every_script_is_covered(self) -> None:
        self.declare({"sgate": "sgate.cli:main"})
        self.wrap("sg", wrapper("sgate.cli"))
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
        self.assertIn("binding.bind_local_package(root)", rm)
        (self.root / "rm").write_text(
            rm.replace("binding.bind_local_package(root)\n", "", 1),
            encoding="utf-8",
        )

        text = "\n".join(self.findings())
        self.assertIn("ensure-emulator", text)
        self.assertIn("run-maestro", text)
        self.assertNotIn("sgate:", text)


if __name__ == "__main__":
    unittest.main()
