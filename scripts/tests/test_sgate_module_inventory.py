"""Tests for the inventory of the `sgate` package.

Two failures live here, and both are the same kind of failure: the package
stopped meaning what its files say.

**A module that is shadowed.** A package directory and a module file of the
same name in one directory — `sgate/gates/` and `sgate/gates.py` — and the
package wins: the module file is never imported, and everything that imported
a name from it dies with `cannot import name ... from 'sgate.gates'`. The
registry of the gates was that module. Nothing noticed, because nothing
imported *every* module: the only walk over the package covered `commands/`.

**An inventory written by hand.** The package docstring listed its internal
modules, and the list was wrong: the modules that came later were not in it.
A list that is written by hand and read by nobody is a list that is wrong;
this test reads the real inventory instead, and the catalogue
(`docs/tools/catalog.md`) renders it from the files themselves.

Run with: python3 -m unittest discover -s scripts/tests
"""

import importlib
import importlib.util
import pathlib
import unittest

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
_PACKAGE_ROOT = _ROOT / "scripts" / "src" / "sgate"


def _module_files() -> list[tuple[str, pathlib.Path]]:
    """Every module of the package, as (dotted name, file)."""
    found: list[tuple[str, pathlib.Path]] = []
    for path in sorted(_PACKAGE_ROOT.rglob("*.py")):
        relative = path.relative_to(_PACKAGE_ROOT)
        parts = list(relative.parts)
        if parts[-1] == "__init__.py":
            parts = parts[:-1]
        else:
            parts[-1] = parts[-1][: -len(".py")]
        if not parts:
            continue
        found.append((".".join(["sgate", *parts]), path))
    return found


class TestEveryModuleImports(unittest.TestCase):
    """Every module of the package imports, and imports as itself."""

    def test_the_package_has_modules(self):
        """A package with no modules is not a package — the test would pass."""
        self.assertGreater(len(_module_files()), 10)

    def test_every_module_imports(self):
        """A module with a syntax error or a broken import is found here."""
        for name, path in _module_files():
            with self.subTest(module=name):
                try:
                    importlib.import_module(name)
                except Exception as error:  # noqa: BLE001 — the point is to report it
                    self.fail(f"{name} does not import: {error!r}")

    def test_no_module_is_shadowed(self):
        """A module file is the module, not a package that hides it.

        A directory `sgate/gates/` next to `sgate/gates.py` wins the name: the
        module file is dead code, and the imports that reached for it fail with
        a name error that says nothing about the directory.
        """
        for name, path in _module_files():
            with self.subTest(module=name):
                spec = importlib.util.find_spec(name)
                self.assertIsNotNone(spec, f"{name} is not importable at all")
                origin = pathlib.Path(spec.origin).resolve() if spec.origin else None
                self.assertEqual(
                    origin,
                    path.resolve(),
                    f"{name} resolves to {origin}, not to {path} — something shadows it",
                )


class TestThePublicApi(unittest.TestCase):
    """The public names of the package are the ones the package says."""

    def test_every_public_name_exists(self):
        import sgate

        for name in sgate.__all__:
            with self.subTest(name=name):
                self.assertTrue(hasattr(sgate, name), f"sgate.{name} does not exist")

    def test_the_package_does_not_enumerate_its_modules_by_hand(self):
        """The inventory is generated; the docstring points at the catalogue.

        A hand-written list of modules is a list that loses a module: the one
        in this docstring had been missing `gatekit`, `controls`,
        `gate_manifest`, `models` and `gate_reachability` for weeks.
        """
        import sgate

        docstring = sgate.__doc__ or ""
        self.assertNotIn(
            "Internal modules",
            docstring,
            "the package docstring enumerates its modules by hand; the catalogue does it",
        )


if __name__ == "__main__":
    unittest.main()
